package com.yrf3.photocopilot.reference;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Small synchronous API client; callers perform its work on their existing IO executor. */
public final class ReferenceClient {
    private static final int LIMIT = 6;
    private static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 256 * 1024;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}");

    private final URI base;
    private final Object lock = new Object();
    private HttpURLConnection active;
    private int generation;
    private boolean closed;

    public ReferenceClient(String baseUrl) {
        try {
            URI supplied = new URI(baseUrl);
            String path = supplied.getPath();
            if (!isHttp(supplied) || supplied.getHost() == null || supplied.getUserInfo() != null
                    || supplied.getQuery() != null || supplied.getFragment() != null
                    || (path != null && !path.isEmpty() && !path.equals("/"))
                    || supplied.getPort() > 65535 || supplied.getPort() == 0) {
                throw new IllegalArgumentException("Use the API's HTTP or HTTPS root URL");
            }
            base = new URI(supplied.getScheme(), null, supplied.getHost(), supplied.getPort(),
                    "/", null, null);
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IllegalArgumentException("Invalid API URL", exception);
        }
    }

    public List<ReferencePhoto> recommend(byte[] jpeg) throws IOException {
        if (jpeg == null || jpeg.length == 0 || jpeg.length > MAX_UPLOAD_BYTES) {
            throw new IOException("Invalid camera snapshot size");
        }
        int request = begin();
        String boundary = "PhotoCopilot-" + UUID.randomUUID();
        byte[] header = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"limit\"\r\n\r\n" + LIMIT + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"snapshot.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] ending = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        HttpURLConnection connection = open(base.resolve("recommend"), request);
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Accept", "application/json");
            connection.setFixedLengthStreamingMode(header.length + jpeg.length + ending.length);
            try (OutputStream output = connection.getOutputStream()) {
                check(request);
                output.write(header);
                output.write(jpeg);
                output.write(ending);
            }
            requireSuccess(connection, request);
            byte[] response = read(connection, MAX_JSON_BYTES, request);
            List<ReferencePhoto> result = parse(response);
            check(request);
            return result;
        } finally {
            finish(connection);
        }
    }

    public byte[] fetchImage(Uri image) throws IOException {
        int request = begin();
        URI target;
        try {
            target = new URI(image.toString());
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IOException("Invalid reference image URL", exception);
        }
        validateImage(target, null);
        HttpURLConnection connection = open(target, request);
        try {
            connection.setRequestProperty("Accept", "image/*");
            requireSuccess(connection, request);
            return read(connection, MAX_IMAGE_BYTES, request);
        } finally {
            finish(connection);
        }
    }

    private List<ReferencePhoto> parse(byte[] bytes) throws IOException {
        try {
            JSONObject response = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray results = response.getJSONArray("results");
            int returned = count(response, "returned");
            int candidates = count(response, "candidate_count");
            if (returned != results.length() || returned > LIMIT || candidates < returned) {
                throw new IOException("Invalid recommendation count");
            }
            List<ReferencePhoto> photos = new ArrayList<>(returned);
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < results.length(); index++) {
                JSONObject item = results.getJSONObject(index);
                String id = string(item, "id");
                if (!ID.matcher(id).matches() || !seen.add(id)) {
                    throw new IOException("Invalid or duplicate reference ID");
                }
                String imagePath = string(item, "image_path");
                if (imagePath.length() > 2048) throw new IOException("Invalid image path");
                URI target = base.resolve(new URI(imagePath));
                validateImage(target, id);
                JSONArray reasons = item.getJSONArray("reference_reasons");
                if (reasons.length() > 16) throw new IOException("Invalid reference reasons");
                List<String> descriptions = new ArrayList<>();
                for (int reasonIndex = 0; reasonIndex < reasons.length(); reasonIndex++) {
                    Object reason = reasons.get(reasonIndex);
                    if (!(reason instanceof String) || ((String) reason).length() > 512) {
                        throw new IOException("Invalid reference reason");
                    }
                    String description = ((String) reason).trim();
                    if (!description.isEmpty()) descriptions.add(description);
                }
                photos.add(new ReferencePhoto(id, Uri.parse(target.toASCIIString()),
                        descriptions.isEmpty() ? id : String.join(", ", descriptions)));
            }
            return photos;
        } catch (JSONException | URISyntaxException | IllegalArgumentException exception) {
            throw new IOException("Invalid recommendation response", exception);
        }
    }

    private static int count(JSONObject object, String name) throws JSONException, IOException {
        Object value = object.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IOException("Invalid recommendation count");
        }
        long number = ((Number) value).longValue();
        if (number < 0 || number > Integer.MAX_VALUE) throw new IOException("Invalid recommendation count");
        return (int) number;
    }

    private static String string(JSONObject object, String name) throws JSONException, IOException {
        Object value = object.get(name);
        if (!(value instanceof String)) throw new IOException("Invalid recommendation field");
        return (String) value;
    }

    private void validateImage(URI target, String id) throws IOException {
        if (!isHttp(target) || !base.getScheme().equalsIgnoreCase(target.getScheme())
                || target.getHost() == null || !base.getHost().equalsIgnoreCase(target.getHost())
                || port(base) != port(target) || target.getUserInfo() != null
                || target.getQuery() != null || target.getFragment() != null) {
            throw new IOException("Reference URL must use the API origin");
        }
        String path = target.getRawPath();
        if (path == null || !path.startsWith("/images/")
                || !ID.matcher(path.substring("/images/".length())).matches()
                || (id != null && !path.equals("/images/" + id))) {
            throw new IOException("Invalid reference image path");
        }
    }

    private static boolean isHttp(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
    }

    private static int port(URI uri) {
        return uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }

    private int begin() throws IOException {
        synchronized (lock) {
            check(generation);
            return generation;
        }
    }

    private HttpURLConnection open(URI target, int request) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-store");
        synchronized (lock) {
            check(request);
            if (active != null) throw new IOException("A reference request is already active");
            active = connection;
        }
        return connection;
    }

    private void requireSuccess(HttpURLConnection connection, int request) throws IOException {
        check(request);
        int status = connection.getResponseCode();
        check(request);
        if (status != HttpURLConnection.HTTP_OK) throw new IOException("Reference API HTTP " + status);
    }

    private byte[] read(HttpURLConnection connection, int limit, int request) throws IOException {
        if (connection.getContentLengthLong() > limit) throw new IOException("Reference response exceeds limit");
        try (InputStream input = connection.getInputStream();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) {
                check(request);
                if (size > limit - output.size()) throw new IOException("Reference response exceeds limit");
                output.write(buffer, 0, size);
            }
            check(request);
            return output.toByteArray();
        }
    }

    private void check(int request) throws IOException {
        synchronized (lock) {
            if (closed || request != generation || Thread.currentThread().isInterrupted()) {
                throw new IOException("Reference request canceled");
            }
        }
    }

    private void finish(HttpURLConnection connection) {
        synchronized (lock) {
            if (active == connection) active = null;
        }
        connection.disconnect();
    }

    public void cancel() {
        HttpURLConnection connection;
        synchronized (lock) {
            generation++;
            connection = active;
            active = null;
        }
        if (connection != null) connection.disconnect();
    }

    public void close() {
        synchronized (lock) {
            closed = true;
        }
        cancel();
    }
}

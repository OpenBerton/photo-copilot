package com.yrf3.photocopilot.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Creates, publishes or removes a single photo; all methods run off the UI thread. */
final class PhotoStore {
    // Process-wide so a short save can finish even when its Activity is recreated.
    static final Executor IO = Executors.newSingleThreadExecutor();
    private final ContentResolver resolver;

    static final class PendingPhoto {
        final Uri uri;
        final OutputStream stream;

        PendingPhoto(Uri uri, OutputStream stream) {
            this.uri = uri;
            this.stream = stream;
        }
    }

    PhotoStore(Context context) {
        resolver = context.getApplicationContext().getContentResolver();
    }

    PendingPhoto create() throws IOException {
        String name = "PhotoCopilot_" + UUID.randomUUID() + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/PhotoCopilot");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "PhotoCopilot");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot create photo directory");
            }
            values.put(MediaStore.Images.Media.DATA, new File(directory, name).getAbsolutePath());
        }
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Cannot create photo entry");
        }
        try {
            OutputStream stream = resolver.openOutputStream(uri, "w");
            if (stream == null) {
                throw new IOException("Cannot open photo output");
            }
            return new PendingPhoto(uri, stream);
        } catch (IOException | RuntimeException exception) {
            remove(uri);
            throw exception;
        }
    }

    void publish(PendingPhoto photo) throws IOException {
        photo.stream.close();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(photo.uri, values, null, null) != 1) {
                throw new IOException("Cannot publish photo");
            }
        }
    }

    void discard(PendingPhoto photo) {
        try {
            photo.stream.close();
        } catch (IOException | RuntimeException exception) {
            Log.w("PhotoStore", "Cannot close failed photo", exception);
        }
        remove(photo.uri);
    }

    private void remove(Uri uri) {
        try {
            resolver.delete(uri, null, null);
        } catch (RuntimeException exception) {
            Log.w("PhotoStore", "Cannot remove incomplete photo", exception);
        }
    }
}

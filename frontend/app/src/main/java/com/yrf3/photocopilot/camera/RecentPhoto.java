package com.yrf3.photocopilot.camera;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.util.Size;
import android.widget.ImageButton;

import androidx.core.content.ContextCompat;

import com.yrf3.photocopilot.R;

import java.io.IOException;
import java.io.InputStream;

/** Loads only this app's last saved URI; it never scans or requests access to the gallery. */
public final class RecentPhoto {
    static final String PREFERENCES = "recent_photo";
    static final String URI_KEY = "uri";
    private final Context activity;
    private final Context app;
    private final ImageButton button;
    private final Runnable cannotOpen;
    private Uri displayedUri;
    private int generation;
    private boolean closed;

    public RecentPhoto(Context context, ImageButton button, Runnable cannotOpen) {
        activity = context;
        app = context.getApplicationContext();
        this.button = button;
        button.setClipToOutline(true);
        this.cannotOpen = cannotOpen;
        button.setOnClickListener(view -> {
            if (displayedUri == null) return;
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(displayedUri, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Photo", displayedUri));
            try {
                activity.startActivity(intent);
            } catch (ActivityNotFoundException | SecurityException exception) {
                cannotOpen.run();
            }
        });
    }

    public void reload() {
        String saved = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(URI_KEY, null);
        if (saved != null) show(Uri.parse(saved), false);
    }

    public void show(Uri uri, boolean animate) {
        int request = ++generation;
        PhotoStore.IO.execute(() -> {
            Bitmap image;
            try {
                image = load(uri);
            } catch (IOException | RuntimeException exception) {
                image = null;
            }
            Bitmap loaded = image;
            ContextCompat.getMainExecutor(app).execute(() -> {
                if (closed || request != generation) {
                    if (loaded != null) loaded.recycle();
                    return;
                }
                displayedUri = loaded == null ? null : uri;
                button.setEnabled(loaded != null);
                button.setAlpha(loaded == null ? 0.35f : 1f);
                if (loaded == null) button.setImageResource(R.drawable.ic_photo);
                else {
                    button.setImageBitmap(loaded);
                    if (animate) {
                        button.setScaleX(0.85f);
                        button.setScaleY(0.85f);
                        button.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                    }
                }
            });
        });
    }

    private Bitmap load(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return app.getContentResolver().loadThumbnail(uri, new Size(192, 192), null);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = app.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Photo unavailable");
            BitmapFactory.decodeStream(input, null, options);
        }
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 384
                || options.outHeight / options.inSampleSize > 384) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap;
        try (InputStream input = app.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IOException("Invalid photo");
        Matrix rotation = new Matrix();
        try (InputStream input = app.getContentResolver().openInputStream(uri)) {
            int orientation = new ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation.setRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation.setRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation.setRotate(270); break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: rotation.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: rotation.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    rotation.setRotate(90); rotation.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    rotation.setRotate(270); rotation.postScale(-1, 1); break;
                default: break;
            }
        } catch (IOException exception) {
            // A valid decoded image can still be shown if optional EXIF metadata is missing.
        }
        if (!rotation.isIdentity()) {
            Bitmap upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotation, true);
            if (upright != bitmap) bitmap.recycle();
            return upright;
        }
        return bitmap;
    }

    public void close() {
        closed = true;
        generation++;
        button.animate().cancel();
    }
}

package com.yrf3.photocopilot.reference;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.yrf3.photocopilot.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** User-triggered preview recommendation and the existing reference selection sheet. */
public final class ReferencePicker {
    public interface SnapshotSource { Bitmap capture(); }

    private final Activity activity;
    private final ImageButton entry;
    private final Drawable entryBackground;
    private final ReferenceClient client;
    private final SnapshotSource snapshots;
    private final List<ReferencePhoto> photos = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Executor main;
    private final Map<String, Bitmap> thumbnails = new HashMap<>();
    private final Map<String, ImageButton> cards = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private Dialog dialog;
    private LinearLayout row;
    private Button clear;
    private Button retry;
    private TextView status;
    private View progress;
    private Future<?> task;
    private String selectedId;
    private boolean loading;
    private int error;
    private volatile int generation;
    private volatile boolean closed;

    public ReferencePicker(Activity activity, ImageButton entry, ReferenceClient client,
                           SnapshotSource snapshots, Bundle state) {
        this.activity = activity;
        this.entry = entry;
        this.client = client;
        this.snapshots = snapshots;
        entryBackground = entry.getBackground();
        main = ContextCompat.getMainExecutor(activity);
        restoreState(state);
        entry.setOnClickListener(view -> show());
        renderEntry();
        ReferencePhoto selected = find(selectedId);
        if (selected != null) {
            int request = generation;
            task = io.submit(() -> loadImages(request, Collections.singletonList(selected)));
        }
    }

    public boolean isShowing() { return dialog != null && dialog.isShowing(); }

    public void setReady(boolean ready) {
        entry.setEnabled(ready);
        entry.setAlpha(ready ? 1f : 0.35f);
    }

    public void show() {
        if (openSheet()) request();
    }

    /** Restore results after rotation without uploading an old frame. */
    public void showRestored() {
        if (!openSheet()) return;
        if (photos.isEmpty()) request();
        else {
            rebuildCards();
            int request = generation;
            List<ReferencePhoto> restored = new ArrayList<>(photos);
            task = io.submit(() -> loadImages(request, restored));
        }
    }

    private boolean openSheet() {
        if (closed || isShowing() || activity.isFinishing() || activity.isDestroyed()) return false;
        Dialog sheet = new Dialog(activity, R.style.Theme_PhotoCopilot_ReferenceSheet);
        dialog = sheet;
        sheet.setOwnerActivity(activity);
        sheet.setCanceledOnTouchOutside(true);
        sheet.setContentView(R.layout.reference_sheet);
        row = sheet.findViewById(R.id.reference_photos);
        clear = sheet.findViewById(R.id.clear_reference);
        retry = sheet.findViewById(R.id.retry_references);
        status = sheet.findViewById(R.id.reference_status);
        progress = sheet.findViewById(R.id.reference_progress);
        clear.setOnClickListener(view -> {
            selectedId = null;
            renderEntry();
            sheet.dismiss();
        });
        retry.setOnClickListener(view -> request());
        sheet.findViewById(R.id.close_references).setOnClickListener(view -> sheet.dismiss());
        View panel = sheet.findViewById(R.id.reference_sheet);
        ViewCompat.setAccessibilityPaneTitle(panel, activity.getString(R.string.reference_photos));
        ViewCompat.setOnApplyWindowInsetsListener(panel, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, 0, bars.right, dp(16) + bars.bottom);
            return windowInsets;
        });
        sheet.setOnDismissListener(ignored -> {
            if (dialog != sheet) return;
            cancelRequest();
            dialog = null;
            row = null;
            cards.clear();
            clear = null;
            retry = null;
            status = null;
            progress = null;
        });
        sheet.show();
        Window window = sheet.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int available = activity.getWindow().getDecorView().getWidth();
        if (available == 0) available = activity.getResources().getDisplayMetrics().widthPixels;
        window.setLayout(Math.min(available, dp(640)), WindowManager.LayoutParams.WRAP_CONTENT);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = 0.3f;
        window.setAttributes(attributes);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, window.getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
        return true;
    }

    private void request() {
        cancelRequest();
        int request = generation;
        photos.clear();
        thumbnails.clear();
        failed.clear();
        selectedId = null;
        error = 0;
        loading = true;
        renderEntry();
        rebuildCards();
        Bitmap snapshot;
        try { snapshot = snapshots.capture(); }
        catch (RuntimeException exception) { snapshot = null; }
        if (snapshot == null) {
            loading = false;
            error = R.string.reference_capture_error;
            renderCards();
            return;
        }
        Bitmap frame = snapshot;
        task = io.submit(() -> {
            try {
                byte[] jpeg = encode(frame);
                if (!current(request)) return;
                List<ReferencePhoto> results = client.recommend(jpeg);
                if (!current(request)) return;
                main.execute(() -> {
                    if (!current(request)) return;
                    photos.addAll(results);
                    loading = false;
                    rebuildCards();
                });
                loadImages(request, results);
            } catch (IOException | RuntimeException exception) {
                main.execute(() -> {
                    if (!current(request)) return;
                    loading = false;
                    error = R.string.reference_request_error;
                    renderCards();
                });
            }
        });
    }

    private boolean current(int request) {
        return !closed && request == generation && !Thread.currentThread().isInterrupted();
    }

    private byte[] encode(Bitmap frame) throws IOException {
        Bitmap scaled = frame;
        try {
            float scale = Math.min(1f, 1280f / Math.max(frame.getWidth(), frame.getHeight()));
            if (scale < 1f) scaled = Bitmap.createScaledBitmap(frame,
                    Math.max(1, Math.round(frame.getWidth() * scale)),
                    Math.max(1, Math.round(frame.getHeight() * scale)), true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, bytes)) {
                throw new IOException("Cannot encode preview");
            }
            return bytes.toByteArray();
        } finally {
            if (scaled != frame) scaled.recycle();
            frame.recycle();
        }
    }

    private void loadImages(int request, List<ReferencePhoto> results) {
        for (ReferencePhoto photo : results) {
            if (!current(request)) return;
            Bitmap image;
            try { image = decode(client.fetchImage(photo.image)); }
            catch (IOException | RuntimeException exception) { image = null; }
            Bitmap decoded = image;
            main.execute(() -> {
                if (!current(request)) {
                    if (decoded != null) decoded.recycle();
                    return;
                }
                if (decoded == null) {
                    failed.add(photo.id);
                    if (photo.id.equals(selectedId)) selectedId = null;
                } else {
                    thumbnails.put(photo.id, decoded);
                    failed.remove(photo.id);
                }
                renderEntry();
                renderCards();
            });
        }
    }

    private Bitmap decode(byte[] bytes) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0
                || (long) options.outWidth * options.outHeight > 20_000_000) {
            throw new IOException("Invalid reference image dimensions");
        }
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 512 || options.outHeight / options.inSampleSize > 512) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (image == null) throw new IOException("Invalid reference image");
        return image;
    }

    private void rebuildCards() {
        if (row == null) return;
        row.removeAllViews();
        cards.clear();
        for (ReferencePhoto photo : photos) {
            ImageButton card = new ImageButton(activity);
            card.setId(R.id.reference_item);
            card.setContentDescription(photo.description);
            card.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.setPadding(dp(3), dp(3), dp(3), dp(3));
            card.setClipToOutline(true);
            card.setImageResource(R.drawable.ic_photo);
            card.setOnClickListener(view -> {
                if (!thumbnails.containsKey(photo.id)) return;
                selectedId = photo.id;
                renderEntry();
                dialog.dismiss();
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(120), dp(160));
            if (!cards.isEmpty()) size.setMarginStart(dp(12));
            row.addView(card, size);
            cards.put(photo.id, card);
        }
        renderCards();
        ImageButton selected = cards.get(selectedId);
        if (selected != null) selected.post(() -> selected.requestRectangleOnScreen(
                new Rect(0, 0, selected.getWidth(), selected.getHeight()), true));
    }

    private ReferencePhoto find(String id) {
        if (id != null) for (ReferencePhoto photo : photos) if (photo.id.equals(id)) return photo;
        return null;
    }

    private void renderEntry() {
        ReferencePhoto selected = find(selectedId);
        Bitmap image = selected == null ? null : thumbnails.get(selected.id);
        entry.setSelected(selected != null);
        entry.setContentDescription(selected == null ? activity.getString(R.string.reference_photos)
                : activity.getString(R.string.reference_selected, selected.description));
        if (image == null) {
            entry.setImageResource(R.drawable.ic_reference);
            entry.setScaleType(ImageView.ScaleType.FIT_CENTER);
            entry.setPadding(dp(12), dp(12), dp(12), dp(12));
            entry.setBackground(entryBackground);
        } else {
            entry.setImageBitmap(image);
            entry.setScaleType(ImageView.ScaleType.CENTER_CROP);
            entry.setPadding(dp(3), dp(3), dp(3), dp(3));
            entry.setBackground(outline(true));
        }
    }

    private void renderCards() {
        for (ReferencePhoto photo : photos) {
            ImageButton card = cards.get(photo.id);
            if (card == null) continue;
            Bitmap image = thumbnails.get(photo.id);
            card.setEnabled(image != null);
            card.setAlpha(image == null ? 0.35f : 1f);
            card.setSelected(photo.id.equals(selectedId));
            card.setBackground(outline(card.isSelected()));
            if (image != null) card.setImageBitmap(image);
        }
        if (dialog == null) return;
        clear.setEnabled(selectedId != null);
        clear.setAlpha(selectedId == null ? 0.35f : 1f);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        dialog.findViewById(R.id.reference_gallery).setVisibility(photos.isEmpty() ? View.GONE : View.VISIBLE);
        int message = loading ? 0 : error != 0 ? error : photos.isEmpty() ? R.string.reference_empty
                : !failed.isEmpty() ? R.string.reference_load_error : 0;
        status.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
        if (message != 0) status.setText(message);
        retry.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
    }

    public void saveState(Bundle state) {
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        ArrayList<String> descriptions = new ArrayList<>();
        for (ReferencePhoto photo : photos) {
            ids.add(photo.id);
            images.add(photo.image.toString());
            descriptions.add(photo.description);
        }
        state.putStringArrayList("reference_ids", ids);
        state.putStringArrayList("reference_images", images);
        state.putStringArrayList("reference_descriptions", descriptions);
        state.putString("reference", selectedId);
        state.putBoolean("reference_open", isShowing());
    }

    private void restoreState(Bundle state) {
        if (state == null) return;
        ArrayList<String> ids = state.getStringArrayList("reference_ids");
        ArrayList<String> images = state.getStringArrayList("reference_images");
        ArrayList<String> descriptions = state.getStringArrayList("reference_descriptions");
        if (ids == null || images == null || descriptions == null || ids.size() > 6
                || ids.size() != images.size() || ids.size() != descriptions.size()) return;
        for (int i = 0; i < ids.size(); i++) {
            photos.add(new ReferencePhoto(ids.get(i), Uri.parse(images.get(i)), descriptions.get(i)));
        }
        selectedId = state.getString("reference");
        if (find(selectedId) == null) selectedId = null;
    }

    private GradientDrawable outline(boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.BLACK);
        shape.setCornerRadius(dp(8));
        shape.setStroke(dp(selected ? 2 : 1), selected ? activity.getColor(R.color.accent) : 0xFF505050);
        return shape;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private void cancelRequest() {
        generation++;
        if (task != null) task.cancel(true);
        task = null;
        client.cancel();
        loading = false;
    }

    public void pause() {
        if (dialog != null) dialog.dismiss();
        cancelRequest();
    }

    public void close() {
        closed = true;
        pause();
        client.close();
        entry.setOnClickListener(null);
        io.shutdownNow();
        thumbnails.clear();
        cards.clear();
    }
}

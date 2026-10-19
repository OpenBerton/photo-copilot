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
import android.os.Build;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A local photo picker. Decode off the UI thread; keep only small thumbnails in memory. */
public final class ReferencePicker {
    private final Activity activity;
    private final ImageButton entry;
    private final Drawable entryBackground;
    private final List<ReferencePhoto> photos;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Executor main;
    private final Map<String, Bitmap> thumbnails = new HashMap<>();
    private final Map<String, ImageButton> cards = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private Dialog dialog;
    private Button clear;
    private TextView status;
    private String selectedId;
    private boolean closed;

    public ReferencePicker(Activity activity, ImageButton entry, List<ReferencePhoto> photos, String selectedId) {
        this.activity = activity;
        this.entry = entry;
        this.photos = new ArrayList<>(photos);
        this.selectedId = find(selectedId) == null ? null : selectedId;
        entryBackground = entry.getBackground();
        main = ContextCompat.getMainExecutor(activity);
        entry.setOnClickListener(view -> show());
        renderEntry();
        ReferencePhoto selected = find(this.selectedId);
        if (selected != null) load(selected);
    }

    public String getSelectedId() { return selectedId; }

    public boolean isShowing() { return dialog != null && dialog.isShowing(); }

    public void show() {
        if (closed || isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
        Dialog sheet = new Dialog(activity, R.style.Theme_PhotoCopilot_ReferenceSheet);
        dialog = sheet;
        sheet.setOwnerActivity(activity);
        sheet.setCanceledOnTouchOutside(true);
        sheet.setContentView(R.layout.reference_sheet);
        LinearLayout row = sheet.findViewById(R.id.reference_photos);
        clear = sheet.findViewById(R.id.clear_reference);
        status = sheet.findViewById(R.id.reference_status);
        failed.clear();
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
                sheet.dismiss();
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(120), dp(160));
            if (!cards.isEmpty()) size.setMarginStart(dp(12));
            row.addView(card, size);
            cards.put(photo.id, card);
            load(photo);
        }
        clear.setOnClickListener(view -> {
            selectedId = null;
            renderEntry();
            sheet.dismiss();
        });
        sheet.findViewById(R.id.close_references).setOnClickListener(view -> sheet.dismiss());
        sheet.findViewById(R.id.reference_gallery).setVisibility(photos.isEmpty() ? View.GONE : View.VISIBLE);
        View panel = sheet.findViewById(R.id.reference_sheet);
        ViewCompat.setAccessibilityPaneTitle(panel, activity.getString(R.string.reference_photos));
        ViewCompat.setOnApplyWindowInsetsListener(panel, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, 0, bars.right, dp(16) + bars.bottom);
            return windowInsets;
        });
        sheet.setOnDismissListener(ignored -> {
            if (dialog == sheet) {
                dialog = null;
                cards.clear();
                clear = null;
                status = null;
            }
        });
        renderCards();
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
        ImageButton selectedCard = cards.get(selectedId);
        if (selectedCard != null) selectedCard.post(() -> selectedCard.requestRectangleOnScreen(
                new Rect(0, 0, selectedCard.getWidth(), selectedCard.getHeight()), true));
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
        if (clear != null) {
            clear.setEnabled(selectedId != null);
            clear.setAlpha(selectedId == null ? 0.35f : 1f);
            status.setText(photos.isEmpty() ? R.string.reference_empty : R.string.reference_load_error);
            status.setVisibility(photos.isEmpty() || !failed.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void load(ReferencePhoto photo) {
        if (thumbnails.containsKey(photo.id) || failed.contains(photo.id) || !loading.add(photo.id)) return;
        io.execute(() -> {
            Bitmap image;
            try {
                image = decode(photo);
            } catch (IOException | RuntimeException exception) {
                image = null;
            }
            Bitmap decoded = image;
            main.execute(() -> {
                if (closed) {
                    if (decoded != null) decoded.recycle();
                    return;
                }
                loading.remove(photo.id);
                if (decoded == null) {
                    failed.add(photo.id);
                    if (photo.id.equals(selectedId)) selectedId = null;
                } else thumbnails.put(photo.id, decoded);
                renderEntry();
                renderCards();
            });
        });
    }

    private Bitmap decode(ReferencePhoto photo) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = activity.getApplicationContext().getContentResolver().openInputStream(photo.image)) {
            BitmapFactory.decodeStream(input, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Reference unavailable");
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 512 || options.outHeight / options.inSampleSize > 512) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        try (InputStream input = activity.getApplicationContext().getContentResolver().openInputStream(photo.image)) {
            Bitmap image = BitmapFactory.decodeStream(input, null, options);
            if (image == null) throw new IOException("Invalid reference image");
            return image;
        }
    }

    private GradientDrawable outline(boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.BLACK);
        shape.setCornerRadius(dp(8));
        shape.setStroke(dp(selected ? 2 : 1), selected ? activity.getColor(R.color.accent) : 0xFF505050);
        return shape;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    public void close() {
        closed = true;
        if (dialog != null) dialog.dismiss();
        entry.setOnClickListener(null);
        io.shutdownNow();
        thumbnails.clear();
        cards.clear();
    }
}

package com.yrf3.photocopilot.camera;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import com.yrf3.photocopilot.R;

/** Keeps controls on the same device edges while their contents follow display rotation. */
public final class CameraLayout extends FrameLayout {
    private final DisplayManager displays;
    private final DisplayManager.DisplayListener displayChanges = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayRemoved(int id) { }
        @Override public void onDisplayChanged(int id) {
            if (getDisplay() != null && getDisplay().getDisplayId() == id) requestLayout();
        }
    };
    private View body;
    private View previewFrame;
    private View previewContent;
    private View[] buttons;
    private ZoomControl zoom;
    private int degrees;

    public CameraLayout(Context context, AttributeSet attributes) {
        super(context, attributes);
        displays = context.getSystemService(DisplayManager.class);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        body = getChildAt(0);
        previewFrame = findViewById(R.id.camera_preview_frame);
        previewContent = findViewById(R.id.camera_preview_content);
        zoom = findViewById(R.id.zoom_control);
        int[] ids = {R.id.flash_button, R.id.reference_button, R.id.grid_button,
                R.id.zoom_button, R.id.switch_camera, R.id.recent_photo};
        buttons = new View[ids.length];
        for (int index = 0; index < ids.length; index++) buttons[index] = findViewById(ids[index]);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        displays.registerDisplayListener(displayChanges, new Handler(Looper.getMainLooper()));
        requestLayout();
    }

    @Override
    protected void onDetachedFromWindow() {
        displays.unregisterDisplayListener(displayChanges);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        degrees = getDisplay() == null ? 0 : getDisplay().getRotation() * 90;
        boolean quarterTurn = degrees % 180 != 0;
        int availableWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int availableHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
        body.measure(exact(quarterTurn ? availableHeight : availableWidth),
                exact(quarterTurn ? availableWidth : availableHeight));

        // CameraX still receives an upright, display-aligned PreviewView. Only its frame
        // stays with the device; do not rotate the sensor output or stretch the preview.
        int frameWidth = previewFrame.getMeasuredWidth();
        int frameHeight = previewFrame.getMeasuredHeight();
        LayoutParams contentSize = (LayoutParams) previewContent.getLayoutParams();
        contentSize.width = quarterTurn ? frameHeight : frameWidth;
        contentSize.height = quarterTurn ? frameWidth : frameHeight;
        previewContent.measure(exact(contentSize.width), exact(contentSize.height));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        int x = getPaddingLeft() + (width - body.getMeasuredWidth()) / 2;
        int y = getPaddingTop() + (height - body.getMeasuredHeight()) / 2;
        body.layout(x, y, x + body.getMeasuredWidth(), y + body.getMeasuredHeight());
        body.setRotation(-degrees);
        previewContent.setRotation(degrees);
        for (View button : buttons) button.setRotation(degrees);
        zoom.setContentRotation(degrees);
    }

    private static int exact(int size) { return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY); }
}

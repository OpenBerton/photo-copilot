package com.yrf3.photocopilot.camera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.yrf3.photocopilot.R;

import java.util.Locale;

/** Native preset buttons become a continuous ruler when the same area is dragged. */
public final class ZoomControl extends FrameLayout {
    private final int touchSlop;
    private ZoomRuler ruler;
    private LinearLayout presets;
    private float minimum = Float.NaN;
    private float maximum = Float.NaN;
    private MotionEvent down;
    private boolean dragging;
    private float contentRotation;

    public ZoomControl(Context context, AttributeSet attributes) {
        super(context, attributes);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        ruler = findViewById(R.id.zoom_slider);
        presets = findViewById(R.id.zoom_presets);
        setZoomRange(1f, 1f);
    }

    public void setZoomRange(float minimum, float maximum) {
        if (this.minimum == minimum && this.maximum == maximum) return;
        endGesture(true);
        this.minimum = minimum;
        this.maximum = maximum;
        ruler.setZoomRange(minimum, maximum);
        presets.removeAllViews();
        if (minimum < 0.95f) addPreset(minimum);
        addPreset(1f);
        addPreset(2f);
        addPreset(5f);
        if (presets.getChildCount() == 0) addPreset(minimum);
        showZoomRatio(ruler.getZoomRatio());
    }

    public void showZoomRatio(float ratio) {
        if (!ruler.isPressed()) ruler.showZoomRatio(ratio);
        for (int index = 0; index < presets.getChildCount(); index++) {
            View button = presets.getChildAt(index);
            button.setSelected(Math.abs(ratio - (float) button.getTag()) < 0.05f);
        }
    }

    public void setContentRotation(float degrees) {
        contentRotation = degrees;
        for (int index = 0; index < presets.getChildCount(); index++) {
            presets.getChildAt(index).setRotation(degrees);
        }
        ruler.setContentRotation(degrees);
    }

    private void addPreset(float ratio) {
        if (ratio < minimum || ratio > maximum) return;
        Button button = new Button(getContext());
        button.setRotation(contentRotation);
        button.setText(String.format(Locale.getDefault(),
                Math.abs(ratio - Math.round(ratio)) < 0.05f ? "%.0f×" : "%.1f×", ratio));
        button.setTextSize(13);
        button.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_selected}, {}},
                new int[] {getContext().getColor(R.color.accent), getContext().getColor(R.color.foreground)}));
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setGravity(Gravity.CENTER);
        button.setTag(ratio);
        StateListDrawable outline = new StateListDrawable();
        outline.addState(new int[] {android.R.attr.state_selected}, circle(getContext().getColor(R.color.accent)));
        outline.addState(new int[] {}, circle(0xFF505050));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), outline, circle(Color.WHITE)));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(48), dp(48));
        if (presets.getChildCount() > 0) size.setMarginStart(dp(4));
        button.setLayoutParams(size);
        button.setEnabled(isEnabled());
        button.setOnClickListener(view -> {
            if (isEnabled()) ruler.setZoomRatioFromUser(ratio);
        });
        presets.addView(button);
    }

    private GradientDrawable circle(int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.BLACK);
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (ruler == null || presets == null) return;
        if (!enabled) endGesture(true);
        ruler.setEnabled(enabled);
        for (int index = 0; index < presets.getChildCount(); index++) presets.getChildAt(index).setEnabled(enabled);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                rememberDown(event);
                return ruler.getVisibility() == VISIBLE;
            case MotionEvent.ACTION_MOVE:
                if (shouldDrag(event)) {
                    beginDrag();
                    // This event cancels the child's tap; the parent receives subsequent moves.
                    ruler.dispatchTouchEvent(event);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) clearDown();
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (down == null) rememberDown(event);
                if (ruler.getVisibility() == VISIBLE) beginDrag();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && shouldDrag(event)) beginDrag();
                if (dragging) ruler.dispatchTouchEvent(event);
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) ruler.dispatchTouchEvent(event);
                else performClick();
                endGesture(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                endGesture(true);
                return true;
            default:
                if (dragging) ruler.dispatchTouchEvent(event);
                return true;
        }
    }

    private void rememberDown(MotionEvent event) {
        clearDown();
        down = MotionEvent.obtain(event);
    }

    private boolean shouldDrag(MotionEvent event) {
        if (down == null) return false;
        int index = event.findPointerIndex(down.getPointerId(0));
        if (index < 0) return false;
        float horizontal = Math.abs(event.getX(index) - down.getX());
        return horizontal > touchSlop && horizontal > Math.abs(event.getY(index) - down.getY());
    }

    private void beginDrag() {
        if (dragging || down == null) return;
        dragging = true;
        showRuler(true);
        ruler.dispatchTouchEvent(down);
    }

    private void endGesture(boolean cancel) {
        if (dragging && cancel) {
            MotionEvent event = MotionEvent.obtain(down);
            event.setAction(MotionEvent.ACTION_CANCEL);
            ruler.dispatchTouchEvent(event);
            event.recycle();
        }
        dragging = false;
        if (ruler != null) showRuler(false);
        clearDown();
    }

    private void showRuler(boolean visible) {
        ruler.setVisibility(visible ? VISIBLE : INVISIBLE);
        presets.setVisibility(visible ? INVISIBLE : VISIBLE);
    }

    private void clearDown() {
        if (down != null) down.recycle();
        down = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        endGesture(true);
        super.onDetachedFromWindow();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (isEnabled()) info.addAction(ruler.getVisibility() == VISIBLE
                ? AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (isEnabled() && (action == AccessibilityNodeInfo.ACTION_EXPAND
                || action == AccessibilityNodeInfo.ACTION_COLLAPSE)) {
            endGesture(true);
            showRuler(action == AccessibilityNodeInfo.ACTION_EXPAND);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}

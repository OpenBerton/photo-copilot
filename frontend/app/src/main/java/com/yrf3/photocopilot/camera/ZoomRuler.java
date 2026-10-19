package com.yrf3.photocopilot.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.widget.SeekBar;

import com.yrf3.photocopilot.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A scrolling logarithmic scale under a fixed centre marker; retains SeekBar accessibility. */
public final class ZoomRuler extends SeekBar {
    private static final double LOG_2 = Math.log(2);
    private static final int[] LABEL_STEPS = {1, 2, 5};
    private final Paint ticks = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labels = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final List<MajorTick> majorTicks = new ArrayList<>();
    private final float density;
    private float minimum = 1f;
    private float maximum = 1f;
    private boolean rangeInitialized;
    private OnSeekBarChangeListener changes;
    private boolean touchUpdate;
    private int pointer = MotionEvent.INVALID_POINTER_ID;
    private float lastX;
    private double dragLogRatio;

    private static final class MajorTick {
        final double position;
        final String text;
        MajorTick(double ratio) {
            position = Math.log(ratio) / LOG_2;
            text = String.format(Locale.getDefault(), ratio >= 1 ? "%.0f" : "%.1f", ratio);
        }
    }

    public ZoomRuler(Context context, AttributeSet attributes) {
        super(context, attributes);
        density = getResources().getDisplayMetrics().density;
        setMax(1000);
        setThumb(null);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        ticks.setStrokeWidth(density);
        labels.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12,
                getResources().getDisplayMetrics()));
        labels.setTextAlign(Paint.Align.CENTER);
        marker.setColor(context.getColor(R.color.accent));
        setZoomRange(1f, 1f);
    }

    @Override
    public void setOnSeekBarChangeListener(OnSeekBarChangeListener listener) {
        changes = listener;
        super.setOnSeekBarChangeListener(listener == null ? null : new OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                boolean userChange = fromUser || touchUpdate;
                touchUpdate = false;
                listener.onProgressChanged(bar, progress, userChange);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { listener.onStartTrackingTouch(bar); }
            @Override public void onStopTrackingTouch(SeekBar bar) { listener.onStopTrackingTouch(bar); }
        });
    }

    public void setZoomRange(float minimum, float maximum) {
        if (rangeInitialized && this.minimum == minimum && this.maximum == maximum) return;
        endDrag();
        this.minimum = minimum;
        this.maximum = maximum;
        rangeInitialized = true;
        majorTicks.clear();
        int firstDecade = (int) Math.floor(Math.log10(minimum));
        int lastDecade = (int) Math.ceil(Math.log10(maximum));
        for (int decade = firstDecade; decade <= lastDecade; decade++) {
            for (int step : LABEL_STEPS) {
                double ratio = step * Math.pow(10, decade);
                if (ratio >= minimum && ratio <= maximum) majorTicks.add(new MajorTick(ratio));
            }
        }
        invalidate();
    }

    public float getZoomRatio() {
        if (maximum <= minimum) return minimum;
        return (float) (minimum * Math.pow(maximum / minimum, getProgress() / 1000.0));
    }

    public void showZoomRatio(float ratio) {
        if (maximum <= minimum) {
            setProgress(0);
        } else {
            float clamped = Math.max(minimum, Math.min(maximum, ratio));
            setProgress((int) Math.round(1000 * Math.log(clamped / minimum)
                    / Math.log(maximum / minimum)));
        }
    }

    public void setZoomRatioFromUser(float ratio) {
        touchUpdate = true;
        try {
            showZoomRatio(ratio);
        } finally {
            touchUpdate = false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || maximum <= minimum) {
            endDrag();
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = event.getPointerId(0);
                lastX = event.getX();
                dragLogRatio = Math.log(getZoomRatio());
                setPressed(true);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (changes != null) changes.onStartTrackingTouch(this);
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointer);
                if (index >= 0) dragTo(event.getX(index));
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                int lifted = event.getActionIndex();
                if (event.getPointerId(lifted) == pointer) {
                    int remaining = lifted == 0 ? 1 : 0;
                    pointer = event.getPointerId(remaining);
                    lastX = event.getX(remaining);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (pointer != MotionEvent.INVALID_POINTER_ID) dragTo(event.getX());
                endDrag();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                endDrag();
                return true;
            default:
                return true;
        }
    }

    private void dragTo(float x) {
        // One doubling takes the same finger travel on every screen, regardless of zoom range.
        dragLogRatio = Math.max(Math.log(minimum), Math.min(Math.log(maximum),
                dragLogRatio - (x - lastX) * LOG_2 / (120 * density)));
        lastX = x;
        setZoomRatioFromUser((float) Math.exp(dragLogRatio));
    }

    private void endDrag() {
        if (pointer == MotionEvent.INVALID_POINTER_ID) return;
        pointer = MotionEvent.INVALID_POINTER_ID;
        setPressed(false);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        if (changes != null) changes.onStopTrackingTouch(this);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width == 0) return;
        Shader fade = new LinearGradient(0, 0, width, 0,
                new int[] {0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[] {0, 0.18f, 0.82f, 1}, Shader.TileMode.CLAMP);
        ticks.setShader(fade);
        labels.setShader(fade);
        float centre = width / 2f;
        arrow.reset();
        arrow.moveTo(centre - 4 * density, 3 * density);
        arrow.lineTo(centre + 4 * density, 3 * density);
        arrow.lineTo(centre, 11 * density);
        arrow.close();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        float centre = getWidth() / 2f;
        double current = Math.log(getZoomRatio()) / LOG_2;
        float spacing = 120 * density;
        double half = centre / spacing;
        int first = (int) Math.ceil(Math.max(Math.log(minimum) / LOG_2, current - half) * 10);
        int last = (int) Math.floor(Math.min(Math.log(maximum) / LOG_2, current + half) * 10);
        for (int index = first; index <= last; index++) {
            float x = centre + (float) (index / 10.0 - current) * spacing;
            boolean major = index % 10 == 0;
            ticks.setAlpha(major ? 230 : 155);
            canvas.drawLine(x, (major ? 12 : 20) * density, x, 32 * density, ticks);
        }
        for (MajorTick tick : majorTicks) {
            float x = centre + (float) (tick.position - current) * spacing;
            if (x < -24 * density || x > getWidth() + 24 * density) continue;
            ticks.setAlpha(230);
            canvas.drawLine(x, 12 * density, x, 32 * density, ticks);
            canvas.drawText(tick.text, x, 52 * density, labels);
        }
        canvas.drawPath(arrow, marker);
        canvas.drawRect(centre - density, 12 * density, centre + density, 34 * density, marker);
    }
}

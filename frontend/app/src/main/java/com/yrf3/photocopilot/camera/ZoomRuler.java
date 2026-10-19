package com.yrf3.photocopilot.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.SeekBar;

/** Native accessible slider, with a logarithmic zoom scale and evenly spaced ruler ticks. */
public final class ZoomRuler extends SeekBar {
    private final Paint ticks = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float minimum = 1f;
    private float maximum = 1f;

    public ZoomRuler(Context context, AttributeSet attributes) {
        super(context, attributes);
        setMax(1000);
        ticks.setColor(0x99FFFFFF);
        ticks.setStrokeWidth(getResources().getDisplayMetrics().density);
    }

    public void setZoomRange(float minimum, float maximum) {
        this.minimum = minimum;
        this.maximum = maximum;
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

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        float left = getPaddingLeft();
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float center = getHeight() / 2f;
        float density = getResources().getDisplayMetrics().density;
        for (int index = 0; index <= 20; index++) {
            float x = left + width * index / 20f;
            float height = (index % 5 == 0 ? 8f : 4f) * density;
            canvas.drawLine(x, center - height, x, center + height, ticks);
        }
        super.onDraw(canvas);
    }
}

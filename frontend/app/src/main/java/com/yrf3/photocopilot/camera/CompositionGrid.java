package com.yrf3.photocopilot.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Draws thirds inside the FIT_CENTER image, excluding the preview's letterbox bars. */
public final class CompositionGrid extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float imageAspectRatio;

    public CompositionGrid(Context context, AttributeSet attributes) {
        super(context, attributes);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        paint.setStrokeWidth(getResources().getDisplayMetrics().density);
        paint.setColor(0xB3FFFFFF);
        paint.setShadowLayer(1f, 0f, 0f, 0x99000000);
    }

    public void setImageAspectRatio(float ratio) {
        imageAspectRatio = ratio;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageAspectRatio <= 0f) {
            return;
        }
        float width = Math.min(getWidth(), getHeight() * imageAspectRatio);
        float height = width / imageAspectRatio;
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        for (int index = 1; index <= 2; index++) {
            float x = left + width * index / 3f;
            float y = top + height * index / 3f;
            canvas.drawLine(x, top, x, top + height, paint);
            canvas.drawLine(left, y, left + width, y, paint);
        }
    }
}

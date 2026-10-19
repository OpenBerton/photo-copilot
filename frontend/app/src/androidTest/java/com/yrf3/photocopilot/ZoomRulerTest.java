package com.yrf3.photocopilot;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.yrf3.photocopilot.camera.ZoomRuler;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class ZoomRulerTest {
    @Test
    public void keyboardAndAccessibilityStillChangeZoomButDisplayUpdatesDoNot() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZoomRuler ruler = ruler();
            ruler.showZoomRatio(2f);
            AtomicInteger userChanges = new AtomicInteger();
            ruler.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (fromUser) userChanges.incrementAndGet();
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            assertTrue(ruler.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                    new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
            float increased = ruler.getZoomRatio();
            assertTrue(increased > 2f);
            assertTrue(ruler.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null));
            assertTrue(ruler.getZoomRatio() < increased);
            assertEquals(2, userChanges.get());
            ruler.showZoomRatio(4f);
            assertEquals("Camera feedback must not become another user zoom request.", 2, userChanges.get());
        });
    }

    @Test
    public void draggingMovesScaleRelativeToFingerWithoutJumpingOnTouch() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZoomRuler ruler = ruler();
            ruler.showZoomRatio(2f);
            AtomicInteger userChanges = new AtomicInteger();
            ruler.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (fromUser) userChanges.incrementAndGet();
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            float initial = ruler.getZoomRatio();
            float start = ruler.getWidth() * 0.8f;
            touch(ruler, MotionEvent.ACTION_DOWN, start);
            assertEquals("Touching away from the centre must not jump to another value.",
                    initial, ruler.getZoomRatio(), 0.01f);
            touch(ruler, MotionEvent.ACTION_MOVE, start - dp(ruler, 80));
            assertTrue("Sliding the scale left brings higher zoom values to the fixed centre.",
                    ruler.getZoomRatio() > initial * 1.3f);
            touch(ruler, MotionEvent.ACTION_MOVE, start);
            assertEquals("Returning the same distance restores the original zoom.",
                    initial, ruler.getZoomRatio(), 0.01f);
            touch(ruler, MotionEvent.ACTION_UP, start);
            assertFalse(ruler.isPressed());
            assertTrue("Dragging must send user changes to the camera listener.", userChanges.get() >= 2);
        });
    }

    @Test
    public void limitsClampAndDraggingBackRespondsImmediately() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZoomRuler ruler = ruler();
            ruler.showZoomRatio(7f);
            float start = ruler.getWidth() * 0.9f;
            float end = ruler.getWidth() * 0.1f;
            touch(ruler, MotionEvent.ACTION_DOWN, start);
            touch(ruler, MotionEvent.ACTION_MOVE, end);
            assertEquals(8f, ruler.getZoomRatio(), 0.01f);
            touch(ruler, MotionEvent.ACTION_MOVE, end + dp(ruler, 32));
            assertTrue("Reversing direction at the limit must not have dead travel.",
                    ruler.getZoomRatio() < 7.5f);
            touch(ruler, MotionEvent.ACTION_CANCEL, end);
            assertFalse(ruler.isPressed());
            ruler.showZoomRatio(0.5f);
            touch(ruler, MotionEvent.ACTION_DOWN, end);
            touch(ruler, MotionEvent.ACTION_MOVE, start);
            assertEquals(0.5f, ruler.getZoomRatio(), 0.01f);
            touch(ruler, MotionEvent.ACTION_UP, start);
        });
    }

    @Test
    public void centreMarkerStaysFixedWhileScaleMovesAndEdgesFade() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZoomRuler ruler = ruler();
            ruler.showZoomRatio(2f);
            Bitmap before = render(ruler);
            ruler.showZoomRatio(3.5f);
            Bitmap after = render(ruler);
            try {
                int centre = ruler.getWidth() / 2;
                int markerY = Math.round(dp(ruler, 20));
                int accent = ruler.getContext().getColor(R.color.accent);
                assertEquals("The selection marker is fixed at the centre.", accent,
                        before.getPixel(centre, markerY));
                assertEquals(accent, after.getPixel(centre, markerY));
                int y1 = Math.round(dp(ruler, 16));
                int y2 = Math.round(dp(ruler, 30));
                assertTrue("Left ticks fade towards the edge.",
                        peakAlpha(before, 0, ruler.getWidth() / 20, y1, y2) < 128);
                assertTrue("Right ticks fade towards the edge.",
                        peakAlpha(before, ruler.getWidth() * 19 / 20, ruler.getWidth(), y1, y2) < 128);
                assertTrue("Ticks remain visible away from the edges.",
                        peakAlpha(before, ruler.getWidth() / 4, ruler.getWidth() / 3, y1, y2) > 128);
                int changed = 0;
                for (int y = y1; y < after.getHeight(); y++) {
                    for (int x = 0; x < after.getWidth(); x++) {
                        if (Math.abs(x - centre) > dp(ruler, 6)
                                && before.getPixel(x, y) != after.getPixel(x, y)) changed++;
                    }
                }
                assertTrue("The scale, rather than the marker, changes position.", changed > 100);
            } finally {
                before.recycle();
                after.recycle();
            }
        });
    }

    private static ZoomRuler ruler() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZoomRuler ruler = new ZoomRuler(context, null);
        ruler.layout(0, 0, Math.round(dp(ruler, 320)), Math.round(dp(ruler, 64)));
        ruler.setZoomRange(0.5f, 8f);
        return ruler;
    }

    private static float dp(ZoomRuler ruler, float value) {
        return value * ruler.getResources().getDisplayMetrics().density;
    }

    private static void touch(ZoomRuler ruler, int action, float x) {
        long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, action, x, ruler.getHeight() / 2f, 0);
        try {
            assertTrue(ruler.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    private static Bitmap render(ZoomRuler ruler) {
        Bitmap image = Bitmap.createBitmap(ruler.getWidth(), ruler.getHeight(), Bitmap.Config.ARGB_8888);
        ruler.draw(new Canvas(image));
        return image;
    }

    private static int peakAlpha(Bitmap image, int x1, int x2, int y1, int y2) {
        int alpha = 0;
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) alpha = Math.max(alpha, Color.alpha(image.getPixel(x, y)));
        }
        return alpha;
    }
}

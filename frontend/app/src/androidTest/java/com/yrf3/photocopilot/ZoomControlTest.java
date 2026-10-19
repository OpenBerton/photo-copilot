package com.yrf3.photocopilot;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import com.yrf3.photocopilot.camera.ZoomControl;
import com.yrf3.photocopilot.camera.ZoomRuler;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class ZoomControlTest {
    @Test
    public void idleCameraShowsPresetButtonsInsteadOfTheScale() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            View root = LayoutInflater.from(context).inflate(R.layout.activity_main, null);
            assertEquals("The scale is only shown while adjusting, not permanently.",
                    View.INVISIBLE, root.findViewById(R.id.zoom_slider).getVisibility());
            View presets = root.findViewById(context.getResources().getIdentifier(
                    "zoom_presets", "id", context.getPackageName()));
            assertNotNull("Common zoom values are available as buttons.", presets);
            assertEquals(View.VISIBLE, presets.getVisibility());
        });
    }

    @Test
    public void presetsRespectCameraRangeAndTapsDoNotEchoCameraFeedback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZoomControl control = control(0.6f, 2.5f);
            LinearLayout presets = control.findViewById(R.id.zoom_presets);
            assertEquals(3, presets.getChildCount());
            assertEquals("0.6×", ((Button) presets.getChildAt(0)).getText().toString());
            assertEquals("1×", ((Button) presets.getChildAt(1)).getText().toString());
            Button two = (Button) presets.getChildAt(2);
            assertEquals("2×", two.getText().toString());
            ZoomRuler ruler = control.findViewById(R.id.zoom_slider);
            AtomicInteger requests = new AtomicInteger();
            AtomicReference<Float> requested = new AtomicReference<>();
            ruler.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (fromUser) {
                        if (requests.incrementAndGet() == 1) {
                            requested.set(ruler.getZoomRatio());
                            // Camera feedback may arrive while the user callback is still running.
                            control.showZoomRatio(2.1f);
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            two.performClick();
            assertEquals(2f, requested.get(), 0.01f);
            assertEquals("Camera feedback must not send a second user request.", 1, requests.get());
            control.showZoomRatio(2f);
            assertTrue(two.isSelected());
            assertEquals(View.INVISIBLE, ruler.getVisibility());
            control.setZoomRange(0.5f, 8f);
            control.measure(View.MeasureSpec.makeMeasureSpec(Math.round(dp(control, 216)), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.round(dp(control, 64)), View.MeasureSpec.EXACTLY));
            control.layout(0, 0, control.getMeasuredWidth(), control.getMeasuredHeight());
            assertEquals(4, presets.getChildCount());
            int previousRight = 0;
            for (int index = 0; index < presets.getChildCount(); index++) {
                View button = presets.getChildAt(index);
                int left = presets.getLeft() + button.getLeft();
                int right = left + button.getWidth();
                assertTrue("Four presets fit the narrow landscape control area.", left >= previousRight
                        && right <= control.getWidth());
                previousRight = right;
            }
        });
    }

    @Test
    public void draggingOverAButtonShowsTheScaleAndReleaseDoesNotClickTheButton() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZoomControl control = control(1f, 8f);
            control.showZoomRatio(1.7f);
            ZoomRuler ruler = control.findViewById(R.id.zoom_slider);
            LinearLayout presets = control.findViewById(R.id.zoom_presets);
            Button button = (Button) presets.getChildAt(1);
            float x = presets.getLeft() + button.getLeft() + button.getWidth() / 2f;
            float initial = ruler.getZoomRatio();
            touch(control, MotionEvent.ACTION_DOWN, x);
            assertEquals(View.INVISIBLE, ruler.getVisibility());
            assertEquals(initial, ruler.getZoomRatio(), 0.001f);
            touch(control, MotionEvent.ACTION_MOVE, x - dp(control, 64));
            assertEquals(View.VISIBLE, ruler.getVisibility());
            assertEquals(View.INVISIBLE, presets.getVisibility());
            float dragged = ruler.getZoomRatio();
            assertTrue(dragged > 2.3f);
            touch(control, MotionEvent.ACTION_UP, x - dp(control, 64));
            assertEquals("A drag must not end by selecting the button underneath it.",
                    dragged, ruler.getZoomRatio(), 0.001f);
            assertEquals(View.INVISIBLE, ruler.getVisibility());
            assertEquals(View.VISIBLE, presets.getVisibility());
        });
    }

    @Test
    public void cancelOrDisableRestoresButtonsWithoutResettingTheChosenZoom() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZoomControl control = control(1f, 8f);
            ZoomRuler ruler = control.findViewById(R.id.zoom_slider);
            LinearLayout presets = control.findViewById(R.id.zoom_presets);
            float x = control.getWidth() * 0.75f;
            touch(control, MotionEvent.ACTION_DOWN, x);
            touch(control, MotionEvent.ACTION_MOVE, x - dp(control, 60));
            float chosen = ruler.getZoomRatio();
            touch(control, MotionEvent.ACTION_CANCEL, x);
            assertEquals(chosen, ruler.getZoomRatio(), 0.001f);
            assertEquals(View.INVISIBLE, ruler.getVisibility());
            assertFalse(ruler.isPressed());
            touch(control, MotionEvent.ACTION_DOWN, x);
            touch(control, MotionEvent.ACTION_MOVE, x - dp(control, 40));
            control.setEnabled(false);
            assertEquals(View.INVISIBLE, ruler.getVisibility());
            assertFalse(ruler.isPressed());
            for (int index = 0; index < presets.getChildCount(); index++) {
                assertFalse(presets.getChildAt(index).isEnabled());
            }
            control.setEnabled(true);
            assertTrue(presets.getChildAt(0).isEnabled());
        });
    }

    @Test
    public void accessibilityCanOpenTheFineScaleAndReturnToButtons() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZoomControl control = control(1f, 8f);
            ZoomRuler ruler = control.findViewById(R.id.zoom_slider);
            assertTrue(control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null));
            assertEquals(View.VISIBLE, ruler.getVisibility());
            float initial = ruler.getZoomRatio();
            assertTrue(ruler.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                    new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
            assertTrue(ruler.getZoomRatio() > initial);
            assertTrue(control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null));
            assertEquals(View.INVISIBLE, ruler.getVisibility());
        });
    }

    private static ZoomControl control(float minimum, float maximum) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        View root = LayoutInflater.from(context).inflate(R.layout.activity_main, null);
        ZoomControl control = root.findViewById(R.id.zoom_control);
        control.setZoomRange(minimum, maximum);
        control.showZoomRatio(1f);
        control.measure(View.MeasureSpec.makeMeasureSpec(Math.round(dp(control, 320)), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(dp(control, 64)), View.MeasureSpec.EXACTLY));
        control.layout(0, 0, control.getMeasuredWidth(), control.getMeasuredHeight());
        return control;
    }

    private static float dp(View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }

    private static void touch(ZoomControl control, int action, float x) {
        long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, action, x, control.getHeight() / 2f, 0);
        try {
            assertTrue(control.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }
}

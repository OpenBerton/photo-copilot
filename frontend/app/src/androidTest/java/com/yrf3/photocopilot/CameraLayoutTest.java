package com.yrf3.photocopilot;

import static org.junit.Assert.*;

import android.app.UiAutomation;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.PointF;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.Surface;
import android.widget.SeekBar;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class CameraLayoutTest {
    @Before
    public void allowCameraForLayoutChecks() throws Exception {
        CameraPreviewTest.grantPermission(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getPackageName(), android.Manifest.permission.CAMERA);
    }

    @Test
    public void buttonLocationsStayWithDeviceWhenRotated() {
        UiAutomation device = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        assertTrue(device.setRotation(UiAutomation.ROTATION_FREEZE_0));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT);
            AtomicReference<PointF[]> original = new AtomicReference<>();
            scenario.onActivity(activity -> original.set(anchors(activity)));
            for (int rotation : new int[] {Surface.ROTATION_90, Surface.ROTATION_180,
                    Surface.ROTATION_270, Surface.ROTATION_0}) {
                assertTrue(device.setRotation(rotation));
                awaitRotation(scenario, rotation);
                scenario.onActivity(activity -> {
                    PointF[] actual = anchors(activity);
                    for (int index = 0; index < actual.length; index++) {
                        assertEquals("A button keeps its device-relative horizontal anchor (index " + index + ").",
                                original.get()[index].x, actual[index].x, 2f);
                        assertEquals("A button keeps its device-relative vertical anchor (index " + index + ").",
                                original.get()[index].y, actual[index].y, 2f);
                    }
                    for (int id : new int[] {R.id.flash_button, R.id.reference_button, R.id.grid_button,
                            R.id.zoom_button, R.id.switch_camera, R.id.recent_photo, R.id.preview_view}) {
                        assertEquals("Content remains upright in the display.", 0f,
                                totalRotation(activity.findViewById(id)), 0.01f);
                    }
                    android.view.ViewGroup presets = activity.findViewById(R.id.zoom_presets);
                    for (int index = 0; index < presets.getChildCount(); index++) {
                        assertEquals(0f, totalRotation(presets.getChildAt(index)), 0.01f);
                    }
                });
            }
        } finally {
            device.setRotation(UiAutomation.ROTATION_UNFREEZE);
        }
    }

    private static float totalRotation(View view) {
        float rotation = view.getRotation();
        while (view.getParent() instanceof View) {
            view = (View) view.getParent();
            rotation += view.getRotation();
        }
        return rotation % 360;
    }

    private static PointF[] anchors(MainActivity activity) {
        int[] ids = {R.id.flash_button, R.id.reference_button, R.id.grid_button,
                R.id.zoom_button, R.id.shutter_button, R.id.switch_camera, R.id.recent_photo};
        View root = activity.findViewById(R.id.main);
        Rect area = bounds(root);
        area.left += root.getPaddingLeft();
        area.top += root.getPaddingTop();
        area.right -= root.getPaddingRight();
        area.bottom -= root.getPaddingBottom();
        int rotation = root.getDisplay().getRotation();
        float width = rotation % 2 == 0 ? area.width() : area.height();
        float height = rotation % 2 == 0 ? area.height() : area.width();
        PointF[] anchors = new PointF[ids.length];
        for (int index = 0; index < ids.length; index++) {
            Rect button = bounds(activity.findViewById(ids[index]));
            float x = button.exactCenterX() - area.left;
            float y = button.exactCenterY() - area.top;
            float naturalX = rotation == 0 ? x : rotation == 1 ? width - y
                    : rotation == 2 ? width - x : y;
            float naturalY = rotation == 0 ? y : rotation == 1 ? x
                    : rotation == 2 ? height - y : height - x;
            float anchorX = index == 0 || index == 6 ? naturalX
                    : index == 1 || index == 2 || index == 5 ? width - naturalX
                    : naturalX - width / 2f;
            anchors[index] = new PointF(anchorX, index < 3 ? naturalY : height - naturalY);
        }
        return anchors;
    }

    private static void awaitRotation(ActivityScenario<MainActivity> scenario, int rotation) {
        long end = SystemClock.elapsedRealtime() + 10_000;
        AtomicBoolean ready = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> ready.set(activity.findViewById(R.id.main)
                    .getDisplay().getRotation() == rotation && activity.findViewById(R.id.main).isLaidOut()));
            if (ready.get()) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < end);
        fail("The display did not reach rotation " + rotation);
    }
    @Test
    public void controlsStayOutsidePreviewInBothOrientations() {
        UiAutomation device = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        assertTrue(device.setRotation(UiAutomation.ROTATION_FREEZE_0));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT);
            checkLayout(scenario);
            assertTrue(device.setRotation(UiAutomation.ROTATION_FREEZE_90));
            awaitOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE);
            checkLayout(scenario);
        } finally {
            device.setRotation(UiAutomation.ROTATION_UNFREEZE);
        }
    }

    @Test
    public void backgroundOutsideCameraIsPureBlack() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(Color.BLACK, activity.getColor(R.color.background));
                View root = activity.findViewById(R.id.main);
                assertTrue(root.getBackground() instanceof ColorDrawable);
                assertEquals(Color.BLACK, ((ColorDrawable) root.getBackground()).getColor());
            });
        }
    }

    private static void checkLayout(ActivityScenario<MainActivity> scenario) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            Rect preview = deviceBounds(activity, R.id.preview_view);
            Rect zoom = deviceBounds(activity, R.id.zoom_button);
            Rect shutter = deviceBounds(activity, R.id.shutter_button);
            Rect flip = deviceBounds(activity, R.id.switch_camera);
            Rect root = deviceBounds(activity, R.id.main);
            assertTrue("Zoom stays outside the preview in device coordinates.", zoom.top >= preview.bottom);
            assertTrue("The preview must not collapse to a small strip.", preview.height() > root.height() * 0.35f);
            assertTrue("Zoom must stay above the shutter.", zoom.bottom <= shutter.top);
            assertTrue("Camera switching belongs to the right of the shutter.", flip.left >= shutter.right);
            assertTrue(Math.abs(flip.centerY() - shutter.centerY()) <= 2);
            View ruler = activity.findViewById(activity.getResources().getIdentifier(
                    "zoom_slider", "id", activity.getPackageName()));
            assertTrue("A draggable zoom ruler is available.", ruler instanceof SeekBar);
            Rect rulerBounds = deviceBounds(activity, R.id.zoom_control);
            assertTrue(rulerBounds.top >= preview.bottom);
            assertTrue(rulerBounds.bottom <= shutter.top);
            assertTrue("The preview retains usable space.", preview.width() > 0 && preview.height() > 48);
            View recent = activity.findViewById(activity.getResources().getIdentifier(
                    "recent_photo", "id", activity.getPackageName()));
            assertNotNull("The last-photo entry belongs on the left.", recent);
            assertTrue(deviceBounds(activity, R.id.recent_photo).right <= shutter.left);
            assertTrue(root.contains(flip));
            assertTrue(root.contains(deviceBounds(activity, R.id.recent_photo)));
            assertTrue(root.contains(shutter));
        });
    }

    private static Rect deviceBounds(MainActivity activity, int id) {
        Rect logical = bounds(activity.findViewById(id));
        Rect screen = bounds(activity.findViewById(R.id.main));
        switch (activity.findViewById(R.id.main).getDisplay().getRotation()) {
            case Surface.ROTATION_90:
                return new Rect(screen.bottom - logical.bottom, logical.left,
                        screen.bottom - logical.top, logical.right);
            case Surface.ROTATION_180:
                return new Rect(screen.right - logical.right, screen.bottom - logical.bottom,
                        screen.right - logical.left, screen.bottom - logical.top);
            case Surface.ROTATION_270:
                return new Rect(logical.top, screen.right - logical.right,
                        logical.bottom, screen.right - logical.left);
            default: return logical;
        }
    }

    private static Rect bounds(View view) {
        Rect rectangle = new Rect();
        assertTrue("The view has visible bounds.", view.getGlobalVisibleRect(rectangle));
        return rectangle;
    }

    private static void awaitOrientation(ActivityScenario<MainActivity> scenario, int orientation) {
        long end = SystemClock.elapsedRealtime() + 10_000;
        AtomicBoolean ready = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> ready.set(activity.getResources().getConfiguration()
                    .orientation == orientation));
            if (ready.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < end);
        fail("Screen did not rotate.");
    }
}

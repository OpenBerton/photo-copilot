package com.yrf3.photocopilot;

import static org.junit.Assert.*;

import android.app.UiAutomation;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.SeekBar;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class CameraLayoutTest {
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
            Rect preview = bounds(activity.findViewById(R.id.preview_view));
            Rect zoom = bounds(activity.findViewById(R.id.zoom_button));
            Rect shutter = bounds(activity.findViewById(R.id.shutter_button));
            Rect flip = bounds(activity.findViewById(R.id.switch_camera));
            Rect root = bounds(activity.findViewById(R.id.main));
            boolean landscape = activity.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            assertTrue("Zoom stays outside the preview.", landscape
                    ? zoom.left >= preview.right : zoom.top >= preview.bottom);
            if (landscape) {
                assertTrue("Landscape preview must not collapse to a small strip.",
                        preview.height() > root.height() * 0.6f);
            }
            assertTrue("Zoom must stay above the shutter.", zoom.bottom <= shutter.top);
            assertTrue("Camera switching belongs to the right of the shutter.", flip.left >= shutter.right);
            assertTrue(Math.abs(flip.centerY() - shutter.centerY()) <= 2);
            View ruler = activity.findViewById(activity.getResources().getIdentifier(
                    "zoom_slider", "id", activity.getPackageName()));
            assertTrue("A draggable zoom ruler is available.", ruler instanceof SeekBar);
            Rect rulerBounds = bounds(ruler);
            assertTrue(landscape ? rulerBounds.left >= preview.right
                    : rulerBounds.top >= preview.bottom);
            assertTrue(rulerBounds.bottom <= shutter.top);
            assertTrue("The preview retains usable space.", preview.width() > 0 && preview.height() > 48);
            View recent = activity.findViewById(activity.getResources().getIdentifier(
                    "recent_photo", "id", activity.getPackageName()));
            assertNotNull("The last-photo entry belongs on the left.", recent);
            assertTrue(bounds(recent).right <= shutter.left);
            assertTrue(root.contains(flip));
            assertTrue(root.contains(bounds(recent)));
            assertTrue(root.contains(shutter));
        });
    }

    private static Rect bounds(View view) {
        int[] position = new int[2];
        view.getLocationOnScreen(position);
        return new Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight());
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

package com.yrf3.photocopilot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.camera.view.PreviewView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class CameraPreviewTest {
    /**
     * Run against a fresh install, or clear this app's data before starting instrumentation.
     * Permission revocation must happen outside the test: Android kills the target process when
     * revoking CAMERA, which would also stop the instrumentation runner.
     * Requires a device or emulator with a working camera. A screenshot is saved locally under
     * the target app's external files directory in test-artifacts/camera-preview.png.
     */
    @Test
    public void permissionStatesSurviveActivityRecreation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Clear the app's data before this test so the initial permission is denied.",
                PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.CAMERA));

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertPermissionRequired(scenario);
            scenario.recreate();
            assertPermissionRequired(scenario);
        }

        grantCameraPermission(context.getPackageName());
        assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.CAMERA));

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertPreviewVisible(scenario);
            awaitStreaming(scenario);
            scenario.moveToState(Lifecycle.State.CREATED);
            awaitStreamState(scenario, PreviewView.StreamState.IDLE);
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitStreaming(scenario);
            scenario.recreate();
            assertPreviewVisible(scenario);
            awaitStreaming(scenario);
            assertEquals(PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(Manifest.permission.CAMERA));
            saveScreenshot(context);
        }
    }

    private static void assertPermissionRequired(ActivityScenario<MainActivity> scenario) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals("Opening the app must not automatically show a permission dialog.",
                Lifecycle.State.RESUMED, scenario.getState());
        scenario.onActivity(activity -> {
            TextView status = activity.findViewById(R.id.camera_status);
            Button action = activity.findViewById(R.id.camera_action);
            View preview = activity.findViewById(R.id.preview_view);
            assertNotNull(status);
            assertNotNull(action);
            assertNotNull(preview);
            assertTrue("Explain why camera access is needed.", status.isShown());
            assertFalse(status.getText().toString().trim().isEmpty());
            assertTrue("The user must be able to request camera access.", action.isShown());
            assertTrue(action.isEnabled());
            assertFalse(action.getText().toString().trim().isEmpty());
            assertFalse("Keep the preview hidden until permission is granted.", preview.isShown());
        });
    }

    private static void assertPreviewVisible(ActivityScenario<MainActivity> scenario) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(Lifecycle.State.RESUMED, scenario.getState());
        scenario.onActivity(activity -> {
            View preview = activity.findViewById(R.id.preview_view);
            assertNotNull(preview);
            assertTrue("Show the preview surface after camera access is granted.", preview.isShown());
        });
    }

    private static void awaitStreaming(ActivityScenario<MainActivity> scenario) {
        awaitStreamState(scenario, PreviewView.StreamState.STREAMING);
    }

    private static void awaitStreamState(ActivityScenario<MainActivity> scenario,
            PreviewView.StreamState expected) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        AtomicReference<PreviewView.StreamState> state = new AtomicReference<>();
        do {
            scenario.onActivity(activity -> {
                PreviewView preview = activity.findViewById(R.id.preview_view);
                state.set(preview.getPreviewStreamState().getValue());
            });
            if (state.get() == expected) {
                return;
            }
            // Only the instrumentation thread waits; CameraX and the UI remain free to start.
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Camera preview did not reach " + expected
                + " within 15 seconds; last state: " + state.get());
    }

    private static void saveScreenshot(Context context) throws Exception {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().takeScreenshot();
        assertNotNull("The device must allow a screenshot of the camera preview.", screenshot);
        try {
            File directory = context.getExternalFilesDir("test-artifacts");
            assertNotNull("External test-artifact storage must be available.", directory);
            try (FileOutputStream output = new FileOutputStream(
                    new File(directory, "camera-preview.png"))) {
                assertTrue("Save the preview screenshot as a PNG.",
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally {
            screenshot.recycle();
        }
    }

    private static void grantCameraPermission(String packageName) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(
                        "pm grant " + packageName + " " + Manifest.permission.CAMERA);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        assertTrue("Camera permission grant failed: "
                        + new String(output.toByteArray(), StandardCharsets.UTF_8),
                output.size() == 0);
    }
}

package com.yrf3.photocopilot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.app.Instrumentation;
import android.app.Activity;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.widget.SeekBar;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.view.MotionEvent;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ZoomState;
import com.yrf3.photocopilot.camera.CameraSession;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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

        grantPermission(context.getPackageName(), Manifest.permission.CAMERA);
        assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.CAMERA));
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            grantPermission(context.getPackageName(), Manifest.permission.WRITE_EXTERNAL_STORAGE);
            assertEquals(PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE));
        }

        Set<Uri> photosBeforeTest = queryPhotoUris(context);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertPreviewVisible(scenario);
            awaitStreaming(scenario);
            verifyControls(scenario);
            AtomicReference<Integer> selectedLens = new AtomicReference<>();
            AtomicReference<Integer> selectedFlash = new AtomicReference<>();
            AtomicReference<Float> selectedZoom = new AtomicReference<>();
            scenario.onActivity(activity -> {
                selectedLens.set(activity.getCameraSession().getLensFacing());
                selectedFlash.set(activity.getCameraSession().getFlashMode());
                selectedZoom.set(activity.getCameraSession().getZoomState().getZoomRatio());
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            awaitStreamState(scenario, PreviewView.StreamState.IDLE);
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitStreaming(scenario);
            scenario.recreate();
            assertPreviewVisible(scenario);
            awaitStreaming(scenario);
            awaitZoomRatio(scenario, selectedZoom.get());
            assertEquals(PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(Manifest.permission.CAMERA));
            scenario.onActivity(activity -> {
                assertEquals((int) selectedLens.get(), activity.getCameraSession().getLensFacing());
                assertEquals((int) selectedFlash.get(), activity.getCameraSession().getFlashMode());
                assertTrue(activity.findViewById(R.id.grid_button).isSelected());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.composition_grid).getVisibility());
            });
            rotateAndAwait(scenario, UiAutomation.ROTATION_FREEZE_90,
                    Configuration.ORIENTATION_LANDSCAPE);
            saveScreenshot(context, "camera-preview-landscape.png");
            rotateAndAwait(scenario, UiAutomation.ROTATION_FREEZE_0,
                    Configuration.ORIENTATION_PORTRAIT);
            scenario.onActivity(activity -> {
                assertEquals((int) selectedLens.get(), activity.getCameraSession().getLensFacing());
                assertEquals((int) selectedFlash.get(), activity.getCameraSession().getFlashMode());
                assertTrue(activity.findViewById(R.id.grid_button).isSelected());
            });
            awaitZoomRatio(scenario, selectedZoom.get());
            saveScreenshot(context);

            Uri firstPhoto = capturePhoto(context, scenario, true);
            verifyRecentPhotoOpens(scenario, firstPhoto);
            // A temporary dialog pauses the activity without stopping the camera lifecycle.
            scenario.moveToState(Lifecycle.State.STARTED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitCaptureReady(scenario);
            Uri secondPhoto = capturePhoto(context, scenario, false);
            assertFalse("Each shutter action must save a distinct photo.",
                    firstPhoto.equals(secondPhoto));
            saveScreenshot(context);
        } finally {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .setRotation(UiAutomation.ROTATION_UNFREEZE);
            // Preserve existing gallery photos, including photos from earlier test runs.
            Set<Uri> photosCreatedByTest = queryPhotoUris(context);
            photosCreatedByTest.removeAll(photosBeforeTest);
            for (Uri photo : photosCreatedByTest) {
                context.getContentResolver().delete(photo, null, null);
            }
        }
    }

    private static void verifyControls(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            View button = activity.findViewById(R.id.grid_button);
            button.performClick();
            assertTrue(button.isSelected());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.composition_grid).getVisibility());
            button.performClick();
            assertEquals(View.GONE, activity.findViewById(R.id.composition_grid).getVisibility());
            button.performClick();
        });
        AtomicReference<Integer> previousLens = new AtomicReference<>();
        AtomicBoolean canSwitch = new AtomicBoolean();
        scenario.onActivity(activity -> {
            CameraSession camera = activity.getCameraSession();
            previousLens.set(camera.getLensFacing());
            canSwitch.set(camera.canSwitchCamera());
            ZoomState current = camera.getZoomState();
            System.out.println("Initial camera: lens=" + camera.getLensFacing()
                    + ", flash=" + camera.hasFlash() + ", zoom=" + current.getMinZoomRatio()
                    + ".." + current.getMaxZoomRatio());
            View button = activity.findViewById(R.id.switch_camera);
            assertEquals(camera.canSwitchCamera(), button.isEnabled());
            if (camera.canSwitchCamera()) {
                button.performClick();
                assertFalse("Wait for the new camera before another switch.", button.isEnabled());
            }
        });
        awaitCaptureReady(scenario);
        scenario.onActivity(activity -> {
            CameraSession camera = activity.getCameraSession();
            if (canSwitch.get()) {
                assertFalse(previousLens.get() == camera.getLensFacing());
            }
            ZoomState current = camera.getZoomState();
            System.out.println("Selected camera: lens=" + camera.getLensFacing()
                    + ", flash=" + camera.hasFlash() + ", zoom=" + current.getMinZoomRatio()
                    + ".." + current.getMaxZoomRatio());
            View flash = activity.findViewById(R.id.flash_button);
            assertEquals(camera.hasFlash(), flash.isEnabled());
            assertEquals(ImageCapture.FLASH_MODE_OFF, camera.getFlashMode());
            if (camera.hasFlash()) {
                flash.performClick();
                assertEquals(ImageCapture.FLASH_MODE_AUTO, camera.getFlashMode());
                flash.performClick();
                assertEquals(ImageCapture.FLASH_MODE_ON, camera.getFlashMode());
                flash.performClick();
                assertEquals(ImageCapture.FLASH_MODE_OFF, camera.getFlashMode());
            }
        });
        AtomicBoolean zoomSupported = new AtomicBoolean();
        scenario.onActivity(activity -> {
            ZoomState zoom = activity.getCameraSession().getZoomState();
            zoomSupported.set(zoom != null && zoom.getMaxZoomRatio() > 1.1f);
        });
        if (zoomSupported.get()) {
            pinchOut(scenario);
            awaitZoom(scenario, false);
            scenario.onActivity(activity -> activity.findViewById(R.id.zoom_button).performLongClick());
            awaitZoom(scenario, true);
            scenario.onActivity(activity -> activity.findViewById(R.id.zoom_button).performClick());
            awaitZoom(scenario, false);
            dragZoomRuler(scenario);
            awaitZoom(scenario, false);
        }
    }

    private static void dragZoomRuler(ActivityScenario<MainActivity> scenario) {
        float[] point = new float[4];
        scenario.onActivity(activity -> {
            View ruler = activity.findViewById(activity.getResources().getIdentifier(
                    "zoom_slider", "id", activity.getPackageName()));
            assertTrue(ruler instanceof SeekBar);
            assertTrue(ruler.isEnabled());
            int[] location = new int[2];
            ruler.getLocationOnScreen(location);
            point[0] = location[0] + ruler.getWidth() * 0.2f;
            point[1] = location[0] + ruler.getWidth() * 0.7f;
            point[2] = location[1] + ruler.getHeight() / 2f;
        });
        long start = SystemClock.uptimeMillis();
        for (int index = 0; index <= 8; index++) {
            int action = index == 0 ? MotionEvent.ACTION_DOWN
                    : index == 8 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                    point[0] + (point[1] - point[0]) * index / 8f, point[2], 0);
            try {
                InstrumentationRegistry.getInstrumentation().sendPointerSync(event);
            } finally {
                event.recycle();
            }
            SystemClock.sleep(30);
        }
    }

    private static void verifyRecentPhotoOpens(ActivityScenario<MainActivity> scenario, Uri uri) {
        AtomicReference<Intent> opened = new AtomicReference<>();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                    opened.set(intent);
                    return new Instrumentation.ActivityResult(Activity.RESULT_OK, null);
                }
                return null;
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            scenario.onActivity(activity -> activity.findViewById(activity.getResources().getIdentifier(
                    "recent_photo", "id", activity.getPackageName())).performClick());
            assertNotNull("Tapping the thumbnail opens the saved photo.", opened.get());
            assertEquals(uri, opened.get().getData());
            assertEquals("image/jpeg", opened.get().getType());
            assertTrue((opened.get().getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private static void rotateAndAwait(ActivityScenario<MainActivity> scenario,
            int rotation, int expectedOrientation) {
        assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().setRotation(rotation));
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        AtomicBoolean rotated = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> rotated.set(activity.getResources().getConfiguration()
                    .orientation == expectedOrientation));
            if (rotated.get()) {
                awaitCaptureReady(scenario);
                return;
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("The device did not rotate to the requested orientation.");
    }

    private static void awaitZoomRatio(ActivityScenario<MainActivity> scenario, float expected) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        AtomicReference<Float> actual = new AtomicReference<>();
        do {
            scenario.onActivity(activity -> {
                ZoomState state = activity.getCameraSession().getZoomState();
                actual.set(state == null ? null : state.getZoomRatio());
            });
            if (actual.get() != null && Math.abs(actual.get() - expected) < 0.05f) {
                return;
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Zoom was not restored: expected " + expected + ", actual " + actual.get());
    }

    private static void awaitZoom(ActivityScenario<MainActivity> scenario, boolean reset) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        AtomicBoolean applied = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> {
                ZoomState state = activity.getCameraSession().getZoomState();
                applied.set(state != null && (reset ? Math.abs(state.getZoomRatio() - 1f) < 0.05f
                        : state.getZoomRatio() > 1.05f));
            });
            if (applied.get()) {
                return;
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("The camera did not apply the requested zoom.");
    }

    private static void pinchOut(ActivityScenario<MainActivity> scenario) {
        float[] center = new float[3];
        scenario.onActivity(activity -> {
            View preview = activity.findViewById(R.id.preview_view);
            int[] location = new int[2];
            preview.getLocationOnScreen(location);
            center[0] = location[0] + preview.getWidth() / 2f;
            center[1] = location[1] + preview.getHeight() / 2f;
            // The fingers spread horizontally, even when a short landscape preview is used.
            center[2] = preview.getWidth() * 0.22f;
        });
        long start = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
        for (int index = 0; index < 2; index++) {
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = index;
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[index] = new MotionEvent.PointerCoords();
            coordinates[index].y = center[1];
            coordinates[index].pressure = 1f;
            coordinates[index].size = 1f;
        }
        coordinates[0].x = center[0] - center[2];
        coordinates[1].x = center[0] + center[2];
        sendTouch(start, MotionEvent.ACTION_DOWN, 1, properties, coordinates);
        sendTouch(start, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, properties, coordinates);
        for (int step = 1; step <= 8; step++) {
            float span = center[2] * (1f + step * 0.12f);
            coordinates[0].x = center[0] - span;
            coordinates[1].x = center[0] + span;
            SystemClock.sleep(30);
            sendTouch(start, MotionEvent.ACTION_MOVE, 2, properties, coordinates);
        }
        sendTouch(start, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, properties, coordinates);
        sendTouch(start, MotionEvent.ACTION_UP, 1, properties, coordinates);
    }

    private static void sendTouch(long start, int action, int count,
            MotionEvent.PointerProperties[] properties, MotionEvent.PointerCoords[] coordinates) {
        MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, count,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            InstrumentationRegistry.getInstrumentation().sendPointerSync(event);
        } finally {
            event.recycle();
        }
    }

    private static Uri capturePhoto(Context context, ActivityScenario<MainActivity> scenario,
            boolean doubleTap) throws Exception {
        Set<Uri> photosBeforeCapture = queryPhotoUris(context);
        AtomicReference<Drawable> previousThumbnail = new AtomicReference<>();
        scenario.onActivity(activity -> {
            ImageButton recent = activity.findViewById(activity.getResources().getIdentifier(
                    "recent_photo", "id", activity.getPackageName()));
            assertNotNull(recent);
            previousThumbnail.set(recent.getDrawable());
            ImageButton shutter = activity.findViewById(R.id.shutter_button);
            assertNotNull("A shutter button must be available.", shutter);
            assertTrue(shutter.isShown());
            assertTrue("The camera must be ready before taking a photo.", shutter.isEnabled());
            shutter.performClick();
            if (doubleTap) {
                // Simulate two rapid taps before CameraX can finish saving the first image.
                shutter.performClick();
            }
        });
        awaitPhotoSaved(scenario, previousThumbnail.get());
        awaitStreaming(scenario);

        Set<Uri> newPhotos = queryPhotoUris(context);
        newPhotos.removeAll(photosBeforeCapture);
        assertEquals("A shutter action must create exactly one gallery photo, even after rapid taps.",
                1, newPhotos.size());
        Uri photo = newPhotos.iterator().next();
        ContentResolver resolver = context.getContentResolver();
        assertEquals("image/jpeg", resolver.getType(photo));
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(photo, "r")) {
            assertNotNull("The saved photo must be readable from MediaStore.", descriptor);
            assertTrue("The saved JPEG must contain data.", descriptor.getStatSize() > 0);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(photo)) {
            assertNotNull(input);
            BitmapFactory.decodeStream(input, null, options);
        }
        assertTrue("The saved JPEG must have a valid width.", options.outWidth > 0);
        assertTrue("The saved JPEG must have a valid height.", options.outHeight > 0);
        assertEquals("The saved image data must be a JPEG.", "image/jpeg", options.outMimeType);
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 1024
                || options.outHeight / options.inSampleSize > 1024) {
            options.inSampleSize *= 2;
        }
        try (InputStream input = resolver.openInputStream(photo)) {
            assertNotNull(input);
            Bitmap decoded = BitmapFactory.decodeStream(input, null, options);
            assertNotNull("The saved JPEG pixels must be decodable.", decoded);
            decoded.recycle();
        }
        return photo;
    }

    private static void awaitCaptureReady(ActivityScenario<MainActivity> scenario) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        AtomicBoolean ready = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> {
                PreviewView preview = activity.findViewById(R.id.preview_view);
                ImageButton shutter = activity.findViewById(R.id.shutter_button);
                assertNotNull(preview);
                assertNotNull(shutter);
                ready.set(preview.getPreviewStreamState().getValue()
                        == PreviewView.StreamState.STREAMING
                        && shutter.isShown() && shutter.isEnabled());
            });
            if (ready.get()) {
                return;
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Preview and shutter did not become ready within 15 seconds after resuming.");
    }

    private static void awaitPhotoSaved(ActivityScenario<MainActivity> scenario, Drawable previous) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        AtomicBoolean saved = new AtomicBoolean();
        AtomicReference<String> lastStatus = new AtomicReference<>();
        do {
            scenario.onActivity(activity -> {
                TextView status = activity.findViewById(R.id.photo_status);
                ImageButton shutter = activity.findViewById(R.id.shutter_button);
                assertNotNull(status);
                assertNotNull(shutter);
                lastStatus.set(status.getText().toString());
                ImageButton recent = activity.findViewById(activity.getResources().getIdentifier(
                        "recent_photo", "id", activity.getPackageName()));
                saved.set(shutter.isEnabled() && recent != null && recent.isEnabled()
                        && recent.getDrawable() instanceof BitmapDrawable
                        && recent.getDrawable() != previous
                        && activity.findViewById(activity.getResources().getIdentifier(
                                "capture_flash", "id", activity.getPackageName())).getVisibility() == View.GONE);
                if (saved.get()) {
                    assertFalse("Successful capture uses visual feedback, not a text banner.", status.isShown());
                }
            });
            if (saved.get()) {
                return;
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("The photo was not saved and the shutter did not become available within 15 seconds; "
                + "last status: " + lastStatus.get());
    }

    private static Set<Uri> queryPhotoUris(Context context) {
        Set<Uri> photos = new HashSet<>();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try (Cursor cursor = context.getContentResolver().query(collection,
                new String[] {MediaStore.Images.Media._ID},
                MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?",
                new String[] {"PhotoCopilot_%"}, null)) {
            assertNotNull("The image collection must be queryable.", cursor);
            while (cursor.moveToNext()) {
                photos.add(ContentUris.withAppendedId(collection, cursor.getLong(0)));
            }
        }
        return photos;
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
        saveScreenshot(context, "camera-preview.png");
    }

    private static void saveScreenshot(Context context, String filename) throws Exception {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().takeScreenshot();
        assertNotNull("The device must allow a screenshot of the camera preview.", screenshot);
        try {
            File directory = context.getExternalFilesDir("test-artifacts");
            assertNotNull("External test-artifact storage must be available.", directory);
            try (FileOutputStream output = new FileOutputStream(
                    new File(directory, filename))) {
                assertTrue("Save the preview screenshot as a PNG.",
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally {
            screenshot.recycle();
        }
    }

    private static void grantPermission(String packageName, String permission) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(
                        "pm grant " + packageName + " " + permission);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        assertTrue("Permission grant failed for " + permission + ": "
                        + new String(output.toByteArray(), StandardCharsets.UTF_8),
                output.size() == 0);
    }
}

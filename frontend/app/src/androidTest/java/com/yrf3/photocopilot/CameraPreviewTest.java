package com.yrf3.photocopilot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
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

            Uri firstPhoto = capturePhoto(context, scenario, true);
            // A temporary dialog pauses the activity without stopping the camera lifecycle.
            scenario.moveToState(Lifecycle.State.STARTED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitCaptureReady(scenario);
            Uri secondPhoto = capturePhoto(context, scenario, false);
            assertFalse("Each shutter action must save a distinct photo.",
                    firstPhoto.equals(secondPhoto));
        } finally {
            // Preserve existing gallery photos, including photos from earlier test runs.
            Set<Uri> photosCreatedByTest = queryPhotoUris(context);
            photosCreatedByTest.removeAll(photosBeforeTest);
            for (Uri photo : photosCreatedByTest) {
                context.getContentResolver().delete(photo, null, null);
            }
        }
    }

    private static Uri capturePhoto(Context context, ActivityScenario<MainActivity> scenario,
            boolean doubleTap) throws Exception {
        Set<Uri> photosBeforeCapture = queryPhotoUris(context);
        scenario.onActivity(activity -> {
            Button shutter = activity.findViewById(R.id.shutter_button);
            assertNotNull("A shutter button must be available.", shutter);
            assertTrue(shutter.isShown());
            assertTrue("The camera must be ready before taking a photo.", shutter.isEnabled());
            shutter.performClick();
            if (doubleTap) {
                // Simulate two rapid taps before CameraX can finish saving the first image.
                shutter.performClick();
            }
        });
        awaitPhotoSaved(scenario);
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
        return photo;
    }

    private static void awaitCaptureReady(ActivityScenario<MainActivity> scenario) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        AtomicBoolean ready = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> {
                PreviewView preview = activity.findViewById(R.id.preview_view);
                Button shutter = activity.findViewById(R.id.shutter_button);
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

    private static void awaitPhotoSaved(ActivityScenario<MainActivity> scenario) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        AtomicBoolean saved = new AtomicBoolean();
        AtomicReference<String> lastStatus = new AtomicReference<>();
        do {
            scenario.onActivity(activity -> {
                TextView status = activity.findViewById(R.id.photo_status);
                Button shutter = activity.findViewById(R.id.shutter_button);
                assertNotNull(status);
                assertNotNull(shutter);
                lastStatus.set(status.getText().toString());
                saved.set(status.isShown()
                        && activity.getString(R.string.photo_saved).contentEquals(status.getText())
                        && shutter.isEnabled());
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

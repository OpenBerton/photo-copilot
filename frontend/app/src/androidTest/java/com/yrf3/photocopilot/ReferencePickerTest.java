package com.yrf3.photocopilot;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageButton;

import com.yrf3.photocopilot.reference.ReferencePhoto;
import com.yrf3.photocopilot.reference.ReferencePicker;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.io.File;
import java.io.FileOutputStream;

@RunWith(AndroidJUnit4.class)
public class ReferencePickerTest {
    private UiAutomation device;
    private int originalFlags;

    @Before
    public void enableViewIds() {
        device = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AccessibilityServiceInfo info = device.getServiceInfo();
        originalFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        device.setServiceInfo(info);
    }

    @After
    public void restoreAccessibilityFlags() {
        device.setRotation(UiAutomation.ROTATION_UNFREEZE);
        AccessibilityServiceInfo info = device.getServiceInfo();
        info.flags = originalFlags;
        device.setServiceInfo(info);
    }

    @Test
    public void referenceEntryOpensDemoPhotos() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View entry = activity.findViewById(activity.getResources().getIdentifier(
                        "reference_button", "id", activity.getPackageName()));
                assertNotNull("The camera has an entry for choosing reference photos.", entry);
                assertTrue(entry.isShown());
                entry.performClick();
            });
            assertNotNull(awaitNode("reference_demo"));
            assertNotNull(awaitNode("close_references"));
            awaitPhoto(label(R.string.reference_waterfront), false);
            assertFalse(awaitNode("clear_reference").isEnabled());
            assertSheetFitsScreen(scenario);
            saveScreenshot("reference-picker.png");
            click("close_references");
            awaitSelection(scenario, null);
        }
    }

    @Test
    public void selectionSurvivesClosingAndRecreationAndCanBeCleared() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            open(scenario);
            assertTrue(awaitPhoto(label(R.string.reference_waterfront), false)
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK));
            awaitSelection(scenario, label(R.string.reference_waterfront));
            saveScreenshot("reference-selected.png");
            open(scenario);
            assertTrue(awaitPhoto(label(R.string.reference_waterfront), false).isSelected());
            saveScreenshot("reference-picker-selected.png");
            click("close_references");
            awaitSelection(scenario, label(R.string.reference_waterfront));
            scenario.recreate();
            awaitSelection(scenario, label(R.string.reference_waterfront));
            open(scenario);
            assertTrue(awaitNode("clear_reference").isEnabled());
            click("clear_reference");
            awaitSelection(scenario, null);
            scenario.recreate();
            awaitSelection(scenario, null);
        }
    }

    @Test
    public void offscreenSelectionRemainsVisibleWhenReopenedAndRotated() throws Exception {
        assertTrue(device.setRotation(UiAutomation.ROTATION_FREEZE_0));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            open(scenario);
            assertTrue(awaitPhoto(label(R.string.reference_mountains), true)
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK));
            awaitSelection(scenario, label(R.string.reference_mountains));
            open(scenario);
            assertTrue("Reopening reveals the selected photo without another manual scroll.",
                    awaitPhoto(label(R.string.reference_mountains), false).isSelected());
            assertTrue(device.setRotation(UiAutomation.ROTATION_FREEZE_90));
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            AtomicBoolean rotated = new AtomicBoolean();
            do {
                scenario.onActivity(activity -> rotated.set(activity.getResources().getConfiguration()
                        .orientation == Configuration.ORIENTATION_LANDSCAPE));
                if (rotated.get()) break;
                SystemClock.sleep(50);
            } while (SystemClock.elapsedRealtime() < deadline);
            assertTrue("Device rotated to landscape.", rotated.get());
            assertNotNull(awaitNode("reference_demo"));
            assertTrue(awaitPhoto(label(R.string.reference_mountains), false).isSelected());
            assertSheetFitsScreen(scenario);
            saveScreenshot("reference-picker-landscape.png");
            assertTrue(device.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
            awaitSelection(scenario, label(R.string.reference_mountains));
        }
    }

    @Test
    public void emptyOrUnavailablePhotosShowAPromptWithoutSelectingABrokenImage() {
        AtomicReference<ReferencePicker> picker = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    ReferencePicker empty = new ReferencePicker(activity, new ImageButton(activity),
                            Collections.emptyList(), "unknown");
                    picker.set(empty);
                    assertNull(empty.getSelectedId());
                    empty.show();
                });
                assertEquals(label(R.string.reference_empty), awaitNode("reference_status").getText().toString());
                assertFalse(awaitNode("clear_reference").isEnabled());
                scenario.onActivity(activity -> picker.get().close());
                scenario.onActivity(activity -> {
                    ReferencePhoto broken = new ReferencePhoto("broken",
                            Uri.parse("android.resource://" + activity.getPackageName() + "/drawable/missing_reference"),
                            "Unavailable reference");
                    ReferencePicker unavailable = new ReferencePicker(activity, new ImageButton(activity),
                            Collections.singletonList(broken), "broken");
                    picker.set(unavailable);
                    unavailable.show();
                });
                assertEquals(label(R.string.reference_load_error), awaitNode("reference_status").getText().toString());
                assertFalse(awaitNode("reference_item").isEnabled());
                scenario.onActivity(activity -> assertNull(picker.get().getSelectedId()));
            } finally {
                scenario.onActivity(activity -> { if (picker.get() != null) picker.get().close(); });
            }
        }
    }

    private void open(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> activity.findViewById(R.id.reference_button).performClick());
        awaitNode("reference_demo");
    }

    private void click(String id) {
        assertTrue(awaitNode(id).performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }

    private String label(int resource) {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getString(resource);
    }

    private void awaitSelection(ActivityScenario<MainActivity> scenario, String expected) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        AtomicBoolean ready = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> {
                ImageButton entry = activity.findViewById(R.id.reference_button);
                ready.set(expected == null ? !entry.isSelected() : entry.isSelected()
                        && entry.getDrawable() instanceof BitmapDrawable
                        && entry.getContentDescription().toString().contains(expected));
            });
            if (ready.get() && nodes("reference_demo").isEmpty()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Reference selection or sheet dismissal did not match: " + expected);
    }

    private AccessibilityNodeInfo awaitPhoto(String description, boolean scroll) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            for (AccessibilityNodeInfo node : nodes("reference_item")) {
                Rect photo = new Rect();
                Rect gallery = new Rect();
                node.getBoundsInScreen(photo);
                awaitNode("reference_gallery").getBoundsInScreen(gallery);
                if (description.contentEquals(node.getContentDescription()) && node.isEnabled()
                        && node.isVisibleToUser() && !photo.isEmpty() && gallery.contains(photo)) return node;
            }
            if (scroll) {
                List<AccessibilityNodeInfo> galleries = nodes("reference_gallery");
                if (!galleries.isEmpty()) galleries.get(0).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("No loaded, visible reference photo: " + description);
    }

    private void assertSheetFitsScreen(ActivityScenario<MainActivity> scenario) {
        Rect panel = new Rect();
        Rect close = new Rect();
        awaitNode("reference_sheet").getBoundsInScreen(panel);
        awaitNode("close_references").getBoundsInScreen(close);
        assertTrue("The close action stays in the panel.", panel.contains(close));
        scenario.onActivity(activity -> {
            View root = activity.findViewById(R.id.main);
            int[] position = new int[2];
            root.getLocationOnScreen(position);
            Rect screen = new Rect(position[0], position[1], position[0] + root.getWidth(), position[1] + root.getHeight());
            assertTrue("The panel fits this screen and orientation.", screen.contains(panel));
        });
    }

    private void saveScreenshot(String name) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        boolean loaded;
        do {
            loaded = true;
            for (AccessibilityNodeInfo node : nodes("reference_item")) {
                if (node.isVisibleToUser() && !node.isEnabled()) loaded = false;
            }
            if (loaded) break;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("Visible demo thumbnails have loaded before the screenshot.", loaded);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        // Dialog fade animations outlive view removal and the main-thread idle queue.
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SystemClock.sleep(target.getResources().getInteger(android.R.integer.config_longAnimTime) * 2L);
        Bitmap image = device.takeScreenshot();
        assertNotNull(image);
        try {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            File directory = context.getExternalFilesDir("test-artifacts");
            assertNotNull(directory);
            try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally {
            image.recycle();
        }
    }

    private List<AccessibilityNodeInfo> nodes(String id) {
        AccessibilityNodeInfo root = device.getRootInActiveWindow();
        return root == null ? Collections.emptyList() : root.findAccessibilityNodeInfosByViewId(
                "com.yrf3.photocopilot:id/" + id);
    }

    private AccessibilityNodeInfo awaitNode(String id) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            List<AccessibilityNodeInfo> matches = nodes(id);
            if (!matches.isEmpty()) return matches.get(0);
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("No visible UI node: " + id);
    }
}

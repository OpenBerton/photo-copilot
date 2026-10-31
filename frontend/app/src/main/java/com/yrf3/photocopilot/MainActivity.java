package com.yrf3.photocopilot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.content.res.ColorStateList;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.SeekBar;
import com.yrf3.photocopilot.camera.ZoomRuler;
import com.yrf3.photocopilot.camera.ZoomControl;
import com.yrf3.photocopilot.camera.RecentPhoto;
import com.yrf3.photocopilot.reference.ReferenceClient;
import com.yrf3.photocopilot.reference.ReferencePicker;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.yrf3.photocopilot.camera.CameraSession;
import com.yrf3.photocopilot.camera.CompositionGrid;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ZoomState;
import java.util.Locale;

public class MainActivity extends ComponentActivity {
    private static final String PERMISSIONS = "camera_permissions";
    private static final String REQUESTED = "requested";
    private static final String CAMERA_PERMISSION_PENDING = "camera_permission_pending";
    private static final String LOCAL_NETWORK_REQUESTED = "local_network_requested";

    private CameraSession cameraSession;
    private TextView status;
    private Button action;
    private View messagePanel;
    private View previewView;
    private ActivityResultLauncher<String> permissionRequest;
    private boolean cameraPermissionPending;
    private ActivityResultLauncher<String> storageRequest;
    private ActivityResultLauncher<String> localNetworkRequest;
    private boolean openReferencesAfterPermission;
    private ImageButton shutter;
    private TextView photoStatus;
    private ImageButton switchCamera;
    private ImageButton flash;
    private ImageButton gridButton;
    private Button zoomButton;
    private ZoomRuler zoomSlider;
    private ZoomControl zoomControl;
    private RecentPhoto recentPhoto;
    private ReferencePicker references;
    private boolean reopenReferences;
    private View captureFlash;
    private View saveProgress;
    private CompositionGrid grid;
    private boolean gridVisible;
    private final Runnable hidePhotoStatus = () -> photoStatus.setVisibility(View.GONE);
    private boolean captureInProgress;
    private boolean captureAfterPermission;
    private static final String STORAGE_REQUESTED = "storage_requested";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraPermissionPending = savedInstanceState != null
                && savedInstanceState.getBoolean(CAMERA_PERMISSION_PENDING);
        openReferencesAfterPermission = savedInstanceState != null
                && savedInstanceState.getBoolean("reference_after_permission");
        permissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    cameraPermissionPending = false;
                    updateCamera();
                });
        localNetworkRequest = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        getSharedPreferences(PERMISSIONS, MODE_PRIVATE).edit()
                                .remove(LOCAL_NETWORK_REQUESTED).apply();
                        openReferencesAfterPermission = true;
                        maybeOpenReferences();
                    } else {
                        showPhotoStatus(R.string.reference_network_permission, true);
                    }
                });
        storageRequest = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        getSharedPreferences(PERMISSIONS, MODE_PRIVATE).edit()
                                .remove(STORAGE_REQUESTED).apply();
                        captureAfterPermission = true;
                        maybeCaptureAfterPermission();
                    } else {
                        showPhotoStatus(R.string.photo_storage_permission, false);
                        updateShutter();
                    }
                });
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.camera_status);
        action = findViewById(R.id.camera_action);
        messagePanel = findViewById(R.id.camera_message_panel);
        previewView = findViewById(R.id.preview_view);
        shutter = findViewById(R.id.shutter_button);
        photoStatus = findViewById(R.id.photo_status);
        shutter.setOnClickListener(view -> requestPhoto());
        switchCamera = findViewById(R.id.switch_camera);
        flash = findViewById(R.id.flash_button);
        gridButton = findViewById(R.id.grid_button);
        zoomButton = findViewById(R.id.zoom_button);
        zoomSlider = findViewById(R.id.zoom_slider);
        zoomControl = findViewById(R.id.zoom_control);
        captureFlash = findViewById(R.id.capture_flash);
        saveProgress = findViewById(R.id.save_progress);
        recentPhoto = new RecentPhoto(this, findViewById(R.id.recent_photo),
                () -> showPhotoStatus(R.string.photo_cannot_open, true));
        references = new ReferencePicker(this, findViewById(R.id.reference_button),
                new ReferenceClient(BuildConfig.API_BASE_URL),
                () -> cameraSession == null ? null : cameraSession.capturePreviewFrame(), savedInstanceState);
        findViewById(R.id.reference_button).setOnClickListener(view -> requestReferences());
        reopenReferences = savedInstanceState != null && savedInstanceState.getBoolean("reference_open");
        zoomSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                if (fromUser && cameraSession != null) cameraSession.setZoomRatio(zoomSlider.getZoomRatio());
            }
            @Override public void onStartTrackingTouch(SeekBar slider) { }
            @Override public void onStopTrackingTouch(SeekBar slider) { }
        });
        grid = findViewById(R.id.composition_grid);
        androidx.core.view.WindowInsetsControllerCompat systemBars =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        systemBars.setAppearanceLightStatusBars(false);
        systemBars.setAppearanceLightNavigationBars(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        gridVisible = savedInstanceState != null && savedInstanceState.getBoolean("grid");
        switchCamera.setOnClickListener(view -> cameraSession.switchCamera());
        flash.setOnClickListener(view -> cameraSession.cycleFlash());
        gridButton.setOnClickListener(view -> {
            gridVisible = !gridVisible;
            updateControls();
        });
        zoomButton.setOnClickListener(view -> {
            ZoomState state = cameraSession.getZoomState();
            if (state != null) {
                float high = Math.min(2f, state.getMaxZoomRatio());
                float low = Math.min(1f, state.getMinZoomRatio());
                cameraSession.setZoomRatio(state.getZoomRatio() < 0.95f ? 1f
                        : state.getZoomRatio() < high - 0.05f ? high : low);
            }
        });
        zoomButton.setOnLongClickListener(view -> {
            cameraSession.setZoomRatio(1f);
            return true;
        });
        ScaleGestureDetector scaling = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        cameraSession.scaleZoom(detector.getScaleFactor());
                        return true;
                    }
                });
        previewView.setOnTouchListener((view, event) -> {
            scaling.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                view.performClick();
            }
            return true;
        });
        getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event == Lifecycle.Event.ON_RESUME) {
                updateShutter();
                maybeCaptureAfterPermission();
                maybeOpenReferences();
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        cameraSession = new CameraSession(this, this, findViewById(R.id.preview_view),
                new CameraSession.Listener() {
                    @Override
                    public void onLoading() {
                        showStatus(R.string.camera_starting, 0);
                    }

                    @Override
                    public void onStreaming() {
                        messagePanel.setVisibility(View.GONE);
                        status.setVisibility(View.GONE);
                        action.setVisibility(View.GONE);
                        updateShutter();
                        maybeCaptureAfterPermission();
                        maybeOpenReferences();
                        if (reopenReferences) {
                            reopenReferences = false;
                            if (!needsLocalNetworkPermission()) references.showRestored();
                        }
                    }

                    @Override
                    public void onPreviewIdle() {
                        updateShutter();
                    }

                    @Override
                    public void onControlsChanged() {
                        updateControls();
                    }

                    @Override
                    public void onControlError() {
                        showPhotoStatus(R.string.camera_control_error, true);
                    }

                    @Override
                    public void onError(boolean noCamera) {
                        if (!hasCameraPermission()) {
                            showPermissionState();
                        } else {
                            showStatus(noCamera ? R.string.camera_missing : R.string.camera_error,
                                    noCamera ? 0 : R.string.retry);
                        }
                    }
                });
        if (savedInstanceState != null) {
            cameraSession.restoreControls(savedInstanceState.getInt("lens",
                            CameraSelector.LENS_FACING_BACK),
                    savedInstanceState.getInt("flash", ImageCapture.FLASH_MODE_OFF),
                    savedInstanceState.getFloat("zoom", 1f));
        }
        updateControls();
        action.setOnClickListener(view -> {
            if (hasCameraPermission()) {
                cameraSession.stop();
                cameraSession.start();
            } else if (needsSettings()) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                requestCameraPermission();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCamera();
        recentPhoto.reload();
    }

    @Override
    protected void onPause() {
        reopenReferences = references.isShowing();
        references.pause();
        setControlEnabled(shutter, false);
        setControlEnabled(switchCamera, false);
        setControlEnabled(flash, false);
        setControlEnabled(zoomButton, false);
        setControlEnabled(zoomControl, false);
        super.onPause();
    }

    private void updateCamera() {
        if (cameraSession == null) {
            return;
        }
        if (hasCameraPermission()) {
            cameraPermissionPending = false;
            // One-time grants and Android auto-reset must allow a new request later.
            getSharedPreferences(PERMISSIONS, MODE_PRIVATE).edit()
                    .remove(REQUESTED).apply();
            previewView.setVisibility(View.VISIBLE);
            cameraSession.start();
        } else {
            cameraSession.stop();
            previewView.setVisibility(View.INVISIBLE);
            if (cameraPermissionPending) {
                messagePanel.setVisibility(View.GONE);
            } else if (!getSharedPreferences(PERMISSIONS, MODE_PRIVATE).getBoolean(REQUESTED, false)) {
                requestCameraPermission();
            } else {
                showPermissionState();
            }
        }
    }

    private void requestCameraPermission() {
        if (cameraPermissionPending) return;
        cameraPermissionPending = true;
        messagePanel.setVisibility(View.GONE);
        getSharedPreferences(PERMISSIONS, MODE_PRIVATE).edit().putBoolean(REQUESTED, true).apply();
        permissionRequest.launch(Manifest.permission.CAMERA);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsSettings() {
        return getSharedPreferences(PERMISSIONS, MODE_PRIVATE).getBoolean(REQUESTED, false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
    }

    private void showPermissionState() {
        showStatus(needsSettings() ? R.string.camera_permission_settings
                        : R.string.camera_permission_explanation,
                needsSettings() ? R.string.open_settings : R.string.enable_camera);
    }

    private void showStatus(int message, int buttonLabel) {
        updateShutter();
        messagePanel.setVisibility(View.VISIBLE);
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        action.setVisibility(buttonLabel == 0 ? View.GONE : View.VISIBLE);
        if (buttonLabel != 0) {
            action.setText(buttonLabel);
        }
    }

    private boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean storageNeedsSettings() {
        return !hasStoragePermission()
                && getSharedPreferences(PERMISSIONS, MODE_PRIVATE)
                        .getBoolean(STORAGE_REQUESTED, false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void updateShutter() {
        if (shutter != null) {
            shutter.setEnabled(!captureInProgress && cameraSession != null
                    && cameraSession.isReadyToCapture());
            shutter.setAlpha(shutter.isEnabled() ? 1f : 0.35f);
            shutter.setContentDescription(getString(captureInProgress ? R.string.photo_saving
                    : storageNeedsSettings() ? R.string.open_settings : R.string.take_photo));
        }
        saveProgress.setVisibility(captureInProgress ? View.VISIBLE : View.GONE);
        updateControls();
    }

    private void updateControls() {
        if (cameraSession == null || switchCamera == null) {
            return;
        }
        boolean ready = cameraSession.isReadyToCapture() && !captureInProgress;
        references.setReady(ready);
        setControlEnabled(switchCamera, ready && cameraSession.canSwitchCamera());
        switchCamera.setSelected(cameraSession.getLensFacing() == CameraSelector.LENS_FACING_FRONT);
        setControlEnabled(flash, ready && cameraSession.hasFlash());
        int mode = cameraSession.getFlashMode();
        flash.setImageResource(mode == ImageCapture.FLASH_MODE_ON ? R.drawable.ic_flash_on
                : mode == ImageCapture.FLASH_MODE_AUTO ? R.drawable.ic_flash_auto
                : R.drawable.ic_flash_off);
        flash.setContentDescription(getString(mode == ImageCapture.FLASH_MODE_ON
                ? R.string.flash_on : mode == ImageCapture.FLASH_MODE_AUTO
                ? R.string.flash_auto : R.string.flash_off));
        flash.setSelected(mode != ImageCapture.FLASH_MODE_OFF);
        grid.setImageAspectRatio(cameraSession.isPreviewReady()
                ? cameraSession.getPreviewAspectRatio() : 0f);
        grid.setVisibility(gridVisible ? View.VISIBLE : View.GONE);
        gridButton.setSelected(gridVisible);
        gridButton.setImageTintList(ColorStateList.valueOf(getColor(
                gridVisible ? R.color.accent : R.color.foreground)));
        gridButton.setContentDescription(getString(gridVisible ? R.string.grid_on : R.string.grid_off));
        ZoomState state = cameraSession.getZoomState();
        setControlEnabled(zoomButton, ready && state != null
                && state.getMaxZoomRatio() - state.getMinZoomRatio() > 0.001f);
        setControlEnabled(zoomControl, zoomButton.isEnabled());
        zoomControl.setZoomRange(state == null ? 1f : state.getMinZoomRatio(),
                state == null ? 1f : state.getMaxZoomRatio());
        float ratio = state == null ? 1f : state.getZoomRatio();
        zoomControl.showZoomRatio(ratio);
        zoomButton.setText(String.format(Locale.getDefault(),
                Math.abs(ratio - Math.round(ratio)) < 0.05f ? "%.0f×" : "%.1f×", ratio));
        zoomButton.setContentDescription(getString(R.string.zoom_description, ratio));
    }

    private void setControlEnabled(View control, boolean enabled) {
        control.setEnabled(enabled);
        control.setAlpha(enabled ? 1f : 0.35f);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(CAMERA_PERMISSION_PENDING, cameraPermissionPending);
        references.saveState(state);
        state.putBoolean("reference_open", reopenReferences || references.isShowing());
        state.putBoolean("reference_after_permission", openReferencesAfterPermission);
        state.putBoolean("grid", gridVisible);
        state.putInt("lens", cameraSession.getLensFacing());
        state.putInt("flash", cameraSession.getFlashMode());
        state.putFloat("zoom", cameraSession.getRequestedZoomRatio());
        super.onSaveInstanceState(state);
    }

    private void showPhotoStatus(int message, boolean temporary) {
        photoStatus.removeCallbacks(hidePhotoStatus);
        photoStatus.setText(message);
        photoStatus.setVisibility(View.VISIBLE);
        if (temporary) {
            photoStatus.postDelayed(hidePhotoStatus, 2500);
        }
    }

    private void requestPhoto() {
        if (captureInProgress || cameraSession == null || !cameraSession.isReadyToCapture()) {
            return;
        }
        if (hasStoragePermission()) {
            getSharedPreferences(PERMISSIONS, MODE_PRIVATE).edit()
                    .remove(STORAGE_REQUESTED).apply();
            capturePhoto();
        } else if (storageNeedsSettings()) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } else {
            getSharedPreferences(PERMISSIONS, MODE_PRIVATE).edit()
                    .putBoolean(STORAGE_REQUESTED, true).apply();
            storageRequest.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private boolean needsLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT < 37) return false;
        String host = Uri.parse(BuildConfig.API_BASE_URL).getHost();
        // Literal LAN addresses and local hostnames; never resolve DNS on the UI thread.
        boolean local = host != null && (host.startsWith("10.") || host.startsWith("192.168.")
                || host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*")
                || host.endsWith(".local") || !host.contains(".") || host.startsWith("[fc")
                || host.startsWith("[fd") || host.startsWith("[fe80:"));
        return local && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void requestReferences() {
        if (needsLocalNetworkPermission()) {
            if (getSharedPreferences(PERMISSIONS, MODE_PRIVATE).getBoolean(LOCAL_NETWORK_REQUESTED, false)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK)) {
                openReferencesAfterPermission = true;
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            getSharedPreferences(PERMISSIONS, MODE_PRIVATE).edit()
                    .putBoolean(LOCAL_NETWORK_REQUESTED, true).apply();
            localNetworkRequest.launch(Manifest.permission.ACCESS_LOCAL_NETWORK);
        } else {
            references.show();
        }
    }

    private void maybeOpenReferences() {
        if (openReferencesAfterPermission && cameraSession != null && cameraSession.isPreviewReady()
                && !needsLocalNetworkPermission()
                && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            openReferencesAfterPermission = false;
            references.show();
        }
    }

    private void maybeCaptureAfterPermission() {
        if (captureAfterPermission && cameraSession != null
                && cameraSession.isReadyToCapture()) {
            captureAfterPermission = false;
            capturePhoto();
        }
    }

    private void capturePhoto() {
        captureInProgress = true;
        photoStatus.removeCallbacks(hidePhotoStatus);
        photoStatus.setVisibility(View.GONE);
        updateShutter();
        boolean started = cameraSession.takePhoto(new CameraSession.PhotoCallback() {
            @Override
            public void onCaptureStarted() {
                captureFlash.animate().cancel();
                captureFlash.setAlpha(0.55f);
                captureFlash.setVisibility(View.VISIBLE);
                captureFlash.animate().alpha(0f).setDuration(160)
                        .withEndAction(() -> captureFlash.setVisibility(View.GONE)).start();
            }

            @Override
            public void onSaved(Uri uri) {
                captureInProgress = false;
                recentPhoto.show(uri, true);
                updateShutter();
            }

            @Override
            public void onError() {
                captureInProgress = false;
                showPhotoStatus(R.string.photo_error, false);
                updateShutter();
            }
        });
        if (!started) {
            captureInProgress = false;
            showPhotoStatus(R.string.photo_error, false);
            updateShutter();
        }
    }

    @Override
    protected void onDestroy() {
        photoStatus.removeCallbacks(hidePhotoStatus);
        captureFlash.animate().cancel();
        recentPhoto.close();
        references.close();
        if (cameraSession != null) {
            cameraSession.stop();
        }
        super.onDestroy();
    }
}

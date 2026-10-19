package com.yrf3.photocopilot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

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

public class MainActivity extends ComponentActivity {
    private static final String PERMISSIONS = "camera_permissions";
    private static final String REQUESTED = "requested";

    private CameraSession cameraSession;
    private TextView status;
    private Button action;
    private View messagePanel;
    private View previewView;
    private ActivityResultLauncher<String> permissionRequest;
    private ActivityResultLauncher<String> storageRequest;
    private ImageButton shutter;
    private TextView photoStatus;
    private final Runnable hidePhotoStatus = () -> photoStatus.setVisibility(View.GONE);
    private boolean captureInProgress;
    private boolean captureAfterPermission;
    private static final String STORAGE_REQUESTED = "storage_requested";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> updateCamera());
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
        getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event == Lifecycle.Event.ON_RESUME) {
                updateShutter();
                maybeCaptureAfterPermission();
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
                    }

                    @Override
                    public void onPreviewIdle() {
                        updateShutter();
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
        action.setOnClickListener(view -> {
            if (hasCameraPermission()) {
                cameraSession.stop();
                cameraSession.start();
            } else if (needsSettings()) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                getSharedPreferences(PERMISSIONS, MODE_PRIVATE).edit()
                        .putBoolean(REQUESTED, true).apply();
                permissionRequest.launch(Manifest.permission.CAMERA);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCamera();
    }

    @Override
    protected void onPause() {
        shutter.setEnabled(false);
        super.onPause();
    }

    private void updateCamera() {
        if (cameraSession == null) {
            return;
        }
        if (hasCameraPermission()) {
            // One-time grants and Android auto-reset must allow a new request later.
            getSharedPreferences(PERMISSIONS, MODE_PRIVATE).edit()
                    .remove(REQUESTED).apply();
            previewView.setVisibility(View.VISIBLE);
            cameraSession.start();
        } else {
            cameraSession.stop();
            previewView.setVisibility(View.INVISIBLE);
            showPermissionState();
        }
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

    private void maybeCaptureAfterPermission() {
        if (captureAfterPermission && cameraSession != null
                && cameraSession.isReadyToCapture()) {
            captureAfterPermission = false;
            capturePhoto();
        }
    }

    private void capturePhoto() {
        captureInProgress = true;
        showPhotoStatus(R.string.photo_saving, false);
        updateShutter();
        boolean started = cameraSession.takePhoto(new CameraSession.PhotoCallback() {
            @Override
            public void onSaved(Uri uri) {
                captureInProgress = false;
                showPhotoStatus(R.string.photo_saved, true);
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
        if (cameraSession != null) {
            cameraSession.stop();
        }
        super.onDestroy();
    }
}

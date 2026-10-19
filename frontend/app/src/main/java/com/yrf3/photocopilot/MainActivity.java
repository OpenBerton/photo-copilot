package com.yrf3.photocopilot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.yrf3.photocopilot.camera.CameraPreview;

public class MainActivity extends ComponentActivity {
    private static final String PERMISSIONS = "camera_permissions";
    private static final String REQUESTED = "requested";

    private CameraPreview cameraPreview;
    private TextView status;
    private Button action;
    private View messagePanel;
    private View previewView;
    private ActivityResultLauncher<String> permissionRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> updateCamera());
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.camera_status);
        action = findViewById(R.id.camera_action);
        messagePanel = findViewById(R.id.camera_message_panel);
        previewView = findViewById(R.id.preview_view);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        cameraPreview = new CameraPreview(this, this, findViewById(R.id.preview_view),
                new CameraPreview.Listener() {
                    @Override
                    public void onLoading() {
                        showStatus(R.string.camera_starting, 0);
                    }

                    @Override
                    public void onStreaming() {
                        messagePanel.setVisibility(View.GONE);
                        status.setVisibility(View.GONE);
                        action.setVisibility(View.GONE);
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
                cameraPreview.stop();
                cameraPreview.start();
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

    private void updateCamera() {
        if (cameraPreview == null) {
            return;
        }
        if (hasCameraPermission()) {
            // One-time grants and Android auto-reset must allow a new request later.
            getSharedPreferences(PERMISSIONS, MODE_PRIVATE).edit()
                    .remove(REQUESTED).apply();
            previewView.setVisibility(View.VISIBLE);
            cameraPreview.start();
        } else {
            cameraPreview.stop();
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
        messagePanel.setVisibility(View.VISIBLE);
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        action.setVisibility(buttonLabel == 0 ? View.GONE : View.VISIBLE);
        if (buttonLabel != 0) {
            action.setText(buttonLabel);
        }
    }

    @Override
    protected void onDestroy() {
        if (cameraPreview != null) {
            cameraPreview.stop();
        }
        super.onDestroy();
    }
}

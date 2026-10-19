package com.yrf3.photocopilot.camera;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.CameraControl;
import androidx.camera.core.ResolutionInfo;
import androidx.camera.core.ZoomState;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/** Owns camera use cases; permission prompts and screen state stay in the Activity. */
public final class CameraSession {
    public interface Listener {
        void onLoading();
        void onStreaming();
        void onPreviewIdle();
        void onError(boolean noCamera);
        void onControlsChanged();
        void onControlError();
    }

    public interface PhotoCallback {
        void onSaved(Uri uri);
        void onError();
    }

    private final Context context;
    private final LifecycleOwner owner;
    private final PreviewView view;
    private final Listener listener;
    private final Executor main;
    private final PhotoStore photos;
    private ProcessCameraProvider provider;
    private Preview preview;
    private ImageCapture imageCapture;
    private Camera camera;
    private Observer<CameraState> cameraObserver;
    private Observer<ZoomState> zoomObserver;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;
    private float requestedZoomRatio = 1f;
    private boolean bothCameras;
    private boolean cameraOpen;
    private int zoomRequest;
    private boolean starting;
    private boolean capturing;
    private boolean cameraError;
    private int generation;

    public CameraSession(Context context, LifecycleOwner owner, PreviewView view, Listener listener) {
        this.context = context.getApplicationContext();
        this.owner = owner;
        this.view = view;
        this.listener = listener;
        main = ContextCompat.getMainExecutor(this.context);
        photos = new PhotoStore(this.context);
        view.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        view.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        view.getPreviewStreamState().observe(owner, state -> {
            if (preview != null && state == PreviewView.StreamState.STREAMING && !cameraError && cameraOpen) {
                listener.onStreaming();
            } else {
                listener.onPreviewIdle();
            }
        });
    }

    public void start() {
        if (starting || preview != null
                || owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
            return;
        }
        starting = true;
        cameraError = false;
        int request = ++generation;
        listener.onLoading();
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            if (request != generation
                    || owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
                return;
            }
            starting = false;
            try {
                provider = future.get();
                boolean back = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA);
                boolean front = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);
                bothCameras = back && front;
                if (!back && !front) {
                    listener.onError(true);
                    return;
                }
                if (lensFacing == CameraSelector.LENS_FACING_BACK && !back) {
                    lensFacing = CameraSelector.LENS_FACING_FRONT;
                } else if (lensFacing == CameraSelector.LENS_FACING_FRONT && !front) {
                    lensFacing = CameraSelector.LENS_FACING_BACK;
                }
                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing).build();
                preview = new Preview.Builder().setTargetRotation(view.getDisplay() == null
                        ? android.view.Surface.ROTATION_0 : view.getDisplay().getRotation()).build();
                preview.setSurfaceProvider(view.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
                camera = provider.bindToLifecycle(owner, selector, preview, imageCapture);
                if (!camera.getCameraInfo().hasFlashUnit()) {
                    flashMode = ImageCapture.FLASH_MODE_OFF;
                }
                imageCapture.setFlashMode(flashMode);
                zoomObserver = state -> listener.onControlsChanged();
                camera.getCameraInfo().getZoomState().observe(owner, zoomObserver);
                cameraObserver = state -> {
                    boolean wasOpen = cameraOpen;
                    cameraOpen = state.getType() == CameraState.Type.OPEN;
                    cameraError = state.getError() != null;
                    if (cameraOpen && !wasOpen) {
                        // CameraX resets controls while inactive. Reapply the user's zoom on reopen.
                        applyZoom(requestedZoomRatio);
                    }
                    if (cameraError) {
                        Log.w("CameraSession", "Camera error: " + state.getError().getCode());
                        listener.onError(false);
                    } else if (state.getType() == CameraState.Type.OPEN
                            && view.getPreviewStreamState().getValue()
                            == PreviewView.StreamState.STREAMING) {
                        listener.onStreaming();
                    }
                    listener.onControlsChanged();
                };
                camera.getCameraInfo().getCameraState().observe(owner, cameraObserver);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail(exception);
            } catch (ExecutionException | RuntimeException
                     | androidx.camera.core.CameraInfoUnavailableException exception) {
                fail(exception);
            }
        }, main);
    }

    public boolean isPreviewReady() {
        return cameraOpen && !cameraError && imageCapture != null
                && owner.getLifecycle().getCurrentState() == Lifecycle.State.RESUMED
                && view.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING;
    }

    public boolean isReadyToCapture() {
        return !capturing && isPreviewReady();
    }

    public void restoreControls(int lens, int flash, float zoom) {
        if (preview != null || starting) {
            return;
        }
        lensFacing = lens == CameraSelector.LENS_FACING_FRONT
                ? lens : CameraSelector.LENS_FACING_BACK;
        flashMode = flash == ImageCapture.FLASH_MODE_ON || flash == ImageCapture.FLASH_MODE_AUTO
                ? flash : ImageCapture.FLASH_MODE_OFF;
        requestedZoomRatio = Float.isFinite(zoom) && zoom > 0 ? zoom : 1f;
    }

    public int getLensFacing() {
        return lensFacing;
    }

    public int getFlashMode() {
        return imageCapture == null ? flashMode : imageCapture.getFlashMode();
    }

    public boolean hasFlash() {
        return camera != null && camera.getCameraInfo().hasFlashUnit();
    }

    public boolean canSwitchCamera() {
        return bothCameras;
    }

    public float getRequestedZoomRatio() {
        return requestedZoomRatio;
    }

    public ZoomState getZoomState() {
        return camera == null ? null : camera.getCameraInfo().getZoomState().getValue();
    }

    public float getPreviewAspectRatio() {
        ResolutionInfo info = preview == null ? null : preview.getResolutionInfo();
        if (info == null || info.getCropRect().height() == 0) {
            return 0f;
        }
        float width = info.getCropRect().width();
        float height = info.getCropRect().height();
        return info.getRotationDegrees() % 180 == 0 ? width / height : height / width;
    }

    public boolean switchCamera() {
        if (!isReadyToCapture() || !bothCameras) {
            return false;
        }
        int next = lensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        stop();
        lensFacing = next;
        requestedZoomRatio = 1f;
        start();
        return true;
    }

    public void cycleFlash() {
        if (!isReadyToCapture() || !hasFlash()) {
            return;
        }
        flashMode = flashMode == ImageCapture.FLASH_MODE_OFF ? ImageCapture.FLASH_MODE_AUTO
                : flashMode == ImageCapture.FLASH_MODE_AUTO ? ImageCapture.FLASH_MODE_ON
                : ImageCapture.FLASH_MODE_OFF;
        imageCapture.setFlashMode(flashMode);
        listener.onControlsChanged();
    }

    public void scaleZoom(float factor) {
        if (isReadyToCapture() && Float.isFinite(factor) && factor > 0) {
            applyZoom(requestedZoomRatio * factor);
        }
    }

    public void setZoomRatio(float ratio) {
        if (isReadyToCapture() && Float.isFinite(ratio)) {
            applyZoom(ratio);
        }
    }

    private void applyZoom(float ratio) {
        ZoomState state = getZoomState();
        if (camera == null || state == null) {
            return;
        }
        requestedZoomRatio = Math.max(state.getMinZoomRatio(),
                Math.min(state.getMaxZoomRatio(), ratio));
        Camera target = camera;
        int session = generation;
        int request = ++zoomRequest;
        ListenableFuture<Void> future = target.getCameraControl().setZoomRatio(requestedZoomRatio);
        future.addListener(() -> {
            if (session != generation || target != camera || request != zoomRequest) {
                return;
            }
            try {
                future.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException exception) {
                if (!(exception.getCause() instanceof CameraControl.OperationCanceledException)) {
                    ZoomState current = getZoomState();
                    requestedZoomRatio = current == null ? 1f : current.getZoomRatio();
                    listener.onControlError();
                }
            }
            listener.onControlsChanged();
        }, main);
    }

    /** Returns false without starting a second request if a photo is already in progress. */
    public boolean takePhoto(PhotoCallback callback) {
        if (!isReadyToCapture() || view.getDisplay() == null) {
            return false;
        }
        capturing = true;
        int request = generation;
        ImageCapture capture = imageCapture;
        capture.setTargetRotation(view.getDisplay().getRotation());
        PhotoStore.IO.execute(() -> {
            try {
                PhotoStore.PendingPhoto photo = photos.create();
                main.execute(() -> {
                    if (request != generation || capture != imageCapture
                            || owner.getLifecycle().getCurrentState() != Lifecycle.State.RESUMED) {
                        finishPhoto(photo, false, callback);
                        return;
                    }
                    try {
                        capture.takePicture(new ImageCapture.OutputFileOptions.Builder(photo.stream)
                                .build(), main, new ImageCapture.OnImageSavedCallback() {
                                    @Override
                                    public void onImageSaved(ImageCapture.OutputFileResults result) {
                                        finishPhoto(photo, true, callback);
                                    }

                                    @Override
                                    public void onError(ImageCaptureException exception) {
                                        Log.e("CameraSession", "Photo capture failed", exception);
                                        finishPhoto(photo, false, callback);
                                    }
                                });
                    } catch (RuntimeException exception) {
                        Log.e("CameraSession", "Cannot capture photo", exception);
                        finishPhoto(photo, false, callback);
                    }
                });
            } catch (IOException | RuntimeException exception) {
                Log.e("CameraSession", "Cannot create photo", exception);
                main.execute(() -> completePhoto(null, callback));
            }
        });
        return true;
    }

    private void finishPhoto(PhotoStore.PendingPhoto photo, boolean success, PhotoCallback callback) {
        PhotoStore.IO.execute(() -> {
            boolean saved = false;
            if (success) {
                try {
                    photos.publish(photo);
                    saved = true;
                } catch (IOException | RuntimeException exception) {
                    Log.e("CameraSession", "Cannot finish saving photo", exception);
                }
            }
            if (!saved) {
                photos.discard(photo);
            }
            Uri uri = saved ? photo.uri : null;
            main.execute(() -> completePhoto(uri, callback));
        });
    }

    private void completePhoto(Uri uri, PhotoCallback callback) {
        capturing = false;
        if (owner.getLifecycle().getCurrentState() != Lifecycle.State.DESTROYED) {
            if (uri != null) {
                callback.onSaved(uri);
            } else {
                callback.onError();
            }
        }
    }

    private void fail(Exception exception) {
        Log.e("CameraSession", "Cannot start preview", exception);
        stop();
        listener.onError(false);
    }

    public void stop() {
        generation++;
        starting = false;
        cameraOpen = false;
        zoomRequest++;
        if (camera != null && zoomObserver != null) {
            camera.getCameraInfo().getZoomState().removeObserver(zoomObserver);
        }
        if (camera != null && cameraObserver != null) {
            camera.getCameraInfo().getCameraState().removeObserver(cameraObserver);
        }
        if (provider != null && preview != null) {
            provider.unbind(preview, imageCapture);
        }
        preview = null;
        imageCapture = null;
        camera = null;
        cameraObserver = null;
        zoomObserver = null;
        listener.onControlsChanged();
    }
}

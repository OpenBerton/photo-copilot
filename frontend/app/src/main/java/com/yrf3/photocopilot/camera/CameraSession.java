package com.yrf3.photocopilot.camera;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
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
            if (preview != null && state == PreviewView.StreamState.STREAMING && !cameraError) {
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
                CameraSelector selector;
                if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    selector = CameraSelector.DEFAULT_BACK_CAMERA;
                } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    selector = CameraSelector.DEFAULT_FRONT_CAMERA;
                } else {
                    listener.onError(true);
                    return;
                }
                preview = new Preview.Builder().build();
                preview.setSurfaceProvider(view.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
                camera = provider.bindToLifecycle(owner, selector, preview, imageCapture);
                cameraObserver = state -> {
                    cameraError = state.getError() != null;
                    if (cameraError) {
                        Log.w("CameraSession", "Camera error: " + state.getError().getCode());
                        listener.onError(false);
                    } else if (state.getType() == CameraState.Type.OPEN
                            && view.getPreviewStreamState().getValue()
                            == PreviewView.StreamState.STREAMING) {
                        listener.onStreaming();
                    }
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

    public boolean isReadyToCapture() {
        return !capturing && !cameraError && imageCapture != null
                && owner.getLifecycle().getCurrentState() == Lifecycle.State.RESUMED
                && view.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING;
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
    }
}

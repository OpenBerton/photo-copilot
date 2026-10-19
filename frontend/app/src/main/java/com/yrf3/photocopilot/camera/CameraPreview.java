package com.yrf3.photocopilot.camera;

import android.content.Context;
import android.util.Log;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

/** Owns only the preview use case; permission prompts and screen state stay in the Activity. */
public final class CameraPreview {
    public interface Listener {
        void onLoading();
        void onStreaming();
        void onError(boolean noCamera);
    }

    private final Context context;
    private final LifecycleOwner owner;
    private final PreviewView view;
    private final Listener listener;
    private ProcessCameraProvider provider;
    private Preview preview;
    private Camera camera;
    private Observer<CameraState> cameraObserver;
    private boolean starting;
    private int generation;

    public CameraPreview(Context context, LifecycleOwner owner, PreviewView view, Listener listener) {
        this.context = context.getApplicationContext();
        this.owner = owner;
        this.view = view;
        this.listener = listener;
        view.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        view.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        view.getPreviewStreamState().observe(owner, state -> {
            if (preview != null && state == PreviewView.StreamState.STREAMING) {
                listener.onStreaming();
            }
        });
    }

    public void start() {
        if (starting || preview != null
                || owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
            return;
        }
        starting = true;
        int request = ++generation;
        listener.onLoading();
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            // A pending provider may finish after rotation, permission revocation or stop().
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
                camera = provider.bindToLifecycle(owner, selector, preview);
                cameraObserver = state -> {
                    if (state.getError() != null) {
                        Log.w("CameraPreview", "Camera error: " + state.getError().getCode());
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
        }, ContextCompat.getMainExecutor(context));
    }

    private void fail(Exception exception) {
        Log.e("CameraPreview", "Cannot start preview", exception);
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
            // Unbind this use case only, so future capture/analysis use cases are not stopped accidentally.
            provider.unbind(preview);
        }
        preview = null;
        camera = null;
        cameraObserver = null;
    }
}

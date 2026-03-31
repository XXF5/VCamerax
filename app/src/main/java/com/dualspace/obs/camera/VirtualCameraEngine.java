package com.dualspace.obs.camera;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Virtual camera engine that creates virtual cameras visible to other apps.
 * Supports front/back virtual cameras, multiple camera sources, and frame delivery.
 *
 * On Android, true virtual camera devices cannot be created without root or
 * system privileges. This engine manages the rendering pipeline and hooks into
 * the camera system via CameraInterceptor to redirect camera requests.
 */
public class VirtualCameraEngine {

    private static final String TAG = "VirtualCameraEngine";

    // ── Camera Source Types ─────────────────────────────────────────────────
    public enum CameraSource {
        NONE,           // No source active
        REAL_CAMERA,    // Forward from a real camera
        IMAGE,          // Static image or slideshow
        VIDEO,          // Video file playback
        SCREEN,         // Screen capture
        COLOR           // Solid color
    }

    // ── Virtual Camera Info ─────────────────────────────────────────────────
    public static class VirtualCameraInfo {
        public final String cameraId;
        public final int facing;       // Camera.CameraInfo.CAMERA_FACING_FRONT or BACK
        public final int width;
        public final int height;
        public final float fps;
        public boolean active;

        public VirtualCameraInfo(String cameraId, int facing, int width, int height, float fps) {
            this.cameraId = cameraId;
            this.facing = facing;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.active = false;
        }

        @Override
        public String toString() {
            return String.format("VirtualCamera{id=%s, facing=%s, %dx%d @%.0ffps, active=%s}",
                    cameraId,
                    facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "FRONT" : "BACK",
                    width, height, fps, active);
        }
    }

    // ── Frame Listener ──────────────────────────────────────────────────────
    public interface OnCameraFrameListener {
        /**
         * Called when a new frame is available.
         * @param frameBuffer NV21 or YUV420SP frame data
         * @param width  Frame width
         * @param height Frame height
         */
        void onFrame(byte[] frameBuffer, int width, int height);

        /**
         * Called when the camera source encounters an error.
         */
        void onError(String message);
    }

    // ── Camera Event Listener ───────────────────────────────────────────────
    public interface OnCameraEventListener {
        void onCameraCreated(VirtualCameraInfo info);
        void onCameraDestroyed(String cameraId);
        void onCameraSourceChanged(String cameraId, CameraSource newSource);
        void onCameraSwitched(String cameraId, int newFacing);
    }

    // ── Fields ──────────────────────────────────────────────────────────────
    private static volatile VirtualCameraEngine sInstance;

    private final AtomicBoolean mInitialized = new AtomicBoolean(false);
    private final HandlerThread mEngineThread;
    private final Handler mEngineHandler;

    // Virtual cameras
    private final AtomicReference<VirtualCameraInfo> mFrontCamera = new AtomicReference<>();
    private final AtomicReference<VirtualCameraInfo> mBackCamera = new AtomicReference<>();
    private final AtomicReference<VirtualCameraInfo> mActiveCamera = new AtomicReference<>();

    // Current source
    private final AtomicReference<CameraSource> mCurrentSource = new AtomicReference<>(CameraSource.NONE);
    private SurfaceTexture mSurfaceTexture;
    private Surface mCameraSurface;
    private int mFrameWidth = 640;
    private int mFrameHeight = 480;
    private float mTargetFps = 30f;

    // Source instances
    private ImageSource mImageSource;
    private VideoSource mVideoSource;
    private ScreenSource mScreenSource;

    // Real camera
    private Camera mRealCamera;
    private int mRealCameraId = -1;
    private final AtomicBoolean mRealCameraRunning = new AtomicBoolean(false);

    // Listeners
    private final CopyOnWriteArrayList<OnCameraFrameListener> mFrameListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<OnCameraEventListener> mEventListeners = new CopyOnWriteArrayList<>();

    // ── Singleton ───────────────────────────────────────────────────────────

    private VirtualCameraEngine() {
        mEngineThread = new HandlerThread("VirtualCamera-Engine");
        mEngineThread.start();
        mEngineHandler = new Handler(mEngineThread.getLooper());
    }

    public static VirtualCameraEngine getInstance() {
        if (sInstance == null) {
            synchronized (VirtualCameraEngine.class) {
                if (sInstance == null) {
                    sInstance = new VirtualCameraEngine();
                }
            }
        }
        return sInstance;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Initialize the virtual camera engine.
     */
    public void init() {
        if (mInitialized.getAndSet(true)) {
            Log.w(TAG, "Already initialized");
            return;
        }

        // Create virtual camera infos
        mFrontCamera.set(new VirtualCameraInfo(
                "virtual_front", Camera.CameraInfo.CAMERA_FACING_FRONT,
                640, 480, 30f
        ));
        mBackCamera.set(new VirtualCameraInfo(
                "virtual_back", Camera.CameraInfo.CAMERA_FACING_BACK,
                1280, 720, 30f
        ));

        // Default to back camera
        mActiveCamera.set(mBackCamera.get());

        Log.i(TAG, "Virtual camera engine initialized");
    }

    /**
     * Create a virtual camera with the specified facing direction.
     * @param facing Camera.CameraInfo.CAMERA_FACING_FRONT or CAMERA_FACING_BACK
     * @return VirtualCameraInfo for the created camera
     */
    public VirtualCameraInfo createVirtualCamera(int facing) {
        if (!mInitialized.get()) {
            throw new IllegalStateException("Engine not initialized. Call init() first.");
        }

        VirtualCameraInfo info;
        if (facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            info = mFrontCamera.get();
        } else if (facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
            info = mBackCamera.get();
        } else {
            throw new IllegalArgumentException("Invalid facing: " + facing);
        }

        if (info != null) {
            info.active = true;
            notifyCameraCreated(info);
            Log.i(TAG, "Virtual camera created: " + info);
        }
        return info;
    }

    /**
     * Destroy the active virtual camera.
     */
    public void destroyCamera() {
        mEngineHandler.post(() -> {
            VirtualCameraInfo active = mActiveCamera.get();
            if (active == null) return;

            stopCurrentSource();
            active.active = false;
            notifyCameraDestroyed(active.cameraId);
            Log.i(TAG, "Virtual camera destroyed: " + active.cameraId);
        });
    }

    /**
     * Destroy a specific virtual camera by camera ID.
     */
    public void destroyCamera(String cameraId) {
        mEngineHandler.post(() -> {
            VirtualCameraInfo front = mFrontCamera.get();
            VirtualCameraInfo back = mBackCamera.get();

            if (front != null && front.cameraId.equals(cameraId)) {
                front.active = false;
                notifyCameraDestroyed(cameraId);
            }
            if (back != null && back.cameraId.equals(cameraId)) {
                back.active = false;
                notifyCameraDestroyed(cameraId);
            }
        });
    }

    /**
     * Set the camera source for the active virtual camera.
     */
    public void setCameraSource(CameraSource source) {
        if (source == null) return;

        mEngineHandler.post(() -> {
            CameraSource previous = mCurrentSource.getAndSet(source);

            if (previous != source) {
                stopCurrentSource();
                startNewSource(source);
                notifySourceChanged(source);
                Log.i(TAG, "Camera source changed: " + previous + " -> " + source);
            }
        });
    }

    /**
     * Get the rendering Surface for the active virtual camera.
     * Other apps that open this virtual camera will receive frames from this surface.
     */
    public Surface getCameraSurface() {
        if (mCameraSurface == null) {
            mSurfaceTexture = new SurfaceTexture(0);
            mCameraSurface = new Surface(mSurfaceTexture);
        }
        return mCameraSurface;
    }

    /**
     * Switch between front and back virtual cameras.
     */
    public void switchCamera() {
        mEngineHandler.post(() -> {
            VirtualCameraInfo current = mActiveCamera.get();
            VirtualCameraInfo next;

            if (current != null && current.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                next = mBackCamera.get();
            } else {
                next = mFrontCamera.get();
            }

            if (next != null) {
                stopCurrentSource();
                mActiveCamera.set(next);

                // Restart source with new camera
                startNewSource(mCurrentSource.get());
                notifyCameraSwitched(next);
                Log.i(TAG, "Switched to " + (next.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "front" : "back") + " camera");
            }
        });
    }

    /**
     * Set the frame resolution.
     */
    public void setResolution(int width, int height) {
        mFrameWidth = width;
        mFrameHeight = height;
    }

    /**
     * Set the target FPS.
     */
    public void setFps(float fps) {
        mTargetFps = Math.max(1f, Math.min(120f, fps));
    }

    /**
     * Get the active camera info.
     */
    public VirtualCameraInfo getActiveCamera() {
        return mActiveCamera.get();
    }

    /**
     * Get the front virtual camera info.
     */
    public VirtualCameraInfo getFrontCamera() {
        return mFrontCamera.get();
    }

    /**
     * Get the back virtual camera info.
     */
    public VirtualCameraInfo getBackCamera() {
        return mBackCamera.get();
    }

    /**
     * Get the current camera source.
     */
    public CameraSource getCurrentSource() {
        return mCurrentSource.get();
    }

    // ── Listeners ───────────────────────────────────────────────────────────

    public void addFrameListener(OnCameraFrameListener listener) {
        if (listener != null && !mFrameListeners.contains(listener)) {
            mFrameListeners.add(listener);
        }
    }

    public void removeFrameListener(OnCameraFrameListener listener) {
        mFrameListeners.remove(listener);
    }

    public void addEventListener(OnCameraEventListener listener) {
        if (listener != null && !mEventListeners.contains(listener)) {
            mEventListeners.add(listener);
        }
    }

    public void removeEventListener(OnCameraEventListener listener) {
        mEventListeners.remove(listener);
    }

    /**
     * Release all resources.
     */
    public void release() {
        mEngineHandler.post(() -> {
            stopCurrentSource();
            if (mCameraSurface != null) {
                mCameraSurface.release();
                mCameraSurface = null;
            }
            if (mSurfaceTexture != null) {
                mSurfaceTexture.release();
                mSurfaceTexture = null;
            }
            mInitialized.set(false);
        });
        mEngineThread.quitSafely();
        synchronized (VirtualCameraEngine.class) {
            sInstance = null;
        }
        Log.i(TAG, "Virtual camera engine released");
    }

    // ── Internal: Source Management ─────────────────────────────────────────

    private void startNewSource(CameraSource source) {
        switch (source) {
            case REAL_CAMERA:
                startRealCamera();
                break;
            case IMAGE:
                if (mImageSource != null) mImageSource.start();
                break;
            case VIDEO:
                if (mVideoSource != null) mVideoSource.start();
                break;
            case SCREEN:
                if (mScreenSource != null) mScreenSource.startCapture(0);
                break;
            case COLOR:
                startColorSource();
                break;
            case NONE:
                break;
        }
    }

    private void stopCurrentSource() {
        CameraSource current = mCurrentSource.get();
        switch (current) {
            case REAL_CAMERA:
                stopRealCamera();
                break;
            case IMAGE:
                if (mImageSource != null) mImageSource.stop();
                break;
            case VIDEO:
                if (mVideoSource != null) mVideoSource.stop();
                break;
            case SCREEN:
                if (mScreenSource != null) mScreenSource.stopCapture();
                break;
            default:
                break;
        }
    }

    private void startRealCamera() {
        try {
            int cameraCount = Camera.getNumberOfCameras();
            if (cameraCount == 0) {
                Log.e(TAG, "No real cameras available");
                return;
            }

            VirtualCameraInfo active = mActiveCamera.get();
            int desiredFacing = active != null ? active.facing : Camera.CameraInfo.CAMERA_FACING_FRONT;

            // Find the right camera
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < cameraCount; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == desiredFacing) {
                    mRealCameraId = i;
                    break;
                }
            }

            if (mRealCameraId < 0) mRealCameraId = 0;

            mRealCamera = Camera.open(mRealCameraId);
            Camera.Parameters params = mRealCamera.getParameters();

            // Find best preview size
            List<Camera.Size> sizes = params.getSupportedPreviewSizes();
            Camera.Size bestSize = findClosestSize(sizes, mFrameWidth, mFrameHeight);
            params.setPreviewSize(bestSize.width, bestSize.height);

            // Find best FPS range
            List<int[]> fpsRanges = params.getSupportedPreviewFpsRange();
            int[] bestFps = findClosestFps(fpsRanges, (int) (mTargetFps * 1000));
            if (bestFps != null) {
                params.setPreviewFpsRange(bestFps[0], bestFps[1]);
            }

            mRealCamera.setParameters(params);
            mRealCamera.setPreviewTexture(mSurfaceTexture);
            mRealCamera.startPreview();

            mRealCameraRunning.set(true);

            // Set up preview callback for frame delivery
            mRealCamera.setPreviewCallback((data, camera) -> {
                Camera.Size previewSize = camera.getParameters().getPreviewSize();
                deliverFrame(data, previewSize.width, previewSize.height);
            });

            Log.i(TAG, "Real camera started (id=" + mRealCameraId + ")");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start real camera", e);
            notifyFrameError("Failed to start real camera: " + e.getMessage());
        }
    }

    private void stopRealCamera() {
        if (mRealCamera != null) {
            try {
                mRealCamera.setPreviewCallback(null);
                mRealCamera.stopPreview();
                mRealCamera.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping real camera", e);
            }
            mRealCamera = null;
            mRealCameraRunning.set(false);
            Log.i(TAG, "Real camera stopped");
        }
    }

    private void startColorSource() {
        // Generate a solid color frame (black by default)
        int frameSize = mFrameWidth * mFrameHeight * 3 / 2; // NV21 size
        byte[] colorFrame = new byte[frameSize];
        // NV21: all Y = 128 (gray), all U/V = 128 (neutral)
        java.util.Arrays.fill(colorFrame, (byte) 128);
        deliverFrame(colorFrame, mFrameWidth, mFrameHeight);
        Log.i(TAG, "Color source started");
    }

    private Camera.Size findClosestSize(List<Camera.Size> sizes, int targetW, int targetH) {
        Camera.Size best = sizes.get(0);
        int bestDiff = Integer.MAX_VALUE;

        for (Camera.Size size : sizes) {
            int diff = Math.abs(size.width - targetW) + Math.abs(size.height - targetH);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = size;
            }
        }
        return best;
    }

    private int[] findClosestFps(List<int[]> fpsRanges, int targetFps) {
        int[] best = null;
        int bestDiff = Integer.MAX_VALUE;

        for (int[] range : fpsRanges) {
            int maxFps = range[1];
            int diff = Math.abs(maxFps - targetFps);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = range;
            }
        }
        return best;
    }

    // ── Frame delivery ──────────────────────────────────────────────────────

    private void deliverFrame(byte[] data, int width, int height) {
        for (OnCameraFrameListener listener : mFrameListeners) {
            try {
                listener.onFrame(data, width, height);
            } catch (Exception e) {
                Log.e(TAG, "Frame listener error", e);
            }
        }
    }

    private void notifyFrameError(String message) {
        for (OnCameraFrameListener listener : mFrameListeners) {
            try {
                listener.onError(message);
            } catch (Exception e) {
                Log.e(TAG, "Frame listener error callback failed", e);
            }
        }
    }

    // ── Event notifications ─────────────────────────────────────────────────

    private void notifyCameraCreated(VirtualCameraInfo info) {
        for (OnCameraEventListener l : mEventListeners) {
            try { l.onCameraCreated(info); } catch (Exception ignored) {}
        }
    }

    private void notifyCameraDestroyed(String cameraId) {
        for (OnCameraEventListener l : mEventListeners) {
            try { l.onCameraDestroyed(cameraId); } catch (Exception ignored) {}
        }
    }

    private void notifySourceChanged(CameraSource source) {
        VirtualCameraInfo active = mActiveCamera.get();
        String id = active != null ? active.cameraId : "unknown";
        for (OnCameraEventListener l : mEventListeners) {
            try { l.onCameraSourceChanged(id, source); } catch (Exception ignored) {}
        }
    }

    private void notifyCameraSwitched(VirtualCameraInfo info) {
        for (OnCameraEventListener l : mEventListeners) {
            try { l.onCameraSwitched(info.cameraId, info.facing); } catch (Exception ignored) {}
        }
    }
}

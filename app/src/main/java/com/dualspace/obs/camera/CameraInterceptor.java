package com.dualspace.obs.camera;

import android.hardware.Camera;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Camera hook system that intercepts Camera API calls and redirects them
 * to virtual cameras when active.
 *
 * On stock Android, direct hooking of Camera.open() requires either:
 *   1. Xposed framework (root)
 *   2. A custom ROM with hook support
 *   3. Proxy-based approach for in-app interception
 *
 * This class provides a framework for interception. The actual hooking mechanism
 * depends on the runtime environment (Xposed, root, etc.).
 */
public class CameraInterceptor {

    private static final String TAG = "CameraInterceptor";

    // Virtual camera IDs
    public static final String VIRTUAL_FRONT_ID = "virtual_front";
    public static final String VIRTUAL_BACK_ID = "virtual_back";

    // ── Listener ────────────────────────────────────────────────────────────
    public interface OnCameraRedirectListener {
        void onCameraRedirected(String originalCameraId, String virtualCameraId);
        void onCameraRestored(String cameraId);
        void onHookError(String message);
    }

    // ── Camera Parameter Override ───────────────────────────────────────────
    public static class CameraParams {
        public String flashMode;
        public String focusMode;
        public String sceneMode;
        public String whiteBalance;
        public String antibanding;
        public String exposureCompensation;
        public boolean autoExposureLock;
        public boolean autoWhiteBalanceLock;
        public int zoom;
        public int rotation;

        public CameraParams() {
            // Set sensible defaults
            flashMode = Camera.Parameters.FLASH_MODE_OFF;
            focusMode = Camera.Parameters.FOCUS_MODE_AUTO;
            sceneMode = Camera.Parameters.SCENE_MODE_AUTO;
            whiteBalance = Camera.Parameters.WHITE_BALANCE_AUTO;
            antibanding = Camera.Parameters.ANTIBANDING_AUTO;
            zoom = 0;
            rotation = 0;
        }
    }

    // ── Fields ──────────────────────────────────────────────────────────────
    private static volatile CameraInterceptor sInstance;

    private final Set<String> mRedirectedCameras = new CopyOnWriteArraySet<>();
    private final Map<String, CameraParams> mCameraParamsOverrides = new ConcurrentHashMap<>();
    private final Set<OnCameraRedirectListener> mListeners = new CopyOnWriteArraySet<>();

    private volatile boolean mHooksInstalled = false;
    private volatile boolean mVirtualCameraActive = false;
    private VirtualCameraEngine mEngine;

    private Object mCameraManagerProxy; // For Camera2 API interception

    // ── Singleton ───────────────────────────────────────────────────────────

    private CameraInterceptor() {}

    public static CameraInterceptor getInstance() {
        if (sInstance == null) {
            synchronized (CameraInterceptor.class) {
                if (sInstance == null) {
                    sInstance = new CameraInterceptor();
                }
            }
        }
        return sInstance;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Install camera hooks. This method sets up the interception framework.
     *
     * On Xposed: Called from IXposedHookLoadPackage.handleLoadPackage()
     * On non-rooted: Provides a proxy-based mechanism for in-app usage
     */
    public void installHooks() {
        if (mHooksInstalled) {
            Log.w(TAG, "Hooks already installed");
            return;
        }

        mEngine = VirtualCameraEngine.getInstance();

        try {
            installCamera1Hooks();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                installCamera2Hooks();
            }

            mHooksInstalled = true;
            Log.i(TAG, "Camera hooks installed successfully");

        } catch (Exception e) {
            Log.e(TAG, "Failed to install camera hooks", e);
            notifyHookError("Failed to install hooks: " + e.getMessage());
        }
    }

    /**
     * Uninstall all camera hooks and restore normal camera behavior.
     */
    public void uninstallHooks() {
        if (!mHooksInstalled) return;

        // Restore all redirected cameras
        for (String cameraId : mRedirectedCameras) {
            restoreCamera(cameraId);
        }

        mRedirectedCameras.clear();
        mCameraParamsOverrides.clear();
        mCameraManagerProxy = null;
        mHooksInstalled = false;

        Log.i(TAG, "Camera hooks uninstalled");
    }

    /**
     * Redirect a camera to a virtual camera.
     * @param cameraId The original camera ID (e.g., "0", "1")
     */
    public void redirectCamera(String cameraId) {
        if (!mHooksInstalled) {
            Log.w(TAG, "Hooks not installed");
            return;
        }

        mRedirectedCameras.add(cameraId);
        mVirtualCameraActive = true;

        VirtualCameraEngine.VirtualCameraInfo info = determineVirtualCamera(cameraId);
        if (info != null) {
            info.active = true;
        }

        notifyCameraRedirected(cameraId, getVirtualId(cameraId));
        Log.i(TAG, "Camera " + cameraId + " redirected to virtual camera");
    }

    /**
     * Restore a camera to normal operation.
     * @param cameraId The camera ID to restore
     */
    public void restoreCamera(String cameraId) {
        if (!mRedirectedCameras.remove(cameraId)) return;

        if (mRedirectedCameras.isEmpty()) {
            mVirtualCameraActive = false;
        }

        notifyCameraRestored(cameraId);
        Log.i(TAG, "Camera " + cameraId + " restored to normal");
    }

    /**
     * Check if a virtual camera is currently active for any camera ID.
     */
    public boolean isVirtualCameraActive() {
        return mVirtualCameraActive;
    }

    /**
     * Check if a specific camera is redirected.
     */
    public boolean isRedirected(String cameraId) {
        return mRedirectedCameras.contains(cameraId);
    }

    /**
     * Get the virtual camera ID for a redirected camera.
     */
    public String getVirtualId(String cameraId) {
        if ("0".equals(cameraId) || "1".equals(cameraId)) {
            // Assume camera 0 = back, camera 1 = front (common convention)
            return "0".equals(cameraId) ? VIRTUAL_BACK_ID : VIRTUAL_FRONT_ID;
        }
        return VIRTUAL_BACK_ID;
    }

    /**
     * Set camera parameter overrides for a camera.
     */
    public void setCameraParams(String cameraId, CameraParams params) {
        mCameraParamsOverrides.put(cameraId, params);
    }

    /**
     * Get camera parameter overrides for a camera.
     */
    public CameraParams getCameraParams(String cameraId) {
        return mCameraParamsOverrides.get(cameraId);
    }

    /**
     * Apply parameter overrides to a Camera.Parameters object.
     */
    public void applyParamsOverride(String cameraId, Camera.Parameters params) {
        CameraParams overrides = mCameraParamsOverrides.get(cameraId);
        if (overrides == null) return;

        try {
            if (overrides.flashMode != null) params.setFlashMode(overrides.flashMode);
            if (overrides.focusMode != null) params.setFocusMode(overrides.focusMode);
            if (overrides.sceneMode != null) params.setSceneMode(overrides.sceneMode);
            if (overrides.whiteBalance != null) params.setWhiteBalance(overrides.whiteBalance);
            if (overrides.antibanding != null) params.setAntibanding(overrides.antibanding);
            if (overrides.zoom > 0) params.setZoom(overrides.zoom);
            params.setRotation(overrides.rotation);
        } catch (Exception e) {
            Log.w(TAG, "Failed to apply parameter overrides", e);
        }
    }

    // ── Camera Preview Surface Management ───────────────────────────────────

    /**
     * Get the surface for the virtual camera preview.
     * This surface renders the virtual camera's content.
     */
    public android.view.Surface getPreviewSurface(String cameraId) {
        if (mEngine == null || !isRedirected(cameraId)) return null;

        VirtualCameraEngine.VirtualCameraInfo info = determineVirtualCamera(cameraId);
        if (info == null) return null;

        return mEngine.getCameraSurface();
    }

    /**
     * Set up the preview callback to deliver frames from the virtual camera.
     */
    public void setPreviewCallback(String cameraId, Camera.PreviewCallback callback) {
        if (!isRedirected(cameraId) || mEngine == null) return;

        mEngine.addFrameListener(new VirtualCameraEngine.OnCameraFrameListener() {
            @Override
            public void onFrame(byte[] frameBuffer, int width, int height) {
                if (callback != null) {
                    callback.onPreviewFrame(frameBuffer, null);
                }
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Virtual camera frame error: " + message);
            }
        });
    }

    // ── Listeners ───────────────────────────────────────────────────────────

    public void addListener(OnCameraRedirectListener listener) {
        if (listener != null) mListeners.add(listener);
    }

    public void removeListener(OnCameraRedirectListener listener) {
        mListeners.remove(listener);
    }

    public boolean areHooksInstalled() {
        return mHooksInstalled;
    }

    // ── Internal: Hook Installation ─────────────────────────────────────────

    /**
     * Install Camera1 (android.hardware.Camera) hooks.
     * Uses a proxy pattern to intercept open() calls in-process.
     */
    private void installCamera1Hooks() {
        Log.i(TAG, "Installing Camera1 hooks");

        // Register a Camera.ErrorCallback to detect when apps try to open cameras
        // In a real Xposed implementation, Camera.open() would be hooked directly.
        // Here we provide the infrastructure for redirection.

        // The actual interception happens at the app integration level.
        // Apps using this SDK should use CameraInterceptor.openCamera() instead
        // of Camera.open() directly.

        Log.i(TAG, "Camera1 hook infrastructure ready");
    }

    /**
     * Install Camera2 (android.hardware.camera2) hooks.
     */
    private void installCamera2Hooks() {
        Log.i(TAG, "Installing Camera2 hooks");

        // Camera2 interception would require hooking CameraManager.openCamera().
        // This is done via Xposed or similar framework.
        // Here we set up the proxy structure.

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                // Create a proxy for CameraManager that intercepts openCamera calls
                mCameraManagerProxy = Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{ android.hardware.camera2.CameraManager.class },
                        new CameraManagerInvocationHandler()
                );
            } catch (Exception e) {
                Log.e(TAG, "Failed to create CameraManager proxy", e);
            }
        }

        Log.i(TAG, "Camera2 hook infrastructure ready");
    }

    /**
     * Interceptor for Camera.open() - use this instead of Camera.open().
     * @param cameraId Camera ID to open
     * @return Camera instance (real or virtual)
     */
    public Camera interceptOpen(int cameraId) {
        String idStr = String.valueOf(cameraId);

        if (isRedirected(idStr)) {
            Log.i(TAG, "Intercepted Camera.open(" + cameraId + ") -> virtual camera");
            return createVirtualCameraProxy(cameraId);
        }

        Log.d(TAG, "Camera.open(" + cameraId + ") -> real camera");
        try {
            return Camera.open(cameraId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera " + cameraId, e);
            return null;
        }
    }

    /**
     * Create a Camera proxy that forwards calls to the virtual camera.
     */
    private Camera createVirtualCameraProxy(int cameraId) {
        return (Camera) Proxy.newProxyInstance(
                Camera.class.getClassLoader(),
                new Class<?>[]{ Camera.class },
                new VirtualCameraInvocationHandler(cameraId)
        );
    }

    // ── Invocation Handlers ─────────────────────────────────────────────────

    /**
     * InvocationHandler for virtual Camera proxy.
     * Intercepts Camera API calls and redirects to virtual camera engine.
     */
    private class VirtualCameraInvocationHandler implements InvocationHandler {
        private final int mCameraId;
        private Camera.Parameters mVirtualParams;

        VirtualCameraInvocationHandler(int cameraId) {
            mCameraId = cameraId;
            mVirtualParams = createVirtualParameters(cameraId);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            try {
                switch (methodName) {
                    case "getParameters":
                        return mVirtualParams;

                    case "setParameters":
                        if (args != null && args.length > 0 && args[0] instanceof Camera.Parameters) {
                            // Allow parameter changes but apply overrides
                        }
                        return null;

                    case "startPreview":
                        Log.d(TAG, "Virtual camera preview started (id=" + mCameraId + ")");
                        return null;

                    case "stopPreview":
                        Log.d(TAG, "Virtual camera preview stopped (id=" + mCameraId + ")");
                        return null;

                    case "setPreviewCallback":
                        if (args != null && args.length > 0 && args[0] instanceof Camera.PreviewCallback) {
                            setPreviewCallback(String.valueOf(mCameraId),
                                    (Camera.PreviewCallback) args[0]);
                        }
                        return null;

                    case "setPreviewCallbackWithBuffer":
                        if (args != null && args.length > 0 && args[0] instanceof Camera.PreviewCallback) {
                            setPreviewCallback(String.valueOf(mCameraId),
                                    (Camera.PreviewCallback) args[0]);
                        }
                        return null;

                    case "setPreviewTexture":
                        // Preview texture is managed by the virtual camera engine
                        return null;

                    case "setOneShotPreviewCallback":
                        if (args != null && args.length > 0 && args[0] instanceof Camera.PreviewCallback) {
                            setPreviewCallback(String.valueOf(mCameraId),
                                    (Camera.PreviewCallback) args[0]);
                        }
                        return null;

                    case "getCameraInfo":
                        Camera.CameraInfo info = new Camera.CameraInfo();
                        info.facing = mCameraId == 1
                                ? Camera.CameraInfo.CAMERA_FACING_FRONT
                                : Camera.CameraInfo.CAMERA_FACING_BACK;
                        info.orientation = mCameraId == 1 ? 90 : 90;
                        if (args != null && args.length >= 2) {
                            Camera.getCameraInfo(mCameraId, (Camera.CameraInfo) args[0]);
                        }
                        return null;

                    case "unlock":
                    case "lock":
                    case "reconnect":
                        return null;

                    case "release":
                        Log.d(TAG, "Virtual camera released (id=" + mCameraId + ")");
                        return null;

                    case "setErrorCallback":
                        return null;

                    case "setDisplayOrientation":
                        return null;

                    case "toString":
                        return "VirtualCamera[id=" + mCameraId + "]";

                    default:
                        Log.d(TAG, "VirtualCamera unhandled method: " + methodName);
                        return null;
                }
            } catch (Exception e) {
                Log.e(TAG, "VirtualCamera invocation error: " + methodName, e);
                return null;
            }
        }

        private Camera.Parameters createVirtualParameters(int cameraId) {
            // Create a minimal Parameters object
            // Camera.Parameters constructor is not public, so we open a real camera
            // temporarily to get a template, then close it.
            Camera templateCamera = null;
            try {
                int realId = Math.min(cameraId, Camera.getNumberOfCameras() - 1);
                templateCamera = Camera.open(realId);
                Camera.Parameters params = templateCamera.getParameters();
                templateCamera.release();
                templateCamera = null;

                // Apply overrides
                applyParamsOverride(String.valueOf(cameraId), params);
                return params;
            } catch (Exception e) {
                Log.w(TAG, "Could not create template params, using defaults");
                return null;
            } finally {
                if (templateCamera != null) {
                    try { templateCamera.release(); } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * InvocationHandler for Camera2 CameraManager proxy.
     */
    @android.annotation.SuppressLint("NewApi")
    private class CameraManagerInvocationHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            if ("openCamera".equals(methodName)) {
                if (args != null && args.length >= 1 && args[0] instanceof String) {
                    String cameraId = (String) args[0];
                    if (isRedirected(cameraId)) {
                        Log.i(TAG, "Intercepted openCamera(" + cameraId + ") -> virtual camera");
                        // In Camera2, we can't return a virtual CameraDevice.
                        // The interception would need to happen at the service level.
                        notifyCameraRedirected(cameraId, getVirtualId(cameraId));
                    }
                }
                return null;
            }

            // Forward other calls - this proxy pattern requires
            // integration with a hooking framework for actual interception
            Log.w(TAG, "Camera2 forward call: " + methodName);
            return null;
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private VirtualCameraEngine.VirtualCameraInfo determineVirtualCamera(String cameraId) {
        if (mEngine == null) return null;
        if ("1".equals(cameraId)) return mEngine.getFrontCamera();
        return mEngine.getBackCamera();
    }

    private void notifyCameraRedirected(String original, String virtual) {
        for (OnCameraRedirectListener l : mListeners) {
            try { l.onCameraRedirected(original, virtual); } catch (Exception ignored) {}
        }
    }

    private void notifyCameraRestored(String cameraId) {
        for (OnCameraRedirectListener l : mListeners) {
            try { l.onCameraRestored(cameraId); } catch (Exception ignored) {}
        }
    }

    private void notifyHookError(String message) {
        for (OnCameraRedirectListener l : mListeners) {
            try { l.onHookError(message); } catch (Exception ignored) {}
        }
    }


}

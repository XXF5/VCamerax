/*
 * CameraEngine.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Camera capture engine that provides camera access to virtual apps
 * and feeds frames into the OBS recording pipeline.
 */
package com.dualspace.obs.engine;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ConcurrentHashMap;

public class CameraEngine {

    private static final String TAG = "CameraEngine";

    // ──────────────────────────────────────────────────────────────────────────
    // Singleton
    // ──────────────────────────────────────────────────────────────────────────

    private static volatile CameraEngine sInstance;

    public static CameraEngine getInstance() {
        if (sInstance == null) {
            synchronized (CameraEngine.class) {
                if (sInstance == null) {
                    sInstance = new CameraEngine();
                }
            }
        }
        return sInstance;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────────────

    private volatile boolean mInitialized;
    private Context mContext;

    /** cameraId → is-in-use */
    private final ConcurrentHashMap<String, Boolean> mCameraUsage = new ConcurrentHashMap<>();

    private CameraManager mCameraManager;

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    public void init(@NonNull Context context) {
        if (mInitialized) return;

        mContext = context.getApplicationContext();
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);

        try {
            // Enumerate available cameras
            if (mCameraManager != null) {
                String[] cameraIds = mCameraManager.getCameraIdList();
                for (String id : cameraIds) {
                    mCameraUsage.put(id, false);
                    CameraCharacteristics chars = mCameraManager.getCameraCharacteristics(id);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    Log.d(TAG, "Camera found: id=" + id
                            + ", facing=" + (facing != null ? facing : "unknown"));
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to enumerate cameras", e);
        }

        mInitialized = true;
        Log.i(TAG, "CameraEngine initialized");
    }

    /**
     * Acquire a camera for use by a virtual app.
     *
     * @param cameraId camera to acquire
     * @return {@code true} if the camera was successfully acquired
     */
    public boolean acquireCamera(@NonNull String cameraId) {
        if (!mInitialized) {
            Log.e(TAG, "Engine not initialized");
            return false;
        }

        Boolean inUse = mCameraUsage.get(cameraId);
        if (inUse != null && inUse) {
            Log.w(TAG, "Camera already in use: " + cameraId);
            return false;
        }

        mCameraUsage.put(cameraId, true);
        Log.d(TAG, "Camera acquired: " + cameraId);
        return true;
    }

    /**
     * Release a camera.
     */
    public void releaseCamera(@NonNull String cameraId) {
        mCameraUsage.put(cameraId, false);
        Log.d(TAG, "Camera released: " + cameraId);
    }

    /**
     * Check if a camera is available.
     */
    public boolean isCameraAvailable(@NonNull String cameraId) {
        Boolean inUse = mCameraUsage.get(cameraId);
        return inUse != null && !inUse;
    }

    /**
     * Get the ID of the first available rear camera.
     */
    @Nullable
    public String getAvailableRearCamera() {
        if (mCameraManager == null) return null;
        try {
            String[] ids = mCameraManager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics chars = mCameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    if (isCameraAvailable(id)) return id;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to find rear camera", e);
        }
        return null;
    }

    /**
     * Get the ID of the first available front camera.
     */
    @Nullable
    public String getAvailableFrontCamera() {
        if (mCameraManager == null) return null;
        try {
            String[] ids = mCameraManager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics chars = mCameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    if (isCameraAvailable(id)) return id;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to find front camera", e);
        }
        return null;
    }

    /**
     * Release all camera resources.
     */
    public void release() {
        for (String id : mCameraUsage.keySet()) {
            releaseCamera(id);
        }
        mInitialized = false;
        Log.i(TAG, "CameraEngine released");
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    public int getCameraCount() {
        return mCameraUsage.size();
    }
}

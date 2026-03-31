/*
 * ObsCore.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Core OBS (Open Broadcaster Software) recording engine for Android.
 * Manages screen capture, audio capture, and video encoding pipeline.
 */
package com.dualspace.obs.engine;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

public class ObsCore {

    private static final String TAG = "ObsCore";

    private final Context mContext;
    private volatile boolean mInitialized;
    private volatile boolean mRecording;
    private volatile boolean mResourcesReleased;

    public ObsCore(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mInitialized = false;
        mRecording = false;
        mResourcesReleased = false;
    }

    /**
     * Initialize the OBS engine: load native libraries, configure encoders.
     */
    public void initialize() {
        if (mInitialized) {
            Log.w(TAG, "OBS core already initialized");
            return;
        }

        try {
            // Load native OBS libraries
            System.loadLibrary("obs-core");
            System.loadLibrary("obs-encoder");

            nativeInit(mContext.getFilesDir().getAbsolutePath());
            mInitialized = true;
            Log.i(TAG, "OBS core initialized");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native OBS libraries (non-fatal in dev mode)", e);
            // In development, allow the app to run without native libs
            mInitialized = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize OBS core", e);
            mInitialized = false;
        }
    }

    /**
     * Start screen recording.
     *
     * @param outputPath recording output file path
     * @return {@code true} if recording started
     */
    public boolean startRecording(@NonNull String outputPath) {
        if (!mInitialized) {
            Log.e(TAG, "Cannot start recording – not initialized");
            return false;
        }
        if (mRecording) {
            Log.w(TAG, "Already recording");
            return false;
        }

        try {
            nativeStartRecording(outputPath);
            mRecording = true;
            Log.i(TAG, "Recording started: " + outputPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            return false;
        }
    }

    /**
     * Stop the current recording.
     *
     * @return {@code true} if recording was stopped
     */
    public boolean stopRecording() {
        if (!mRecording) {
            Log.w(TAG, "Not recording");
            return false;
        }

        try {
            nativeStopRecording();
            mRecording = false;
            Log.i(TAG, "Recording stopped");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop recording", e);
            return false;
        }
    }

    /**
     * Pause the current recording.
     */
    public void pauseRecording() {
        if (mRecording) {
            nativePauseRecording();
            Log.d(TAG, "Recording paused");
        }
    }

    /**
     * Resume a paused recording.
     */
    public void resumeRecording() {
        if (mRecording) {
            nativeResumeRecording();
            Log.d(TAG, "Recording resumed");
        }
    }

    /**
     * Called when the host app enters the foreground.
     */
    public void onAppForeground() {
        Log.d(TAG, "App foreground – refreshing recording state");
    }

    /**
     * Release non-critical resources under memory pressure.
     */
    public void releaseResources() {
        if (mResourcesReleased) return;
        nativeReleaseResources();
        mResourcesReleased = true;
        Log.d(TAG, "Non-critical resources released");
    }

    /**
     * Full shutdown of the OBS engine.
     */
    public void shutdown() {
        if (mRecording) {
            stopRecording();
        }
        nativeShutdown();
        mInitialized = false;
        Log.i(TAG, "OBS core shut down");
    }

    public boolean isInitialized() { return mInitialized; }
    public boolean isRecording() { return mRecording; }

    // ──────────────────────────────────────────────────────────────────────────
    // Native methods
    // ──────────────────────────────────────────────────────────────────────────

    private native void nativeInit(String dataDir);
    private native void nativeStartRecording(String outputPath);
    private native void nativeStopRecording();
    private native void nativePauseRecording();
    private native void nativeResumeRecording();
    private native void nativeReleaseResources();
    private native void nativeShutdown();
}

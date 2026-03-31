package com.dualspace.obs.obs;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core OBS controller implementing a singleton pattern.
 * Manages the entire OBS lifecycle: init, recording, streaming, scene switching.
 */
public class ObsCore {

    private static final String TAG = "ObsCore";

    // ── States ──────────────────────────────────────────────────────────────
    public enum State {
        IDLE,            // Not initialized
        INITIALIZING,    // Setting up encoders and resources
        READY,           // Encoders ready, waiting for commands
        RECORDING,       // Actively recording to file
        STREAMING,       // Actively streaming via RTMP
        ERROR            // An error occurred
    }

    // ── Listener ────────────────────────────────────────────────────────────
    public interface OnStateChangedListener {
        void onStateChanged(State newState, State previousState);
        void onError(String message, Exception e);
    }

    // ── Configuration ───────────────────────────────────────────────────────
    public static class ObsConfig {
        public int width = 1920;
        public int height = 1080;
        public int videoBitrate = 4_500_000;   // 4.5 Mbps
        public int audioBitrate = 128_000;     // 128 kbps
        public int fps = 30;
        public int audioSampleRate = 48000;
        public int audioChannels = 2;
        public int iframeInterval = 2;         // seconds between I-frames
        public boolean useHevc = false;        // Use H.265 if true

        public ObsConfig() {}
        public ObsConfig(int w, int h, int vBitrate, int aBitrate, int fps) {
            this.width = w;
            this.height = h;
            this.videoBitrate = vBitrate;
            this.audioBitrate = aBitrate;
            this.fps = fps;
        }
    }

    // ── Real-time Stats ────────────────────────────────────────────────────
    public static class Stats {
        public long bytesWritten;
        public long currentBitrate;     // bits per second
        public int currentFps;
        public int droppedFrames;
        public long durationMs;
        public int width;
        public int height;

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "Stats{res=%dx%d, bitrate=%d kbps, fps=%d, dropped=%d, duration=%.1fs}",
                    width, height, currentBitrate / 1000, currentFps,
                    droppedFrames, durationMs / 1000.0);
        }
    }

    // ── Singleton ───────────────────────────────────────────────────────────
    private static volatile ObsCore sInstance;

    private ObsCore() {
        mHandlerThread = new HandlerThread("ObsCore-Handler");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    public static ObsCore getInstance() {
        if (sInstance == null) {
            synchronized (ObsCore.class) {
                if (sInstance == null) {
                    sInstance = new ObsCore();
                }
            }
        }
        return sInstance;
    }

    // ── Fields ──────────────────────────────────────────────────────────────
    private final AtomicReference<State> mState = new AtomicReference<>(State.IDLE);
    private final List<OnStateChangedListener> mListeners = new CopyOnWriteArrayList<>();
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final AtomicBoolean mReleased = new AtomicBoolean(false);

    private ObsConfig mConfig;
    private Context mAppContext;

    private VideoEncoder mVideoEncoder;
    private AudioEncoder mAudioEncoder;
    private RecordingManager mRecordingManager;
    private StreamManager mStreamManager;
    private SceneCollection mSceneCollection;

    private final AtomicLong mStartTimeMs = new AtomicLong(0);
    private final AtomicLong mBytesWritten = new AtomicLong(0);
    private final AtomicLong mFrameCount = new AtomicLong(0);
    private final AtomicLong mDroppedFrames = new AtomicLong(0);

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Initialize the OBS core with the given configuration.
     * Must be called from the main thread.
     */
    public void init(Context context, ObsConfig config) {
        if (context == null) throw new IllegalArgumentException("Context must not be null");
        if (config == null) throw new IllegalArgumentException("Config must not be null");

        mAppContext = context.getApplicationContext();
        mConfig = config;

        transitionState(State.INITIALIZING, null);

        mHandler.post(() -> {
            try {
                // Initialize video encoder
                mVideoEncoder = new VideoEncoder();
                mVideoEncoder.configure(
                        mConfig.width, mConfig.height,
                        mConfig.videoBitrate, mConfig.fps,
                        mConfig.iframeInterval, mConfig.useHevc
                );
                mVideoEncoder.setOnEncodedDataListener((data, info) -> {
                    onVideoDataEncoded(data, info);
                });

                // Initialize audio encoder
                mAudioEncoder = new AudioEncoder();
                mAudioEncoder.configure(
                        mConfig.audioSampleRate,
                        mConfig.audioChannels,
                        mConfig.audioBitrate
                );
                mAudioEncoder.setOnEncodedDataListener((data, info) -> {
                    onAudioDataEncoded(data, info);
                });

                // Initialize managers
                mRecordingManager = new RecordingManager(mAppContext);
                mStreamManager = new StreamManager();

                // Initialize scene collection
                mSceneCollection = new SceneCollection(mAppContext);
                mSceneCollection.load();

                transitionState(State.READY, null);
                Log.i(TAG, "OBS Core initialized: " + mConfig.width + "x" + mConfig.height
                        + " @" + mConfig.fps + "fps, " + (mConfig.videoBitrate / 1000) + "kbps");

            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize OBS core", e);
                transitionState(State.ERROR, e);
            }
        });
    }

    /**
     * Start recording to a file at the given path.
     */
    public void startRecording(String outputPath) {
        ensureState(State.READY);
        transitionState(State.RECORDING, null);

        mHandler.post(() -> {
            try {
                mStartTimeMs.set(System.currentTimeMillis());
                mBytesWritten.set(0);
                mFrameCount.set(0);
                mDroppedFrames.set(0);

                RecordingManager.RecordingConfig recConfig = new RecordingManager.RecordingConfig();
                recConfig.outputPath = outputPath;
                recConfig.width = mConfig.width;
                recConfig.height = mConfig.height;
                recConfig.fps = mConfig.fps;
                recConfig.videoBitrate = mConfig.videoBitrate;
                recConfig.audioBitrate = mConfig.audioBitrate;
                recConfig.audioSampleRate = mConfig.audioSampleRate;
                recConfig.audioChannels = mConfig.audioChannels;

                mRecordingManager.startRecording(recConfig);

                mVideoEncoder.start();
                mAudioEncoder.start();

                Log.i(TAG, "Recording started: " + outputPath);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start recording", e);
                transitionState(State.ERROR, e);
            }
        });
    }

    /**
     * Stop the current recording.
     */
    public void stopRecording() {
        if (mState.get() != State.RECORDING) {
            Log.w(TAG, "Not recording, ignoring stopRecording call");
            return;
        }

        mHandler.post(() -> {
            try {
                mVideoEncoder.stop();
                mAudioEncoder.stop();
                mRecordingManager.stopRecording();
                transitionState(State.READY, null);
                Log.i(TAG, "Recording stopped. Output: " + mRecordingManager.getOutputFile());
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recording", e);
                transitionState(State.ERROR, e);
            }
        });
    }

    /**
     * Start RTMP streaming.
     */
    public void startStreaming(String rtmpUrl, String streamKey) {
        ensureState(State.READY);
        transitionState(State.STREAMING, null);

        mHandler.post(() -> {
            try {
                mStartTimeMs.set(System.currentTimeMillis());
                mBytesWritten.set(0);
                mFrameCount.set(0);
                mDroppedFrames.set(0);

                mStreamManager.connect(rtmpUrl, streamKey);

                mVideoEncoder.start();
                mAudioEncoder.start();

                Log.i(TAG, "Streaming started: " + rtmpUrl);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start streaming", e);
                transitionState(State.ERROR, e);
            }
        });
    }

    /**
     * Stop the current stream.
     */
    public void stopStreaming() {
        if (mState.get() != State.STREAMING) {
            Log.w(TAG, "Not streaming, ignoring stopStreaming call");
            return;
        }

        mHandler.post(() -> {
            try {
                mVideoEncoder.stop();
                mAudioEncoder.stop();
                mStreamManager.disconnect();
                transitionState(State.READY, null);
                Log.i(TAG, "Streaming stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping stream", e);
                transitionState(State.ERROR, e);
            }
        });
    }

    /**
     * Switch to the scene at the given index.
     */
    public void switchScene(int index) {
        mHandler.post(() -> {
            if (mSceneCollection != null) {
                mSceneCollection.switchScene(index);
                Log.i(TAG, "Switched to scene at index " + index);
            }
        });
    }

    /**
     * Get real-time stats.
     */
    public Stats getStats() {
        Stats stats = new Stats();
        stats.bytesWritten = mBytesWritten.get();
        stats.droppedFrames = (int) mDroppedFrames.get();
        stats.durationMs = mStartTimeMs.get() > 0
                ? System.currentTimeMillis() - mStartTimeMs.get()
                : 0;
        stats.width = mConfig != null ? mConfig.width : 0;
        stats.height = mConfig != null ? mConfig.height : 0;

        // Estimate bitrate from bytes written
        long durSec = Math.max(1, stats.durationMs / 1000);
        stats.currentBitrate = (mBytesWritten.get() * 8) / durSec;

        // Estimate FPS from frame count
        stats.currentFps = (int) (mFrameCount.get() / Math.max(1, stats.durationMs / 1000.0));

        return stats;
    }

    /**
     * Get the current state.
     */
    public State getState() {
        return mState.get();
    }

    /**
     * Get the video encoder's input Surface (for rendering screen capture into).
     * Returns null if not yet initialized.
     */
    public android.view.Surface getInputSurface() {
        if (mVideoEncoder != null) {
            return mVideoEncoder.getInputSurface();
        }
        return null;
    }

    /**
     * Dynamically adjust video bitrate.
     */
    public void setVideoBitrate(int bitrateBps) {
        if (mVideoEncoder != null && mConfig != null) {
            mConfig.videoBitrate = bitrateBps;
            mHandler.post(() -> mVideoEncoder.setBitrate(bitrateBps));
        }
    }

    /**
     * Request a key frame.
     */
    public void requestKeyFrame() {
        if (mVideoEncoder != null) {
            mHandler.post(mVideoEncoder::requestKeyFrame);
        }
    }

    /**
     * Set audio gain.
     */
    public void setAudioGain(float gain) {
        if (mAudioEncoder != null) {
            mHandler.post(() -> mAudioEncoder.setGain(gain));
        }
    }

    /**
     * Get the scene collection.
     */
    public SceneCollection getSceneCollection() {
        return mSceneCollection;
    }

    /**
     * Add a state change listener.
     */
    public void addListener(OnStateChangedListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    /**
     * Remove a state change listener.
     */
    public void removeListener(OnStateChangedListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Release all resources. Call when done using OBS.
     */
    public void release() {
        if (mReleased.getAndSet(true)) return;

        mHandler.post(() -> {
            try {
                if (mState.get() == State.RECORDING) stopRecording();
                else if (mState.get() == State.STREAMING) stopStreaming();

                if (mVideoEncoder != null) mVideoEncoder.release();
                if (mAudioEncoder != null) mAudioEncoder.release();
                if (mRecordingManager != null) mRecordingManager.release();
                if (mStreamManager != null) mStreamManager.disconnect();
                if (mSceneCollection != null) mSceneCollection.save();
            } catch (Exception e) {
                Log.e(TAG, "Error during release", e);
            } finally {
                transitionState(State.IDLE, null);
            }
        });

        mHandlerThread.quitSafely();
        synchronized (ObsCore.class) {
            sInstance = null;
        }
        Log.i(TAG, "OBS Core released");
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private void transitionState(State newState, Exception error) {
        State previous = mState.getAndSet(newState);
        if (previous == newState) return;

        Log.i(TAG, "State: " + previous + " -> " + newState);
        for (OnStateChangedListener l : mListeners) {
            if (error != null) {
                l.onError(error.getMessage(), error);
            }
            l.onStateChanged(newState, previous);
        }
    }

    private void ensureState(State expected) {
        State current = mState.get();
        if (current != expected) {
            throw new IllegalStateException(
                    "Expected state " + expected + " but was " + current);
        }
    }

    private void onVideoDataEncoded(byte[] data, android.media.MediaCodec.BufferInfo info) {
        mBytesWritten.addAndGet(data.length);
        if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
            mFrameCount.incrementAndGet();
        }
        // Forward to recording manager or stream manager
        if (mState.get() == State.RECORDING && mRecordingManager != null) {
            mRecordingManager.writeVideoData(data, info);
        } else if (mState.get() == State.STREAMING && mStreamManager != null) {
            mStreamManager.sendVideoData(data, info);
        }
    }

    private void onAudioDataEncoded(byte[] data, android.media.MediaCodec.BufferInfo info) {
        mBytesWritten.addAndGet(data.length);
        if (mState.get() == State.RECORDING && mRecordingManager != null) {
            mRecordingManager.writeAudioData(data, info);
        } else if (mState.get() == State.STREAMING && mStreamManager != null) {
            mStreamManager.sendAudioData(data, info);
        }
    }
}

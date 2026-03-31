package com.dualspace.obs.obs;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.format.DateFormat;
import android.util.Log;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Recording manager using MediaMuxer for MP4 output.
 * Supports quality presets, pause/resume, max duration/size limits, and auto-naming.
 */
public class RecordingManager {

    private static final String TAG = "RecordingManager";

    // ── Quality Presets ─────────────────────────────────────────────────────
    public enum QualityPreset {
        LOW(854, 480, 1_500_000, 64_000),
        MEDIUM(1280, 720, 3_000_000, 96_000),
        HIGH(1920, 1080, 6_000_000, 128_000),
        ULTRA(2560, 1440, 12_000_000, 192_000),
        LOSSLESS(3840, 2160, 30_000_000, 256_000);

        public final int width;
        public final int height;
        public final int videoBitrate;
        public final int audioBitrate;

        QualityPreset(int w, int h, int vBit, int aBit) {
            this.width = w;
            this.height = h;
            this.videoBitrate = vBit;
            this.audioBitrate = aBit;
        }
    }

    // ── Recording Config ────────────────────────────────────────────────────
    public static class RecordingConfig {
        public String outputPath;          // null for auto-generated
        public QualityPreset qualityPreset = QualityPreset.HIGH;
        public int width = 1920;
        public int height = 1080;
        public int fps = 30;
        public int videoBitrate = 6_000_000;
        public int audioBitrate = 128_000;
        public int audioSampleRate = 48000;
        public int audioChannels = 2;
        public long maxDurationMs = 0;     // 0 = unlimited
        public long maxFileSizeBytes = 0;  // 0 = unlimited
    }

    // ── State ───────────────────────────────────────────────────────────────
    public enum RecordingState {
        IDLE,
        RECORDING,
        PAUSED,
        STOPPED
    }

    // ── Listener ────────────────────────────────────────────────────────────
    public interface OnRecordingEventListener {
        void onRecordingStarted(String filePath);
        void onRecordingPaused();
        void onRecordingResumed();
        void onRecordingStopped(String filePath, long durationMs);
        void onRecordingError(String message);
        void onMaxDurationReached();
        void onMaxFileSizeReached();
    }

    // ── Fields ──────────────────────────────────────────────────────────────
    private final Context mAppContext;
    private MediaMuxer mMuxer;
    private final AtomicBoolean mMuxerStarted = new AtomicBoolean(false);
    private final AtomicBoolean mRecording = new AtomicBoolean(false);
    private final AtomicBoolean mPaused = new AtomicBoolean(false);

    private int mVideoTrackIndex = -1;
    private int mAudioTrackIndex = -1;
    private boolean mVideoTrackAdded = false;
    private boolean mAudioTrackAdded = false;

    private RecordingConfig mConfig;
    private String mOutputPath;
    private final AtomicLong mStartTimeMs = new AtomicLong(0);
    private final AtomicLong mPausedDurationMs = new AtomicLong(0);
    private final AtomicLong mPauseStartTimeMs = new AtomicLong(0);
    private final AtomicLong mFileSizeBytes = new AtomicLong(0);

    private HandlerThread mRecordThread;
    private Handler mRecordHandler;
    private Runnable mMaxDurationChecker;
    private OnRecordingEventListener mEventListener;

    private final Object mMuxerLock = new Object();

    // ── Constructor ─────────────────────────────────────────────────────────

    public RecordingManager(Context context) {
        mAppContext = context.getApplicationContext();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Start recording with the given configuration.
     */
    public void startRecording(RecordingConfig config) {
        if (config == null) throw new IllegalArgumentException("Config must not be null");
        if (mRecording.get()) {
            Log.w(TAG, "Already recording");
            return;
        }

        mConfig = config;
        mOutputPath = config.outputPath != null ? config.outputPath : generateOutputPath();

        // Check available storage
        if (!checkStorage()) {
            notifyError("Insufficient storage space");
            return;
        }

        try {
            mMuxer = new MediaMuxer(mOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            mRecording.set(true);
            mPaused.set(false);
            mVideoTrackAdded = false;
            mAudioTrackAdded = false;
            mVideoTrackIndex = -1;
            mAudioTrackIndex = -1;
            mMuxerStarted.set(false);
            mFileSizeBytes.set(0);
            mStartTimeMs.set(System.currentTimeMillis());
            mPausedDurationMs.set(0);

            mRecordThread = new HandlerThread("Recording-Thread");
            mRecordThread.start();
            mRecordHandler = new Handler(mRecordThread.getLooper());

            // Start max duration checker
            if (mConfig.maxDurationMs > 0) {
                startMaxDurationChecker();
            }

            notifyStarted(mOutputPath);
            Log.i(TAG, "Recording started: " + mOutputPath);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            notifyError("Failed to start recording: " + e.getMessage());
            cleanup();
        }
    }

    /**
     * Stop recording and finalize the output file.
     */
    public void stopRecording() {
        if (!mRecording.get()) return;

        mRecording.set(false);
        mPaused.set(false);

        if (mRecordHandler != null) {
            mRecordHandler.post(() -> {
                synchronized (mMuxerLock) {
                    try {
                        if (mMuxer != null && mMuxerStarted.get()) {
                            mMuxer.stop();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping muxer", e);
                    }
                }
                cleanup();

                long durationMs = getDuration();
                notifyStopped(mOutputPath, durationMs);
                Log.i(TAG, "Recording stopped: " + mOutputPath + " (" + durationMs + "ms)");
            });
        }
    }

    /**
     * Pause the current recording.
     */
    public void pause() {
        if (!mRecording.get() || mPaused.get()) return;

        mPaused.set(true);
        mPauseStartTimeMs.set(System.currentTimeMillis());

        if (mRecordHandler != null) {
            mRecordHandler.removeCallbacksAndMessages(null);
        }

        notifyPaused();
        Log.i(TAG, "Recording paused");
    }

    /**
     * Resume a paused recording.
     */
    public void resume() {
        if (!mRecording.get() || !mPaused.get()) return;

        long pauseDuration = System.currentTimeMillis() - mPauseStartTimeMs.get();
        mPausedDurationMs.addAndGet(pauseDuration);
        mPaused.set(false);

        if (mConfig.maxDurationMs > 0 && mRecordHandler != null) {
            startMaxDurationChecker();
        }

        notifyResumed();
        Log.i(TAG, "Recording resumed");
    }

    /**
     * Get the recording duration in milliseconds (excluding paused time).
     */
    public long getDuration() {
        if (!mRecording.get()) return 0;
        if (mPaused.get()) {
            return mPauseStartTimeMs.get() - mStartTimeMs.get() - mPausedDurationMs.get();
        }
        return System.currentTimeMillis() - mStartTimeMs.get() - mPausedDurationMs.get();
    }

    /**
     * Get the output file path.
     */
    public String getOutputFile() {
        return mOutputPath;
    }

    /**
     * Get current recording state.
     */
    public RecordingState getState() {
        if (!mRecording.get()) return RecordingState.IDLE;
        if (mPaused.get()) return RecordingState.PAUSED;
        return RecordingState.RECORDING;
    }

    /**
     * Set the event listener.
     */
    public void setOnRecordingEventListener(OnRecordingEventListener listener) {
        mEventListener = listener;
    }

    // ── Track management (called from ObsCore) ──────────────────────────────

    /**
     * Add a video track from the encoder's output format.
     * Must be called before writing video data.
     */
    public void addVideoTrack(MediaFormat format) {
        synchronized (mMuxerLock) {
            if (mMuxer == null || mVideoTrackAdded) return;
            mVideoTrackIndex = mMuxer.addTrack(format);
            mVideoTrackAdded = true;
            Log.d(TAG, "Video track added at index " + mVideoTrackIndex);
            tryStartMuxer();
        }
    }

    /**
     * Add an audio track from the encoder's output format.
     * Must be called before writing audio data.
     */
    public void addAudioTrack(MediaFormat format) {
        synchronized (mMuxerLock) {
            if (mMuxer == null || mAudioTrackAdded) return;
            mAudioTrackIndex = mMuxer.addTrack(format);
            mAudioTrackAdded = true;
            Log.d(TAG, "Audio track added at index " + mAudioTrackIndex);
            tryStartMuxer();
        }
    }

    // ── Data writing (called from ObsCore) ──────────────────────────────────

    /**
     * Write encoded video data to the output file.
     */
    public void writeVideoData(byte[] data, MediaCodec.BufferInfo info) {
        if (!mRecording.get() || mPaused.get()) return;
        if (mVideoTrackIndex < 0) return;

        // Add video track from first key frame if not added yet
        if (!mVideoTrackAdded && mRecordHandler != null) {
            // Format will be provided separately via addVideoTrack
            return;
        }

        mRecordHandler.post(() -> {
            synchronized (mMuxerLock) {
                if (mMuxer == null || !mMuxerStarted.get()) return;

                try {
                    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
                    mMuxer.writeSampleData(mVideoTrackIndex, buffer, info);
                    mFileSizeBytes.addAndGet(data.length);

                    // Check max file size
                    if (mConfig.maxFileSizeBytes > 0 && mFileSizeBytes.get() >= mConfig.maxFileSizeBytes) {
                        notifyMaxFileSizeReached();
                        stopRecording();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error writing video data", e);
                }
            }
        });
    }

    /**
     * Write encoded audio data to the output file.
     */
    public void writeAudioData(byte[] data, MediaCodec.BufferInfo info) {
        if (!mRecording.get() || mPaused.get()) return;
        if (mAudioTrackIndex < 0) return;

        mRecordHandler.post(() -> {
            synchronized (mMuxerLock) {
                if (mMuxer == null || !mMuxerStarted.get()) return;

                try {
                    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
                    mMuxer.writeSampleData(mAudioTrackIndex, buffer, info);
                    mFileSizeBytes.addAndGet(data.length);
                } catch (Exception e) {
                    Log.e(TAG, "Error writing audio data", e);
                }
            }
        });
    }

    // ── Release ─────────────────────────────────────────────────────────────

    public void release() {
        if (mRecording.get()) {
            stopRecording();
        }
        cleanup();
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private void tryStartMuxer() {
        synchronized (mMuxerLock) {
            if (mVideoTrackAdded && mMuxer != null && !mMuxerStarted.get()) {
                // Start with just video if audio isn't added yet
                // or wait for both tracks
                if (mAudioTrackAdded || mConfig.audioChannels == 0) {
                    mMuxer.start();
                    mMuxerStarted.set(true);
                    Log.i(TAG, "Muxer started");
                }
            }
        }
    }

    private String generateOutputPath() {
        File dir;
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "DualSpace");
        } else {
            dir = new File(mAppContext.getExternalFilesDir(null), "Recordings");
        }

        if (!dir.exists() && !dir.mkdirs()) {
            dir = mAppContext.getExternalFilesDir(null);
        }

        String timestamp = new DateFormat().format("yyyy-MM-dd_HH-mm-ss", new java.util.Date());
        return new File(dir, "Recording_" + timestamp + ".mp4").getAbsolutePath();
    }

    private boolean checkStorage() {
        File dir = new File(mOutputPath != null ? mOutputPath : "/").getParentFile();
        if (dir == null) return true;

        try {
            long available = dir.getFreeSpace();
            // Require at least 100MB free
            if (available < 100 * 1024 * 1024) {
                Log.w(TAG, "Low storage: " + (available / (1024 * 1024)) + "MB free");
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not check storage", e);
            return true; // Allow if check fails
        }
    }

    private void startMaxDurationChecker() {
        if (mRecordHandler == null) return;

        mRecordHandler.removeCallbacks(mMaxDurationChecker);
        mMaxDurationChecker = new Runnable() {
            @Override
            public void run() {
                if (!mRecording.get() || mPaused.get()) return;
                if (getDuration() >= mConfig.maxDurationMs) {
                    notifyMaxDurationReached();
                    stopRecording();
                } else {
                    // Check again in 1 second
                    mRecordHandler.postDelayed(this, 1000);
                }
            }
        };
        mRecordHandler.postDelayed(mMaxDurationChecker, 1000);
    }

    private void cleanup() {
        if (mRecordHandler != null) {
            mRecordHandler.removeCallbacksAndMessages(null);
        }
        if (mRecordThread != null) {
            mRecordThread.quitSafely();
            mRecordThread = null;
            mRecordHandler = null;
        }

        synchronized (mMuxerLock) {
            try {
                if (mMuxer != null) {
                    mMuxer.release();
                    mMuxer = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error releasing muxer", e);
            }
        }

        mMuxerStarted.set(false);
    }

    private void notifyStarted(String path) {
        if (mEventListener != null) mEventListener.onRecordingStarted(path);
    }

    private void notifyStopped(String path, long durationMs) {
        if (mEventListener != null) mEventListener.onRecordingStopped(path, durationMs);
    }

    private void notifyPaused() {
        if (mEventListener != null) mEventListener.onRecordingPaused();
    }

    private void notifyResumed() {
        if (mEventListener != null) mEventListener.onRecordingResumed();
    }

    private void notifyError(String message) {
        if (mEventListener != null) mEventListener.onRecordingError(message);
    }

    private void notifyMaxDurationReached() {
        if (mEventListener != null) mEventListener.onMaxDurationReached();
    }

    private void notifyMaxFileSizeReached() {
        if (mEventListener != null) mEventListener.onMaxFileSizeReached();
    }
}

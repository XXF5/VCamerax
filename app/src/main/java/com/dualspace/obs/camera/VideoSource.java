package com.dualspace.obs.camera;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Video camera source using MediaPlayer for video file playback.
 * Supports loop mode, speed control, seeking, and audio sync.
 * Provides frames for virtual camera rendering.
 */
public class VideoSource {

    private static final String TAG = "VideoSource";
    private static final int FRAME_POLL_INTERVAL_MS = 33; // ~30fps

    // ── Frame Listener ──────────────────────────────────────────────────────
    public interface OnFrameReadyListener {
        void onFrameReady(byte[] frameBuffer, int width, int height);
        void onVideoEnded();
        void onError(String message);
    }

    // ── Fields ──────────────────────────────────────────────────────────────
    private MediaPlayer mMediaPlayer;
    private MediaMetadataRetriever mMetadataRetriever;
    private Surface mOutputSurface;

    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final AtomicBoolean mPaused = new AtomicBoolean(false);
    private final AtomicBoolean mLooping = new AtomicBoolean(false);
    private final AtomicReference<String> mVideoPath = new AtomicReference<>(null);
    private final AtomicLong mDurationMs = new AtomicLong(0);
    private final AtomicLong mCurrentPositionMs = new AtomicLong(0);
    private final AtomicReference<Float> mSpeed = new AtomicReference<>(1.0f);

    private int mVideoWidth = 640;
    private int mVideoHeight = 480;
    private int mTargetWidth = 640;
    private int mTargetHeight = 480;

    private HandlerThread mSourceThread;
    private Handler mSourceHandler;
    private Runnable mFramePoller;

    private OnFrameReadyListener mFrameListener;
    private MediaPlayer.OnCompletionListener mCompletionListener;
    private MediaPlayer.OnErrorListener mErrorListener;

    // Audio sync
    private final AtomicBoolean mAudioEnabled = new AtomicBoolean(true);
    private float mVolume = 1.0f;

    // ── Constructor ─────────────────────────────────────────────────────────

    public VideoSource() {
        mSourceThread = new HandlerThread("VideoSource-Thread");
        mSourceThread.start();
        mSourceHandler = new Handler(mSourceThread.getLooper());
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Load a video file for playback.
     * @param path Absolute path to the video file
     */
    public void loadVideo(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Video path must not be null or empty");
        }

        mSourceHandler.post(() -> {
            // Release previous resources
            releasePlayer();

            mVideoPath.set(path);
            File file = new File(path);

            if (!file.exists()) {
                Log.e(TAG, "Video file not found: " + path);
                notifyError("Video file not found: " + path);
                return;
            }

            // Extract metadata
            try {
                mMetadataRetriever = new MediaMetadataRetriever();
                mMetadataRetriever.setDataSource(path);

                String widthStr = mMetadataRetriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String heightStr = mMetadataRetriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String durationStr = mMetadataRetriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);

                mVideoWidth = widthStr != null ? Integer.parseInt(widthStr) : 640;
                mVideoHeight = heightStr != null ? Integer.parseInt(heightStr) : 480;
                mDurationMs.set(durationStr != null ? Long.parseLong(durationStr) : 0);

                Log.i(TAG, String.format("Video loaded: %s (%dx%d, %dms)",
                        path, mVideoWidth, mVideoHeight, mDurationMs.get()));

            } catch (Exception e) {
                Log.e(TAG, "Failed to extract video metadata", e);
                mVideoWidth = 640;
                mVideoHeight = 480;
            }

            // Create MediaPlayer
            try {
                mMediaPlayer = new MediaPlayer();
                mMediaPlayer.setDataSource(path);
                mMediaPlayer.prepare();

                // Set up listeners
                mMediaPlayer.setOnCompletionListener(mp -> {
                    if (mLooping.get()) {
                        seekTo(0);
                        startPlayback();
                    } else {
                        notifyVideoEnded();
                    }
                });

                mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
                    notifyError("MediaPlayer error: " + what);
                    return true;
                });

                mMediaPlayer.setVolume(mVolume, mVolume);

                // Get actual video dimensions from player
                if (mMediaPlayer.getVideoWidth() > 0 && mMediaPlayer.getVideoHeight() > 0) {
                    mVideoWidth = mMediaPlayer.getVideoWidth();
                    mVideoHeight = mMediaPlayer.getVideoHeight();
                }

            } catch (IOException e) {
                Log.e(TAG, "Failed to prepare MediaPlayer", e);
                notifyError("Failed to prepare video: " + e.getMessage());
            }
        });
    }

    /**
     * Start video playback and frame delivery.
     */
    public void start() {
        if (!mRunning.getAndSet(true)) {
            // First start
            if (mMediaPlayer != null && !mMediaPlayer.isPlaying()) {
                startPlayback();
            }
        } else if (mPaused.getAndSet(false)) {
            // Resume
            if (mMediaPlayer != null) {
                mMediaPlayer.start();
            }
            startFramePolling();
        }

        startFramePolling();
        Log.i(TAG, "Video source started");
    }

    /**
     * Stop video playback and frame delivery.
     */
    public void stop() {
        if (!mRunning.getAndSet(false)) return;
        mPaused.set(false);

        stopFramePolling();

        mSourceHandler.post(() -> {
            if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                mMediaPlayer.stop();
            }
        });

        Log.i(TAG, "Video source stopped");
    }

    /**
     * Seek to a specific position.
     * @param ms Position in milliseconds
     */
    public void seekTo(long ms) {
        mSourceHandler.post(() -> {
            if (mMediaPlayer != null) {
                long clampedMs = Math.max(0, Math.min(ms, mDurationMs.get()));
                mMediaPlayer.seekTo((int) clampedMs);
                mCurrentPositionMs.set(clampedMs);
            }
        });
    }

    /**
     * Set playback speed.
     * @param speed Speed multiplier: 0.25f, 0.5f, 1.0f, 1.5f, 2.0f, 4.0f
     */
    public void setSpeed(float speed) {
        float clampedSpeed = Math.max(0.25f, Math.min(4.0f, speed));
        mSpeed.set(clampedSpeed);

        mSourceHandler.post(() -> {
            if (mMediaPlayer != null) {
                // MediaPlayer.setPlaybackParams requires API 23+
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        android.media.PlaybackParams params = mMediaPlayer.getPlaybackParams();
                        params.setSpeed(clampedSpeed);
                        mMediaPlayer.setPlaybackParams(params);
                        Log.d(TAG, "Playback speed set to " + clampedSpeed + "x");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to set playback speed", e);
                    }
                } else {
                    Log.w(TAG, "Speed control requires API 23+");
                }
            }
        });
    }

    /**
     * Set looping mode.
     * @param looping true to loop the video
     */
    public void setLooping(boolean looping) {
        mLooping.set(looping);
        mSourceHandler.post(() -> {
            if (mMediaPlayer != null) {
                mMediaPlayer.setLooping(looping);
            }
        });
    }

    /**
     * Enable/disable audio.
     */
    public void setAudioEnabled(boolean enabled) {
        mAudioEnabled.set(enabled);
        mSourceHandler.post(() -> {
            if (mMediaPlayer != null) {
                mMediaPlayer.setVolume(enabled ? mVolume : 0f, enabled ? mVolume : 0f);
            }
        });
    }

    /**
     * Set volume (0.0 - 1.0).
     */
    public void setVolume(float volume) {
        mVolume = Math.max(0f, Math.min(1f, volume));
        mSourceHandler.post(() -> {
            if (mMediaPlayer != null) {
                mMediaPlayer.setVolume(
                        mAudioEnabled.get() ? mVolume : 0f,
                        mAudioEnabled.get() ? mVolume : 0f
                );
            }
        });
    }

    /**
     * Set the output surface for rendering.
     */
    public void setOutputSurface(Surface surface) {
        mOutputSurface = surface;
        mSourceHandler.post(() -> {
            if (mMediaPlayer != null) {
                mMediaPlayer.setSurface(surface);
            }
        });
    }

    /**
     * Get the current playback position in milliseconds.
     */
    public long getCurrentPosition() {
        return mCurrentPositionMs.get();
    }

    /**
     * Get the video duration in milliseconds.
     */
    public long getDuration() {
        return mDurationMs.get();
    }

    /**
     * Get video dimensions.
     */
    public int getVideoWidth() { return mVideoWidth; }
    public int getVideoHeight() { return mVideoHeight; }

    /**
     * Check if currently playing.
     */
    public boolean isPlaying() {
        return mRunning.get() && !mPaused.get();
    }

    /**
     * Check if the video source has a video loaded.
     */
    public boolean isLoaded() {
        return mVideoPath.get() != null;
    }

    /**
     * Extract a frame at a specific time using MediaMetadataRetriever.
     * @param timeMs Time in microseconds
     * @return Bitmap frame or null
     */
    public Bitmap getFrameAtTime(long timeMs) {
        if (mMetadataRetriever == null) return null;

        try {
            Bitmap frame = mMetadataRetriever.getFrameAtTime(
                    timeMs * 1000, // Convert ms to microseconds
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            );
            return frame;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract frame at " + timeMs + "ms", e);
            return null;
        }
    }

    /**
     * Extract all video frames at regular intervals.
     * @param intervalMs Interval between frames in milliseconds
     * @return List of Bitmaps
     */
    public java.util.List<Bitmap> extractFrames(long intervalMs) {
        java.util.List<Bitmap> frames = new java.util.ArrayList<>();
        if (mMetadataRetriever == null || mDurationMs.get() == 0) return frames;

        long duration = mDurationMs.get();
        for (long time = 0; time < duration; time += intervalMs) {
            Bitmap frame = getFrameAtTime(time);
            if (frame != null) {
                frames.add(frame);
            }
        }

        Log.i(TAG, "Extracted " + frames.size() + " frames");
        return frames;
    }

    /**
     * Set the frame ready listener.
     */
    public void setOnFrameReadyListener(OnFrameReadyListener listener) {
        mFrameListener = listener;
    }

    /**
     * Set the target resolution for frame delivery.
     */
    public void setResolution(int width, int height) {
        mTargetWidth = Math.max(1, width);
        mTargetHeight = Math.max(1, height);
    }

    /**
     * Release all resources.
     */
    public void release() {
        stop();
        releasePlayer();

        if (mSourceThread != null) {
            mSourceThread.quitSafely();
            mSourceThread = null;
            mSourceHandler = null;
        }
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private void startPlayback() {
        if (mMediaPlayer == null) return;

        try {
            if (mOutputSurface != null) {
                mMediaPlayer.setSurface(mOutputSurface);
            }
            mMediaPlayer.start();
            Log.d(TAG, "Playback started");
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to start playback", e);
            notifyError("Failed to start playback: " + e.getMessage());
        }
    }

    private void startFramePolling() {
        stopFramePolling();

        mFramePoller = new Runnable() {
            @Override
            public void run() {
                if (!mRunning.get() || mPaused.get()) return;

                try {
                    if (mMediaPlayer != null) {
                        mCurrentPositionMs.set(mMediaPlayer.getCurrentPosition());

                        // Extract current frame using MediaMetadataRetriever
                        if (mMetadataRetriever != null && mFrameListener != null) {
                            Bitmap frame = mMetadataRetriever.getFrameAtTime(
                                    mCurrentPositionMs.get() * 1000,
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            );

                            if (frame != null) {
                                deliverFrame(frame);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error polling frame", e);
                }

                if (mSourceHandler != null && mRunning.get()) {
                    mSourceHandler.postDelayed(this, FRAME_POLL_INTERVAL_MS);
                }
            }
        };

        mSourceHandler.postDelayed(mFramePoller, FRAME_POLL_INTERVAL_MS);
    }

    private void stopFramePolling() {
        if (mSourceHandler != null && mFramePoller != null) {
            mSourceHandler.removeCallbacks(mFramePoller);
            mFramePoller = null;
        }
    }

    private void deliverFrame(Bitmap bitmap) {
        if (mFrameListener == null || bitmap == null) return;

        try {
            // Scale if needed
            if (bitmap.getWidth() != mTargetWidth || bitmap.getHeight() != mTargetHeight) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, mTargetWidth, mTargetHeight, true);
                if (scaled != bitmap) {
                    bitmap = scaled;
                }
            }

            // Convert ARGB to NV21
            int[] argb = new int[mTargetWidth * mTargetHeight];
            bitmap.getPixels(argb, 0, mTargetWidth, 0, 0, mTargetWidth, mTargetHeight);

            byte[] nv21 = new byte[mTargetWidth * mTargetHeight * 3 / 2];
            int frameSize = mTargetWidth * mTargetHeight;
            int yIndex = 0;
            int uvIndex = frameSize;
            int idx = 0;

            for (int j = 0; j < mTargetHeight; j++) {
                for (int i = 0; i < mTargetWidth; i++) {
                    int pixel = argb[idx++];
                    int R = (pixel >> 16) & 0xFF;
                    int G = (pixel >> 8) & 0xFF;
                    int B = pixel & 0xFF;

                    int Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                    int U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                    int V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                    Y = clamp(Y, 0, 255);
                    U = clamp(U, 0, 255);
                    V = clamp(V, 0, 255);

                    nv21[yIndex++] = (byte) Y;

                    if (j % 2 == 0 && idx % 2 == 0) {
                        nv21[uvIndex++] = (byte) V;
                        nv21[uvIndex++] = (byte) U;
                    }
                }
            }

            mFrameListener.onFrameReady(nv21, mTargetWidth, mTargetHeight);

        } catch (Exception e) {
            Log.e(TAG, "Error delivering frame", e);
        }
    }

    private void releasePlayer() {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.setSurface(null);
                mMediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaPlayer", e);
            }
            mMediaPlayer = null;
        }

        if (mMetadataRetriever != null) {
            try {
                mMetadataRetriever.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaMetadataRetriever", e);
            }
            mMetadataRetriever = null;
        }
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private void notifyVideoEnded() {
        if (mFrameListener != null) {
            mFrameListener.onVideoEnded();
        }
    }

    private void notifyError(String message) {
        if (mFrameListener != null) {
            mFrameListener.onError(message);
        }
    }
}

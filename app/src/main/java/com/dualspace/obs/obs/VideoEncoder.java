package com.dualspace.obs.obs;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Hardware H.264/H.265 video encoder using MediaCodec.
 * Provides an input Surface for screen capture rendering.
 */
public class VideoEncoder {

    private static final String TAG = "VideoEncoder";
    private static final long TIMEOUT_US = 10_000; // 10 seconds

    // ── Listener ────────────────────────────────────────────────────────────
    public interface OnEncodedDataListener {
        void onEncodedData(byte[] data, MediaCodec.BufferInfo info);
    }

    // ── Fields ──────────────────────────────────────────────────────────────
    private MediaCodec mEncoder;
    private Surface mInputSurface;
    private OnEncodedDataListener mListener;

    private int mWidth;
    private int mHeight;
    private int mBitrate;
    private int mFps;
    private int mIframeInterval;
    private boolean mUseHevc;

    private boolean mRunning = false;
    private final Object mLock = new Object();

    private HandlerThread mEncoderThread;
    private Handler mEncoderHandler;

    private MediaFormat mOutputFormat;
    private boolean mOutputFormatChanged = false;

    // ── Configure ───────────────────────────────────────────────────────────

    /**
     * Configure the encoder with the given parameters.
     * Resolutions supported: 480p to 4K, bitrate 500k-50M, FPS 15-120.
     */
    public void configure(int width, int height, int bitrate, int fps,
                          int iframeInterval, boolean useHevc) {
        synchronized (mLock) {
            if (mRunning) {
                throw new IllegalStateException("Cannot configure while encoder is running");
            }

            // Validate parameters
            mWidth = clamp(width, 640, 3840);
            mHeight = clamp(height, 480, 2160);
            mBitrate = clamp(bitrate, 500_000, 50_000_000);
            mFps = clamp(fps, 15, 120);
            mIframeInterval = Math.max(1, iframeInterval);
            mUseHevc = useHevc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;

            try {
                String mimeType = mUseHevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
                mEncoder = createEncoder(mimeType);

                MediaFormat format = MediaFormat.createVideoFormat(mimeType, mWidth, mHeight);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, mBitrate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, mFps);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, mIframeInterval);

                // Configure for low-latency if available (API 23+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        format.setInteger(MediaFormat.KEY_LATENCY, 0);
                    } catch (Exception ignored) {
                        // Not all encoders support this
                    }
                }

                // Request key frame every iframeInterval seconds via CSD
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        Bundle params = new Bundle();
                        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        mEncoder.setParameters(params);
                    } catch (Exception ignored) {}
                }

                mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                mInputSurface = mEncoder.createInputSurface();

                Log.i(TAG, String.format("Configured %s encoder: %dx%d @ %dfps, %d kbps, I-frame every %ds",
                        mUseHevc ? "HEVC" : "H.264",
                        mWidth, mHeight, mFps, mBitrate / 1000, mIframeInterval));

            } catch (Exception e) {
                Log.e(TAG, "Failed to configure video encoder", e);
                releaseEncoder();
                throw new RuntimeException("Video encoder configuration failed", e);
            }
        }
    }

    /**
     * Simplified configure with defaults.
     */
    public void configure(int width, int height, int bitrate, int fps) {
        configure(width, height, bitrate, fps, 2, false);
    }

    // ── Start / Stop ────────────────────────────────────────────────────────

    /**
     * Start encoding. Data will be delivered via the listener.
     */
    public void start() {
        synchronized (mLock) {
            if (mEncoder == null) {
                throw new IllegalStateException("Encoder not configured");
            }
            if (mRunning) {
                Log.w(TAG, "Encoder already running");
                return;
            }

            mRunning = true;
            mOutputFormatChanged = false;

            mEncoderThread = new HandlerThread("VideoEncoder-Thread");
            mEncoderThread.start();
            mEncoderHandler = new Handler(mEncoderThread.getLooper());

            mEncoder.start();
            mEncoderHandler.post(this::encodeLoop);

            Log.i(TAG, "Video encoder started");
        }
    }

    /**
     * Stop encoding.
     */
    public void stop() {
        synchronized (mLock) {
            if (!mRunning) return;
            mRunning = false;

            if (mEncoderHandler != null) {
                mEncoderHandler.post(() -> {
                    try {
                        if (mEncoder != null) {
                            mEncoder.signalEndOfInputStream();
                            mEncoder.stop();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping encoder", e);
                    }
                });
            }

            if (mEncoderThread != null) {
                mEncoderThread.quitSafely();
                try {
                    mEncoderThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                mEncoderThread = null;
                mEncoderHandler = null;
            }

            Log.i(TAG, "Video encoder stopped");
        }
    }

    /**
     * Release all resources.
     */
    public void release() {
        synchronized (mLock) {
            stop();
            releaseEncoder();
        }
    }

    // ── Public getters ──────────────────────────────────────────────────────

    /**
     * Get the input Surface for rendering frames into the encoder.
     * Screen capture output should target this surface.
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    /**
     * Get the output MediaFormat (available after encoder starts producing data).
     */
    public MediaFormat getOutputFormat() {
        return mOutputFormat;
    }

    public int getWidth() { return mWidth; }
    public int getHeight() { return mHeight; }
    public int getBitrate() { return mBitrate; }
    public int getFps() { return mFps; }

    // ── Dynamic adjustments ─────────────────────────────────────────────────

    /**
     * Dynamically adjust the bitrate.
     */
    public void setBitrate(int bitrateBps) {
        synchronized (mLock) {
            if (mEncoder == null || !mRunning) return;
            mBitrate = clamp(bitrateBps, 500_000, 50_000_000);
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, mBitrate);
                mEncoder.setParameters(params);
                Log.d(TAG, "Bitrate adjusted to " + (mBitrate / 1000) + " kbps");
            } catch (Exception e) {
                Log.e(TAG, "Failed to set bitrate", e);
            }
        }
    }

    /**
     * Request a key frame (I-frame) to be generated.
     */
    public void requestKeyFrame() {
        synchronized (mLock) {
            if (mEncoder == null || !mRunning) return;
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                mEncoder.setParameters(params);
                Log.d(TAG, "Key frame requested");
            } catch (Exception e) {
                Log.e(TAG, "Failed to request key frame", e);
            }
        }
    }

    // ── Listener ────────────────────────────────────────────────────────────

    public void setOnEncodedDataListener(OnEncodedDataListener listener) {
        mListener = listener;
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private MediaCodec createEncoder(String mimeType) {
        try {
            return MediaCodec.createEncoderByType(mimeType);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create encoder for " + mimeType, e);
            // Fallback to H.264 if HEVC fails
            if (mimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                Log.w(TAG, "Falling back to H.264");
                mUseHevc = false;
                return createEncoder(MediaFormat.MIMETYPE_VIDEO_AVC);
            }
            throw new RuntimeException("No video encoder available", e);
        }
    }

    private void encodeLoop() {
        if (!mRunning || mEncoder == null) return;

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        ByteBuffer[] outputBuffers = null;

        try {
            while (mRunning) {
                int outputBufferIndex = mEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);

                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    mOutputFormat = mEncoder.getOutputFormat();
                    mOutputFormatChanged = true;
                    Log.d(TAG, "Output format changed: " + mOutputFormat);
                    continue;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = mEncoder.getOutputBuffers();
                    continue;
                }

                if (outputBufferIndex < 0) continue;

                ByteBuffer outputBuffer;
                if (outputBuffers != null) {
                    outputBuffer = outputBuffers[outputBufferIndex];
                } else {
                    outputBuffer = mEncoder.getOutputBuffer(outputBufferIndex);
                }

                if (outputBuffer != null && mListener != null && bufferInfo.size > 0) {
                    byte[] outData = new byte[bufferInfo.size];
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    outputBuffer.get(outData);

                    // Deliver encoded data
                    final byte[] data = outData;
                    final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    info.set(bufferInfo.offset, bufferInfo.size,
                            bufferInfo.presentationTimeUs, bufferInfo.flags);

                    mListener.onEncodedData(data, info);
                }

                mEncoder.releaseOutputBuffer(outputBufferIndex, false);

                // Check for end of stream
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.i(TAG, "Output EOS received");
                    break;
                }
            }
        } catch (Exception e) {
            if (mRunning) {
                Log.e(TAG, "Error in encode loop", e);
            }
        }
    }

    private void releaseEncoder() {
        try {
            if (mEncoder != null) {
                mEncoder.stop();
                mEncoder.release();
                mEncoder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing encoder", e);
        }

        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }

        mOutputFormat = null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

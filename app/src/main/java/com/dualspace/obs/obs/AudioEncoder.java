package com.dualspace.obs.obs;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Audio encoder using AudioRecord for mic capture and MediaCodec for AAC encoding.
 * Supports mono/stereo, sample rate selection, gain control, and audio level monitoring.
 */
public class AudioEncoder {

    private static final String TAG = "AudioEncoder";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final long TIMEOUT_US = 10_000;

    // ── Audio Source ────────────────────────────────────────────────────────
    public enum AudioSource {
        MIC(MediaRecorder.AudioSource.MIC),
        VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION),
        VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION),
        CAMCORDER(MediaRecorder.AudioSource.CAMCORDER),
        DEFAULT(MediaRecorder.AudioSource.DEFAULT);

        final int id;
        AudioSource(int id) { this.id = id; }
    }

    // ── Listener ────────────────────────────────────────────────────────────
    public interface OnEncodedDataListener {
        void onEncodedData(byte[] data, MediaCodec.BufferInfo info);
    }

    // ── Fields ──────────────────────────────────────────────────────────────
    private MediaCodec mEncoder;
    private AudioRecord mAudioRecord;
    private OnEncodedDataListener mListener;

    private int mSampleRate;
    private int mChannels;
    private int mBitrate;
    private AudioSource mAudioSource = AudioSource.MIC;
    private float mGain = 1.0f;

    private boolean mRunning = false;
    private final Object mLock = new Object();

    private HandlerThread mEncodeThread;
    private Handler mEncodeHandler;
    private HandlerThread mRecordThread;
    private Handler mRecordHandler;

    private MediaFormat mOutputFormat;
    private boolean mOutputFormatChanged = false;

    // Audio level tracking
    private float mCurrentAudioLevel = 0f;
    private static final float LEVEL_SMOOTHING = 0.1f;

    // ── Configure ───────────────────────────────────────────────────────────

    /**
     * Configure the audio encoder.
     * @param sampleRate typically 44100 or 48000
     * @param channels   1 (mono) or 2 (stereo)
     * @param bitrate    typically 64000-320000
     */
    public void configure(int sampleRate, int channels, int bitrate) {
        synchronized (mLock) {
            if (mRunning) {
                throw new IllegalStateException("Cannot configure while encoder is running");
            }

            mSampleRate = (sampleRate == 44100 || sampleRate == 48000) ? sampleRate : 48000;
            mChannels = (channels == 1 || channels == 2) ? channels : 2;
            mBitrate = Math.max(64000, Math.min(320000, bitrate));

            try {
                mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);

                MediaFormat format = new MediaFormat();
                format.setString(MediaFormat.KEY_MIME, MIME_TYPE);
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, mSampleRate);
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, mChannels);
                format.setInteger(MediaFormat.KEY_BIT_RATE, mBitrate);
                // Use AAC LC profile
                format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC);

                mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                // Create AudioRecord
                int channelConfig = mChannels == 1
                        ? AudioFormat.CHANNEL_IN_MONO
                        : AudioFormat.CHANNEL_IN_STEREO;
                int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
                int minBufSize = AudioRecord.getMinBufferSize(
                        mSampleRate, channelConfig, audioFormat);

                mAudioRecord = new AudioRecord(
                        mAudioSource.id,
                        mSampleRate,
                        channelConfig,
                        audioFormat,
                        Math.max(minBufSize, mSampleRate * mChannels * 2 * 4) // 4 frames buffer
                );

                Log.i(TAG, String.format("Configured audio encoder: %dHz, %d ch, %d kbps",
                        mSampleRate, mChannels, mBitrate / 1000));

            } catch (Exception e) {
                Log.e(TAG, "Failed to configure audio encoder", e);
                releaseEncoder();
                throw new RuntimeException("Audio encoder configuration failed", e);
            }
        }
    }

    // ── Start / Stop ────────────────────────────────────────────────────────

    /**
     * Start audio capture and encoding.
     */
    public void start() {
        synchronized (mLock) {
            if (mEncoder == null || mAudioRecord == null) {
                throw new IllegalStateException("Encoder not configured");
            }
            if (mRunning) {
                Log.w(TAG, "Audio encoder already running");
                return;
            }

            mRunning = true;
            mOutputFormatChanged = false;

            // Start encode thread
            mEncodeThread = new HandlerThread("AudioEncoder-Encode");
            mEncodeThread.start();
            mEncodeHandler = new Handler(mEncodeThread.getLooper());

            // Start record thread
            mRecordThread = new HandlerThread("AudioEncoder-Record");
            mRecordThread.start();
            mRecordHandler = new Handler(mRecordThread.getLooper());

            try {
                mEncoder.start();
                mAudioRecord.startRecording();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start audio encoder", e);
                mRunning = false;
                throw new RuntimeException("Failed to start audio", e);
            }

            mEncodeHandler.post(this::encodeLoop);
            mRecordHandler.post(this::recordLoop);

            Log.i(TAG, "Audio encoder started");
        }
    }

    /**
     * Stop audio capture and encoding.
     */
    public void stop() {
        synchronized (mLock) {
            if (!mRunning) return;
            mRunning = false;

            // Stop recording
            if (mAudioRecord != null) {
                try {
                    mAudioRecord.stop();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping AudioRecord", e);
                }
            }

            // Stop encoding thread
            if (mRecordThread != null) {
                mRecordThread.quitSafely();
                try { mRecordThread.join(5000); } catch (InterruptedException ignored) {}
                mRecordThread = null;
                mRecordHandler = null;
            }

            if (mEncodeThread != null) {
                mEncodeThread.quitSafely();
                try { mEncodeThread.join(5000); } catch (InterruptedException ignored) {}
                mEncodeThread = null;
                mEncodeHandler = null;
            }

            try {
                mEncoder.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping MediaCodec", e);
            }

            Log.i(TAG, "Audio encoder stopped");
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

    // ── Controls ────────────────────────────────────────────────────────────

    /**
     * Set the audio source.
     */
    public void setAudioSource(AudioSource source) {
        synchronized (mLock) {
            if (mRunning) {
                Log.w(TAG, "Cannot change audio source while recording");
                return;
            }
            mAudioSource = source;
            Log.i(TAG, "Audio source set to " + source);
        }
    }

    /**
     * Set the audio gain multiplier (1.0 = normal).
     * Range: 0.0 (muted) to 5.0 (amplified).
     */
    public void setGain(float gain) {
        mGain = Math.max(0.0f, Math.min(5.0f, gain));
        Log.d(TAG, "Gain set to " + mGain);
    }

    /**
     * Get the current audio level (0.0 to 1.0) based on recent PCM samples.
     */
    public float getAudioLevel() {
        return mCurrentAudioLevel;
    }

    /**
     * Get the output MediaFormat.
     */
    public MediaFormat getOutputFormat() {
        return mOutputFormat;
    }

    /**
     * Set the encoded data listener.
     */
    public void setOnEncodedDataListener(OnEncodedDataListener listener) {
        mListener = listener;
    }

    public int getSampleRate() { return mSampleRate; }
    public int getChannels() { return mChannels; }

    // ── Internal ────────────────────────────────────────────────────────────

    /**
     * Record loop: reads PCM data from AudioRecord and feeds it to the encoder.
     */
    private void recordLoop() {
        if (!mRunning || mAudioRecord == null || mEncoder == null) return;

        int channelConfig = mChannels == 1
                ? AudioFormat.CHANNEL_IN_MONO
                : AudioFormat.CHANNEL_IN_STEREO;
        int bytesPerFrame = mChannels * 2; // 16-bit PCM
        // Read in small chunks to reduce latency
        int readSize = mSampleRate * bytesPerFrame / 20; // ~50ms of audio

        try {
            while (mRunning) {
                int inputBufferIndex = mEncoder.dequeueInputBuffer(TIMEOUT_US);
                if (inputBufferIndex < 0) continue;

                ByteBuffer inputBuffer = mEncoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer == null) continue;

                inputBuffer.clear();

                byte[] pcmData = new byte[readSize];
                int bytesRead = mAudioRecord.read(pcmData, 0, readSize);

                if (bytesRead <= 0) {
                    // No data, release buffer and wait
                    mEncoder.queueInputBuffer(inputBufferIndex, 0, 0,
                            0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }

                // Apply gain if != 1.0
                if (Math.abs(mGain - 1.0f) > 0.001f) {
                    applyGain(pcmData, bytesRead, mGain);
                }

                // Calculate audio level
                calculateAudioLevel(pcmData, bytesRead);

                inputBuffer.put(pcmData, 0, bytesRead);

                long presentationTimeUs = (System.nanoTime() / 1000);
                mEncoder.queueInputBuffer(
                        inputBufferIndex, 0, bytesRead,
                        presentationTimeUs, 0
                );
            }
        } catch (Exception e) {
            if (mRunning) {
                Log.e(TAG, "Error in record loop", e);
            }
        }
    }

    /**
     * Encode loop: reads encoded AAC data from the encoder and delivers it.
     */
    private void encodeLoop() {
        if (!mRunning || mEncoder == null) return;

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        try {
            while (mRunning) {
                int outputBufferIndex = mEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);

                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    mOutputFormat = mEncoder.getOutputFormat();
                    mOutputFormatChanged = true;
                    Log.d(TAG, "Audio output format changed: " + mOutputFormat);
                    continue;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // Handled by getInputBuffer/getOutputBuffer on API 21+
                    continue;
                }

                if (outputBufferIndex < 0) continue;

                ByteBuffer outputBuffer = mEncoder.getOutputBuffer(outputBufferIndex);
                if (outputBuffer != null && mListener != null && bufferInfo.size > 0) {
                    byte[] outData = new byte[bufferInfo.size];
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    outputBuffer.get(outData);

                    final byte[] data = outData;
                    final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    info.set(bufferInfo.offset, bufferInfo.size,
                            bufferInfo.presentationTimeUs, bufferInfo.flags);

                    mListener.onEncodedData(data, info);
                }

                mEncoder.releaseOutputBuffer(outputBufferIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.i(TAG, "Audio output EOS received");
                    break;
                }
            }
        } catch (Exception e) {
            if (mRunning) {
                Log.e(TAG, "Error in encode loop", e);
            }
        }
    }

    /**
     * Apply gain to 16-bit PCM data in-place.
     */
    private void applyGain(byte[] pcmData, int length, float gain) {
        for (int i = 0; i < length - 1; i += 2) {
            short sample = (short) ((pcmData[i + 1] << 8) | (pcmData[i] & 0xFF));
            sample = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, (int) (sample * gain)));
            pcmData[i] = (byte) (sample & 0xFF);
            pcmData[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
    }

    /**
     * Calculate RMS audio level and smooth it.
     */
    private void calculateAudioLevel(byte[] pcmData, int length) {
        if (length < 2) return;

        double sumSquares = 0;
        int sampleCount = length / 2;
        for (int i = 0; i < length - 1; i += 2) {
            short sample = (short) ((pcmData[i + 1] << 8) | (pcmData[i] & 0xFF));
            sumSquares += (double) sample * sample;
        }

        double rms = Math.sqrt(sumSquares / sampleCount);
        float level = (float) (rms / Short.MAX_VALUE);
        level = Math.min(1.0f, level * 3.0f); // Amplify for visualization

        // Exponential smoothing
        mCurrentAudioLevel = mCurrentAudioLevel * (1.0f - LEVEL_SMOOTHING) + level * LEVEL_SMOOTHING;
    }

    private void releaseEncoder() {
        try {
            if (mEncoder != null) {
                mEncoder.release();
                mEncoder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing encoder", e);
        }

        try {
            if (mAudioRecord != null) {
                mAudioRecord.release();
                mAudioRecord = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing AudioRecord", e);
        }

        mOutputFormat = null;
    }
}

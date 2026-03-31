package com.dualspace.obs.obs;

import android.media.MediaCodec;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RTMP streaming manager with auto-reconnect, platform presets, and health monitoring.
 * Uses a basic Socket-based RTMP handshake and streaming implementation.
 */
public class StreamManager {

    private static final String TAG = "StreamManager";
    private static final int DEFAULT_PORT = 1935;
    private static final int SOCKET_TIMEOUT_MS = 10_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int BASE_RECONNECT_DELAY_MS = 1000;

    // ── Stream Health ───────────────────────────────────────────────────────
    public enum StreamHealth {
        EXCELLENT,   // 0-1% dropped, low latency
        GOOD,        // 1-3% dropped
        FAIR,        // 3-5% dropped
        POOR,        // 5-10% dropped
        BAD,         // >10% dropped or high latency
        DISCONNECTED // Not connected
    }

    // ── Platform Presets ────────────────────────────────────────────────────
    public enum PlatformPreset {
        YOUTUBE("rtmp://a.rtmp.youtube.com/live2", 4500, 128),
        TWITCH("rtmp://live.twitch.tv/app", 4500, 160),
        FACEBOOK("rtmps://live-api-s.facebook.com:443/rtmp/", 4000, 128),
        KICK("rtmp://fa723fc0e0.a0377.us-east-1.ingest.twitch.tv/app", 4500, 128),
        CUSTOM("", 4500, 128);

        public final String ingestUrl;
        public final int recommendedVideoBitrate; // kbps
        public final int recommendedAudioBitrate; // kbps

        PlatformPreset(String url, int vBitrate, int aBitrate) {
            this.ingestUrl = url;
            this.recommendedVideoBitrate = vBitrate;
            this.recommendedAudioBitrate = aBitrate;
        }
    }

    // ── Stream Stats ────────────────────────────────────────────────────────
    public static class StreamStats {
        public long bytesSent;
        public long framesSent;
        public long durationMs;
        public int droppedFrames;
        public float droppedFramePercent;
        public int currentBitrate; // kbps
        public long estimatedViewers;
        public String serverAddress;

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "StreamStats{sent=%dKB, frames=%d, dropped=%.1f%%, bitrate=%dkbps, dur=%.1fs}",
                    bytesSent / 1024, framesSent, droppedFramePercent,
                    currentBitrate, durationMs / 1000.0);
        }
    }

    // ── Connection Listener ─────────────────────────────────────────────────
    public interface OnConnectionListener {
        void onConnected();
        void onDisconnected(String reason);
        void onError(String message);
        void onReconnecting(int attempt);
    }

    // ── Fields ──────────────────────────────────────────────────────────────
    private Socket mSocket;
    private OutputStream mOutputStream;
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final AtomicBoolean mReconnecting = new AtomicBoolean(false);

    private HandlerThread mStreamThread;
    private Handler mStreamHandler;
    private HandlerThread mHealthThread;
    private Handler mHealthHandler;

    private OnConnectionListener mConnectionListener;

    // Stats
    private final AtomicLong mBytesSent = new AtomicLong(0);
    private final AtomicLong mFramesSent = new AtomicLong(0);
    private final AtomicLong mStartTimeMs = new AtomicLong(0);
    private final AtomicLong mDroppedFrames = new AtomicLong(0);
    private final AtomicLong mLastBytesSent = new AtomicLong(0);
    private volatile int mCurrentBitrate = 0;
    private int mReconnectAttempt = 0;

    private String mServerUrl;
    private String mStreamKey;
    private String mServerHost;
    private int mServerPort;

    private final Object mConnectionLock = new Object();

    // RTMP state
    private volatile int mChunkSize = 4096;
    private volatile int mStreamId = 0;
    private volatile int mWindowAckSize = 2500000;

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Connect to an RTMP server.
     * @param url  Full RTMP URL, e.g. "rtmp://live.twitch.tv/app"
     * @param key  Stream key
     */
    public void connect(String url, String key) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("URL must not be null or empty");
        }
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Stream key must not be null or empty");
        }

        mServerUrl = url;
        mStreamKey = key;
        parseServerAddress(url);

        mStreamThread = new HandlerThread("RTMP-Stream");
        mStreamThread.start();
        mStreamHandler = new Handler(mStreamThread.getLooper());

        mHealthThread = new HandlerThread("RTMP-Health");
        mHealthThread.start();
        mHealthHandler = new Handler(mHealthThread.getLooper());

        mStreamHandler.post(this::doConnect);
    }

    /**
     * Disconnect from the RTMP server.
     */
    public void disconnect() {
        mConnected.set(false);
        mReconnecting.set(false);

        mStreamHandler.post(() -> {
            closeSocket();

            if (mHealthThread != null) {
                mHealthThread.quitSafely();
                mHealthThread = null;
            }
        });

        if (mStreamThread != null) {
            mStreamThread.quitSafely();
            mStreamThread = null;
        }

        notifyDisconnected("User disconnected");
        Log.i(TAG, "Disconnected from " + mServerHost);
    }

    /**
     * Test the connection to an RTMP server without streaming.
     * @return true if connection was successful
     */
    public boolean testConnection(String url) {
        if (url == null || url.isEmpty()) return false;

        String[] parts = parseRtmpUrl(url);
        if (parts == null) return false;

        Socket testSocket = new Socket();
        try {
            testSocket.connect(
                    new InetSocketAddress(parts[0], Integer.parseInt(parts[1])),
                    SOCKET_TIMEOUT_MS
            );
            testSocket.close();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Connection test failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get current stream health.
     */
    public StreamHealth getHealth() {
        if (!mConnected.get()) return StreamHealth.DISCONNECTED;

        float droppedPercent = calculateDroppedPercent();
        if (droppedPercent <= 1.0f) return StreamHealth.EXCELLENT;
        if (droppedPercent <= 3.0f) return StreamHealth.GOOD;
        if (droppedPercent <= 5.0f) return StreamHealth.FAIR;
        if (droppedPercent <= 10.0f) return StreamHealth.POOR;
        return StreamHealth.BAD;
    }

    /**
     * Get current stream stats.
     */
    public StreamStats getStats() {
        StreamStats stats = new StreamStats();
        stats.bytesSent = mBytesSent.get();
        stats.framesSent = mFramesSent.get();
        stats.droppedFrames = (int) mDroppedFrames.get();
        stats.durationMs = mStartTimeMs.get() > 0
                ? System.currentTimeMillis() - mStartTimeMs.get()
                : 0;
        stats.droppedFramePercent = calculateDroppedPercent();
        stats.currentBitrate = mCurrentBitrate;
        stats.estimatedViewers = -1; // RTMP doesn't support viewer count natively
        stats.serverAddress = mServerHost + ":" + mServerPort;
        return stats;
    }

    /**
     * Check if connected to the server.
     */
    public boolean isConnected() {
        return mConnected.get();
    }

    public void setOnConnectionListener(OnConnectionListener listener) {
        mConnectionListener = listener;
    }

    // ── Data sending (called from ObsCore) ──────────────────────────────────

    /**
     * Send encoded video data to the RTMP stream.
     */
    public void sendVideoData(byte[] data, MediaCodec.BufferInfo info) {
        if (!mConnected.get() || mOutputStream == null) {
            mDroppedFrames.incrementAndGet();
            return;
        }

        mStreamHandler.post(() -> {
            try {
                // Wrap in RTMP message format
                byte[] rtmpMessage = buildRtmpVideoMessage(data, info);
                mOutputStream.write(rtmpMessage);
                mOutputStream.flush();
                mBytesSent.addAndGet(rtmpMessage.length);
                mFramesSent.incrementAndGet();
            } catch (IOException e) {
                Log.e(TAG, "Failed to send video data", e);
                mDroppedFrames.incrementAndGet();
                handleConnectionError("Video send failed: " + e.getMessage());
            }
        });
    }

    /**
     * Send encoded audio data to the RTMP stream.
     */
    public void sendAudioData(byte[] data, MediaCodec.BufferInfo info) {
        if (!mConnected.get() || mOutputStream == null) return;

        mStreamHandler.post(() -> {
            try {
                byte[] rtmpMessage = buildRtmpAudioMessage(data, info);
                mOutputStream.write(rtmpMessage);
                mOutputStream.flush();
                mBytesSent.addAndGet(rtmpMessage.length);
            } catch (IOException e) {
                Log.e(TAG, "Failed to send audio data", e);
                handleConnectionError("Audio send failed: " + e.getMessage());
            }
        });
    }

    // ── Internal: Connection ────────────────────────────────────────────────

    private void doConnect() {
        synchronized (mConnectionLock) {
            try {
                Log.i(TAG, "Connecting to " + mServerHost + ":" + mServerPort);

                mSocket = new Socket();
                mSocket.connect(
                        new InetSocketAddress(mServerHost, mServerPort),
                        SOCKET_TIMEOUT_MS
                );
                mSocket.setTcpNoDelay(true);
                mSocket.setKeepAlive(true);

                mOutputStream = mSocket.getOutputStream();

                // RTMP Handshake
                performRtmpHandshake();

                // Connect command
                sendConnectCommand();

                mConnected.set(true);
                mStartTimeMs.set(System.currentTimeMillis());
                mBytesSent.set(0);
                mFramesSent.set(0);
                mDroppedFrames.set(0);
                mReconnectAttempt = 0;

                notifyConnected();
                startHealthMonitoring();

                Log.i(TAG, "Connected to RTMP server: " + mServerHost);

            } catch (SocketTimeoutException e) {
                Log.e(TAG, "Connection timeout", e);
                notifyError("Connection timeout");
                scheduleReconnect();
            } catch (IOException e) {
                Log.e(TAG, "Connection failed", e);
                notifyError("Connection failed: " + e.getMessage());
                scheduleReconnect();
            }
        }
    }

    private void performRtmpHandshake() throws IOException {
        OutputStream os = mOutputStream;
        byte[] rtmpVersion = new byte[]{0x03}; // RTMP version 3

        // C0: version
        os.write(rtmpVersion);

        // C1: timestamp (4) + zero (4) + random (1528)
        byte[] c1 = new byte[1536];
        int time = (int) (System.currentTimeMillis() / 1000);
        c1[0] = (byte) ((time >> 24) & 0xFF);
        c1[1] = (byte) ((time >> 16) & 0xFF);
        c1[2] = (byte) ((time >> 8) & 0xFF);
        c1[3] = (byte) (time & 0xFF);
        // Remaining bytes are zero + random (already zero-initialized)
        os.write(c1);
        os.flush();

        // S0 + S1: Read server response (1 + 1536 bytes)
        byte[] s0s1 = new byte[1537];
        readFully(mSocket.getInputStream(), s0s1);

        // C2: Echo S1 time + S1 random
        byte[] c2 = new byte[1536];
        System.arraycopy(s0s1, 1, c2, 0, 1536);
        os.write(c2);
        os.flush();

        // S2: Read remaining 1536 bytes
        byte[] s2 = new byte[1536];
        readFully(mSocket.getInputStream(), s2);

        Log.d(TAG, "RTMP handshake completed");
    }

    private void sendConnectCommand() throws IOException {
        // Build a basic RTMP connect command (AMF0 encoded)
        // This is a simplified version - production code would use a proper AMF encoder
        ByteBuffer buffer = ByteBuffer.allocate(512);

        // RTMP Header (type 0, stream 0)
        buffer.put((byte) 0x03);  // Chunk stream ID 3 (commands)
        buffer.put((byte) 0x00);  // Message type 0 (0 = AMF0 command)
        buffer.putInt((int) (System.currentTimeMillis() / 1000)); // Timestamp
        buffer.putInt(0);         // Stream ID

        // Message type: AMF0 command (20)
        buffer.put((byte) 20);

        // Message length placeholder (will update)
        int lengthPos = buffer.position();
        buffer.putInt(0);

        // AMF0: connect command
        byte[] connectPayload = buildConnectAmfPayload();
        buffer.put(connectPayload);

        // Update length
        int len = connectPayload.length;
        buffer.putInt(lengthPos, len);

        byte[] packet = new byte[buffer.position()];
        buffer.flip();
        buffer.get(packet);

        mOutputStream.write(packet);
        mOutputStream.flush();

        // Send createStream command
        sendCreateStreamCommand();
    }

    private byte[] buildConnectAmfPayload() {
        // Simplified AMF0 connect payload
        // In a production implementation, use a proper AMF0 encoder
        ByteBuffer amf = ByteBuffer.allocate(512);

        // String "connect"
        writeAmf0String(amf, "connect");

        // Number (transaction ID = 1)
        writeAmf0Number(amf, 1.0);

        // Object: { app: streamKey, flashver: "FMLE/3.0", tcUrl: serverUrl }
        amf.put((byte) 0x03); // Object marker
        writeAmf0ObjectProperty(amf, "app", mStreamKey);
        writeAmf0ObjectProperty(amf, "flashver", "FMLE/3.0 (compatible; OBS)");
        writeAmf0ObjectProperty(amf, "tcUrl", mServerUrl + "/" + mStreamKey);
        amf.put((byte) 0x00);
        amf.put((byte) 0x00);
        amf.put((byte) 0x09); // End of object

        byte[] result = new byte[amf.position()];
        amf.flip();
        amf.get(result);
        return result;
    }

    private void sendCreateStreamCommand() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(256);

        buffer.put((byte) 0x03);
        buffer.put((byte) 0x00);
        buffer.putInt((int) (System.currentTimeMillis() / 1000));
        buffer.putInt(0);
        buffer.put((byte) 20); // AMF0 command

        int lengthPos = buffer.position();
        buffer.putInt(0);

        ByteBuffer amf = ByteBuffer.allocate(128);
        writeAmf0String(amf, "createStream");
        writeAmf0Number(amf, 2.0);
        amf.put((byte) 0x05); // Null

        byte[] payload = new byte[amf.position()];
        amf.flip();
        amf.get(payload);

        buffer.putInt(lengthPos, payload.length);
        buffer.put(payload);

        byte[] packet = new byte[buffer.position()];
        buffer.flip();
        buffer.get(packet);

        mOutputStream.write(packet);
        mOutputStream.flush();
    }

    private byte[] buildRtmpVideoMessage(byte[] data, MediaCodec.BufferInfo info) {
        // Build RTMP video message (message type 9)
        ByteBuffer msg = ByteBuffer.allocate(data.length + 32);

        // Chunk header
        msg.put((byte) 0x06); // Chunk stream ID for video
        msg.put((byte) 0x17); // Type 1 (relative timestamp), message type 0x09 video
        msg.putInt((int) (info.presentationTimeUs / 1000));
        msg.putInt(data.length + 5); // message length

        // Video tag body
        // Frame type + codec
        boolean isKeyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        msg.put((byte) (isKeyFrame ? 0x17 : 0x27)); // Keyframe=0x17, Inter=0x27 (AVC/H.264)
        msg.put((byte) 0x01); // AVC sequence header or NALU
        msg.put((byte) 0x00);
        msg.put((byte) 0x00);
        msg.put((byte) 0x00);

        msg.put(data);

        byte[] result = new byte[msg.position()];
        msg.flip();
        msg.get(result);
        return result;
    }

    private byte[] buildRtmpAudioMessage(byte[] data, MediaCodec.BufferInfo info) {
        // Build RTMP audio message (message type 8)
        ByteBuffer msg = ByteBuffer.allocate(data.length + 16);

        // Chunk header
        msg.put((byte) 0x07); // Chunk stream ID for audio
        msg.put((byte) 0x08); // Type 1 (relative timestamp), message type 0x08 audio
        msg.putInt((int) (info.presentationTimeUs / 1000));
        msg.putInt(data.length + 2); // message length

        // Audio tag body
        msg.put((byte) 0xAF); // Sound format: AAC, 44kHz, stereo, 16-bit
        msg.put((byte) 0x01); // AAC raw frame
        msg.put(data);

        byte[] result = new byte[msg.position()];
        msg.flip();
        msg.get(result);
        return result;
    }

    // ── AMF0 helpers ────────────────────────────────────────────────────────

    private void writeAmf0String(ByteBuffer buf, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) bytes = java.util.Arrays.copyOf(bytes, 0xFFFF);
        buf.putShort((short) bytes.length);
        buf.put(bytes);
    }

    private void writeAmf0Number(ByteBuffer buf, double value) {
        buf.put((byte) 0x00); // Number marker
        buf.putDouble(value);
    }

    private void writeAmf0ObjectProperty(ByteBuffer buf, String key, String value) {
        writeAmf0String(buf, key);
        writeAmf0String(buf, value);
    }

    private void readFully(java.io.InputStream is, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = is.read(buffer, offset, buffer.length - offset);
            if (read < 0) throw new IOException("Unexpected end of stream");
            offset += read;
        }
    }

    // ── Health Monitoring ───────────────────────────────────────────────────

    private void startHealthMonitoring() {
        if (mHealthHandler == null) return;

        mHealthHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!mConnected.get()) return;

                // Calculate bitrate
                long currentBytes = mBytesSent.get();
                long deltaBytes = currentBytes - mLastBytesSent.get();
                mLastBytesSent.set(currentBytes);
                mCurrentBitrate = (int) ((deltaBytes * 8) / 1000); // kbps

                StreamHealth health = getHealth();
                Log.d(TAG, "Health: " + health + ", bitrate: " + mCurrentBitrate + " kbps"
                        + ", dropped: " + calculateDroppedPercent() + "%");

                // Schedule next check (every 2 seconds)
                if (mHealthHandler != null && mConnected.get()) {
                    mHealthHandler.postDelayed(this, 2000);
                }
            }
        }, 2000);
    }

    private float calculateDroppedPercent() {
        long total = mFramesSent.get() + mDroppedFrames.get();
        if (total == 0) return 0f;
        return (mDroppedFrames.get() * 100.0f) / total;
    }

    // ── Reconnection ────────────────────────────────────────────────────────

    private void scheduleReconnect() {
        if (mReconnecting.getAndSet(true)) return;
        if (mReconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            notifyError("Max reconnection attempts reached");
            mReconnecting.set(false);
            return;
        }

        mReconnectAttempt++;
        long delay = BASE_RECONNECT_DELAY_MS * (1L << (mReconnectAttempt - 1));
        delay = Math.min(delay, 30_000); // Cap at 30 seconds

        Log.i(TAG, "Reconnecting in " + delay + "ms (attempt " + mReconnectAttempt + ")");

        if (mConnectionListener != null) {
            mConnectionListener.onReconnecting(mReconnectAttempt);
        }

        mStreamHandler.postDelayed(() -> {
            mReconnecting.set(false);
            closeSocket();
            doConnect();
        }, delay);
    }

    private void handleConnectionError(String message) {
        Log.w(TAG, "Connection error: " + message);
        mConnected.set(false);
        notifyDisconnected(message);
        scheduleReconnect();
    }

    private void closeSocket() {
        try {
            if (mOutputStream != null) mOutputStream.close();
        } catch (Exception ignored) {}
        try {
            if (mSocket != null) mSocket.close();
        } catch (Exception ignored) {}
        mSocket = null;
        mOutputStream = null;
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    private void parseServerAddress(String url) {
        String[] parts = parseRtmpUrl(url);
        if (parts != null) {
            mServerHost = parts[0];
            mServerPort = parts[1] != null ? Integer.parseInt(parts[1]) : DEFAULT_PORT;
        } else {
            mServerHost = url;
            mServerPort = DEFAULT_PORT;
        }
    }

    /**
     * Parse RTMP URL into [host, port].
     */
    private static String[] parseRtmpUrl(String url) {
        try {
            String cleanUrl = url.replace("rtmps://", "https://")
                                 .replace("rtmp://", "http://")
                                 .replace("http://", "")
                                 .replace("https://", "");

            int slashIdx = cleanUrl.indexOf('/');
            String hostPort = slashIdx >= 0 ? cleanUrl.substring(0, slashIdx) : cleanUrl;

            int colonIdx = hostPort.lastIndexOf(':');
            if (colonIdx >= 0) {
                return new String[]{
                        hostPort.substring(0, colonIdx),
                        hostPort.substring(colonIdx + 1)
                };
            }
            return new String[]{hostPort, String.valueOf(DEFAULT_PORT)};
        } catch (Exception e) {
            return null;
        }
    }

    // ── Listener notifications ──────────────────────────────────────────────

    private void notifyConnected() {
        if (mConnectionListener != null) {
            mConnectionListener.onConnected();
        }
    }

    private void notifyDisconnected(String reason) {
        if (mConnectionListener != null) {
            mConnectionListener.onDisconnected(reason);
        }
    }

    private void notifyError(String message) {
        if (mConnectionListener != null) {
            mConnectionListener.onError(message);
        }
    }
}

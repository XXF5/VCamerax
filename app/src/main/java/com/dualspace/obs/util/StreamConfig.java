package com.dualspace.obs.util;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Stream configuration model.
 * Encapsulates all settings needed for a streaming session: platform,
 * server URL, stream key, video/audio parameters, and encoder selection.
 * Supports serialization to/from Bundle and uses the Builder pattern
 * for easy construction.
 */
public class StreamConfig implements Parcelable {

    private static final String TAG = "StreamConfig";

    // Bundle keys
    private static final String KEY_PLATFORM = "platform";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_STREAM_KEY = "stream_key";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_BITRATE = "bitrate";
    private static final String KEY_FPS = "fps";
    private static final String KEY_ENCODER = "encoder";
    private static final String KEY_AUDIO_BITRATE = "audio_bitrate";
    private static final String KEY_AUDIO_SAMPLE_RATE = "audio_sample_rate";
    private static final String KEY_CUSTOM_SERVER = "custom_server";
    private static final String KEY_CUSTOM_KEY = "custom_key";

    // ──────────────── Platform Enum ────────────────

    /**
     * Supported streaming platforms.
     */
    public enum Platform {
        YOUTUBE("rtmp://a.rtmp.youtube.com/live2"),
        TWITCH("rtmp://live-iad.twitch.tv/app"),
        FACEBOOK("rtmps://live-api-s.facebook.com:443/rtmp/"),
        KICK("rtmp://fa723fc0a4f8.us-east-1.egress.switchboard.gg/app"),
        CUSTOM("");

        private final String defaultServerUrl;

        Platform(String defaultServerUrl) {
            this.defaultServerUrl = defaultServerUrl;
        }

        public String getDefaultServerUrl() {
            return defaultServerUrl;
        }

        public static Platform fromString(String name) {
            if (name == null) return CUSTOM;
            try {
                return Platform.valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                return CUSTOM;
            }
        }
    }

    /**
     * Supported hardware/software encoder types.
     */
    public enum Encoder {
        HARDWARE_H264("hardware_h264"),
        HARDWARE_HEVC("hardware_hevc"),
        SOFTWARE_X264("software_x264"),
        AUTO("auto");

        private final String value;

        Encoder(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static Encoder fromString(String value) {
            if (value == null) return AUTO;
            for (Encoder enc : values()) {
                if (enc.value.equalsIgnoreCase(value)) return enc;
            }
            return AUTO;
        }
    }

    // ──────────────── Fields ────────────────

    private Platform platform;
    private String serverUrl;
    private String streamKey;
    private int width;
    private int height;
    private int bitrate;       // video bitrate in kbps
    private int fps;
    private Encoder encoder;
    private int audioBitrate;  // audio bitrate in kbps
    private int audioSampleRate;

    // ──────────────── Parcelable ────────────────

    public static final Creator<StreamConfig> CREATOR = new Creator<StreamConfig>() {
        @Override
        public StreamConfig createFromParcel(Parcel in) {
            return new StreamConfig(in);
        }

        @Override
        public StreamConfig[] newArray(int size) {
            return new StreamConfig[size];
        }
    };

    protected StreamConfig(Parcel in) {
        platform = Platform.fromString(in.readString());
        serverUrl = in.readString();
        streamKey = in.readString();
        width = in.readInt();
        height = in.readInt();
        bitrate = in.readInt();
        fps = in.readInt();
        encoder = Encoder.fromString(in.readString());
        audioBitrate = in.readInt();
        audioSampleRate = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(platform.name());
        dest.writeString(serverUrl);
        dest.writeString(streamKey);
        dest.writeInt(width);
        dest.writeInt(height);
        dest.writeInt(bitrate);
        dest.writeInt(fps);
        dest.writeString(encoder.getValue());
        dest.writeInt(audioBitrate);
        dest.writeInt(audioSampleRate);
    }

    // ──────────────── Constructor ────────────────

    protected StreamConfig(Builder builder) {
        this.platform = builder.platform;
        this.serverUrl = builder.serverUrl;
        this.streamKey = builder.streamKey;
        this.width = builder.width;
        this.height = builder.height;
        this.bitrate = builder.bitrate;
        this.fps = builder.fps;
        this.encoder = builder.encoder;
        this.audioBitrate = builder.audioBitrate;
        this.audioSampleRate = builder.audioSampleRate;
    }

    // ──────────────── Platform URL Helper ────────────────

    /**
     * Get the full RTMP URL for the current platform.
     * For CUSTOM platforms, returns the configured serverUrl.
     *
     * @return full server URL, or empty string if not set
     */
    public String getPlatformUrl() {
        if (platform == Platform.CUSTOM) {
            return serverUrl != null ? serverUrl : "";
        }
        return platform.getDefaultServerUrl();
    }

    /**
     * Get the full RTMP destination URL (server + stream key).
     *
     * @return full RTMP URL, or null if incomplete
     */
    @Nullable
    public String getFullRtmpUrl() {
        String base = getPlatformUrl();
        if (base == null || base.isEmpty() || streamKey == null || streamKey.isEmpty()) {
            return null;
        }
        // Strip trailing slash from base
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + streamKey;
    }

    // ──────────────── Bundle Serialization ────────────────

    /**
     * Serialize this config to a Bundle.
     *
     * @return Bundle with all config fields
     */
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString(KEY_PLATFORM, platform.name());
        bundle.putString(KEY_SERVER_URL, serverUrl);
        bundle.putString(KEY_STREAM_KEY, streamKey);
        bundle.putInt(KEY_WIDTH, width);
        bundle.putInt(KEY_HEIGHT, height);
        bundle.putInt(KEY_BITRATE, bitrate);
        bundle.putInt(KEY_FPS, fps);
        bundle.putString(KEY_ENCODER, encoder.getValue());
        bundle.putInt(KEY_AUDIO_BITRATE, audioBitrate);
        bundle.putInt(KEY_AUDIO_SAMPLE_RATE, audioSampleRate);
        return bundle;
    }

    /**
     * Deserialize a StreamConfig from a Bundle.
     *
     * @param bundle source bundle
     * @return StreamConfig, or null if bundle is null
     */
    @Nullable
    public static StreamConfig fromBundle(@Nullable Bundle bundle) {
        if (bundle == null) return null;

        return new Builder()
                .platform(Platform.fromString(bundle.getString(KEY_PLATFORM, "CUSTOM")))
                .serverUrl(bundle.getString(KEY_SERVER_URL, ""))
                .streamKey(bundle.getString(KEY_STREAM_KEY, ""))
                .resolution(bundle.getInt(KEY_WIDTH, 1280), bundle.getInt(KEY_HEIGHT, 720))
                .bitrate(bundle.getInt(KEY_BITRATE, 4500))
                .fps(bundle.getInt(KEY_FPS, 30))
                .encoder(Encoder.fromString(bundle.getString(KEY_ENCODER, "auto")))
                .audioBitrate(bundle.getInt(KEY_AUDIO_BITRATE, 128))
                .audioSampleRate(bundle.getInt(KEY_AUDIO_SAMPLE_RATE, 44100))
                .build();
    }

    // ──────────────── Validation ────────────────

    /**
     * Validate that this config has the minimum required fields to start streaming.
     *
     * @return true if the config is valid for streaming
     */
    public boolean isValid() {
        // Must have a server URL (either from platform or custom)
        String url = getPlatformUrl();
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "isValid: server URL is empty");
            return false;
        }

        // Must have a stream key
        if (streamKey == null || streamKey.isEmpty()) {
            Log.w(TAG, "isValid: stream key is empty");
            return false;
        }

        // Resolution must be reasonable
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "isValid: invalid resolution " + width + "x" + height);
            return false;
        }

        // Bitrate must be positive
        if (bitrate <= 0) {
            Log.w(TAG, "isValid: invalid bitrate " + bitrate);
            return false;
        }

        // FPS must be positive
        if (fps <= 0) {
            Log.w(TAG, "isValid: invalid fps " + fps);
            return false;
        }

        return true;
    }

    // ──────────────── Getters ────────────────

    public Platform getPlatform() {
        return platform;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getStreamKey() {
        return streamKey;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getBitrate() {
        return bitrate;
    }

    public int getFps() {
        return fps;
    }

    public Encoder getEncoder() {
        return encoder;
    }

    public int getAudioBitrate() {
        return audioBitrate;
    }

    public int getAudioSampleRate() {
        return audioSampleRate;
    }

    // ──────────────── toString ────────────────

    @NonNull
    @Override
    public String toString() {
        return "StreamConfig{" +
                "platform=" + platform +
                ", serverUrl='" + serverUrl + '\'' +
                ", streamKey='" + (streamKey != null ? "***" : "null") + '\'' +
                ", resolution=" + width + "x" + height +
                ", bitrate=" + bitrate + "kbps" +
                ", fps=" + fps +
                ", encoder=" + encoder +
                ", audioBitrate=" + audioBitrate + "kbps" +
                ", audioSampleRate=" + audioSampleRate +
                '}';
    }

    // ──────────────── equals / hashCode ────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StreamConfig that = (StreamConfig) o;
        return width == that.width
                && height == that.height
                && bitrate == that.bitrate
                && fps == that.fps
                && audioBitrate == that.audioBitrate
                && audioSampleRate == that.audioSampleRate
                && platform == that.platform
                && (serverUrl != null ? serverUrl.equals(that.serverUrl) : that.serverUrl == null)
                && (streamKey != null ? streamKey.equals(that.streamKey) : that.streamKey == null)
                && encoder == that.encoder;
    }

    @Override
    public int hashCode() {
        int result = platform != null ? platform.hashCode() : 0;
        result = 31 * result + (serverUrl != null ? serverUrl.hashCode() : 0);
        result = 31 * result + (streamKey != null ? streamKey.hashCode() : 0);
        result = 31 * result + width;
        result = 31 * result + height;
        result = 31 * result + bitrate;
        result = 31 * result + fps;
        result = 31 * result + (encoder != null ? encoder.hashCode() : 0);
        result = 31 * result + audioBitrate;
        result = 31 * result + audioSampleRate;
        return result;
    }

    // ──────────────── Builder ────────────────

    /**
     * Builder for constructing StreamConfig instances.
     */
    public static class Builder {
        private Platform platform = Platform.YOUTUBE;
        private String serverUrl = "";
        private String streamKey = "";
        private int width = 1280;
        private int height = 720;
        private int bitrate = 4500;
        private int fps = 30;
        private Encoder encoder = Encoder.AUTO;
        private int audioBitrate = 128;
        private int audioSampleRate = 44100;

        public Builder() {}

        public Builder platform(Platform platform) {
            this.platform = platform;
            return this;
        }

        public Builder serverUrl(String serverUrl) {
            this.serverUrl = serverUrl != null ? serverUrl : "";
            return this;
        }

        public Builder streamKey(String streamKey) {
            this.streamKey = streamKey != null ? streamKey : "";
            return this;
        }

        public Builder resolution(int width, int height) {
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            return this;
        }

        public Builder bitrate(int bitrate) {
            this.bitrate = Math.max(100, bitrate);
            return this;
        }

        public Builder fps(int fps) {
            this.fps = Math.max(1, Math.min(60, fps));
            return this;
        }

        public Builder encoder(Encoder encoder) {
            this.encoder = encoder != null ? encoder : Encoder.AUTO;
            return this;
        }

        public Builder audioBitrate(int audioBitrate) {
            this.audioBitrate = Math.max(32, Math.min(320, audioBitrate));
            return this;
        }

        public Builder audioSampleRate(int audioSampleRate) {
            this.audioSampleRate = audioSampleRate;
            return this;
        }

        /**
         * Set video resolution from a preset string like "1080p", "720p", "480p".
         *
         * @param preset resolution preset
         * @return this builder
         */
        public Builder resolutionPreset(String preset) {
            if (preset == null) return this;
            switch (preset.toLowerCase()) {
                case "4k":
                case "2160p":
                    resolution(3840, 2160);
                    break;
                case "1440p":
                    resolution(2560, 1440);
                    break;
                case "1080p":
                    resolution(1920, 1080);
                    break;
                case "720p":
                    resolution(1280, 720);
                    break;
                case "480p":
                    resolution(854, 480);
                    break;
                case "360p":
                    resolution(640, 360);
                    break;
                case "240p":
                    resolution(426, 240);
                    break;
                default:
                    // Try parsing "WxH" format
                    String[] parts = preset.toLowerCase().split("[x@]");
                    if (parts.length == 2) {
                        try {
                            resolution(Integer.parseInt(parts[0].trim()),
                                    Integer.parseInt(parts[1].trim()));
                        } catch (NumberFormatException ignored) {}
                    }
                    break;
            }
            return this;
        }

        /**
         * Set bitrate from a quality preset.
         *
         * @param quality "low", "medium", "high", "ultra"
         * @return this builder
         */
        public Builder qualityPreset(String quality) {
            if (quality == null) return this;
            switch (quality.toLowerCase()) {
                case "low":
                    bitrate(1500);
                    fps(24);
                    break;
                case "medium":
                    bitrate(3000);
                    fps(30);
                    break;
                case "high":
                    bitrate(4500);
                    fps(30);
                    break;
                case "ultra":
                    bitrate(6000);
                    fps(60);
                    break;
                default:
                    break;
            }
            return this;
        }

        /**
         * Build the StreamConfig with the current settings.
         *
         * @return immutable StreamConfig
         */
        public StreamConfig build() {
            return new StreamConfig(this);
        }
    }
}

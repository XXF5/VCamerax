package com.dualspace.obs.cloner;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Configuration model for cloned apps.
 * Controls storage, network, camera, location, permissions, and appearance.
 */
public class CloneConfig implements Parcelable {

    // ── Enums ──────────────────────────────────────────────────────────────

    /**
     * Storage isolation level for the cloned app.
     */
    public enum StorageMode {
        /** Clone gets its own private data directory, completely isolated from the source. */
        PRIVATE,
        /** Clone shares data with the source app (same UID sandbox). */
        SHARED,
        /** Clone uses an encrypted, sandboxed container. */
        ISOLATED
    }

    /**
     * Network access level for the cloned app.
     */
    public enum NetworkMode {
        /** Full network access (default). */
        FULL,
        /** Only Wi-Fi or whitelisted domains. */
        RESTRICTED,
        /** No network access at all. */
        NONE
    }

    /**
     * Camera access mode for the cloned app.
     */
    public enum CameraMode {
        /** Pass through the real device camera. */
        REAL,
        /** Supply a virtual / fake camera feed. */
        VIRTUAL,
        /** Block camera access entirely. */
        NONE
    }

    // ── Fields ─────────────────────────────────────────────────────────────

    /** Display name for the clone (e.g. "WhatsApp Work"). */
    private String cloneName;

    /** Optional path to a custom icon PNG. {@code null} means use the source icon. */
    @Nullable
    private String customIconPath;

    /** Accent / tint color for the clone launcher icon badge (ARGB). */
    private int customColor;

    /** Runtime permission overrides. Key = permission name, Value = granted? */
    private final Map<String, Boolean> permissions;

    /** How the clone's data directory is managed. */
    @NonNull
    private StorageMode storageMode;

    /** How the clone can access the network. */
    @NonNull
    private NetworkMode networkMode;

    /** How the clone accesses the camera. */
    @NonNull
    private CameraMode cameraMode;

    /** Whether to spoof the device location for this clone. */
    private boolean locationSpoofing;

    /** Fake latitude used when location spoofing is enabled. */
    private double fakeLat;

    /** Fake longitude used when location spoofing is enabled. */
    private double fakeLng;

    // ── Constructors ───────────────────────────────────────────────────────

    public CloneConfig() {
        this.cloneName = "";
        this.customIconPath = null;
        this.customColor = 0xFF6200EE; // default purple accent
        this.permissions = new HashMap<>();
        this.storageMode = StorageMode.PRIVATE;
        this.networkMode = NetworkMode.FULL;
        this.cameraMode = CameraMode.REAL;
        this.locationSpoofing = false;
        this.fakeLat = 0.0;
        this.fakeLng = 0.0;
    }

    protected CloneConfig(Parcel in) {
        this.cloneName = in.readString();
        this.customIconPath = in.readString();
        this.customColor = in.readInt();
        this.storageMode = StorageMode.valueOf(in.readString());
        this.networkMode = NetworkMode.valueOf(in.readString());
        this.cameraMode = CameraMode.valueOf(in.readString());
        this.locationSpoofing = in.readByte() != 0;
        this.fakeLat = in.readDouble();
        this.fakeLng = in.readDouble();

        // Read permissions map
        int permCount = in.readInt();
        this.permissions = new HashMap<>(permCount);
        for (int i = 0; i < permCount; i++) {
            String key = in.readString();
            boolean value = in.readByte() != 0;
            permissions.put(key, value);
        }
    }

    // ── Parcelable ─────────────────────────────────────────────────────────

    public static final Creator<CloneConfig> CREATOR = new Creator<CloneConfig>() {
        @Override
        public CloneConfig createFromParcel(Parcel in) {
            return new CloneConfig(in);
        }

        @Override
        public CloneConfig[] newArray(int size) {
            return new CloneConfig[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(cloneName);
        dest.writeString(customIconPath);
        dest.writeInt(customColor);
        dest.writeString(storageMode.name());
        dest.writeString(networkMode.name());
        dest.writeString(cameraMode.name());
        dest.writeByte((byte) (locationSpoofing ? 1 : 0));
        dest.writeDouble(fakeLat);
        dest.writeDouble(fakeLng);

        dest.writeInt(permissions.size());
        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
            dest.writeString(entry.getKey());
            dest.writeByte((byte) (entry.getValue() ? 1 : 0));
        }
    }

    // ── Bundle serialization ───────────────────────────────────────────────

    private static final String KEY_CLONE_NAME      = "clone_name";
    private static final String KEY_CUSTOM_ICON     = "custom_icon_path";
    private static final String KEY_CUSTOM_COLOR    = "custom_color";
    private static final String KEY_STORAGE_MODE    = "storage_mode";
    private static final String KEY_NETWORK_MODE    = "network_mode";
    private static final String KEY_CAMERA_MODE     = "camera_mode";
    private static final String KEY_LOC_SPOOF       = "location_spoofing";
    private static final String KEY_FAKE_LAT        = "fake_lat";
    private static final String KEY_FAKE_LNG        = "fake_lng";
    private static final String KEY_PERMISSIONS     = "permissions";
    private static final String KEY_PERM_PREFIX     = "perm_";

    /**
     * Serialize this configuration into a {@link Bundle}.
     *
     * @return a fully populated Bundle
     */
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString(KEY_CLONE_NAME, cloneName);
        bundle.putString(KEY_CUSTOM_ICON, customIconPath);
        bundle.putInt(KEY_CUSTOM_COLOR, customColor);
        bundle.putString(KEY_STORAGE_MODE, storageMode.name());
        bundle.putString(KEY_NETWORK_MODE, networkMode.name());
        bundle.putString(KEY_CAMERA_MODE, cameraMode.name());
        bundle.putBoolean(KEY_LOC_SPOOF, locationSpoofing);
        bundle.putDouble(KEY_FAKE_LAT, fakeLat);
        bundle.putDouble(KEY_FAKE_LNG, fakeLng);

        // Flatten the permissions map into individual keys
        Bundle permBundle = new Bundle();
        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
            permBundle.putBoolean(KEY_PERM_PREFIX + entry.getKey(), entry.getValue());
        }
        bundle.putBundle(KEY_PERMISSIONS, permBundle);
        return bundle;
    }

    /**
     * Deserialize a {@link CloneConfig} from a {@link Bundle}.
     *
     * @param bundle the source Bundle (may be {@code null})
     * @return a new CloneConfig, or {@code null} if the bundle is null
     */
    @Nullable
    public static CloneConfig fromBundle(@Nullable Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        Builder builder = new Builder()
                .cloneName(bundle.getString(KEY_CLONE_NAME, ""))
                .customIconPath(bundle.getString(KEY_CUSTOM_ICON))
                .customColor(bundle.getInt(KEY_CUSTOM_COLOR, 0xFF6200EE))
                .storageMode(StorageMode.valueOf(bundle.getString(KEY_STORAGE_MODE, "PRIVATE")))
                .networkMode(NetworkMode.valueOf(bundle.getString(KEY_NETWORK_MODE, "FULL")))
                .cameraMode(CameraMode.valueOf(bundle.getString(KEY_CAMERA_MODE, "REAL")))
                .locationSpoofing(bundle.getBoolean(KEY_LOC_SPOOF, false))
                .fakeLat(bundle.getDouble(KEY_FAKE_LAT, 0.0))
                .fakeLng(bundle.getDouble(KEY_FAKE_LNG, 0.0));

        // Restore permissions
        Bundle permBundle = bundle.getBundle(KEY_PERMISSIONS);
        if (permBundle != null) {
            for (String key : permBundle.keySet()) {
                if (key.startsWith(KEY_PERM_PREFIX)) {
                    String permName = key.substring(KEY_PERM_PREFIX.length());
                    builder.permission(permName, permBundle.getBoolean(key, false));
                }
            }
        }

        return builder.build();
    }

    // ── JSON serialization ─────────────────────────────────────────────────

    /**
     * Serialize to a JSON string (for Room storage).
     */
    @NonNull
    public String toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("cloneName", cloneName);
        json.put("customIconPath", customIconPath);
        json.put("customColor", customColor);
        json.put("storageMode", storageMode.name());
        json.put("networkMode", networkMode.name());
        json.put("cameraMode", cameraMode.name());
        json.put("locationSpoofing", locationSpoofing);
        json.put("fakeLat", fakeLat);
        json.put("fakeLng", fakeLng);

        JSONObject permsJson = new JSONObject();
        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
            permsJson.put(entry.getKey(), entry.getValue());
        }
        json.put("permissions", permsJson);
        return json.toString();
    }

    /**
     * Deserialize from a JSON string (for Room storage).
     */
    @Nullable
    public static CloneConfig fromJson(@Nullable String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(jsonStr);
            Builder builder = new Builder()
                    .cloneName(json.optString("cloneName", ""))
                    .customIconPath(json.optString("customIconPath", null))
                    .customColor(json.optInt("customColor", 0xFF6200EE))
                    .storageMode(StorageMode.valueOf(json.optString("storageMode", "PRIVATE")))
                    .networkMode(NetworkMode.valueOf(json.optString("networkMode", "FULL")))
                    .cameraMode(CameraMode.valueOf(json.optString("cameraMode", "REAL")))
                    .locationSpoofing(json.optBoolean("locationSpoofing", false))
                    .fakeLat(json.optDouble("fakeLat", 0.0))
                    .fakeLng(json.optDouble("fakeLng", 0.0));

            JSONObject permsJson = json.optJSONObject("permissions");
            if (permsJson != null) {
                Iterator<String> keys = permsJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    builder.permission(key, permsJson.getBoolean(key));
                }
            }
            return builder.build();
        } catch (JSONException e) {
            return null;
        }
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    @NonNull
    public String getCloneName() {
        return cloneName;
    }

    public void setCloneName(@NonNull String cloneName) {
        this.cloneName = cloneName;
    }

    @Nullable
    public String getCustomIconPath() {
        return customIconPath;
    }

    public void setCustomIconPath(@Nullable String customIconPath) {
        this.customIconPath = customIconPath;
    }

    public int getCustomColor() {
        return customColor;
    }

    public void setCustomColor(int customColor) {
        this.customColor = customColor;
    }

    @NonNull
    public Map<String, Boolean> getPermissions() {
        return permissions;
    }

    public void setPermission(String name, boolean granted) {
        permissions.put(name, granted);
    }

    public boolean hasPermission(String name) {
        return permissions.containsKey(name) && permissions.get(name);
    }

    @NonNull
    public StorageMode getStorageMode() {
        return storageMode;
    }

    public void setStorageMode(@NonNull StorageMode storageMode) {
        this.storageMode = storageMode;
    }

    @NonNull
    public NetworkMode getNetworkMode() {
        return networkMode;
    }

    public void setNetworkMode(@NonNull NetworkMode networkMode) {
        this.networkMode = networkMode;
    }

    @NonNull
    public CameraMode getCameraMode() {
        return cameraMode;
    }

    public void setCameraMode(@NonNull CameraMode cameraMode) {
        this.cameraMode = cameraMode;
    }

    public boolean isLocationSpoofing() {
        return locationSpoofing;
    }

    public void setLocationSpoofing(boolean locationSpoofing) {
        this.locationSpoofing = locationSpoofing;
    }

    public double getFakeLat() {
        return fakeLat;
    }

    public void setFakeLat(double fakeLat) {
        this.fakeLat = fakeLat;
    }

    public double getFakeLng() {
        return fakeLng;
    }

    public void setFakeLng(double fakeLng) {
        this.fakeLng = fakeLng;
    }

    // ── Builder ────────────────────────────────────────────────────────────

    /**
     * Fluent builder for constructing {@link CloneConfig} instances.
     */
    public static class Builder {
        private final CloneConfig config;

        public Builder() {
            this.config = new CloneConfig();
        }

        public Builder cloneName(@NonNull String cloneName) {
            config.cloneName = cloneName;
            return this;
        }

        public Builder customIconPath(@Nullable String path) {
            config.customIconPath = path;
            return this;
        }

        public Builder customColor(int color) {
            config.customColor = color;
            return this;
        }

        public Builder permission(String name, boolean granted) {
            config.permissions.put(name, granted);
            return this;
        }

        public Builder storageMode(@NonNull StorageMode mode) {
            config.storageMode = mode;
            return this;
        }

        public Builder networkMode(@NonNull NetworkMode mode) {
            config.networkMode = mode;
            return this;
        }

        public Builder cameraMode(@NonNull CameraMode mode) {
            config.cameraMode = mode;
            return this;
        }

        public Builder locationSpoofing(boolean spoof) {
            config.locationSpoofing = spoof;
            return this;
        }

        public Builder fakeLat(double lat) {
            config.fakeLat = lat;
            return this;
        }

        public Builder fakeLng(double lng) {
            config.fakeLng = lng;
            return this;
        }

        /**
         * Build an immutable snapshot of the configuration.
         */
        public CloneConfig build() {
            return config;
        }
    }
}

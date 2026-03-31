package com.dualspace.obs.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Battery state change receiver.
 * Monitors battery level, charging state, and takes action when the
 * battery is critically low or power is connected/disconnected.
 * Adjusts stream quality based on battery state to prolong usage.
 */
public class BatteryReceiver extends BroadcastReceiver {

    private static final String TAG = "BatteryReceiver";

    // Battery thresholds
    private static final int CRITICAL_BATTERY_LEVEL = 5;
    private static final int LOW_BATTERY_LEVEL = 15;
    private static final int WARNING_BATTERY_LEVEL = 30;

    // Quality presets
    private static final int QUALITY_HIGH = 3;
    private static final int QUALITY_MEDIUM = 2;
    private static final int QUALITY_LOW = 1;
    private static final int QUALITY_MINIMAL = 0;

    /** Callback for battery events. */
    public interface OnBatteryChangeListener {
        void onBatteryLow(int level);
        void onBatteryCritical(int level);
        void onPowerConnected();
        void onPowerDisconnected();
        void onQualityChanged(int qualityLevel, int bitrateKbps, int fps);
        void onStopStreamingRequested(String reason);
    }

    private OnBatteryChangeListener listener;
    private final AtomicInteger currentQualityLevel = new AtomicInteger(QUALITY_HIGH);

    public void setOnBatteryChangeListener(OnBatteryChangeListener listener) {
        this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();

        if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            handleBatteryChanged(context, intent);
        } else if (Intent.ACTION_BATTERY_LOW.equals(action)) {
            int level = getBatteryLevel(context);
            Log.w(TAG, "System broadcast: battery low (" + level + "%)");
            handleLowBattery(context, level);
        } else if (Intent.ACTION_BATTERY_OKAY.equals(action)) {
            int level = getBatteryLevel(context);
            Log.i(TAG, "System broadcast: battery okay (" + level + "%)");
            restoreQuality(context);
        } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            Log.i(TAG, "Power connected");
            handlePowerConnected(context);
        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            Log.i(TAG, "Power disconnected");
            handlePowerDisconnected(context);
        }
    }

    // ──────────────── Battery State Handling ────────────────

    /**
     * Handle ACTION_BATTERY_CHANGED: examine level and charging state.
     *
     * @param context application context
     * @param intent  battery changed intent with extras
     */
    private void handleBatteryChanged(Context context, Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        int percent = (int) ((level / (float) scale) * 100);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        Log.d(TAG, "Battery: " + percent + "%, charging=" + isCharging
                + ", plugged=" + pluggedType(plugged));

        if (!isCharging) {
            // Adjust quality based on battery level while on battery
            adjustQuality(context, percent);
        }
    }

    /**
     * Handle critically low battery.
     * Warns the user and requests streaming to stop.
     *
     * @param context application context
     * @param level   battery level percentage
     */
    public void handleLowBattery(Context context, int level) {
        Log.w(TAG, "Low battery: " + level + "%");

        if (listener != null) {
            listener.onBatteryLow(level);
        }

        if (level <= CRITICAL_BATTERY_LEVEL) {
            Log.e(TAG, "CRITICAL battery level: " + level + "% - requesting stream stop");
            handleCriticalBattery(context, level);
        }
    }

    /**
     * Handle critical battery level.
     * Stops streaming and saves state for recovery.
     *
     * @param context application context
     * @param level   battery level percentage
     */
    private void handleCriticalBattery(Context context, int level) {
        if (listener != null) {
            listener.onBatteryCritical(level);
            listener.onStopStreamingRequested(
                    "Battery critically low (" + level + "%). Streaming stopped to preserve power.");
        }

        // Save pending stream state so user can resume after charging
        SharedPreferences prefs = context.getSharedPreferences("dualspace_obs_prefs",
                Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean("pref_battery_stop_stream", true)
                .putLong("pref_battery_stop_time", System.currentTimeMillis())
                .putInt("pref_battery_stop_level", level)
                .apply();

        currentQualityLevel.set(QUALITY_MINIMAL);
    }

    /**
     * Handle power connected event.
     * Restores high-quality streaming.
     *
     * @param context application context
     */
    public void handlePowerConnected(Context context) {
        Log.i(TAG, "Power connected - restoring high quality");

        currentQualityLevel.set(QUALITY_HIGH);
        QualityConfig config = getConfigForQuality(QUALITY_HIGH);

        if (listener != null) {
            listener.onPowerConnected();
            listener.onQualityChanged(QUALITY_HIGH, config.bitrateKbps, config.fps);
        }

        // Clear any battery-stop flags
        SharedPreferences prefs = context.getSharedPreferences("dualspace_obs_prefs",
                Context.MODE_PRIVATE);
        prefs.edit().remove("pref_battery_stop_stream").apply();
    }

    /**
     * Handle power disconnected event.
     * Checks current battery level and adjusts quality.
     *
     * @param context application context
     */
    public void handlePowerDisconnected(Context context) {
        Log.i(TAG, "Power disconnected");

        if (listener != null) {
            listener.onPowerDisconnected();
        }

        // Check battery level and adjust quality
        int level = getBatteryLevel(context);
        if (level <= WARNING_BATTERY_LEVEL) {
            adjustQuality(context, level);
        }
    }

    // ──────────────── Quality Adjustment ────────────────

    /**
     * Adjust stream quality based on current battery level.
     *
     * @param context       application context
     * @param batteryLevel  battery percentage (0-100)
     */
    public void adjustQuality(Context context, int batteryLevel) {
        int newQuality;

        if (batteryLevel <= CRITICAL_BATTERY_LEVEL) {
            newQuality = QUALITY_MINIMAL;
        } else if (batteryLevel <= LOW_BATTERY_LEVEL) {
            newQuality = QUALITY_LOW;
        } else if (batteryLevel <= WARNING_BATTERY_LEVEL) {
            newQuality = QUALITY_MEDIUM;
        } else {
            newQuality = QUALITY_HIGH;
        }

        int previous = currentQualityLevel.getAndSet(newQuality);
        if (previous != newQuality) {
            QualityConfig config = getConfigForQuality(newQuality);
            Log.i(TAG, "Quality adjusted: " + qualityName(previous) + " -> "
                    + qualityName(newQuality) + " (bitrate=" + config.bitrateKbps
                    + "kbps, fps=" + config.fps + ")");

            if (listener != null) {
                listener.onQualityChanged(newQuality, config.bitrateKbps, config.fps);
            }

            // Persist the quality setting
            SharedPreferences prefs = context.getSharedPreferences("dualspace_obs_prefs",
                    Context.MODE_PRIVATE);
            prefs.edit().putInt("pref_battery_quality_level", newQuality).apply();
        }
    }

    /**
     * Restore quality to the saved preference or high when battery is okay.
     *
     * @param context application context
     */
    private void restoreQuality(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("dualspace_obs_prefs",
                Context.MODE_PRIVATE);
        int savedQuality = prefs.getInt("pref_battery_quality_level", QUALITY_HIGH);

        int newQuality = Math.min(savedQuality, QUALITY_HIGH);
        currentQualityLevel.set(newQuality);

        QualityConfig config = getConfigForQuality(newQuality);
        Log.i(TAG, "Quality restored: " + qualityName(newQuality)
                + " (bitrate=" + config.bitrateKbps + "kbps, fps=" + config.fps + ")");

        if (listener != null) {
            listener.onQualityChanged(newQuality, config.bitrateKbps, config.fps);
        }
    }

    // ──────────────── Quality Presets ────────────────

    private QualityConfig getConfigForQuality(int quality) {
        switch (quality) {
            case QUALITY_HIGH:
                return new QualityConfig(4500, 30);
            case QUALITY_MEDIUM:
                return new QualityConfig(2500, 24);
            case QUALITY_LOW:
                return new QualityConfig(1500, 20);
            case QUALITY_MINIMAL:
                return new QualityConfig(800, 15);
            default:
                return new QualityConfig(2500, 24);
        }
    }

    private static String qualityName(int quality) {
        switch (quality) {
            case QUALITY_HIGH: return "HIGH";
            case QUALITY_MEDIUM: return "MEDIUM";
            case QUALITY_LOW: return "LOW";
            case QUALITY_MINIMAL: return "MINIMAL";
            default: return "UNKNOWN";
        }
    }

    // ──────────────── Helpers ────────────────

    /**
     * Get current battery level (0-100).
     *
     * @param context application context
     * @return battery percentage
     */
    public static int getBatteryLevel(Context context) {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        // Fallback using sticky broadcast
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, filter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return (int) ((level / (float) scale) * 100);
        }
        return 100;
    }

    /**
     * Check if device is currently charging.
     *
     * @param context application context
     * @return true if charging
     */
    public static boolean isCharging(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, filter);
        if (batteryStatus == null) return false;
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private static String pluggedType(int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "AC";
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "Wireless";
            default: return "None";
        }
    }

    public int getCurrentQualityLevel() {
        return currentQualityLevel.get();
    }

    /** Simple holder for bitrate and fps pair. */
    private static class QualityConfig {
        final int bitrateKbps;
        final int fps;

        QualityConfig(int bitrateKbps, int fps) {
            this.bitrateKbps = bitrateKbps;
            this.fps = fps;
        }
    }
}

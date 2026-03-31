package com.dualspace.obs.util;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Device information utility.
 * Provides methods to query device capabilities, hardware specs,
 * screen info, battery state, and streaming suitability.
 */
public class DeviceUtils {

    private static final String TAG = "DeviceUtils";

    // Cached values
    private static Integer cpuCores = null;
    private static Long totalRam = null;

    // ──────────────── Device Identity ────────────────

    /**
     * Get the device model name.
     *
     * @return e.g. "Pixel 7 Pro"
     */
    public static String getDeviceModel() {
        return Build.MODEL;
    }

    /**
     * Get the device manufacturer.
     *
     * @return e.g. "Google"
     */
    public static String getManufacturer() {
        return Build.MANUFACTURER;
    }

    /**
     * Get the combined manufacturer + model string.
     *
     * @return e.g. "Google Pixel 7 Pro"
     */
    public static String getFullDeviceName() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    /**
     * Get the Android version string.
     *
     * @return e.g. "14" or "13"
     */
    public static String getAndroidVersion() {
        return Build.VERSION.RELEASE;
    }

    /**
     * Get the Android SDK level.
     *
     * @return e.g. 34
     */
    public static int getSdkLevel() {
        return Build.VERSION.SDK_INT;
    }

    /**
     * Get a human-readable API level name.
     *
     * @return e.g. "Upside Down Cake (API 34)"
     */
    public static String getApiName() {
        String name;
        switch (Build.VERSION.SDK_INT) {
            case 35: name = "Vanilla Ice Cream"; break;
            case 34: name = "Upside Down Cake"; break;
            case 33: name = "Tiramisu"; break;
            case 32: name = "S"; break;
            case 31: name = "S"; break;
            case 30: name = "R"; break;
            case 29: name = "Q"; break;
            case 28: name = "Pie"; break;
            case 27: name = "Oreo"; break;
            case 26: name = "Oreo"; break;
            case 25: name = "Nougat"; break;
            case 24: name = "Nougat"; break;
            case 23: name = "Marshmallow"; break;
            case 22: name = "Lollipop"; break;
            case 21: name = "Lollipop"; break;
            default: name = "Unknown"; break;
        }
        return name + " (API " + Build.VERSION.SDK_INT + ")";
    }

    // ──────────────── CPU ────────────────

    /**
     * Get the number of CPU cores.
     *
     * @return number of available processors
     */
    public static int getCpuCores() {
        if (cpuCores != null) return cpuCores;
        cpuCores = Runtime.getRuntime().availableProcessors();
        return cpuCores;
    }

    /**
     * Get the CPU ABI(s).
     *
     * @return primary ABI string
     */
    public static String getCpuAbi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return Build.SUPPORTED_ABIS[0];
        }
        return Build.CPU_ABI;
    }

    // ──────────────── RAM ────────────────

    /**
     * Get total device RAM in bytes.
     *
     * @param context application context
     * @return total RAM in bytes
     */
    public static long getTotalRAM(Context context) {
        if (totalRam != null) return totalRam;

        // Use ActivityManager on API 16+
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            totalRam = mi.totalMem;
            return totalRam;
        }

        // Fallback: read from /proc/meminfo
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"));
            String line = reader.readLine();
            reader.close();
            if (line != null) {
                Pattern pattern = Pattern.compile("(\\d+)");
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    totalRam = Long.parseLong(matcher.group(1)) * 1024; // KB -> bytes
                    return totalRam;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read /proc/meminfo", e);
        }

        totalRam = 0L;
        return totalRam;
    }

    /**
     * Get available RAM in bytes.
     *
     * @param context application context
     * @return available RAM in bytes
     */
    public static long getAvailableRAM(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.availMem;
        }
        return 0;
    }

    /**
     * Format bytes to a human-readable string.
     *
     * @param bytes value in bytes
     * @return e.g. "3.8 GB"
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %cB", bytes / Math.pow(1024, exp), unit);
    }

    // ──────────────── Storage ────────────────

    /**
     * Get storage information as a map.
     *
     * @return map with keys "total", "used", "free" (values in bytes)
     */
    public static Map<String, Long> getStorageInfo() {
        Map<String, Long> info = new HashMap<>();
        try {
            File path = Environment.getDataDirectory();
            StatFs stat = new StatFs(path.getPath());

            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availableBlocks = stat.getAvailableBlocksLong();

            long total = blockSize * totalBlocks;
            long free = blockSize * availableBlocks;
            long used = total - free;

            info.put("total", total);
            info.put("used", used);
            info.put("free", free);
        } catch (Exception e) {
            Log.w(TAG, "Failed to get storage info", e);
            info.put("total", 0L);
            info.put("used", 0L);
            info.put("free", 0L);
        }
        return info;
    }

    // ──────────────── Screen ────────────────

    /**
     * Get screen information as a map.
     *
     * @param context application context
     * @return map with keys "width", "height", "density", "densityDpi", "refreshRate"
     */
    public static Map<String, Object> getScreenInfo(Context context) {
        Map<String, Object> info = new HashMap<>();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(metrics);

            info.put("width", metrics.widthPixels);
            info.put("height", metrics.heightPixels);
            info.put("density", metrics.density);
            info.put("densityDpi", metrics.densityDpi);
            info.put("refreshRate", metrics.refreshRate);

            // Real metrics for devices with navigation bars / cutouts
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                DisplayMetrics realMetrics = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(realMetrics);
                info.put("realWidth", realMetrics.widthPixels);
                info.put("realHeight", realMetrics.heightPixels);
            }
        }
        return info;
    }

    /**
     * Check if the device is in landscape orientation.
     *
     * @param context application context
     * @return true if landscape
     */
    public static boolean isLandscape(Context context) {
        return context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    // ──────────────── Battery ────────────────

    /**
     * Get battery level (0-100).
     *
     * @param context application context
     * @return battery percentage
     */
    public static int getBatteryLevel(Context context) {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        return 100;
    }

    /**
     * Check if the device is charging.
     *
     * @param context application context
     * @return true if charging
     */
    public static boolean isCharging(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            return bm != null && bm.isCharging();
        }
        return false;
    }

    // ──────────────── Hardware Encoder ────────────────

    /**
     * Check if the device has a hardware H.264 (AVC) encoder.
     *
     * @return true if a hardware AVC encoder is available
     */
    public static boolean hasHardwareEncoder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
            MediaCodecInfo[] codecs = codecList.getCodecInfos();

            for (MediaCodecInfo codec : codecs) {
                if (codec.isEncoder()) {
                    String[] types = codec.getSupportedTypes();
                    for (String type : types) {
                        if (type.equalsIgnoreCase("video/avc")) {
                            // Check if it's a hardware encoder
                            if (codec.getName().contains("OMX.")
                                    || codec.getName().contains("c2.")) {
                                Log.i(TAG, "Hardware H.264 encoder found: " + codec.getName());
                                return true;
                            }
                        }
                    }
                }
            }
        }
        Log.w(TAG, "No hardware H.264 encoder found");
        return false;
    }

    /**
     * Check if the device has a hardware H.265 (HEVC) encoder.
     *
     * @return true if a hardware HEVC encoder is available
     */
    public static boolean hasHevcEncoder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
            MediaCodecInfo[] codecs = codecList.getCodecInfos();

            for (MediaCodecInfo codec : codecs) {
                if (codec.isEncoder()) {
                    String[] types = codec.getSupportedTypes();
                    for (String type : types) {
                        if (type.equalsIgnoreCase("video/hevc")) {
                            if (codec.getName().contains("OMX.")
                                    || codec.getName().contains("c2.")) {
                                Log.i(TAG, "Hardware HEVC encoder found: " + codec.getName());
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    // ──────────────── Streaming Suitability ────────────────

    /**
     * Check if the device is suitable for streaming.
     * Evaluates CPU cores, RAM, SDK level, and hardware encoder support.
     *
     * @param context application context
     * @return map with "suitable" (boolean) and "reasons" (list of strings)
     */
    public static Map<String, Object> isSuitableForStreaming(Context context) {
        Map<String, Object> result = new HashMap<>();
        java.util.List<String> reasons = new java.util.ArrayList<>();
        boolean suitable = true;

        // CPU: at least 4 cores recommended
        int cores = getCpuCores();
        if (cores < 4) {
            reasons.add("Low CPU core count: " + cores + " (recommended: 4+)");
            suitable = false;
        }

        // RAM: at least 2 GB recommended
        long totalRam = getTotalRAM(context);
        long twoGB = 2L * 1024 * 1024 * 1024;
        if (totalRam > 0 && totalRam < twoGB) {
            reasons.add("Low RAM: " + formatBytes(totalRam) + " (recommended: 2 GB+)");
            suitable = false;
        }

        // API: at least 21 (Lollipop) required
        if (Build.VERSION.SDK_INT < 21) {
            reasons.add("Android version too old: " + Build.VERSION.SDK_INT + " (minimum: 21)");
            suitable = false;
        }

        // Hardware encoder: strongly recommended
        if (!hasHardwareEncoder()) {
            reasons.add("No hardware H.264 encoder found (software encoding will be slow)");
            suitable = false;
        }

        // Free storage: at least 500 MB
        Map<String, Long> storage = getStorageInfo();
        long freeStorage = storage.get("free");
        long fiveHundredMB = 500L * 1024 * 1024;
        if (freeStorage < fiveHundredMB) {
            reasons.add("Low storage: " + formatBytes(freeStorage) + " free (recommended: 500 MB+)");
        }

        result.put("suitable", suitable);
        result.put("reasons", reasons);
        return result;
    }

    // ──────────────── Device Summary ────────────────

    /**
     * Get a complete device info summary string.
     *
     * @param context application context
     * @return formatted multi-line string with all device info
     */
    public static String getDeviceInfoSummary(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Device: ").append(getFullDeviceName()).append("\n");
        sb.append("Android: ").append(getAndroidVersion()).append(" (").append(getApiName()).append(")\n");
        sb.append("CPU: ").append(getCpuCores()).append(" cores, ").append(getCpuAbi()).append("\n");
        sb.append("RAM: ").append(formatBytes(getTotalRAM(context)));
        sb.append(" (").append(formatBytes(getAvailableRAM(context))).append(" available)\n");

        Map<String, Long> storage = getStorageInfo();
        sb.append("Storage: ").append(formatBytes(storage.get("total")));
        sb.append(" total, ").append(formatBytes(storage.get("free"))).append(" free\n");

        Map<String, Object> screen = getScreenInfo(context);
        sb.append("Screen: ").append(screen.get("width")).append("x").append(screen.get("height"));
        sb.append(" @").append(screen.get("densityDpi")).append("dpi");
        sb.append(" ").append(String.format("%.0f", (float) screen.get("refreshRate"))).append("Hz\n");

        sb.append("Battery: ").append(getBatteryLevel(context)).append("%");
        sb.append(isCharging(context) ? " (charging)" : " (on battery)\n");

        sb.append("HW Encoder: ").append(hasHardwareEncoder() ? "Yes" : "No");
        sb.append(" | HEVC: ").append(hasHevcEncoder() ? "Yes" : "No\n");

        return sb.toString();
    }
}

package com.dualspace.obs.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Permission utility helper.
 * Manages checking, requesting, and verifying all permissions required
 * by DualSpace OBS: camera, microphone, storage, overlay, notification, and
 * battery optimization exemption.
 */
public class PermissionHelper {

    // Runtime permissions
    public static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };

    // Additional permissions for API 33+
    private static final String[] POST_NOTIFICATION_PERMISSION = {
            Manifest.permission.POST_NOTIFICATIONS,
    };

    private static final String TAG = "PermissionHelper";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int OVERLAY_REQUEST_CODE = 1002;
    private static final int BATTERY_REQUEST_CODE = 1003;

    // ──────────────── Check All ────────────────

    /**
     * Check if all required runtime permissions are granted.
     *
     * @param context application or activity context
     * @return true if all permissions are granted
     */
    public static boolean hasAllPermissions(Context context) {
        List<String> denied = getDeniedPermissions(context);
        return denied.isEmpty();
    }

    /**
     * Get a list of currently denied runtime permissions.
     *
     * @param context application or activity context
     * @return list of permission strings that are not granted
     */
    public static List<String> getDeniedPermissions(Context context) {
        List<String> denied = new ArrayList<>();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                denied.add(permission);
            }
        }

        // API 33+: check notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (String permission : POST_NOTIFICATION_PERMISSION) {
                if (ContextCompat.checkSelfPermission(context, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    denied.add(permission);
                }
            }
        }

        return denied;
    }

    // ──────────────── Request Permissions ────────────────

    /**
     * Request all required runtime permissions from an activity.
     * Results are delivered in {@link Activity#onRequestPermissionsResult}.
     *
     * @param activity the calling activity
     */
    public static void requestPermissions(Activity activity) {
        List<String> denied = getDeniedPermissions(activity);
        if (!denied.isEmpty()) {
            ActivityCompat.requestPermissions(
                    activity,
                    denied.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE
            );
        }
    }

    /**
     * Request a specific subset of permissions.
     *
     * @param activity     the calling activity
     * @param permissions  array of permission strings
     * @param requestCode  custom request code
     */
    public static void requestPermissions(Activity activity, String[] permissions, int requestCode) {
        ActivityCompat.requestPermissions(activity, permissions, requestCode);
    }

    // ──────────────── Overlay Permission ────────────────

    /**
     * Check if the app can draw over other apps (system overlay permission).
     *
     * @param context application or activity context
     * @return true if overlay permission is granted
     */
    public static boolean canDrawOverlays(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true; // Before M, overlay permission was not required
    }

    /**
     * Request the system overlay permission.
     * Opens the system settings screen; result is delivered in
     * {@link Activity#onActivityResult} with {@link #OVERLAY_REQUEST_CODE}.
     *
     * @param activity the calling activity
     */
    public static void requestOverlay(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(activity)) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())
                );
                activity.startActivityForResult(intent, OVERLAY_REQUEST_CODE);
            }
        }
    }

    // ──────────────── Notification Permission ────────────────

    /**
     * Check if the app can post notifications (API 33+).
     *
     * @param context application or activity context
     * @return true if notification permission is granted (or not required)
     */
    public static boolean canPostNotifications(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /**
     * Request notification permission (API 33+). No-op on older devices.
     *
     * @param activity the calling activity
     */
    public static void requestNotificationPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        activity,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE
                );
            }
        }
    }

    // ──────────────── Battery Optimization ────────────────

    /**
     * Check if the app is exempt from battery optimization.
     *
     * @param context application or activity context
     * @return true if whitelisted or not required
     */
    public static boolean isBatteryOptimizationWhitelisted(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                return pm.isIgnoringBatteryOptimizations(context.getPackageName());
            }
        }
        return true;
    }

    /**
     * Request battery optimization exemption.
     * Opens the system settings; result is delivered in
     * {@link Activity#onActivityResult} with {@link #BATTERY_REQUEST_CODE}.
     *
     * @param activity the calling activity
     */
    public static void requestBatteryOptimization(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(activity.getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(intent, BATTERY_REQUEST_CODE);
            }
        }
    }

    // ──────────────── Storage Permission Variants ────────────────

    /**
     * Check if we have read access to media (API 33+ uses granular permissions).
     *
     * @param context application or activity context
     * @return true if storage/media read is available
     */
    public static boolean hasStorageReadAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context,
                    Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(context,
                    Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    // ──────────────── Permission Result Helpers ────────────────

    /**
     * Process permission results from {@link Activity#onRequestPermissionsResult}.
     *
     * @param requestCode  the request code
     * @param grantResults the results array
     * @return true if all requested permissions were granted
     */
    public static boolean allGranted(int requestCode, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Check if a user permanently denied a permission (should show rationale).
     *
     * @param activity   the activity
     * @param permission the permission to check
     * @return true if the user checked "Never ask again"
     */
    public static boolean isPermanentlyDenied(Activity activity, String permission) {
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                && ContextCompat.checkSelfPermission(activity, permission)
                != PackageManager.PERMISSION_GRANTED;
    }
}

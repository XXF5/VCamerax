package com.dualspace.obs.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.dualspace.obs.floating.FloatingControlService;

/**
 * Boot completed receiver.
 * Checks SharedPreferences for auto-start preference and launches
 * the FloatingControlService if enabled.
 * Also checks for any pending stream/recording sessions to resume.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private static final String PREFS_NAME = "dualspace_obs_prefs";
    private static final String KEY_AUTO_START = "pref_auto_start";
    private static final String KEY_PENDING_STREAM = "pref_pending_stream";
    private static final String KEY_PENDING_RECORDING = "pref_pending_recording";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.i(TAG, "Received broadcast: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            handleBootCompleted(context);
        }
    }

    /**
     * Handle device boot completed.
     * Check if auto-start is enabled and launch the service.
     */
    private void handleBootCompleted(Context context) {
        if (!shouldAutoStart(context)) {
            Log.i(TAG, "Auto-start is disabled, skipping service launch");
            return;
        }

        Log.i(TAG, "Auto-start enabled, launching FloatingControlService");

        // Check for pending sessions
        checkPendingSessions(context);

        // Start the floating control service
        startService(context);
    }

    /**
     * Check if auto-start is enabled in SharedPreferences.
     *
     * @param context application context
     * @return true if auto-start is enabled
     */
    public boolean shouldAutoStart(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_AUTO_START, false);
    }

    /**
     * Check for any pending streams or recordings that should be resumed.
     *
     * @param context application context
     */
    private void checkPendingSessions(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        boolean hasPendingStream = prefs.getBoolean(KEY_PENDING_STREAM, false);
        boolean hasPendingRecording = prefs.getBoolean(KEY_PENDING_RECORDING, false);

        if (hasPendingStream) {
            Log.i(TAG, "Found pending stream session");
            // Clear the pending flag; the service will handle resumption
            prefs.edit().putBoolean(KEY_PENDING_STREAM, false).apply();
        }

        if (hasPendingRecording) {
            Log.i(TAG, "Found pending recording session");
            prefs.edit().putBoolean(KEY_PENDING_RECORDING, false).apply();
        }
    }

    /**
     * Start the FloatingControlService as a foreground service.
     *
     * @param context application context
     */
    public void startService(Context context) {
        try {
            Intent serviceIntent = new Intent(context, FloatingControlService.class);
            serviceIntent.setAction("ACTION_SHOW");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.i(TAG, "FloatingControlService started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start FloatingControlService", e);
        }
    }
}

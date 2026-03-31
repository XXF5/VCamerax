/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.activities;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;
import com.dualspace.obs.R;

/**
 * SplashActivity displays the app's splash screen for a short duration,
 * performs first-run checks, verifies essential permissions, and then
 * navigates the user to {@link MainActivity}.
 *
 * <p>Uses {@link Handler#postDelayed} for the timed transition and
 * SharedPreferences to track first-run state.</p>
 */
public class SplashActivity extends AppCompatActivity {

    private static final String TAG = "SplashActivity";

    /** Delay in milliseconds before navigating away from the splash screen. */
    private static final long SPLASH_DELAY_MS = 2000L;

    /** Request code for runtime permission requests. */
    private static final int PERMISSION_REQUEST_CODE = 1001;

    // ─── UI References ───────────────────────────────────────────────
    private ImageView logoImageView;
    private LottieAnimationView lottieAnimationView;
    private TextView versionTextView;

    // ─── State ───────────────────────────────────────────────────────
    private final Handler splashHandler = new Handler(Looper.getMainLooper());
    private boolean hasNavigated = false;

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        initViews();
        playAnimations();
        checkFirstRun();

        // Post delayed navigation so the splash is visible for SPLASH_DELAY_MS
        splashHandler.postDelayed(this::navigateToMain, SPLASH_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Remove pending callbacks to prevent leaks / navigation after destroy
        splashHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        // Prevent back-press during splash
        super.onBackPressed();
    }

    // ══════════════════════════════════════════════════════════════════
    // Initialisation
    // ══════════════════════════════════════════════════════════════════

    /**
     * Finds and caches references to all splash-screen views.
     */
    private void initViews() {
        logoImageView = findViewById(R.id.splash_logo);
        lottieAnimationView = findViewById(R.id.splash_lottie_animation);
        versionTextView = findViewById(R.id.splash_version_text);

        // Display version string from PackageInfo
        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            if (versionTextView != null) {
                versionTextView.setText(getString(R.string.splash_version, versionName));
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unable to read version name", e);
        }
    }

    /**
     * Starts the Lottie animation and an optional alpha fade-in on the logo.
     */
    private void playAnimations() {
        if (lottieAnimationView != null) {
            lottieAnimationView.playAnimation();
        }

        if (logoImageView != null) {
            AlphaAnimation fadeIn = new AlphaAnimation(0.0f, 1.0f);
            fadeIn.setDuration(800L);
            fadeIn.setFillAfter(true);
            logoImageView.startAnimation(fadeIn);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Permission Handling
    // ══════════════════════════════════════════════════════════════════

    /**
     * Checks whether the essential permissions have already been granted.
     * If any are missing, the permission request dialog is shown.
     *
     * <p>Essential permissions checked here:
     * <ul>
     *     <li>{@link Manifest.permission#CAMERA}</li>
     *     <li>{@link Manifest.permission#RECORD_AUDIO}</li>
     *     <li>{@link Manifest.permission#READ_EXTERNAL_STORAGE} (API &lt; 33)</li>
     *     <li>{@link Manifest.permission#READ_MEDIA_IMAGES} (API &ge; 33)</li>
     *     <li>{@link Manifest.permission#READ_MEDIA_VIDEO} (API &ge; 33)</li>
     * </ul>
     * </p>
     */
    private void checkPermissions() {
        if (hasAllPermissions()) {
            Log.d(TAG, "All essential permissions already granted");
            return;
        }

        Log.i(TAG, "Requesting essential permissions");

        // Build the permission list based on API level
        java.util.List<String> permissions = new java.util.ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);

        if (android.os.Build.VERSION.SDK_INT < 33) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        } else {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
        }

        ActivityCompat.requestPermissions(
                this,
                permissions.toArray(new String[0]),
                PERMISSION_REQUEST_CODE
        );
    }

    /**
     * @return {@code true} if every essential permission has been granted.
     */
    private boolean hasAllPermissions() {
        java.util.List<String> required = new java.util.ArrayList<>();
        required.add(Manifest.permission.CAMERA);
        required.add(Manifest.permission.RECORD_AUDIO);

        if (android.os.Build.VERSION.SDK_INT < 33) {
            required.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        } else {
            required.add(Manifest.permission.READ_MEDIA_IMAGES);
            required.add(Manifest.permission.READ_MEDIA_VIDEO);
        }

        for (String perm : required) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Log.i(TAG, "All permissions granted after request");
            } else {
                Log.w(TAG, "Some permissions were denied – limited functionality");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // First-Run Check
    // ══════════════════════════════════════════════════════════════════

    /**
     * Uses {@link SharedPreferences} to determine whether this is the
     * first time the app has been launched.  If so, a flag is persisted
     * and any first-run initialisation logic (e.g. creating default
     * directories) is executed.
     */
    private void checkFirstRun() {
        SharedPreferences prefs = getSharedPreferences("dualspace_prefs", Context.MODE_PRIVATE);
        boolean isFirstRun = prefs.getBoolean("is_first_run", true);

        if (isFirstRun) {
            Log.i(TAG, "First run detected – performing initial setup");
            prefs.edit().putBoolean("is_first_run", false).apply();
            performFirstRunSetup();
        } else {
            Log.d(TAG, "Returning user – skipping first-run setup");
        }
    }

    /**
     * Performs one-time setup tasks such as creating default storage
     * directories.
     */
    private void performFirstRunSetup() {
        java.io.File baseDir = getExternalFilesDir(null);
        if (baseDir != null) {
            java.io.File recordingsDir = new java.io.File(baseDir, "Recordings");
            java.io.File clonesDir = new java.io.File(baseDir, "Clones");
            java.io.File cacheDir = new java.io.File(baseDir, "Cache");

            boolean created = true;
            if (!recordingsDir.exists()) created &= recordingsDir.mkdirs();
            if (!clonesDir.exists()) created &= clonesDir.mkdirs();
            if (!cacheDir.exists()) created &= cacheDir.mkdirs();

            Log.i(TAG, "Default directories created: " + created);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Navigation
    // ══════════════════════════════════════════════════════════════════

    /**
     * Navigates to {@link MainActivity} and finishes this activity.
     * Guards against double-navigation with {@link #hasNavigated}.
     */
    private void navigateToMain() {
        if (hasNavigated) {
            return;
        }
        hasNavigated = true;

        checkPermissions();

        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}

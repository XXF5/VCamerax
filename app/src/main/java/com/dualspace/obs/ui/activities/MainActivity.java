/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.activities;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.dualspace.obs.R;
import com.dualspace.obs.databinding.ActivityMainBinding;
import com.dualspace.obs.ui.fragments.HomeFragment;
import com.dualspace.obs.ui.fragments.AppsFragment;
import com.dualspace.obs.ui.fragments.OBSFragment;
import com.dualspace.obs.ui.fragments.SettingsFragment;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationBarView;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity serves as the application's primary entry point after the
 * splash screen.  It hosts a {@link BottomNavigationView} with four tabs
 * (Home, Apps, OBS, Settings) and manages fragment switching via
 * {@link FragmentTransaction}.
 *
 * <h3>Key responsibilities:</h3>
 * <ul>
 *   <li>Bottom navigation setup with badge support on the Apps tab</li>
 *   <li>Fragment lifecycle management (detach / attach instead of replace)</li>
 *   <li>Runtime permission handling for camera, microphone, storage, overlay</li>
 *   <li>Double-back-press to exit</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    /** Request code used when requesting the SYSTEM_ALERT_WINDOW overlay permission. */
    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 2001;
    /** Request code used for the standard runtime permission batch. */
    private static final int RUNTIME_PERMISSION_REQUEST_CODE = 2002;

    // ─── Back-press exit state ───────────────────────────────────────
    private static final long BACK_PRESS_INTERVAL_MS = 2000L;
    private long lastBackPressTime = 0L;

    // ─── Preferences ─────────────────────────────────────────────────
    private SharedPreferences appPrefs;

    // ─── Current navigation state ────────────────────────────────────
    private int currentTabId = R.id.nav_home;
    private Fragment currentFragment;

    // ─── Fragment references (cached for detach / attach pattern) ────
    private Fragment homeFragment;
    private Fragment appsFragment;
    private Fragment obsFragment;
    private Fragment settingsFragment;

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        appPrefs = getSharedPreferences("dualspace_prefs", MODE_PRIVATE);

        initToolbar(binding);
        setupNavigation(binding.bottomNavigation);
        checkPermissions();

        // Restore or show default tab
        if (savedInstanceState != null) {
            currentTabId = savedInstanceState.getInt("current_tab", R.id.nav_home);
        }
        binding.bottomNavigation.setSelectedItemId(currentTabId);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("current_tab", currentTabId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAppsBadge();
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        // Handle any deep-link or notification navigation here
        if (intent.hasExtra("navigate_to")) {
            String target = intent.getStringExtra("navigate_to");
            if ("obs".equalsIgnoreCase(target)) {
                // Switch to OBS tab
                BottomNavigationView nav = findViewById(R.id.bottom_navigation);
                if (nav != null) nav.setSelectedItemId(R.id.nav_obs);
            }
        }
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackPressTime < BACK_PRESS_INTERVAL_MS) {
            // Double back press detected – exit
            super.onBackPressed();
            finishAffinity();
        } else {
            lastBackPressTime = now;
            Toast.makeText(this, R.string.press_back_again_to_exit,
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Toolbar
    // ══════════════════════════════════════════════════════════════════

    private void initToolbar(@NonNull ActivityMainBinding binding) {
        Toolbar toolbar = binding.toolbar;
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_notifications) {
            Toast.makeText(this, "Notifications", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_help) {
            Toast.makeText(this, "Help & Feedback", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ══════════════════════════════════════════════════════════════════
    // Bottom Navigation
    // ══════════════════════════════════════════════════════════════════

    /**
     * Sets up the {@link BottomNavigationView} with four tabs and an
     * {@link NavigationBarView.OnItemSelectedListener} that drives fragment
     * switching.
     *
     * @param bottomNav the navigation view from the layout
     */
    public void setupNavigation(@NonNull BottomNavigationView bottomNav) {
        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == currentTabId) {
                return true; // Already showing this tab
            }

            Fragment fragment = null;
            String title = null;

            if (itemId == R.id.nav_home) {
                fragment = getOrCreateFragment(0);
                title = getString(R.string.tab_home);
            } else if (itemId == R.id.nav_apps) {
                fragment = getOrCreateFragment(1);
                title = getString(R.string.tab_apps);
            } else if (itemId == R.id.nav_obs) {
                fragment = getOrCreateFragment(2);
                title = getString(R.string.tab_obs);
            } else if (itemId == R.id.nav_settings) {
                fragment = getOrCreateFragment(3);
                title = getString(R.string.tab_settings);
            }

            if (fragment != null) {
                switchFragment(fragment);
                currentTabId = itemId;
                if (getSupportActionBar() != null && title != null) {
                    getSupportActionBar().setTitle(title);
                }
            }
            return true;
        });

        // Set up the badge on the Apps tab
        updateAppsBadge();
    }

    /**
     * Updates the badge on the Apps tab to reflect the current clone count.
     */
    private void updateAppsBadge() {
        BottomNavigationView nav = findViewById(R.id.bottom_navigation);
        if (nav == null) return;

        int cloneCount = appPrefs.getInt("clone_count", 0);

        BadgeDrawable badge = nav.getOrCreateBadge(R.id.nav_apps);
        if (cloneCount > 0) {
            badge.setVisible(true);
            badge.setNumber(cloneCount);
            badge.setBackgroundColor(Color.parseColor("#FF4444"));
            badge.setBadgeTextColor(Color.WHITE);
        } else {
            badge.setVisible(false);
            badge.clearNumber();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Fragment Management
    // ══════════════════════════════════════════════════════════════════

    /**
     * Lazily creates fragment instances and returns the one at {@code index}.
     * <ul>
     *   <li>0 = HomeFragment</li>
     *   <li>1 = AppsFragment</li>
     *   <li>2 = OBSFragment</li>
     *   <li>3 = SettingsFragment</li>
     * </ul>
     */
    @NonNull
    private Fragment getOrCreateFragment(int index) {
        switch (index) {
            case 0:
                if (homeFragment == null) homeFragment = new HomeFragment();
                return homeFragment;
            case 1:
                if (appsFragment == null) appsFragment = new AppsFragment();
                return appsFragment;
            case 2:
                if (obsFragment == null) obsFragment = new OBSFragment();
                return obsFragment;
            case 3:
                if (settingsFragment == null) settingsFragment = new SettingsFragment();
                return settingsFragment;
            default:
                if (homeFragment == null) homeFragment = new HomeFragment();
                return homeFragment;
        }
    }

    /**
     * Replaces the currently displayed fragment using a
     * {@link FragmentTransaction}.  Uses the hide/show + attach/detach
     * pattern for smoother transitions.
     *
     * @param fragment the new fragment to display
     */
    public void switchFragment(@NonNull Fragment fragment) {
        if (currentFragment == fragment) {
            return;
        }

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

        if (currentFragment != null) {
            transaction.detach(currentFragment);
        }

        // Attach the target fragment (or add if not yet added)
        if (!fragment.isAdded()) {
            transaction.add(R.id.fragment_container, fragment, fragment.getClass().getSimpleName());
        } else {
            transaction.attach(fragment);
        }

        transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        transaction.commitAllowingStateLoss();

        currentFragment = fragment;
    }

    // ══════════════════════════════════════════════════════════════════
    // Permission Handling
    // ══════════════════════════════════════════════════════════════════

    /**
     * Checks and requests all required runtime permissions:
     * <ul>
     *   <li>Camera</li>
     *   <li>Microphone</li>
     *   <li>Storage (READ_EXTERNAL_STORAGE or READ_MEDIA_IMAGES/VIDEO)</li>
     *   <li>Overlay (SYSTEM_ALERT_WINDOW – via Settings intent)</li>
     * </ul>
     */
    public void checkPermissions() {
        List<String> needed = new ArrayList<>();

        // Camera
        if (!hasPermission(Manifest.permission.CAMERA)) {
            needed.add(Manifest.permission.CAMERA);
        }
        // Microphone
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        // Storage – API-level branching
        if (Build.VERSION.SDK_INT < 33) {
            if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        } else {
            if (!hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
                needed.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (!hasPermission(Manifest.permission.READ_MEDIA_VIDEO)) {
                needed.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
        }

        // Notifications (API 33+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]));
        }

        // Overlay permission must be requested via a system Settings intent
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
        }
    }

    /**
     * Launches the system overlay permission screen.
     */
    private void requestOverlayPermission() {
        Log.i(TAG, "Requesting SYSTEM_ALERT_WINDOW overlay permission");
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );
        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
    }

    /**
     * Convenience method to check a single permission.
     */
    private boolean hasPermission(@NonNull String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Convenience wrapper that fires a single permission-request call.
     */
    public void requestPermissions(@NonNull String[] permissions) {
        ActivityCompat.requestPermissions(
                this, permissions, RUNTIME_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RUNTIME_PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.w(TAG, "Permission denied: " + permissions[i]);
                }
            }
            if (!allGranted) {
                showPermissionDeniedDialog();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
                Log.i(TAG, "Overlay permission granted");
            } else {
                Log.w(TAG, "Overlay permission denied");
                Toast.makeText(this, R.string.overlay_permission_required,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Shows a dialog informing the user that some permissions were denied
     * and offering to navigate to app settings.
     */
    private void showPermissionDeniedDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.permission_denied_title)
                .setMessage(R.string.permission_denied_message)
                .setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}

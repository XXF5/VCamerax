/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.dualspace.obs.R;
import com.dualspace.obs.databinding.ActivitySettingsBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * SettingsActivity hosts a {@link PreferenceFragmentCompat} that exposes
 * all application settings organised into categories:
 *
 * <ul>
 *   <li><b>General</b> – theme, language, notifications, auto-start</li>
 *   <li><b>Virtual Space</b> – engine mode, memory limit, compatibility</li>
 *   <li><b>OBS</b> – default quality, encoder, auto-reconnect, overlay</li>
 *   <li><b>Camera</b> – resolution, FPS, default source, noise suppression</li>
 *   <li><b>Storage</b> – path, cache size, clear cache, clear all</li>
 *   <li><b>About</b> – version, changelog, licenses</li>
 * </ul>
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    private ActivitySettingsBinding binding;

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        initToolbar();

        // Load the PreferenceFragment
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }
    }

    private void initToolbar() {
        Toolbar toolbar = binding.toolbar;
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.title_settings);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    // ══════════════════════════════════════════════════════════════════
    // PreferenceFragment
    // ══════════════════════════════════════════════════════════════════

    /**
     * The main preference screen fragment.  All settings persistence goes
     * through the default {@link androidx.preference.PreferenceManager}.
     */
    public static class SettingsFragment extends PreferenceFragmentCompat {

        private static final String TAG = "SettingsFragment";

        private SharedPreferences appPrefs;
        private Context context;

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            setPreferencesFromResource(R.xml.preferences_root, rootKey);
            context = requireContext();
            appPrefs = context.getSharedPreferences("dualspace_prefs", Context.MODE_PRIVATE);

            setupGeneralPreferences();
            setupVirtualSpacePreferences();
            setupOBSPreferences();
            setupCameraPreferences();
            setupStoragePreferences();
            setupAboutPreferences();
        }

        // ─── General ────────────────────────────────────────────────

        private void setupGeneralPreferences() {
            // Theme
            ListPreference themePref = findPreference("pref_theme");
            if (themePref != null) {
                themePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = (String) newValue;
                    switch (value) {
                        case "light":
                            AppCompatDelegate.setDefaultNightMode(
                                    AppCompatDelegate.MODE_NIGHT_NO);
                            break;
                        case "dark":
                            AppCompatDelegate.setDefaultNightMode(
                                    AppCompatDelegate.MODE_NIGHT_YES);
                            break;
                        case "system":
                        default:
                            AppCompatDelegate.setDefaultNightMode(
                                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                            break;
                    }
                    return true;
                });
            }

            // Language
            ListPreference langPref = findPreference("pref_language");
            if (langPref != null) {
                langPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    Log.i(TAG, "Language changed to: " + newValue);
                    Toast.makeText(context, R.string.restart_required,
                            Toast.LENGTH_SHORT).show();
                    return true;
                });
            }

            // Auto-start
            SwitchPreferenceCompat autoStartPref = findPreference("pref_auto_start");
            if (autoStartPref != null) {
                autoStartPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enabled = (Boolean) newValue;
                    appPrefs.edit().putBoolean("auto_start", enabled).apply();
                    Log.i(TAG, "Auto-start: " + enabled);
                    return true;
                });
            }
        }

        // ─── Virtual Space ──────────────────────────────────────────

        private void setupVirtualSpacePreferences() {
            // Engine mode
            ListPreference enginePref = findPreference("pref_engine_mode");
            if (enginePref != null) {
                enginePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    appPrefs.edit().putString("engine_mode", (String) newValue).apply();
                    Log.i(TAG, "Engine mode: " + newValue);
                    return true;
                });
            }

            // Memory limit
            ListPreference memoryPref = findPreference("pref_memory_limit");
            if (memoryPref != null) {
                memoryPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    appPrefs.edit().putString("memory_limit", (String) newValue).apply();
                    Log.i(TAG, "Memory limit: " + newValue);
                    return true;
                });
            }

            // Compatibility mode
            SwitchPreferenceCompat compatPref = findPreference("pref_compatibility_mode");
            if (compatPref != null) {
                compatPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    appPrefs.edit().putBoolean("compatibility_mode",
                            (Boolean) newValue).apply();
                    return true;
                });
            }
        }

        // ─── OBS ────────────────────────────────────────────────────

        private void setupOBSPreferences() {
            // Default quality
            ListPreference qualityPref = findPreference("pref_default_quality");
            if (qualityPref != null) {
                qualityPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = (String) newValue;
                    SharedPreferences obsPrefs = context.getSharedPreferences(
                            "obs_prefs", Context.MODE_PRIVATE);
                    obsPrefs.edit().putString("quality_preset", value).apply();

                    // Auto-set resolution/bitrate/fps based on quality
                    switch (value) {
                        case "low":
                            obsPrefs.edit()
                                    .putString("resolution", "854x480")
                                    .putInt("bitrate", 1000)
                                    .putInt("fps", 24)
                                    .apply();
                            break;
                        case "medium":
                            obsPrefs.edit()
                                    .putString("resolution", "1280x720")
                                    .putInt("bitrate", 2500)
                                    .putInt("fps", 30)
                                    .apply();
                            break;
                        case "high":
                            obsPrefs.edit()
                                    .putString("resolution", "1920x1080")
                                    .putInt("bitrate", 4500)
                                    .putInt("fps", 30)
                                    .apply();
                            break;
                        case "ultra":
                            obsPrefs.edit()
                                    .putString("resolution", "1920x1080")
                                    .putInt("bitrate", 6000)
                                    .putInt("fps", 60)
                                    .apply();
                            break;
                    }
                    Log.i(TAG, "OBS quality set to: " + value);
                    return true;
                });
            }

            // Encoder
            ListPreference encoderPref = findPreference("pref_encoder");
            if (encoderPref != null) {
                encoderPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    context.getSharedPreferences("obs_prefs", Context.MODE_PRIVATE)
                            .edit().putString("encoder", (String) newValue).apply();
                    Log.i(TAG, "Encoder: " + newValue);
                    return true;
                });
            }

            // Auto-reconnect
            SwitchPreferenceCompat reconnectPref = findPreference("pref_auto_reconnect");
            if (reconnectPref != null) {
                reconnectPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    context.getSharedPreferences("obs_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("auto_reconnect", (Boolean) newValue)
                            .apply();
                    return true;
                });
            }

            // Overlay
            SwitchPreferenceCompat overlayPref = findPreference("pref_overlay");
            if (overlayPref != null) {
                overlayPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    appPrefs.edit().putBoolean("overlay_enabled",
                            (Boolean) newValue).apply();
                    return true;
                });
            }
        }

        // ─── Camera ─────────────────────────────────────────────────

        private void setupCameraPreferences() {
            // Resolution
            ListPreference camResPref = findPreference("pref_camera_resolution");
            if (camResPref != null) {
                camResPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
                            .edit().putString("resolution", (String) newValue).apply();
                    return true;
                });
            }

            // FPS
            ListPreference camFpsPref = findPreference("pref_camera_fps");
            if (camFpsPref != null) {
                camFpsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
                            .edit().putInt("fps", Integer.parseInt((String) newValue))
                            .apply();
                    return true;
                });
            }

            // Default source
            ListPreference sourcePref = findPreference("pref_default_source");
            if (sourcePref != null) {
                sourcePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
                            .edit().putString("source_type", (String) newValue)
                            .apply();
                    return true;
                });
            }

            // Noise suppression
            SwitchPreferenceCompat noisePref = findPreference("pref_noise_suppression");
            if (noisePref != null) {
                noisePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("noise_suppression", (Boolean) newValue)
                            .apply();
                    return true;
                });
            }
        }

        // ─── Storage ────────────────────────────────────────────────

        private void setupStoragePreferences() {
            // Storage path
            Preference pathPref = findPreference("pref_storage_path");
            if (pathPref != null) {
                File recordingsDir = context.getExternalFilesDir("Recordings");
                pathPref.setSummary(recordingsDir != null
                        ? recordingsDir.getAbsolutePath() : "Internal storage");

                pathPref.setOnPreferenceClickListener(preference -> {
                    Toast.makeText(context, R.string.storage_path_info,
                            Toast.LENGTH_SHORT).show();
                    return true;
                });
            }

            // Cache size
            Preference cachePref = findPreference("pref_cache_size");
            if (cachePref != null) {
                updateCacheSizeDisplay(cachePref);
            }

            // Clear cache
            Preference clearCachePref = findPreference("pref_clear_cache");
            if (clearCachePref != null) {
                clearCachePref.setOnPreferenceClickListener(preference -> {
                    clearCache(cachePref);
                    return true;
                });
            }

            // Clear all data
            Preference clearAllPref = findPreference("pref_clear_all");
            if (clearAllPref != null) {
                clearAllPref.setOnPreferenceClickListener(preference -> {
                    new MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.confirm_clear_all_title)
                            .setMessage(R.string.confirm_clear_all_message)
                            .setPositiveButton(R.string.clear_all, (dialog, which) -> {
                                clearAllData();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return true;
                });
            }
        }

        // ─── About ──────────────────────────────────────────────────

        private void setupAboutPreferences() {
            // Version
            Preference versionPref = findPreference("pref_version");
            if (versionPref != null) {
                try {
                    PackageInfo pi = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0);
                    versionPref.setSummary(
                            getString(R.string.about_version, pi.versionName, pi.versionCode));
                } catch (PackageManager.NameNotFoundException e) {
                    versionPref.setSummary("Unknown");
                }
            }

            // Changelog
            Preference changelogPref = findPreference("pref_changelog");
            if (changelogPref != null) {
                changelogPref.setOnPreferenceClickListener(preference -> {
                    showChangelog();
                    return true;
                });
            }

            // Licenses
            Preference licensesPref = findPreference("pref_licenses");
            if (licensesPref != null) {
                licensesPref.setOnPreferenceClickListener(preference -> {
                    showLicenses();
                    return true;
                });
            }
        }

        // ════════════════════════════════════════════════════════════
        // Storage Operations
        // ════════════════════════════════════════════════════════════

        /**
         * Calculates and returns the total cache size in bytes.
         */
        public long calculateCacheSize() {
            File cacheDir = context.getExternalFilesDir("Cache");
            if (cacheDir == null) return 0L;
            return getDirectorySize(cacheDir);
        }

        /**
         * Recursively calculates the size of a directory.
         */
        private long getDirectorySize(@NonNull File dir) {
            if (!dir.exists()) return 0L;
            long size = 0L;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        size += getDirectorySize(file);
                    } else {
                        size += file.length();
                    }
                }
            }
            return size;
        }

        /**
         * Updates the cache-size preference summary.
         */
        private void updateCacheSizeDisplay(@NonNull Preference cachePref) {
            long bytes = calculateCacheSize();
            cachePref.setSummary(formatFileSize(bytes));
        }

        /**
         * Clears the application cache directory.
         */
        public void clearCache(@Nullable Preference cachePref) {
            File cacheDir = context.getExternalFilesDir("Cache");
            if (cacheDir != null) {
                deleteRecursive(cacheDir);
                boolean recreated = cacheDir.mkdirs();
                Log.i(TAG, "Cache cleared, dir recreated: " + recreated);
            }

            // Also clear Glide cache
            try {
                com.bumptech.glide.Glide.get(context).clearDiskCache();
            } catch (Exception e) {
                Log.w(TAG, "Failed to clear Glide cache", e);
            }

            if (cachePref != null) {
                updateCacheSizeDisplay(cachePref);
            }

            Toast.makeText(context, R.string.cache_cleared,
                    Toast.LENGTH_SHORT).show();
        }

        /**
         * Clears all user data (cloned apps, recordings, settings).
         */
        private void clearAllData() {
            // Clear cloned apps
            appPrefs.edit()
                    .putString("cloned_packages", "")
                    .putInt("clone_count", 0)
                    .apply();

            // Clear OBS settings
            context.getSharedPreferences("obs_prefs", Context.MODE_PRIVATE).edit().clear().apply();

            // Clear camera settings
            context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE).edit().clear().apply();

            // Clear default preferences
            androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(context).edit().clear().apply();

            // Clear recordings
            File recordingsDir = context.getExternalFilesDir("Recordings");
            if (recordingsDir != null) {
                deleteRecursive(recordingsDir);
                recordingsDir.mkdirs();
            }

            // Clear clones
            File clonesDir = context.getExternalFilesDir("Clones");
            if (clonesDir != null) {
                deleteRecursive(clonesDir);
                clonesDir.mkdirs();
            }

            Log.i(TAG, "All data cleared");
            Toast.makeText(context, R.string.all_data_cleared, Toast.LENGTH_SHORT).show();

            // Restart activity to reflect changes
            getActivity().recreate();
        }

        /**
         * Recursively deletes a file or directory.
         */
        private void deleteRecursive(@NonNull File fileOrDir) {
            if (fileOrDir.isDirectory()) {
                File[] children = fileOrDir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursive(child);
                    }
                }
            }
            fileOrDir.delete();
        }

        // ════════════════════════════════════════════════════════════
        // Dialogs
        // ════════════════════════════════════════════════════════════

        private void showChangelog() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            String versionDate = sdf.format(new Date());

            String changelog =
                    "Version 2.0.0 (" + versionDate + ")\n\n"
                    + "• Dual Space + OBS integration\n"
                    + "• App cloning with full customization\n"
                    + "• Live streaming to YouTube, Twitch, etc.\n"
                    + "• Virtual camera with multiple sources\n"
                    + "• Screen capture support\n"
                    + "• Audio mixer with noise suppression\n"
                    + "• Recording management\n"
                    + "• Material Design 3 UI\n";

            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.changelog)
                    .setMessage(changelog)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }

        private void showLicenses() {
            String licenses =
                    "This application uses the following open-source libraries:\n\n"
                    + "• AndroidX (Apache 2.0)\n"
                    + "• Material Components for Android (Apache 2.0)\n"
                    + "• OBS Studio (GPLv2)\n"
                    + "• Glide (BSD)\n"
                    + "• Lottie (Apache 2.0)\n"
                    + "• OkHttp (Apache 2.0)\n"
                    + "• Gson (Apache 2.0)\n"
                    + "• MPAndroidChart (Apache 2.0)\n"
                    + "• CameraX (Apache 2.0)\n"
                    + "• ExoPlayer / Media3 (Apache 2.0)\n"
                    + "• EventBus (Apache 2.0)\n"
                    + "• PermissionX (Apache 2.0)\n";

            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.open_source_licenses)
                    .setMessage(licenses)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }

        // ════════════════════════════════════════════════════════════
        // Helpers
        // ════════════════════════════════════════════════════════════

        private static String formatFileSize(long bytes) {
            if (bytes <= 0) return "0 B";
            final String[] units = new String[]{"B", "KB", "MB", "GB"};
            int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
            return String.format(Locale.getDefault(), "%.1f %s",
                    bytes / Math.pow(1024, digitGroups), units[digitGroups]);
        }
    }
}

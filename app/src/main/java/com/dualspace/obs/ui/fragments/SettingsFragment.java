/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.dualspace.obs.BuildConfig;
import com.dualspace.obs.R;
import com.dualspace.obs.databinding.FragmentSettingsBinding;
import com.dualspace.obs.ui.activities.SettingsActivity;

/**
 * SettingsFragment provides a grouped settings dashboard with category
 * cards for General, Virtual Space, OBS, Camera, Storage, and About.
 *
 * <p>Each category card acts as a clickable entry point that opens a
 * sub-settings screen (via {@link androidx.preference.PreferenceFragmentCompat})
 * or a dedicated activity.</p>
 *
 * <p>The About section displays the app version, build info, and links
 * to the privacy policy and open-source licenses.</p>
 *
 * <p>Uses {@link FragmentSettingsBinding} (ViewBinding) for view access.</p>
 */
public class SettingsFragment extends Fragment {

    private static final String TAG = "SettingsFragment";

    // ─── Binding ───────────────────────────────────────────────────────
    private FragmentSettingsBinding binding;

    // ══════════════════════════════════════════════════════════════════
    // Fragment Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupSettingsCategories();
        setupAboutSection();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ══════════════════════════════════════════════════════════════════
    // Settings Categories
    // ══════════════════════════════════════════════════════════════════

    private void setupSettingsCategories() {
        // ─── General ────────────────────────────────────────────────────
        binding.cardGeneral.setOnClickListener(v -> {
            openSubSettings(GeneralPrefsFragment.class, getString(R.string.settings_general));
        });
        binding.tvGeneralSummary.setText("Theme, language, notifications, auto-start");

        // ─── Virtual Space ─────────────────────────────────────────────
        binding.cardVirtualSpace.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "Virtual Space settings",
                    Toast.LENGTH_SHORT).show();
        });
        binding.tvVirtualSpaceSummary.setText("Clone location, sandbox mode, storage");

        // ─── OBS / Streaming ───────────────────────────────────────────
        binding.cardObs.setOnClickListener(v -> {
            openSubSettings(ObsPrefsFragment.class, getString(R.string.settings_streaming));
        });
        binding.tvObsSummary.setText("Quality, encoder, bitrate, auto-reconnect");

        // ─── Camera ────────────────────────────────────────────────────
        binding.cardCamera.setOnClickListener(v -> {
            openSubSettings(CameraPrefsFragment.class, getString(R.string.nav_camera));
        });
        binding.tvCameraSummary.setText("Resolution, FPS, source selection");

        // ─── Storage ───────────────────────────────────────────────────
        binding.cardStorage.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "Storage management",
                    Toast.LENGTH_SHORT).show();
        });
        binding.tvStorageSummary.setText("Recording path, cleanup, auto-delete");

        // ─── About ─────────────────────────────────────────────────────
        binding.cardAbout.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "About",
                    Toast.LENGTH_SHORT).show();
        });
        binding.tvAboutSummary.setText("Version, licenses, privacy policy");
    }

    /**
     * Opens a sub-settings screen by embedding a
     * {@link PreferenceFragmentCompat} inside a container or
     * navigating to a dedicated activity.
     *
     * @param fragmentClass the PreferenceFragmentCompat class to display
     * @param title         the toolbar title for the sub-screen
     */
    private void openSubSettings(@NonNull Class<? extends PreferenceFragmentCompat> fragmentClass,
                                 @NonNull String title) {
        Intent intent = new Intent(requireContext(), SettingsActivity.class);
        intent.putExtra(SettingsActivity.EXTRA_PREFS_FRAGMENT, fragmentClass.getName());
        intent.putExtra(SettingsActivity.EXTRA_PREFS_TITLE, title);
        startActivity(intent);
    }

    // ══════════════════════════════════════════════════════════════════
    // About Section
    // ══════════════════════════════════════════════════════════════════

    private void setupAboutSection() {
        binding.tvAppVersion.setText(String.format("Version %s (%d)",
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        binding.tvBuildInfo.setText(String.format("Build: %s | SDK %d",
                BuildConfig.BUILD_TYPE, android.os.Build.VERSION.SDK_INT));

        // Privacy Policy
        binding.tvPrivacyPolicy.setOnClickListener(v -> {
            openUrl("https://dualspace-obs.app/privacy-policy");
        });

        // Terms of Service
        binding.tvTermsOfService.setOnClickListener(v -> {
            openUrl("https://dualspace-obs.app/terms-of-service");
        });

        // Open Source Licenses
        binding.tvOpenSourceLicenses.setOnClickListener(v -> {
            openUrl("https://dualspace-obs.app/licenses");
        });

        // Check for Updates
        binding.tvCheckUpdates.setOnClickListener(v -> {
            Toast.makeText(requireContext(),
                    R.string.settings_check_updates, Toast.LENGTH_SHORT).show();
        });
    }

    private void openUrl(@NonNull String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    // ══════════════════════════════════════════════════════════════════
    // Nested Preference Fragments
    // ══════════════════════════════════════════════════════════════════

    /**
     * General preferences sub-screen (theme, language, notifications, auto-start).
     */
    public static class GeneralPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState,
                                        @Nullable String rootKey) {
            setPreferencesFromResource(R.xml.preferences_general, rootKey);
        }
    }

    /**
     * OBS / Streaming preferences sub-screen (quality, encoder, auto-reconnect).
     */
    public static class ObsPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState,
                                        @Nullable String rootKey) {
            setPreferencesFromResource(R.xml.preferences_obs, rootKey);
        }
    }

    /**
     * Camera preferences sub-screen (resolution, FPS, source).
     */
    public static class CameraPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState,
                                        @Nullable String rootKey) {
            setPreferencesFromResource(R.xml.preferences_camera, rootKey);
        }
    }
}

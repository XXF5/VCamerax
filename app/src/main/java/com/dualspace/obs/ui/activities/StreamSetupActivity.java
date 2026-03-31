/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.dualspace.obs.R;
import com.dualspace.obs.databinding.ActivityStreamSetupBinding;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * StreamSetupActivity provides a form for configuring streaming
 * parameters before going live.  The user selects a platform, enters
 * the stream key, chooses a quality preset, and can optionally test
 * the connection before saving or starting the stream.
 *
 * <h3>Supported platforms (with auto-filled server URLs):</h3>
 * <ul>
 *   <li>YouTube – rtmp://a.rtmp.youtube.com/live2</li>
 *   <li>Twitch – rtmp://live.twitch.tv/app</li>
 *   <li>Facebook – rtmps://live-api-s.facebook.com:443/rtmp/</li>
 *   <li>Kick – rtmps://fa723fc5050b.us-east-1.contribute.live-video.net/app</li>
 *   <li>Custom – user-specified URL</li>
 * </ul>
 */
public class StreamSetupActivity extends AppCompatActivity {

    private static final String TAG = "StreamSetupActivity";

    // ─── Binding ─────────────────────────────────────────────────────
    private ActivityStreamSetupBinding binding;

    // ─── Platform → server URL map ───────────────────────────────────
    private static final Map<String, PlatformInfo> PLATFORM_MAP = new HashMap<>();

    static {
        PLATFORM_MAP.put("YouTube", new PlatformInfo(
                "rtmp://a.rtmp.youtube.com/live2",
                "https://www.youtube.com",
                "YouTube Live Streaming"
        ));
        PLATFORM_MAP.put("Twitch", new PlatformInfo(
                "rtmp://live.twitch.tv/app",
                "https://www.twitch.tv",
                "Twitch"
        ));
        PLATFORM_MAP.put("Facebook", new PlatformInfo(
                "rtmps://live-api-s.facebook.com:443/rtmp/",
                "https://www.facebook.com/live",
                "Facebook Live"
        ));
        PLATFORM_MAP.put("Kick", new PlatformInfo(
                "rtmps://fa723fc5050b.us-east-1.contribute.live-video.net/app",
                "https://kick.com",
                "Kick"
        ));
    }

    // ─── State ───────────────────────────────────────────────────────
    private String selectedPlatform = "YouTube";
    private boolean isStreamKeyVisible = false;
    private String selectedQuality = "medium";

    // ─── Preferences ─────────────────────────────────────────────────
    private SharedPreferences obsPrefs;

    // ─── Connection test state ───────────────────────────────────────
    private volatile boolean isTestingConnection = false;

    // ══════════════════════════════════════════════════════════════════
    // Data Model
    // ══════════════════════════════════════════════════════════════════

    public static class PlatformInfo {
        public final String serverUrl;
        public final String dashboardUrl;
        public final String displayName;

        PlatformInfo(String serverUrl, String dashboardUrl, String displayName) {
            this.serverUrl = serverUrl;
            this.dashboardUrl = dashboardUrl;
            this.displayName = displayName;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityStreamSetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        obsPrefs = getSharedPreferences("obs_prefs", MODE_PRIVATE);

        initToolbar();
        initPlatformSelector();
        initServerUrl();
        initStreamKey();
        initQualitySelector();
        initButtons();
        loadSavedSettings();
    }

    // ══════════════════════════════════════════════════════════════════
    // Initialisation
    // ══════════════════════════════════════════════════════════════════

    private void initToolbar() {
        Toolbar toolbar = binding.toolbar;
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.title_stream_setup);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    /**
     * Populates the platform Spinner and registers the selection listener.
     */
    private void initPlatformSelector() {
        String[] platforms = PLATFORM_MAP.keySet().toArray(new String[0]);
        String[] allPlatforms = new String[platforms.length + 1];
        System.arraycopy(platforms, 0, allPlatforms, 0, platforms.length);
        allPlatforms[allPlatforms.length - 1] = "Custom";

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                allPlatforms
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.platformSpinner.setAdapter(adapter);

        binding.platformSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String platform = (String) parent.getItemAtPosition(position);
                selectPlatform(platform);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void initServerUrl() {
        // Server URL is auto-filled but editable
    }

    private void initStreamKey() {
        // Stream key show/hide toggle
        binding.streamKeyToggle.setOnClickListener(v -> {
            isStreamKeyVisible = !isStreamKeyVisible;
            if (binding.streamKeyInput.getInputType()
                    == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) {
                binding.streamKeyInput.setInputType(
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                binding.streamKeyToggle.setImageResource(R.drawable.ic_visibility_off);
            } else {
                binding.streamKeyInput.setInputType(
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                binding.streamKeyToggle.setImageResource(R.drawable.ic_visibility);
            }
        });

        // Clear stream key button
        binding.clearStreamKeyButton.setOnClickListener(v -> {
            binding.streamKeyInput.setText("");
        });
    }

    private void initQualitySelector() {
        String[] qualities = {"Low (480p)", "Medium (720p)", "High (1080p)", "Ultra (1080p60)"};
        String[] qualityValues = {"low", "medium", "high", "ultra"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, qualities);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.qualitySpinner.setAdapter(adapter);

        binding.qualitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedQuality = qualityValues[position];
                applyQualityPreset(selectedQuality);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void initButtons() {
        // Test connection
        binding.testConnectionButton.setOnClickListener(v -> testConnection());

        // Save settings
        binding.saveButton.setOnClickListener(v -> saveSettings());

        // Save & Start stream
        binding.saveAndStartButton.setOnClickListener(v -> {
            if (saveSettings()) {
                startStream();
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // Platform Selection
    // ══════════════════════════════════════════════════════════════════

    /**
     * Handles platform selection, auto-filling the server URL.
     *
     * @param platform the selected platform name
     */
    public void selectPlatform(@NonNull String platform) {
        selectedPlatform = platform;

        PlatformInfo info = PLATFORM_MAP.get(platform);
        if (info != null) {
            binding.serverUrlInput.setText(info.serverUrl);
            binding.serverUrlInput.setEnabled(false); // Read-only for known platforms
            binding.platformInfoText.setText(info.displayName);

            // Set platform icon
            switch (platform) {
                case "YouTube":
                    binding.platformIcon.setImageResource(R.drawable.ic_youtube);
                    break;
                case "Twitch":
                    binding.platformIcon.setImageResource(R.drawable.ic_twitch);
                    break;
                case "Facebook":
                    binding.platformIcon.setImageResource(R.drawable.ic_facebook);
                    break;
                case "Kick":
                    binding.platformIcon.setImageResource(R.drawable.ic_kick);
                    break;
            }
        } else {
            // Custom platform
            binding.serverUrlInput.setText("");
            binding.serverUrlInput.setEnabled(true);
            binding.platformInfoText.setText(R.string.custom_server_info);
            binding.platformIcon.setImageResource(R.drawable.ic_custom_server);
        }

        Log.i(TAG, "Platform selected: " + platform);
    }

    // ══════════════════════════════════════════════════════════════════
    // Quality Presets
    // ══════════════════════════════════════════════════════════════════

    private void applyQualityPreset(@NonNull String quality) {
        int resolution, bitrate, fps;

        switch (quality) {
            case "low":
                resolution = 0; // index for 854x480
                bitrate = 1000;
                fps = 24;
                break;
            case "high":
                resolution = 2; // index for 1920x1080
                bitrate = 4500;
                fps = 30;
                break;
            case "ultra":
                resolution = 2;
                bitrate = 6000;
                fps = 60;
                break;
            case "medium":
            default:
                resolution = 1; // index for 1280x720
                bitrate = 2500;
                fps = 30;
                break;
        }

        // Update the resolution spinner
        binding.resolutionSpinner.setSelection(resolution);
        binding.bitrateInput.setText(String.valueOf(bitrate));
        binding.fpsInput.setText(String.valueOf(fps));

        Log.i(TAG, "Quality preset applied: " + quality
                + " (bitrate=" + bitrate + "kbps, fps=" + fps + ")");
    }

    // ══════════════════════════════════════════════════════════════════
    // Connection Test
    // ══════════════════════════════════════════════════════════════════

    /**
     * Tests the RTMP connection on a background thread.
     */
    public void testConnection() {
        String serverUrl = binding.serverUrlInput.getText().toString().trim();
        String streamKey = binding.streamKeyInput.getText().toString().trim();

        if (serverUrl.isEmpty()) {
            binding.serverUrlLayout.setError(getString(R.string.server_url_required));
            return;
        }
        binding.serverUrlLayout.setError(null);

        if (streamKey.isEmpty()) {
            binding.streamKeyLayout.setError(getString(R.string.stream_key_required));
            return;
        }
        binding.streamKeyLayout.setError(null);

        if (isTestingConnection) {
            Toast.makeText(this, R.string.test_in_progress,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        isTestingConnection = true;
        binding.testConnectionButton.setEnabled(false);
        binding.testConnectionButton.setText(R.string.testing);
        binding.testResultText.setText(R.string.testing_connection);
        binding.testResultText.setTextColor(
                ContextCompat.getColor(this, R.color.color_testing));

        // Run test on background thread
        new Thread(() -> {
            boolean success = false;
            String message;
            try {
                Log.i(TAG, "Testing connection to: " + serverUrl);

                // ── Simulated connection test ──
                // In production: attempt RTMP handshake
                Thread.sleep(2000);
                success = Math.random() > 0.2; // 80% success rate for demo

                message = success
                        ? getString(R.string.connection_success)
                        : getString(R.string.connection_failed_timeout);

                Log.i(TAG, "Connection test " + (success ? "succeeded" : "failed"));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                message = getString(R.string.connection_test_cancelled);
            } catch (Exception e) {
                Log.e(TAG, "Connection test error", e);
                message = getString(R.string.connection_failed_error);
            }

            boolean finalSuccess = success;
            String finalMessage = message;

            runOnUiThread(() -> {
                isTestingConnection = false;
                binding.testConnectionButton.setEnabled(true);
                binding.testConnectionButton.setText(R.string.test_connection);

                binding.testResultText.setText(finalMessage);
                binding.testResultText.setTextColor(ContextCompat.getColor(
                        StreamSetupActivity.this,
                        finalSuccess ? R.color.color_success : R.color.color_error));

                // Update status indicator
                binding.connectionStatusIndicator.setBackgroundResource(
                        finalSuccess
                                ? R.drawable.ic_status_success
                                : R.drawable.ic_status_error);
            });
        }).start();
    }

    // ══════════════════════════════════════════════════════════════════
    // Save Settings
    // ══════════════════════════════════════════════════════════════════

    /**
     * Validates inputs and saves all settings to SharedPreferences.
     *
     * @return {@code true} if all inputs are valid and were saved.
     */
    public boolean saveSettings() {
        String serverUrl = binding.serverUrlInput.getText().toString().trim();
        String streamKey = binding.streamKeyInput.getText().toString().trim();
        String bitrateStr = binding.bitrateInput.getText().toString().trim();
        String fpsStr = binding.fpsInput.getText().toString().trim();

        // Validate
        boolean valid = true;

        if (serverUrl.isEmpty()) {
            binding.serverUrlLayout.setError(getString(R.string.server_url_required));
            valid = false;
        } else {
            binding.serverUrlLayout.setError(null);
        }

        if (streamKey.isEmpty()) {
            binding.streamKeyLayout.setError(getString(R.string.stream_key_required));
            valid = false;
        } else {
            binding.streamKeyLayout.setError(null);
        }

        int bitrate = 2500;
        if (!bitrateStr.isEmpty()) {
            try {
                bitrate = Integer.parseInt(bitrateStr);
                if (bitrate < 500 || bitrate > 10000) {
                    binding.bitrateLayout.setError(getString(R.string.bitrate_range_warning));
                    valid = false;
                } else {
                    binding.bitrateLayout.setError(null);
                }
            } catch (NumberFormatException e) {
                binding.bitrateLayout.setError(getString(R.string.invalid_number));
                valid = false;
            }
        }

        int fps = 30;
        if (!fpsStr.isEmpty()) {
            try {
                fps = Integer.parseInt(fpsStr);
                if (fps < 15 || fps > 60) {
                    binding.fpsLayout.setError(getString(R.string.fps_range_warning));
                    valid = false;
                } else {
                    binding.fpsLayout.setError(null);
                }
            } catch (NumberFormatException e) {
                binding.fpsLayout.setError(getString(R.string.invalid_number));
                valid = false;
            }
        }

        if (!valid) {
            return false;
        }

        // Determine resolution from spinner
        String resolution = (String) binding.resolutionSpinner.getSelectedItem();

        // Save to preferences
        obsPrefs.edit()
                .putString("platform", selectedPlatform)
                .putString("stream_server", serverUrl)
                .putString("stream_key", streamKey)
                .putString("resolution", resolution)
                .putInt("bitrate", bitrate)
                .putInt("fps", fps)
                .putString("quality_preset", selectedQuality)
                .apply();

        Log.i(TAG, "Stream settings saved: platform=" + selectedPlatform
                + ", server=" + serverUrl
                + ", quality=" + selectedQuality
                + ", bitrate=" + bitrate
                + ", fps=" + fps);

        Snackbar.make(binding.getRoot(), R.string.settings_saved, Snackbar.LENGTH_SHORT).show();
        return true;
    }

    /**
     * Saves settings and navigates to OBSStudioActivity to start
     * streaming.
     */
    public void startStream() {
        Intent intent = new Intent(this, OBSStudioActivity.class);
        intent.putExtra("auto_start_stream", true);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    // ══════════════════════════════════════════════════════════════════
    // Load Saved Settings
    // ══════════════════════════════════════════════════════════════════

    private void loadSavedSettings() {
        String savedPlatform = obsPrefs.getString("platform", "YouTube");
        String savedServer = obsPrefs.getString("stream_server", "");
        String savedKey = obsPrefs.getString("stream_key", "");
        String savedQuality = obsPrefs.getString("quality_preset", "medium");
        int savedBitrate = obsPrefs.getInt("bitrate", 2500);
        int savedFps = obsPrefs.getInt("fps", 30);

        // Restore platform spinner
        String[] platforms = PLATFORM_MAP.keySet().toArray(new String[0]);
        for (int i = 0; i < platforms.length; i++) {
            if (platforms[i].equals(savedPlatform)) {
                binding.platformSpinner.setSelection(i);
                break;
            }
        }

        // Restore server URL
        if (!savedServer.isEmpty()) {
            binding.serverUrlInput.setText(savedServer);
        }

        // Restore stream key
        if (!savedKey.isEmpty()) {
            binding.streamKeyInput.setText(savedKey);
        }

        // Restore quality
        String[] qualityValues = {"low", "medium", "high", "ultra"};
        for (int i = 0; i < qualityValues.length; i++) {
            if (qualityValues[i].equals(savedQuality)) {
                binding.qualitySpinner.setSelection(i);
                break;
            }
        }

        // Restore bitrate and FPS
        binding.bitrateInput.setText(String.valueOf(savedBitrate));
        binding.fpsInput.setText(String.valueOf(savedFps));
    }
}

/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dualspace.obs.R;
import com.dualspace.obs.databinding.ActivityObsStudioBinding;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OBSStudioActivity provides the main streaming/recording dashboard.
 * It manages a preview surface, scene and source lists, stream/record
 * controls, live statistics, and audio mixer sliders.
 *
 * <h3>Layout structure (top → bottom, left → right):</h3>
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │ Toolbar  [Settings menu]                     │
 * ├──────────────────────┬───────────────────────┤
 * │                      │  Scene List           │
 * │   Preview Surface    │  Source List          │
 * │   (SurfaceView)      │  Audio Mixer          │
 * ├──────────────────────┴───────────────────────┤
 * │ Stats: timer | bitrate | FPS | dropped       │
 * ├──────────────────────────────────────────────┤
 * │  [Stream]  [Record]  [FAB + add source]     │
 * └──────────────────────────────────────────────┘
 * </pre>
 */
public class OBSStudioActivity extends AppCompatActivity {

    private static final String TAG = "OBSStudioActivity";

    /** Interval in milliseconds between stat updates. */
    private static final long STATS_UPDATE_INTERVAL_MS = 1000L;

    // ─── Binding ─────────────────────────────────────────────────────
    private ActivityObsStudioBinding binding;

    // ─── OBS State ───────────────────────────────────────────────────
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isOBSInitialized = new AtomicBoolean(false);

    // ─── Stats ───────────────────────────────────────────────────────
    private long streamStartTimeMs = 0L;
    private int currentBitrate = 0;       // kbps
    private int currentFps = 0;
    private int droppedFrames = 0;
    private final Handler statsHandler = new Handler(Looper.getMainLooper());

    // ─── Audio Mixer ─────────────────────────────────────────────────
    private AudioManager audioManager;
    private int originalVolume;
    private int micVolumePercent = 100;
    private int systemVolumePercent = 100;

    // ─── Adapters ────────────────────────────────────────────────────
    private SourceAdapter sceneAdapter;
    private SourceAdapter sourceAdapter;

    // ─── Background ──────────────────────────────────────────────────
    private final ExecutorService obsExecutor = Executors.newSingleThreadExecutor();

    // ─── Preferences ─────────────────────────────────────────────────
    private SharedPreferences obsPrefs;

    // ══════════════════════════════════════════════════════════════════
    // Data Model
    // ══════════════════════════════════════════════════════════════════

    /**
     * Simple model for a scene or source item.
     */
    public static class SourceItem {
        public final String name;
        public final String type;   // "scene", "camera", "display", "image", "audio", "mic"
        public final boolean active;
        public final boolean visible;

        public SourceItem(String name, String type, boolean active, boolean visible) {
            this.name = name;
            this.type = type;
            this.active = active;
            this.visible = visible;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityObsStudioBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        obsPrefs = getSharedPreferences("obs_prefs", MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        initToolbar();
        initPreview();
        initSceneList();
        initSourceList();
        initControls();
        initAudioMixer();
        initOBS();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isOBSInitialized.get()) {
            startStatsUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopStatsUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStatsUpdates();
        obsExecutor.shutdownNow();
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.menu_obs_studio, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_obs_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_virtual_camera) {
            startActivity(new Intent(this, VirtualCameraActivity.class));
            return true;
        } else if (id == R.id.action_stream_setup) {
            startActivity(new Intent(this, StreamSetupActivity.class));
            return true;
        } else if (id == R.id.action_recordings) {
            startActivity(new Intent(this, RecordingListActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (isStreaming.get() || isRecording.get()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.confirm_exit_title)
                    .setMessage(R.string.confirm_exit_obs_message)
                    .setPositiveButton(R.string.stop_and_exit, (d, w) -> {
                        stopStream();
                        stopRecord();
                        finish();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Initialisation
    // ══════════════════════════════════════════════════════════════════

    private void initToolbar() {
        Toolbar toolbar = binding.toolbar;
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.title_obs_studio);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void initPreview() {
        binding.previewSurface.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int w, int h) {
                Log.i(TAG, "Preview surface available: " + w + "x" + h);
                // In production: pass Surface to OBS native layer
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int w, int h) {
                Log.d(TAG, "Preview surface resized: " + w + "x" + h);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                Log.d(TAG, "Preview surface destroyed");
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                // Called every frame
            }
        });
    }

    private void initSceneList() {
        List<SourceItem> scenes = new ArrayList<>();
        scenes.add(new SourceItem("Scene 1", "scene", true, true));
        scenes.add(new SourceItem("Scene 2", "scene", false, true));
        scenes.add(new SourceItem("BRB", "scene", false, true));

        sceneAdapter = new SourceAdapter(scenes, (item, position) -> {
            Log.i(TAG, "Scene selected: " + item.name);
            // In production: switch OBS scene
        });

        binding.sceneRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.sceneRecyclerView.setAdapter(sceneAdapter);
        binding.sceneRecyclerView.addItemDecoration(
                new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
    }

    private void initSourceList() {
        List<SourceItem> sources = new ArrayList<>();
        sources.add(new SourceItem("Camera", "camera", true, true));
        sources.add(new SourceItem("Display Capture", "display", false, true));
        sources.add(new SourceItem("Microphone", "mic", false, true));
        sources.add(new SourceItem("Game Audio", "audio", false, true));

        sourceAdapter = new SourceAdapter(sources, (item, position) -> {
            Log.i(TAG, "Source selected: " + item.name);
            // In production: toggle source visibility
        });

        binding.sourceRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.sourceRecyclerView.setAdapter(sourceAdapter);
        binding.sourceRecyclerView.addItemDecoration(
                new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
    }

    private void initControls() {
        // Resolution / Bitrate / FPS display from prefs
        updatePresetDisplay();

        // Stream button
        binding.streamButton.setOnClickListener(v -> {
            if (isStreaming.get()) {
                stopStream();
            } else {
                startStream();
            }
        });

        // Record button
        binding.recordButton.setOnClickListener(v -> {
            if (isRecording.get()) {
                stopRecord();
            } else {
                startRecord();
            }
        });

        // FAB – add source
        binding.addSourceFab.setOnClickListener(v -> showAddSourceDialog());
    }

    private void initAudioMixer() {
        // Mic volume slider
        binding.micVolumeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                micVolumePercent = progress;
                binding.micVolumeText.setText(progress + "%");
                // In production: pass to OBS audio pipeline
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        binding.micVolumeSlider.setProgress(micVolumePercent);

        // System volume slider
        binding.systemVolumeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                systemVolumePercent = progress;
                binding.systemVolumeText.setText(progress + "%");
                if (fromUser && audioManager != null) {
                    int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int targetVol = (int) (maxVol * (progress / 100.0));
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        binding.systemVolumeSlider.setProgress(systemVolumePercent);

        // Mute buttons
        binding.micMuteButton.setOnClickListener(v -> {
            boolean muted = binding.micMuteButton.isSelected();
            binding.micMuteButton.setSelected(!muted);
            binding.micVolumeSlider.setEnabled(muted);
            // In production: mute/unmute mic in OBS
            Toast.makeText(this, muted ? "Mic unmuted" : "Mic muted", Toast.LENGTH_SHORT).show();
        });

        binding.systemMuteButton.setOnClickListener(v -> {
            boolean muted = binding.systemMuteButton.isSelected();
            binding.systemMuteButton.setSelected(!muted);
            binding.systemVolumeSlider.setEnabled(muted);
            if (audioManager != null) {
                audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        muted ? AudioManager.ADJUST_UNMUTE : AudioManager.ADJUST_MUTE,
                        0);
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // OBS Initialisation
    // ══════════════════════════════════════════════════════════════════

    /**
     * Initialises the OBS engine on a background thread.  On success,
     * {@link #isOBSInitialized} is set to {@code true} and the stats
     * timer is started.
     */
    public void initOBS() {
        binding.loadingOverlay.setVisibility(View.VISIBLE);
        binding.statusText.setText(R.string.obs_initializing);

        obsExecutor.execute(() -> {
            try {
                Log.i(TAG, "Initialising OBS engine…");

                // ── TODO: Replace with actual OBS native init ──
                // Simulated init delay
                Thread.sleep(1500);

                Log.i(TAG, "OBS engine initialised successfully");
                isOBSInitialized.set(true);

                runOnUiThread(() -> {
                    binding.loadingOverlay.setVisibility(View.GONE);
                    binding.statusText.setText(R.string.obs_ready);
                    startStatsUpdates();
                });
            } catch (InterruptedException e) {
                Log.e(TAG, "OBS init interrupted", e);
                Thread.currentThread().interrupt();
                runOnUiThread(() -> {
                    binding.loadingOverlay.setVisibility(View.GONE);
                    binding.statusText.setText(R.string.obs_init_failed);
                    Toast.makeText(this, R.string.obs_init_failed, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "OBS init error", e);
                runOnUiThread(() -> {
                    binding.loadingOverlay.setVisibility(View.GONE);
                    binding.statusText.setText(R.string.obs_init_failed);
                    Toast.makeText(this, R.string.obs_init_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // Streaming Controls
    // ══════════════════════════════════════════════════════════════════

    /**
     * Starts the live stream using the configured RTMP server URL and
     * stream key from preferences.
     */
    public void startStream() {
        if (!isOBSInitialized.get()) {
            Toast.makeText(this, R.string.obs_not_initialized, Toast.LENGTH_SHORT).show();
            return;
        }

        String serverUrl = obsPrefs.getString("stream_server", "");
        String streamKey = obsPrefs.getString("stream_key", "");

        if (serverUrl.isEmpty() || streamKey.isEmpty()) {
            Toast.makeText(this, R.string.configure_stream_first, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, StreamSetupActivity.class));
            return;
        }

        obsExecutor.execute(() -> {
            try {
                Log.i(TAG, "Starting stream to " + serverUrl);

                // ── TODO: Replace with actual OBS stream start ──
                isStreaming.set(true);
                droppedFrames = 0;
                currentBitrate = obsPrefs.getInt("bitrate", 2500);
                currentFps = obsPrefs.getInt("fps", 30);
                streamStartTimeMs = System.currentTimeMillis();

                runOnUiThread(() -> {
                    binding.streamButton.setText(R.string.stop_stream);
                    binding.streamButton.setBackgroundColor(
                            getColor(R.color.color_stop_red));
                    binding.liveIndicator.setVisibility(View.VISIBLE);
                    binding.statusText.setText(R.string.streaming);
                    startStatsUpdates();
                    Toast.makeText(this, R.string.stream_started, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to start stream", e);
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.stream_start_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Stops the active live stream.
     */
    public void stopStream() {
        if (!isStreaming.get()) return;

        obsExecutor.execute(() -> {
            try {
                Log.i(TAG, "Stopping stream");

                // ── TODO: Replace with actual OBS stream stop ──
                isStreaming.set(false);

                runOnUiThread(() -> {
                    binding.streamButton.setText(R.string.start_stream);
                    binding.streamButton.setBackgroundColor(
                            getColor(R.color.color_start_green));
                    binding.liveIndicator.setVisibility(View.GONE);
                    binding.statusText.setText(R.string.obs_ready);
                    binding.streamTimerText.setText("00:00:00");
                    Toast.makeText(this, R.string.stream_stopped, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop stream", e);
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // Recording Controls
    // ══════════════════════════════════════════════════════════════════

    /**
     * Starts local recording.
     */
    public void startRecord() {
        if (!isOBSInitialized.get()) {
            Toast.makeText(this, R.string.obs_not_initialized, Toast.LENGTH_SHORT).show();
            return;
        }

        obsExecutor.execute(() -> {
            try {
                Log.i(TAG, "Starting recording");

                // ── TODO: Replace with actual OBS record start ──
                isRecording.set(true);

                runOnUiThread(() -> {
                    binding.recordButton.setText(R.string.stop_record);
                    binding.recordButton.setBackgroundColor(
                            getColor(R.color.color_stop_red));
                    binding.recordIndicator.setVisibility(View.VISIBLE);
                    Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to start recording", e);
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.record_start_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Stops the active recording.
     */
    public void stopRecord() {
        if (!isRecording.get()) return;

        obsExecutor.execute(() -> {
            try {
                Log.i(TAG, "Stopping recording");

                // ── TODO: Replace with actual OBS record stop ──
                isRecording.set(false);

                runOnUiThread(() -> {
                    binding.recordButton.setText(R.string.start_record);
                    binding.recordButton.setBackgroundColor(
                            getColor(R.color.color_start_green));
                    binding.recordIndicator.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.recording_stopped, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop recording", e);
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // Statistics
    // ══════════════════════════════════════════════════════════════════

    private final Runnable statsRunnable = new Runnable() {
        @Override
        public void run() {
            updateStats();
            statsHandler.postDelayed(this, STATS_UPDATE_INTERVAL_MS);
        }
    };

    private void startStatsUpdates() {
        stopStatsUpdates();
        statsHandler.post(statsRunnable);
    }

    private void stopStatsUpdates() {
        statsHandler.removeCallbacks(statsRunnable);
    }

    /**
     * Reads simulated / real stats and updates the UI.  Called every
     * second while the activity is active and streaming or recording.
     */
    public void updateStats() {
        if (!isStreaming.get() && !isRecording.get()) {
            return;
        }

        // ── Timer ──
        if (isStreaming.get() && streamStartTimeMs > 0) {
            long elapsed = System.currentTimeMillis() - streamStartTimeMs;
            binding.streamTimerText.setText(formatDuration(elapsed));
        }

        // ── Bitrate (simulated fluctuation) ──
        if (isStreaming.get()) {
            int baseBitrate = obsPrefs.getInt("bitrate", 2500);
            // Simulate ±10% fluctuation
            float fluctuation = 0.9f + (float) (Math.random() * 0.2);
            currentBitrate = (int) (baseBitrate * fluctuation);
            binding.bitrateText.setText(String.format(Locale.getDefault(),
                    "%d kbps", currentBitrate));
        }

        // ── FPS ──
        if (isStreaming.get()) {
            int targetFps = obsPrefs.getInt("fps", 30);
            currentFps = targetFps - (int) (Math.random() * 3); // Occasional dropped frame
            binding.fpsText.setText(String.format(Locale.getDefault(), "%d FPS", currentFps));
        }

        // ── Dropped frames ──
        if (isStreaming.get()) {
            // Accumulate slowly
            if (Math.random() < 0.15) {
                droppedFrames++;
            }
            binding.droppedFramesText.setText(String.valueOf(droppedFrames));
        }

        // ── CPU / Memory (simulated) ──
        if (binding.cpuUsageText != null) {
            int cpuUsage = 15 + (int) (Math.random() * 40);
            binding.cpuUsageText.setText(cpuUsage + "%");
        }
        if (binding.memoryUsageText != null) {
            long mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            binding.memoryUsageText.setText(formatFileSize(mem));
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Add Source
    // ══════════════════════════════════════════════════════════════════

    /**
     * Shows a dialog to add a new source (camera, display capture,
     * image, audio input, etc.).
     */
    public void addSource() {
        showAddSourceDialog();
    }

    private void showAddSourceDialog() {
        String[] sourceTypes = {
                "Camera Capture",
                "Display Capture",
                "Window Capture",
                "Image Source",
                "Image Slideshow",
                "Audio Input Capture",
                "Audio Output Capture",
                "Text (GDI+)",
                "Color Source",
                "Browser Source"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_source)
                .setItems(sourceTypes, (dialog, which) -> {
                    String selected = sourceTypes[which];
                    showSourceNameDialog(selected);
                })
                .show();
    }

    private void showSourceNameDialog(String sourceType) {
        EditText input = new EditText(this);
        input.setHint(R.string.source_name_hint);

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.name_source, sourceType))
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = sourceType;
                    addSourceToOBS(name, sourceType);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Adds a new source to the OBS scene.
     *
     * @param name       the user-visible name
     * @param sourceType the source type identifier
     */
    public void addSourceToOBS(@NonNull String name, @NonNull String sourceType) {
        String type;
        switch (sourceType) {
            case "Camera Capture":
                type = "camera";
                break;
            case "Display Capture":
                type = "display";
                break;
            case "Audio Input Capture":
                type = "mic";
                break;
            case "Audio Output Capture":
                type = "audio";
                break;
            default:
                type = "custom";
                break;
        }

        SourceItem newItem = new SourceItem(name, type, false, true);
        sourceAdapter.addItem(newItem);

        Log.i(TAG, "Source added: " + name + " (" + type + ")");
        Toast.makeText(this, getString(R.string.source_added, name), Toast.LENGTH_SHORT).show();

        // ── TODO: Actually create the OBS source via JNI ──
    }

    // ══════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════

    private void updatePresetDisplay() {
        String resolution = obsPrefs.getString("resolution", "1280x720");
        int bitrate = obsPrefs.getInt("bitrate", 2500);
        int fps = obsPrefs.getInt("fps", 30);

        binding.resolutionText.setText(resolution);
        binding.bitrateText.setText(bitrate + " kbps");
        binding.fpsText.setText(fps + " FPS");
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        long hh = seconds / 3600;
        long mm = (seconds % 3600) / 60;
        long ss = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hh, mm, ss);
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format(Locale.getDefault(), "%.1f %s",
                bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    // ══════════════════════════════════════════════════════════════════
    // Source Adapter
    // ══════════════════════════════════════════════════════════════════

    /**
     * A lightweight RecyclerView adapter for scene and source items.
     */
    private static class SourceAdapter extends RecyclerView.Adapter<SourceAdapter.ViewHolder> {

        private final List<SourceItem> items;
        private final OnSourceClickListener listener;

        interface OnSourceClickListener {
            void onSourceClick(SourceItem item, int position);
        }

        SourceAdapter(@NonNull List<SourceItem> items,
                      @NonNull OnSourceClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        public void addItem(@NonNull SourceItem item) {
            items.add(item);
            notifyItemInserted(items.size() - 1);
        }

        public void removeItem(int position) {
            if (position >= 0 && position < items.size()) {
                items.remove(position);
                notifyItemRemoved(position);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_source, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SourceItem item = items.get(position);
            holder.nameTextView.setText(item.name);
            holder.typeTextView.setText(item.type);

            // Highlight active item
            holder.itemView.setAlpha(item.active ? 1.0f : 0.6f);

            holder.itemView.setOnClickListener(v -> {
                // Toggle active state
                for (int i = 0; i < items.size(); i++) {
                    items.get(i).active = (i == position);
                }
                notifyDataSetChanged();
                listener.onSourceClick(item, position);
            });

            // Visibility toggle via the icon
            holder.visibilityToggle.setOnClickListener(v -> {
                // In production: toggle source visibility in OBS
                boolean visible = !holder.visibilityToggle.isSelected();
                holder.visibilityToggle.setSelected(visible);
                holder.visibilityToggle.setAlpha(visible ? 1.0f : 0.4f);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final TextView nameTextView;
            final TextView typeTextView;
            final ImageView visibilityToggle;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                nameTextView = itemView.findViewById(R.id.source_name);
                typeTextView = itemView.findViewById(R.id.source_type);
                visibilityToggle = itemView.findViewById(R.id.source_visibility_toggle);
            }
        }
    }
}

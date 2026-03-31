/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dualspace.obs.R;
import com.dualspace.obs.databinding.FragmentObsBinding;
import com.dualspace.obs.ui.adapters.RecordingAdapter;
import com.dualspace.obs.ui.adapters.RecordingAdapter.RecordingItem;
import com.dualspace.obs.ui.activities.OBSStudioActivity;
import com.dualspace.obs.ui.activities.StreamSetupActivity;
import com.dualspace.obs.ui.activities.RecordingListActivity;
import com.dualspace.obs.util.StreamConfig;
import com.dualspace.obs.util.PrefsHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * OBSFragment serves as the dashboard for OBS streaming &amp; recording
 * features inside the main navigation.  It provides:
 * <ul>
 *   <li>A quick stream-setup card (platform, server, key preview)</li>
 *   <li>Live status indicators (streaming / recording state)</li>
 *   <li>Quick action buttons: Start Stream, Start Recording, Open OBS Studio</li>
 *   <li>A preview of recent recordings with play / delete actions</li>
 *   <li>Real-time stats preview (bitrate, FPS, duration)</li>
 * </ul>
 *
 * <p>Uses {@link FragmentObsBinding} (ViewBinding) for type-safe view access.</p>
 */
public class OBSFragment extends Fragment {

    private static final String TAG = "OBSFragment";

    // ─── Stats refresh interval ────────────────────────────────────────
    private static final long STATS_REFRESH_MS = TimeUnit.SECONDS.toMillis(1);

    // ─── Binding ───────────────────────────────────────────────────────
    private FragmentObsBinding binding;

    // ─── Adapter ───────────────────────────────────────────────────────
    private RecordingAdapter recentRecordingsAdapter;

    // ─── State ─────────────────────────────────────────────────────────
    private boolean isStreaming = false;
    private boolean isRecording = false;
    private long recordingStartTimeMs = 0L;
    private long streamStartTimeMs = 0L;

    // ─── Stats update ──────────────────────────────────────────────────
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private int currentBitrate = 0;   // kbps
    private float currentFps = 0f;

    // ══════════════════════════════════════════════════════════════════
    // Fragment Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentObsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupStreamSetupCard();
        setupStatusIndicators();
        setupQuickActions();
        setupStatsPreview();
        setupRecentRecordings();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStreamConfig();
        updateStatusUI();
        startStatsRefresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopStatsRefresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ══════════════════════════════════════════════════════════════════
    // Stream Setup Card
    // ══════════════════════════════════════════════════════════════════

    private void setupStreamSetupCard() {
        // Click to open full stream setup
        binding.cardStreamSetup.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), StreamSetupActivity.class);
            startActivity(intent);
        });

        binding.btnEditStreamSetup.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), StreamSetupActivity.class);
            startActivity(intent);
        });

        refreshStreamConfig();
    }

    /**
     * Reads the saved stream configuration and updates the summary views.
     */
    private void refreshStreamConfig() {
        if (getContext() == null || binding == null) return;

        // Read saved stream configuration from SharedPreferences
        PrefsHelper prefs = PrefsHelper.getInstance(requireContext());
        String platform = prefs.getString("stream_platform", "");
        if (platform == null || platform.isEmpty()) {
            platform = "Not configured";
        }
        binding.tvStreamPlatform.setText(platform);

        String server = prefs.getString("stream_server_url", "");
        if (server != null && !server.isEmpty()) {
            binding.tvStreamServer.setText(server);
            binding.tvStreamServer.setVisibility(View.VISIBLE);
        } else {
            binding.tvStreamServer.setVisibility(View.GONE);
        }

        // Show masked stream key
        String key = prefs.getString("stream_key", "");
        if (key != null && !key.isEmpty()) {
            String masked = key.length() > 8
                    ? key.substring(0, 4) + "••••••••" + key.substring(key.length() - 4)
                    : "••••••••";
            binding.tvStreamKeyPreview.setText(masked);
            binding.tvStreamKeyPreview.setVisibility(View.VISIBLE);
        } else {
            binding.tvStreamKeyPreview.setVisibility(View.GONE);
        }

        // Quality preset
        String quality = prefs.getString("stream_quality", "1080p");
        if (quality != null && !quality.isEmpty()) {
            binding.tvStreamQuality.setText(quality);
        } else {
            binding.tvStreamQuality.setText("1080p");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Status Indicators
    // ══════════════════════════════════════════════════════════════════

    private void setupStatusIndicators() {
        updateStatusUI();
    }

    private void updateStatusUI() {
        if (binding == null) return;

        // Streaming indicator
        if (isStreaming) {
            binding.ivStatusStreaming.setImageResource(R.drawable.bg_status_streaming);
            binding.tvStatusStreaming.setText(R.string.stream_live);
            binding.tvStatusStreaming.setTextColor(
                    requireContext().getColor(R.color.text_streaming));
            binding.btnStartStream.setText(R.string.btn_stop_stream);
            binding.btnStartStream.setIconTintResource(R.color.status_recording_red);
        } else {
            binding.ivStatusStreaming.setImageResource(R.drawable.bg_status_streaming);
            binding.ivStatusStreaming.setAlpha(0.3f);
            binding.tvStatusStreaming.setText(R.string.stream_offline);
            binding.tvStatusStreaming.setTextColor(
                    requireContext().getColor(R.color.text_hint));
            binding.btnStartStream.setText(R.string.btn_start_stream);
            binding.btnStartStream.setIconTintResource(null);
        }

        // Recording indicator
        if (isRecording) {
            binding.ivStatusRecording.setImageResource(R.drawable.bg_status_recording);
            binding.tvStatusRecording.setText(R.string.rec_indicator);
            binding.tvStatusRecording.setTextColor(
                    requireContext().getColor(R.color.text_recording));
            binding.btnStartRecording.setText(R.string.btn_stop_recording);
            binding.btnStartRecording.setIconTintResource(R.color.status_recording_red);
        } else {
            binding.ivStatusRecording.setImageResource(R.drawable.bg_status_recording);
            binding.ivStatusRecording.setAlpha(0.3f);
            binding.tvStatusRecording.setText(R.string.stream_offline);
            binding.tvStatusRecording.setTextColor(
                    requireContext().getColor(R.color.text_hint));
            binding.btnStartRecording.setText(R.string.btn_start_recording);
            binding.btnStartRecording.setIconTintResource(null);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Quick Actions
    // ══════════════════════════════════════════════════════════════════

    private void setupQuickActions() {
        // Start / Stop Stream
        binding.btnStartStream.setOnClickListener(v -> {
            if (isStreaming) {
                stopStreaming();
            } else {
                startStreaming();
            }
        });

        // Start / Stop Recording
        binding.btnStartRecording.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });

        // Open OBS Studio
        binding.btnOpenStudio.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), OBSStudioActivity.class);
            startActivity(intent);
        });
    }

    private void startStreaming() {
        isStreaming = true;
        streamStartTimeMs = System.currentTimeMillis();
        updateStatusUI();
        Toast.makeText(requireContext(), R.string.toast_stream_started,
                Toast.LENGTH_SHORT).show();
    }

    private void stopStreaming() {
        isStreaming = false;
        updateStatusUI();
        Toast.makeText(requireContext(), R.string.toast_stream_stopped,
                Toast.LENGTH_SHORT).show();
    }

    private void startRecording() {
        isRecording = true;
        recordingStartTimeMs = System.currentTimeMillis();
        updateStatusUI();
        Toast.makeText(requireContext(), R.string.toast_recording_started,
                Toast.LENGTH_SHORT).show();
    }

    private void stopRecording() {
        isRecording = false;
        updateStatusUI();
        Toast.makeText(requireContext(), "Recording saved",
                Toast.LENGTH_SHORT).show();
    }

    // ══════════════════════════════════════════════════════════════════
    // Stats Preview
    // ══════════════════════════════════════════════════════════════════

    private void setupStatsPreview() {
        binding.cardStats.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "Open OBS Studio for detailed stats",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshStats() {
        if (binding == null || getContext() == null) return;

        // Bitrate
        if (isStreaming || isRecording) {
            // In production, read actual values from the engine / encoder
            currentBitrate = 4500; // placeholder
            currentFps = 30.0f;   // placeholder
        } else {
            currentBitrate = 0;
            currentFps = 0f;
        }

        binding.tvStatBitrate.setText(getString(R.string.stat_bitrate, currentBitrate));
        binding.tvStatFps.setText(getString(R.string.stat_framerate, currentFps));

        // Duration
        long activeStart = isStreaming ? streamStartTimeMs
                : isRecording ? recordingStartTimeMs
                : 0L;

        if (activeStart > 0) {
            long elapsed = System.currentTimeMillis() - activeStart;
            long seconds = elapsed / 1000;
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            long secs = seconds % 60;
            binding.tvStatDuration.setText(
                    getString(R.string.stat_duration, hours, minutes, secs));
        } else {
            binding.tvStatDuration.setText(
                    getString(R.string.stat_duration, 0, 0, 0));
        }
    }

    private void startStatsRefresh() {
        statsHandler.postDelayed(statsRefreshRunnable, STATS_REFRESH_MS);
    }

    private void stopStatsRefresh() {
        statsHandler.removeCallbacks(statsRefreshRunnable);
    }

    private final Runnable statsRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStats();
            statsHandler.postDelayed(this, STATS_REFRESH_MS);
        }
    };

    // ══════════════════════════════════════════════════════════════════
    // Recent Recordings
    // ══════════════════════════════════════════════════════════════════

    private void setupRecentRecordings() {
        recentRecordingsAdapter = new RecordingAdapter(requireContext());
        recentRecordingsAdapter.setOnRecordingClickListener(item -> {
            // Open the recording for playback
        });
        recentRecordingsAdapter.setOnRecordingDeleteListener((item, position) -> {
            recentRecordingsAdapter.removeRecording(position);
            updateRecordingsEmptyState();
            Toast.makeText(requireContext(),
                    "Deleted: " + item.fileName, Toast.LENGTH_SHORT).show();
        });

        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        layoutManager.setOrientation(LinearLayoutManager.HORIZONTAL);

        binding.rvRecentRecordings.setLayoutManager(layoutManager);
        binding.rvRecentRecordings.setAdapter(recentRecordingsAdapter);
        binding.rvRecentRecordings.setHasFixedSize(true);
        binding.rvRecentRecordings.setItemAnimator(null);

        // Load sample data
        loadRecentRecordings();

        // See all button
        binding.btnSeeAllRecordings.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), RecordingListActivity.class);
            startActivity(intent);
        });
    }

    private void loadRecentRecordings() {
        // In production, load from Room database
        List<RecordingItem> recordings = new ArrayList<>();
        recentRecordingsAdapter.setRecordings(recordings);
        updateRecordingsEmptyState();
    }

    private void updateRecordingsEmptyState() {
        if (recentRecordingsAdapter.getItemCount() == 0) {
            binding.tvNoRecordings.setVisibility(View.VISIBLE);
            binding.rvRecentRecordings.setVisibility(View.GONE);
        } else {
            binding.tvNoRecordings.setVisibility(View.GONE);
            binding.rvRecentRecordings.setVisibility(View.VISIBLE);
        }
    }
}

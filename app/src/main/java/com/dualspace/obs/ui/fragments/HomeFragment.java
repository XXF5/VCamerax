/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.fragments;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dualspace.obs.BuildConfig;
import com.dualspace.obs.R;
import com.dualspace.obs.databinding.FragmentHomeBinding;
import com.dualspace.obs.ui.adapters.CloneListAdapter;
import com.dualspace.obs.ui.activities.CloneSetupActivity;
import com.dualspace.obs.ui.activities.OBSStudioActivity;
import com.dualspace.obs.ui.activities.StreamSetupActivity;
import com.dualspace.obs.ui.activities.VirtualCameraActivity;
import com.dualspace.obs.util.DeviceUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HomeFragment is the primary landing screen shown when the user first
 * opens the app.  It provides a dashboard overview featuring:
 * <ul>
 *   <li>A welcome card showing the app name and version</li>
 *   <li>Quick-action buttons for core features (Clone, Record, Stream, Camera)</li>
 *   <li>A horizontal RecyclerView of recently cloned apps</li>
 *   <li>A system stats card (RAM, Storage, CPU cores)</li>
 *   <li>A rotating tips card with helpful guidance</li>
 * </ul>
 *
 * <p>This fragment uses {@link FragmentHomeBinding} (ViewBinding) for
 * view access and follows the standard Android Fragment lifecycle.</p>
 */
public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";

    // ─── Tips rotation interval ────────────────────────────────────────
    private static final long TIPS_ROTATION_MS = TimeUnit.SECONDS.toMillis(8);

    // ─── Binding ───────────────────────────────────────────────────────
    private FragmentHomeBinding binding;

    // ─── Adapters ──────────────────────────────────────────────────────
    private CloneListAdapter recentClonesAdapter;

    // ─── Tips ──────────────────────────────────────────────────────────
    private final String[] tips = {
            "Tip: Long-press a cloned app to access advanced options like duplicate and export.",
            "Tip: Enable the floating panel in Settings to control recording from any app.",
            "Tip: Use hardware encoding for better stream performance on supported devices.",
            "Tip: You can stream to multiple platforms simultaneously with Custom RTMP.",
            "Tip: Check available storage before cloning large apps like games or social media.",
            "Tip: Enable adaptive bitrate to automatically adjust quality on poor networks.",
    };
    private int currentTipIndex = 0;
    private final Handler tipsHandler = new Handler(Looper.getMainLooper());

    // ─── System stats refresh ──────────────────────────────────────────
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private static final long STATS_REFRESH_MS = TimeUnit.SECONDS.toMillis(5);

    // ══════════════════════════════════════════════════════════════════
    // Fragment Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupWelcomeCard();
        setupQuickActions();
        setupRecentClones();
        setupSystemStats();
        setupTipsCard();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshSystemStats();
        startTipsRotation();
        startStatsRefresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopTipsRotation();
        stopStatsRefresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ══════════════════════════════════════════════════════════════════
    // Welcome Card
    // ══════════════════════════════════════════════════════════════════

    private void setupWelcomeCard() {
        binding.tvWelcomeTitle.setText(String.format(Locale.US,
                "Welcome to %s", getString(R.string.app_name)));
        binding.tvWelcomeVersion.setText(String.format(Locale.US,
                "Version %s", BuildConfig.VERSION_NAME));
    }

    // ══════════════════════════════════════════════════════════════════
    // Quick Actions
    // ══════════════════════════════════════════════════════════════════

    private void setupQuickActions() {
        // Clone App
        binding.btnQuickClone.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), CloneSetupActivity.class);
            startActivity(intent);
        });

        // Start Recording
        binding.btnQuickRecord.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), OBSStudioActivity.class);
            intent.setAction("com.dualspace.obs.action.START_RECORDING");
            startActivity(intent);
        });

        // Start Streaming
        binding.btnQuickStream.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), StreamSetupActivity.class);
            startActivity(intent);
        });

        // Virtual Camera
        binding.btnQuickCamera.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), VirtualCameraActivity.class);
            startActivity(intent);
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // Recent Clones
    // ══════════════════════════════════════════════════════════════════

    private void setupRecentClones() {
        recentClonesAdapter = new CloneListAdapter();
        recentClonesAdapter.setOnItemClickListener(item -> {
            Toast.makeText(requireContext(),
                    "Launch: " + item.cloneName, Toast.LENGTH_SHORT).show();
        });

        binding.rvRecentClones.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        binding.rvRecentClones.setAdapter(recentClonesAdapter);
        binding.rvRecentClones.setHasFixedSize(true);
        binding.rvRecentClones.setItemAnimator(null);

        // Load sample data – in a real app this would come from Room / LiveData
        loadRecentClones();

        // "See All" link
        binding.tvSeeAllClones.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "Opening Apps tab…", Toast.LENGTH_SHORT).show();
            if (getActivity() instanceof com.dualspace.obs.ui.activities.MainActivity) {
                // Could use a shared ViewModel or callback to switch tabs
            }
        });
    }

    /**
     * Loads recently cloned apps. In production this reads from Room via
     * a {@link androidx.lifecycle.LiveData} observer.
     */
    private void loadRecentClones() {
        List<CloneListAdapter.CloneItem> sampleItems = new ArrayList<>();
        recentClonesAdapter.setClones(sampleItems);

        if (sampleItems.isEmpty()) {
            binding.tvNoRecentClones.setVisibility(View.VISIBLE);
            binding.rvRecentClones.setVisibility(View.GONE);
        } else {
            binding.tvNoRecentClones.setVisibility(View.GONE);
            binding.rvRecentClones.setVisibility(View.VISIBLE);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // System Stats
    // ══════════════════════════════════════════════════════════════════

    private void setupSystemStats() {
        refreshSystemStats();

        binding.cardSystemStats.setOnClickListener(v -> {
            String summary = DeviceUtils.getDeviceInfoSummary(requireContext());
            Toast.makeText(requireContext(), summary, Toast.LENGTH_LONG).show();
        });
    }

    private void refreshSystemStats() {
        if (getContext() == null) return;

        ActivityManager activityManager = (ActivityManager)
                requireContext().getSystemService(Context.ACTIVITY_SERVICE);

        // RAM
        if (activityManager != null) {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            long totalRam = memoryInfo.totalMem;
            long availableRam = memoryInfo.availMem;
            long usedRam = totalRam - availableRam;

            binding.tvRamUsed.setText(Formatter.formatFileSize(requireContext(), usedRam));
            binding.tvRamTotal.setText(String.format(Locale.US, "/ %s",
                    Formatter.formatFileSize(requireContext(), totalRam)));

            int ramPercent = totalRam > 0 ? (int) ((usedRam * 100L) / totalRam) : 0;
            binding.progressRam.setProgress(ramPercent);
        }

        // Storage
        Map<String, Long> storageInfo = DeviceUtils.getStorageInfo();
        long usedStorage = storageInfo.getOrDefault("used", 0L);
        long totalStorage = storageInfo.getOrDefault("total", 0L);

        binding.tvStorageUsed.setText(Formatter.formatFileSize(requireContext(), usedStorage));
        binding.tvStorageTotal.setText(String.format(Locale.US, "/ %s",
                Formatter.formatFileSize(requireContext(), totalStorage)));

        int storagePercent = totalStorage > 0 ? (int) ((usedStorage * 100L) / totalStorage) : 0;
        binding.progressStorage.setProgress(storagePercent);

        // CPU Cores
        int cores = DeviceUtils.getCpuCores();
        binding.tvCpuCores.setText(String.format(Locale.US, "%d cores", cores));
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
            refreshSystemStats();
            statsHandler.postDelayed(this, STATS_REFRESH_MS);
        }
    };

    // ══════════════════════════════════════════════════════════════════
    // Tips Card
    // ══════════════════════════════════════════════════════════════════

    private void setupTipsCard() {
        if (tips.length > 0) {
            binding.tvTipText.setText(tips[currentTipIndex]);
        }

        binding.btnNextTip.setOnClickListener(v -> showNextTip());
    }

    private void showNextTip() {
        currentTipIndex = (currentTipIndex + 1) % tips.length;
        if (binding != null && tips.length > 0) {
            binding.tvTipText.setText(tips[currentTipIndex]);
        }
    }

    private void startTipsRotation() {
        tipsHandler.postDelayed(tipsRotationRunnable, TIPS_ROTATION_MS);
    }

    private void stopTipsRotation() {
        tipsHandler.removeCallbacks(tipsRotationRunnable);
    }

    private final Runnable tipsRotationRunnable = new Runnable() {
        @Override
        public void run() {
            showNextTip();
            tipsHandler.postDelayed(this, TIPS_ROTATION_MS);
        }
    };
}

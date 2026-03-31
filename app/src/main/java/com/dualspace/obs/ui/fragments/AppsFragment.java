/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.fragments;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.MenuItemCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dualspace.obs.R;
import com.dualspace.obs.databinding.FragmentAppsBinding;
import com.dualspace.obs.ui.adapters.AppListAdapter;
import com.dualspace.obs.ui.adapters.AppListAdapter.AppInfo;
import com.dualspace.obs.ui.activities.CloneSetupActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AppsFragment displays the full list of installed (third-party) apps on
 * the device with search filtering, sort options, and a clone button on
 * each row.  The user can click an item to see app details or tap the
 * clone icon to start the cloning workflow.
 *
 * <p>Data is loaded synchronously from {@link PackageManager} for
 * simplicity; a production build should move this to a background
 * thread or use {@link androidx.lifecycle.ViewModel} + LiveData.</p>
 */
public class AppsFragment extends Fragment
        implements SearchView.OnQueryTextListener,
        AppListAdapter.OnItemClickListener {

    private static final String TAG = "AppsFragment";

    // ─── Binding ───────────────────────────────────────────────────────
    private FragmentAppsBinding binding;

    // ─── Adapter ───────────────────────────────────────────────────────
    private AppListAdapter adapter;

    // ─── Full list (unfiltered) ────────────────────────────────────────
    private final List<AppInfo> allApps = new ArrayList<>();

    // ══════════════════════════════════════════════════════════════════
    // Fragment Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAppsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupRecyclerView();
        setupFAB();
        loadInstalledApps();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.menu_app_list, menu);

        MenuItem searchItem = menu.findItem(R.id.action_search_apps);
        SearchView searchView = (SearchView) MenuItemCompat.getActionView(searchItem);
        if (searchView != null) {
            searchView.setQueryHint(getString(R.string.search_apps));
            searchView.setOnQueryTextListener(this);
            searchView.setIconifiedByDefault(true);
        }

        MenuItem sortItem = menu.findItem(R.id.action_sort);
        if (sortItem != null) {
            sortItem.setOnMenuItemClickListener(item -> {
                // Cycle through sort modes
                showSortDialog();
                return true;
            });
        }

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_sort) {
            showSortDialog();
            return true;
        } else if (id == R.id.action_show_system) {
            loadInstalledApps(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh list in case a clone was just created or removed
        loadInstalledApps();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ══════════════════════════════════════════════════════════════════
    // SearchView Callbacks
    // ══════════════════════════════════════════════════════════════════

    @Override
    public boolean onQueryTextSubmit(String query) {
        return false; // handled by filter on text change
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        adapter.filterApps(newText);
        updateEmptyState();
        return true;
    }

    // ══════════════════════════════════════════════════════════════════
    // AppListAdapter.OnItemClickListener
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void onItemClick(@NonNull AppInfo app) {
        showAppDetails(app);
    }

    // ══════════════════════════════════════════════════════════════════
    // Setup
    // ══════════════════════════════════════════════════════════════════

    private void setupRecyclerView() {
        adapter = new AppListAdapter();
        adapter.setOnItemClickListener(this);
        adapter.setOnItemClickListener(app -> {
            showAppDetails(app);
        });

        // Clone button per item is handled in the adapter's bind,
        // but we also set a long-click for context menu behavior
        adapter.setOnItemLongClickListener(app -> {
            showAppContextMenu(app);
            return true;
        });

        binding.rvAppList.setLayoutManager(
                new LinearLayoutManager(requireContext()));
        binding.rvAppList.setAdapter(adapter);
        binding.rvAppList.setHasFixedSize(true);

        // Scroll listener to hide FAB on scroll
        binding.rvAppList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && binding.fabClone.isShown()) {
                    binding.fabClone.hide();
                } else if (dy < 0 && !binding.fabClone.isShown()) {
                    binding.fabClone.show();
                }
            }
        });
    }

    private void setupFAB() {
        binding.fabClone.setOnClickListener(v -> {
            // Open app picker / clone setup with multi-select
            Intent intent = new Intent(requireContext(), CloneSetupActivity.class);
            startActivity(intent);
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // Data Loading
    // ══════════════════════════════════════════════════════════════════

    /**
     * Loads the list of installed third-party apps from the system
     * PackageManager.  Filters out system apps by default.
     *
     * @param includeSystem if {@code true}, system apps are also included
     */
    private void loadInstalledApps(boolean includeSystem) {
        if (getContext() == null) return;

        PackageManager pm = requireContext().getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);

        allApps.clear();
        for (ApplicationInfo info : installed) {
            // Skip system apps unless requested
            if (!includeSystem
                    && (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }

            String appName = info.loadLabel(pm).toString();
            if (TextUtils.isEmpty(appName)) {
                appName = info.packageName;
            }

            long sizeBytes = 0L;
            try {
                android.content.pm.PackageStats stats =
                        new android.content.pm.PackageStats(info.packageName);
                // PackageStats constructor is deprecated/hidden on newer APIs;
                // use a reasonable fallback
                java.io.File apkFile = new java.io.File(info.sourceDir);
                sizeBytes = apkFile.length();
            } catch (Exception e) {
                // ignore
            }

            allApps.add(new AppInfo(
                    info.packageName,
                    appName,
                    getVersionName(pm, info.packageName),
                    sizeBytes,
                    "" // icon path – not used; icon loaded directly
            ));
        }

        // Sort alphabetically by default
        Collections.sort(allApps, (a, b) ->
                a.appName.compareToIgnoreCase(b.appName));

        adapter.setApps(allApps);
        updateEmptyState();
    }

    /**
     * Overload that excludes system apps by default.
     */
    private void loadInstalledApps() {
        loadInstalledApps(false);
    }

    // ══════════════════════════════════════════════════════════════════
    // UI Helpers
    // ══════════════════════════════════════════════════════════════════

    private void updateEmptyState() {
        if (adapter.getItemCount() == 0) {
            binding.layoutEmptyState.setVisibility(View.VISIBLE);
            binding.rvAppList.setVisibility(View.GONE);
            binding.tvEmptyTitle.setText(R.string.empty_state_title);
            binding.tvEmptyDescription.setText(R.string.no_cloned_apps_desc);
        } else {
            binding.layoutEmptyState.setVisibility(View.GONE);
            binding.rvAppList.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Shows app details in a Material dialog (package name, version,
     * size, installed source).
     */
    private void showAppDetails(@NonNull AppInfo app) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(app.appName)
                .setMessage(String.format("Package: %s\nVersion: %s\nSize: %s",
                        app.packageName,
                        app.versionName,
                        formatSize(app.sizeBytes)))
                .setPositiveButton(R.string.btn_clone, (dialog, which) -> {
                    startClone(app);
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .setNeutralButton("App Info", (dialog, which) -> {
                    openAppSystemSettings(app.packageName);
                })
                .show();
    }

    /**
     * Shows a context menu-like dialog for the given app.
     */
    private void showAppContextMenu(@NonNull AppInfo app) {
        String[] options = {"Clone App", "App Details", "Open System Settings", "Share"};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(app.appName)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            startClone(app);
                            break;
                        case 1:
                            showAppDetails(app);
                            break;
                        case 2:
                            openAppSystemSettings(app.packageName);
                            break;
                        case 3:
                            shareApp(app);
                            break;
                    }
                })
                .show();
    }

    /**
     * Launches the CloneSetupActivity for the given app.
     */
    private void startClone(@NonNull AppInfo app) {
        Intent intent = new Intent(requireContext(), CloneSetupActivity.class);
        intent.putExtra(CloneSetupActivity.EXTRA_PACKAGE_NAME, app.packageName);
        intent.putExtra(CloneSetupActivity.EXTRA_APP_NAME, app.appName);
        startActivity(intent);
    }

    /**
     * Opens the system App Info settings page for the given package.
     */
    private void openAppSystemSettings(@NonNull String packageName) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + packageName));
        startActivity(intent);
    }

    /**
     * Shares a reference to the app via an Intent chooser.
     */
    private void shareApp(@NonNull AppInfo app) {
        // Share the Play Store link if possible
        String url = "https://play.google.com/store/apps/details?id=" + app.packageName;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT,
                String.format("Check out %s: %s", app.appName, url));
        startActivity(Intent.createChooser(shareIntent, "Share app"));
    }

    /**
     * Shows a sort mode picker dialog.
     */
    private void showSortDialog() {
        String[] modes = {"Name (A-Z)", "Name (Z-A)", "Size (Largest)", "Size (Smallest)"};
        AppListAdapter.SortMode[] sortModes = {
                AppListAdapter.SortMode.NAME_ASC,
                AppListAdapter.SortMode.NAME_DESC,
                AppListAdapter.SortMode.SIZE_DESC,
                AppListAdapter.SortMode.SIZE_ASC
        };

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Sort apps")
                .setItems(modes, (dialog, which) -> {
                    adapter.sortApps(sortModes[which]);
                })
                .show();
    }

    // ══════════════════════════════════════════════════════════════════
    // Utility
    // ══════════════════════════════════════════════════════════════════

    @NonNull
    private static String getVersionName(@NonNull PackageManager pm,
                                         @NonNull String packageName) {
        try {
            return pm.getPackageInfo(packageName, 0).versionName != null
                    ? pm.getPackageInfo(packageName, 0).versionName
                    : "1.0";
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0";
        }
    }

    @NonNull
    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB"};
        int idx = 0;
        double sz = bytes;
        while (sz >= 1024 && idx < units.length - 1) { sz /= 1024; idx++; }
        return String.format("%.1f %s", sz, units[idx]);
    }
}

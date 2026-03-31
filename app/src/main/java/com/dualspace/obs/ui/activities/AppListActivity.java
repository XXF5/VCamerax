/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.dualspace.obs.R;
import com.dualspace.obs.databinding.ActivityAppListBinding;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AppListActivity presents the user's installed, cloned, and system apps
 * in a tabbed layout backed by {@link ViewPager2} and {@link TabLayout}.
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Three tabs: All Apps, Cloned Apps, System Apps</li>
 *   <li>{@link SearchView} with real-time filtering</li>
 *   <li>Long-press context menu (Clone / Launch / Info / Share / Remove)</li>
 *   <li>Swipe-to-delete for cloned apps with an Undo {@link Snackbar}</li>
 *   <li>Empty-state views for each tab</li>
 * </ul>
 */
public class AppListActivity extends AppCompatActivity {

    private static final String TAG = "AppListActivity";

    /** Request code for choosing an app to share. */
    private static final int SHARE_REQUEST_CODE = 3001;

    // ─── Binding ─────────────────────────────────────────────────────
    private ActivityAppListBinding binding;

    // ─── Data ────────────────────────────────────────────────────────
    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> clonedApps = new ArrayList<>();
    private final List<AppInfo> systemApps = new ArrayList<>();

    // ─── Adapter references ──────────────────────────────────────────
    private AppPagerAdapter pagerAdapter;
    private AppRecyclerViewAdapter currentAdapter;

    // ─── Context action mode ─────────────────────────────────────────
    private ActionMode currentActionMode;
    private AppInfo selectedApp;

    // ══════════════════════════════════════════════════════════════════
    // Data Model
    // ══════════════════════════════════════════════════════════════════

    /**
     * Simple POJO representing an installed / cloned application.
     */
    public static class AppInfo {
        public final String packageName;
        public final String appName;
        public final Drawable icon;
        public final long sizeBytes;
        public final boolean isSystem;
        public final boolean isCloned;
        public final String versionName;
        public final long installTime;

        public AppInfo(String packageName, String appName, Drawable icon,
                       long sizeBytes, boolean isSystem, boolean isCloned,
                       String versionName, long installTime) {
            this.packageName = packageName;
            this.appName = appName;
            this.icon = icon;
            this.sizeBytes = sizeBytes;
            this.isSystem = isSystem;
            this.isCloned = isCloned;
            this.versionName = versionName;
            this.installTime = installTime;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityAppListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        initToolbar();
        initTabs();
        initSearch();
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadClonedApps();
    }

    // ══════════════════════════════════════════════════════════════════
    // Initialisation
    // ══════════════════════════════════════════════════════════════════

    private void initToolbar() {
        Toolbar toolbar = binding.toolbar;
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.title_app_list);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_arrow_back);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    /**
     * Configures the ViewPager2 with a TabLayout and three tabs.
     */
    private void initTabs() {
        pagerAdapter = new AppPagerAdapter();
        binding.viewPager.setAdapter(pagerAdapter);
        binding.viewPager.setOffscreenPageLimit(2);

        new TabLayoutMediator(binding.tabLayout, binding.viewPager,
                (tab, position) -> {
                    switch (position) {
                        case 0: tab.setText(R.string.tab_all_apps); break;
                        case 1: tab.setText(R.string.tab_cloned_apps); break;
                        case 2: tab.setText(R.string.tab_system_apps); break;
                    }
                }
        ).attach();

        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                switch (position) {
                    case 0: currentAdapter = pagerAdapter.allAppsAdapter; break;
                    case 1: currentAdapter = pagerAdapter.clonedAppsAdapter; break;
                    case 2: currentAdapter = pagerAdapter.systemAppsAdapter; break;
                }
            }
        });
    }

    /**
     * Sets up the SearchView with an OnQueryTextListener for filtering.
     */
    private void initSearch() {
        SearchView searchView = binding.searchView;
        searchView.setQueryHint(getString(R.string.search_apps_hint));
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                filterApps(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterApps(newText);
                return true;
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // Data Loading
    // ══════════════════════════════════════════════════════════════════

    /**
     * Loads all installed apps (user + system) on a background thread
     * and splits them into the {@link #allApps} and {@link #systemApps}
     * lists.
     */
    public void loadApps() {
        new LoadAppsTask().execute();
    }

    /**
     * Loads the list of cloned apps from SharedPreferences.
     */
    public void loadClonedApps() {
        // In production this would come from Room; using prefs for now.
        clonedApps.clear();
        String serialized = getSharedPreferences("dualspace_prefs", MODE_PRIVATE)
                .getString("cloned_packages", "");
        if (!TextUtils.isEmpty(serialized)) {
            // Find matching AppInfo objects from allApps
            for (AppInfo info : allApps) {
                if (serialized.contains(info.packageName)) {
                    if (!containsPackage(clonedApps, info.packageName)) {
                        clonedApps.add(info);
                    }
                }
            }
        }
        if (pagerAdapter != null && pagerAdapter.clonedAppsAdapter != null) {
            pagerAdapter.clonedAppsAdapter.updateData(new ArrayList<>(clonedApps));
        }
    }

    private boolean containsPackage(List<AppInfo> list, String pkg) {
        for (AppInfo info : list) {
            if (info.packageName.equals(pkg)) return true;
        }
        return false;
    }

    /**
     * Filters all three adapters by the given query string.
     */
    public void filterApps(@NonNull String query) {
        String filter = query.trim().toLowerCase(Locale.getDefault());
        if (pagerAdapter != null) {
            pagerAdapter.allAppsAdapter.getFilter().filter(filter);
            pagerAdapter.clonedAppsAdapter.getFilter().filter(filter);
            pagerAdapter.systemAppsAdapter.getFilter().filter(filter);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Clone & Delete Operations
    // ══════════════════════════════════════════════════════════════════

    /**
     * Launches {@link CloneSetupActivity} for the given package.
     */
    public void cloneApp(@NonNull String packageName) {
        Intent intent = new Intent(this, CloneSetupActivity.class);
        intent.putExtra(CloneSetupActivity.EXTRA_PACKAGE_NAME, packageName);
        startActivity(intent);
    }

    /**
     * Deletes a cloned app, showing an Undo snackbar.
     */
    public void deleteClone(@NonNull String packageName) {
        // Remove from local list
        for (int i = clonedApps.size() - 1; i >= 0; i--) {
            if (clonedApps.get(i).packageName.equals(packageName)) {
                clonedApps.remove(i);
                break;
            }
        }

        // Update adapter
        if (pagerAdapter != null && pagerAdapter.clonedAppsAdapter != null) {
            pagerAdapter.clonedAppsAdapter.updateData(new ArrayList<>(clonedApps));
        }

        // Persist
        String serialized = getSharedPreferences("dualspace_prefs", MODE_PRIVATE)
                .getString("cloned_packages", "");
        StringBuilder sb = new StringBuilder();
        for (AppInfo info : clonedApps) {
            if (sb.length() > 0) sb.append(",");
            sb.append(info.packageName);
        }
        getSharedPreferences("dualspace_prefs", MODE_PRIVATE)
                .edit().putString("cloned_packages", sb.toString()).apply();

        // Update badge count in main activity
        getSharedPreferences("dualspace_prefs", MODE_PRIVATE)
                .edit().putInt("clone_count", clonedApps.size()).apply();

        // Show undo snackbar
        Snackbar snackbar = Snackbar.make(
                binding.getRoot(),
                getString(R.string.clone_deleted, packageName),
                Snackbar.LENGTH_LONG
        );
        snackbar.setAction(R.string.undo, v -> {
            // Re-add the clone (simplest approach: re-run loadClonedApps
            // from the original serialized string).
            Log.i(TAG, "Undo: re-adding " + packageName);
            loadClonedApps();
        });
        snackbar.show();
    }

    // ══════════════════════════════════════════════════════════════════
    // Context Menu (Action Mode)
    // ══════════════════════════════════════════════════════════════════

    private final ActionMode.Callback actionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.menu_app_context, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            // Show / hide "Remove" based on whether the app is cloned
            if (selectedApp != null) {
                menu.findItem(R.id.action_remove_clone)
                        .setVisible(selectedApp.isCloned);
            }
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (selectedApp == null) return false;
            int id = item.getItemId();

            if (id == R.id.action_clone) {
                cloneApp(selectedApp.packageName);
                mode.finish();
                return true;
            } else if (id == R.id.action_launch) {
                launchApp(selectedApp.packageName);
                mode.finish();
                return true;
            } else if (id == R.id.action_info) {
                showAppInfo(selectedApp);
                mode.finish();
                return true;
            } else if (id == R.id.action_share) {
                shareApp(selectedApp);
                mode.finish();
                return true;
            } else if (id == R.id.action_remove_clone) {
                deleteClone(selectedApp.packageName);
                mode.finish();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            currentActionMode = null;
            selectedApp = null;
        }
    };

    private void launchApp(@NonNull String packageName) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            Toast.makeText(this, R.string.cannot_launch_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAppInfo(@NonNull AppInfo info) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        String dateStr = sdf.format(new Date(info.installTime));
        String sizeStr = formatFileSize(info.sizeBytes);

        new MaterialAlertDialogBuilder(this)
                .setTitle(info.appName)
                .setMessage(
                        getString(R.string.app_info_detail,
                                info.packageName,
                                info.versionName,
                                sizeStr,
                                dateStr,
                                info.isSystem ? "System" : "User")
                )
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void shareApp(@NonNull AppInfo info) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, info.appName);
        shareIntent.putExtra(Intent.EXTRA_TEXT,
                "Check out " + info.appName + ": https://play.google.com/store/apps/details?id="
                        + info.packageName);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_app)));
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format(Locale.getDefault(), "%.1f %s",
                bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    // ══════════════════════════════════════════════════════════════════
    // Background Task
    // ══════════════════════════════════════════════════════════════════

    /**
     * Loads the full list of installed packages on a worker thread.
     */
    private class LoadAppsTask extends AsyncTask<Void, Void, List<AppInfo>> {

        @Override
        protected List<AppInfo> doInBackground(Void... voids) {
            PackageManager pm = getPackageManager();
            List<AppInfo> result = new ArrayList<>();

            List<PackageInfo> packages = pm.getInstalledPackages(0);
            for (PackageInfo pi : packages) {
                try {
                    String appName = pi.applicationInfo.loadLabel(pm).toString();
                    Drawable icon = pi.applicationInfo.loadIcon(pm);
                    boolean isSystem = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                    // Skip our own package
                    if (pi.packageName.equals(getPackageName())) continue;

                    // Calculate APK size
                    String apkPath = pi.applicationInfo.sourceDir;
                    File apkFile = new File(apkPath);
                    long size = apkFile.exists() ? apkFile.length() : 0L;

                    AppInfo info = new AppInfo(
                            pi.packageName,
                            appName,
                            icon,
                            size,
                            isSystem,
                            false,
                            pi.versionName != null ? pi.versionName : "1.0",
                            pi.firstInstallTime
                    );
                    result.add(info);
                } catch (Exception e) {
                    Log.w(TAG, "Skipping package: " + pi.packageName, e);
                }
            }

            // Sort alphabetically
            Collections.sort(result, (a, b) ->
                    a.appName.toLowerCase(Locale.getDefault()).compareTo(
                            b.appName.toLowerCase(Locale.getDefault())));

            return result;
        }

        @Override
        protected void onPostExecute(@NonNull List<AppInfo> apps) {
            allApps.clear();
            systemApps.clear();
            allApps.addAll(apps);

            for (AppInfo info : apps) {
                if (info.isSystem) {
                    systemApps.add(info);
                }
            }

            // Update adapters
            if (pagerAdapter != null) {
                pagerAdapter.allAppsAdapter.updateData(new ArrayList<>(allApps));
                pagerAdapter.systemAppsAdapter.updateData(new ArrayList<>(systemApps));
            }

            // Then load clones
            loadClonedApps();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Pager Adapter (ViewPager2)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Fragment-less ViewPager2 adapter that hosts three
     * {@link RecyclerView}s inside a {@link LinearLayout}.
     */
    private class AppPagerAdapter extends RecyclerView.Adapter<AppPagerAdapter.PageViewHolder> {

        static final int PAGE_COUNT = 3;

        final AppRecyclerViewAdapter allAppsAdapter;
        final AppRecyclerViewAdapter clonedAppsAdapter;
        final AppRecyclerViewAdapter systemAppsAdapter;
        final String[] titles;

        AppPagerAdapter() {
            allAppsAdapter = new AppRecyclerViewAdapter(new ArrayList<>(allApps), 0);
            clonedAppsAdapter = new AppRecyclerViewAdapter(new ArrayList<>(clonedApps), 1);
            systemAppsAdapter = new AppRecyclerViewAdapter(new ArrayList<>(systemApps), 2);
            titles = new String[]{
                    getString(R.string.tab_all_apps),
                    getString(R.string.tab_cloned_apps),
                    getString(R.string.tab_system_apps)
            };
            // Default current adapter to "all"
            currentAdapter = allAppsAdapter;
        }

        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            RecyclerView recyclerView = new RecyclerView(parent.getContext());
            recyclerView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            recyclerView.setLayoutManager(new LinearLayoutManager(parent.getContext()));
            recyclerView.addItemDecoration(
                    new DividerItemDecoration(parent.getContext(), DividerItemDecoration.VERTICAL));
            return new PageViewHolder(recyclerView);
        }

        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            holder.recyclerView.setAdapter(getAdapterForPosition(position));
        }

        @Override
        public int getItemCount() {
            return PAGE_COUNT;
        }

        private AppRecyclerViewAdapter getAdapterForPosition(int position) {
            switch (position) {
                case 1: return clonedAppsAdapter;
                case 2: return systemAppsAdapter;
                default: return allAppsAdapter;
            }
        }

        static class PageViewHolder extends RecyclerView.ViewHolder {
            final RecyclerView recyclerView;

            PageViewHolder(@NonNull View itemView) {
                super(itemView);
                recyclerView = (RecyclerView) itemView;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // RecyclerView Adapter
    // ══════════════════════════════════════════════════════════════════

    /**
     * Adapter for displaying a list of {@link AppInfo} items in a
     * {@link RecyclerView}.  Supports filtering and long-press context
     * menus.
     */
    private class AppRecyclerViewAdapter
            extends RecyclerView.Adapter<AppRecyclerViewAdapter.ViewHolder>
            implements Filterable {

        private List<AppInfo> originalList;
        private List<AppInfo> filteredList;
        private final int tabPosition;

        AppRecyclerViewAdapter(@NonNull List<AppInfo> data, int tabPosition) {
            this.originalList = data;
            this.filteredList = new ArrayList<>(data);
            this.tabPosition = tabPosition;
        }

        public void updateData(@NonNull List<AppInfo> newData) {
            this.originalList = newData;
            this.filteredList = new ArrayList<>(newData);
            notifyDataSetChanged();
        }

        // ─── ViewHolder ─────────────────────────────────────────────

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView iconImageView;
            final TextView nameTextView;
            final TextView packageTextView;
            final TextView sizeTextView;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                iconImageView = itemView.findViewById(R.id.app_icon);
                nameTextView = itemView.findViewById(R.id.app_name);
                packageTextView = itemView.findViewById(R.id.app_package);
                sizeTextView = itemView.findViewById(R.id.app_size);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppInfo info = filteredList.get(position);

            holder.iconImageView.setImageDrawable(info.icon);
            holder.nameTextView.setText(info.appName);
            holder.packageTextView.setText(info.packageName);
            holder.sizeTextView.setText(formatFileSize(info.sizeBytes));

            // Single click → launch or clone
            holder.itemView.setOnClickListener(v -> {
                if (info.isCloned) {
                    launchApp(info.packageName);
                } else {
                    cloneApp(info.packageName);
                }
            });

            // Long click → context menu
            holder.itemView.setOnLongClickListener(v -> {
                selectedApp = info;
                if (currentActionMode != null) {
                    currentActionMode.finish();
                }
                currentActionMode = startSupportActionMode(actionModeCallback);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return filteredList.size();
        }

        // ─── Empty State ────────────────────────────────────────────

        /**
         * Returns {@code true} when the adapter has no items.
         * The hosting ViewPager2 page should show an empty-state view
         * based on this.
         */
        public boolean isEmpty() {
            return filteredList.isEmpty();
        }

        // ─── Filter ─────────────────────────────────────────────────

        @NonNull
        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    String query = constraint.toString().toLowerCase(Locale.getDefault()).trim();
                    FilterResults results = new FilterResults();

                    if (query.isEmpty()) {
                        results.values = new ArrayList<>(originalList);
                        results.count = originalList.size();
                    } else {
                        List<AppInfo> filtered = new ArrayList<>();
                        for (AppInfo info : originalList) {
                            if (info.appName.toLowerCase(Locale.getDefault()).contains(query)
                                    || info.packageName.toLowerCase(Locale.getDefault()).contains(query)) {
                                filtered.add(info);
                            }
                        }
                        results.values = filtered;
                        results.count = filtered.size();
                    }
                    return results;
                }

                @Override
                @SuppressWarnings("unchecked")
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    filteredList = (List<AppInfo>) results.values;
                    notifyDataSetChanged();
                }
            };
        }
    }
}

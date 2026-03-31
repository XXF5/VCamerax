/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.dualspace.obs.R;
import com.dualspace.obs.databinding.ActivityCloneSetupBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CloneSetupActivity guides the user through a 5-step wizard to clone
 * an application into the dual space.  Steps:
 *
 * <ol>
 *   <li>Select App – RecyclerView of installed (non-system) apps</li>
 *   <li>Customize – name, icon, accent colour</li>
 *   <li>Permissions – toggle per-permission with a "Grant All" button</li>
 *   <li>Advanced – storage mode, network, location, DPI</li>
 *   <li>Review – summary of all selections</li>
 * </ol>
 *
 * <p>Navigates forward / backward with Next / Back buttons.  The Clone
 * button on the last step starts an asynchronous cloning operation
 * via {@link ExecutorService}.</p>
 */
public class CloneSetupActivity extends AppCompatActivity {

    private static final String TAG = "CloneSetupActivity";

    /** Intent extra for the pre-selected package name. */
    public static final String EXTRA_PACKAGE_NAME = "extra_package_name";

    // ─── Binding ─────────────────────────────────────────────────────
    private ActivityCloneSetupBinding binding;

    // ─── Step constants ──────────────────────────────────────────────
    private static final int TOTAL_STEPS = 5;
    private int currentStep = 0;

    // ─── Data models ─────────────────────────────────────────────────
    private final List<CloneAppInfo> installedApps = new ArrayList<>();
    private final List<PermissionItem> permissionItems = new ArrayList<>();

    // ─── Wizard state ────────────────────────────────────────────────
    private CloneAppInfo selectedApp;
    private String customName;
    private int accentColor = 0xFF6200EE; // default purple
    private String storageMode = "isolated";  // isolated, shared, sandbox
    private boolean networkEnabled = true;
    private boolean locationEnabled = false;
    private int customDpi = 0; // 0 = auto

    // ─── Cloning ─────────────────────────────────────────────────────
    private final ExecutorService cloneExecutor = Executors.newSingleThreadExecutor();
    private boolean isCloning = false;

    // ─── Preferences ─────────────────────────────────────────────────
    private SharedPreferences prefs;

    // ─── ViewPager adapter ───────────────────────────────────────────
    private CloneWizardPagerAdapter wizardAdapter;

    // ══════════════════════════════════════════════════════════════════
    // Data Models
    // ══════════════════════════════════════════════════════════════════

    public static class CloneAppInfo {
        public final String packageName;
        public final String appName;
        public final Drawable icon;
        public final long sizeBytes;
        public final String versionName;

        CloneAppInfo(String packageName, String appName, Drawable icon,
                     long sizeBytes, String versionName) {
            this.packageName = packageName;
            this.appName = appName;
            this.icon = icon;
            this.sizeBytes = sizeBytes;
            this.versionName = versionName;
        }
    }

    public static class PermissionItem {
        public final String name;
        public final String description;
        public boolean granted;

        PermissionItem(String name, String description, boolean granted) {
            this.name = name;
            this.description = description;
            this.granted = granted;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityCloneSetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = getSharedPreferences("dualspace_prefs", MODE_PRIVATE);

        initToolbar();
        initSteps();
        initNavigationButtons();
        loadInstalledApps();
        initPermissionItems();

        // Pre-select app if passed via Intent
        if (getIntent().hasExtra(EXTRA_PACKAGE_NAME)) {
            String pkg = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
            for (CloneAppInfo app : installedApps) {
                if (app.packageName.equals(pkg)) {
                    selectedApp = app;
                    break;
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cloneExecutor.shutdownNow();
    }

    // ══════════════════════════════════════════════════════════════════
    // Initialisation
    // ══════════════════════════════════════════════════════════════════

    private void initToolbar() {
        Toolbar toolbar = binding.toolbar;
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.title_clone_setup);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> {
            if (isCloning) {
                Toast.makeText(this, R.string.clone_in_progress, Toast.LENGTH_SHORT).show();
            } else {
                onBackPressed();
            }
        });
    }

    private void initSteps() {
        wizardAdapter = new CloneWizardPagerAdapter();
        binding.viewPager.setAdapter(wizardAdapter);
        binding.viewPager.setUserInputEnabled(false); // Disable swipe, use buttons
        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentStep = position;
                updateStepIndicator();
                updateNavigationButtons();
            }
        });
        updateStepIndicator();
    }

    private void initNavigationButtons() {
        binding.backButton.setOnClickListener(v -> prevStep());
        binding.nextButton.setOnClickListener(v -> {
            if (currentStep == TOTAL_STEPS - 1) {
                startClone();
            } else {
                nextStep();
            }
        });
        updateNavigationButtons();
    }

    // ══════════════════════════════════════════════════════════════════
    // Data Loading
    // ══════════════════════════════════════════════════════════════════

    private void loadInstalledApps() {
        installedApps.clear();
        PackageManager pm = getPackageManager();
        List<PackageInfo> packages = pm.getInstalledPackages(0);

        for (PackageInfo pi : packages) {
            try {
                // Skip system apps and our own package
                if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (pi.packageName.equals(getPackageName())) continue;

                String appName = pi.applicationInfo.loadLabel(pm).toString();
                Drawable icon = pi.applicationInfo.loadIcon(pm);
                String apkPath = pi.applicationInfo.sourceDir;
                long size = new java.io.File(apkPath).length();

                installedApps.add(new CloneAppInfo(
                        pi.packageName, appName, icon, size,
                        pi.versionName != null ? pi.versionName : "1.0"
                ));
            } catch (Exception e) {
                Log.w(TAG, "Skipping package: " + pi.packageName);
            }
        }

        Collections.sort(installedApps, (a, b) ->
                a.appName.toLowerCase(Locale.getDefault()).compareTo(
                        b.appName.toLowerCase(Locale.getDefault())));

        if (wizardAdapter.appListAdapter != null) {
            wizardAdapter.appListAdapter.updateData(new ArrayList<>(installedApps));
        }
    }

    private void initPermissionItems() {
        permissionItems.clear();
        permissionItems.add(new PermissionItem("Camera", "Access camera hardware", true));
        permissionItems.add(new PermissionItem("Microphone", "Record audio", false));
        permissionItems.add(new PermissionItem("Storage", "Read/write files", true));
        permissionItems.add(new PermissionItem("Contacts", "Read contacts", false));
        permissionItems.add(new PermissionItem("Phone", "Make/receive calls", false));
        permissionItems.add(new PermissionItem("SMS", "Send and receive SMS", false));
        permissionItems.add(new PermissionItem("Location", "Access device location", false));
        permissionItems.add(new PermissionItem("Calendar", "Read/write calendar events", false));

        if (wizardAdapter.permissionAdapter != null) {
            wizardAdapter.permissionAdapter.updateData(new ArrayList<>(permissionItems));
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Step Navigation
    // ══════════════════════════════════════════════════════════════════

    /**
     * Validates the current step and, if valid, advances to the next.
     */
    public void nextStep() {
        if (!validateStep(currentStep)) {
            return;
        }

        if (currentStep < TOTAL_STEPS - 1) {
            collectStepData(currentStep);
            currentStep++;
            binding.viewPager.setCurrentItem(currentStep, true);
            updateStepIndicator();
            updateNavigationButtons();

            // Populate step 5 (review) when navigating to it
            if (currentStep == 4) {
                populateReviewStep();
            }
        }
    }

    /**
     * Returns to the previous step.
     */
    public void prevStep() {
        if (currentStep > 0) {
            collectStepData(currentStep);
            currentStep--;
            binding.viewPager.setCurrentItem(currentStep, true);
            updateStepIndicator();
            updateNavigationButtons();
        }
    }

    /**
     * Validates the current wizard step.
     *
     * @return {@code true} if the step's data is valid and the user can
     * proceed.
     */
    public boolean validateStep(int step) {
        switch (step) {
            case 0: // Select App
                if (selectedApp == null) {
                    Toast.makeText(this, R.string.please_select_app,
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            case 1: // Customize
                if (customName != null && customName.trim().isEmpty()) {
                    Toast.makeText(this, R.string.name_cannot_be_empty,
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            case 2: // Permissions
                return true;
            case 3: // Advanced
                return true;
            case 4: // Review
                return true;
            default:
                return true;
        }
    }

    /**
     * Collects data from the current step's views into the wizard state.
     */
    private void collectStepData(int step) {
        switch (step) {
            case 1: // Customize
                View step1View = wizardAdapter.getStepView(1);
                if (step1View != null) {
                    EditText nameEdit = step1View.findViewById(R.id.clone_name_input);
                    if (nameEdit != null) {
                        customName = nameEdit.getText().toString().trim();
                    }
                    if (customName == null || customName.isEmpty()) {
                        customName = selectedApp != null ? selectedApp.appName : "";
                    }
                }
                break;
            case 3: // Advanced
                View step3View = wizardAdapter.getStepView(3);
                if (step3View != null) {
                    Spinner storageSpinner = step3View.findViewById(R.id.storage_mode_spinner);
                    if (storageSpinner != null) {
                        storageMode = (String) storageSpinner.getSelectedItem();
                    }
                    Switch networkSwitch = step3View.findViewById(R.id.network_toggle);
                    if (networkSwitch != null) networkEnabled = networkSwitch.isChecked();
                    Switch locationSwitch = step3View.findViewById(R.id.location_toggle);
                    if (locationSwitch != null) locationEnabled = locationSwitch.isChecked();
                    EditText dpiInput = step3View.findViewById(R.id.dpi_input);
                    if (dpiInput != null) {
                        String dpiStr = dpiInput.getText().toString().trim();
                        if (!dpiStr.isEmpty()) {
                            try { customDpi = Integer.parseInt(dpiStr); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                break;
        }
    }

    private void updateStepIndicator() {
        for (int i = 0; i < TOTAL_STEPS; i++) {
            View dot = binding.stepIndicatorContainer.getChildAt(i);
            if (dot != null) {
                if (i == currentStep) {
                    dot.setBackgroundResource(R.drawable.step_indicator_active);
                    dot.setAlpha(1.0f);
                } else if (i < currentStep) {
                    dot.setBackgroundResource(R.drawable.step_indicator_completed);
                    dot.setAlpha(1.0f);
                } else {
                    dot.setBackgroundResource(R.drawable.step_indicator_inactive);
                    dot.setAlpha(0.5f);
                }
            }
        }

        binding.stepTitleText.setText(getString(R.string.step_label, currentStep + 1, TOTAL_STEPS));
    }

    private void updateNavigationButtons() {
        binding.backButton.setVisibility(currentStep > 0 ? View.VISIBLE : View.GONE);
        binding.nextButton.setText(
                currentStep == TOTAL_STEPS - 1 ? R.string.clone_button : R.string.next
        );
    }

    private void populateReviewStep() {
        View reviewView = wizardAdapter.getStepView(4);
        if (reviewView == null || selectedApp == null) return;

        // App
        TextView appNameView = reviewView.findViewById(R.id.review_app_name);
        if (appNameView != null) appNameView.setText(selectedApp.appName);

        ImageView appIconView = reviewView.findViewById(R.id.review_app_icon);
        if (appIconView != null) appIconView.setImageDrawable(selectedApp.icon);

        TextView appPackageView = reviewView.findViewById(R.id.review_app_package);
        if (appPackageView != null) appPackageView.setText(selectedApp.packageName);

        // Custom name
        TextView customNameView = reviewView.findViewById(R.id.review_custom_name);
        if (customNameView != null) {
            customNameView.setText(customName != null ? customName : selectedApp.appName);
        }

        // Permissions
        int grantedCount = 0;
        for (PermissionItem p : permissionItems) {
            if (p.granted) grantedCount++;
        }
        TextView permView = reviewView.findViewById(R.id.review_permissions);
        if (permView != null) {
            permView.setText(getString(R.string.permissions_granted_count, grantedCount, permissionItems.size()));
        }

        // Advanced
        TextView advView = reviewView.findViewById(R.id.review_advanced);
        if (advView != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("Storage: ").append(storageMode).append("\n");
            sb.append("Network: ").append(networkEnabled ? "Enabled" : "Disabled").append("\n");
            sb.append("Location: ").append(locationEnabled ? "Enabled" : "Disabled").append("\n");
            sb.append("DPI: ").append(customDpi > 0 ? customDpi : "Auto");
            advView.setText(sb.toString());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Cloning
    // ══════════════════════════════════════════════════════════════════

    /**
     * Starts the clone operation on a background thread.  Shows a
     * progress bar and disables navigation during cloning.
     */
    public void startClone() {
        if (selectedApp == null || isCloning) return;

        collectStepData(currentStep);
        isCloning = true;
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.progressBar.setIndeterminate(true);
        binding.nextButton.setEnabled(false);
        binding.backButton.setEnabled(false);
        binding.cloneStatusText.setText(R.string.cloning_in_progress);
        binding.cloneStatusText.setVisibility(View.VISIBLE);

        cloneExecutor.execute(() -> {
            try {
                Log.i(TAG, "Starting clone: " + selectedApp.packageName);

                // ── Simulated cloning process ──
                // In production: copy APK, extract dex, set up isolated environment
                Thread.sleep(3000);

                // Persist clone
                String clonedPackages = prefs.getString("cloned_packages", "");
                StringBuilder sb = new StringBuilder(clonedPackages);
                if (sb.length() > 0) sb.append(",");
                sb.append(selectedApp.packageName);
                prefs.edit()
                        .putString("cloned_packages", sb.toString())
                        .putInt("clone_count", prefs.getInt("clone_count", 0) + 1)
                        .apply();

                Log.i(TAG, "Clone completed: " + selectedApp.packageName);
                runOnUiThread(() -> onCloneComplete(true, null));

            } catch (InterruptedException e) {
                Log.e(TAG, "Clone interrupted", e);
                Thread.currentThread().interrupt();
                runOnUiThread(() -> onCloneComplete(false, "Clone was interrupted"));
            } catch (Exception e) {
                Log.e(TAG, "Clone failed", e);
                runOnUiThread(() -> onCloneComplete(false, e.getMessage()));
            }
        });
    }

    /**
     * Called when the clone operation finishes.  Shows a success or
     * failure dialog and optionally finishes the activity.
     */
    public void onCloneComplete(boolean success, @Nullable String errorMessage) {
        isCloning = false;
        binding.progressBar.setVisibility(View.GONE);
        binding.nextButton.setEnabled(true);
        binding.backButton.setEnabled(true);
        binding.cloneStatusText.setVisibility(View.GONE);

        if (success) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.clone_success_title)
                    .setMessage(getString(R.string.clone_success_message, selectedApp.appName))
                    .setPositiveButton(R.string.open_app, (dialog, which) -> {
                        // Launch the cloned app
                        PackageManager pm = getPackageManager();
                        Intent intent = pm.getLaunchIntentForPackage(selectedApp.packageName);
                        if (intent != null) {
                            startActivity(intent);
                        }
                        finish();
                    })
                    .setNegativeButton(android.R.string.done, (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.clone_failed_title)
                    .setMessage(getString(R.string.clone_failed_message,
                            errorMessage != null ? errorMessage : "Unknown error"))
                    .setPositiveButton(R.string.retry, (dialog, which) -> startClone())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ViewPager2 Adapter
    // ══════════════════════════════════════════════════════════════════

    /**
     * Fragment-less ViewPager2 adapter that inflates each wizard step
     * layout.
     */
    private class CloneWizardPagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        AppListAdapter appListAdapter;
        PermissionListAdapter permissionAdapter;
        private final View[] stepViews = new View[TOTAL_STEPS];

        // Step layout resources
        private static final int[] STEP_LAYOUTS = {
                R.layout.clone_step_select_app,
                R.layout.clone_step_customize,
                R.layout.clone_step_permissions,
                R.layout.clone_step_advanced,
                R.layout.clone_step_review
        };

        @Override
        public int getItemCount() {
            return TOTAL_STEPS;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layoutRes = STEP_LAYOUTS[viewType];
            View view = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
            return new StepViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            View stepView = holder.itemView;
            stepViews[position] = stepView;

            switch (position) {
                case 0: setupStepSelectApp(stepView); break;
                case 1: setupStepCustomize(stepView); break;
                case 2: setupStepPermissions(stepView); break;
                case 3: setupStepAdvanced(stepView); break;
                case 4: break; // Review populated dynamically
            }
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @Nullable
        View getStepView(int position) {
            return stepViews[position];
        }

        // ─── Step 0: Select App ─────────────────────────────────────

        private void setupStepSelectApp(@NonNull View stepView) {
            RecyclerView recyclerView = stepView.findViewById(R.id.apps_recycler_view);
            recyclerView.setLayoutManager(new LinearLayoutManager(CloneSetupActivity.this));
            recyclerView.addItemDecoration(
                    new DividerItemDecoration(CloneSetupActivity.this,
                            DividerItemDecoration.VERTICAL));

            appListAdapter = new AppListAdapter(new ArrayList<>(installedApps));
            recyclerView.setAdapter(appListAdapter);
        }

        // ─── Step 1: Customize ──────────────────────────────────────

        private void setupStepCustomize(@NonNull View stepView) {
            EditText nameInput = stepView.findViewById(R.id.clone_name_input);
            if (nameInput != null && selectedApp != null) {
                nameInput.setText(selectedApp.appName);
                customName = selectedApp.appName;
            }

            ImageView iconPreview = stepView.findViewById(R.id.clone_icon_preview);
            if (iconPreview != null && selectedApp != null) {
                iconPreview.setImageDrawable(selectedApp.icon);
            }

            // Color picker (simplified: a few preset color buttons)
            LinearLayout colorPicker = stepView.findViewById(R.id.color_picker_container);
            if (colorPicker != null) {
                int[] colors = {0xFF6200EE, 0xFFD50000, 0xFF00897B, 0xFF1565C0,
                        0xFFFF6F00, 0xFF2E7D32, 0xFF6D4C41, 0xFF37474F};
                for (int color : colors) {
                    ImageView swatch = new ImageView(CloneSetupActivity.this);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            (int) (48 * getResources().getDisplayMetrics().density),
                            (int) (48 * getResources().getDisplayMetrics().density));
                    lp.setMargins(8, 0, 8, 0);
                    swatch.setLayoutParams(lp);
                    swatch.setImageResource(R.drawable.color_swatch);
                    swatch.setColorFilter(color);
                    swatch.setTag(color);

                    swatch.setOnClickListener(v -> {
                        accentColor = (int) v.getTag();
                        Toast.makeText(CloneSetupActivity.this,
                                "Color selected", Toast.LENGTH_SHORT).show();
                    });
                    colorPicker.addView(swatch);
                }
            }
        }

        // ─── Step 2: Permissions ────────────────────────────────────

        private void setupStepPermissions(@NonNull View stepView) {
            RecyclerView permRecyclerView = stepView.findViewById(R.id.permissions_recycler_view);
            permRecyclerView.setLayoutManager(new LinearLayoutManager(CloneSetupActivity.this));

            permissionAdapter = new PermissionListAdapter(new ArrayList<>(permissionItems));
            permRecyclerView.setAdapter(permissionAdapter);

            Button grantAllBtn = stepView.findViewById(R.id.grant_all_permissions_button);
            if (grantAllBtn != null) {
                grantAllBtn.setOnClickListener(v -> {
                    for (PermissionItem item : permissionItems) {
                        item.granted = true;
                    }
                    permissionAdapter.updateData(new ArrayList<>(permissionItems));
                    Toast.makeText(CloneSetupActivity.this,
                            R.string.all_permissions_granted, Toast.LENGTH_SHORT).show();
                });
            }
        }

        // ─── Step 3: Advanced ───────────────────────────────────────

        private void setupStepAdvanced(@NonNull View stepView) {
            Spinner storageSpinner = stepView.findViewById(R.id.storage_mode_spinner);
            if (storageSpinner != null) {
                ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                        CloneSetupActivity.this,
                        R.array.storage_modes,
                        android.R.layout.simple_spinner_item);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                storageSpinner.setAdapter(adapter);
            }
        }

        class StepViewHolder extends RecyclerView.ViewHolder {
            StepViewHolder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // App List Adapter (Step 0)
    // ══════════════════════════════════════════════════════════════════

    private class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

        private List<CloneAppInfo> data;

        AppListAdapter(@NonNull List<CloneAppInfo> data) {
            this.data = data;
        }

        void updateData(@NonNull List<CloneAppInfo> newData) {
            this.data = newData;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_clone_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            CloneAppInfo app = data.get(position);
            holder.iconView.setImageDrawable(app.icon);
            holder.nameView.setText(app.appName);
            holder.packageView.setText(app.packageName);
            holder.sizeView.setText(formatFileSize(app.sizeBytes));

            // Highlight selected
            boolean isSelected = selectedApp != null
                    && selectedApp.packageName.equals(app.packageName);
            holder.itemView.setAlpha(isSelected ? 1.0f : 0.7f);
            holder.itemView.setBackgroundColor(
                    isSelected
                            ? ContextCompat.getColor(CloneSetupActivity.this,
                                    R.color.color_selection_highlight)
                            : ContextCompat.getColor(CloneSetupActivity.this,
                                    android.R.color.transparent));

            holder.itemView.setOnClickListener(v -> {
                selectedApp = app;
                notifyDataSetChanged();
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView iconView;
            final TextView nameView;
            final TextView packageView;
            final TextView sizeView;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                iconView = itemView.findViewById(R.id.app_icon);
                nameView = itemView.findViewById(R.id.app_name);
                packageView = itemView.findViewById(R.id.app_package);
                sizeView = itemView.findViewById(R.id.app_size);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Permission Adapter (Step 2)
    // ══════════════════════════════════════════════════════════════════

    private class PermissionListAdapter extends RecyclerView.Adapter<PermissionListAdapter.ViewHolder> {

        private List<PermissionItem> data;

        PermissionListAdapter(@NonNull List<PermissionItem> data) {
            this.data = data;
        }

        void updateData(@NonNull List<PermissionItem> newData) {
            this.data = newData;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_permission, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PermissionItem item = data.get(position);
            holder.nameView.setText(item.name);
            holder.descriptionView.setText(item.description);
            holder.toggle.setChecked(item.granted);
            holder.toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                item.granted = isChecked;
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView nameView;
            final TextView descriptionView;
            final Switch toggle;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                nameView = itemView.findViewById(R.id.permission_name);
                descriptionView = itemView.findViewById(R.id.permission_description);
                toggle = itemView.findViewById(R.id.permission_toggle);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format(Locale.getDefault(), "%.1f %s",
                bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}

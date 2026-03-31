/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dualspace.obs.R;
import com.dualspace.obs.databinding.ActivityRecordingListBinding;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecordingListActivity displays all locally recorded files (from OBS)
 * in a scrollable list.  Each item shows a thumbnail, name, size,
 * duration, and date.  The user can play, share, or delete recordings,
 * and sort by date / name / size.
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>RecyclerView with recording items (thumbnail, name, size, duration, date)</li>
 *   <li>Play button – opens with an external video player</li>
 *   <li>Share button – shares via Android share sheet</li>
 *   <li>Delete button – confirmation dialog, then file removal</li>
 *   <li>Sort by date / name / size</li>
 *   <li>Empty state when no recordings exist</li>
 * </ul>
 */
public class RecordingListActivity extends AppCompatActivity {

    private static final String TAG = "RecordingListActivity";

    // ─── Sort modes ──────────────────────────────────────────────────
    private static final String SORT_DATE = "date";
    private static final String SORT_NAME = "name";
    private static final String SORT_SIZE = "size";

    // ─── Binding ─────────────────────────────────────────────────────
    private ActivityRecordingListBinding binding;

    // ─── Data ────────────────────────────────────────────────────────
    private final List<RecordingItem> recordings = new ArrayList<>();
    private RecordingAdapter adapter;
    private String currentSortMode = SORT_DATE;

    // ══════════════════════════════════════════════════════════════════
    // Data Model
    // ══════════════════════════════════════════════════════════════════

    public static class RecordingItem {
        public final File file;
        public final String name;
        public final long sizeBytes;
        public final long lastModified;
        public final String duration;
        public final String formattedDate;
        public final String formattedSize;
        public Bitmap thumbnail;

        RecordingItem(File file, String name, long sizeBytes, long lastModified,
                      String duration, String formattedDate, String formattedSize) {
            this.file = file;
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.lastModified = lastModified;
            this.duration = duration;
            this.formattedDate = formattedDate;
            this.formattedSize = formattedSize;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityRecordingListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        initToolbar();
        initRecyclerView();
        initSortChips();
        loadRecordings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadRecordings();
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.menu_recording_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_delete_all) {
            confirmDeleteAll();
            return true;
        } else if (id == R.id.action_sort) {
            showSortDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ══════════════════════════════════════════════════════════════════
    // Initialisation
    // ══════════════════════════════════════════════════════════════════

    private void initToolbar() {
        Toolbar toolbar = binding.toolbar;
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.title_recordings);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void initRecyclerView() {
        adapter = new RecordingAdapter(new ArrayList<>());
        binding.recyclerView.setLayoutManager(
                new LinearLayoutManager(this));
        binding.recyclerView.addItemDecoration(
                new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        binding.recyclerView.setAdapter(adapter);
    }

    private void initSortChips() {
        binding.sortDateChip.setChecked(true);

        binding.sortDateChip.setOnClickListener(v -> {
            currentSortMode = SORT_DATE;
            updateSortChips();
            sortAndRefresh();
        });
        binding.sortNameChip.setOnClickListener(v -> {
            currentSortMode = SORT_NAME;
            updateSortChips();
            sortAndRefresh();
        });
        binding.sortSizeChip.setOnClickListener(v -> {
            currentSortMode = SORT_SIZE;
            updateSortChips();
            sortAndRefresh();
        });
    }

    private void updateSortChips() {
        binding.sortDateChip.setChecked(SORT_DATE.equals(currentSortMode));
        binding.sortNameChip.setChecked(SORT_NAME.equals(currentSortMode));
        binding.sortSizeChip.setChecked(SORT_SIZE.equals(currentSortMode));
    }

    // ══════════════════════════════════════════════════════════════════
    // Data Loading
    // ══════════════════════════════════════════════════════════════════

    /**
     * Scans the Recordings directory for video files and loads them on a
     * background thread.
     */
    private void loadRecordings() {
        new LoadRecordingsTask().execute();
    }

    /**
     * Sorts the recordings list and refreshes the adapter.
     */
    private void sortAndRefresh() {
        switch (currentSortMode) {
            case SORT_NAME:
                Collections.sort(recordings, (a, b) ->
                        a.name.toLowerCase(Locale.getDefault()).compareTo(
                                b.name.toLowerCase(Locale.getDefault())));
                break;
            case SORT_SIZE:
                Collections.sort(recordings, (a, b) ->
                        Long.compare(b.sizeBytes, a.sizeBytes)); // Largest first
                break;
            case SORT_DATE:
            default:
                Collections.sort(recordings, (a, b) ->
                        Long.compare(b.lastModified, a.lastModified)); // Newest first
                break;
        }
        adapter.updateData(new ArrayList<>(recordings));
    }

    private void updateEmptyState() {
        if (recordings.isEmpty()) {
            binding.emptyStateView.setVisibility(View.VISIBLE);
            binding.recyclerView.setVisibility(View.GONE);
        } else {
            binding.emptyStateView.setVisibility(View.GONE);
            binding.recyclerView.setVisibility(View.VISIBLE);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Actions
    // ══════════════════════════════════════════════════════════════════

    /**
     * Opens a recording file with an external video player.
     */
    private void playRecording(@NonNull RecordingItem item) {
        Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                item.file
        );

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "No player found for video", e);
            Toast.makeText(this, R.string.no_video_player,
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Shares a recording file via the Android share sheet.
     */
    private void shareRecording(@NonNull RecordingItem item) {
        Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                item.file
        );

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("video/*");
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, item.name);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_recording)));
    }

    /**
     * Shows a confirmation dialog, then deletes the file and removes it
     * from the list.
     */
    private void deleteRecording(@NonNull RecordingItem item, int position) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(getString(R.string.confirm_delete_message, item.name))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    boolean deleted = item.file.delete();
                    if (deleted) {
                        recordings.remove(position);
                        adapter.removeItem(position);
                        updateEmptyState();
                        Toast.makeText(this, R.string.recording_deleted,
                                Toast.LENGTH_SHORT).show();

                        // Notify media scanner
                        sendBroadcast(new Intent(
                                Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                                Uri.fromFile(item.file)));
                    } else {
                        Toast.makeText(this, R.string.delete_failed,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteAll() {
        if (recordings.isEmpty()) {
            Toast.makeText(this, R.string.no_recordings, Toast.LENGTH_SHORT).show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_delete_all_title)
                .setMessage(getString(R.string.confirm_delete_all_message, recordings.size()))
                .setPositiveButton(R.string.delete_all, (dialog, which) -> {
                    int deleted = 0;
                    for (RecordingItem item : recordings) {
                        if (item.file.delete()) {
                            deleted++;
                        }
                    }
                    recordings.clear();
                    adapter.updateData(new ArrayList<>());
                    updateEmptyState();
                    Toast.makeText(this,
                            getString(R.string.recordings_deleted_count, deleted),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSortDialog() {
        String[] options = {getString(R.string.sort_by_date),
                getString(R.string.sort_by_name),
                getString(R.string.sort_by_size)};
        int selectedIndex;
        switch (currentSortMode) {
            case SORT_NAME: selectedIndex = 1; break;
            case SORT_SIZE: selectedIndex = 2; break;
            default: selectedIndex = 0; break;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sort_by)
                .setSingleChoiceItems(options, selectedIndex, (dialog, which) -> {
                    switch (which) {
                        case 0: currentSortMode = SORT_DATE; break;
                        case 1: currentSortMode = SORT_NAME; break;
                        case 2: currentSortMode = SORT_SIZE; break;
                    }
                    updateSortChips();
                    sortAndRefresh();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ══════════════════════════════════════════════════════════════════
    // Background Task
    // ══════════════════════════════════════════════════════════════════

    /**
     * Loads recording metadata (size, duration, thumbnail) on a
     * background thread.
     */
    private class LoadRecordingsTask extends AsyncTask<Void, Void, List<RecordingItem>> {

        @Override
        protected void onPreExecute() {
            binding.loadingProgress.setVisibility(View.VISIBLE);
        }

        @Override
        protected List<RecordingItem> doInBackground(Void... voids) {
            List<RecordingItem> result = new ArrayList<>();
            File recordingsDir = getExternalFilesDir("Recordings");

            if (recordingsDir == null || !recordingsDir.exists()) {
                return result;
            }

            File[] files = recordingsDir.listFiles();
            if (files == null) return result;

            SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();

            for (File file : files) {
                if (!file.isFile() || !file.getName().matches(".*\\.(mp4|mkv|flv|mov|avi|ts)$")) {
                    continue;
                }

                try {
                    String name = file.getName();
                    long size = file.length();
                    long lastMod = file.lastModified();
                    String dateStr = dateFmt.format(new Date(lastMod));

                    // Extract duration and thumbnail
                    retriever.setDataSource(file.getAbsolutePath());
                    String durationStr = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION);

                    long durationMs = 0L;
                    if (durationStr != null) {
                        try { durationMs = Long.parseLong(durationStr); } catch (NumberFormatException ignored) {}
                    }
                    String formattedDuration = formatDuration(durationMs);

                    RecordingItem item = new RecordingItem(
                            file, name, size, lastMod,
                            formattedDuration, dateStr,
                            formatFileSize(size)
                    );

                    // Extract thumbnail (at 1/10th scale for performance)
                    try {
                        byte[] thumbnailBytes = retriever.getEmbeddedPicture();
                        if (thumbnailBytes != null) {
                            item.thumbnail = BitmapFactory.decodeByteArray(
                                    thumbnailBytes, 0, thumbnailBytes.length);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not extract thumbnail for " + name);
                    }

                    // Fallback: generate thumbnail from frame
                    if (item.thumbnail == null && durationMs > 0) {
                        try {
                            Bitmap frame = retriever.getFrameAtTime(
                                    durationMs / 4,  // 25% into the video
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                            if (frame != null) {
                                item.thumbnail = frame;
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Could not extract frame for " + name);
                        }
                    }

                    result.add(item);
                } catch (Exception e) {
                    Log.w(TAG, "Error reading recording: " + file.getName(), e);
                }
            }

            try {
                retriever.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever", e);
            }

            return result;
        }

        @Override
        protected void onPostExecute(@NonNull List<RecordingItem> items) {
            binding.loadingProgress.setVisibility(View.GONE);
            recordings.clear();
            recordings.addAll(items);
            sortAndRefresh();
            updateEmptyState();

            Log.i(TAG, "Loaded " + items.size() + " recordings");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // RecyclerView Adapter
    // ══════════════════════════════════════════════════════════════════

    private class RecordingAdapter
            extends RecyclerView.Adapter<RecordingAdapter.ViewHolder>
            implements Filterable {

        private List<RecordingItem> originalList;
        private List<RecordingItem> filteredList;

        RecordingAdapter(@NonNull List<RecordingItem> data) {
            this.originalList = data;
            this.filteredList = new ArrayList<>(data);
        }

        void updateData(@NonNull List<RecordingItem> newData) {
            this.originalList = newData;
            this.filteredList = new ArrayList<>(newData);
            notifyDataSetChanged();
        }

        void removeItem(int position) {
            if (position >= 0 && position < filteredList.size()) {
                filteredList.remove(position);
                notifyItemRemoved(position);
            }
        }

        // ─── ViewHolder ─────────────────────────────────────────────

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView thumbnailView;
            final TextView nameView;
            final TextView sizeView;
            final TextView durationView;
            final TextView dateView;
            final ImageView playButton;
            final ImageView shareButton;
            final ImageView deleteButton;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                thumbnailView = itemView.findViewById(R.id.recording_thumbnail);
                nameView = itemView.findViewById(R.id.recording_name);
                sizeView = itemView.findViewById(R.id.recording_size);
                durationView = itemView.findViewById(R.id.recording_duration);
                dateView = itemView.findViewById(R.id.recording_date);
                playButton = itemView.findViewById(R.id.recording_play);
                shareButton = itemView.findViewById(R.id.recording_share);
                deleteButton = itemView.findViewById(R.id.recording_delete);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recording, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            RecordingItem item = filteredList.get(position);

            // Thumbnail
            if (item.thumbnail != null) {
                holder.thumbnailView.setImageBitmap(item.thumbnail);
            } else {
                holder.thumbnailView.setImageResource(R.drawable.ic_video_placeholder);
            }

            holder.nameView.setText(item.name);
            holder.sizeView.setText(item.formattedSize);
            holder.durationView.setText(item.duration);
            holder.dateView.setText(item.formattedDate);

            // Play
            holder.playButton.setOnClickListener(v -> playRecording(item));

            // Share
            holder.shareButton.setOnClickListener(v -> shareRecording(item));

            // Delete
            holder.deleteButton.setOnClickListener(v ->
                    deleteRecording(item, holder.getAdapterPosition()));

            // Click on item → play
            holder.itemView.setOnClickListener(v -> playRecording(item));
        }

        @Override
        public int getItemCount() {
            return filteredList.size();
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
                        List<RecordingItem> filtered = new ArrayList<>();
                        for (RecordingItem item : originalList) {
                            if (item.name.toLowerCase(Locale.getDefault()).contains(query)) {
                                filtered.add(item);
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
                    filteredList = (List<RecordingItem>) results.values;
                    notifyDataSetChanged();
                }
            };
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════

    private static String formatDuration(long millis) {
        if (millis <= 0) return "0:00";
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format(Locale.getDefault(), "%.1f %s",
                bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}

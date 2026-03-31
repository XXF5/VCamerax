package com.dualspace.obs.ui.adapters;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.dualspace.obs.R;

import java.io.File;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for displaying a list of recorded video files.
 *
 * <p>Each item shows a thumbnail, file name, size, duration, and date.
 * Action buttons allow play, share, and delete.  The list supports sorting
 * by name, date, size, or duration, and efficient updates via
 * {@link DiffUtil}.</p>
 */
public class RecordingAdapter extends RecyclerView.Adapter<RecordingAdapter.RecordingViewHolder> {

    // ── Data model ─────────────────────────────────────────────────────────

    /**
     * Represents a single recorded file.
     */
    public static class RecordingItem {
        @NonNull public final String filePath;
        @NonNull public final String fileName;
        public final long sizeBytes;
        /** Duration in milliseconds. */
        public final long durationMs;
        /** Creation timestamp (epoch millis). */
        public final long createdAt;
        /** MIME type (e.g. "video/mp4"). */
        @NonNull public final String mimeType;
        /** Path to a thumbnail image file, or {@code null}. */
        @Nullable public final String thumbnailPath;

        public RecordingItem(@NonNull String filePath,
                             @NonNull String fileName,
                             long sizeBytes,
                             long durationMs,
                             long createdAt,
                             @NonNull String mimeType,
                             @Nullable String thumbnailPath) {
            this.filePath = filePath;
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
            this.durationMs = durationMs;
            this.createdAt = createdAt;
            this.mimeType = mimeType;
            this.thumbnailPath = thumbnailPath;
        }
    }

    // ── Listener interfaces ────────────────────────────────────────────────

    /** Click on the item itself (e.g. to play). */
    public interface OnRecordingClickListener {
        void onRecordingClick(@NonNull RecordingItem item);
    }

    /** Delete button pressed. */
    public interface OnRecordingDeleteListener {
        void onRecordingDelete(@NonNull RecordingItem item, int position);
    }

    // ── Sort modes ─────────────────────────────────────────────────────────

    public enum SortMode {
        DATE_DESC,
        DATE_ASC,
        NAME_ASC,
        NAME_DESC,
        SIZE_DESC,
        SIZE_ASC,
        DURATION_DESC,
        DURATION_ASC
    }

    // ── DiffUtil ───────────────────────────────────────────────────────────

    private static class RecordingDiffCallback extends DiffUtil.Callback {
        private final List<RecordingItem> oldList;
        private final List<RecordingItem> newList;

        RecordingDiffCallback(@NonNull List<RecordingItem> oldList,
                              @NonNull List<RecordingItem> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() { return oldList.size(); }

        @Override
        public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            return oldList.get(oldPos).filePath.equals(newList.get(newPos).filePath);
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            RecordingItem o = oldList.get(oldPos);
            RecordingItem n = newList.get(newPos);
            return o.fileName.equals(n.fileName)
                    && o.sizeBytes == n.sizeBytes
                    && o.durationMs == n.durationMs;
        }
    }

    // ── ViewHolder ─────────────────────────────────────────────────────────

    public class RecordingViewHolder extends RecyclerView.ViewHolder {

        final ImageView thumbnailView;
        final TextView nameView;
        final TextView sizeView;
        final TextView durationView;
        final TextView dateView;
        final ImageButton playBtn;
        final ImageButton shareBtn;
        final ImageButton deleteBtn;

        public RecordingViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnailView = itemView.findViewById(R.id.iv_recording_thumbnail);
            nameView = itemView.findViewById(R.id.tv_recording_name);
            sizeView = itemView.findViewById(R.id.tv_recording_size);
            durationView = itemView.findViewById(R.id.tv_recording_duration);
            dateView = itemView.findViewById(R.id.tv_recording_date);
            playBtn = itemView.findViewById(R.id.btn_recording_play);
            shareBtn = itemView.findViewById(R.id.btn_recording_share);
            deleteBtn = itemView.findViewById(R.id.btn_recording_delete);
        }

        public void bind(@NonNull RecordingItem item) {
            nameView.setText(item.fileName);
            sizeView.setText(formatFileSize(item.sizeBytes));
            durationView.setText(formatDuration(item.durationMs));
            dateView.setText(formatDate(item.createdAt));

            // Thumbnail – clear so callers can load via Glide / Coil
            thumbnailView.setImageDrawable(null);

            // Play button
            playBtn.setOnClickListener(v -> playRecording(item));

            // Share button
            shareBtn.setOnClickListener(v -> shareRecording(item));

            // Delete button
            deleteBtn.setOnClickListener(v -> {
                if (deleteListener != null) {
                    int pos = getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        deleteListener.onRecordingDelete(item, pos);
                    }
                }
            });
        }
    }

    // ── Instance state ─────────────────────────────────────────────────────

    private final List<RecordingItem> items = new ArrayList<>();
    private final Context context;  // application context, safe to retain

    @Nullable private OnRecordingClickListener clickListener;
    @Nullable private OnRecordingDeleteListener deleteListener;

    /** FileProvider authority for sharing via content:// URIs. */
    @Nullable private String fileProviderAuthority;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * @param context application context (retained safely)
     */
    public RecordingAdapter(@NonNull Context context) {
        this.context = context.getApplicationContext();
        setHasStableIds(true);
    }

    // ── Adapter overrides ──────────────────────────────────────────────────

    @NonNull
    @Override
    public RecordingViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view = inflater.inflate(R.layout.item_recording_list, parent, false);
        return new RecordingViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecordingViewHolder holder, int position) {
        RecordingItem item = items.get(position);
        holder.bind(item);

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onRecordingClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).filePath.hashCode();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Replace the full list using DiffUtil.
     */
    public void setRecordings(@NonNull List<RecordingItem> newItems) {
        RecordingDiffCallback diff = new RecordingDiffCallback(items, newItems);
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(diff);
        items.clear();
        items.addAll(newItems);
        result.dispatchUpdatesTo(this);
    }

    /**
     * Add a single recording to the top of the list.
     */
    public void addRecording(@NonNull RecordingItem item) {
        items.add(0, item);
        notifyItemInserted(0);
    }

    /**
     * Remove a recording at the given position.
     */
    public void removeRecording(int position) {
        if (position >= 0 && position < items.size()) {
            items.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, items.size() - position);
        }
    }

    /**
     * Sort the current list by the given mode.
     */
    public void sortRecordings(@NonNull SortMode mode) {
        Comparator<RecordingItem> comparator;
        switch (mode) {
            case DATE_ASC:
                comparator = Comparator.comparingLong(o -> o.createdAt);
                break;
            case NAME_ASC:
                comparator = (a, b) -> a.fileName.compareToIgnoreCase(b.fileName);
                break;
            case NAME_DESC:
                comparator = (a, b) -> b.fileName.compareToIgnoreCase(a.fileName);
                break;
            case SIZE_DESC:
                comparator = (a, b) -> Long.compare(b.sizeBytes, a.sizeBytes);
                break;
            case SIZE_ASC:
                comparator = Comparator.comparingLong(o -> o.sizeBytes);
                break;
            case DURATION_DESC:
                comparator = (a, b) -> Long.compare(b.durationMs, a.durationMs);
                break;
            case DURATION_ASC:
                comparator = Comparator.comparingLong(o -> o.durationMs);
                break;
            case DATE_DESC:
            default:
                comparator = (a, b) -> Long.compare(b.createdAt, a.createdAt);
                break;
        }
        Collections.sort(items, comparator);
        notifyDataSetChanged();
    }

    /**
     * Get the item at the given position.
     */
    @Nullable
    public RecordingItem getItem(int position) {
        if (position >= 0 && position < items.size()) {
            return items.get(position);
        }
        return null;
    }

    /**
     * Get a defensive copy of the current list.
     */
    @NonNull
    public List<RecordingItem> getRecordings() {
        return new ArrayList<>(items);
    }

    /**
     * Set the FileProvider authority used when sharing recordings.
     */
    public void setFileProviderAuthority(@NonNull String authority) {
        this.fileProviderAuthority = authority;
    }

    // ── Setters ────────────────────────────────────────────────────────────

    public void setOnRecordingClickListener(@Nullable OnRecordingClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnRecordingDeleteListener(@Nullable OnRecordingDeleteListener listener) {
        this.deleteListener = listener;
    }

    // ── Action helpers ─────────────────────────────────────────────────────

    /**
     * Launch an Intent to play the recording using the system video player.
     */
    private void playRecording(@NonNull RecordingItem item) {
        File file = new File(item.filePath);
        if (!file.exists()) {
            Toast.makeText(context, "File not found: " + item.fileName,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Uri uri;
        if (fileProviderAuthority != null) {
            uri = FileProvider.getUriForFile(context, fileProviderAuthority, file);
        } else {
            uri = Uri.fromFile(file);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, item.mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "No app found to play video",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Share the recording via an Android share sheet.
     */
    private void shareRecording(@NonNull RecordingItem item) {
        File file = new File(item.filePath);
        if (!file.exists()) {
            Toast.makeText(context, "File not found: " + item.fileName,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Uri uri;
        if (fileProviderAuthority != null) {
            uri = FileProvider.getUriForFile(context, fileProviderAuthority, file);
        } else {
            uri = Uri.fromFile(file);
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(item.mimeType);
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, item.fileName);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Intent chooser = Intent.createChooser(shareIntent, "Share recording");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
    }

    // ── Formatting helpers ─────────────────────────────────────────────────

    @NonNull
    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB"};
        int idx = 0;
        double sz = bytes;
        while (sz >= 1024 && idx < units.length - 1) { sz /= 1024; idx++; }
        return new DecimalFormat("#,##0.#").format(sz) + " " + units[idx];
    }

    @NonNull
    private static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    @NonNull
    private static String formatDate(long epochMs) {
        if (DateUtils.isToday(epochMs)) {
            return "Today " + new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(epochMs));
        }

        boolean isThisYear = new Date(epochMs).getYear() == new Date().getYear();
        if (isThisYear) {
            return new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                    .format(new Date(epochMs));
        }
        return new SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(new Date(epochMs));
    }
}

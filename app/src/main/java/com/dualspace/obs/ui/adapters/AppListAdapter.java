package com.dualspace.obs.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.dualspace.obs.R;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for displaying a list of installed apps.
 *
 * <p>Supports click and long-click listeners, search filtering, alphabetical
 * and size-based sorting, and efficient list updates via {@link DiffUtil}.</p>
 */
public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.AppViewHolder> {

    // ── Data model ─────────────────────────────────────────────────────────

    /**
     * Represents a single app entry in the list.
     */
    public static class AppInfo {
        @NonNull public final String packageName;
        @NonNull public final String appName;
        @NonNull public final String versionName;
        public final long sizeBytes;
        @NonNull public final String iconPath;  // file path or content URI

        public AppInfo(@NonNull String packageName,
                       @NonNull String appName,
                       @NonNull String versionName,
                       long sizeBytes,
                       @NonNull String iconPath) {
            this.packageName = packageName;
            this.appName = appName;
            this.versionName = versionName;
            this.sizeBytes = sizeBytes;
            this.iconPath = iconPath;
        }
    }

    // ── Listener interfaces ────────────────────────────────────────────────

    /** Simple click listener. */
    public interface OnItemClickListener {
        void onItemClick(@NonNull AppInfo app);
    }

    /** Long-press listener. */
    public interface OnItemLongClickListener {
        boolean onItemLongClick(@NonNull AppInfo app);
    }

    // ── DiffUtil callback ──────────────────────────────────────────────────

    private static class AppDiffCallback extends DiffUtil.Callback {
        private final List<AppInfo> oldList;
        private final List<AppInfo> newList;

        AppDiffCallback(@NonNull List<AppInfo> oldList, @NonNull List<AppInfo> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            return oldList.get(oldPos).packageName.equals(newList.get(newPos).packageName);
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            AppInfo oldApp = oldList.get(oldPos);
            AppInfo newApp = newList.get(newPos);
            return oldApp.appName.equals(newApp.appName)
                    && oldApp.sizeBytes == newApp.sizeBytes
                    && oldApp.versionName.equals(newApp.versionName);
        }
    }

    // ── ViewHolder ─────────────────────────────────────────────────────────

    public static class AppViewHolder extends RecyclerView.ViewHolder {
        final ImageView iconView;
        final TextView nameView;
        final TextView packageView;
        final TextView sizeView;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.iv_app_icon);
            nameView = itemView.findViewById(R.id.tv_app_name);
            packageView = itemView.findViewById(R.id.tv_app_package);
            sizeView = itemView.findViewById(R.id.tv_app_size);
        }

        public void bind(@NonNull AppInfo app) {
            nameView.setText(app.appName);
            packageView.setText(app.packageName);
            sizeView.setText(formatFileSize(app.sizeBytes));

            // Icon loading – callers can use Glide / Coil externally;
            // here we just clear any leftover. A real app would load async.
            iconView.setImageDrawable(null);
        }
    }

    // ── Instance state ─────────────────────────────────────────────────────

    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> filteredApps = new ArrayList<>();

    @Nullable private OnItemClickListener clickListener;
    @Nullable private OnItemLongClickListener longClickListener;
    @Nullable private String currentFilter;

    // ── Constructor ────────────────────────────────────────────────────────

    public AppListAdapter() {
        setHasStableIds(true);
    }

    // ── Adapter overrides ──────────────────────────────────────────────────

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view = inflater.inflate(R.layout.item_app_list, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo app = filteredApps.get(position);
        holder.bind(app);

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onItemClick(app);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                return longClickListener.onItemLongClick(app);
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return filteredApps.size();
    }

    @Override
    public long getItemId(int position) {
        // Use hashCode of package name for stable IDs
        return filteredApps.get(position).packageName.hashCode();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Replace the entire data set using {@link DiffUtil} for efficient updates.
     *
     * @param apps the new list of apps (a defensive copy is made)
     */
    public void setApps(@NonNull List<AppInfo> apps) {
        List<AppInfo> copy = new ArrayList<>(apps);

        // Compute diff between old filtered list and new filtered list
        List<AppInfo> newFiltered = applyFilter(copy, currentFilter);
        AppDiffCallback diffCallback = new AppDiffCallback(filteredApps, newFiltered);
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(diffCallback);

        allApps.clear();
        allApps.addAll(copy);
        filteredApps.clear();
        filteredApps.addAll(newFiltered);

        result.dispatchUpdatesTo(this);
    }

    /**
     * Filter the list by a search query (matches app name or package name,
     * case-insensitive). Pass {@code null} or empty to clear the filter.
     *
     * @param query the search text
     */
    public void filterApps(@Nullable String query) {
        currentFilter = (query != null && !query.trim().isEmpty())
                ? query.trim().toLowerCase(Locale.US)
                : null;

        List<AppInfo> newFiltered = applyFilter(allApps, currentFilter);
        AppDiffCallback diffCallback = new AppDiffCallback(filteredApps, newFiltered);
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(diffCallback);

        filteredApps.clear();
        filteredApps.addAll(newFiltered);
        result.dispatchUpdatesTo(this);
    }

    /**
     * Sort modes for the app list.
     */
    public enum SortMode {
        /** Alphabetical by app name (A→Z). */
        NAME_ASC,
        /** Alphabetical by app name (Z→A). */
        NAME_DESC,
        /** Largest apps first. */
        SIZE_DESC,
        /** Smallest apps first. */
        SIZE_ASC
    }

    /**
     * Sort the current (filtered) list by the given mode and dispatch updates.
     *
     * @param mode the sorting strategy
     */
    public void sortApps(@NonNull SortMode mode) {
        Comparator<AppInfo> comparator;
        switch (mode) {
            case NAME_DESC:
                comparator = (a, b) -> b.appName.compareToIgnoreCase(a.appName);
                break;
            case SIZE_DESC:
                comparator = (a, b) -> Long.compare(b.sizeBytes, a.sizeBytes);
                break;
            case SIZE_ASC:
                comparator = (a, b) -> Long.compare(a.sizeBytes, b.sizeBytes);
                break;
            case NAME_ASC:
            default:
                comparator = (a, b) -> a.appName.compareToIgnoreCase(b.appName);
                break;
        }

        Collections.sort(filteredApps, comparator);
        notifyDataSetChanged();
    }

    /**
     * Set a simple click listener.
     */
    public void setOnItemClickListener(@Nullable OnItemClickListener listener) {
        this.clickListener = listener;
    }

    /**
     * Set a long-click listener.
     */
    public void setOnItemLongClickListener(@Nullable OnItemLongClickListener listener) {
        this.longClickListener = listener;
    }

    /**
     * Get the app at the given filtered position.
     */
    @Nullable
    public AppInfo getApp(int position) {
        if (position >= 0 && position < filteredApps.size()) {
            return filteredApps.get(position);
        }
        return null;
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    @NonNull
    private List<AppInfo> applyFilter(@NonNull List<AppInfo> source,
                                      @Nullable String filter) {
        if (filter == null) {
            return new ArrayList<>(source);
        }
        List<AppInfo> result = new ArrayList<>(source.size());
        for (AppInfo app : source) {
            if (app.appName.toLowerCase(Locale.US).contains(filter)
                    || app.packageName.toLowerCase(Locale.US).contains(filter)) {
                result.add(app);
            }
        }
        return result;
    }

    @NonNull
    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        double size = bytes;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return new DecimalFormat("#,##0.#").format(size) + " " + units[unitIndex];
    }
}

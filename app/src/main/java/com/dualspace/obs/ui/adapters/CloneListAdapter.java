package com.dualspace.obs.ui.adapters;

import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.dualspace.obs.R;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for displaying cloned apps.
 *
 * <p>Shows the clone name, source package, creation date, and size.
 * Supports swipe-to-delete via {@link ItemTouchHelper}, a context menu
 * for clone-specific actions, and DiffUtil-based updates.</p>
 *
 * <p>Usage with swipe-to-delete:</p>
 * <pre>{@code
 * new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
 *     public boolean onMove(...) { return false; }
 *     public void onSwiped(holder, dir) { adapter.removeItem(holder.getAdapterPosition()); }
 * }).attachToRecyclerView(recyclerView);
 * }</pre>
 */
public class CloneListAdapter extends RecyclerView.Adapter<CloneListAdapter.CloneViewHolder> {

    // ── Data model ─────────────────────────────────────────────────────────

    /**
     * Represents a single cloned app entry in the list.
     */
    public static class CloneItem {
        @NonNull public final String cloneId;
        @NonNull public final String cloneName;
        @NonNull public final String sourcePackage;
        @Nullable public final String iconPath;
        public final long createdAt;   // epoch millis
        public final long sizeBytes;

        public CloneItem(@NonNull String cloneId,
                         @NonNull String cloneName,
                         @NonNull String sourcePackage,
                         @Nullable String iconPath,
                         long createdAt,
                         long sizeBytes) {
            this.cloneId = cloneId;
            this.cloneName = cloneName;
            this.sourcePackage = sourcePackage;
            this.iconPath = iconPath;
            this.createdAt = createdAt;
            this.sizeBytes = sizeBytes;
        }
    }

    // ── Listener interfaces ────────────────────────────────────────────────

    public interface OnItemClickListener {
        void onItemClick(@NonNull CloneItem item);
    }

    public interface OnItemLongClickListener {
        boolean onItemLongClick(@NonNull CloneItem item, int position);
    }

    public interface OnDeleteListener {
        /** Called when the user swipes to delete or confirms deletion from the context menu. */
        void onDeleteClone(@NonNull CloneItem item, int position);
    }

    /**
     * Context menu action identifiers.
     */
    public static final int MENU_LAUNCH   = 1;
    public static final int MENU_SETTINGS = 2;
    public static final int MENU_DUPLICATE = 3;
    public static final int MENU_EXPORT   = 4;
    public static final int MENU_DELETE   = 5;

    public interface OnContextMenuListener {
        boolean onContextItemSelected(@NonNull MenuItem item, @NonNull CloneItem cloneItem);
    }

    // ── DiffUtil callback ──────────────────────────────────────────────────

    private static class CloneDiffCallback extends DiffUtil.Callback {
        private final List<CloneItem> oldList;
        private final List<CloneItem> newList;

        CloneDiffCallback(@NonNull List<CloneItem> oldList, @NonNull List<CloneItem> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() { return oldList.size(); }

        @Override
        public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            return oldList.get(oldPos).cloneId.equals(newList.get(newPos).cloneId);
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            CloneItem o = oldList.get(oldPos);
            CloneItem n = newList.get(newPos);
            return o.cloneName.equals(n.cloneName)
                    && o.sourcePackage.equals(n.sourcePackage)
                    && o.sizeBytes == n.sizeBytes;
        }
    }

    // ── ViewHolder ─────────────────────────────────────────────────────────

    public class CloneViewHolder extends RecyclerView.ViewHolder
            implements View.OnCreateContextMenuListener {

        final ImageView iconView;
        final TextView nameView;
        final TextView packageView;
        final TextView dateView;
        final TextView sizeView;
        final View deleteBtn; // visible during swipe

        public CloneViewHolder(@NonNull View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.iv_clone_icon);
            nameView = itemView.findViewById(R.id.tv_clone_name);
            packageView = itemView.findViewById(R.id.tv_clone_package);
            dateView = itemView.findViewById(R.id.tv_clone_date);
            sizeView = itemView.findViewById(R.id.tv_clone_size);
            deleteBtn = itemView.findViewById(R.id.btn_clone_delete);

            itemView.setOnCreateContextMenuListener(this);
        }

        public void bind(@NonNull CloneItem item) {
            nameView.setText(item.cloneName);
            packageView.setText(item.sourcePackage);
            dateView.setText(DateFormat.getDateTimeInstance()
                    .format(new Date(item.createdAt)));
            sizeView.setText(formatFileSize(item.sizeBytes));
            iconView.setImageDrawable(null);
        }

        @Override
        public void onCreateContextMenu(ContextMenu menu, View v,
                                        ContextMenu.ContextMenuInfo menuInfo) {
            menu.setHeaderTitle(currentBoundItem != null ? currentBoundItem.cloneName : "Clone");
            menu.add(Menu.NONE, MENU_LAUNCH, Menu.NONE, "Launch");
            menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, "Settings");
            menu.add(Menu.NONE, MENU_DUPLICATE, Menu.NONE, "Duplicate");
            menu.add(Menu.NONE, MENU_EXPORT, Menu.NONE, "Export APK");
            menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, "Delete");
        }
    }

    // ── Instance state ─────────────────────────────────────────────────────

    private final List<CloneItem> items = new ArrayList<>();
    @Nullable private CloneItem currentBoundItem;

    @Nullable private OnItemClickListener clickListener;
    @Nullable private OnItemLongClickListener longClickListener;
    @Nullable private OnDeleteListener deleteListener;
    @Nullable private OnContextMenuListener contextMenuListener;
    @Nullable private String currentFilter;

    // ── Constructor ────────────────────────────────────────────────────────

    public CloneListAdapter() {
        setHasStableIds(true);
    }

    // ── Adapter overrides ──────────────────────────────────────────────────

    @NonNull
    @Override
    public CloneViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view = inflater.inflate(R.layout.item_clone_list, parent, false);
        return new CloneViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CloneViewHolder holder, int position) {
        CloneItem item = items.get(position);
        currentBoundItem = item;
        holder.bind(item);

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onItemClick(item);
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                return longClickListener.onItemLongClick(item, holder.getAdapterPosition());
            }
            return false;
        });

        holder.deleteBtn.setOnClickListener(v -> {
            if (deleteListener != null) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    deleteListener.onDeleteClone(item, pos);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).cloneId.hashCode();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Replace the entire data set with DiffUtil animation.
     */
    public void setClones(@NonNull List<CloneItem> newItems) {
        List<CloneItem> filtered = applyFilter(newItems, currentFilter);
        CloneDiffCallback diff = new CloneDiffCallback(items, filtered);
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(diff);

        items.clear();
        items.addAll(filtered);
        result.dispatchUpdatesTo(this);
    }

    /**
     * Remove an item at the given adapter position and notify the RecyclerView.
     */
    public void removeItem(int position) {
        if (position >= 0 && position < items.size()) {
            items.remove(position);
            notifyItemRemoved(position);
            // Update positions of remaining items
            notifyItemRangeChanged(position, items.size() - position);
        }
    }

    /**
     * Get the item at the given position.
     */
    @Nullable
    public CloneItem getItem(int position) {
        if (position >= 0 && position < items.size()) {
            return items.get(position);
        }
        return null;
    }

    /**
     * Filter clones by clone name or source package (case-insensitive).
     */
    public void filterClones(@Nullable String query) {
        currentFilter = (query != null && !query.trim().isEmpty())
                ? query.trim().toLowerCase(Locale.US)
                : null;

        List<CloneItem> filtered = applyFilter(items, currentFilter);
        CloneDiffCallback diff = new CloneDiffCallback(items, filtered);
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(diff);

        items.clear();
        items.addAll(filtered);
        result.dispatchUpdatesTo(this);
    }

    /**
     * Sort modes for the clone list.
     */
    public enum SortMode {
        NAME_ASC, NAME_DESC, DATE_DESC, DATE_ASC, SIZE_DESC, SIZE_ASC
    }

    /**
     * Sort the list and refresh.
     */
    public void sortClones(@NonNull SortMode mode) {
        Comparator<CloneItem> comparator;
        switch (mode) {
            case NAME_DESC:
                comparator = (a, b) -> b.cloneName.compareToIgnoreCase(a.cloneName);
                break;
            case DATE_DESC:
                comparator = (a, b) -> Long.compare(b.createdAt, a.createdAt);
                break;
            case DATE_ASC:
                comparator = (a, b) -> Long.compare(a.createdAt, b.createdAt);
                break;
            case SIZE_DESC:
                comparator = (a, b) -> Long.compare(b.sizeBytes, a.sizeBytes);
                break;
            case SIZE_ASC:
                comparator = (a, b) -> Long.compare(a.sizeBytes, b.sizeBytes);
                break;
            case NAME_ASC:
            default:
                comparator = (a, b) -> a.cloneName.compareToIgnoreCase(b.cloneName);
                break;
        }
        Collections.sort(items, comparator);
        notifyDataSetChanged();
    }

    /**
     * Handle a context menu item selection from the clone list.
     *
     * @return {@code true} if the item was handled
     */
    public boolean handleContextMenuItem(@NonNull MenuItem item) {
        if (contextMenuListener != null && currentBoundItem != null) {
            return contextMenuListener.onContextItemSelected(item, currentBoundItem);
        }
        return false;
    }

    // ── Setters ────────────────────────────────────────────────────────────

    public void setOnItemClickListener(@Nullable OnItemClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnItemLongClickListener(@Nullable OnItemLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setOnDeleteListener(@Nullable OnDeleteListener listener) {
        this.deleteListener = listener;
    }

    public void setOnContextMenuListener(@Nullable OnContextMenuListener listener) {
        this.contextMenuListener = listener;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    @NonNull
    private List<CloneItem> applyFilter(@NonNull List<CloneItem> source,
                                        @Nullable String filter) {
        if (filter == null) return new ArrayList<>(source);
        List<CloneItem> out = new ArrayList<>(source.size());
        for (CloneItem ci : source) {
            if (ci.cloneName.toLowerCase(Locale.US).contains(filter)
                    || ci.sourcePackage.toLowerCase(Locale.US).contains(filter)) {
                out.add(ci);
            }
        }
        return out;
    }

    @NonNull
    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB"};
        int idx = 0;
        double sz = bytes;
        while (sz >= 1024 && idx < units.length - 1) { sz /= 1024; idx++; }
        return String.format(Locale.US, "%,.1f %s", sz, units[idx]);
    }
}

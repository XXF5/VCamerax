package com.dualspace.obs.ui.adapters;

import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.dualspace.obs.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RecyclerView adapter for OBS scene sources.
 *
 * <p>Each item shows a source icon, name, type label, and an enabled/disabled
 * toggle switch.  Items can be reordered via drag-and-drop using an
 * {@link androidx.recyclerview.widget.ItemTouchHelper}.</p>
 *
 * <p>Setup example:</p>
 * <pre>{@code
 * ItemTouchHelper.Callback callback = new SourceAdapter.DragCallback(adapter);
 * new ItemTouchHelper(callback).attachToRecyclerView(recyclerView);
 * }</pre>
 */
public class SourceAdapter extends RecyclerView.Adapter<SourceAdapter.SourceViewHolder> {

    // ── Data model ─────────────────────────────────────────────────────────

    /**
     * Represents a single OBS source (e.g. camera, mic, screen capture, image).
     */
    public static class SourceItem {
        @NonNull public final String id;
        @NonNull public final String name;
        @NonNull public final String type;  // e.g. "camera", "audio_input", "window_capture"
        public final boolean enabled;
        public final int iconRes;  // drawable resource id for the source type icon

        public SourceItem(@NonNull String id,
                          @NonNull String name,
                          @NonNull String type,
                          boolean enabled,
                          int iconRes) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.enabled = enabled;
            this.iconRes = iconRes;
        }

        /**
         * Convenience factory for common source types.
         */
        @NonNull
        public static SourceItem camera(@NonNull String id, @NonNull String name, boolean enabled) {
            return new SourceItem(id, name, "camera", enabled, R.drawable.ic_source_camera);
        }

        @NonNull
        public static SourceItem microphone(@NonNull String id, @NonNull String name, boolean enabled) {
            return new SourceItem(id, name, "audio_input", enabled, R.drawable.ic_source_mic);
        }

        @NonNull
        public static SourceItem screenCapture(@NonNull String id, @NonNull String name, boolean enabled) {
            return new SourceItem(id, name, "screen_capture", enabled, R.drawable.ic_source_screen);
        }

        @NonNull
        public static SourceItem windowCapture(@NonNull String id, @NonNull String name, boolean enabled) {
            return new SourceItem(id, name, "window_capture", enabled, R.drawable.ic_source_window);
        }

        @NonNull
        public static SourceItem image(@NonNull String id, @NonNull String name, boolean enabled) {
            return new SourceItem(id, name, "image_source", enabled, R.drawable.ic_source_image);
        }

        @NonNull
        public static SourceItem text(@NonNull String id, @NonNull String name, boolean enabled) {
            return new SourceItem(id, name, "text_gdiplus", enabled, R.drawable.ic_source_text);
        }

        @NonNull
        public static SourceItem mediaFile(@NonNull String id, @NonNull String name, boolean enabled) {
            return new SourceItem(id, name, "ffmpeg_source", enabled, R.drawable.ic_source_media);
        }
    }

    // ── Listener interfaces ────────────────────────────────────────────────

    /** Simple click to select a source. */
    public interface OnSourceClickListener {
        void onSourceClick(@NonNull SourceItem source, int position);
    }

    /** Long-click for source options (properties, rename, remove, etc.). */
    public interface OnSourceLongClickListener {
        boolean onSourceLongClick(@NonNull SourceItem source, int position);
    }

    /** Toggle the enabled state of a source. */
    public interface OnSourceToggleListener {
        void onSourceToggled(@NonNull SourceItem source, int position, boolean newState);
    }

    /** Notified after a drag-and-drop reorder completes. */
    public interface OnSourceReorderListener {
        void onSourcesReordered(@NonNull List<SourceItem> newOrder);
    }

    // ── Drag-and-drop callback ─────────────────────────────────────────────

    /**
     * An {@link androidx.recyclerview.widget.ItemTouchHelper.Callback} that
     * enables drag-to-reorder on the source list.
     *
     * <p>Only vertical drag is supported. Swipe is disabled.</p>
     */
    public static class DragCallback extends androidx.recyclerview.widget.ItemTouchHelper.Callback {

        private final SourceAdapter adapter;

        public DragCallback(@NonNull SourceAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder) {
            // Enable vertical drag only; no swipe
            int dragFlags = androidx.recyclerview.widget.ItemTouchHelper.UP
                    | androidx.recyclerview.widget.ItemTouchHelper.DOWN;
            int swipeFlags = 0;
            return makeMovementFlags(dragFlags, swipeFlags);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder source,
                              @NonNull RecyclerView.ViewHolder target) {
            int fromPos = source.getAdapterPosition();
            int toPos = target.getAdapterPosition();
            if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) {
                return false;
            }
            adapter.onItemMove(fromPos, toPos);
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            // Swipe is disabled
        }

        @Override
        public boolean isLongPressDragEnabled() {
            // We handle drag via the handle ImageView, not long-press
            return false;
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            adapter.onDragFinished();
        }
    }

    // ── ViewHolder ─────────────────────────────────────────────────────────

    public class SourceViewHolder extends RecyclerView.ViewHolder {

        final ImageView iconView;
        final TextView nameView;
        final TextView typeView;
        final Switch enabledSwitch;
        final ImageView dragHandle;

        public SourceViewHolder(@NonNull View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.iv_source_icon);
            nameView = itemView.findViewById(R.id.tv_source_name);
            typeView = itemView.findViewById(R.id.tv_source_type);
            enabledSwitch = itemView.findViewById(R.id.switch_source_enabled);
            dragHandle = itemView.findViewById(R.id.iv_drag_handle);
        }

        public void bind(@NonNull SourceItem item, int position) {
            nameView.setText(item.name);
            typeView.setText(capitalizeType(item.type));

            if (item.iconRes != 0) {
                iconView.setImageResource(item.iconRes);
                iconView.setVisibility(View.VISIBLE);
            } else {
                iconView.setVisibility(View.GONE);
            }

            // Avoid re-triggering the listener while setting state programmatically
            enabledSwitch.setOnCheckedChangeListener(null);
            enabledSwitch.setChecked(item.enabled);
            enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (toggleListener != null) {
                    int pos = getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        toggleListener.onSourceToggled(item, pos, isChecked);
                    }
                }
            });

            // Drag handle touch start
            dragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    // Request the ItemTouchHelper to start dragging
                    if (dragStartListener != null) {
                        dragStartListener.onStartDrag(this);
                    }
                }
                return false;
            });
        }
    }

    /**
     * Interface used to kick off a drag from the handle.
     */
    public interface OnStartDragListener {
        void onStartDrag(@NonNull SourceViewHolder holder);
    }

    // ── Instance state ─────────────────────────────────────────────────────

    private final List<SourceItem> sources = new ArrayList<>();

    @Nullable private OnSourceClickListener clickListener;
    @Nullable private OnSourceLongClickListener longClickListener;
    @Nullable private OnSourceToggleListener toggleListener;
    @Nullable private OnSourceReorderListener reorderListener;
    @Nullable private OnStartDragListener dragStartListener;

    // ── Constructor ────────────────────────────────────────────────────────

    public SourceAdapter() {
        setHasStableIds(true);
    }

    // ── Adapter overrides ──────────────────────────────────────────────────

    @NonNull
    @Override
    public SourceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view = inflater.inflate(R.layout.item_source_list, parent, false);
        return new SourceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SourceViewHolder holder, int position) {
        SourceItem item = sources.get(position);
        holder.bind(item, position);

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    clickListener.onSourceClick(sources.get(pos), pos);
                }
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    return longClickListener.onSourceLongClick(sources.get(pos), pos);
                }
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return sources.size();
    }

    @Override
    public long getItemId(int position) {
        return sources.get(position).id.hashCode();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Replace the source list.
     */
    public void setSources(@NonNull List<SourceItem> newSources) {
        sources.clear();
        sources.addAll(newSources);
        notifyDataSetChanged();
    }

    /**
     * Add a source at the end of the list.
     */
    public void addSource(@NonNull SourceItem item) {
        sources.add(item);
        notifyItemInserted(sources.size() - 1);
    }

    /**
     * Remove a source by position.
     */
    public void removeSource(int position) {
        if (position >= 0 && position < sources.size()) {
            sources.remove(position);
            notifyItemRemoved(position);
        }
    }

    /**
     * Update a source at the given position (e.g. after toggling enabled).
     */
    public void updateSource(int position, @NonNull SourceItem updated) {
        if (position >= 0 && position < sources.size()) {
            sources.set(position, updated);
            notifyItemChanged(position);
        }
    }

    /**
     * Get a copy of the current source list.
     */
    @NonNull
    public List<SourceItem> getSources() {
        return new ArrayList<>(sources);
    }

    /**
     * Get item at position.
     */
    @Nullable
    public SourceItem getSource(int position) {
        if (position >= 0 && position < sources.size()) {
            return sources.get(position);
        }
        return null;
    }

    // ── Setters ────────────────────────────────────────────────────────────

    public void setOnSourceClickListener(@Nullable OnSourceClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnSourceLongClickListener(@Nullable OnSourceLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setOnSourceToggleListener(@Nullable OnSourceToggleListener listener) {
        this.toggleListener = listener;
    }

    public void setOnSourceReorderListener(@Nullable OnSourceReorderListener listener) {
        this.reorderListener = listener;
    }

    public void setOnStartDragListener(@Nullable OnStartDragListener listener) {
        this.dragStartListener = listener;
    }

    // ── Internal drag helpers ──────────────────────────────────────────────

    /**
     * Called by {@link DragCallback#onMove} to swap items and notify the RV.
     */
    void onItemMove(int fromPos, int toPos) {
        if (fromPos < toPos) {
            for (int i = fromPos; i < toPos; i++) {
                Collections.swap(sources, i, i + 1);
            }
        } else {
            for (int i = fromPos; i > toPos; i--) {
                Collections.swap(sources, i, i - 1);
            }
        }
        notifyItemMoved(fromPos, toPos);
    }

    /**
     * Called when a drag completes — notify the reorder listener.
     */
    void onDragFinished() {
        if (reorderListener != null) {
            reorderListener.onSourcesReordered(new ArrayList<>(sources));
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    @NonNull
    private static String capitalizeType(@NonNull String type) {
        // Replace underscores with spaces and capitalize
        String[] parts = type.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            if (part.length() > 0) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }
}

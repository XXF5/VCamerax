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
import androidx.recyclerview.widget.RecyclerView;

import com.dualspace.obs.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal RecyclerView adapter for OBS scene management.
 *
 * <p>Each item displays the scene name, source count, and an active-scene
 * indicator.  The list terminates with an "Add Scene" button.  Long-pressing
 * a scene opens a context menu with rename, duplicate, and delete actions.</p>
 *
 * <p>Layout XML:</p>
 * <ul>
 *   <li>{@code R.layout.item_scene_list} – individual scene card</li>
 *   <li>{@code R.layout.item_scene_add} – the "+" add button at the end</li>
 * </ul>
 *
 * <p>RecyclerView should use a horizontal LinearLayoutManager:</p>
 * <pre>{@code
 * recyclerView.setLayoutManager(
 *     new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
 * recyclerView.setAdapter(new SceneAdapter());
 * }</pre>
 */
public class SceneAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // ── View types ─────────────────────────────────────────────────────────

    private static final int TYPE_SCENE = 0;
    private static final int TYPE_ADD   = 1;

    // ── Data model ─────────────────────────────────────────────────────────

    /**
     * Represents an OBS scene.
     */
    public static class SceneItem {
        @NonNull public final String id;
        @NonNull public final String name;
        public final int sourceCount;
        public final boolean active;

        public SceneItem(@NonNull String id,
                         @NonNull String name,
                         int sourceCount,
                         boolean active) {
            this.id = id;
            this.name = name;
            this.sourceCount = sourceCount;
            this.active = active;
        }
    }

    // ── Context menu IDs ───────────────────────────────────────────────────

    public static final int MENU_RENAME    = 10;
    public static final int MENU_DUPLICATE = 11;
    public static final int MENU_DELETE    = 12;

    // ── Listener interfaces ────────────────────────────────────────────────

    /** Tap to switch to a scene. */
    public interface OnSceneClickListener {
        void onSceneClick(@NonNull SceneItem scene, int position);
    }

    /** Long-press for context menu. */
    public interface OnSceneLongClickListener {
        void onSceneLongClick(@NonNull SceneItem scene, int position);
    }

    /** Context menu item selected. */
    public interface OnSceneMenuListener {
        boolean onSceneMenuItemSelected(@NonNull MenuItem item,
                                        @NonNull SceneItem scene,
                                        int position);
    }

    /** "Add scene" button pressed. */
    public interface OnAddSceneClickListener {
        void onAddSceneClick();
    }

    // ── Scene ViewHolder ───────────────────────────────────────────────────

    public class SceneViewHolder extends RecyclerView.ViewHolder
            implements View.OnCreateContextMenuListener {

        final TextView nameView;
        final TextView sourceCountView;
        final ImageView activeIndicator;
        final View cardRoot;

        public SceneViewHolder(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.tv_scene_name);
            sourceCountView = itemView.findViewById(R.id.tv_scene_source_count);
            activeIndicator = itemView.findViewById(R.id.iv_scene_active_indicator);
            cardRoot = itemView.findViewById(R.id.scene_card_root);
            itemView.setOnCreateContextMenuListener(this);
        }

        public void bind(@NonNull SceneItem scene) {
            nameView.setText(scene.name);
            sourceCountView.setText(formatSourceCount(scene.sourceCount));

            if (scene.active) {
                activeIndicator.setVisibility(View.VISIBLE);
                cardRoot.setBackgroundResource(R.drawable.bg_scene_active);
            } else {
                activeIndicator.setVisibility(View.GONE);
                cardRoot.setBackgroundResource(R.drawable.bg_scene_inactive);
            }
        }

        @Override
        public void onCreateContextMenu(ContextMenu menu, View v,
                                        ContextMenu.ContextMenuInfo menuInfo) {
            int pos = getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos < scenes.size()) {
                SceneItem scene = scenes.get(pos);
                menu.setHeaderTitle(scene.name);
                menu.add(Menu.NONE, MENU_RENAME, Menu.NONE, "Rename");
                menu.add(Menu.NONE, MENU_DUPLICATE, Menu.NONE, "Duplicate");
                menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, "Delete");
            }
        }
    }

    // ── Add-Scene ViewHolder ───────────────────────────────────────────────

    public static class AddSceneViewHolder extends RecyclerView.ViewHolder {
        final View addBtn;

        public AddSceneViewHolder(@NonNull View itemView) {
            super(itemView);
            addBtn = itemView.findViewById(R.id.btn_add_scene);
        }
    }

    // ── Instance state ─────────────────────────────────────────────────────

    private final List<SceneItem> scenes = new ArrayList<>();
    @Nullable private SceneItem lastContextMenuScene;
    private int lastContextMenuPosition = -1;

    @Nullable private OnSceneClickListener clickListener;
    @Nullable private OnSceneLongClickListener longClickListener;
    @Nullable private OnSceneMenuListener menuListener;
    @Nullable private OnAddSceneClickListener addSceneClickListener;

    // ── Constructor ────────────────────────────────────────────────────────

    public SceneAdapter() {
        setHasStableIds(true);
    }

    // ── Adapter overrides ──────────────────────────────────────────────────

    @Override
    public int getItemViewType(int position) {
        // Last item is the "Add" button
        if (position == scenes.size()) {
            return TYPE_ADD;
        }
        return TYPE_SCENE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_ADD) {
            View view = inflater.inflate(R.layout.item_scene_add, parent, false);
            return new AddSceneViewHolder(view);
        }
        View view = inflater.inflate(R.layout.item_scene_list, parent, false);
        return new SceneViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_ADD) {
            AddSceneViewHolder addHolder = (AddSceneViewHolder) holder;
            addHolder.addBtn.setOnClickListener(v -> {
                if (addSceneClickListener != null) {
                    addSceneClickListener.onAddSceneClick();
                }
            });
            return;
        }

        SceneViewHolder sceneHolder = (SceneViewHolder) holder;
        SceneItem scene = scenes.get(position);
        sceneHolder.bind(scene);

        sceneHolder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onSceneClick(scene, position);
            }
        });

        sceneHolder.itemView.setOnLongClickListener(v -> {
            lastContextMenuScene = scene;
            lastContextMenuPosition = position;
            if (longClickListener != null) {
                longClickListener.onSceneLongClick(scene, position);
            }
            return false; // return false so the context menu still appears
        });
    }

    @Override
    public int getItemCount() {
        // +1 for the add button
        return scenes.size() + 1;
    }

    @Override
    public long getItemId(int position) {
        if (position == scenes.size()) {
            return Long.MAX_VALUE; // stable ID for the add button
        }
        return scenes.get(position).id.hashCode();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Replace all scenes.
     */
    public void setScenes(@NonNull List<SceneItem> newScenes) {
        scenes.clear();
        scenes.addAll(newScenes);
        notifyDataSetChanged();
    }

    /**
     * Add a new scene to the list.
     */
    public void addScene(@NonNull SceneItem scene) {
        scenes.add(scene);
        notifyItemInserted(scenes.size() - 1);
    }

    /**
     * Remove a scene by position.
     */
    public void removeScene(int position) {
        if (position >= 0 && position < scenes.size()) {
            scenes.remove(position);
            notifyItemRemoved(position);
            // The add button stays at the end, so refresh its position too
            notifyItemRangeChanged(position, scenes.size() - position + 1);
        }
    }

    /**
     * Update a scene at the given position (e.g. after renaming).
     */
    public void updateScene(int position, @NonNull SceneItem updated) {
        if (position >= 0 && position < scenes.size()) {
            scenes.set(position, updated);
            notifyItemChanged(position);
        }
    }

    /**
     * Set the active scene by its ID. All other scenes will be marked inactive.
     */
    public void setActiveScene(@NonNull String sceneId) {
        for (int i = 0; i < scenes.size(); i++) {
            SceneItem old = scenes.get(i);
            if (old.id.equals(sceneId) && old.active) {
                continue; // already active
            }
            boolean newActive = old.id.equals(sceneId);
            if (newActive != old.active) {
                scenes.set(i, new SceneItem(old.id, old.name, old.sourceCount, newActive));
                notifyItemChanged(i);
            }
        }
    }

    /**
     * Handle a context menu selection from the hosting Activity/Fragment.
     *
     * @return {@code true} if handled
     */
    public boolean handleContextMenuItem(@NonNull MenuItem item) {
        if (menuListener != null && lastContextMenuScene != null
                && lastContextMenuPosition >= 0) {
            return menuListener.onSceneMenuItemSelected(
                    item, lastContextMenuScene, lastContextMenuPosition);
        }
        return false;
    }

    /**
     * Get a scene by position.
     */
    @Nullable
    public SceneItem getScene(int position) {
        if (position >= 0 && position < scenes.size()) {
            return scenes.get(position);
        }
        return null;
    }

    /**
     * Get a copy of the scene list.
     */
    @NonNull
    public List<SceneItem> getScenes() {
        return new ArrayList<>(scenes);
    }

    // ── Setters ────────────────────────────────────────────────────────────

    public void setOnSceneClickListener(@Nullable OnSceneClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnSceneLongClickListener(@Nullable OnSceneLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setOnSceneMenuListener(@Nullable OnSceneMenuListener listener) {
        this.menuListener = listener;
    }

    public void setOnAddSceneClickListener(@Nullable OnAddSceneClickListener listener) {
        this.addSceneClickListener = listener;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    @NonNull
    private static String formatSourceCount(int count) {
        if (count == 0) return "No sources";
        if (count == 1) return "1 source";
        return count + " sources";
    }
}

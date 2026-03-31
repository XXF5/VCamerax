package com.dualspace.obs.obs;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Scene and source collection management.
 * Supports creating scenes, adding/removing sources, transitions, and persistence.
 */
public class SceneCollection {

    private static final String TAG = "SceneCollection";
    private static final String PREFS_NAME = "dualspace_scenes";
    private static final String KEY_SCENES = "scenes_json";

    // ── Source Types ────────────────────────────────────────────────────────
    public enum SourceType {
        SCREEN_CAPTURE,
        CAMERA,
        IMAGE,
        TEXT,
        AUDIO,
        COLOR,
        WINDOW,
        BROWSER
    }

    // ── Transition Types ────────────────────────────────────────────────────
    public enum TransitionType {
        CUT,      // Instant switch
        FADE,     // Cross-fade
        SWIPE,    // Directional swipe
        STINGER   // Video/audio stinger overlay
    }

    // ── Source ──────────────────────────────────────────────────────────────
    public static class Source {
        private String id;
        private SourceType type;
        private String name;
        private float x;          // Position X (0.0 - 1.0 relative)
        private float y;          // Position Y
        private float width;      // Size width (0.0 - 1.0 relative)
        private float height;     // Size height
        private int zOrder;       // Drawing order (higher = on top)
        private boolean enabled;
        private float opacity;    // 0.0 - 1.0
        private boolean audible;
        private float volume;     // 0.0 - 1.0
        private String sourceConfig; // JSON string for type-specific config

        public Source() {
            this.id = UUID.randomUUID().toString();
            this.enabled = true;
            this.opacity = 1.0f;
            this.audible = true;
            this.volume = 1.0f;
            this.x = 0f;
            this.y = 0f;
            this.width = 1f;
            this.height = 1f;
            this.zOrder = 0;
        }

        public Source(SourceType type, String name) {
            this();
            this.type = type;
            this.name = name;
        }

        public Source(SourceType type, String name, float x, float y, float w, float h) {
            this(type, name);
            this.x = x;
            this.y = y;
            this.width = w;
            this.height = h;
        }

        // Getters
        public String getId() { return id; }
        public SourceType getType() { return type; }
        public String getName() { return name; }
        public float getX() { return x; }
        public float getY() { return y; }
        public float getWidth() { return width; }
        public float getHeight() { return height; }
        public int getZOrder() { return zOrder; }
        public boolean isEnabled() { return enabled; }
        public float getOpacity() { return opacity; }
        public boolean isAudible() { return audible; }
        public float getVolume() { return volume; }
        public String getSourceConfig() { return sourceConfig; }

        // Setters
        public void setType(SourceType type) { this.type = type; }
        public void setName(String name) { this.name = name; }
        public void setPosition(float x, float y) { this.x = x; this.y = y; }
        public void setSize(float w, float h) { this.width = w; this.height = h; }
        public void setZOrder(int zOrder) { this.zOrder = zOrder; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public void setOpacity(float opacity) { this.opacity = clamp(opacity, 0f, 1f); }
        public void setAudible(boolean audible) { this.audible = audible; }
        public void setVolume(float volume) { this.volume = clamp(volume, 0f, 1f); }
        public void setSourceConfig(String config) { this.sourceConfig = config; }

        // ── JSON Serialization ──────────────────────────────────────────────

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("type", type.name());
            json.put("name", name);
            json.put("x", x);
            json.put("y", y);
            json.put("width", width);
            json.put("height", height);
            json.put("zOrder", zOrder);
            json.put("enabled", enabled);
            json.put("opacity", opacity);
            json.put("audible", audible);
            json.put("volume", volume);
            if (sourceConfig != null) {
                json.put("sourceConfig", sourceConfig);
            }
            return json;
        }

        public static Source fromJson(JSONObject json) throws JSONException {
            Source source = new Source();
            source.id = json.optString("id", UUID.randomUUID().toString());
            source.type = SourceType.valueOf(json.optString("type", "SCREEN_CAPTURE"));
            source.name = json.optString("name", "Unknown");
            source.x = (float) json.optDouble("x", 0);
            source.y = (float) json.optDouble("y", 0);
            source.width = (float) json.optDouble("width", 1);
            source.height = (float) json.optDouble("height", 1);
            source.zOrder = json.optInt("zOrder", 0);
            source.enabled = json.optBoolean("enabled", true);
            source.opacity = (float) json.optDouble("opacity", 1.0);
            source.audible = json.optBoolean("audible", true);
            source.volume = (float) json.optDouble("volume", 1.0);
            source.sourceConfig = json.optString("sourceConfig", null);
            return source;
        }
    }

    // ── Scene ───────────────────────────────────────────────────────────────
    public static class Scene {
        private String id;
        private String name;
        private final List<Source> sources = new ArrayList<>();

        public Scene() {
            this.id = UUID.randomUUID().toString();
        }

        public Scene(String name) {
            this();
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<Source> getSources() { return sources; }

        // Source management
        public Source addSource(Source source) {
            sources.add(source);
            reorderSources();
            return source;
        }

        public boolean removeSource(String sourceId) {
            Iterator<Source> it = sources.iterator();
            while (it.hasNext()) {
                if (it.next().getId().equals(sourceId)) {
                    it.remove();
                    return true;
                }
            }
            return false;
        }

        public Source getSource(String sourceId) {
            for (Source s : sources) {
                if (s.getId().equals(sourceId)) return s;
            }
            return null;
        }

        public void reorderSources() {
            Collections.sort(sources, (a, b) -> Integer.compare(a.getZOrder(), b.getZOrder()));
        }

        // ── JSON ────────────────────────────────────────────────────────────

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("name", name);
            JSONArray sourcesArray = new JSONArray();
            for (Source source : sources) {
                sourcesArray.put(source.toJson());
            }
            json.put("sources", sourcesArray);
            return json;
        }

        public static Scene fromJson(JSONObject json) throws JSONException {
            Scene scene = new Scene();
            scene.id = json.optString("id", UUID.randomUUID().toString());
            scene.name = json.optString("name", "Untitled Scene");
            JSONArray sourcesArray = json.optJSONArray("sources");
            if (sourcesArray != null) {
                for (int i = 0; i < sourcesArray.length(); i++) {
                    try {
                        Source source = Source.fromJson(sourcesArray.getJSONObject(i));
                        scene.sources.add(source);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to parse source at index " + i, e);
                    }
                }
            }
            return scene;
        }
    }

    // ── SceneCollection Fields ──────────────────────────────────────────────
    private final Context mAppContext;
    private final List<Scene> mScenes = new ArrayList<>();
    private int mCurrentSceneIndex = 0;
    private TransitionType mTransition = TransitionType.CUT;
    private int mTransitionDurationMs = 300; // for FADE/SWIPE

    private OnSceneChangeListener mSceneChangeListener;

    public interface OnSceneChangeListener {
        void onSceneSwitched(Scene newScene, Scene oldScene);
        void onSourceAdded(Scene scene, Source source);
        void onSourceRemoved(Scene scene, String sourceId);
        void onSceneAdded(Scene scene);
        void onSceneRemoved(int index);
    }

    // ── Constructor ─────────────────────────────────────────────────────────

    public SceneCollection(Context context) {
        mAppContext = context.getApplicationContext();
    }

    // ── Scene Management ────────────────────────────────────────────────────

    /**
     * Create a new scene with the given name and add it to the collection.
     * @return the created Scene
     */
    public Scene createScene(String name) {
        Scene scene = new Scene(name);
        mScenes.add(scene);
        if (mSceneChangeListener != null) {
            mSceneChangeListener.onSceneAdded(scene);
        }
        Log.i(TAG, "Scene created: " + name + " (total: " + mScenes.size() + ")");
        return scene;
    }

    /**
     * Remove a scene by index.
     */
    public void removeScene(int index) {
        if (index < 0 || index >= mScenes.size()) return;
        if (mScenes.size() <= 1) {
            Log.w(TAG, "Cannot remove the last scene");
            return;
        }

        mScenes.remove(index);
        if (mCurrentSceneIndex >= mScenes.size()) {
            mCurrentSceneIndex = mScenes.size() - 1;
        }

        if (mSceneChangeListener != null) {
            mSceneChangeListener.onSceneRemoved(index);
        }
    }

    /**
     * Switch to the scene at the given index.
     */
    public void switchScene(int index) {
        if (index < 0 || index >= mScenes.size()) {
            Log.w(TAG, "Invalid scene index: " + index);
            return;
        }
        if (index == mCurrentSceneIndex) return;

        Scene oldScene = mScenes.get(mCurrentSceneIndex);
        mCurrentSceneIndex = index;
        Scene newScene = mScenes.get(mCurrentSceneIndex);

        if (mSceneChangeListener != null) {
            mSceneChangeListener.onSceneSwitched(newScene, oldScene);
        }
        Log.i(TAG, "Switched to scene: " + newScene.getName());
    }

    /**
     * Switch to a scene by its ID.
     */
    public void switchSceneById(String sceneId) {
        for (int i = 0; i < mScenes.size(); i++) {
            if (mScenes.get(i).getId().equals(sceneId)) {
                switchScene(i);
                return;
            }
        }
    }

    /**
     * Get the current scene.
     */
    public Scene getCurrentScene() {
        if (mScenes.isEmpty()) return null;
        return mScenes.get(mCurrentSceneIndex);
    }

    /**
     * Get scene at index.
     */
    public Scene getScene(int index) {
        if (index < 0 || index >= mScenes.size()) return null;
        return mScenes.get(index);
    }

    /**
     * Get all scenes.
     */
    public List<Scene> getScenes() {
        return Collections.unmodifiableList(mScenes);
    }

    /**
     * Get the current scene index.
     */
    public int getCurrentSceneIndex() {
        return mCurrentSceneIndex;
    }

    /**
     * Get the number of scenes.
     */
    public int getSceneCount() {
        return mScenes.size();
    }

    // ── Source Management ───────────────────────────────────────────────────

    /**
     * Add a source to the scene at the given index.
     */
    public Source addSource(int sceneIndex, Source source) {
        Scene scene = getScene(sceneIndex);
        if (scene == null) {
            Log.w(TAG, "Invalid scene index: " + sceneIndex);
            return null;
        }
        Source added = scene.addSource(source);
        if (mSceneChangeListener != null) {
            mSceneChangeListener.onSourceAdded(scene, added);
        }
        return added;
    }

    /**
     * Remove a source from the scene at the given index.
     */
    public boolean removeSource(int sceneIndex, String sourceId) {
        Scene scene = getScene(sceneIndex);
        if (scene == null) return false;
        boolean removed = scene.removeSource(sourceId);
        if (removed && mSceneChangeListener != null) {
            mSceneChangeListener.onSourceRemoved(scene, sourceId);
        }
        return removed;
    }

    /**
     * Reorder sources in a scene by z-order.
     */
    public void reorderSources(int sceneIndex) {
        Scene scene = getScene(sceneIndex);
        if (scene != null) {
            scene.reorderSources();
        }
    }

    // ── Transitions ─────────────────────────────────────────────────────────

    public void setTransition(TransitionType type) {
        mTransition = type;
    }

    public TransitionType getTransition() {
        return mTransition;
    }

    public void setTransitionDurationMs(int durationMs) {
        mTransitionDurationMs = Math.max(50, Math.min(2000, durationMs));
    }

    public int getTransitionDurationMs() {
        return mTransitionDurationMs;
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    /**
     * Save all scenes to SharedPreferences.
     */
    public void save() {
        try {
            SharedPreferences prefs = mAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONObject root = new JSONObject();
            root.put("currentSceneIndex", mCurrentSceneIndex);
            root.put("transition", mTransition.name());
            root.put("transitionDurationMs", mTransitionDurationMs);

            JSONArray scenesArray = new JSONArray();
            for (Scene scene : mScenes) {
                scenesArray.put(scene.toJson());
            }
            root.put("scenes", scenesArray);

            prefs.edit().putString(KEY_SCENES, root.toString()).apply();
            Log.i(TAG, "Saved " + mScenes.size() + " scenes");

        } catch (JSONException e) {
            Log.e(TAG, "Failed to save scenes", e);
        }
    }

    /**
     * Load scenes from SharedPreferences.
     */
    public void load() {
        try {
            SharedPreferences prefs = mAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_SCENES, null);
            if (json == null || json.isEmpty()) {
                // Create default scene
                createDefaultScene();
                return;
            }

            JSONObject root = new JSONObject(json);
            mCurrentSceneIndex = root.optInt("currentSceneIndex", 0);
            mTransition = TransitionType.valueOf(root.optString("transition", "CUT"));
            mTransitionDurationMs = root.optInt("transitionDurationMs", 300);

            JSONArray scenesArray = root.optJSONArray("scenes");
            mScenes.clear();
            if (scenesArray != null) {
                for (int i = 0; i < scenesArray.length(); i++) {
                    try {
                        Scene scene = Scene.fromJson(scenesArray.getJSONObject(i));
                        mScenes.add(scene);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to parse scene at index " + i, e);
                    }
                }
            }

            if (mScenes.isEmpty()) {
                createDefaultScene();
            }

            // Clamp current scene index
            if (mCurrentSceneIndex >= mScenes.size()) {
                mCurrentSceneIndex = 0;
            }

            Log.i(TAG, "Loaded " + mScenes.size() + " scenes");

        } catch (JSONException e) {
            Log.e(TAG, "Failed to load scenes", e);
            createDefaultScene();
        }
    }

    // ── Listener ────────────────────────────────────────────────────────────

    public void setOnSceneChangeListener(OnSceneChangeListener listener) {
        mSceneChangeListener = listener;
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private void createDefaultScene() {
        mScenes.clear();

        Scene defaultScene = new Scene("Main Scene");
        // Add a default screen capture source
        Source screenSource = new Source(
                SourceType.SCREEN_CAPTURE,
                "Screen Capture",
                0f, 0f, 1f, 1f
        );
        screenSource.setZOrder(0);
        defaultScene.addSource(screenSource);

        mScenes.add(defaultScene);
        mCurrentSceneIndex = 0;
        save();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

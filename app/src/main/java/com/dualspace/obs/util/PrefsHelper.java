package com.dualspace.obs.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.util.Log;

import java.util.Set;

/**
 * SharedPreferences helper with a singleton pattern.
 * Provides typed get/set methods with default values and support
 * for change listeners.
 */
public class PrefsHelper {

    private static final String TAG = "PrefsHelper";
    private static final String DEFAULT_PREFS_NAME = "dualspace_obs_prefs";

    private static volatile PrefsHelper instance;

    private final SharedPreferences prefs;
    private final SharedPreferences.Editor editor;

    // ──────────────── Singleton ────────────────

    private PrefsHelper(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(DEFAULT_PREFS_NAME, Context.MODE_PRIVATE);
        editor = prefs.edit();
    }

    /**
     * Initialize the singleton. Must be called before first use,
     * typically in Application.onCreate().
     *
     * @param context application context
     * @return the singleton instance
     */
    public static PrefsHelper init(Context context) {
        if (instance == null) {
            synchronized (PrefsHelper.class) {
                if (instance == null) {
                    instance = new PrefsHelper(context);
                    Log.i(TAG, "PrefsHelper initialized");
                }
            }
        }
        return instance;
    }

    /**
     * Get the singleton instance. Must call {@link #init(Context)} first.
     *
     * @return the singleton instance
     * @throws IllegalStateException if not initialized
     */
    public static PrefsHelper getInstance() {
        if (instance == null) {
            throw new IllegalStateException(
                    "PrefsHelper not initialized. Call PrefsHelper.init(context) first.");
        }
        return instance;
    }

    // ──────────────── String ────────────────

    /**
     * Get a string value.
     *
     * @param key          preference key
     * @param defaultValue fallback value
     * @return stored or default value
     */
    public String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    /**
     * Store a string value.
     *
     * @param key   preference key
     * @param value value to store
     */
    public void putString(String key, String value) {
        editor.putString(key, value).apply();
    }

    // ──────────────── Integer ────────────────

    /**
     * Get an integer value.
     *
     * @param key          preference key
     * @param defaultValue fallback value
     * @return stored or default value
     */
    public int getInt(String key, int defaultValue) {
        return prefs.getInt(key, defaultValue);
    }

    /**
     * Store an integer value.
     *
     * @param key   preference key
     * @param value value to store
     */
    public void putInt(String key, int value) {
        editor.putInt(key, value).apply();
    }

    // ──────────────── Long ────────────────

    /**
     * Get a long value.
     *
     * @param key          preference key
     * @param defaultValue fallback value
     * @return stored or default value
     */
    public long getLong(String key, long defaultValue) {
        return prefs.getLong(key, defaultValue);
    }

    /**
     * Store a long value.
     *
     * @param key   preference key
     * @param value value to store
     */
    public void putLong(String key, long value) {
        editor.putLong(key, value).apply();
    }

    // ──────────────── Float ────────────────

    /**
     * Get a float value.
     *
     * @param key          preference key
     * @param defaultValue fallback value
     * @return stored or default value
     */
    public float getFloat(String key, float defaultValue) {
        return prefs.getFloat(key, defaultValue);
    }

    /**
     * Store a float value.
     *
     * @param key   preference key
     * @param value value to store
     */
    public void putFloat(String key, float value) {
        editor.putFloat(key, value).apply();
    }

    // ──────────────── Boolean ────────────────

    /**
     * Get a boolean value.
     *
     * @param key          preference key
     * @param defaultValue fallback value
     * @return stored or default value
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    /**
     * Store a boolean value.
     *
     * @param key   preference key
     * @param value value to store
     */
    public void putBoolean(String key, boolean value) {
        editor.putBoolean(key, value).apply();
    }

    // ──────────────── StringSet ────────────────

    /**
     * Get a string set value.
     * <p>Note: the returned set is a copy; modifications won't affect storage.
     *
     * @param key          preference key
     * @param defaultValue fallback value
     * @return stored or default value
     */
    public Set<String> getStringSet(String key, Set<String> defaultValue) {
        return prefs.getStringSet(key, defaultValue);
    }

    /**
     * Store a string set value.
     *
     * @param key    preference key
     * @param value  value to store (must be a new Set, not the one returned by getStringSet)
     */
    public void putStringSet(String key, Set<String> value) {
        editor.putStringSet(key, value).apply();
    }

    // ──────────────── Remove / Clear / Contains ────────────────

    /**
     * Remove a single preference.
     *
     * @param key preference key
     */
    public void remove(String key) {
        editor.remove(key).apply();
    }

    /**
     * Clear all preferences.
     */
    public void clear() {
        editor.clear().apply();
        Log.w(TAG, "All preferences cleared");
    }

    /**
     * Check if a preference key exists.
     *
     * @param key preference key
     * @return true if the key exists in preferences
     */
    public boolean contains(String key) {
        return prefs.contains(key);
    }

    // ──────────────── Listeners ────────────────

    /**
     * Register a preference change listener.
     *
     * @param listener the listener to register
     */
    public void registerListener(OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    /**
     * Unregister a preference change listener.
     *
     * @param listener the listener to unregister
     */
    public void unregisterListener(OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    // ──────────────── Bulk / Transactions ────────────────

    /**
     * Begin a batch edit. Callers should follow with put*() calls,
     * then call {@link #applyBatch()} or {@link #commitBatch()}.
     *
     * @return this PrefsHelper instance for chaining
     */
    public PrefsHelper beginBatch() {
        // Note: SharedPreferences.Editor is already created once; just reuse it.
        return this;
    }

    /**
     * Apply (async) a batch of edits.
     */
    public void applyBatch() {
        editor.apply();
    }

    /**
     * Commit (sync) a batch of edits.
     *
     * @return true if the values were successfully written
     */
    public boolean commitBatch() {
        return editor.commit();
    }

    // ──────────────── Direct Access ────────────────

    /**
     * Get the underlying SharedPreferences instance for advanced use.
     *
     * @return the SharedPreferences
     */
    public SharedPreferences getPreferences() {
        return prefs;
    }
}

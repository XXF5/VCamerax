/*
 * VirtualEnvironment.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Manages the virtual file-system environment in which cloned applications
 * operate.  Provides path translation between virtual (per-app) and real
 * (host) paths, environment-variable management, and virtual content-
 * provider registration.
 */
package com.dualspace.obs.engine;

import android.content.ContentProvider;
import android.content.Context;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VirtualEnvironment {

    private static final String TAG = "VirtualEnv";

    /** Top-level directory name inside the app's data folder. */
    private static final String VIRTUAL_ROOT_DIR = "virtual_env";

    // Standard Android sub-directories that every app expects
    private static final String[] STANDARD_DIRS = {
            "data",
            "cache",
            "files",
            "databases",
            "shared_prefs",
            "code_cache",
            "no_backup",
            "external_files"
    };

    // ──────────────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────────────

    @Nullable
    private File mRootDir;

    /** userId → virtual-root for that user. */
    private final ConcurrentHashMap<Integer, File> mUserRoots = new ConcurrentHashMap<>();

    /** Virtual path prefix → real path prefix. */
    private final ConcurrentHashMap<String, String> mPathMappings = new ConcurrentHashMap<>();

    /** Virtual environment variables (name → value). */
    private final ConcurrentHashMap<String, String> mEnvVars = new ConcurrentHashMap<>();

    /** Registered virtual content-provider authorities → provider class names. */
    private final ConcurrentHashMap<String, String> mVirtualProviders = new ConcurrentHashMap<>();

    private volatile boolean mIsSetup;

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Set up the virtual environment for a given user.
     *
     * @param userId Android user id (0 = primary)
     */
    public synchronized void setup(int userId) {
        if (mIsSetup) {
            Log.w(TAG, "Environment already set up for user " + userId);
            return;
        }

        Context ctx = DualSpaceApplication.getAppContext();
        File baseDir = ctx.getFilesDir();

        mRootDir = new File(baseDir, VIRTUAL_ROOT_DIR + "/user_" + userId);
        if (!mRootDir.exists() && !mRootDir.mkdirs()) {
            throw new IllegalStateException("Cannot create virtual root: " + mRootDir);
        }

        mUserRoots.put(userId, mRootDir);
        createVirtualDirs(mRootDir);

        // Default environment variables
        initDefaultEnvVars(mRootDir);

        // Register built-in virtual content providers
        registerBuiltinProviders();

        mIsSetup = true;
        Log.i(TAG, "Virtual environment set up at " + mRootDir.getAbsolutePath());
    }

    /**
     * Create the standard Android directory structure inside the given root.
     *
     * @param root parent directory
     */
    public void createVirtualDirs(@NonNull File root) {
        for (String dirName : STANDARD_DIRS) {
            File dir = new File(root, dirName);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Failed to create virtual dir: " + dir.getAbsolutePath());
            } else {
                // Set permissions: owner rwx
                dir.setReadable(true, false);
                dir.setWritable(true, false);
                dir.setExecutable(true, false);
            }
        }

        // Also create a per-app package directory template
        File appsDir = new File(root, "apps");
        if (!appsDir.exists() && !appsDir.mkdirs()) {
            Log.e(TAG, "Failed to create apps dir: " + appsDir.getAbsolutePath());
        }

        Log.d(TAG, "Virtual directories created under " + root);
    }

    /**
     * Translate a real (host) path into the corresponding virtual path.
     *
     * @param realPath absolute real path on the host filesystem
     * @return virtual path, or {@code null} if no mapping exists
     */
    @Nullable
    public String getVirtualPath(@NonNull String realPath) {
        for (Map.Entry<String, String> entry : mPathMappings.entrySet()) {
            String realPrefix = entry.getValue(); // real prefix
            String virtPrefix = entry.getKey();   // virtual prefix

            if (realPath.startsWith(realPrefix)) {
                return virtPrefix + realPath.substring(realPrefix.length());
            }
        }

        // Fallback: map under virtual root if the real path is inside the app data dir
        if (mRootDir != null) {
            String appDataPath = DualSpaceApplication.getAppContext().getFilesDir().getAbsolutePath();
            if (realPath.startsWith(appDataPath)) {
                return mRootDir.getAbsolutePath() + realPath.substring(appDataPath.length());
            }
        }

        return null;
    }

    /**
     * Translate a virtual path back into the real (host) path.
     *
     * @param virtualPath absolute virtual path
     * @return real path, or the original path if no mapping exists
     */
    @NonNull
    public String getRealPath(@NonNull String virtualPath) {
        // First check explicit path mappings (virtual → real)
        for (Map.Entry<String, String> entry : mPathMappings.entrySet()) {
            String virtPrefix = entry.getKey();
            String realPrefix = entry.getValue();

            if (virtualPath.startsWith(virtPrefix)) {
                return realPrefix + virtualPath.substring(virtPrefix.length());
            }
        }

        // Fallback: if virtualPath starts with our virtual root, remap to real data dir
        if (mRootDir != null && virtualPath.startsWith(mRootDir.getAbsolutePath())) {
            String appDataPath = DualSpaceApplication.getAppContext().getFilesDir().getAbsolutePath();
            return appDataPath + virtualPath.substring(mRootDir.getAbsolutePath().length());
        }

        // No mapping found — return as-is
        return virtualPath;
    }

    /**
     * Register a path-mapping pair.
     *
     * @param virtualPrefix virtual path prefix (e.g. {@code /virtual/user_0})
     * @param realPrefix    real path prefix (e.g. {@code /data/data/com.dualspace.obs})
     */
    public void addPathMapping(@NonNull String virtualPrefix, @NonNull String realPrefix) {
        // Ensure trailing separators are consistent
        virtualPrefix = ensureTrailingSeparator(virtualPrefix);
        realPrefix = ensureTrailingSeparator(realPrefix);
        mPathMappings.put(virtualPrefix, realPrefix);
        Log.d(TAG, "Path mapping added: " + virtualPrefix + " → " + realPrefix);
    }

    /**
     * Remove a path mapping.
     */
    public void removePathMapping(@NonNull String virtualPrefix) {
        mPathMappings.remove(ensureTrailingSeparator(virtualPrefix));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Environment variables
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Set a virtual environment variable.
     */
    public void setEnvVar(@NonNull String name, @NonNull String value) {
        mEnvVars.put(name, value);
    }

    /**
     * Get a virtual environment variable.
     *
     * @return value or {@code null} if not set
     */
    @Nullable
    public String getEnvVar(@NonNull String name) {
        return mEnvVars.get(name);
    }

    /**
     * Returns an unmodifiable snapshot of all environment variables.
     */
    @NonNull
    public Map<String, String> getAllEnvVars() {
        return Collections.unmodifiableMap(new HashMap<>(mEnvVars));
    }

    /**
     * Clear all custom environment variables.
     */
    public void clearEnvVars() {
        mEnvVars.clear();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Virtual content providers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Register a virtual content provider.
     *
     * @param authority       provider authority
     * @param providerClass   fully-qualified class name
     */
    public void registerVirtualProvider(@NonNull String authority,
                                        @NonNull String providerClass) {
        mVirtualProviders.put(authority, providerClass);
        Log.d(TAG, "Virtual provider registered: " + authority + " → " + providerClass);
    }

    /**
     * Unregister a virtual content provider.
     */
    public void unregisterVirtualProvider(@NonNull String authority) {
        mVirtualProviders.remove(authority);
    }

    /**
     * Check whether a provider authority is a virtual one.
     */
    public boolean isVirtualProvider(@NonNull String authority) {
        return mVirtualProviders.containsKey(authority);
    }

    @Nullable
    public String getVirtualProviderClass(@NonNull String authority) {
        return mVirtualProviders.get(authority);
    }

    @NonNull
    public Map<String, String> getAllVirtualProviders() {
        return Collections.unmodifiableMap(new HashMap<>(mVirtualProviders));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Storage usage
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Calculate total storage used by the virtual environment in bytes.
     */
    public long getStorageUsage() {
        if (mRootDir == null || !mRootDir.exists()) {
            return 0L;
        }
        return calculateDirectorySize(mRootDir);
    }

    /**
     * Get storage usage for a specific app package within the virtual env.
     *
     * @param packageName the package name
     * @return size in bytes
     */
    public long getStorageUsage(@NonNull String packageName) {
        if (mRootDir == null) return 0L;
        File appDir = new File(mRootDir, "apps/" + packageName);
        if (!appDir.exists()) return 0L;
        return calculateDirectorySize(appDir);
    }

    /**
     * Returns the virtual root directory.
     */
    @Nullable
    public File getRootDir() {
        return mRootDir;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Teardown
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Destroy the virtual environment and delete all files.
     */
    public synchronized void destroy() {
        if (!mIsSetup) {
            return;
        }

        try {
            // Delete the virtual root and all contents
            if (mRootDir != null && mRootDir.exists()) {
                deleteRecursive(mRootDir);
                Log.i(TAG, "Virtual environment destroyed: " + mRootDir);
            }

            mUserRoots.clear();
            mPathMappings.clear();
            mEnvVars.clear();
            mVirtualProviders.clear();
            mRootDir = null;
            mIsSetup = false;
        } catch (Exception e) {
            Log.e(TAG, "Error destroying virtual environment", e);
        }
    }

    /**
     * Check whether the environment has been set up.
     */
    public boolean isSetup() {
        return mIsSetup;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void initDefaultEnvVars(@NonNull File root) {
        mEnvVars.clear();

        mEnvVars.put("VIRTUAL_ROOT", root.getAbsolutePath());
        mEnvVars.put("VIRTUAL_DATA", new File(root, "data").getAbsolutePath());
        mEnvVars.put("VIRTUAL_CACHE", new File(root, "cache").getAbsolutePath());
        mEnvVars.put("VIRTUAL_FILES", new File(root, "files").getAbsolutePath());
        mEnvVars.put("VIRTUAL_DATABASES", new File(root, "databases").getAbsolutePath());
        mEnvVars.put("VIRTUAL_SHARED_PREFS", new File(root, "shared_prefs").getAbsolutePath());

        // External storage paths for the virtual env
        File externalFiles = new File(root, "external_files");
        mEnvVars.put("EXTERNAL_STORAGE", externalFiles.getAbsolutePath());
        mEnvVars.put("SECONDARY_STORAGE", externalFiles.getAbsolutePath());

        // Locale
        mEnvVars.put("LANG", Locale.getDefault().getLanguage());
        mEnvVars.put("LOCALE", Locale.getDefault().toString());

        Log.d(TAG, "Default env vars initialized (" + mEnvVars.size() + " variables)");
    }

    private void registerBuiltinProviders() {
        // Built-in virtual providers used by the dual-space framework
        registerVirtualProvider(
                "com.dualspace.obs.virtual.settings",
                "com.dualspace.obs.provider.VirtualSettingsProvider");
        registerVirtualProvider(
                "com.dualspace.obs.virtual.contacts",
                "com.dualspace.obs.provider.VirtualContactsProvider");
        registerVirtualProvider(
                "com.dualspace.obs.virtual.calllog",
                "com.dualspace.obs.provider.VirtualCallLogProvider");
        registerVirtualProvider(
                "com.dualspace.obs.virtual.sms",
                "com.dualspace.obs.provider.VirtualSmsProvider");
    }

    /**
     * Recursively calculate the total size of a directory.
     */
    private long calculateDirectorySize(@NonNull File dir) {
        if (!dir.exists()) return 0L;
        if (dir.isFile()) return dir.length();

        long size = 0L;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                size += calculateDirectorySize(f);
            }
        }
        return size;
    }

    /**
     * Recursively delete a file or directory.
     */
    private boolean deleteRecursive(@NonNull File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) {
                        Log.w(TAG, "Failed to delete: " + child);
                    }
                }
            }
        }
        return file.delete();
    }

    private static String ensureTrailingSeparator(@NonNull String path) {
        if (!path.endsWith(File.separator) && !path.endsWith("/")) {
            return path + "/";
        }
        return path;
    }
}

/*
 * StorageManager.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Manages per-app isolated storage inside the virtual environment.  Provides
 * file operations with automatic path translation, configurable storage quotas,
 * and database file management.
 */
package com.dualspace.obs.engine;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StorageManager {

    private static final String TAG = "StorageManager";

    /** Default per-app storage quota: 512 MB. */
    private static final long DEFAULT_QUOTA_BYTES = 512L * 1024L * 1024L;

    /** Buffer size for copy operations. */
    private static final int COPY_BUFFER_SIZE = 8192;

    // ──────────────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────────────

    /** packageName → quota in bytes */
    private final ConcurrentHashMap<String, Long> mQuotas = new ConcurrentHashMap<>();

    private VirtualEnvironment mVirtualEnv;

    // ──────────────────────────────────────────────────────────────────────────
    // Constructor
    // ──────────────────────────────────────────────────────────────────────────

    public StorageManager() {
        // VirtualEnvironment will be set when the engine is initialized
    }

    /**
     * Associate this storage manager with a virtual environment.
     * Called automatically by VirtualEngine during initialization.
     */
    public void setVirtualEnvironment(@NonNull VirtualEnvironment env) {
        mVirtualEnv = env;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // App storage lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Create isolated storage directories for a virtual app.
     *
     * @param packageName package name
     * @return the root directory for this app's virtual storage
     * @throws IOException if directories cannot be created
     */
    @NonNull
    public File createAppStorage(@NonNull String packageName) throws IOException {
        File appRoot = getAppRoot(packageName);

        if (appRoot.exists()) {
            Log.d(TAG, "Storage already exists for " + packageName + " at " + appRoot);
            return appRoot;
        }

        // Create standard sub-directories
        String[] subDirs = {
                "data",
                "cache",
                "files",
                "databases",
                "shared_prefs",
                "code_cache"
        };

        for (String sub : subDirs) {
            File dir = new File(appRoot, sub);
            if (!dir.mkdirs() && !dir.exists()) {
                throw new IOException("Failed to create directory: " + dir.getAbsolutePath());
            }
        }

        // Set default quota
        mQuotas.putIfAbsent(packageName, DEFAULT_QUOTA_BYTES);

        Log.i(TAG, "Storage created for " + packageName + " at " + appRoot);
        return appRoot;
    }

    /**
     * Delete all storage for a virtual app.
     *
     * @param packageName package name
     * @return {@code true} if storage was deleted
     */
    public boolean deleteAppStorage(@NonNull String packageName) {
        File appRoot = getAppRoot(packageName);
        if (!appRoot.exists()) {
            Log.d(TAG, "No storage to delete for " + packageName);
            return true;
        }

        boolean success = deleteRecursive(appRoot);
        mQuotas.remove(packageName);

        if (success) {
            Log.i(TAG, "Storage deleted for " + packageName);
        } else {
            Log.e(TAG, "Failed to fully delete storage for " + packageName);
        }
        return success;
    }

    /**
     * Check whether storage exists for a package.
     */
    public boolean hasAppStorage(@NonNull String packageName) {
        return getAppRoot(packageName).exists();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // File operations
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Copy a file within the virtual environment.
     *
     * @param sourcePath source virtual path
     * @param destPath   destination virtual path
     * @return {@code true} if the copy succeeded
     */
    public boolean copyFile(@NonNull String sourcePath, @NonNull String destPath) {
        File source = new File(sourcePath);
        File dest = new File(destPath);

        if (!source.exists()) {
            Log.e(TAG, "copyFile: source not found: " + sourcePath);
            return false;
        }

        // Check quota if destination is under an app's storage
        String destPkg = findPackageForPath(destPath);
        if (destPkg != null) {
            long currentUsage = getStorageUsage(destPkg);
            long fileSize = source.length();
            long quota = mQuotas.getOrDefault(destPkg, DEFAULT_QUOTA_BYTES);
            if (currentUsage + fileSize > quota) {
                Log.w(TAG, "copyFile: quota exceeded for " + destPkg
                        + " (current=" + currentUsage
                        + ", file=" + fileSize
                        + ", quota=" + quota + ")");
                return false;
            }
        }

        // Ensure parent directory exists
        File parentDir = dest.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                Log.e(TAG, "copyFile: cannot create parent dir: " + parentDir);
                return false;
            }
        }

        // Use NIO channel copy for performance, fall back to stream copy
        boolean success = false;
        FileInputStream fis = null;
        FileOutputStream fos = null;
        FileChannel inChannel = null;
        FileChannel outChannel = null;

        try {
            fis = new FileInputStream(source);
            fos = new FileOutputStream(dest);
            inChannel = fis.getChannel();
            outChannel = fos.getChannel();
            outChannel.transferFrom(inChannel, 0, inChannel.size());
            success = true;
        } catch (IOException e) {
            Log.e(TAG, "NIO copy failed, falling back to stream copy", e);
            success = streamCopy(source, dest);
        } finally {
            closeQuietly(outChannel);
            closeQuietly(inChannel);
            closeQuietly(fos);
            closeQuietly(fis);
        }

        return success;
    }

    /**
     * Move / rename a file.
     */
    public boolean moveFile(@NonNull String sourcePath, @NonNull String destPath) {
        File source = new File(sourcePath);
        File dest = new File(destPath);

        if (!source.exists()) {
            Log.e(TAG, "moveFile: source not found: " + sourcePath);
            return false;
        }

        File parentDir = dest.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                return false;
            }
        }

        boolean success = source.renameTo(dest);
        if (!success) {
            // Cross-device link: copy then delete
            success = copyFile(sourcePath, destPath);
            if (success) {
                source.delete();
            }
        }
        return success;
    }

    /**
     * Delete a single file.
     */
    public boolean deleteFile(@NonNull String filePath) {
        File file = new File(filePath);
        if (!file.exists()) return true;
        return file.delete();
    }

    /**
     * Create an empty file (and any missing parent directories).
     */
    public boolean createFile(@NonNull String filePath) throws IOException {
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                throw new IOException("Cannot create parent directory: " + parent);
            }
        }
        return file.createNewFile();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Database management
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Open (or create) a SQLite database for a virtual app.
     *
     * @param packageName package name
     * @param dbName      database file name (e.g. "app.db")
     * @return opened database, or {@code null} on failure
     */
    @Nullable
    public SQLiteDatabase openDatabase(@NonNull String packageName,
                                       @NonNull String dbName) {
        File dbDir = new File(getAppRoot(packageName), "databases");
        if (!dbDir.exists() && !dbDir.mkdirs()) {
            Log.e(TAG, "Cannot create databases dir for " + packageName);
            return null;
        }

        File dbFile = new File(dbDir, dbName);
        try {
            return SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open database " + dbName + " for " + packageName, e);
            return null;
        }
    }

    /**
     * Delete a virtual app's database.
     */
    public boolean deleteDatabase(@NonNull String packageName, @NonNull String dbName) {
        File dbFile = new File(new File(getAppRoot(packageName), "databases"), dbName);
        if (!dbFile.exists()) return true;

        // Close any open connections first
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
        } catch (Exception ignored) {
            // DB may not be open
        }
        if (db != null) {
            db.close();
        }

        boolean deleted = dbFile.delete();
        // Also delete WAL and journal files
        new File(dbFile.getPath() + "-wal").delete();
        new File(dbFile.getPath() + "-journal").delete();
        new File(dbFile.getPath() + "-shm").delete();

        return deleted;
    }

    /**
     * Get total size of all databases for a package.
     */
    public long getDatabaseUsage(@NonNull String packageName) {
        File dbDir = new File(getAppRoot(packageName), "databases");
        if (!dbDir.exists()) return 0L;
        return calculateDirSize(dbDir);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Quota management
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Set the storage quota for a package.
     *
     * @param packageName package name
     * @param quotaBytes  maximum bytes allowed
     */
    public void setQuota(@NonNull String packageName, long quotaBytes) {
        if (quotaBytes < 0) {
            throw new IllegalArgumentException("Quota must be non-negative");
        }
        mQuotas.put(packageName, quotaBytes);
        Log.d(TAG, "Quota set for " + packageName + ": " + formatBytes(quotaBytes));
    }

    /**
     * Get the storage quota for a package.
     *
     * @return quota in bytes, or default quota if not explicitly set
     */
    public long getQuota(@NonNull String packageName) {
        return mQuotas.getOrDefault(packageName, DEFAULT_QUOTA_BYTES);
    }

    /**
     * Check whether a package is within its storage quota.
     */
    public boolean isWithinQuota(@NonNull String packageName) {
        return getStorageUsage(packageName) <= getQuota(packageName);
    }

    /**
     * Get remaining quota for a package.
     */
    public long getRemainingQuota(@NonNull String packageName) {
        long used = getStorageUsage(packageName);
        long quota = getQuota(packageName);
        return Math.max(0L, quota - used);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Storage usage queries
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Get total storage used by a package in bytes.
     *
     * @param packageName package name
     * @return size in bytes
     */
    public long getStorageUsage(@NonNull String packageName) {
        File appRoot = getAppRoot(packageName);
        if (!appRoot.exists()) return 0L;
        return calculateDirSize(appRoot);
    }

    /**
     * Get storage usage breakdown by sub-directory.
     *
     * @param packageName package name
     * @return map of directory name → size in bytes
     */
    @NonNull
    public Map<String, Long> getStorageBreakdown(@NonNull String packageName) {
        File appRoot = getAppRoot(packageName);
        Map<String, Long> breakdown = new HashMap<>();

        if (!appRoot.exists()) return breakdown;

        File[] dirs = appRoot.listFiles();
        if (dirs != null) {
            for (File dir : dirs) {
                if (dir.isDirectory()) {
                    breakdown.put(dir.getName(), calculateDirSize(dir));
                }
            }
        }

        return breakdown;
    }

    /**
     * Get total storage used by all virtual apps combined.
     */
    public long getTotalStorageUsage() {
        if (mVirtualEnv == null) return 0L;
        // Use the virtual environment's root dir
        File rootDir = mVirtualEnv.getRootDir();
        if (rootDir == null || !rootDir.exists()) return 0L;
        return calculateDirSize(new File(rootDir, "apps"));
    }

    /**
     * Get available space for the virtual environment.
     *
     * @return available bytes on the underlying filesystem
     */
    public long getAvailableSpace() {
        Context ctx = DualSpaceApplication.getAppContext();
        File dataDir = ctx.getFilesDir();
        return dataDir.getUsableSpace();
    }

    /**
     * Get total space of the underlying filesystem.
     */
    public long getTotalSpace() {
        Context ctx = DualSpaceApplication.getAppContext();
        File dataDir = ctx.getFilesDir();
        return dataDir.getTotalSpace();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Resolve the root directory for a given package's virtual storage.
     */
    @NonNull
    private File getAppRoot(@NonNull String packageName) {
        Context ctx = DualSpaceApplication.getAppContext();
        File baseDir = ctx.getFilesDir();

        // apps/<packageName>/
        if (mVirtualEnv != null && mVirtualEnv.getRootDir() != null) {
            return new File(new File(mVirtualEnv.getRootDir(), "apps"), packageName);
        }
        return new File(new File(baseDir, "virtual_env/apps"), packageName);
    }

    /**
     * Try to determine which package a file path belongs to.
     */
    @Nullable
    private String findPackageForPath(@NonNull String path) {
        if (mVirtualEnv == null || mVirtualEnv.getRootDir() == null) return null;
        String appsPrefix = new File(mVirtualEnv.getRootDir(), "apps").getAbsolutePath() + "/";
        if (path.startsWith(appsPrefix)) {
            String relative = path.substring(appsPrefix.length());
            int slash = relative.indexOf('/');
            if (slash > 0) {
                return relative.substring(0, slash);
            }
            return relative; // the path is the package dir itself
        }
        return null;
    }

    /**
     * Stream-based file copy fallback.
     */
    private boolean streamCopy(@NonNull File source, @NonNull File dest) {
        InputStream in = null;
        OutputStream out = null;

        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(dest);
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Stream copy failed: " + source + " → " + dest, e);
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /**
     * Recursively calculate directory size.
     */
    private long calculateDirSize(@NonNull File dir) {
        if (!dir.exists()) return 0L;
        if (dir.isFile()) return dir.length();

        long size = 0L;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                size += calculateDirSize(f);
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
                    deleteRecursive(child);
                }
            }
        }
        return file.delete();
    }

    private static void closeQuietly(@Nullable AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Intentionally swallowed
            }
        }
    }

    @NonNull
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}

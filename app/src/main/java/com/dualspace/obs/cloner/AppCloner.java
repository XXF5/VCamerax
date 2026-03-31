package com.dualspace.obs.cloner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dualspace.obs.cloner.CloneDatabase.CloneDao;
import com.dualspace.obs.cloner.CloneDatabase.CloneEntity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main app cloning engine.
 *
 * <p>Orchestrates the full clone lifecycle:</p>
 * <ol>
 *   <li>Extract APK from the installed app</li>
 *   <li>Verify the APK signature</li>
 *   <li>Create an isolated storage directory</li>
 *   <li>Save the clone configuration</li>
 *   <li>Register the clone in the Room database</li>
 * </ol>
 *
 * <p>All heavy I/O is dispatched to a background thread pool.  Results are
 * delivered through the {@link CloneCallback} listener interface.</p>
 */
public class AppCloner {

    private static final String TAG = "AppCloner";

    /** Root directory under the app's files dir where clone data lives. */
    private static final String CLONES_ROOT = "clones";

    // ── Callback interface ─────────────────────────────────────────────────

    /**
     * Listener for asynchronous clone operation results.
     */
    public interface CloneCallback {
        /** Called when a clone is successfully created. */
        void onCloneSuccess(@NonNull CloneInfo cloneInfo);

        /** Called when a clone operation fails. */
        void onCloneFailed(@NonNull String packageName, @NonNull String error);

        /** Called when a clone is successfully removed. */
        void onRemoveSuccess(@NonNull String cloneId);

        /** Called when clone removal fails. */
        void onRemoveFailed(@NonNull String cloneId, @NonNull String error);
    }

    // ── CloneInfo ──────────────────────────────────────────────────────────

    /**
     * Read-only snapshot of a clone's metadata.
     */
    public static class CloneInfo {
        @NonNull public final String sourcePackage;
        @NonNull public final String cloneName;
        @NonNull public final String cloneId;
        @Nullable public final String iconPath;
        public final long installedAt;
        public final long size;

        public CloneInfo(@NonNull String sourcePackage,
                         @NonNull String cloneName,
                         @NonNull String cloneId,
                         @Nullable String iconPath,
                         long installedAt,
                         long size) {
            this.sourcePackage = sourcePackage;
            this.cloneName = cloneName;
            this.cloneId = cloneId;
            this.iconPath = iconPath;
            this.installedAt = installedAt;
            this.size = size;
        }

        /**
         * Build a {@link CloneInfo} from a persisted {@link CloneEntity}.
         */
        @NonNull
        public static CloneInfo fromEntity(@NonNull CloneEntity entity) {
            return new CloneInfo(
                    entity.getSourcePackage(),
                    entity.getCloneName(),
                    entity.getCloneId(),
                    entity.getIconPath(),
                    entity.getCreatedAt(),
                    entity.getSizeBytes()
            );
        }
    }

    // ── Fields ─────────────────────────────────────────────────────────────

    private final Context context;
    private final ApkExtractor extractor;
    private final ExecutorService executor;
    private final CloneDao dao;
    private CloneCallback callback;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Create a new AppCloner.
     *
     * @param context application context
     */
    public AppCloner(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.extractor = new ApkExtractor(this.context);
        this.executor = Executors.newSingleThreadExecutor();
        this.dao = CloneDatabase.getDao(this.context);
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Set a callback for asynchronous operation results.
     *
     * @param callback the listener (may be {@code null} to clear)
     */
    public void setCallback(@Nullable CloneCallback callback) {
        this.callback = callback;
    }

    /**
     * Clone an installed app into a virtual space.
     *
     * <p>Steps performed on a background thread:</p>
     * <ol>
     *   <li>Extract APK to internal storage</li>
     *   <li>Verify APK signature</li>
     *   <li>Create isolated storage directory</li>
     *   <li>Save icon (or copy source icon)</li>
     *   <li>Persist {@link CloneEntity} in Room</li>
     * </ol>
     *
     * @param packageName the installed app to clone
     * @param config      clone configuration
     */
    public void cloneApp(@NonNull String packageName, @NonNull CloneConfig config) {
        executor.execute(() -> {
            // ── 1. Gather APK info ──────────────────────────────────────
            ApkExtractor.ApkInfo apkInfo = extractor.getApkInfo(packageName);
            if (apkInfo == null) {
                notifyCloneFailed(packageName, "Package not found: " + packageName);
                return;
            }

            // ── 2. Verify signature ─────────────────────────────────────
            ApkExtractor.SignatureResult sigResult = extractor.verifySignature(packageName);
            if (!sigResult.valid) {
                notifyCloneFailed(packageName, "Signature verification failed: " + sigResult.message);
                return;
            }
            Log.d(TAG, "Signature OK for " + packageName + " → " + sigResult.sha256);

            // ── 3. Generate clone ID ────────────────────────────────────
            String cloneId = UUID.randomUUID().toString();
            String cloneName = config.getCloneName().isEmpty()
                    ? apkInfo.label + " (Clone)"
                    : config.getCloneName();

            // ── 4. Create storage ──────────────────────────────────────
            File cloneDir = new File(context.getFilesDir(), CLONES_ROOT + "/" + cloneId);
            if (!cloneDir.mkdirs() && !cloneDir.isDirectory()) {
                notifyCloneFailed(packageName, "Cannot create clone directory: " + cloneDir);
                return;
            }
            File apkDir = new File(cloneDir, "apk");
            if (!apkDir.mkdirs()) {
                notifyCloneFailed(packageName, "Cannot create APK directory");
                return;
            }
            File dataDir = new File(cloneDir, "data");
            if (!dataDir.mkdirs()) {
                notifyCloneFailed(packageName, "Cannot create data directory");
                return;
            }

            // ── 5. Extract APK ──────────────────────────────────────────
            File extractedApk = extractor.extractApk(packageName, apkDir);
            if (extractedApk == null) {
                notifyCloneFailed(packageName, "APK extraction failed");
                return;
            }

            long sizeBytes = extractedApk.length();

            // ── 6. Save icon ────────────────────────────────────────────
            String iconPath = config.getCustomIconPath();
            if (iconPath == null) {
                iconPath = saveIcon(packageName, cloneDir);
            }

            // ── 7. Persist config ───────────────────────────────────────
            config.setCloneName(cloneName);

            CloneEntity entity = new CloneEntity(
                    packageName,
                    cloneName,
                    cloneId,
                    iconPath,
                    config,
                    System.currentTimeMillis(),
                    sizeBytes
            );

            try {
                long rowId = dao.insert(entity);
                if (rowId == -1L) {
                    notifyCloneFailed(packageName, "Database insert failed");
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "DB insert error", e);
                notifyCloneFailed(packageName, "Database error: " + e.getMessage());
                return;
            }

            // ── 8. Success ──────────────────────────────────────────────
            CloneInfo info = new CloneInfo(
                    packageName, cloneName, cloneId, iconPath,
                    System.currentTimeMillis(), sizeBytes
            );
            Log.d(TAG, "Clone created: " + cloneName + " (" + cloneId + ")");
            notifyCloneSuccess(info);
        });
    }

    /**
     * Remove a clone and clean up all associated files.
     *
     * @param cloneId the clone to remove
     */
    public void removeClone(@NonNull String cloneId) {
        executor.execute(() -> {
            // ── 1. Delete from database ─────────────────────────────────
            CloneEntity entity = dao.getByCloneId(cloneId);
            if (entity == null) {
                notifyRemoveFailed(cloneId, "Clone not found in database");
                return;
            }

            int rows = dao.delete(entity);
            if (rows == 0) {
                notifyRemoveFailed(cloneId, "Database delete returned 0 rows");
                return;
            }

            // ── 2. Remove icon file ─────────────────────────────────────
            if (entity.getIconPath() != null) {
                new File(entity.getIconPath()).delete();
            }

            // ── 3. Remove clone directory ───────────────────────────────
            File cloneDir = new File(context.getFilesDir(), CLONES_ROOT + "/" + cloneId);
            if (cloneDir.exists()) {
                deleteRecursive(cloneDir);
            }

            Log.d(TAG, "Clone removed: " + cloneId);
            notifyRemoveSuccess(cloneId);
        });
    }

    /**
     * Get all registered clones (synchronous, may block on DB I/O).
     *
     * @return unmodifiable list of {@link CloneInfo}
     */
    @NonNull
    public List<CloneInfo> getAllClones() {
        List<CloneEntity> entities = dao.getAllSync();
        List<CloneInfo> result = new ArrayList<>(entities.size());
        for (CloneEntity entity : entities) {
            result.add(CloneInfo.fromEntity(entity));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Verify the integrity of a specific clone.
     *
     * <p>Checks that the database entry exists, the APK file is present on
     * disk, and the file size matches the stored record.</p>
     *
     * @param cloneId the clone to verify
     * @return {@code true} if the clone passes all integrity checks
     */
    public boolean verifyClone(@NonNull String cloneId) {
        CloneEntity entity = dao.getByCloneId(cloneId);
        if (entity == null) {
            Log.w(TAG, "verifyClone: clone not found in DB: " + cloneId);
            return false;
        }

        // Check APK file exists
        File apkDir = new File(context.getFilesDir(), CLONES_ROOT + "/" + cloneId + "/apk");
        File[] apkFiles = apkDir.listFiles((dir, name) -> name.endsWith(".apk"));
        if (apkFiles == null || apkFiles.length == 0) {
            Log.w(TAG, "verifyClone: APK file missing for " + cloneId);
            return false;
        }

        // Check size
        File apkFile = apkFiles[0];
        if (entity.getSizeBytes() > 0 && apkFile.length() != entity.getSizeBytes()) {
            Log.w(TAG, "verifyClone: size mismatch for " + cloneId
                    + " (expected=" + entity.getSizeBytes()
                    + ", actual=" + apkFile.length() + ")");
            return false;
        }

        // Check icon if recorded
        if (entity.getIconPath() != null) {
            File iconFile = new File(entity.getIconPath());
            if (!iconFile.exists()) {
                Log.w(TAG, "verifyClone: icon missing for " + cloneId);
                return false;
            }
        }

        // Optional: re-verify source signature
        ApkExtractor.SignatureResult sig = extractor.verifySignature(entity.getSourcePackage());
        if (!sig.valid) {
            Log.w(TAG, "verifyClone: source signature invalid for " + cloneId);
            return false;
        }

        Log.d(TAG, "verifyClone: OK for " + cloneId);
        return true;
    }

    /**
     * Look up a clone by its ID.
     *
     * @param cloneId the unique clone identifier
     * @return a {@link CloneInfo} snapshot, or {@code null} if not found
     */
    @Nullable
    public CloneInfo getCloneInfo(@NonNull String cloneId) {
        CloneEntity entity = dao.getByCloneId(cloneId);
        return entity != null ? CloneInfo.fromEntity(entity) : null;
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    /**
     * Save the source app's icon as a PNG in the clone directory.
     *
     * @return the absolute path to the saved icon, or {@code null} on failure
     */
    @Nullable
    private String saveIcon(@NonNull String packageName, @NonNull File cloneDir) {
        try {
            Drawable drawable = extractor.getAppIcon(packageName);
            if (drawable == null) {
                return null;
            }

            Bitmap bitmap = android.graphics.drawable.DrawableToBitmapHelper.getBitmap(drawable);
            if (bitmap == null) {
                return null;
            }

            File iconFile = new File(cloneDir, "icon.png");
            try (FileOutputStream fos = new FileOutputStream(iconFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                return iconFile.getAbsolutePath();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save icon for " + packageName, e);
            return null;
        }
    }

    /**
     * Recursively delete a directory and all its contents.
     */
    private void deleteRecursive(@NonNull File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private void notifyCloneSuccess(@NonNull CloneInfo info) {
        CloneCallback cb = this.callback;
        if (cb != null) {
            // Post to main thread
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> cb.onCloneSuccess(info));
        }
    }

    private void notifyCloneFailed(@NonNull String pkg, @NonNull String error) {
        CloneCallback cb = this.callback;
        if (cb != null) {
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> cb.onCloneFailed(pkg, error));
        }
    }

    private void notifyRemoveSuccess(@NonNull String cloneId) {
        CloneCallback cb = this.callback;
        if (cb != null) {
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> cb.onRemoveSuccess(cloneId));
        }
    }

    private void notifyRemoveFailed(@NonNull String cloneId, @NonNull String error) {
        CloneCallback cb = this.callback;
        if (cb != null) {
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> cb.onRemoveFailed(cloneId, error));
        }
    }

    // ── Drawable-to-Bitmap helper ──────────────────────────────────────────

    /**
     * Simple utility to convert a Drawable to a Bitmap.
     * (Kept as a static inner class to avoid external dependencies.)
     */
    private static class DrawableToBitmapHelper {
        @Nullable
        static Bitmap getBitmap(@NonNull Drawable drawable) {
            if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                return ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
            }
            int w = drawable.getIntrinsicWidth();
            if (w <= 0) w = 96;
            int h = drawable.getIntrinsicHeight();
            if (h <= 0) h = 96;

            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return bitmap;
        }
    }
}

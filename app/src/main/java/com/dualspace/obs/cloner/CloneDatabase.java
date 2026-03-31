package com.dualspace.obs.cloner;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverter;
import androidx.room.TypeConverters;

import com.dualspace.obs.cloner.CloneDatabase.CloneDao;
import com.dualspace.obs.cloner.CloneDatabase.CloneEntity;

import org.json.JSONException;

/**
 * Room database for persistent clone management.
 *
 * <p>Entities, DAOs, and TypeConverters are defined as inner classes to keep
 * the clone-related persistence layer self-contained.</p>
 */
@Database(
        entities = {CloneEntity.class},
        version = 1,
        exportSchema = false
)
@TypeConverters(CloneDatabase.Converters.class)
public abstract class CloneDatabase extends RoomDatabase {

    private static final String DB_NAME = "dualspace_clones.db";
    private static volatile CloneDatabase INSTANCE;

    // ── Singleton access ───────────────────────────────────────────────────

    /**
     * Return the singleton database instance, creating it if necessary.
     *
     * @param context application context (used only for the first call)
     * @return the shared {@link CloneDatabase}
     */
    @NonNull
    public static CloneDatabase getInstance(@NonNull Context context) {
        if (INSTANCE == null) {
            synchronized (CloneDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            CloneDatabase.class,
                            DB_NAME
                    )
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Convenience shorthand for {@code getInstance(context).getCloneDao()}.
     */
    @NonNull
    public static CloneDao getDao(@NonNull Context context) {
        return getInstance(context).getCloneDao();
    }

    /** Expose the DAO for data access. */
    @NonNull
    public abstract CloneDao getCloneDao();

    // ── TypeConverters ─────────────────────────────────────────────────────

    /**
     * Room type converters for persisting {@link CloneConfig} as a JSON string
     * and handling other non-primitive column types.
     */
    public static class Converters {

        /**
         * Serialize a {@link CloneConfig} to a JSON string for storage.
         */
        @TypeConverter
        @NonNull
        public String fromConfig(@NonNull CloneConfig config) {
            try {
                return config.toJson();
            } catch (JSONException e) {
                return "{}";
            }
        }

        /**
         * Deserialize a JSON string back to a {@link CloneConfig}.
         */
        @TypeConverter
        @NonNull
        public CloneConfig toConfig(@NonNull String json) {
            CloneConfig config = CloneConfig.fromJson(json);
            return config != null ? config : new CloneConfig();
        }
    }

    // ── Entity ─────────────────────────────────────────────────────────────

    /**
     * Represents one cloned app row in the database.
     */
    @androidx.room.Entity(
            tableName = "clones",
            indices = {
                    @androidx.room.Index(value = {"cloneId"}, unique = true),
                    @androidx.room.Index(value = {"sourcePackage"})
            }
    )
    public static class CloneEntity {

        @androidx.room.PrimaryKey(autoGenerate = true)
        private long id;

        /** The original app's package name (e.g. "com.whatsapp"). */
        @NonNull
        private String sourcePackage = "";

        /** User-visible name for this clone. */
        @NonNull
        private String cloneName = "";

        /** Unique identifier assigned at clone time. */
        @NonNull
        private String cloneId = "";

        /** Path to the launcher icon for this clone. */
        @Nullable
        private String iconPath;

        /** {@link CloneConfig} serialized as JSON. */
        @NonNull
        private String configJson = "{}";

        /** Epoch millis when the clone was created. */
        private long createdAt;

        /** Size of the cloned APK in bytes. */
        private long sizeBytes;

        // ── Default constructor for Room ────────────────────────────────

        public CloneEntity() {
        }

        // ── Convenience constructor ─────────────────────────────────────

        public CloneEntity(@NonNull String sourcePackage,
                           @NonNull String cloneName,
                           @NonNull String cloneId,
                           @Nullable String iconPath,
                           @NonNull CloneConfig config,
                           long createdAt,
                           long sizeBytes) {
            this.sourcePackage = sourcePackage;
            this.cloneName = cloneName;
            this.cloneId = cloneId;
            this.iconPath = iconPath;
            this.createdAt = createdAt;
            this.sizeBytes = sizeBytes;
            try {
                this.configJson = config.toJson();
            } catch (JSONException e) {
                this.configJson = "{}";
            }
        }

        // ── Getters & Setters ──────────────────────────────────────────

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        @NonNull
        public String getSourcePackage() {
            return sourcePackage;
        }

        public void setSourcePackage(@NonNull String sourcePackage) {
            this.sourcePackage = sourcePackage;
        }

        @NonNull
        public String getCloneName() {
            return cloneName;
        }

        public void setCloneName(@NonNull String cloneName) {
            this.cloneName = cloneName;
        }

        @NonNull
        public String getCloneId() {
            return cloneId;
        }

        public void setCloneId(@NonNull String cloneId) {
            this.cloneId = cloneId;
        }

        @Nullable
        public String getIconPath() {
            return iconPath;
        }

        public void setIconPath(@Nullable String iconPath) {
            this.iconPath = iconPath;
        }

        @NonNull
        public String getConfigJson() {
            return configJson;
        }

        public void setConfigJson(@NonNull String configJson) {
            this.configJson = configJson;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        /**
         * Convenience method to deserialize the stored config JSON.
         */
        @NonNull
        public CloneConfig getConfig() {
            CloneConfig config = CloneConfig.fromJson(configJson);
            return config != null ? config : new CloneConfig();
        }
    }

    // ── DAO ────────────────────────────────────────────────────────────────

    /**
     * Data access object for clone entities.
     */
    @androidx.room.Dao
    public interface CloneDao {

        /**
         * Insert a new clone record.
         *
         * @param entity the clone to persist
         * @return the row ID of the inserted record
         */
        long insert(@NonNull CloneEntity entity);

        /**
         * Delete a clone record by its database row ID.
         *
         * @param entity the entity to delete (matched by primary key)
         * @return the number of rows removed
         */
        int delete(@NonNull CloneEntity entity);

        /**
         * Delete a clone by its unique cloneId.
         *
         * @param cloneId the clone identifier
         * @return rows removed (0 or 1)
         */
        int deleteByCloneId(@NonNull String cloneId);

        /**
         * Update an existing clone record.
         *
         * @param entity the entity with updated fields
         * @return rows updated
         */
        int update(@NonNull CloneEntity entity);

        /**
         * Retrieve all clones, ordered by creation time descending.
         *
         * @return a live list of all clone entities
         */
        @androidx.room.Query("SELECT * FROM clones ORDER BY createdAt DESC")
        @NonNull
        androidx.lifecycle.LiveData<java.util.List<CloneEntity>> getAll();

        /**
         * One-shot retrieval of all clones.
         */
        @androidx.room.Query("SELECT * FROM clones ORDER BY createdAt DESC")
        @NonNull
        java.util.List<CloneEntity> getAllSync();

        /**
         * Look up a clone by its unique cloneId.
         *
         * @param cloneId the clone identifier
         * @return the matching entity, or {@code null}
         */
        @androidx.room.Query("SELECT * FROM clones WHERE cloneId = :cloneId LIMIT 1")
        @Nullable
        CloneEntity getByCloneId(@NonNull String cloneId);

        /**
         * Look up a clone by its unique cloneId (LiveData variant).
         */
        @androidx.room.Query("SELECT * FROM clones WHERE cloneId = :cloneId LIMIT 1")
        @Nullable
        androidx.lifecycle.LiveData<CloneEntity> getLiveByCloneId(@NonNull String cloneId);

        /**
         * Find all clones originating from the same source package.
         *
         * @param sourcePackage the original app package name
         * @return list of clones sharing the same source
         */
        @androidx.room.Query("SELECT * FROM clones WHERE sourcePackage = :sourcePackage ORDER BY createdAt DESC")
        @NonNull
        java.util.List<CloneEntity> getBySourcePackage(@NonNull String sourcePackage);

        /**
         * Count the total number of clones.
         */
        @androidx.room.Query("SELECT COUNT(*) FROM clones")
        int count();

        /**
         * Check whether a cloneId already exists.
         */
        @androidx.room.Query("SELECT EXISTS(SELECT 1 FROM clones WHERE cloneId = :cloneId LIMIT 1)")
        boolean exists(@NonNull String cloneId);
    }
}

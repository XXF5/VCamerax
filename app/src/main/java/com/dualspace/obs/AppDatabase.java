/*
 * AppDatabase.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Room database definition for the DualSpace application.
 * Holds metadata about cloned apps, settings, and usage statistics.
 */
package com.dualspace.obs;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(
        entities = {
                com.dualspace.obs.db.entity.ClonedAppEntity.class,
                com.dualspace.obs.db.entity.UsageStatEntity.class,
                com.dualspace.obs.db.entity.AppSettingsEntity.class
        },
        version = 1,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    /** Data access object for cloned apps. */
    public abstract com.dualspace.obs.db.dao.ClonedAppDao clonedAppDao();

    /** Data access object for usage statistics. */
    public abstract com.dualspace.obs.db.dao.UsageStatDao usageStatDao();

    /** Data access object for app settings. */
    public abstract com.dualspace.obs.db.dao.AppSettingsDao appSettingsDao();
}

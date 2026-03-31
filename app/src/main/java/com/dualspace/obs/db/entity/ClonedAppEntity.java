/*
 * ClonedAppEntity.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Room entity representing a cloned application.
 */
package com.dualspace.obs.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "cloned_apps")
public class ClonedAppEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "package_name")
    public String packageName;

    @ColumnInfo(name = "label")
    public String label;

    @ColumnInfo(name = "icon_path")
    public String iconPath;

    @ColumnInfo(name = "is_running")
    public boolean isRunning;

    @ColumnInfo(name = "last_used")
    public long lastUsedTimestamp;

    @ColumnInfo(name = "virtual_pid")
    public int virtualPid;

    @ColumnInfo(name = "storage_bytes")
    public long storageBytes;

    @ColumnInfo(name = "installed_time")
    public long installedTime;
}

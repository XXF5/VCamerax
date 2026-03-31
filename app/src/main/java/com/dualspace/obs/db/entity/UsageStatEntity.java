/*
 * UsageStatEntity.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Room entity for tracking per-app usage statistics.
 */
package com.dualspace.obs.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "usage_stats")
public class UsageStatEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "package_name")
    public String packageName;

    @ColumnInfo(name = "session_start")
    public long sessionStartMs;

    @ColumnInfo(name = "session_end")
    public long sessionEndMs;

    @ColumnInfo(name = "duration_ms")
    public long durationMs;

    @ColumnInfo(name = "cpu_time_ms")
    public long cpuTimeMs;

    @ColumnInfo(name = "memory_bytes_peak")
    public long memoryBytesPeak;
}

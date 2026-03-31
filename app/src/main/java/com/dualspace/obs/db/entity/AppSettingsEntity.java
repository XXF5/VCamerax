/*
 * AppSettingsEntity.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Room entity for key-value application settings.
 */
package com.dualspace.obs.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_settings")
public class AppSettingsEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "key")
    public String key;

    @ColumnInfo(name = "value")
    @NonNull
    public String value;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}

/*
 * AppSettingsDao.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Data access object for application settings (key-value store).
 */
package com.dualspace.obs.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.dualspace.obs.db.entity.AppSettingsEntity;

@Dao
public interface AppSettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrUpdate(AppSettingsEntity entity);

    @Query("SELECT value FROM app_settings WHERE key = :key LIMIT 1")
    String getValue(String key);

    @Query("SELECT * FROM app_settings WHERE key = :key LIMIT 1")
    AppSettingsEntity getEntry(String key);

    @Query("DELETE FROM app_settings WHERE key = :key")
    void deleteByKey(String key);

    @Query("SELECT COUNT(*) FROM app_settings")
    int getCount();
}

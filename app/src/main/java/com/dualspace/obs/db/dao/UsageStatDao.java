/*
 * UsageStatDao.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Data access object for usage statistics.
 */
package com.dualspace.obs.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.dualspace.obs.db.entity.UsageStatEntity;

import java.util.List;

@Dao
public interface UsageStatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(UsageStatEntity entity);

    @Query("SELECT * FROM usage_stats WHERE package_name = :packageName ORDER BY session_start DESC")
    List<UsageStatEntity> getStatsForPackage(String packageName);

    @Query("SELECT SUM(duration_ms) FROM usage_stats WHERE package_name = :packageName")
    long getTotalUsageMs(String packageName);

    @Query("SELECT AVG(memory_bytes_peak) FROM usage_stats WHERE package_name = :packageName")
    long getAverageMemoryUsage(String packageName);

    @Query("DELETE FROM usage_stats WHERE package_name = :packageName")
    void deleteStatsForPackage(String packageName);

    @Query("SELECT COUNT(*) FROM usage_stats")
    int getCount();
}

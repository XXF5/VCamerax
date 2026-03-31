/*
 * ClonedAppDao.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Data access object for cloned app operations.
 */
package com.dualspace.obs.db.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.dualspace.obs.db.entity.ClonedAppEntity;

import java.util.List;

@Dao
public interface ClonedAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ClonedAppEntity entity);

    @Update
    void update(ClonedAppEntity entity);

    @Delete
    void delete(ClonedAppEntity entity);

    @Query("SELECT * FROM cloned_apps ORDER BY label ASC")
    List<ClonedAppEntity> getAllClonedApps();

    @Query("SELECT * FROM cloned_apps WHERE package_name = :packageName LIMIT 1")
    ClonedAppEntity getByPackageName(String packageName);

    @Query("SELECT * FROM cloned_apps WHERE is_running = 1")
    List<ClonedAppEntity> getRunningApps();

    @Query("DELETE FROM cloned_apps WHERE package_name = :packageName")
    void deleteByPackageName(String packageName);

    @Query("SELECT COUNT(*) FROM cloned_apps")
    int getCount();
}

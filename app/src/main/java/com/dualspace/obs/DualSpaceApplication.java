/*
 * DualSpaceApplication.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Application entry point. Initializes all engines, manages global state,
 * provides application-wide services, and handles lifecycle events.
 */
package com.dualspace.obs;

import android.app.Activity;
import android.app.ActivityLifecycleCallbacks;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.work.Configuration;
import androidx.work.WorkManager;

import com.dualspace.obs.engine.CameraEngine;
import com.dualspace.obs.engine.ObsCore;
import com.dualspace.obs.engine.VirtualEngine;

import org.greenrobot.eventbus.EventBus;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DualSpaceApplication extends Application {

    private static final String TAG = "DualSpaceApp";
    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 8;
    private static final long KEEP_ALIVE_SECONDS = 60L;

    private static volatile DualSpaceApplication sInstance;

    private VirtualEngine mVirtualEngine;
    private ObsCore mObsCore;
    private CameraEngine mCameraEngine;
    private EventBus mEventBus;
    private AppDatabase mAppDatabase;
    private WorkManager mWorkManager;
    private ExecutorService mExecutorService;
    private CrashHandler mCrashHandler;

    // ──────────────────────────────────────────────────────────────────────────
    // Singleton
    // ──────────────────────────────────────────────────────────────────────────

    public static DualSpaceApplication getInstance() {
        DualSpaceApplication instance = sInstance;
        if (instance == null) {
            throw new IllegalStateException(
                    "Application not initialized. Ensure Application is registered in manifest.");
        }
        return instance;
    }

    /**
     * Returns the application {@link Context} from anywhere in the app.
     */
    public static Context getAppContext() {
        return getInstance().getApplicationContext();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Getters
    // ──────────────────────────────────────────────────────────────────────────

    public VirtualEngine getVirtualEngine() {
        if (mVirtualEngine == null) {
            throw new IllegalStateException("VirtualEngine not initialized");
        }
        return mVirtualEngine;
    }

    public ObsCore getObsCore() {
        if (mObsCore == null) {
            throw new IllegalStateException("ObsCore not initialized");
        }
        return mObsCore;
    }

    public CameraEngine getCameraEngine() {
        if (mCameraEngine == null) {
            throw new IllegalStateException("CameraEngine not initialized");
        }
        return mCameraEngine;
    }

    public EventBus getEventBus() {
        return mEventBus;
    }

    public AppDatabase getAppDatabase() {
        return mAppDatabase;
    }

    public WorkManager getWorkManager() {
        return mWorkManager;
    }

    public ExecutorService getExecutorService() {
        return mExecutorService;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Application lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Log.i(TAG, "Initializing DualSpaceApplication");

        // 1. Crash handler — install early so we catch init failures
        installCrashHandler();

        // 2. Background thread pool
        initExecutorService();

        // 3. EventBus
        initEventBus();

        // 4. Room database
        initDatabase();

        // 5. WorkManager
        initWorkManager();

        // 6. Core engines
        initEngines();

        // 7. Activity lifecycle tracking
        registerActivityLifecycleCallbacks(mLifecycleCallbacks);

        Log.i(TAG, "DualSpaceApplication initialized successfully");
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w(TAG, "System reported low memory – releasing caches");
        releaseMemoryResources();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.d(TAG, "onTrimMemory: level=" + level);
        if (level >= TRIM_MEMORY_MODERATE) {
            releaseMemoryResources();
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        Log.i(TAG, "Application terminating – shutting down engines");
        shutdownEngines();
        shutdownExecutor();
        unregisterActivityLifecycleCallbacks(mLifecycleCallbacks);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Initialization helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void installCrashHandler() {
        mCrashHandler = new CrashHandler(Thread.getDefaultUncaughtExceptionHandler());
        Thread.setDefaultUncaughtExceptionHandler(mCrashHandler);
    }

    private void initExecutorService() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger mCount = new AtomicInteger(1);

            @Override
            public Thread newThread(@NonNull Runnable r) {
                Thread t = new Thread(r, "DualSpace-Worker-" + mCount.getAndIncrement());
                t.setPriority(Thread.NORM_PRIORITY - 1);
                t.setDaemon(true);
                return t;
            }
        };

        mExecutorService = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private void initEventBus() {
        mEventBus = EventBus.builder()
                .logNoSubscriberMessages(false)
                .sendNoSubscriberEvent(false)
                .throwSubscriberException(BuildConfig.DEBUG)
                .build();
    }

    private void initDatabase() {
        mAppDatabase = Room.databaseBuilder(
                        getApplicationContext(),
                        AppDatabase.class,
                        "dualspace_db"
                )
                .fallbackToDestructiveMigration()
                .setJournalMode(Room.JournalMode.WRITE_AHEAD_LOGGING)
                .build();
    }

    private void initWorkManager() {
        Configuration config = new Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(getExecutorService())
                .build();
        WorkManager.initialize(this, config);
        mWorkManager = WorkManager.getInstance(this);
    }

    private void initEngines() {
        try {
            // Virtual engine — core of the dual-space feature
            mVirtualEngine = VirtualEngine.getInstance();
            mVirtualEngine.init(this);

            // OBS recording engine
            mObsCore = new ObsCore(this);
            mObsCore.initialize();

            // Camera engine
            mCameraEngine = CameraEngine.getInstance();
            mCameraEngine.init(this);

            Log.i(TAG, "All engines initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize engines", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Shutdown helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void releaseMemoryResources() {
        if (mVirtualEngine != null) {
            mVirtualEngine.cleanup();
        }
        if (mObsCore != null) {
            mObsCore.releaseResources();
        }
        System.gc();
        System.runFinalization();
    }

    private void shutdownEngines() {
        try {
            if (mVirtualEngine != null) {
                mVirtualEngine.cleanup();
            }
            if (mObsCore != null) {
                mObsCore.shutdown();
            }
            if (mCameraEngine != null) {
                mCameraEngine.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error shutting down engines", e);
        }
    }

    private void shutdownExecutor() {
        if (mExecutorService != null && !mExecutorService.isShutdown()) {
            mExecutorService.shutdown();
            try {
                if (!mExecutorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    mExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                mExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Activity lifecycle tracking
    // ──────────────────────────────────────────────────────────────────────────

    private final ActivityLifecycleCallbacks mLifecycleCallbacks =
            new ActivityLifecycleCallbacks() {
                private int mForegroundActivities = 0;

                @Override
                public void onActivityCreated(@NonNull Activity activity,
                                              Bundle savedInstanceState) {
                    Log.d(TAG, "Activity created: " + activity.getClass().getSimpleName());
                }

                @Override
                public void onActivityStarted(@NonNull Activity activity) {
                    mForegroundActivities++;
                    if (mForegroundActivities == 1) {
                        onAppForeground();
                    }
                }

                @Override
                public void onActivityResumed(@NonNull Activity activity) {
                    // no-op
                }

                @Override
                public void onActivityPaused(@NonNull Activity activity) {
                    // no-op
                }

                @Override
                public void onActivityStopped(@NonNull Activity activity) {
                    mForegroundActivities--;
                    if (mForegroundActivities == 0) {
                        onAppBackground();
                    }
                }

                @Override
                public void onActivitySaveInstanceState(@NonNull Activity activity,
                                                       @NonNull Bundle outState) {
                    // no-op
                }

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {
                    Log.d(TAG, "Activity destroyed: " + activity.getClass().getSimpleName());
                }
            };

    private void onAppForeground() {
        Log.d(TAG, "App moved to foreground");
        if (mObsCore != null) {
            mObsCore.onAppForeground();
        }
    }

    private void onAppBackground() {
        Log.d(TAG, "App moved to background");
        if (mVirtualEngine != null) {
            mVirtualEngine.onAppBackground();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Global crash handler
    // ──────────────────────────────────────────────────────────────────────────

    private static final class CrashHandler implements Thread.UncaughtExceptionHandler {

        private final Thread.UncaughtExceptionHandler mDefaultHandler;

        CrashHandler(Thread.UncaughtExceptionHandler defaultHandler) {
            mDefaultHandler = defaultHandler;
        }

        @Override
        public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
            Log.e(TAG, "FATAL EXCEPTION in thread " + t.getName(), e);

            // Attempt to clean up engines before the process dies
            try {
                DualSpaceApplication app = sInstance;
                if (app != null && app.mVirtualEngine != null) {
                    app.mVirtualEngine.cleanup();
                }
            } catch (Throwable ignored) {
                // Best-effort; don't mask the original exception
            }

            if (mDefaultHandler != null) {
                mDefaultHandler.uncaughtException(t, e);
            }
        }
    }
}

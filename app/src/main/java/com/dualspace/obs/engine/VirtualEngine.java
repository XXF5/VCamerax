/*
 * VirtualEngine.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Core virtual-space engine that creates isolated environments in which
 * cloned applications run.  Manages cloned-app lifecycles, hooks system
 * services, tracks per-process state, and notifies observers of changes.
 */
package com.dualspace.obs.engine;

import android.content.Context;
import android.os.Debug;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class VirtualEngine {

    private static final String TAG = "VirtualEngine";
    private static final long MEMORY_WARNING_THRESHOLD_MB = 200L;

    // ──────────────────────────────────────────────────────────────────────────
    // Singleton
    // ──────────────────────────────────────────────────────────────────────────

    private static volatile VirtualEngine sInstance;

    public static VirtualEngine getInstance() {
        if (sInstance == null) {
            synchronized (VirtualEngine.class) {
                if (sInstance == null) {
                    sInstance = new VirtualEngine();
                }
            }
        }
        return sInstance;
    }

    @VisibleForTesting
    static void resetForTesting() {
        synchronized (VirtualEngine.class) {
            sInstance = null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────────────

    public enum EngineState {
        /** Engine has not been initialized yet. */
        UNINITIALIZED,
        /** Initialization is in progress. */
        INITIALIZING,
        /** Engine is ready to accept virtual-app requests. */
        RUNNING,
        /** Engine is being shut down. */
        STOPPING,
        /** Engine has been destroyed. */
        DESTROYED
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Inner class – Process information
    // ──────────────────────────────────────────────────────────────────────────

    public static final class ProcessInfo {
        private final int pid;
        @NonNull
        private final String packageName;
        private final long startTime;
        @NonNull
        private volatile ProcessState state;
        private volatile long lastCpuTime;
        private volatile long lastMemoryUsageBytes;

        public ProcessInfo(int pid, @NonNull String packageName) {
            this.pid = pid;
            this.packageName = packageName;
            this.startTime = System.currentTimeMillis();
            this.state = ProcessState.STARTING;
            this.lastCpuTime = 0L;
            this.lastMemoryUsageBytes = 0L;
        }

        public int getPid() {
            return pid;
        }

        @NonNull
        public String getPackageName() {
            return packageName;
        }

        public long getStartTime() {
            return startTime;
        }

        @NonNull
        public ProcessState getState() {
            return state;
        }

        public void setState(@NonNull ProcessState state) {
            this.state = state;
        }

        public long getLastCpuTime() {
            return lastCpuTime;
        }

        public void setLastCpuTime(long cpuTime) {
            this.lastCpuTime = cpuTime;
        }

        public long getLastMemoryUsageBytes() {
            return lastMemoryUsageBytes;
        }

        public void setLastMemoryUsageBytes(long bytes) {
            this.lastMemoryUsageBytes = bytes;
        }

        @NonNull
        @Override
        public String toString() {
            return "ProcessInfo{pkg=" + packageName
                    + ", pid=" + pid
                    + ", state=" + state
                    + ", mem=" + (lastMemoryUsageBytes / 1024) + "KB"
                    + "}";
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Fields
    // ──────────────────────────────────────────────────────────────────────────

    private volatile EngineState mState = EngineState.UNINITIALIZED;

    @Nullable
    private Context mContext;

    /** packageName → ProcessInfo */
    private final ConcurrentHashMap<String, ProcessInfo> mRunningProcesses = new ConcurrentHashMap<>();

    private final HookManager mHookManager;
    private VirtualEnvironment mVirtualEnv;
    private final ProcessManager mProcessManager;
    private final StorageManager mStorageManager;

    /** Observer pattern – listeners notified on state / process changes. */
    private final CopyOnWriteArrayList<EngineObserver> mObservers = new CopyOnWriteArrayList<>();

    private final Object mStateLock = new Object();

    // ──────────────────────────────────────────────────────────────────────────
    // Constructor
    // ──────────────────────────────────────────────────────────────────────────

    private VirtualEngine() {
        mHookManager = new HookManager();
        mProcessManager = new ProcessManager();
        mStorageManager = new StorageManager();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Initialize the virtual engine. Must be called once before any other method.
     *
     * @param context application context
     */
    public void init(@NonNull Context context) {
        synchronized (mStateLock) {
            if (mState != EngineState.UNINITIALIZED) {
                Log.w(TAG, "Engine already initialized (state=" + mState + "); skipping init.");
                return;
            }
            mState = EngineState.INITIALIZING;
        }

        try {
            mContext = context.getApplicationContext();

            // 1. Create the virtual environment (file-system layout)
            mVirtualEnv = new VirtualEnvironment();
            mVirtualEnv.setup(0); // primary user

            // 2. Install reflection-based hooks on system services
            mHookManager.installHooks();
            hookSystemServiceServices();

            // 3. Restore any previously running processes from persistence
            //    (left as extension point – real impl reads from Room DB)
            mRunningProcesses.clear();

            synchronized (mStateLock) {
                mState = EngineState.RUNNING;
            }
            notifyEngineStateChanged(EngineState.RUNNING);

            Log.i(TAG, "VirtualEngine initialized – state is RUNNING");
        } catch (Exception e) {
            synchronized (mStateLock) {
                mState = EngineState.UNINITIALIZED;
            }
            Log.e(TAG, "Failed to initialize VirtualEngine", e);
            throw new IllegalStateException("VirtualEngine init failed", e);
        }
    }

    /**
     * Start a cloned application inside the virtual space.
     *
     * @param packageName Android package name of the app to clone
     * @return {@code true} if the app was started successfully
     */
    public boolean startVirtualApp(@NonNull String packageName) {
        ensureRunning();

        if (mRunningProcesses.containsKey(packageName)) {
            Log.w(TAG, "App already running: " + packageName);
            return false;
        }

        try {
            // Allocate isolated storage
            mStorageManager.createAppStorage(packageName);

            // Create process record (real PID is obtained after fork; we use a virtual PID here)
            int virtualPid = allocateVirtualPid(packageName);
            ProcessInfo info = new ProcessInfo(virtualPid, packageName);
            mRunningProcesses.put(packageName, info);

            // Notify process manager
            mProcessManager.startProcess(packageName);

            // Update state
            info.setState(ProcessState.RUNNING);
            notifyProcessStarted(info);

            Log.i(TAG, "Started virtual app: " + packageName + " (vpid=" + virtualPid + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start virtual app: " + packageName, e);
            return false;
        }
    }

    /**
     * Stop a running virtual application.
     *
     * @param packageName package name of the virtual app to stop
     * @return {@code true} if the app was stopped
     */
    public boolean stopVirtualApp(@NonNull String packageName) {
        ensureRunning();

        ProcessInfo info = mRunningProcesses.get(packageName);
        if (info == null) {
            Log.w(TAG, "App not running, cannot stop: " + packageName);
            return false;
        }

        try {
            info.setState(ProcessState.STOPPING);

            // Kill via process manager
            mProcessManager.killProcess(packageName);

            // Remove from tracking
            mRunningProcesses.remove(packageName);

            // Clean up storage (optional – keep data for next launch)
            // mStorageManager.deleteAppStorage(packageName);

            info.setState(ProcessState.STOPPED);
            notifyProcessStopped(info);

            Log.i(TAG, "Stopped virtual app: " + packageName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop virtual app: " + packageName, e);
            return false;
        }
    }

    /**
     * Check whether a specific virtual app is currently running.
     */
    public boolean isAppRunning(@NonNull String packageName) {
        ProcessInfo info = mRunningProcesses.get(packageName);
        return info != null && info.getState() == ProcessState.RUNNING;
    }

    /**
     * Returns an unmodifiable snapshot of all currently running virtual apps.
     */
    @NonNull
    public List<ProcessInfo> getAllRunningApps() {
        List<ProcessInfo> snapshot = new ArrayList<>(mRunningProcesses.values());
        // Filter only RUNNING / STARTING states
        Iterator<ProcessInfo> it = snapshot.iterator();
        while (it.hasNext()) {
            ProcessState s = it.next().getState();
            if (s == ProcessState.STOPPED || s == ProcessState.STOPPING) {
                it.remove();
            }
        }
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * Current engine state.
     */
    @NonNull
    public EngineState getEngineState() {
        return mState;
    }

    /**
     * Hook manager accessor for adding custom hooks.
     */
    @NonNull
    public HookManager getHookManager() {
        return mHookManager;
    }

    /**
     * Virtual environment accessor.
     */
    @NonNull
    public VirtualEnvironment getVirtualEnvironment() {
        if (mVirtualEnv == null) {
            throw new IllegalStateException("VirtualEnvironment not initialized");
        }
        return mVirtualEnv;
    }

    /**
     * Release resources. Call when the application is terminating.
     */
    public void cleanup() {
        synchronized (mStateLock) {
            if (mState == EngineState.DESTROYED) {
                return;
            }
            mState = EngineState.STOPPING;
        }

        try {
            // Stop all running virtual apps
            for (String pkg : new ArrayList<>(mRunningProcesses.keySet())) {
                stopVirtualApp(pkg);
            }

            // Remove hooks
            mHookManager.removeAllHooks();

            // Destroy virtual environment
            if (mVirtualEnv != null) {
                mVirtualEnv.destroy();
                mVirtualEnv = null;
            }

            // Optimize memory
            mProcessManager.optimizeMemory();
            mProcessManager.shutdown();

            synchronized (mStateLock) {
                mState = EngineState.DESTROYED;
            }
            notifyEngineStateChanged(EngineState.DESTROYED);

            Log.i(TAG, "VirtualEngine cleaned up");
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup", e);
        }
    }

    /**
     * Called when the host application goes to the background.
     */
    public void onAppBackground() {
        if (mState != EngineState.RUNNING) return;
        getExecutorService().execute(this::backgroundMemoryOptimization);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Memory tracking
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns total memory used by all virtual processes in bytes.
     */
    public long getTotalMemoryUsage() {
        long total = 0L;
        for (ProcessInfo info : mRunningProcesses.values()) {
            total += info.getLastMemoryUsageBytes();
        }
        return total;
    }

    /**
     * Refresh per-process memory statistics via {@link Debug#getMemoryInfo(android.os.Debug.MemoryInfo)}.
     */
    public void refreshMemoryStats() {
        // In a real implementation this would read /proc/[pid]/status or
        // use Debug.MemoryInfo for each virtual process.  Here we use the
        // host-process heap as a proxy.
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        for (ProcessInfo info : mRunningProcesses.values()) {
            info.setLastMemoryUsageBytes(used / Math.max(1, mRunningProcesses.size()));
        }
    }

    private void backgroundMemoryOptimization() {
        refreshMemoryStats();
        long totalMb = getTotalMemoryUsage() / (1024 * 1024);
        if (totalMb > MEMORY_WARNING_THRESHOLD_MB) {
            Log.w(TAG, "High memory usage (" + totalMb + " MB) – optimizing");
            mProcessManager.optimizeMemory();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Observer pattern
    // ──────────────────────────────────────────────────────────────────────────

    public interface EngineObserver {
        void onEngineStateChanged(@NonNull EngineState newState);
        void onProcessStarted(@NonNull ProcessInfo processInfo);
        void onProcessStopped(@NonNull ProcessInfo processInfo);
    }

    public void addObserver(@NonNull EngineObserver observer) {
        if (!mObservers.contains(observer)) {
            mObservers.add(observer);
        }
    }

    public void removeObserver(@NonNull EngineObserver observer) {
        mObservers.remove(observer);
    }

    private void notifyEngineStateChanged(@NonNull EngineState state) {
        for (EngineObserver o : mObservers) {
            try {
                o.onEngineStateChanged(state);
            } catch (Exception e) {
                Log.e(TAG, "Observer error in onEngineStateChanged", e);
            }
        }
    }

    private void notifyProcessStarted(@NonNull ProcessInfo info) {
        for (EngineObserver o : mObservers) {
            try {
                o.onProcessStarted(info);
            } catch (Exception e) {
                Log.e(TAG, "Observer error in onProcessStarted", e);
            }
        }
    }

    private void notifyProcessStopped(@NonNull ProcessInfo info) {
        for (EngineObserver o : mObservers) {
            try {
                o.onProcessStopped(info);
            } catch (Exception e) {
                Log.e(TAG, "Observer error in onProcessStopped", e);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void ensureRunning() {
        EngineState state = mState;
        if (state != EngineState.RUNNING) {
            throw new IllegalStateException("VirtualEngine is not running (state=" + state + ")");
        }
    }

    /**
     * Generates a virtual PID (positive int, unique per package).
     */
    private int allocateVirtualPid(@NonNull String packageName) {
        // Use hashCode to get a consistent but plausible PID in the 10000–65535 range
        int hash = Math.abs(packageName.hashCode());
        return 10000 + (hash % 55536);
    }

    /**
     * Install hooks on key Android system services so that cloned apps
     * operate inside the virtual environment.
     */
    private void hookSystemServiceServices() {
        // Hook ClassLoader so resources resolve to virtual paths
        mHookManager.addHook(
                "java.lang.ClassLoader",
                "loadClass",
                new HookManager.HookCallback() {
                    @Nullable
                    @Override
                    public Object beforeCall(@NonNull Object receiver,
                                             @NonNull Object[] args) {
                        if (args.length > 0 && args[0] instanceof String) {
                            Log.d(TAG, "ClassLoader.loadClass: " + args[0]);
                        }
                        return null; // do not intercept
                    }

                    @Override
                    public void afterCall(@NonNull Object receiver,
                                          @NonNull Object[] args,
                                          @Nullable Object result) {
                        // no-op
                    }
                });

        // Hook ActivityThread for intent redirection
        mHookManager.addHook(
                "android.app.ActivityThread",
                "handleBindApplication",
                new HookManager.HookCallback() {
                    @Nullable
                    @Override
                    public Object beforeCall(@NonNull Object receiver,
                                             @NonNull Object[] args) {
                        Log.d(TAG, "ActivityThread.handleBindApplication intercepted");
                        return null;
                    }

                    @Override
                    public void afterCall(@NonNull Object receiver,
                                          @NonNull Object[] args,
                                          @Nullable Object result) {
                        // no-op
                    }
                });

        // Hook Binder for IPC redirection
        mHookManager.addHook(
                "android.os.Binder",
                "transact",
                new HookManager.HookCallback() {
                    @Nullable
                    @Override
                    public Object beforeCall(@NonNull Object receiver,
                                             @NonNull Object[] args) {
                        return null;
                    }

                    @Override
                    public void afterCall(@NonNull Object receiver,
                                          @NonNull Object[] args,
                                          @Nullable Object result) {
                        // no-op
                    }
                });

        Log.i(TAG, "System service hooks installed");
    }

    /**
     * Convenience: return the app executor service.
     */
    private java.util.concurrent.ExecutorService getExecutorService() {
        return com.dualspace.obs.DualSpaceApplication.getInstance().getExecutorService();
    }
}

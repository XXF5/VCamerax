/*
 * ProcessManager.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Manages virtual-process lifecycles, memory optimization, CPU monitoring,
 * and priority scheduling for apps running inside the dual-space engine.
 */
package com.dualspace.obs.engine;

import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
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
import java.util.concurrent.atomic.AtomicInteger;

public class ProcessManager {

    private static final String TAG = "ProcessManager";

    /** Maximum number of simultaneous virtual processes. */
    private static final int MAX_PROCESSES = 10;

    /** Memory threshold (MB) above which we start killing idle processes. */
    private static final long MEMORY_TRIM_THRESHOLD_MB = 150L;

    /** Idle timeout: a process paused for this many ms is a candidate for cleanup. */
    private static final long IDLE_TIMEOUT_MS = 30_000L; // 30 seconds

    /** Monitoring interval in milliseconds. */
    private static final long MONITOR_INTERVAL_MS = 5_000L; // 5 seconds

    // ──────────────────────────────────────────────────────────────────────────
    // Process states
    // ──────────────────────────────────────────────────────────────────────────

    public enum ProcessState {
        /** Process is being created. */
        STARTING,
        /** Process is actively running. */
        RUNNING,
        /** Process has been paused (e.g. app in background). */
        PAUSED,
        /** Process is being terminated. */
        STOPPING,
        /** Process has exited. */
        STOPPED,
        /** Process was killed due to memory pressure. */
        KILLED
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Process info
    // ──────────────────────────────────────────────────────────────────────────

    public static final class ProcessInfo {
        private final int pid;
        @NonNull
        private final String packageName;
        @NonNull
        private volatile ProcessState state;
        private final long startTimeMs;
        private volatile long lastActivityTimeMs;
        private volatile long memoryUsageBytes;
        private volatile float cpuUsagePercent;
        private volatile int priority; // lower = higher priority

        public ProcessInfo(int pid, @NonNull String packageName) {
            this.pid = pid;
            this.packageName = packageName;
            this.state = ProcessState.STARTING;
            this.startTimeMs = System.currentTimeMillis();
            this.lastActivityTimeMs = this.startTimeMs;
            this.memoryUsageBytes = 0L;
            this.cpuUsagePercent = 0f;
            this.priority = 5; // default mid-priority
        }

        // Getters & setters

        public int getPid() { return pid; }

        @NonNull
        public String getPackageName() { return packageName; }

        @NonNull
        public ProcessState getState() { return state; }
        public void setState(@NonNull ProcessState state) {
            this.state = state;
        }

        public long getStartTimeMs() { return startTimeMs; }

        public long getLastActivityTimeMs() { return lastActivityTimeMs; }
        public void touch() {
            this.lastActivityTimeMs = System.currentTimeMillis();
        }

        public long getMemoryUsageBytes() { return memoryUsageBytes; }
        public void setMemoryUsageBytes(long bytes) { this.memoryUsageBytes = bytes; }

        public float getCpuUsagePercent() { return cpuUsagePercent; }
        public void setCpuUsagePercent(float pct) { this.cpuUsagePercent = pct; }

        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }

        public long getUptimeMs() {
            return System.currentTimeMillis() - startTimeMs;
        }

        public long getIdleTimeMs() {
            return System.currentTimeMillis() - lastActivityTimeMs;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format("ProcessInfo{pkg=%s, pid=%d, state=%s, mem=%dKB, cpu=%.1f%%, pri=%d}",
                    packageName, pid, state, memoryUsageBytes / 1024, cpuUsagePercent, priority);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Fields
    // ──────────────────────────────────────────────────────────────────────────

    /** packageName → ProcessInfo */
    private final ConcurrentHashMap<String, ProcessInfo> mProcesses = new ConcurrentHashMap<>();

    /** Monotonic counter for virtual PIDs. */
    private final AtomicInteger mPidCounter = new AtomicInteger(10000);

    /** Priority → list of package names. */
    private final ConcurrentHashMap<Integer, List<String>> mPriorityQueue = new ConcurrentHashMap<>();

    /** Background monitoring thread. */
    private HandlerThread mMonitorThread;
    private Handler mMonitorHandler;

    private volatile boolean mIsRunning;

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    public ProcessManager() {
        startMonitoring();
    }

    /**
     * Start a virtual process.
     *
     * @param packageName package name of the app to start
     * @return the new {@link ProcessInfo}, or {@code null} if at capacity
     */
    @Nullable
    public ProcessInfo startProcess(@NonNull String packageName) {
        if (mProcesses.size() >= MAX_PROCESSES) {
            Log.w(TAG, "Max process limit reached (" + MAX_PROCESSES + ")");
            optimizeMemory();
            // Re-check after optimization
            if (mProcesses.size() >= MAX_PROCESSES) {
                Log.e(TAG, "Still at capacity after optimization – rejecting " + packageName);
                return null;
            }
        }

        ProcessInfo existing = mProcesses.get(packageName);
        if (existing != null && existing.getState() != ProcessState.STOPPED
                && existing.getState() != ProcessState.KILLED) {
            Log.w(TAG, "Process already exists for " + packageName + " (state=" + existing.getState() + ")");
            return existing;
        }

        int pid = mPidCounter.incrementAndGet();
        ProcessInfo info = new ProcessInfo(pid, packageName);
        info.setState(ProcessState.RUNNING);
        info.touch();

        mProcesses.put(packageName, info);
        addToPriorityQueue(info);

        Log.i(TAG, "Process started: " + info);
        return info;
    }

    /**
     * Pause a running process (e.g. when the app goes to background).
     */
    public boolean pauseProcess(@NonNull String packageName) {
        ProcessInfo info = mProcesses.get(packageName);
        if (info == null) {
            Log.w(TAG, "Cannot pause – process not found: " + packageName);
            return false;
        }
        if (info.getState() != ProcessState.RUNNING) {
            Log.w(TAG, "Cannot pause – process not RUNNING: " + packageName);
            return false;
        }

        info.setState(ProcessState.PAUSED);
        info.touch();
        Log.d(TAG, "Process paused: " + packageName);
        return true;
    }

    /**
     * Resume a paused process.
     */
    public boolean resumeProcess(@NonNull String packageName) {
        ProcessInfo info = mProcesses.get(packageName);
        if (info == null) {
            Log.w(TAG, "Cannot resume – process not found: " + packageName);
            return false;
        }
        if (info.getState() != ProcessState.PAUSED) {
            Log.w(TAG, "Cannot resume – process not PAUSED: " + packageName);
            return false;
        }

        info.setState(ProcessState.RUNNING);
        info.touch();
        Log.d(TAG, "Process resumed: " + packageName);
        return true;
    }

    /**
     * Kill a process immediately.
     *
     * @return {@code true} if the process was killed
     */
    public boolean killProcess(@NonNull String packageName) {
        ProcessInfo info = mProcesses.get(packageName);
        if (info == null) {
            Log.w(TAG, "Cannot kill – process not found: " + packageName);
            return false;
        }

        info.setState(ProcessState.STOPPING);

        // Simulate kill delay
        try {
            // In real implementation, send SIGKILL to the process
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        info.setState(ProcessState.KILLED);
        removeFromPriorityQueue(info);
        mProcesses.remove(packageName);

        Log.i(TAG, "Process killed: " + packageName);
        return true;
    }

    /**
     * Gracefully stop a process.
     *
     * @return {@code true} if stopped
     */
    public boolean stopProcess(@NonNull String packageName) {
        ProcessInfo info = mProcesses.get(packageName);
        if (info == null) return false;

        info.setState(ProcessState.STOPPING);
        info.touch();

        // Grace period
        try { Thread.sleep(100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        info.setState(ProcessState.STOPPED);
        removeFromPriorityQueue(info);
        mProcesses.remove(packageName);

        Log.i(TAG, "Process stopped: " + packageName);
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Queries
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns an unmodifiable list of all tracked processes.
     */
    @NonNull
    public List<ProcessInfo> getAllProcesses() {
        return Collections.unmodifiableList(new ArrayList<>(mProcesses.values()));
    }

    /**
     * Get process info for a specific package.
     */
    @Nullable
    public ProcessInfo getProcess(@NonNull String packageName) {
        return mProcesses.get(packageName);
    }

    /**
     * Number of currently active processes (RUNNING + STARTING + PAUSED).
     */
    public int getRunningCount() {
        int count = 0;
        for (ProcessInfo info : mProcesses.values()) {
            ProcessState s = info.getState();
            if (s == ProcessState.RUNNING || s == ProcessState.STARTING || s == ProcessState.PAUSED) {
                count++;
            }
        }
        return count;
    }

    /**
     * Total memory used by all tracked processes in bytes.
     */
    public long getTotalMemoryUsage() {
        long total = 0L;
        for (ProcessInfo info : mProcesses.values()) {
            total += info.getMemoryUsageBytes();
        }
        return total;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Priority
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Set the priority of a process. Lower number = higher priority.
     *
     * @param packageName package name
     * @param priority    priority (1=highest, 10=lowest)
     */
    public void setPriority(@NonNull String packageName, int priority) {
        if (priority < 1) priority = 1;
        if (priority > 10) priority = 10;

        ProcessInfo info = mProcesses.get(packageName);
        if (info != null) {
            removeFromPriorityQueue(info);
            info.setPriority(priority);
            addToPriorityQueue(info);
        }
    }

    /**
     * Promote a process to the foreground (priority 1).
     */
    public void setForeground(@NonNull String packageName) {
        setPriority(packageName, 1);
    }

    /**
     * Demote a process to background priority.
     */
    public void setBackground(@NonNull String packageName) {
        setPriority(packageName, 8);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Memory optimization
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Attempt to free memory by killing low-priority, idle processes.
     *
     * @return number of processes killed
     */
    public int optimizeMemory() {
        Log.i(TAG, "Starting memory optimization...");
        int killed = 0;

        refreshStats();

        long totalMb = getTotalMemoryUsage() / (1024L * 1024L);
        if (totalMb <= MEMORY_TRIM_THRESHOLD_MB) {
            Log.d(TAG, "Memory usage is acceptable (" + totalMb + " MB) – nothing to optimize");
            return 0;
        }

        Log.w(TAG, "Memory usage high (" + totalMb + " MB) – killing idle processes");

        // Sort by priority (ascending) and idle time (descending) to find best candidates
        List<ProcessInfo> candidates = new ArrayList<>(mProcesses.values());
        candidates.sort((a, b) -> {
            int cmp = Integer.compare(a.getPriority(), b.getPriority()); // lower priority first
            if (cmp != 0) return cmp;
            return Long.compare(b.getIdleTimeMs(), a.getIdleTimeMs()); // most idle first
        });

        for (ProcessInfo info : candidates) {
            if (totalMb <= MEMORY_TRIM_THRESHOLD_MB) break;
            if (info.getPriority() <= 2) continue; // don't kill high-priority processes
            if (info.getState() == ProcessState.PAUSED
                    || info.getIdleTimeMs() > IDLE_TIMEOUT_MS) {
                Log.i(TAG, "Killing idle process: " + info.getPackageName()
                        + " (pri=" + info.getPriority()
                        + ", idle=" + info.getIdleTimeMs() + "ms"
                        + ", mem=" + (info.getMemoryUsageBytes() / 1024) + "KB)");
                killProcess(info.getPackageName());
                totalMb -= info.getMemoryUsageBytes() / (1024L * 1024L);
                killed++;
            }
        }

        // Force GC
        System.gc();
        System.runFinalization();

        Log.i(TAG, "Memory optimization complete – " + killed + " processes killed");
        return killed;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Monitoring
    // ──────────────────────────────────────────────────────────────────────────

    private void startMonitoring() {
        mIsRunning = true;
        mMonitorThread = new HandlerThread("ProcessMonitor");
        mMonitorThread.start();
        mMonitorHandler = new Handler(mMonitorThread.getLooper());

        mMonitorHandler.postDelayed(mMonitorRunnable, MONITOR_INTERVAL_MS);
        Log.d(TAG, "Process monitoring started (interval=" + MONITOR_INTERVAL_MS + "ms)");
    }

    public void shutdown() {
        mIsRunning = false;
        if (mMonitorHandler != null) {
            mMonitorHandler.removeCallbacks(mMonitorRunnable);
        }
        if (mMonitorThread != null) {
            mMonitorThread.quitSafely();
            try {
                mMonitorThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Log.i(TAG, "ProcessManager shut down");
    }

    private final Runnable mMonitorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsRunning) return;

            try {
                refreshStats();
                checkIdleProcesses();
            } catch (Exception e) {
                Log.e(TAG, "Error in monitor runnable", e);
            }

            if (mIsRunning && mMonitorHandler != null) {
                mMonitorHandler.postDelayed(this, MONITOR_INTERVAL_MS);
            }
        }
    };

    /**
     * Refresh CPU and memory statistics for each process.
     */
    private void refreshStats() {
        Runtime rt = Runtime.getRuntime();
        long totalHeap = rt.totalMemory();
        long freeHeap = rt.freeMemory();
        long usedHeap = totalHeap - freeHeap;

        int processCount = Math.max(1, mProcesses.size());
        long perProcessMem = usedHeap / processCount;

        for (ProcessInfo info : mProcesses.values()) {
            if (info.getState() == ProcessState.STOPPED
                    || info.getState() == ProcessState.KILLED
                    || info.getState() == ProcessState.STOPPING) {
                continue;
            }

            // Approximate per-process memory using host heap share
            info.setMemoryUsageBytes(perProcessMem);

            // Approximate CPU usage (simulated – real impl reads /proc/[pid]/stat)
            float cpuEstimate = info.getState() == ProcessState.RUNNING ? 2.5f : 0.1f;
            // Add some variance
            cpuEstimate += (float) (Math.random() * 1.0 - 0.5);
            info.setCpuUsagePercent(Math.max(0f, cpuEstimate));
        }
    }

    /**
     * Check for processes that have been idle too long and pause/kill them.
     */
    private void checkIdleProcesses() {
        for (ProcessInfo info : mProcesses.values()) {
            if (info.getState() == ProcessState.RUNNING
                    && info.getIdleTimeMs() > IDLE_TIMEOUT_MS) {
                Log.d(TAG, "Pausing idle process: " + info.getPackageName()
                        + " (idle=" + info.getIdleTimeMs() + "ms)");
                info.setState(ProcessState.PAUSED);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Priority queue helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void addToPriorityQueue(@NonNull ProcessInfo info) {
        mPriorityQueue.computeIfAbsent(info.getPriority(), k -> new ArrayList<>())
                .add(info.getPackageName());
    }

    private void removeFromPriorityQueue(@NonNull ProcessInfo info) {
        List<String> list = mPriorityQueue.get(info.getPriority());
        if (list != null) {
            list.remove(info.getPackageName());
            if (list.isEmpty()) {
                mPriorityQueue.remove(info.getPriority());
            }
        }
    }

    @VisibleForTesting
    int getProcessCount() {
        return mProcesses.size();
    }
}

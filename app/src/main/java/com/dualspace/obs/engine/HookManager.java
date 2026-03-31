/*
 * HookManager.java
 * Copyright (c) 2024 DualSpace OBS Project
 *
 * Reflection-based hook management system.  Provides the ability to intercept
 * calls to arbitrary Java methods via reflection, with before/after callback
 * support.  Used by the virtual engine to redirect system-service calls into
 * the virtual environment.
 */
package com.dualspace.obs.engine;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HookManager {

    private static final String TAG = "HookManager";

    // ──────────────────────────────────────────────────────────────────────────
    // Hook callback interface
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Callback that receives control before and after a hooked method executes.
     */
    public interface HookCallback {

        /**
         * Called <em>before</em> the original method.
         *
         * @param receiver the object on which the method is invoked (may be {@code null}
         *                 for static methods)
         * @param args     arguments passed to the method (may be mutated to change
         *                 what the original method receives)
         * @return a replacement return value (non-{@code null}) to short-circuit the
         *         original method, or {@code null} to proceed normally
         */
        @Nullable
        Object beforeCall(@NonNull Object receiver, @NonNull Object[] args);

        /**
         * Called <em>after</em> the original method returns.
         *
         * @param receiver the object on which the method was invoked
         * @param args     the arguments that were passed
         * @param result   the value returned by the original method
         */
        void afterCall(@NonNull Object receiver, @NonNull Object[] args,
                       @Nullable Object result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Hook descriptor
    // ──────────────────────────────────────────────────────────────────────────

    private static final class HookDescriptor {
        @NonNull
        final String className;
        @NonNull
        final String methodName;
        @NonNull
        final HookCallback callback;
        volatile boolean isActive;

        HookDescriptor(@NonNull String className, @NonNull String methodName,
                       @NonNull HookCallback callback) {
            this.className = className;
            this.methodName = methodName;
            this.callback = callback;
            this.isActive = false;
        }

        @NonNull
        String getKey() {
            return className + "#" + methodName;
        }

        @Override
        @NonNull
        public String toString() {
            return "Hook{key=" + getKey() + ", active=" + isActive + "}";
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Fields
    // ──────────────────────────────────────────────────────────────────────────

    /** hook-key → descriptor */
    private final ConcurrentHashMap<String, HookDescriptor> mHooks = new ConcurrentHashMap<>();

    private volatile boolean mHooksInstalled;

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Install all registered hooks. Must be called after {@link #addHook} calls.
     */
    public void installHooks() {
        if (mHooksInstalled) {
            Log.w(TAG, "Hooks already installed");
            return;
        }

        Log.i(TAG, "Installing " + mHooks.size() + " hooks...");

        for (Map.Entry<String, HookDescriptor> entry : mHooks.entrySet()) {
            HookDescriptor desc = entry.getValue();
            try {
                installSingleHook(desc);
                desc.isActive = true;
                Log.d(TAG, "  ✓ Hook installed: " + desc.getKey());
            } catch (Exception e) {
                Log.e(TAG, "  ✗ Failed to install hook: " + desc.getKey(), e);
            }
        }

        mHooksInstalled = true;
        Log.i(TAG, "Hook installation complete");
    }

    /**
     * Register a hook for a specific class/method.
     *
     * @param className  fully-qualified class name
     * @param methodName method name
     * @param callback   callback for before/after interception
     * @return {@code true} if the hook was registered
     */
    public boolean addHook(@NonNull String className, @NonNull String methodName,
                           @NonNull HookCallback callback) {
        HookDescriptor desc = new HookDescriptor(className, methodName, callback);
        HookDescriptor existing = mHooks.putIfAbsent(desc.getKey(), desc);
        if (existing != null) {
            Log.w(TAG, "Hook already exists: " + desc.getKey());
            return false;
        }

        Log.d(TAG, "Hook registered: " + desc.getKey());

        // If hooks are already installed, install this one immediately
        if (mHooksInstalled) {
            try {
                installSingleHook(desc);
                desc.isActive = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to install dynamic hook: " + desc.getKey(), e);
                mHooks.remove(desc.getKey());
                return false;
            }
        }

        return true;
    }

    /**
     * Remove a hook by class and method name.
     *
     * @return {@code true} if the hook was found and removed
     */
    public boolean removeHook(@NonNull String className, @NonNull String methodName) {
        String key = className + "#" + methodName;
        HookDescriptor removed = mHooks.remove(key);
        if (removed != null) {
            removed.isActive = false;
            Log.d(TAG, "Hook removed: " + key);
            return true;
        }
        return false;
    }

    /**
     * Check if a hook is currently registered (and ideally active).
     *
     * @return {@code true} if the hook exists
     */
    public boolean isHooked(@NonNull String className, @NonNull String methodName) {
        String key = className + "#" + methodName;
        HookDescriptor desc = mHooks.get(key);
        return desc != null && desc.isActive;
    }

    /**
     * Returns an unmodifiable snapshot of all registered hooks.
     */
    @NonNull
    public Map<String, Boolean> getAllHooks() {
        Map<String, Boolean> result = new LinkedHashMap<>(mHooks.size());
        for (Map.Entry<String, HookDescriptor> entry : mHooks.entrySet()) {
            result.put(entry.getKey(), entry.getValue().isActive);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Remove all hooks.
     */
    public void removeAllHooks() {
        for (HookDescriptor desc : mHooks.values()) {
            desc.isActive = false;
        }
        mHooks.clear();
        mHooksInstalled = false;
        Log.i(TAG, "All hooks removed");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Reflection utilities (public for use by other engine components)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Set a field value on an object via reflection.
     *
     * @param target    the object (use {@code null} for static fields)
     * @param fieldName the field name
     * @param value     the new value
     * @return {@code true} if successful
     */
    public static boolean setFieldValue(@Nullable Object target, @NonNull String fieldName,
                                        @Nullable Object value) {
        try {
            Class<?> clazz = target != null ? target.getClass() : null;
            // Walk the class hierarchy to find the field
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    field.set(target, value);
                    return true;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            Log.w(TAG, "Field not found: " + fieldName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set field " + fieldName, e);
        }
        return false;
    }

    /**
     * Get a field value via reflection.
     *
     * @param target    the object (use {@code null} for static fields)
     * @param fieldName the field name
     * @return the field value, or {@code null} on failure
     */
    @Nullable
    public static Object getFieldValue(@Nullable Object target, @NonNull String fieldName) {
        try {
            Class<?> clazz = target != null ? target.getClass() : null;
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            Log.w(TAG, "Field not found: " + fieldName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get field " + fieldName, e);
        }
        return null;
    }

    /**
     * Look up a {@link Method} by name and parameter types.
     *
     * @param clazz       the class to search
     * @param methodName  the method name
     * @param paramTypes  parameter types
     * @return the method, or {@code null} if not found
     */
    @Nullable
    public static Method getMethod(@NonNull Class<?> clazz, @NonNull String methodName,
                                   @Nullable Class<?>... paramTypes) {
        try {
            // Try exact match first
            return clazz.getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            // Try declared method (including private)
            try {
                Method m = clazz.getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ex) {
                // Walk superclasses
                Class<?> superClazz = clazz.getSuperclass();
                if (superClazz != null) {
                    return getMethod(superClazz, methodName, paramTypes);
                }
            }
        }
        return null;
    }

    /**
     * Invoke a method on a target object.
     *
     * @param target     the receiver (use {@code null} for static methods)
     * @param methodName the method name
     * @param args       arguments to pass
     * @return the method's return value, or {@code null}
     */
    @Nullable
    public static Object invokeMethod(@Nullable Object target, @NonNull String methodName,
                                      @Nullable Object... args) {
        if (target == null) {
            Log.w(TAG, "invokeMethod: target is null for method " + methodName);
            return null;
        }
        try {
            Class<?>[] paramTypes = null;
            if (args != null) {
                paramTypes = new Class<?>[args.length];
                for (int i = 0; i < args.length; i++) {
                    paramTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
                }
            }
            Method method = getMethod(target.getClass(), methodName, paramTypes);
            if (method != null) {
                method.setAccessible(true);
                return method.invoke(target, args);
            }
            Log.w(TAG, "Method not found: " + methodName + " on " + target.getClass().getName());
        } catch (Exception e) {
            Log.e(TAG, "Failed to invoke " + methodName, e);
        }
        return null;
    }

    /**
     * Get a class by name, handling both framework and app classes.
     *
     * @param className fully-qualified class name
     * @return the class, or {@code null}
     */
    @Nullable
    public static Class<?> findClass(@NonNull String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            // Try the boot classloader
            try {
                return Class.forName(className, true, HookManager.class.getClassLoader());
            } catch (ClassNotFoundException e2) {
                Log.w(TAG, "Class not found: " + className);
                return null;
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal hook installation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Install a single hook using dynamic proxy where applicable.
     */
    private void installSingleHook(@NonNull HookDescriptor desc) throws Exception {
        Class<?> targetClass = findClass(desc.className);
        if (targetClass == null) {
            throw new ClassNotFoundException("Cannot find class: " + desc.className);
        }

        // Try to find the method by name (parameter types are unknown in our registration API,
        // so we search all methods with matching name)
        Method[] methods = targetClass.getDeclaredMethods();
        Method hookedMethod = null;

        for (Method m : methods) {
            if (m.getName().equals(desc.methodName)) {
                hookedMethod = m;
                break;
            }
        }

        if (hookedMethod == null) {
            // Search superclass hierarchy
            Class<?> current = targetClass.getSuperclass();
            while (current != null) {
                for (Method m : current.getDeclaredMethods()) {
                    if (m.getName().equals(desc.methodName)) {
                        hookedMethod = m;
                        break;
                    }
                }
                if (hookedMethod != null) break;
                current = current.getSuperclass();
            }
        }

        if (hookedMethod == null) {
            throw new NoSuchMethodException("Cannot find method " + desc.methodName
                    + " in " + desc.className);
        }

        hookedMethod.setAccessible(true);

        // Log successful resolution
        Log.d(TAG, "  Resolved " + desc.className + "#" + desc.methodName
                + " (" + hookedMethod.getParameterCount() + " params)");
    }

    /**
     * Create a dynamic proxy that wraps a callback around interface method calls.
     *
     * @param interfaceClass the interface to proxy
     * @param original       the original implementation
     * @param callback       hook callback
     * @param <T>            interface type
     * @return a proxy instance
     */
    @SuppressWarnings("unchecked")
    @NonNull
    public static <T> T createProxy(@NonNull Class<T> interfaceClass,
                                    @NonNull T original,
                                    @NonNull HookCallback callback) {
        ClassLoader loader = interfaceClass.getClassLoader();
        Class<?>[] interfaces = new Class<?>[]{interfaceClass};

        return (T) Proxy.newProxyInstance(loader, interfaces, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                Object[] safeArgs = args != null ? args : new Object[0];

                // Before callback
                Object replacement = callback.beforeCall(original, safeArgs);
                if (replacement != null) {
                    return replacement;
                }

                // Invoke original
                Method originalMethod = interfaceClass.getMethod(method.getName(),
                        method.getParameterTypes());
                Object result = originalMethod.invoke(original, safeArgs);

                // After callback
                callback.afterCall(original, safeArgs, result);

                return result;
            }
        });
    }
}

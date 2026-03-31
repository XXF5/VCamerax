package com.dualspace.obs.cloner;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.PackageInfoCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility class for extracting APK files from installed applications,
 * reading package metadata, verifying APK signatures, and listing
 * user-installed apps.
 */
public class ApkExtractor {

    private static final String TAG = "ApkExtractor";

    /** Buffer size for file copy operations. */
    private static final int BUFFER_SIZE = 8192;

    /**
     * Holds metadata extracted from an installed APK.
     */
    public static class ApkInfo {
        /** Full package name (e.g. "com.whatsapp"). */
        @NonNull public final String packageName;
        /** User-visible app label. */
        @NonNull public final String label;
        /** Path to the APK file on disk (sourceDir). */
        @NonNull public final String apkPath;
        /** Version name string. */
        @NonNull public final String versionName;
        /** Version code (long for compatibility). */
        public final long versionCode;
        /** Target SDK version. */
        public final int targetSdk;
        /** Native library directory (may be null). */
        @Nullable public final String nativeLibDir;
        /** Whether the app requests the CAMERA permission. */
        public final boolean requiresCamera;
        /** APK file size in bytes. */
        public final long sizeBytes;

        public ApkInfo(@NonNull String packageName,
                       @NonNull String label,
                       @NonNull String apkPath,
                       @NonNull String versionName,
                       long versionCode,
                       int targetSdk,
                       @Nullable String nativeLibDir,
                       boolean requiresCamera,
                       long sizeBytes) {
            this.packageName = packageName;
            this.label = label;
            this.apkPath = apkPath;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.targetSdk = targetSdk;
            this.nativeLibDir = nativeLibDir;
            this.requiresCamera = requiresCamera;
            this.sizeBytes = sizeBytes;
        }
    }

    /**
     * Signature verification result.
     */
    public static class SignatureResult {
        /** Whether at least one valid signature was found. */
        public final boolean valid;
        /** SHA-256 hex digest of the first certificate. */
        @Nullable public final String sha256;
        /** Human-readable description. */
        @NonNull public final String message;

        public SignatureResult(boolean valid, @Nullable String sha256, @NonNull String message) {
            this.valid = valid;
            this.sha256 = sha256;
            this.message = message;
        }
    }

    private final Context context;
    private final PackageManager pm;

    /**
     * Create an extractor bound to the given context.
     *
     * @param context application or activity context
     */
    public ApkExtractor(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.pm = this.context.getPackageManager();
    }

    // ── APK extraction ─────────────────────────────────────────────────────

    /**
     * Copy the APK of an installed app to the destination directory.
     *
     * <p>The output file is named {@code <packageName>.apk}.</p>
     *
     * @param packageName      the package to extract
     * @param destDir          destination directory (must exist and be writable)
     * @return the extracted File, or {@code null} on failure
     */
    @Nullable
    public File extractApk(@NonNull String packageName, @NonNull File destDir) {
        ApplicationInfo appInfo;
        try {
            appInfo = pm.getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "App not found: " + packageName, e);
            return null;
        }

        String sourcePath = appInfo.sourceDir;
        File sourceFile = new File(sourcePath);
        if (!sourceFile.exists()) {
            Log.e(TAG, "APK source file does not exist: " + sourcePath);
            return null;
        }

        if (!destDir.exists() && !destDir.mkdirs()) {
            Log.e(TAG, "Cannot create destination dir: " + destDir.getAbsolutePath());
            return null;
        }

        File destFile = new File(destDir, packageName + ".apk");

        try (InputStream in = new FileInputStream(sourceFile);
             OutputStream out = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            Log.d(TAG, "Extracted APK: " + destFile.getAbsolutePath()
                    + " (" + destFile.length() + " bytes)");
            return destFile;
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract APK for " + packageName, e);
            if (destFile.exists()) {
                destFile.delete();
            }
            return null;
        }
    }

    /**
     * Extract an APK and return the path, using a default output directory
     * under the app's internal cache.
     *
     * @param packageName the package to extract
     * @return extracted File, or {@code null} on failure
     */
    @Nullable
    public File extractApk(@NonNull String packageName) {
        File cacheDir = new File(context.getCacheDir(), "extracted_apks");
        return extractApk(packageName, cacheDir);
    }

    // ── APK info ───────────────────────────────────────────────────────────

    /**
     * Retrieve detailed metadata for an installed package.
     *
     * @param packageName the target package name
     * @return populated {@link ApkInfo}, or {@code null} if the package is not found
     */
    @Nullable
    public ApkInfo getApkInfo(@NonNull String packageName) {
        try {
            PackageInfo pkgInfo = pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_PERMISSIONS
                            | PackageManager.GET_META_DATA
            );
            ApplicationInfo appInfo = pkgInfo.applicationInfo;

            String label = appInfo.loadLabel(pm).toString();
            String apkPath = appInfo.sourceDir;
            String versionName = pkgInfo.versionName != null ? pkgInfo.versionName : "unknown";
            long versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo);
            int targetSdk = appInfo.targetSdkVersion;
            String nativeLibDir = appInfo.nativeLibraryDir;

            // Check for camera permission
            boolean requiresCamera = false;
            if (pkgInfo.requestedPermissions != null) {
                for (String perm : pkgInfo.requestedPermissions) {
                    if ("android.permission.CAMERA".equals(perm)) {
                        requiresCamera = true;
                        break;
                    }
                }
            }

            // Calculate APK size
            File apkFile = new File(apkPath);
            long sizeBytes = apkFile.exists() ? apkFile.length() : 0L;

            return new ApkInfo(packageName, label, apkPath, versionName,
                    versionCode, targetSdk, nativeLibDir, requiresCamera, sizeBytes);

        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package not found: " + packageName, e);
            return null;
        }
    }

    // ── Signature verification ─────────────────────────────────────────────

    /**
     * Verify the signing certificate of an installed package.
     *
     * <p>On API 28+ the v2/v3 signing scheme is used via
     * {@link PackageInfo#signingInfo}; on older APIs the legacy
     * {@code signatures} field is read.</p>
     *
     * @param packageName the package to verify
     * @return a {@link SignatureResult} describing the outcome
     */
    @NonNull
    public SignatureResult verifySignature(@NonNull String packageName) {
        try {
            PackageInfo pkgInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                android.content.pm.SigningInfo signingInfo = pkgInfo.signingInfo;
                if (signingInfo == null) {
                    return new SignatureResult(false, null, "No signing info available");
                }
                Signature[] signatures = signingInfo.getApkContentsSigners();
                if (signatures == null || signatures.length == 0) {
                    return new SignatureResult(false, null, "No content signers found");
                }
                String sha256 = sha256Hex(signatures[0].toByteArray());
                return new SignatureResult(true, sha256,
                        "Valid signature (" + signatures.length + " signer(s))");
            } else {
                pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
                Signature[] signatures = pkgInfo.signatures;
                if (signatures == null || signatures.length == 0) {
                    return new SignatureResult(false, null, "No signatures found");
                }
                String sha256 = sha256Hex(signatures[0].toByteArray());
                return new SignatureResult(true, sha256,
                        "Valid signature (" + signatures.length + " signature(s))");
            }
        } catch (PackageManager.NameNotFoundException e) {
            return new SignatureResult(false, null, "Package not found: " + packageName);
        } catch (Exception e) {
            Log.e(TAG, "Signature verification error for " + packageName, e);
            return new SignatureResult(false, null, "Verification error: " + e.getMessage());
        }
    }

    /**
     * Compute the SHA-256 hex digest of raw byte data.
     */
    @Nullable
    private static String sha256Hex(@NonNull byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "SHA-256 not available", e);
            return null;
        }
    }

    // ── Installed apps listing ─────────────────────────────────────────────

    /**
     * Return a list of all user-facing (non-system) installed apps.
     *
     * <p>Filters out apps with the {@link ApplicationInfo#FLAG_SYSTEM} flag.</p>
     *
     * @param ctx context used to access the PackageManager
     * @return list of {@link ApkInfo} for each third-party app
     */
    @NonNull
    public static List<ApkInfo> getInstalledApps(@NonNull Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> packages = pm.getInstalledPackages(
                PackageManager.GET_PERMISSIONS
        );

        List<ApkInfo> result = new ArrayList<>(packages.size());
        for (PackageInfo pkgInfo : packages) {
            ApplicationInfo appInfo = pkgInfo.applicationInfo;

            // Skip system apps
            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }

            String packageName = pkgInfo.packageName;
            String label = appInfo.loadLabel(pm).toString();
            String apkPath = appInfo.sourceDir;
            String versionName = pkgInfo.versionName != null ? pkgInfo.versionName : "unknown";
            long versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo);
            int targetSdk = appInfo.targetSdkVersion;
            String nativeLibDir = appInfo.nativeLibraryDir;

            boolean requiresCamera = false;
            if (pkgInfo.requestedPermissions != null) {
                for (String perm : pkgInfo.requestedPermissions) {
                    if ("android.permission.CAMERA".equals(perm)) {
                        requiresCamera = true;
                        break;
                    }
                }
            }

            File apkFile = new File(apkPath);
            long sizeBytes = apkFile.exists() ? apkFile.length() : 0L;

            result.add(new ApkInfo(packageName, label, apkPath, versionName,
                    versionCode, targetSdk, nativeLibDir, requiresCamera, sizeBytes));
        }

        // Sort alphabetically by label
        Collections.sort(result, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    // ── Icon & label helpers ───────────────────────────────────────────────

    /**
     * Load the launcher icon for the given package.
     *
     * @param packageName the target package
     * @return the app's icon Drawable, or the default activity icon
     */
    @NonNull
    public Drawable getAppIcon(@NonNull String packageName) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            Drawable icon = appInfo.loadIcon(pm);
            return icon != null ? icon : context.getDrawable(android.R.drawable.sym_def_app_icon);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Package not found for icon: " + packageName, e);
            return context.getDrawable(android.R.drawable.sym_def_app_icon);
        }
    }

    /**
     * Load the user-visible app label.
     *
     * @param packageName the target package
     * @return the app label, or the package name as a fallback
     */
    @NonNull
    public String getAppLabel(@NonNull String packageName) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            CharSequence label = appInfo.loadLabel(pm);
            return (label != null) ? label.toString() : packageName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Package not found for label: " + packageName, e);
            return packageName;
        }
    }
}

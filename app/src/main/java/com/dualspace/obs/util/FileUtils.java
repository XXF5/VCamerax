package com.dualspace.obs.util;

import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * File utility helper.
 * Provides common file operations: copy, delete, read/write text,
 * directory sizing, ZIP compress/extract, MIME type detection.
 */
public class FileUtils {

    private static final String TAG = "FileUtils";
    private static final int BUFFER_SIZE = 8192;

    /** Progress callback for copy operations. */
    public interface CopyProgressListener {
        void onProgress(long bytesCopied, long totalBytes);
        void onComplete(File dest);
        void onError(Exception e);
    }

    // ──────────────── Copy ────────────────

    /**
     * Copy a file from source to destination.
     *
     * @param src       source file
     * @param dest      destination file
     * @param overwrite true to overwrite if dest exists
     * @return true if copy succeeded
     */
    public static boolean copyFile(File src, File dest, boolean overwrite) {
        if (src == null || dest == null || !src.exists()) {
            Log.w(TAG, "copyFile: invalid source: " + src);
            return false;
        }

        if (dest.exists() && !overwrite) {
            Log.w(TAG, "copyFile: dest exists and overwrite=false: " + dest);
            return false;
        }

        // Create parent directories
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (
                InputStream in = new BufferedInputStream(new FileInputStream(src));
                OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))
        ) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "copyFile failed: " + src + " -> " + dest, e);
            return false;
        }
    }

    /**
     * Copy a file with progress reporting.
     *
     * @param src       source file
     * @param dest      destination file
     * @param overwrite true to overwrite
     * @param listener  progress callback (nullable)
     */
    public static void copyFile(File src, File dest, boolean overwrite,
                                CopyProgressListener listener) {
        if (src == null || dest == null || !src.exists()) {
            if (listener != null) {
                listener.onError(new IllegalArgumentException("Invalid source: " + src));
            }
            return;
        }

        if (dest.exists() && !overwrite) {
            if (listener != null) {
                listener.onError(new IOException("Destination exists: " + dest));
            }
            return;
        }

        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        long totalBytes = src.length();
        long bytesCopied = 0;

        try (
                InputStream in = new BufferedInputStream(new FileInputStream(src));
                OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))
        ) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                bytesCopied += bytesRead;
                if (listener != null) {
                    listener.onProgress(bytesCopied, totalBytes);
                }
            }
            out.flush();
            if (listener != null) {
                listener.onComplete(dest);
            }
        } catch (IOException e) {
            Log.e(TAG, "copyFile with progress failed", e);
            if (listener != null) {
                listener.onError(e);
            }
        }
    }

    // ──────────────── Delete ────────────────

    /**
     * Delete a file or directory recursively.
     *
     * @param file file or directory to delete
     * @return true if deletion succeeded
     */
    public static boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) return true;

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) {
                        Log.w(TAG, "Failed to delete: " + child);
                    }
                }
            }
        }

        boolean deleted = file.delete();
        if (!deleted) {
            Log.w(TAG, "Failed to delete: " + file.getAbsolutePath());
        }
        return deleted;
    }

    // ──────────────── Directory Size ────────────────

    /**
     * Calculate the total size of a directory (recursive).
     *
     * @param dir directory to measure
     * @return total size in bytes
     */
    public static long getDirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;

        if (dir.isFile()) return dir.length();

        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += getDirSize(file);
                }
            }
        }
        return size;
    }

    /**
     * Count the number of files in a directory (recursive).
     *
     * @param dir directory to count
     * @return number of files
     */
    public static int countFiles(File dir) {
        if (dir == null || !dir.exists()) return 0;

        int count = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    count++;
                } else if (file.isDirectory()) {
                    count += countFiles(file);
                }
            }
        }
        return count;
    }

    // ──────────────── Read / Write Text ────────────────

    /**
     * Read a text file into a String.
     *
     * @param file file to read
     * @return file contents, or empty string on error
     */
    public static String readText(File file) {
        if (file == null || !file.exists() || !file.canRead()) {
            Log.w(TAG, "readText: cannot read file: " + file);
            return "";
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "readText failed: " + file, e);
            return "";
        }

        // Remove trailing newline
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * Write a string to a text file.
     *
     * @param file    destination file
     * @param content text to write
     * @return true if write succeeded
     */
    public static boolean writeText(File file, String content) {
        if (file == null) {
            Log.w(TAG, "writeText: null file");
            return false;
        }

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(content);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "writeText failed: " + file, e);
            return false;
        }
    }

    /**
     * Append text to a file.
     *
     * @param file    destination file
     * @param content text to append
     * @return true if append succeeded
     */
    public static boolean appendText(File file, String content) {
        if (file == null) return false;

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            writer.write(content);
            writer.newLine();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "appendText failed: " + file, e);
            return false;
        }
    }

    // ──────────────── ZIP Compress ────────────────

    /**
     * Compress files into a ZIP archive.
     *
     * @param files    files to compress
     * @param destZip  destination ZIP file
     * @return true if compression succeeded
     */
    public static boolean compressToZip(List<File> files, File destZip) {
        if (files == null || files.isEmpty() || destZip == null) {
            Log.w(TAG, "compressToZip: invalid arguments");
            return false;
        }

        File parent = destZip.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(destZip)))) {
            byte[] buffer = new byte[BUFFER_SIZE];

            for (File file : files) {
                if (!file.exists()) continue;

                ZipEntry entry = new ZipEntry(file.getName());
                zos.putNextEntry(entry);

                try (FileInputStream fis = new FileInputStream(file);
                     BufferedInputStream bis = new BufferedInputStream(fis)) {
                    int bytesRead;
                    while ((bytesRead = bis.read(buffer)) != -1) {
                        zos.write(buffer, 0, bytesRead);
                    }
                }

                zos.closeEntry();
            }

            return true;
        } catch (IOException e) {
            Log.e(TAG, "compressToZip failed", e);
            return false;
        }
    }

    /**
     * Compress a directory recursively into a ZIP archive.
     *
     * @param dir      directory to compress
     * @param destZip  destination ZIP file
     * @return true if compression succeeded
     */
    public static boolean compressDirToZip(File dir, File destZip) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            Log.w(TAG, "compressDirToZip: invalid directory: " + dir);
            return false;
        }

        File parent = destZip.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(destZip)))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            String basePath = dir.getAbsolutePath();

            List<File> allFiles = listFilesRecursive(dir);
            for (File file : allFiles) {
                String entryName = file.getAbsolutePath()
                        .substring(basePath.length() + 1)
                        .replace("\\", "/");

                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);

                if (file.isFile()) {
                    try (FileInputStream fis = new FileInputStream(file);
                         BufferedInputStream bis = new BufferedInputStream(fis)) {
                        int bytesRead;
                        while ((bytesRead = bis.read(buffer)) != -1) {
                            zos.write(buffer, 0, bytesRead);
                        }
                    }
                }

                zos.closeEntry();
            }

            return true;
        } catch (IOException e) {
            Log.e(TAG, "compressDirToZip failed", e);
            return false;
        }
    }

    // ──────────────── ZIP Extract ────────────────

    /**
     * Extract a ZIP archive to a destination directory.
     *
     * @param zipFile  source ZIP file
     * @param destDir  destination directory (created if needed)
     * @return true if extraction succeeded
     */
    public static boolean extractZip(File zipFile, File destDir) {
        if (zipFile == null || !zipFile.exists() || destDir == null) {
            Log.w(TAG, "extractZip: invalid arguments");
            return false;
        }

        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile)))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());

                // Security: prevent zip slip
                String canonicalDest = destDir.getCanonicalPath();
                String canonicalOut = outFile.getCanonicalPath();
                if (!canonicalOut.startsWith(canonicalDest + File.separator)) {
                    Log.w(TAG, "Zip slip attempt: " + entry.getName());
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }

                    try (FileOutputStream fos = new FileOutputStream(outFile);
                         BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            bos.write(buffer, 0, bytesRead);
                        }
                    }
                }
                zis.closeEntry();
            }

            return true;
        } catch (IOException e) {
            Log.e(TAG, "extractZip failed", e);
            return false;
        }
    }

    // ──────────────── File Info ────────────────

    /**
     * Get the file extension (without dot), lowercased.
     *
     * @param file file to inspect
     * @return extension (e.g. "mp4") or empty string
     */
    public static String getExtension(File file) {
        if (file == null || !file.exists()) return "";
        return getExtension(file.getName());
    }

    /**
     * Get the file extension from a filename.
     *
     * @param filename filename string
     * @return extension (e.g. "mp4") or empty string
     */
    public static String getExtension(String filename) {
        if (TextUtils.isEmpty(filename)) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase();
    }

    /**
     * Get the MIME type for a file.
     *
     * @param file file to inspect
     * @return MIME type (e.g. "video/mp4") or "application/octet-stream"
     */
    public static String getMimeType(File file) {
        if (file == null || !file.exists()) return "application/octet-stream";
        return getMimeType(file.getName());
    }

    /**
     * Get the MIME type for a filename.
     *
     * @param filename filename to inspect
     * @return MIME type or "application/octet-stream" as fallback
     */
    public static String getMimeType(String filename) {
        if (TextUtils.isEmpty(filename)) return "application/octet-stream";
        String ext = getExtension(filename);
        if (ext.isEmpty()) return "application/octet-stream";

        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    /**
     * Get the filename without extension.
     *
     * @param filename filename string
     * @return base name without extension
     */
    public static String getBaseName(String filename) {
        if (TextUtils.isEmpty(filename)) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return filename;
        return filename.substring(0, dot);
    }

    // ──────────────── Helpers ────────────────

    private static List<File> listFilesRecursive(File dir) {
        List<File> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                result.add(file);
                if (file.isDirectory()) {
                    result.addAll(listFilesRecursive(file));
                }
            }
        }
        return result;
    }

    /**
     * Ensure a directory exists, creating it if necessary.
     *
     * @param dir directory to ensure
     * @return true if directory exists (or was created)
     */
    public static boolean ensureDir(File dir) {
        if (dir == null) return false;
        if (dir.exists()) return dir.isDirectory();
        return dir.mkdirs();
    }
}

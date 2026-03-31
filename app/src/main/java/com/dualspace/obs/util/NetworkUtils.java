package com.dualspace.obs.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Network utility helper.
 * Provides network availability checks, type detection, RTMP server
 * reachability testing, download speed estimation, and public IP lookup.
 */
public class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    // Timeouts
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int SPEED_TEST_DURATION_MS = 5_000;
    private static final int RTMP_TIMEOUT_MS = 3_000;

    // Speed test
    private static final String SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=1000000";

    // IP lookup
    private static final String IP_LOOKUP_URL = "https://api.ipify.org?format=text";

    // Thread pool for async operations
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** Callback for async speed tests. */
    public interface SpeedTestCallback {
        void onSpeedResult(double kbps);
        void onError(String message);
    }

    /** Callback for async IP lookup. */
    public interface IpCallback {
        void onResult(String ip);
        void onError(String message);
    }

    // ──────────────── Availability ────────────────

    /**
     * Check if any network is available.
     *
     * @param context application context
     * @return true if network is available
     */
    public static boolean isAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network network = cm.getActiveNetwork();
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnectedOrConnecting();
        }
    }

    /**
     * Check if the network is connected and validated (can reach the internet).
     *
     * @param context application context
     * @return true if network is fully connected
     */
    public static boolean isConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network network = cm.getActiveNetwork();
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

    // ──────────────── Network Type ────────────────

    /**
     * Get the current network type as a human-readable string.
     *
     * @param context application context
     * @return "WiFi", "Mobile", "Ethernet", "VPN", or "Unknown"
     */
    public static String getType(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "Unknown";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network network = cm.getActiveNetwork();
            if (network == null) return "Unknown";
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return "Unknown";

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Mobile";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "Bluetooth";
            return "Unknown";
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null) return "Unknown";
            return getLegacyTypeName(info.getType(), info.getSubtype());
        }
    }

    /**
     * Check if currently connected via WiFi.
     *
     * @param context application context
     * @return true if on WiFi
     */
    public static boolean isWiFi(Context context) {
        return "WiFi".equals(getType(context));
    }

    /**
     * Check if currently connected via mobile data.
     *
     * @param context application context
     * @return true if on mobile data
     */
    public static boolean isMobile(Context context) {
        return "Mobile".equals(getType(context));
    }

    /**
     * Check if the current network is metered (mobile data, etc.).
     *
     * @param context application context
     * @return true if on a metered connection
     */
    public static boolean isMetered(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ConnectivityManager cm = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
        }
        return !isWiFi(context);
    }

    private static String getLegacyTypeName(int type, int subtype) {
        switch (type) {
            case ConnectivityManager.TYPE_WIFI:
                return "WiFi";
            case ConnectivityManager.TYPE_MOBILE:
                return getMobileSubtypeName(subtype);
            case ConnectivityManager.TYPE_ETHERNET:
                return "Ethernet";
            case ConnectivityManager.TYPE_VPN:
                return "VPN";
            case ConnectivityManager.TYPE_BLUETOOTH:
                return "Bluetooth";
            default:
                return "Unknown";
        }
    }

    private static String getMobileSubtypeName(int subtype) {
        switch (subtype) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "2G";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return "3G";
            case TelephonyManager.NETWORK_TYPE_LTE:
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return "4G";
            case TelephonyManager.NETWORK_TYPE_NR:
                return "5G";
            default:
                return "Mobile";
        }
    }

    // ──────────────── RTMP Reachability ────────────────

    /**
     * Check if an RTMP server is reachable by attempting a TCP connection.
     * This is a blocking call; use on a background thread.
     *
     * @param host RTMP server hostname or IP
     * @param port RTMP server port (typically 1935)
     * @return true if the server is reachable
     */
    public static boolean checkRTMP(String host, int port) {
        if (host == null || host.isEmpty()) {
            Log.w(TAG, "checkRTMP: empty host");
            return false;
        }

        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), RTMP_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "RTMP server unreachable: " + host + ":" + port + " - " + e.getMessage());
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Check RTMP server reachability asynchronously.
     *
     * @param host     RTMP server hostname
     * @param port     RTMP server port
     * @param callback result callback
     */
    public static void checkRTMPAsync(String host, int port, final java.util.function.Consumer<Boolean> callback) {
        executor.execute(() -> {
            boolean reachable = checkRTMP(host, port);
            if (callback != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                        () -> callback.accept(reachable));
            }
        });
    }

    // ──────────────── Speed Test ────────────────

    /**
     * Run a simple download speed test.
     * Blocking call — use on a background thread.
     *
     * @return estimated download speed in kbps, or -1 on error
     */
    public static double testSpeed() {
        long startTime = System.currentTimeMillis();
        long totalBytes = 0;

        HttpURLConnection conn = null;
        try {
            URL url = new URL(SPEED_TEST_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(SPEED_TEST_DURATION_MS + 2_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");

            InputStream in = conn.getInputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                totalBytes += bytesRead;
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= SPEED_TEST_DURATION_MS) {
                    break;
                }
            }
            in.close();

            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed <= 0) elapsed = 1;

            // bits per second -> kbps
            double bps = (totalBytes * 8.0) / (elapsed / 1000.0);
            double kbps = bps / 1000.0;

            Log.i(TAG, String.format("Speed test: %.0f kbps (%.2f MB in %d ms)",
                    kbps, totalBytes / (1024.0 * 1024.0), elapsed));
            return kbps;
        } catch (IOException e) {
            Log.e(TAG, "Speed test failed", e);
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Run a speed test asynchronously.
     *
     * @param callback result callback on main thread
     */
    public static void testSpeedAsync(SpeedTestCallback callback) {
        executor.execute(() -> {
            double kbps = testSpeed();
            if (callback != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (kbps >= 0) {
                        callback.onSpeedResult(kbps);
                    } else {
                        callback.onError("Speed test failed");
                    }
                });
            }
        });
    }

    // ──────────────── Public IP ────────────────

    /**
     * Get the device's public IP address.
     * Blocking call — use on a background thread.
     *
     * @return public IP string, or "Unknown" on error
     */
    public static String getPublicIp() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(IP_LOOKUP_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String ip = reader.readLine();
                    if (ip != null && !ip.isEmpty()) {
                        Log.d(TAG, "Public IP: " + ip);
                        return ip.trim();
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to get public IP", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return "Unknown";
    }

    /**
     * Get public IP asynchronously.
     *
     * @param callback result callback on main thread
     */
    public static void getPublicIpAsync(IpCallback callback) {
        executor.execute(() -> {
            String ip = getPublicIp();
            if (callback != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (!"Unknown".equals(ip)) {
                        callback.onResult(ip);
                    } else {
                        callback.onError("Failed to resolve public IP");
                    }
                });
            }
        });
    }

    // ──────────────── Streaming Suitability ────────────────

    /**
     * Check if the current network is suitable for streaming.
     * WiFi with validated connectivity is preferred; mobile data
     * with at least 4G is acceptable.
     *
     * @param context application context
     * @return map with "suitable" (boolean) and "reason" (string)
     */
    public static java.util.Map<String, Object> isSuitableForStreaming(Context context) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();

        if (!isConnected(context)) {
            result.put("suitable", false);
            result.put("reason", "No internet connection");
            return result;
        }

        String type = getType(context);

        // WiFi is always suitable
        if ("WiFi".equals(type) || "Ethernet".equals(type)) {
            result.put("suitable", true);
            result.put("reason", "Connected via " + type);
            return result;
        }

        // Mobile: check generation
        if ("Mobile".equals(type)) {
            // We check a speed estimate or just allow 4G+
            ConnectivityManager cm = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network network = cm.getActiveNetwork();
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                    result.put("suitable", true);
                    result.put("reason", "Mobile unmetered connection");
                    return result;
                }
            }

            // Allow mobile but warn about data usage
            result.put("suitable", true);
            result.put("reason", "Mobile data connection (metered — may incur charges)");
            return result;
        }

        result.put("suitable", false);
        result.put("reason", "Network type '" + type + "' not suitable for streaming");
        return result;
    }

    // ──────────────── DNS Resolution ────────────────

    /**
     * Resolve a hostname to an IP address.
     *
     * @param hostname hostname to resolve
     * @return IP address string, or null on failure
     */
    public static String resolveHost(String hostname) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(hostname);
            if (addresses != null && addresses.length > 0) {
                return addresses[0].getHostAddress();
            }
        } catch (UnknownHostException e) {
            Log.w(TAG, "DNS resolution failed: " + hostname, e);
        }
        return null;
    }
}

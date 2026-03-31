package com.dualspace.obs.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Network connectivity change receiver.
 * Detects WiFi/mobile connectivity changes, auto-reconnects streaming
 * on network restoration, and notifies the OBS engine.
 *
 * On Android N+ (API 24+), uses ConnectivityManager.NetworkCallback.
 * On older devices, falls back to the legacy BroadcastReceiver approach.
 */
public class NetworkReceiver extends BroadcastReceiver {

    private static final String TAG = "NetworkReceiver";

    /** Callback interface for network events. */
    public interface OnNetworkChangeListener {
        void onNetworkConnected(String type);
        void onNetworkDisconnected();
        void onNetworkTypeChanged(String type);
    }

    private OnNetworkChangeListener listener;
    private final AtomicBoolean wasConnected = new AtomicBoolean(false);

    // ConnectivityManager callback for API 24+
    private ConnectivityManager.NetworkCallback networkCallback;
    private ConnectivityManager connectivityManager;

    public void setOnNetworkChangeListener(OnNetworkChangeListener listener) {
        this.listener = listener;
    }

    // ──────────────── Legacy Receiver (API < 24) ────────────────

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (!ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) return;

        Log.d(TAG, "Connectivity change received (legacy)");

        boolean noConnectivity = intent.getBooleanExtra(
                ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
        if (noConnectivity) {
            handleConnectivityChange(context, false, null);
            return;
        }

        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork != null && activeNetwork.isConnected()) {
            String type = getNetworkTypeName(activeNetwork.getType());
            handleConnectivityChange(context, true, type);
        } else {
            handleConnectivityChange(context, false, null);
        }
    }

    // ──────────────── Modern Callback (API 24+) ────────────────

    /**
     * Register a NetworkCallback for devices running API 24+.
     * Call from an Activity or Service lifecycle method.
     *
     * @param context application or activity context
     */
    public void registerCallback(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.d(TAG, "NetworkCallback not supported on API < 24, using legacy receiver");
            return;
        }

        connectivityManager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Network available (callback)");
                String type = getCurrentNetworkType(context);
                handleConnectivityChange(context, true, type);
            }

            @Override
            public void onLost(Network network) {
                Log.w(TAG, "Network lost (callback)");
                handleConnectivityChange(context, false, null);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                String type = getCurrentNetworkType(context);
                Log.d(TAG, "Network capabilities changed: " + type);
                if (listener != null) {
                    listener.onNetworkTypeChanged(type);
                }
            }
        };

        connectivityManager.registerNetworkCallback(request, networkCallback);
        Log.i(TAG, "NetworkCallback registered");
    }

    /**
     * Unregister the NetworkCallback. Call when done.
     */
    public void unregisterCallback() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister NetworkCallback", e);
            }
            networkCallback = null;
            connectivityManager = null;
            Log.i(TAG, "NetworkCallback unregistered");
        }
    }

    // ──────────────── Connectivity Handling ────────────────

    /**
     * Handle a connectivity change event.
     *
     * @param context    application context
     * @param connected  true if network is available
     * @param networkType human-readable type (WiFi / Mobile / Unknown)
     */
    public void handleConnectivityChange(Context context, boolean connected, String networkType) {
        boolean prev = wasConnected.getAndSet(connected);

        if (connected) {
            if (!prev) {
                // Transition: disconnected → connected
                Log.i(TAG, "Network restored: " + networkType);
                if (listener != null) {
                    listener.onNetworkConnected(networkType != null ? networkType : "Unknown");
                }
                onNetworkRestored(context);
            } else {
                // Still connected, type may have changed
                Log.d(TAG, "Network type changed: " + networkType);
                if (listener != null) {
                    listener.onNetworkTypeChanged(networkType != null ? networkType : "Unknown");
                }
            }
        } else {
            if (prev) {
                // Transition: connected → disconnected
                Log.w(TAG, "Network lost");
                if (listener != null) {
                    listener.onNetworkDisconnected();
                }
                onNetworkLost(context);
            }
        }
    }

    /**
     * Called when network connectivity is restored.
     * Attempts to auto-reconnect any active streaming session.
     *
     * @param context application context
     */
    private void onNetworkRestored(Context context) {
        // Send a local broadcast or intent to notify the streaming engine
        Intent reconnectIntent = new Intent("com.dualspace.obs.ACTION_NETWORK_RESTORED");
        reconnectIntent.putExtra("network_type", getCurrentNetworkType(context));
        context.sendBroadcast(reconnectIntent);

        Log.i(TAG, "Network restore notification sent");
    }

    /**
     * Called when network connectivity is lost.
     * Notifies the streaming engine to pause or stop.
     *
     * @param context application context
     */
    private void onNetworkLost(Context context) {
        Intent lostIntent = new Intent("com.dualspace.obs.ACTION_NETWORK_LOST");
        context.sendBroadcast(lostIntent);

        Log.w(TAG, "Network loss notification sent");
    }

    // ──────────────── Helpers ────────────────

    /**
     * Get the current network type as a human-readable string.
     *
     * @param context application context
     * @return "WiFi", "Mobile", "Ethernet", "VPN", or "Unknown"
     */
    public static String getCurrentNetworkType(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "Unknown";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) return "Unknown";
            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            if (caps == null) return "Unknown";

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Mobile";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
            return "Unknown";
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null) return "Unknown";
            return getNetworkTypeName(info.getType());
        }
    }

    private static String getNetworkTypeName(int type) {
        switch (type) {
            case ConnectivityManager.TYPE_WIFI:
                return "WiFi";
            case ConnectivityManager.TYPE_MOBILE:
            case ConnectivityManager.TYPE_MOBILE_DUN:
            case ConnectivityManager.TYPE_MOBILE_HIPRI:
            case ConnectivityManager.TYPE_MOBILE_MMS:
            case ConnectivityManager.TYPE_MOBILE_SUPL:
                return "Mobile";
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

    /**
     * Check if the device currently has network connectivity.
     *
     * @param context application context
     * @return true if connected
     */
    public static boolean isConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }
}

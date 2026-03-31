package com.dualspace.obs.obs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;

/**
 * Foreground service for screen capture via MediaProjection.
 * Manages VirtualDisplay lifecycle and delivers frames to a target Surface.
 *
 * Actions:
 *   ACTION_START_CAPTURE  - Intent extra: RESULT_CODE, RESULT_DATA (Intent)
 *   ACTION_STOP_CAPTURE
 *   ACTION_SET_REGION     - Intent extra: REGION_LEFT, REGION_TOP, REGION_RIGHT, REGION_BOTTOM
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 1001;

    // ── Intent Actions ──────────────────────────────────────────────────────
    public static final String ACTION_START_CAPTURE = "com.dualspace.obs.action.START_CAPTURE";
    public static final String ACTION_STOP_CAPTURE = "com.dualspace.obs.action.STOP_CAPTURE";
    public static final String ACTION_SET_REGION = "com.dualspace.obs.action.SET_REGION";

    // ── Intent Extras ───────────────────────────────────────────────────────
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_REGION_LEFT = "region_left";
    public static final String EXTRA_REGION_TOP = "region_top";
    public static final String EXTRA_REGION_RIGHT = "region_right";
    public static final String EXTRA_REGION_BOTTOM = "region_bottom";

    // ── Frame Listener ──────────────────────────────────────────────────────
    public interface OnFrameListener {
        void onFrame(Image image);
        void onCaptureError(String message);
    }

    // ── Fields ──────────────────────────────────────────────────────────────
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private Surface mTargetSurface;          // Output surface (encoder input, etc.)
    private ImageReader mImageReader;
    private HandlerThread mFrameThread;
    private Handler mFrameHandler;

    private int mScreenWidth;
    private int mScreenHeight;
    private int mScreenDensity;
    private Rect mCaptureRegion;
    private boolean mIsCapturing = false;
    private OnFrameListener mFrameListener;

    private final IBinder mBinder = new LocalBinder();

    // ── Binder ──────────────────────────────────────────────────────────────
    public class LocalBinder extends Binder {
        public ScreenCaptureService getService() {
            return ScreenCaptureService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);

        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;
        mScreenDensity = metrics.densityDpi;

        mFrameThread = new HandlerThread("ScreenCapture-Frame");
        mFrameThread.start();
        mFrameHandler = new Handler(mFrameThread.getLooper());

        Log.i(TAG, "Service created. Screen: " + mScreenWidth + "x" + mScreenHeight);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        switch (intent.getAction()) {
            case ACTION_START_CAPTURE:
                int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
                Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                if (resultData != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("Screen capture active"));
                    startCapture(resultCode, resultData);
                }
                break;

            case ACTION_STOP_CAPTURE:
                stopCapture();
                stopForeground(true);
                stopSelf();
                break;

            case ACTION_SET_REGION:
                int left = intent.getIntExtra(EXTRA_REGION_LEFT, 0);
                int top = intent.getIntExtra(EXTRA_REGION_TOP, 0);
                int right = intent.getIntExtra(EXTRA_REGION_RIGHT, mScreenWidth);
                int bottom = intent.getIntExtra(EXTRA_REGION_BOTTOM, mScreenHeight);
                setCaptureRegion(new Rect(left, top, right, bottom));
                break;
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        if (mFrameThread != null) {
            mFrameThread.quitSafely();
        }
        Log.i(TAG, "Service destroyed");
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Start screen capture using MediaProjection result.
     */
    public void startCapture(int resultCode, Intent data) {
        if (mIsCapturing) {
            Log.w(TAG, "Already capturing");
            return;
        }

        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mMediaProjection = projectionManager.getMediaProjection(resultCode, data);

        if (mMediaProjection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection");
            if (mFrameListener != null) {
                mFrameListener.onCaptureError("MediaProjection denied or unavailable");
            }
            return;
        }

        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "MediaProjection stopped");
                cleanupVirtualDisplay();
                mIsCapturing = false;
                if (mFrameListener != null) {
                    mFrameListener.onCaptureError("MediaProjection stopped by system");
                }
            }
        }, mFrameHandler);

        createVirtualDisplay();
        mIsCapturing = true;
        Log.i(TAG, "Screen capture started");
    }

    /**
     * Stop screen capture.
     */
    public void stopCapture() {
        if (!mIsCapturing) return;
        mIsCapturing = false;
        cleanupVirtualDisplay();

        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        Log.i(TAG, "Screen capture stopped");
    }

    /**
     * Set the capture region (null for full screen).
     */
    public void setCaptureRegion(Rect region) {
        if (region == null) {
            mCaptureRegion = null;
        } else {
            mCaptureRegion = new Rect(
                    Math.max(0, region.left),
                    Math.max(0, region.top),
                    Math.min(mScreenWidth, region.right),
                    Math.min(mScreenHeight, region.bottom)
            );
        }
        Log.i(TAG, "Capture region: " + mCaptureRegion);

        // Recreate VirtualDisplay with new region
        if (mIsCapturing) {
            cleanupVirtualDisplay();
            createVirtualDisplay();
        }
    }

    /**
     * Set the target Surface for rendering captured frames.
     * Typically the video encoder's input surface.
     */
    public void setTargetSurface(Surface surface) {
        mTargetSurface = surface;
        if (mIsCapturing) {
            cleanupVirtualDisplay();
            createVirtualDisplay();
        }
    }

    /**
     * Check if currently capturing.
     */
    public boolean isCapturing() {
        return mIsCapturing;
    }

    /**
     * Set the frame listener for per-frame Image delivery.
     */
    public void setOnFrameListener(OnFrameListener listener) {
        mFrameListener = listener;
    }

    /**
     * Handle screen rotation changes.
     */
    public void onScreenRotation(int newWidth, int newHeight) {
        if (mScreenWidth != newWidth || mScreenHeight != newHeight) {
            Log.i(TAG, "Screen rotation detected: " + mScreenWidth + "x" + mScreenHeight
                    + " -> " + newWidth + "x" + newHeight);
            mScreenWidth = newWidth;
            mScreenHeight = newHeight;

            if (mIsCapturing) {
                cleanupVirtualDisplay();
                createVirtualDisplay();
            }
        }
    }

    public int getScreenWidth() { return mScreenWidth; }
    public int getScreenHeight() { return mScreenHeight; }
    public Rect getCaptureRegion() { return mCaptureRegion; }

    // ── Internal ────────────────────────────────────────────────────────────

    private void createVirtualDisplay() {
        if (mMediaProjection == null) return;

        int displayWidth = mScreenWidth;
        int displayHeight = mScreenHeight;

        if (mCaptureRegion != null) {
            displayWidth = mCaptureRegion.width();
            displayHeight = mCaptureRegion.height();
        }

        // Use ImageReader for per-frame processing if a listener is set,
        // otherwise render directly to target surface.
        Surface surface;
        if (mFrameListener != null && mTargetSurface == null) {
            // Create ImageReader for frame processing
            if (mImageReader != null) {
                mImageReader.close();
            }
            mImageReader = ImageReader.newInstance(
                    displayWidth, displayHeight,
                    PixelFormat.RGBA_8888, 2
            );
            mImageReader.setOnImageAvailableListener(
                    reader -> {
                        Image image = null;
                        try {
                            image = reader.acquireLatestImage();
                            if (image != null) {
                                mFrameListener.onFrame(image);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error acquiring image", e);
                        } finally {
                            if (image != null) {
                                image.close();
                            }
                        }
                    },
                    mFrameHandler
            );
            surface = mImageReader.getSurface();
        } else {
            surface = mTargetSurface;
        }

        if (surface == null) {
            Log.w(TAG, "No target surface or image reader configured");
            return;
        }

        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        }

        try {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                    "DualSpaceScreenCapture",
                    displayWidth,
                    displayHeight,
                    mScreenDensity,
                    flags,
                    surface,
                    null,   // callbacks
                    mFrameHandler
            );
            Log.i(TAG, "VirtualDisplay created: " + displayWidth + "x" + displayHeight);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create VirtualDisplay", e);
            mIsCapturing = false;
        }
    }

    private void cleanupVirtualDisplay() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
            mImageReader.setOnImageAvailableListener(null, null);
            mImageReader.close();
            mImageReader = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Capture",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Screen capture service is running");
            channel.setShowBadge(false);

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("DualSpace OBS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true);

        // Add stop action
        Intent stopIntent = new Intent(this, ScreenCaptureService.class);
        stopIntent.setAction(ACTION_STOP_CAPTURE);
        PendingIntent stopPendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder.setPriority(Notification.PRIORITY_LOW);
        } else {
            return builder.getNotification();
        }

        return builder.build();
    }
}

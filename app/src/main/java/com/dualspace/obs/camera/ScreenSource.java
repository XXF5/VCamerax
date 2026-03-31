package com.dualspace.obs.camera;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Screen capture source for the virtual camera.
 * Uses MediaProjection to capture the screen and delivers frames
 * for virtual camera rendering. Supports region selection and FPS control.
 */
public class ScreenSource {

    private static final String TAG = "ScreenSource";
    private static final int DEFAULT_DENSITY = 160;

    // ── Frame Listener ──────────────────────────────────────────────────────
    public interface OnFrameReadyListener {
        /**
         * Called when a new screen frame is available.
         * @param frameBuffer NV21 frame data
         * @param width  Frame width
         * @param height Frame height
         */
        void onFrameReady(byte[] frameBuffer, int width, int height);
        void onError(String message);
        void onCaptureStarted();
        void onCaptureStopped();
    }

    // ── Fields ──────────────────────────────────────────────────────────────
    private Context mAppContext;
    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;

    private final AtomicBoolean mCapturing = new AtomicBoolean(false);
    private final AtomicBoolean mPaused = new AtomicBoolean(false);

    private int mDisplayId = 0;
    private int mScreenWidth;
    private int mScreenHeight;
    private int mScreenDensity;
    private int mCaptureWidth;
    private int mCaptureHeight;
    private int mTargetFps = 30;

    private Rect mCaptureRegion;     // null = full screen

    private HandlerThread mSourceThread;
    private Handler mSourceHandler;

    private OnFrameReadyListener mFrameListener;

    // FPS control
    private long mLastFrameTimeMs = 0;
    private long mMinFrameIntervalMs = 33; // ~30fps default

    // Rotation tracking
    private int mDisplayRotation = 0;

    // ── Constructor ─────────────────────────────────────────────────────────

    public ScreenSource(Context context) {
        mAppContext = context.getApplicationContext();
        mProjectionManager = (MediaProjectionManager)
                mAppContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // Get screen dimensions
        WindowManager wm = (WindowManager) mAppContext.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            android.view.Display display = wm.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            mScreenWidth = metrics.widthPixels;
            mScreenHeight = metrics.heightPixels;
            mScreenDensity = metrics.densityDpi;
            mDisplayRotation = display.getRotation();
        } else {
            mScreenWidth = 1080;
            mScreenHeight = 1920;
            mScreenDensity = DEFAULT_DENSITY;
        }

        mCaptureWidth = mScreenWidth;
        mCaptureHeight = mScreenHeight;

        mSourceThread = new HandlerThread("ScreenSource-Thread");
        mSourceThread.start();
        mSourceHandler = new Handler(mSourceThread.getLooper());

        Log.i(TAG, "ScreenSource initialized: " + mScreenWidth + "x" + mScreenHeight);
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Start screen capture.
     * @param resultCode The result code from MediaProjection permission request
     * @param data       The Intent data from the permission result
     */
    public void startCapture(int resultCode, Intent data) {
        if (mCapturing.getAndSet(true)) {
            Log.w(TAG, "Already capturing");
            return;
        }

        mSourceHandler.post(() -> {
            try {
                if (data == null) {
                    mCapturing.set(false);
                    notifyError("Permission data is null. Did you request MediaProjection permission?");
                    return;
                }

                mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);
                if (mMediaProjection == null) {
                    mCapturing.set(false);
                    notifyError("Failed to obtain MediaProjection");
                    return;
                }

                mMediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        Log.i(TAG, "MediaProjection stopped by system");
                        cleanupCapture();
                        mCapturing.set(false);
                        if (mFrameListener != null) {
                            mFrameListener.onCaptureStopped();
                        }
                    }
                }, mSourceHandler);

                createVirtualDisplay();
                notifyCaptureStarted();
                Log.i(TAG, "Screen capture started: " + mCaptureWidth + "x" + mCaptureHeight);

            } catch (Exception e) {
                mCapturing.set(false);
                Log.e(TAG, "Failed to start screen capture", e);
                notifyError("Failed to start capture: " + e.getMessage());
            }
        });
    }

    /**
     * Start screen capture with display ID (requires system-level permissions).
     * @param displayId The display to capture (0 = default)
     */
    public void startCapture(int displayId) {
        mDisplayId = displayId;
        // For non-default displays, additional permissions may be needed.
        // This method is primarily for API compatibility.
        Log.w(TAG, "startCapture(displayId) requires additional system permissions");
        notifyError("Display-specific capture requires system permissions");
    }

    /**
     * Stop screen capture.
     */
    public void stopCapture() {
        if (!mCapturing.getAndSet(false)) return;

        mSourceHandler.post(() -> {
            cleanupCapture();

            if (mFrameListener != null) {
                mFrameListener.onCaptureStopped();
            }
            Log.i(TAG, "Screen capture stopped");
        });
    }

    /**
     * Set the capture region. Null for full screen.
     * @param region Capture region in screen coordinates
     */
    public void setRegion(Rect region) {
        mSourceHandler.post(() -> {
            if (region != null) {
                mCaptureRegion = new Rect(
                        Math.max(0, Math.min(region.left, mScreenWidth)),
                        Math.max(0, Math.min(region.top, mScreenHeight)),
                        Math.max(0, Math.min(region.right, mScreenWidth)),
                        Math.max(0, Math.min(region.bottom, mScreenHeight))
                );
                mCaptureWidth = mCaptureRegion.width();
                mCaptureHeight = mCaptureRegion.height();
            } else {
                mCaptureRegion = null;
                mCaptureWidth = mScreenWidth;
                mCaptureHeight = mScreenHeight;
            }

            Log.i(TAG, "Capture region: " + (mCaptureRegion != null
                    ? mCaptureRegion.toShortString()
                    : "full screen " + mCaptureWidth + "x" + mCaptureHeight));

            // Recreate VirtualDisplay if currently capturing
            if (mCapturing.get()) {
                cleanupCapture();
                createVirtualDisplay();
            }
        });
    }

    /**
     * Set the target FPS for frame delivery.
     * @param fps Frames per second (1-60)
     */
    public void setFps(int fps) {
        mTargetFps = Math.max(1, Math.min(60, fps));
        mMinFrameIntervalMs = 1000 / mTargetFps;
        Log.d(TAG, "FPS set to " + mTargetFps);
    }

    /**
     * Get the current frame (capture the screen now).
     * Note: This is a one-shot capture; use listeners for continuous capture.
     * @return NV21 frame data or null
     */
    public byte[] getFrame() {
        if (!mCapturing.get() || mImageReader == null) return null;

        try {
            Image image = mImageReader.acquireLatestImage();
            if (image == null) return null;

            byte[] nv21 = imageToNv21(image);
            image.close();
            return nv21;
        } catch (Exception e) {
            Log.e(TAG, "Error getting frame", e);
            return null;
        }
    }

    /**
     * Check if currently capturing.
     */
    public boolean isCapturing() {
        return mCapturing.get();
    }

    /**
     * Get screen dimensions.
     */
    public int getScreenWidth() { return mScreenWidth; }
    public int getScreenHeight() { return mScreenHeight; }
    public int getCaptureWidth() { return mCaptureWidth; }
    public int getCaptureHeight() { return mCaptureHeight; }
    public Rect getCaptureRegion() { return mCaptureRegion; }

    /**
     * Set the frame ready listener.
     */
    public void setOnFrameReadyListener(OnFrameReadyListener listener) {
        mFrameListener = listener;
    }

    /**
     * Set the target output resolution (frames will be scaled).
     */
    public void setOutputResolution(int width, int height) {
        mCaptureWidth = Math.max(1, width);
        mCaptureHeight = Math.max(1, height);
    }

    /**
     * Release all resources.
     */
    public void release() {
        stopCapture();

        if (mSourceThread != null) {
            mSourceThread.quitSafely();
            mSourceThread = null;
            mSourceHandler = null;
        }
    }

    // ── Internal: VirtualDisplay ────────────────────────────────────────────

    private void createVirtualDisplay() {
        if (mMediaProjection == null) return;

        // Release previous resources
        if (mImageReader != null) {
            mImageReader.setOnImageAvailableListener(null, null);
            mImageReader.close();
            mImageReader = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        int displayWidth = mCaptureWidth;
        int displayHeight = mCaptureHeight;

        // Create ImageReader for frame access
        mImageReader = ImageReader.newInstance(
                displayWidth, displayHeight,
                PixelFormat.RGBA_8888, 3
        );

        mImageReader.setOnImageAvailableListener(reader -> {
            if (!mCapturing.get() || mPaused.get()) return;

            // FPS control
            long now = System.currentTimeMillis();
            if (now - mLastFrameTimeMs < mMinFrameIntervalMs) return;
            mLastFrameTimeMs = now;

            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;

                byte[] nv21 = imageToNv21(image);
                if (nv21 != null && mFrameListener != null) {
                    mFrameListener.onFrameReady(nv21, displayWidth, displayHeight);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing frame", e);
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, mSourceHandler);

        // Create VirtualDisplay
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
        }

        try {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                    "DualSpace-ScreenSource",
                    displayWidth,
                    displayHeight,
                    mScreenDensity,
                    flags,
                    mImageReader.getSurface(),
                    null,       // VirtualDisplay.Callback
                    mSourceHandler
            );
            Log.d(TAG, "VirtualDisplay created: " + displayWidth + "x" + displayHeight);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create VirtualDisplay", e);
            notifyError("Failed to create virtual display: " + e.getMessage());
            mCapturing.set(false);
        }
    }

    private void cleanupCapture() {
        if (mImageReader != null) {
            mImageReader.setOnImageAvailableListener(null, null);
            mImageReader.close();
            mImageReader = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }

    // ── Internal: Frame Conversion ──────────────────────────────────────────

    /**
     * Convert an RGBA_8888 Image to NV21 byte array.
     */
    private byte[] imageToNv21(Image image) {
        if (image.getPlanes().length == 0) return null;

        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();

        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        int width = image.getWidth();
        int height = image.getHeight();

        // Create bitmap from Image
        Bitmap bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
        );
        bitmap.copyPixelsFromBuffer(buffer);

        // Crop if there's row padding
        if (rowPadding > 0) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            bitmap.recycle();
            bitmap = cropped;
        }

        // Apply region crop if set
        if (mCaptureRegion != null) {
            int left = mCaptureRegion.left;
            int top = mCaptureRegion.top;
            int regionW = mCaptureRegion.width();
            int regionH = mCaptureRegion.height();

            if (left + regionW <= bitmap.getWidth() && top + regionH <= bitmap.getHeight()) {
                Bitmap cropped = Bitmap.createBitmap(bitmap, left, top, regionW, regionH);
                bitmap.recycle();
                bitmap = cropped;
            }
        }

        // Convert to NV21
        int[] argb = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(argb, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        int frameW = bitmap.getWidth();
        int frameH = bitmap.getHeight();
        byte[] nv21 = new byte[frameW * frameH * 3 / 2];
        int frameSize = frameW * frameH;
        int yIndex = 0;
        int uvIndex = frameSize;
        int idx = 0;

        for (int j = 0; j < frameH; j++) {
            for (int i = 0; i < frameW; i++) {
                int pixel = argb[idx++];
                int R = (pixel >> 16) & 0xFF;
                int G = (pixel >> 8) & 0xFF;
                int B = pixel & 0xFF;

                // RGB to YUV (BT.601)
                int Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                int U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                int V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                Y = clamp(Y, 0, 255);
                U = clamp(U, 0, 255);
                V = clamp(V, 0, 255);

                nv21[yIndex++] = (byte) Y;

                // NV21: VU interleaved at half resolution
                if (j % 2 == 0 && idx % 2 == 0) {
                    nv21[uvIndex++] = (byte) V;
                    nv21[uvIndex++] = (byte) U;
                }
            }
        }

        bitmap.recycle();
        return nv21;
    }

    /**
     * Check and handle display rotation changes.
     */
    public void onDisplayRotationChanged(int newRotation) {
        if (newRotation == mDisplayRotation) return;

        int prevRotation = mDisplayRotation;
        mDisplayRotation = newRotation;

        // Swap dimensions for 90/270 degree rotations
        if (newRotation == Surface.ROTATION_90 || newRotation == Surface.ROTATION_270) {
            int tmp = mScreenWidth;
            mScreenWidth = mScreenHeight;
            mScreenHeight = tmp;
        }

        if (mCaptureRegion == null) {
            mCaptureWidth = mScreenWidth;
            mCaptureHeight = mScreenHeight;
        }

        Log.i(TAG, "Display rotation changed: " + prevRotation + " -> " + newRotation
                + ", screen: " + mScreenWidth + "x" + mScreenHeight);

        // Recreate VirtualDisplay if capturing
        if (mCapturing.get()) {
            mSourceHandler.post(() -> {
                cleanupCapture();
                createVirtualDisplay();
            });
        }
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private void notifyCaptureStarted() {
        if (mFrameListener != null) mFrameListener.onCaptureStarted();
    }

    private void notifyError(String message) {
        if (mFrameListener != null) mFrameListener.onError(message);
    }
}

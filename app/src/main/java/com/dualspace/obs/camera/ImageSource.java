package com.dualspace.obs.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Movie;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Image camera source supporting static images and GIF slideshows.
 * Loads images from gallery/file paths, scales to camera resolution,
 * and delivers frames via callback at configurable intervals.
 */
public class ImageSource {

    private static final String TAG = "ImageSource";

    // ── Frame Listener ──────────────────────────────────────────────────────
    public interface OnFrameReadyListener {
        /**
         * Called when a new frame is ready.
         * @param frameBuffer NV21 frame data
         * @param width  Frame width
         * @param height Frame height
         */
        void onFrameReady(byte[] frameBuffer, int width, int height);
        void onError(String message);
    }

    // ── Fields ──────────────────────────────────────────────────────────────
    private final List<Bitmap> mBitmaps = new ArrayList<>();
    private final AtomicReference<Bitmap> mCurrentFrame = new AtomicReference<>();
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final AtomicBoolean mPaused = new AtomicBoolean(false);
    private final AtomicLong mSlideshowIntervalMs = new AtomicLong(5000); // 5 seconds default
    private final AtomicInteger mCurrentIndex = new AtomicInteger(0);

    private int mTargetWidth = 640;
    private int mTargetHeight = 480;

    private HandlerThread mSourceThread;
    private Handler mSourceHandler;

    private OnFrameReadyListener mFrameListener;

    // GIF support
    private Movie mGifMovie;
    private volatile boolean mIsGif = false;
    private final AtomicLong mGifStartTime = new AtomicLong(0);
    private File mGifFile;

    // Frame conversion buffer
    private byte[] mFrameBuffer;
    private int[] mArgbPixels;

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Load images from file paths. Images are decoded and scaled to target resolution.
     * @param paths List of absolute file paths to images (PNG, JPG, WebP)
     */
    public void loadImages(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            Log.w(TAG, "No paths provided");
            return;
        }

        mSourceHandler.post(() -> {
            mBitmaps.clear();
            mIsGif = false;

            for (String path : paths) {
                if (path == null) continue;

                // Check if it's a GIF
                if (path.toLowerCase().endsWith(".gif")) {
                    loadGif(path);
                    return; // GIF mode - only one source
                }

                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, opts);

                    // Calculate inSampleSize for efficient decoding
                    opts.inSampleSize = calculateInSampleSize(opts, mTargetWidth, mTargetHeight);
                    opts.inJustDecodeBounds = false;

                    Bitmap raw = BitmapFactory.decodeFile(path, opts);
                    if (raw != null) {
                        Bitmap scaled = scaleBitmap(raw, mTargetWidth, mTargetHeight);
                        if (scaled != raw && raw != scaled) {
                            raw.recycle();
                        }
                        mBitmaps.add(scaled);
                        Log.d(TAG, "Loaded image: " + path + " -> " + scaled.getWidth() + "x" + scaled.getHeight());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load image: " + path, e);
                }
            }

            mCurrentIndex.set(0);
            if (!mBitmaps.isEmpty()) {
                mCurrentFrame.set(mBitmaps.get(0));
            }
            Log.i(TAG, "Loaded " + mBitmaps.size() + " images");
        });
    }

    /**
     * Start delivering frames.
     */
    public void start() {
        if (mRunning.getAndSet(true)) {
            Log.w(TAG, "Already running");
            return;
        }

        if (mIsGif) {
            startGifPlayback();
        } else {
            startSlideshow();
        }

        Log.i(TAG, "Image source started");
    }

    /**
     * Stop delivering frames.
     */
    public void stop() {
        if (!mRunning.getAndSet(false)) return;
        mPaused.set(false);

        if (mSourceHandler != null) {
            mSourceHandler.removeCallbacksAndMessages(null);
        }

        Log.i(TAG, "Image source stopped");
    }

    /**
     * Set the slideshow interval in milliseconds.
     * @param intervalMs Time between image transitions (min 100ms)
     */
    public void setSlideshowInterval(long intervalMs) {
        mSlideshowIntervalMs.set(Math.max(100, intervalMs));
    }

    /**
     * Get the current frame as a Bitmap (may be null).
     */
    public Bitmap getCurrentFrame() {
        return mCurrentFrame.get();
    }

    /**
     * Get the next frame (advances slideshow index).
     */
    public Bitmap getNextFrame() {
        if (mBitmaps.isEmpty()) return null;

        int next = (mCurrentIndex.get() + 1) % mBitmaps.size();
        mCurrentIndex.set(next);
        mCurrentFrame.set(mBitmaps.get(next));
        return mCurrentFrame.get();
    }

    /**
     * Get the previous frame.
     */
    public Bitmap getPreviousFrame() {
        if (mBitmaps.isEmpty()) return null;

        int prev = mCurrentIndex.get() - 1;
        if (prev < 0) prev = mBitmaps.size() - 1;
        mCurrentIndex.set(prev);
        mCurrentFrame.set(mBitmaps.get(prev));
        return mCurrentFrame.get();
    }

    /**
     * Jump to a specific frame index.
     */
    public void setFrame(int index) {
        if (index < 0 || index >= mBitmaps.size()) return;
        mCurrentIndex.set(index);
        mCurrentFrame.set(mBitmaps.get(index));
    }

    /**
     * Get the number of loaded images.
     */
    public int getImageCount() {
        return mBitmaps.size();
    }

    /**
     * Check if current source is a GIF.
     */
    public boolean isGif() {
        return mIsGif;
    }

    /**
     * Set the target frame resolution.
     */
    public void setResolution(int width, int height) {
        mTargetWidth = Math.max(1, width);
        mTargetHeight = Math.max(1, height);
        mFrameBuffer = new byte[mTargetWidth * mTargetHeight * 3 / 2];
        mArgbPixels = new int[mTargetWidth * mTargetHeight];
    }

    /**
     * Set the frame ready listener.
     */
    public void setOnFrameReadyListener(OnFrameReadyListener listener) {
        mFrameListener = listener;
    }

    /**
     * Release all resources.
     */
    public void release() {
        stop();
        for (Bitmap bmp : mBitmaps) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        mBitmaps.clear();
        mCurrentFrame.set(null);

        if (mGifMovie != null) {
            mGifMovie = null;
        }

        if (mSourceThread != null) {
            mSourceThread.quitSafely();
            mSourceThread = null;
            mSourceHandler = null;
        }
    }

    // ── Initialization ──────────────────────────────────────────────────────

    public ImageSource() {
        mSourceThread = new HandlerThread("ImageSource-Thread");
        mSourceThread.start();
        mSourceHandler = new Handler(mSourceThread.getLooper());
        setResolution(mTargetWidth, mTargetHeight);
    }

    // ── Internal: GIF ───────────────────────────────────────────────────────

    private void loadGif(String path) {
        try {
            mGifFile = new File(path);
            FileInputStream fis = new FileInputStream(mGifFile);
            mGifMovie = Movie.decodeStream(fis);
            fis.close();

            if (mGifMovie != null && mGifMovie.duration() > 0) {
                mIsGif = true;
                // Set slideshow interval to GIF duration
                mSlideshowIntervalMs.set(mGifMovie.duration() > 0 ? mGifMovie.duration() : 100);
                Log.i(TAG, "GIF loaded: " + path + ", duration=" + mGifMovie.duration() + "ms"
                        + ", width=" + mGifMovie.width() + ", height=" + mGifMovie.height());
            } else {
                Log.w(TAG, "Failed to decode GIF: " + path);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to load GIF: " + path, e);
        }
    }

    private void startGifPlayback() {
        if (mGifMovie == null) {
            notifyError("No GIF loaded");
            return;
        }

        mGifStartTime.set(android.os.SystemClock.uptimeMillis());
        mSourceHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!mRunning.get() || mPaused.get() || mGifMovie == null) return;

                long now = android.os.SystemClock.uptimeMillis();
                int duration = mGifMovie.duration();
                if (duration <= 0) duration = 100;

                int elapsed = (int) ((now - mGifStartTime.get()) % duration);
                mGifMovie.setTime(elapsed);

                // Draw GIF frame to bitmap
                Bitmap frame = Bitmap.createBitmap(mTargetWidth, mTargetHeight, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(frame);
                canvas.drawColor(0xFF000000); // Black background

                float scaleX = (float) mTargetWidth / mGifMovie.width();
                float scaleY = (float) mTargetHeight / mGifMovie.height();
                float scale = Math.min(scaleX, scaleY);

                canvas.save();
                canvas.scale(scale, scale);
                mGifMovie.draw(canvas, 0, 0);
                canvas.restore();

                mCurrentFrame.set(frame);
                deliverFrame(frame);

                // Schedule next frame (~30fps for GIF)
                mSourceHandler.postDelayed(this, 33);
            }
        });
    }

    // ── Internal: Slideshow ─────────────────────────────────────────────────

    private void startSlideshow() {
        if (mBitmaps.isEmpty()) {
            notifyError("No images loaded");
            return;
        }

        // Deliver first frame immediately
        deliverFrame(mBitmaps.get(mCurrentIndex.get()));

        // Schedule frame transitions
        mSourceHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!mRunning.get() || mPaused.get()) return;

                Bitmap frame = getNextFrame();
                if (frame != null) {
                    deliverFrame(frame);
                }

                mSourceHandler.postDelayed(this, mSlideshowIntervalMs.get());
            }
        });
    }

    // ── Internal: Frame Delivery ────────────────────────────────────────────

    private void deliverFrame(Bitmap bitmap) {
        if (mFrameListener == null || bitmap == null) return;

        try {
            // Convert Bitmap to NV21 format
            byte[] nv21 = bitmapToNv21(bitmap);
            if (nv21 != null) {
                mFrameListener.onFrameReady(nv21, mTargetWidth, mTargetHeight);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error converting frame", e);
        }
    }

    /**
     * Convert ARGB Bitmap to NV21 byte array.
     */
    private byte[] bitmapToNv21(Bitmap bitmap) {
        if (bitmap.getWidth() != mTargetWidth || bitmap.getHeight() != mTargetHeight) {
            bitmap = scaleBitmap(bitmap, mTargetWidth, mTargetHeight);
        }

        int[] argb = new int[mTargetWidth * mTargetHeight];
        bitmap.getPixels(argb, 0, mTargetWidth, 0, 0, mTargetWidth, mTargetHeight);

        byte[] nv21 = new byte[mTargetWidth * mTargetHeight * 3 / 2];

        int frameSize = mTargetWidth * mTargetHeight;
        int yIndex = 0;
        int uvIndex = frameSize;

        int R, G, B, Y, U, V;
        int index = 0;

        for (int j = 0; j < mTargetHeight; j++) {
            for (int i = 0; i < mTargetWidth; i++) {
                int pixel = argb[index++];
                R = (pixel >> 16) & 0xFF;
                G = (pixel >> 8) & 0xFF;
                B = pixel & 0xFF;

                // RGB to YUV (BT.601)
                Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                Y = clamp(Y, 0, 255);
                U = clamp(U, 0, 255);
                V = clamp(V, 0, 255);

                nv21[yIndex++] = (byte) Y;

                // NV21: VU interleaved at half resolution
                if (j % 2 == 0 && index % 2 == 0) {
                    nv21[uvIndex++] = (byte) V;
                    nv21[uvIndex++] = (byte) U;
                }
            }
        }

        return nv21;
    }

    /**
     * Scale a bitmap to target dimensions.
     */
    private Bitmap scaleBitmap(Bitmap source, int targetW, int targetH) {
        if (source == null) return null;
        if (source.getWidth() == targetW && source.getHeight() == targetH) return source;

        Matrix matrix = new Matrix();
        matrix.postScale((float) targetW / source.getWidth(),
                (float) targetH / source.getHeight());

        Bitmap scaled = Bitmap.createBitmap(source, 0, 0,
                source.getWidth(), source.getHeight(), matrix, true);
        return scaled;
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqW, int reqH) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqH || width > reqW) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while (halfHeight / inSampleSize >= reqH && halfWidth / inSampleSize >= reqW) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private void notifyError(String message) {
        if (mFrameListener != null) {
            mFrameListener.onError(message);
        }
    }
}

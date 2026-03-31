package com.dualspace.obs.floating;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dualspace.obs.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Floating camera preview window.
 * Provides a draggable, resizable camera overlay with source switching,
 * screenshot capture, and Picture-in-Picture mode.
 */
public class FloatingCameraWindow {

    private static final String TAG = "FloatingCameraWindow";
    private static final int MIN_WIDTH_DP = 120;
    private static final int MAX_WIDTH_DP = 400;
    private static final int PIP_WIDTH_DP = 100;
    private static final int PIP_MARGIN_DP = 16;
    private static final int RESIZE_HANDLE_SIZE_DP = 24;

    private final Context context;
    private final WindowManager windowManager;
    private final DisplayMetrics displayMetrics;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Root container
    private View containerView;
    private WindowManager.LayoutParams containerParams;

    // Camera surface
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;

    // Controls
    private ImageView btnSwitchSource;
    private ImageView btnScreenshot;
    private ImageView btnPip;
    private TextView tvSourceLabel;

    // Resize handle (bottom-right corner)
    private View resizeHandle;

    // Camera
    private Camera camera;
    private int cameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
    private boolean isPreviewing = false;
    private CameraSource currentSource = CameraSource.FRONT;

    // State
    private boolean isPipMode = false;
    private boolean isDragging = false;
    private boolean isResizing = false;
    private float initialTouchX, initialTouchY;
    private float initialViewX, initialViewY;
    private int initialWidth, initialHeight;
    private int currentWidthPx, currentHeightPx;

    /** Camera source options. */
    public enum CameraSource {
        FRONT,
        BACK,
        SCREEN,
        USB
    }

    /** Screenshot callback. */
    public interface OnScreenshotListener {
        void onScreenshotTaken(File file);
        void onScreenshotError(String message);
    }

    private OnScreenshotListener screenshotListener;

    public FloatingCameraWindow(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.displayMetrics = new DisplayMetrics();
        this.windowManager.getDefaultDisplay().getMetrics(this.displayMetrics);
    }

    public void setScreenshotListener(OnScreenshotListener listener) {
        this.screenshotListener = listener;
    }

    // ──────────────── Window Lifecycle ────────────────

    /**
     * Initialize and show the floating camera window.
     */
    public void initWindow() {
        if (containerView != null && containerView.isShown()) {
            Log.w(TAG, "Window already visible");
            return;
        }

        containerView = LayoutInflater.from(context).inflate(R.layout.floating_camera_window, null);
        surfaceView = containerView.findViewById(R.id.camera_surface);
        btnSwitchSource = containerView.findViewById(R.id.btn_switch_source);
        btnScreenshot = containerView.findViewById(R.id.btn_screenshot);
        btnPip = containerView.findViewById(R.id.btn_pip);
        resizeHandle = containerView.findViewById(R.id.resize_handle);
        tvSourceLabel = containerView.findViewById(R.id.tv_source_label);

        // Default dimensions
        currentWidthPx = (int) (200 * displayMetrics.density);
        currentHeightPx = (int) (280 * displayMetrics.density);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        containerParams = new WindowManager.LayoutParams(
                currentWidthPx,
                currentHeightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
        );
        containerParams.gravity = Gravity.TOP | Gravity.END;
        containerParams.x = (int) (16 * displayMetrics.density);
        containerParams.y = (int) (200 * displayMetrics.density);

        // Surface holder setup
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(surfaceHolderCallback);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        // Touch handling on container (drag)
        containerView.setOnTouchListener(containerTouchListener);

        // Resize handle
        resizeHandle.setOnTouchListener(resizeTouchListener);

        // Control buttons
        btnSwitchSource.setOnClickListener(v -> {
            cycleSource();
        });
        btnScreenshot.setOnClickListener(v -> takeScreenshot());
        btnPip.setOnClickListener(v -> {
            if (isPipMode) {
                exitPip();
            } else {
                enterPip();
            }
        });

        updateSourceLabel();

        windowManager.addView(containerView, containerParams);
        Log.i(TAG, "Camera window initialized: " + currentWidthPx + "x" + currentHeightPx);
    }

    /**
     * Remove the floating camera window.
     */
    public void destroyWindow() {
        stopPreview();
        if (containerView != null) {
            if (containerView.isAttachedToWindow()) {
                try {
                    windowManager.removeView(containerView);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "destroyWindow: view not attached", e);
                }
            }
            containerView = null;
        }
    }

    // ──────────────── Camera Preview ────────────────

    private final SurfaceHolder.Callback surfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            startPreview();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (isPreviewing && camera != null) {
                try {
                    camera.stopPreview();
                } catch (Exception e) {
                    Log.w(TAG, "stopPreview on surfaceChanged", e);
                }
                startPreview();
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            stopPreview();
        }
    };

    /**
     * Start camera preview on the surface.
     */
    @SuppressWarnings("deprecation")
    public void startPreview() {
        if (surfaceHolder == null || surfaceHolder.getSurface() == null) {
            Log.w(TAG, "startPreview: surface not ready");
            return;
        }

        stopPreview();

        try {
            camera = Camera.open(cameraId);
            Camera.Parameters params = camera.getParameters();

            // Set optimal preview size
            List<Camera.Size> sizes = params.getSupportedPreviewSizes();
            if (sizes != null && !sizes.isEmpty()) {
                Camera.Size optimal = findOptimalPreviewSize(sizes, currentWidthPx, currentHeightPx);
                params.setPreviewSize(optimal.width, optimal.height);
            }

            // Set focus mode if continuous available
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }

            camera.setParameters(params);
            camera.setPreviewDisplay(surfaceHolder);
            camera.startPreview();
            isPreviewing = true;
            Log.i(TAG, "Camera preview started (cameraId=" + cameraId + ")");
        } catch (Exception e) {
            Log.e(TAG, "startPreview failed", e);
            releaseCamera();
        }
    }

    /**
     * Stop and release the camera.
     */
    public void stopPreview() {
        if (camera != null) {
            try {
                if (isPreviewing) {
                    camera.stopPreview();
                }
            } catch (Exception e) {
                Log.w(TAG, "stopPreview exception", e);
            }
            releaseCamera();
            isPreviewing = false;
            Log.i(TAG, "Camera preview stopped");
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            camera.release();
            camera = null;
        }
    }

    @SuppressWarnings("deprecation")
    private Camera.Size findOptimalPreviewSize(List<Camera.Size> sizes, int targetWidth, int targetHeight) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) targetHeight / targetWidth;

        Camera.Size bestSize = sizes.get(0);
        double bestDiff = Double.MAX_VALUE;

        for (Camera.Size size : sizes) {
            double ratio = (double) size.height / size.width;
            double ratioDiff = Math.abs(ratio - targetRatio);
            double areaDiff = Math.abs(size.width * size.height - targetWidth * targetHeight);

            if (ratioDiff < ASPECT_TOLERANCE && areaDiff < bestDiff) {
                bestSize = size;
                bestDiff = areaDiff;
            }
        }

        // If no size matched the ratio, take closest by area
        if (bestDiff == Double.MAX_VALUE) {
            for (Camera.Size size : sizes) {
                double areaDiff = Math.abs(size.width * size.height - targetWidth * targetHeight);
                if (areaDiff < bestDiff) {
                    bestSize = size;
                    bestDiff = areaDiff;
                }
            }
        }

        return bestSize;
    }

    // ──────────────── Source Management ────────────────

    public void setSource(CameraSource source) {
        this.currentSource = source;
        updateSourceLabel();

        switch (source) {
            case FRONT:
                cameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
                if (isPreviewing) startPreview();
                break;
            case BACK:
                cameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
                if (isPreviewing) startPreview();
                break;
            case SCREEN:
                // Screen capture is handled externally; stop camera preview
                stopPreview();
                break;
            case USB:
                // USB camera is handled externally
                stopPreview();
                break;
        }
    }

    private void cycleSource() {
        CameraSource[] sources = CameraSource.values();
        int nextIndex = (currentSource.ordinal() + 1) % sources.length;
        setSource(sources[nextIndex]);
    }

    private void updateSourceLabel() {
        if (tvSourceLabel != null && mainHandler != null) {
            mainHandler.post(() -> {
                if (tvSourceLabel != null) {
                    tvSourceLabel.setText(currentSource.name());
                }
            });
        }
    }

    // ──────────────── Screenshot ────────────────

    public void takeScreenshot() {
        if (surfaceView == null) {
            notifyScreenshotError("Surface not available");
            return;
        }

        try {
            // Create bitmap from surface view
            Bitmap bitmap = Bitmap.createBitmap(
                    surfaceView.getWidth(),
                    surfaceView.getHeight(),
                    Bitmap.Config.ARGB_8888
            );
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            surfaceView.draw(canvas);

            // Save to file
            File dir = new File(context.getExternalFilesDir(null), "screenshots");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String filename = "cam_screenshot_" + System.currentTimeMillis() + ".png";
            File file = new File(dir, filename);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(baos.toByteArray());
            fos.flush();
            fos.close();
            bitmap.recycle();

            Log.i(TAG, "Screenshot saved: " + file.getAbsolutePath());
            if (screenshotListener != null) {
                screenshotListener.onScreenshotTaken(file);
            }
        } catch (IOException e) {
            Log.e(TAG, "Screenshot failed", e);
            notifyScreenshotError("IO error: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Screenshot OOM", e);
            notifyScreenshotError("Out of memory");
        }
    }

    private void notifyScreenshotError(String message) {
        if (screenshotListener != null) {
            mainHandler.post(() -> screenshotListener.onScreenshotError(message));
        }
    }

    // ──────────────── Resize ────────────────

    private final View.OnTouchListener resizeTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isResizing = true;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    initialWidth = containerParams.width;
                    initialHeight = containerParams.height;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    if (!isResizing) return false;

                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;

                    // Use the larger delta to maintain aspect ratio roughly
                    float delta = Math.max(dx, dy);

                    int minW = (int) (MIN_WIDTH_DP * displayMetrics.density);
                    int maxW = (int) (MAX_WIDTH_DP * displayMetrics.density);
                    int newWidth = (int) Math.max(minW, Math.min(maxW, initialWidth + delta));
                    float aspect = (float) initialHeight / initialWidth;
                    int newHeight = (int) (newWidth * aspect);

                    resize(newWidth, newHeight);
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    isResizing = false;
                    return true;
            }
            return false;
        }
    };

    /**
     * Resize the camera window.
     *
     * @param widthPx  width in pixels
     * @param heightPx height in pixels
     */
    public void resize(int widthPx, int heightPx) {
        int minW = (int) (MIN_WIDTH_DP * displayMetrics.density);
        int maxW = (int) (MAX_WIDTH_DP * displayMetrics.density);

        currentWidthPx = Math.max(minW, Math.min(maxW, widthPx));
        // Keep aspect ratio reasonable
        currentHeightPx = Math.max(minW, heightPx);

        if (containerView != null && containerView.isAttachedToWindow()) {
            containerParams.width = currentWidthPx;
            containerParams.height = currentHeightPx;
            try {
                windowManager.updateViewLayout(containerView, containerParams);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "resize: view not attached", e);
            }
            Log.d(TAG, "Resized to " + currentWidthPx + "x" + currentHeightPx);
        }
    }

    // ──────────────── Drag ────────────────

    private final View.OnTouchListener containerTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (isResizing) return false;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isDragging = false;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    initialViewX = containerParams.x;
                    initialViewY = containerParams.y;
                    return false; // let child views handle clicks

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        containerParams.x = (int) (initialViewX + dx);
                        containerParams.y = (int) (initialViewY + dy);
                        try {
                            windowManager.updateViewLayout(containerView, containerParams);
                        } catch (IllegalArgumentException e) {
                            Log.w(TAG, "drag: view not attached", e);
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    if (isDragging) {
                        snapToCorner();
                        return true;
                    }
                    return false;
            }
            return false;
        }
    };

    private void snapToCorner() {
        int screenWidth = displayMetrics.widthPixels;
        int screenHeight = displayMetrics.heightPixels;

        int centerX = containerParams.x + containerParams.width / 2;
        int centerY = containerParams.y + containerParams.height / 2;

        int targetX = (centerX < screenWidth / 2)
                ? (int) (PIP_MARGIN_DP * displayMetrics.density)
                : screenWidth - containerParams.width - (int) (PIP_MARGIN_DP * displayMetrics.density);
        int targetY = (centerY < screenHeight / 2)
                ? (int) (PIP_MARGIN_DP * displayMetrics.density)
                : screenHeight - containerParams.height - (int) (PIP_MARGIN_DP * displayMetrics.density);

        animateToPosition(targetX, targetY);
    }

    private void animateToPosition(int targetX, int targetY) {
        Handler handler = new Handler(Looper.getMainLooper());
        long startTime = System.currentTimeMillis();
        int duration = 200;
        int startX = containerParams.x;
        int startY = containerParams.y;

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (containerView == null || !containerView.isAttachedToWindow()) return;
                long elapsed = System.currentTimeMillis() - startTime;
                float progress = Math.min(1f, (float) elapsed / duration);
                float eased = 1 - (float) Math.pow(1 - progress, 3);

                containerParams.x = (int) (startX + (targetX - startX) * eased);
                containerParams.y = (int) (startY + (targetY - startY) * eased);
                try {
                    windowManager.updateViewLayout(containerView, containerParams);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "animate: view not attached", e);
                }

                if (progress < 1f) {
                    handler.postDelayed(this, 16);
                }
            }
        });
    }

    // ──────────────── PiP Mode ────────────────

    /**
     * Enter Picture-in-Picture mode: shrink to a small corner window.
     */
    public void enterPip() {
        if (isPipMode) return;

        isPipMode = true;
        int pipSize = (int) (PIP_WIDTH_DP * displayMetrics.density);
        int margin = (int) (PIP_MARGIN_DP * displayMetrics.density);

        resize(pipSize, (int) (pipSize * 1.33f));

        // Move to bottom-right corner
        int targetX = displayMetrics.widthPixels - pipSize - margin;
        int targetY = displayMetrics.heightPixels - (int) (pipSize * 1.33f) - margin;
        animateToPosition(targetX, targetY);

        Log.i(TAG, "Entered PiP mode");
    }

    /**
     * Exit Picture-in-Picture mode: restore to previous size.
     */
    public void exitPip() {
        if (!isPipMode) return;

        isPipMode = false;
        int restoreWidth = (int) (200 * displayMetrics.density);
        int restoreHeight = (int) (280 * displayMetrics.density);
        resize(restoreWidth, restoreHeight);
        snapToCorner();

        Log.i(TAG, "Exited PiP mode");
    }

    public boolean isPipMode() {
        return isPipMode;
    }

    public boolean isPreviewing() {
        return isPreviewing;
    }
}

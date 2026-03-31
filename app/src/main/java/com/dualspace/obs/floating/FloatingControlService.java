package com.dualspace.obs.floating;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.dualspace.obs.R;

/**
 * Floating control service providing an overlay bubble and expandable panel.
 * Runs as a foreground service with a persistent notification.
 * The bubble is draggable, snaps to screen edges, and auto-hides after inactivity.
 */
public class FloatingControlService extends Service {

    private static final String TAG = "FloatingControlService";
    private static final String CHANNEL_ID = "dualspace_obs_floating_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int AUTO_HIDE_DELAY_MS = 60_000;
    private static final int SNAP_ANIMATION_DURATION = 200;

    private WindowManager windowManager;
    private DisplayMetrics displayMetrics;

    // Bubble views
    private View bubbleView;
    private ImageView bubbleIcon;
    private WindowManager.LayoutParams bubbleParams;

    // Panel views
    private View panelView;
    private WindowManager.LayoutParams panelParams;
    private boolean isPanelExpanded = false;

    // Panel buttons
    private ImageView btnRecord;
    private ImageView btnStream;
    private ImageView btnCamera;
    private ImageView btnScreenshot;
    private ImageView btnSettings;
    private ImageView btnClose;

    // Touch tracking
    private float initialTouchX, initialTouchY;
    private float initialBubbleX, initialBubbleY;
    private boolean isDragging = false;
    private long touchStartTime;
    private Runnable longPressRunnable;

    // Auto-hide
    private final Handler autoHideHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoHideRunnable = this::hideBubble;
    private boolean isHidden = false;

    // Callbacks
    private OnFloatingActionListener actionListener;

    /** Interface for actions triggered from the floating panel. */
    public interface OnFloatingActionListener {
        void onRecordClicked();
        void onStreamClicked();
        void onCameraClicked();
        void onScreenshotClicked();
        void onSettingsClicked();
        void onCloseClicked();
    }

    public void setActionListener(OnFloatingActionListener listener) {
        this.actionListener = listener;
    }

    // ──────────────── Lifecycle ────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "FloatingControlService created");

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        showBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "ACTION_SHOW":
                    showBubble();
                    break;
                case "ACTION_HIDE":
                    hideBubble();
                    break;
                case "ACTION_STOP":
                    stopSelf();
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "FloatingControlService destroyed");
        autoHideHandler.removeCallbacks(autoHideRunnable);
        removeBubble();
        removePanel();
        if (longPressRunnable != null) {
            new Handler(Looper.getMainLooper()).removeCallbacks(longPressRunnable);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ──────────────── Notification ────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "DualSpace OBS Floating Control",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Floating control overlay is active");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DualSpace OBS")
                .setContentText("Floating control active")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    // ──────────────── Bubble ────────────────

    private void showBubble() {
        if (bubbleView != null && bubbleView.isShown()) {
            // Already showing; reset auto-hide timer
            resetAutoHideTimer();
            return;
        }

        bubbleView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null);
        bubbleIcon = bubbleView.findViewById(R.id.floating_bubble_icon);

        int size = (int) (56 * displayMetrics.density);
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        bubbleParams = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 0;
        bubbleParams.y = (int) (200 * displayMetrics.density);

        bubbleView.setOnTouchListener(bubbleTouchListener);
        windowManager.addView(bubbleView, bubbleParams);
        isHidden = false;
        resetAutoHideTimer();

        Log.d(TAG, "Bubble shown at x=" + bubbleParams.x + " y=" + bubbleParams.y);
    }

    private void hideBubble() {
        if (bubbleView != null && bubbleView.isShown()) {
            windowManager.removeView(bubbleView);
            isHidden = true;
            Log.d(TAG, "Bubble hidden");
        }
    }

    private void removeBubble() {
        if (bubbleView != null) {
            if (bubbleView.isAttachedToWindow()) {
                windowManager.removeView(bubbleView);
            }
            bubbleView = null;
        }
    }

    // ──────────────── Touch Handling ────────────────

    private final View.OnTouchListener bubbleTouchListener = new View.OnTouchListener() {
        private static final int CLICK_THRESHOLD = 10; // dp
        private static final int LONG_PRESS_TIMEOUT = 500; // ms

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    initialBubbleX = bubbleParams.x;
                    initialBubbleY = bubbleParams.y;
                    isDragging = false;
                    touchStartTime = System.currentTimeMillis();
                    resetAutoHideTimer();

                    // Schedule long press
                    longPressRunnable = () -> {
                        if (!isDragging) {
                            showContextMenu();
                        }
                    };
                    new Handler(Looper.getMainLooper()).postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    float distance = (float) Math.sqrt(dx * dx + dy * dy);
                    float threshold = CLICK_THRESHOLD * displayMetrics.density;

                    if (distance > threshold) {
                        isDragging = true;
                        // Cancel long press on significant drag
                        if (longPressRunnable != null) {
                            new Handler(Looper.getMainLooper()).removeCallbacks(longPressRunnable);
                            longPressRunnable = null;
                        }
                    }

                    if (isDragging) {
                        int newX = initialBubbleX + (int) dx;
                        int newY = initialBubbleY + (int) dy;
                        updatePosition(newX, newY);
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    // Cancel long press
                    if (longPressRunnable != null) {
                        new Handler(Looper.getMainLooper()).removeCallbacks(longPressRunnable);
                        longPressRunnable = null;
                    }

                    if (!isDragging) {
                        long elapsed = System.currentTimeMillis() - touchStartTime;
                        if (elapsed < LONG_PRESS_TIMEOUT) {
                            // Short tap → expand / collapse panel
                            if (isPanelExpanded) {
                                collapsePanel();
                            } else {
                                expandPanel();
                            }
                        }
                    } else {
                        // Drag ended → snap to edge
                        snapToEdge();
                    }
                    return true;
                }
            }
            return false;
        }
    };

    private void showContextMenu() {
        // Long press shows a quick context menu above the bubble
        Log.d(TAG, "Long press context menu triggered");
        // Collapse panel if expanded
        if (isPanelExpanded) {
            collapsePanel();
        }
        // Toggle visibility as a quick action for long press
        hideBubble();
    }

    // ──────────────── Position ────────────────

    /**
     * Update bubble position relative to the window.
     *
     * @param x horizontal offset in pixels
     * @param y vertical offset in pixels
     */
    public void updatePosition(int x, int y) {
        if (bubbleParams == null || windowManager == null) return;

        // Clamp Y so bubble stays within usable screen area
        int minY = 0;
        int maxY = displayMetrics.heightPixels - bubbleParams.height;
        bubbleParams.y = Math.max(minY, Math.min(maxY, y));

        bubbleParams.x = x;
        try {
            windowManager.updateViewLayout(bubbleView, bubbleParams);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "updatePosition: view not attached", e);
        }
    }

    private void snapToEdge() {
        if (bubbleParams == null) return;
        int screenWidth = displayMetrics.widthPixels;
        int bubbleCenterX = bubbleParams.x + bubbleParams.width / 2;

        int targetX;
        if (bubbleCenterX < screenWidth / 2) {
            targetX = 0; // snap left
        } else {
            targetX = screenWidth - bubbleParams.width; // snap right
        }

        // Animate to edge
        animatePosition(bubbleParams.x, targetX, bubbleParams.y, bubbleParams.y, SNAP_ANIMATION_DURATION);
    }

    private void animatePosition(int fromX, int toX, int fromY, int toY, long duration) {
        Handler handler = new Handler(Looper.getMainLooper());
        long startTime = System.currentTimeMillis();
        int frames = (int) (duration / 16); // ~60 fps

        handler.post(new Runnable() {
            int frame = 0;

            @Override
            public void run() {
                if (frame >= frames || bubbleView == null || !bubbleView.isAttachedToWindow()) return;

                float progress = (float) frame / frames;
                // Ease-out cubic
                float eased = 1 - (float) Math.pow(1 - progress, 3);

                int currentX = (int) (fromX + (toX - fromX) * eased);
                int currentY = (int) (fromY + (toY - fromY) * eased);
                updatePosition(currentX, currentY);

                frame++;
                handler.postDelayed(this, 16);
            }
        });
    }

    // ──────────────── Panel ────────────────

    private void expandPanel() {
        if (isPanelExpanded) return;

        panelView = LayoutInflater.from(this).inflate(R.layout.floating_panel, null);

        // Find buttons
        btnRecord = panelView.findViewById(R.id.panel_btn_record);
        btnStream = panelView.findViewById(R.id.panel_btn_stream);
        btnCamera = panelView.findViewById(R.id.panel_btn_camera);
        btnScreenshot = panelView.findViewById(R.id.panel_btn_screenshot);
        btnSettings = panelView.findViewById(R.id.panel_btn_settings);
        btnClose = panelView.findViewById(R.id.panel_btn_close);

        // Set click listeners
        btnRecord.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onRecordClicked();
            collapsePanel();
        });
        btnStream.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onStreamClicked();
            collapsePanel();
        });
        btnCamera.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onCameraClicked();
            collapsePanel();
        });
        btnScreenshot.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onScreenshotClicked();
            collapsePanel();
        });
        btnSettings.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onSettingsClicked();
            collapsePanel();
        });
        btnClose.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onCloseClicked();
            collapsePanel();
            stopSelf();
        });

        // Layout params for panel
        int panelWidth = (int) (48 * displayMetrics.density); // narrow vertical strip
        int panelHeight = LinearLayout.LayoutParams.WRAP_CONTENT;

        panelParams = new WindowManager.LayoutParams(
                panelWidth,
                panelHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        // Position panel next to the bubble
        int bubbleRight = bubbleParams.x + bubbleParams.width;
        int screenWidth = displayMetrics.widthPixels;

        if (bubbleRight + panelWidth > screenWidth) {
            // Place panel to the left of bubble
            panelParams.gravity = Gravity.TOP | Gravity.END;
            panelParams.x = screenWidth - bubbleParams.x;
            panelParams.y = bubbleParams.y;
        } else {
            panelParams.gravity = Gravity.TOP | Gravity.START;
            panelParams.x = bubbleRight;
            panelParams.y = bubbleParams.y;
        }

        panelView.setAlpha(0f);
        windowManager.addView(panelView, panelParams);

        // Fade in
        panelView.animate().alpha(1f).setDuration(150).start();

        isPanelExpanded = true;
        Log.d(TAG, "Panel expanded");
    }

    private void collapsePanel() {
        if (!isPanelExpanded || panelView == null) return;

        panelView.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction(() -> removePanel())
                .start();
        isPanelExpanded = false;
        Log.d(TAG, "Panel collapsed");
    }

    private void removePanel() {
        if (panelView != null) {
            if (panelView.isAttachedToWindow()) {
                try {
                    windowManager.removeView(panelView);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "removePanel: view not attached", e);
                }
            }
            panelView = null;
            isPanelExpanded = false;
        }
    }

    // ──────────────── Auto-hide ────────────────

    private void resetAutoHideTimer() {
        autoHideHandler.removeCallbacks(autoHideRunnable);
        if (!isHidden && !isPanelExpanded) {
            autoHideHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS);
        }
    }

    // ──────────────── Public API ────────────────

    public boolean isPanelExpanded() {
        return isPanelExpanded;
    }

    public boolean isBubbleVisible() {
        return bubbleView != null && bubbleView.isShown();
    }

    public void setBubbleIcon(int resId) {
        if (bubbleIcon != null) {
            bubbleIcon.setImageResource(resId);
        }
    }
}

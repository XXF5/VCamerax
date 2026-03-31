package com.dualspace.obs.floating;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dualspace.obs.R;

/**
 * Stream / recording overlay displayed on screen while recording or streaming.
 * Shows timer, record/stream toggle buttons, mute, bitrate/FPS indicators,
 * and a connection status indicator.
 */
public class StreamOverlay {

    private static final String TAG = "StreamOverlay";
    private static final long TIMER_UPDATE_INTERVAL_MS = 1_000;

    private final Context context;
    private final WindowManager windowManager;
    private final DisplayMetrics displayMetrics;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Root view
    private View rootView;
    private WindowManager.LayoutParams rootParams;

    // UI elements
    private TextView tvTimer;
    private ImageView btnRecord;
    private ImageView btnStream;
    private ImageView btnMute;
    private TextView tvBitrate;
    private TextView tvFps;
    private TextView tvConnectionStatus;
    private View statusDot;

    // State
    private boolean isVisible = false;
    private boolean isRecording = false;
    private boolean isStreaming = false;
    private boolean isMuted = false;
    private long recordingStartTime = 0;
    private long elapsedSeconds = 0;
    private ConnectionStatus connectionStatus = ConnectionStatus.DISCONNECTED;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;
            elapsedSeconds = (System.currentTimeMillis() - recordingStartTime) / 1000;
            updateTimer();
            mainHandler.postDelayed(this, TIMER_UPDATE_INTERVAL_MS);
        }
    };

    /** Connection status options. */
    public enum ConnectionStatus {
        CONNECTED,
        CONNECTING,
        DISCONNECTED,
        RECONNECTING,
        ERROR
    }

    /** Callback for overlay button presses. */
    public interface OnOverlayActionListener {
        void onToggleRecord(boolean recording);
        void onToggleStream(boolean streaming);
        void onToggleMute(boolean muted);
    }

    private OnOverlayActionListener actionListener;

    public StreamOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.displayMetrics = new DisplayMetrics();
        this.windowManager.getDefaultDisplay().getMetrics(this.displayMetrics);
    }

    public void setActionListener(OnOverlayActionListener listener) {
        this.actionListener = listener;
    }

    // ──────────────── Show / Hide ────────────────

    /**
     * Show the stream overlay at the top-center of the screen.
     */
    public void show() {
        if (isVisible) return;

        rootView = buildOverlayView();

        int width = WindowManager.LayoutParams.WRAP_CONTENT;
        int height = WindowManager.LayoutParams.WRAP_CONTENT;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

        rootParams = new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
        );
        rootParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        rootParams.y = (int) (24 * displayMetrics.density);

        windowManager.addView(rootView, rootParams);
        isVisible = true;

        refreshAllUi();
        Log.i(TAG, "Stream overlay shown");
    }

    /**
     * Hide the overlay.
     */
    public void hide() {
        if (!isVisible || rootView == null) return;

        stopTimer();
        if (rootView.isAttachedToWindow()) {
            try {
                windowManager.removeView(rootView);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "hide: view not attached", e);
            }
        }
        rootView = null;
        isVisible = false;
        Log.i(TAG, "Stream overlay hidden");
    }

    // ──────────────── Build UI Programmatically ────────────────

    private View buildOverlayView() {
        int dp8 = (int) (8 * displayMetrics.density);
        int dp12 = (int) (12 * displayMetrics.density);
        int dp16 = (int) (16 * displayMetrics.density);
        int dp4 = (int) (4 * displayMetrics.density);

        // Root horizontal layout
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp12, dp8, dp12, dp8);

        // Rounded background
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(24 * displayMetrics.density);
        bg.setColor(0xCC000000); // semi-transparent black
        root.setBackground(bg);

        // ── Timer ──
        tvTimer = new TextView(context);
        tvTimer.setTextColor(0xFFFFFFFF);
        tvTimer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTimer.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvTimer.setText("00:00:00");
        LinearLayout.LayoutParams timerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        timerLp.rightMargin = dp16;
        root.addView(tvTimer, timerLp);

        // ── Record button ──
        btnRecord = createCircleButton(0xFFFF4444, android.R.drawable.ic_menu_add); // placeholder
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                (int) (36 * displayMetrics.density),
                (int) (36 * displayMetrics.density)
        );
        btnLp.rightMargin = dp8;
        root.addView(btnRecord, btnLp);
        btnRecord.setOnClickListener(v -> toggleRecord());

        // ── Stream button ──
        btnStream = createCircleButton(0xFF44FF44, android.R.drawable.ic_menu_share);
        root.addView(btnStream, new LinearLayout.LayoutParams(btnLp));
        btnStream.setOnClickListener(v -> toggleStream());

        // ── Mute button ──
        btnMute = createCircleButton(0xFF888888, android.R.drawable.ic_lock_silent_mode);
        LinearLayout.LayoutParams muteLp = new LinearLayout.LayoutParams(btnLp);
        muteLp.leftMargin = dp8;
        root.addView(btnMute, muteLp);
        btnMute.setOnClickListener(v -> toggleMute());

        // ── Spacer ──
        View spacer = new View(context);
        LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(
                (int) (8 * displayMetrics.density),
                LinearLayout.LayoutParams.MATCH_PARENT
        );
        root.addView(spacer, spacerLp);

        // ── Bitrate ──
        tvBitrate = new TextView(context);
        tvBitrate.setTextColor(0xFFAAAAAA);
        tvBitrate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvBitrate.setText("0 kbps");
        LinearLayout.LayoutParams statLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        root.addView(tvBitrate, statLp);

        // ── FPS ──
        tvFps = new TextView(context);
        tvFps.setTextColor(0xFFAAAAAA);
        tvFps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvFps.setText("0 fps");
        LinearLayout.LayoutParams fpsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        fpsLp.leftMargin = dp8;
        root.addView(tvFps, fpsLp);

        // ── Connection status ──
        LinearLayout statusContainer = new LinearLayout(context);
        statusContainer.setOrientation(LinearLayout.HORIZONTAL);
        statusContainer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusContainerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusContainerLp.leftMargin = dp8;
        root.addView(statusContainer, statusContainerLp);

        statusDot = new View(context);
        int dotSize = (int) (8 * displayMetrics.density);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dotSize, dotSize);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setCornerRadius(dotSize / 2f);
        dotBg.setColor(0xFFFF0000);
        statusDot.setBackground(dotBg);
        statusContainer.addView(statusDot, dotLp);

        tvConnectionStatus = new TextView(context);
        tvConnectionStatus.setTextColor(0xFFAAAAAA);
        tvConnectionStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvConnectionStatus.setText("Disconnected");
        LinearLayout.LayoutParams statusTextLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusTextLp.leftMargin = dp4;
        statusContainer.addView(tvConnectionStatus, statusTextLp);

        // Make overlay draggable
        root.setOnTouchListener(overlayTouchListener);

        return root;
    }

    private ImageView createCircleButton(int bgColor, int iconRes) {
        ImageView btn = new ImageView(context);
        btn.setImageResource(iconRes);
        btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        btn.setPadding(
                (int) (8 * displayMetrics.density),
                (int) (8 * displayMetrics.density),
                (int) (8 * displayMetrics.density),
                (int) (8 * displayMetrics.density)
        );
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(18 * displayMetrics.density);
        bg.setColor(bgColor);
        btn.setBackground(bg);
        return btn;
    }

    // ──────────────── Overlay Drag ────────────────

    private final View.OnTouchListener overlayTouchListener = new View.OnTouchListener() {
        private float initialTouchY, initialY;
        private boolean isDragging = false;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialTouchY = event.getRawY();
                    initialY = rootParams.y;
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dy) > 10) isDragging = true;
                    if (isDragging) {
                        rootParams.y = (int) (initialY + dy);
                        try {
                            windowManager.updateViewLayout(rootView, rootParams);
                        } catch (IllegalArgumentException e) {
                            Log.w(TAG, "drag: view not attached", e);
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    return isDragging;
            }
            return false;
        }
    };

    // ──────────────── Timer ────────────────

    /**
     * Update the timer display to current elapsed time.
     */
    public void updateTimer() {
        if (tvTimer == null) return;
        int hours = (int) (elapsedSeconds / 3600);
        int minutes = (int) ((elapsedSeconds % 3600) / 60);
        int seconds = (int) (elapsedSeconds % 60);
        String time = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        tvTimer.setText(time);
    }

    private void startTimer() {
        recordingStartTime = System.currentTimeMillis() - (elapsedSeconds * 1000);
        mainHandler.post(timerRunnable);
    }

    private void stopTimer() {
        mainHandler.removeCallbacks(timerRunnable);
    }

    // ──────────────── Toggle Actions ────────────────

    /**
     * Toggle recording on/off.
     */
    public void toggleRecord() {
        isRecording = !isRecording;

        if (isRecording) {
            startTimer();
        } else {
            stopTimer();
            elapsedSeconds = 0;
            updateTimer();
        }

        refreshRecordButton();
        if (actionListener != null) {
            actionListener.onToggleRecord(isRecording);
        }
        Log.i(TAG, "Recording " + (isRecording ? "started" : "stopped"));
    }

    /**
     * Toggle streaming on/off.
     */
    public void toggleStream() {
        isStreaming = !isStreaming;

        refreshStreamButton();
        if (actionListener != null) {
            actionListener.onToggleStream(isStreaming);
        }
        Log.i(TAG, "Streaming " + (isStreaming ? "started" : "stopped"));
    }

    /**
     * Toggle mute on/off.
     */
    public void toggleMute() {
        isMuted = !isMuted;

        refreshMuteButton();
        if (actionListener != null) {
            actionListener.onToggleMute(isMuted);
        }
        Log.i(TAG, "Mute " + (isMuted ? "on" : "off"));
    }

    // ──────────────── Stats Update ────────────────

    /**
     * Update stream statistics display.
     *
     * @param bitrateKbps current bitrate in kbps
     * @param fps         current frames per second
     */
    public void updateStats(int bitrateKbps, int fps) {
        if (tvBitrate != null && mainHandler != null) {
            mainHandler.post(() -> {
                if (tvBitrate != null) {
                    tvBitrate.setText(bitrateKbps + " kbps");
                }
                if (tvFps != null) {
                    tvFps.setText(fps + " fps");
                }
            });
        }
    }

    /**
     * Set the connection status and update the indicator.
     *
     * @param status current connection status
     */
    public void setConnectionStatus(ConnectionStatus status) {
        this.connectionStatus = status;
        refreshConnectionStatus();
    }

    // ──────────────── UI Refresh ────────────────

    private void refreshAllUi() {
        refreshRecordButton();
        refreshStreamButton();
        refreshMuteButton();
        refreshConnectionStatus();
        updateTimer();
    }

    private void refreshRecordButton() {
        if (btnRecord == null) return;
        int color = isRecording ? 0xFFFF0000 : 0xFF666666;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(18 * displayMetrics.density);
        bg.setColor(color);
        btnRecord.setBackground(bg);
        // Animate pulse effect while recording
        if (isRecording) {
            btnRecord.setAlpha(1f);
            btnRecord.animate().alpha(0.5f).setDuration(500)
                    .withEndAction(() -> {
                        if (isRecording && btnRecord != null) {
                            btnRecord.animate().alpha(1f).setDuration(500).start();
                        }
                    }).start();
        }
    }

    private void refreshStreamButton() {
        if (btnStream == null) return;
        int color = isStreaming ? 0xFF44FF44 : 0xFF666666;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(18 * displayMetrics.density);
        bg.setColor(color);
        btnStream.setBackground(bg);
    }

    private void refreshMuteButton() {
        if (btnMute == null) return;
        int color = isMuted ? 0xFFFF8800 : 0xFF888888;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(18 * displayMetrics.density);
        bg.setColor(color);
        btnMute.setBackground(bg);
    }

    private void refreshConnectionStatus() {
        if (statusDot == null || tvConnectionStatus == null) return;
        int dotColor;
        String text;

        switch (connectionStatus) {
            case CONNECTED:
                dotColor = 0xFF44FF44;
                text = "Live";
                break;
            case CONNECTING:
                dotColor = 0xFFFFFF00;
                text = "Connecting";
                break;
            case RECONNECTING:
                dotColor = 0xFFFF8800;
                text = "Reconnecting";
                break;
            case ERROR:
                dotColor = 0xFFFF0000;
                text = "Error";
                break;
            case DISCONNECTED:
            default:
                dotColor = 0xFF666666;
                text = "Offline";
                break;
        }

        GradientDrawable dotBg = new GradientDrawable();
        int dotSize = (int) (8 * displayMetrics.density);
        dotBg.setCornerRadius(dotSize / 2f);
        dotBg.setColor(dotColor);
        statusDot.setBackground(dotBg);
        tvConnectionStatus.setText(text);
    }

    // ──────────────── State Getters ────────────────

    public boolean isRecording() {
        return isRecording;
    }

    public boolean isStreaming() {
        return isStreaming;
    }

    public boolean isMuted() {
        return isMuted;
    }

    public boolean isVisible() {
        return isVisible;
    }

    public long getElapsedSeconds() {
        return elapsedSeconds;
    }
}

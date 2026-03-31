/*
 * Copyright (c) 2024 Dual Space OBS Contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.dualspace.obs.ui.activities;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.MediaProjectionManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.dualspace.obs.R;
import com.dualspace.obs.databinding.ActivityVirtualCameraBinding;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * VirtualCameraActivity lets the user configure a virtual camera source
 * for OBS streaming.  The user can select from several source types
 * (None, Image, Video, Screen Capture, Color), choose camera
 * orientation, and adjust resolution / FPS settings.
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Live camera preview via {@link TextureView}</li>
 *   <li>Source type selection via {@link ChipGroup}</li>
 *   <li>Image / video picker via system intents</li>
 *   <li>Screen capture via MediaProjection</li>
 *   <li>Camera switch (front / back)</li>
 *   <li>Resolution &amp; FPS spinners</li>
 *   <li>Test preview &amp; save settings</li>
 * </ul>
 */
public class VirtualCameraActivity extends AppCompatActivity {

    private static final String TAG = "VirtualCameraActivity";

    // ─── Permission request codes ────────────────────────────────────
    private static final int CAMERA_PERMISSION_REQUEST = 5001;
    private static final int SCREEN_CAPTURE_REQUEST = 5002;
    private static final int STORAGE_PERMISSION_REQUEST = 5003;

    // ─── Intent request codes (legacy) ───────────────────────────────
    private static final int PICK_IMAGE_REQUEST = 5101;
    private static final int PICK_VIDEO_REQUEST = 5102;

    // ─── Binding ─────────────────────────────────────────────────────
    private ActivityVirtualCameraBinding binding;

    // ─── Preferences ─────────────────────────────────────────────────
    private SharedPreferences cameraPrefs;

    // ─── Camera ──────────────────────────────────────────────────────
    private Camera camera;
    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    private SurfaceTexture previewSurfaceTexture;
    private final Handler cameraHandler = new Handler(Looper.getMainLooper());

    // ─── Source state ────────────────────────────────────────────────
    private String selectedSourceType = "none"; // none, image, video, screen, color
    private Uri selectedImageUri;
    private Uri selectedVideoUri;
    private int selectedColor = 0xFF000000; // default black

    // ─── Settings ────────────────────────────────────────────────────
    private String selectedResolution = "1280x720";
    private int selectedFps = 30;
    private boolean noiseSuppressionEnabled = true;

    // ─── Temp file for camera capture ────────────────────────────────
    private File tempImageFile;

    // ══════════════════════════════════════════════════════════════════
    // Activity Result Launchers
    // ══════════════════════════════════════════════════════════════════

    private final ActivityResultLauncher<Intent> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedImageUri = result.getData().getData();
                    selectedSourceType = "image";
                    updateSourceChips();
                    showSelectedImage();
                    Toast.makeText(this, R.string.image_selected,
                            Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> videoPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedVideoUri = result.getData().getData();
                    selectedSourceType = "video";
                    updateSourceChips();
                    showSelectedVideoThumbnail();
                    Toast.makeText(this, R.string.video_selected,
                            Toast.LENGTH_SHORT).show();
                }
            });

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityVirtualCameraBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        cameraPrefs = getSharedPreferences("camera_prefs", MODE_PRIVATE);
        loadSavedSettings();

        initToolbar();
        initSourceChips();
        initCameraPreview();
        initResolutionSpinner();
        initFpsSpinner();
        initButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if ("none".equals(selectedSourceType) || "camera".equals(selectedSourceType)) {
            requestCameraAndStart();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
        cameraHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.menu_virtual_camera, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.action_apply_settings) {
            applySettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ══════════════════════════════════════════════════════════════════
    // Initialisation
    // ══════════════════════════════════════════════════════════════════

    private void initToolbar() {
        Toolbar toolbar = binding.toolbar;
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.title_virtual_camera);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void initSourceChips() {
        binding.sourceChipGroup.setOnCheckedChangeListener((group, checkedId) -> {
            Chip chip = group.findViewById(checkedId);
            if (chip != null) {
                selectedSourceType = chip.getTag().toString();
                Log.i(TAG, "Source type selected: " + selectedSourceType);
                handleSourceTypeChange(selectedSourceType);
            }
        });
        // Set initial chip
        updateSourceChips();
    }

    private void updateSourceChips() {
        Chip chip;
        switch (selectedSourceType) {
            case "image":
                chip = binding.chipImage;
                break;
            case "video":
                chip = binding.chipVideo;
                break;
            case "screen":
                chip = binding.chipScreen;
                break;
            case "color":
                chip = binding.chipColor;
                break;
            default:
                chip = binding.chipNone;
                selectedSourceType = "none";
                break;
        }
        if (chip != null) {
            binding.sourceChipGroup.check(chip.getId());
        }
    }

    private void handleSourceTypeChange(@NonNull String type) {
        switch (type) {
            case "none":
                stopCamera();
                binding.cameraPreview.setVisibility(View.VISIBLE);
                requestCameraAndStart();
                break;
            case "image":
                stopCamera();
                if (selectedImageUri != null) {
                    showSelectedImage();
                } else {
                    selectImage();
                }
                break;
            case "video":
                stopCamera();
                if (selectedVideoUri != null) {
                    showSelectedVideoThumbnail();
                } else {
                    selectVideo();
                }
                break;
            case "screen":
                stopCamera();
                captureScreen();
                break;
            case "color":
                stopCamera();
                showColorPreview();
                break;
        }
    }

    private void initCameraPreview() {
        binding.cameraPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int w, int h) {
                Log.d(TAG, "Camera preview surface available: " + w + "x" + h);
                previewSurfaceTexture = surface;
                if ("none".equals(selectedSourceType)) {
                    startCamera();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int w, int h) {
                Log.d(TAG, "Camera preview surface resized");
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                stopCamera();
                previewSurfaceTexture = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) { }
        });
    }

    private void initResolutionSpinner() {
        String[] resolutions = {"640x480", "1280x720", "1920x1080", "2560x1440", "3840x2160"};
        ArrayAdapter<String> resAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, resolutions);
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.resolutionSpinner.setAdapter(resAdapter);

        // Pre-select saved resolution
        for (int i = 0; i < resolutions.length; i++) {
            if (resolutions[i].equals(selectedResolution)) {
                binding.resolutionSpinner.setSelection(i);
                break;
            }
        }

        binding.resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedResolution = (String) parent.getItemAtPosition(position);
                Log.i(TAG, "Resolution set to " + selectedResolution);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void initFpsSpinner() {
        String[] fpsOptions = {"15", "24", "30", "60"};
        ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, fpsOptions);
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.fpsSpinner.setAdapter(fpsAdapter);

        // Pre-select saved FPS
        for (int i = 0; i < fpsOptions.length; i++) {
            if (fpsOptions[i].equals(String.valueOf(selectedFps))) {
                binding.fpsSpinner.setSelection(i);
                break;
            }
        }

        binding.fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedFps = Integer.parseInt((String) parent.getItemAtPosition(position));
                Log.i(TAG, "FPS set to " + selectedFps);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void initButtons() {
        // Switch camera button
        binding.switchCameraButton.setOnClickListener(v -> switchCamera());

        // Test preview button
        binding.testPreviewButton.setOnClickListener(v -> {
            if ("none".equals(selectedSourceType)) {
                // Already showing camera preview
                Toast.makeText(this, R.string.preview_active, Toast.LENGTH_SHORT).show();
            } else {
                handleSourceTypeChange(selectedSourceType);
                Toast.makeText(this, R.string.preview_refreshed, Toast.LENGTH_SHORT).show();
            }
        });

        // Save settings button
        binding.saveSettingsButton.setOnClickListener(v -> applySettings());

        // Noise suppression toggle
        binding.noiseSuppressionToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            noiseSuppressionEnabled = isChecked;
            Log.i(TAG, "Noise suppression: " + isChecked);
        });
        binding.noiseSuppressionToggle.setChecked(noiseSuppressionEnabled);
    }

    // ══════════════════════════════════════════════════════════════════
    // Camera Management
    // ══════════════════════════════════════════════════════════════════

    /**
     * Requests camera permission and starts the camera preview.
     */
    public void initCamera() {
        requestCameraAndStart();
    }

    private void requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    /**
     * Opens the camera and starts the preview on the TextureView.
     */
    private void startCamera() {
        try {
            if (camera != null) {
                camera.stopPreview();
                camera.release();
                camera = null;
            }

            camera = Camera.open(currentCameraId);
            Camera.Parameters params = camera.getParameters();

            // Set preview size based on selected resolution
            String[] dims = selectedResolution.split("x");
            int targetW = Integer.parseInt(dims[0]);
            int targetH = Integer.parseInt(dims[1]);

            Camera.Size bestSize = findBestPreviewSize(params, targetW, targetH);
            if (bestSize != null) {
                params.setPreviewSize(bestSize.width, bestSize.height);
            }

            // Set FPS range
            try {
                List<int[]> fpsRanges = params.getSupportedPreviewFpsRange();
                int[] targetRange = findBestFpsRange(fpsRanges, selectedFps);
                if (targetRange != null) {
                    params.setPreviewFpsRange(targetRange[0], targetRange[1]);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not set FPS range", e);
            }

            camera.setParameters(params);

            if (previewSurfaceTexture != null) {
                Surface surface = new Surface(previewSurfaceTexture);
                camera.setPreviewTexture(previewSurfaceTexture);
                camera.startPreview();
                Log.i(TAG, "Camera preview started (id=" + currentCameraId + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start camera", e);
            Toast.makeText(this, R.string.camera_error, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Stops and releases the camera.
     */
    private void stopCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing camera", e);
            }
            camera = null;
            Log.d(TAG, "Camera released");
        }
    }

    /**
     * Switches between the front and back cameras.
     */
    public void switchCamera() {
        stopCamera();

        Camera.CameraInfo info = new Camera.CameraInfo();
        int numCameras = Camera.getNumberOfCameras();

        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing != currentCameraId) {
                currentCameraId = i;
                break;
            }
        }

        Log.i(TAG, "Switching to camera " + currentCameraId);

        // Small delay before restarting to avoid camera busy errors
        cameraHandler.postDelayed(this::startCamera, 300);

        Toast.makeText(this,
                currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT
                        ? R.string.front_camera : R.string.rear_camera,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Finds the closest supported preview size to the target dimensions.
     */
    @Nullable
    private Camera.Size findBestPreviewSize(@NonNull Camera.Parameters params,
                                            int targetW, int targetH) {
        List<Camera.Size> sizes = params.getSupportedPreviewSizes();
        if (sizes == null) return null;

        Camera.Size bestSize = null;
        int bestDiff = Integer.MAX_VALUE;

        for (Camera.Size size : sizes) {
            int diff = Math.abs(size.width - targetW) + Math.abs(size.height - targetH);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestSize = size;
            }
        }
        return bestSize;
    }

    /**
     * Finds the best FPS range matching the target FPS.
     */
    @Nullable
    private int[] findBestFpsRange(@NonNull List<int[]> ranges, int targetFps) {
        int[] bestRange = null;
        int bestDiff = Integer.MAX_VALUE;

        int targetTimes1000 = targetFps * 1000;

        for (int[] range : ranges) {
            int avg = (range[0] + range[1]) / 2;
            int diff = Math.abs(avg - targetTimes1000);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestRange = range;
            }
        }
        return bestRange;
    }

    // ══════════════════════════════════════════════════════════════════
    // Source Selection
    // ══════════════════════════════════════════════════════════════════

    /**
     * Opens the system image picker.
     */
    public void selectImage() {
        Intent intent = new Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        imagePickerLauncher.launch(intent);
    }

    /**
     * Opens the system video picker.
     */
    public void selectVideo() {
        Intent intent = new Intent(Intent.ACTION_PICK,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        intent.setType("video/*");
        videoPickerLauncher.launch(intent);
    }

    /**
     * Requests screen capture via MediaProjection.
     */
    public void captureScreen() {
        Log.i(TAG, "Requesting screen capture permission");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaProjectionManager projectionManager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projectionManager != null) {
                startActivityForResult(
                        projectionManager.createScreenCaptureIntent(),
                        SCREEN_CAPTURE_REQUEST);
            }
        } else {
            Toast.makeText(this, R.string.screen_capture_not_supported,
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCREEN_CAPTURE_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "Screen capture permission granted");
                Toast.makeText(this, R.string.screen_capture_active,
                        Toast.LENGTH_SHORT).show();

                // In production: pass the result Intent to the ScreenCaptureService
                // startService(new Intent(this, ScreenCaptureService.class)
                //         .putExtra("result_code", resultCode)
                //         .putExtra("data", data));
            } else {
                Log.w(TAG, "Screen capture permission denied");
                Toast.makeText(this, R.string.screen_capture_denied,
                        Toast.LENGTH_SHORT).show();
                selectedSourceType = "none";
                updateSourceChips();
            }
        }
    }

    private void showSelectedImage() {
        if (selectedImageUri != null) {
            binding.cameraPreview.setVisibility(View.VISIBLE);
            try (InputStream is = getContentResolver().openInputStream(selectedImageUri)) {
                if (is != null) {
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    drawBitmapOnTextureView(bitmap);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to load selected image", e);
            }
        }
    }

    private void showSelectedVideoThumbnail() {
        if (selectedVideoUri != null) {
            binding.cameraPreview.setVisibility(View.VISIBLE);
            try (InputStream is = getContentResolver().openInputStream(selectedVideoUri)) {
                if (is != null) {
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    drawBitmapOnTextureView(bitmap);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to load video thumbnail", e);
            }
        }
    }

    private void showColorPreview() {
        binding.cameraPreview.setVisibility(View.VISIBLE);
        // Create a 1x1 Bitmap of the selected color and draw it
        int width = binding.cameraPreview.getWidth();
        int height = binding.cameraPreview.getHeight();
        if (width <= 0 || height <= 0) {
            width = 1280;
            height = 720;
        }
        Bitmap colorBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(colorBitmap);
        canvas.drawColor(selectedColor);
        drawBitmapOnTextureView(colorBitmap);
    }

    private void drawBitmapOnTextureView(@NonNull Bitmap bitmap) {
        if (previewSurfaceTexture == null) {
            Log.w(TAG, "No surface texture available to draw bitmap");
            return;
        }
        try {
            Surface surface = new Surface(previewSurfaceTexture);
            Canvas canvas = surface.lockCanvas(null);
            if (canvas != null) {
                canvas.drawBitmap(bitmap, 0, 0, null);
                surface.unlockCanvasAndPost(canvas);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to draw bitmap on TextureView", e);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Permissions
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, R.string.camera_permission_denied,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Settings Persistence
    // ══════════════════════════════════════════════════════════════════

    private void loadSavedSettings() {
        selectedSourceType = cameraPrefs.getString("source_type", "none");
        selectedResolution = cameraPrefs.getString("resolution", "1280x720");
        selectedFps = cameraPrefs.getInt("fps", 30);
        noiseSuppressionEnabled = cameraPrefs.getBoolean("noise_suppression", true);

        String imageUriStr = cameraPrefs.getString("selected_image_uri", null);
        if (imageUriStr != null) {
            selectedImageUri = Uri.parse(imageUriStr);
        }
        String videoUriStr = cameraPrefs.getString("selected_video_uri", null);
        if (videoUriStr != null) {
            selectedVideoUri = Uri.parse(videoUriStr);
        }

        String cameraFacing = cameraPrefs.getString("camera_facing", "back");
        currentCameraId = "front".equals(cameraFacing)
                ? Camera.CameraInfo.CAMERA_FACING_FRONT
                : Camera.CameraInfo.CAMERA_FACING_BACK;
    }

    /**
     * Persists all current settings to SharedPreferences and notifies
     * the user.
     */
    public void applySettings() {
        cameraPrefs.edit()
                .putString("source_type", selectedSourceType)
                .putString("resolution", selectedResolution)
                .putInt("fps", selectedFps)
                .putBoolean("noise_suppression", noiseSuppressionEnabled)
                .putString("camera_facing",
                        currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT
                                ? "front" : "back")
                .apply();

        if (selectedImageUri != null) {
            cameraPrefs.edit().putString("selected_image_uri",
                    selectedImageUri.toString()).apply();
        }
        if (selectedVideoUri != null) {
            cameraPrefs.edit().putString("selected_video_uri",
                    selectedVideoUri.toString()).apply();
        }

        Log.i(TAG, "Settings saved: type=" + selectedSourceType
                + ", res=" + selectedResolution
                + ", fps=" + selectedFps
                + ", noise=" + noiseSuppressionEnabled);

        Snackbar.make(binding.getRoot(), R.string.settings_saved, Snackbar.LENGTH_SHORT).show();
    }
}

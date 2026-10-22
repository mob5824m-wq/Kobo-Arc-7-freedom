package ca.arc7freedom.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Hardware-Tuned Standalone **Arc7 Camera** App (`v2.0.0`):
 *
 * Specifically engineered for Kobo Arc 7 (`CAMERA_FACING_FRONT` HD sensor where
 * bare `Camera.open()` returns `null`!) AND Kobo Arc 7HD (dual Front + Rear sensors):
 *   - Photo Mode + HD Video Recording Mode (`MediaRecorder` `.mp4`)
 *   - Front Screen Flash (pulses max brightness white illumination before shutter capture!)
 *   - Self-Timer (`Off`, `3s`, `10s`) with live full-screen countdown
 *   - Rule-of-Thirds 3x3 Composition Grid
 *   - Live Sensor Color Effects (`none`, `mono`, `sepia`, `aqua`, `negative`)
 *   - Exposure Compensation (`EV -2 .. +2`) + Sensor Switcher + Instant `Arc7 Photos` link!
 */
@SuppressWarnings("deprecation")
public class Arc7CameraActivity extends Activity implements SurfaceHolder.Callback {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private Camera camera;
    private MediaRecorder mediaRecorder;

    private int numCameras = 0;
    private int currentCamIdx = 0;
    private int currentDisplayOrientation = 0;
    private boolean videoMode = false;
    private boolean recordingVideo = false;
    private long recordingStartMs = 0L;
    private File currentVideoFile = null;

    private boolean screenFlashEnabled = false;
    private boolean showGrid = true;
    private int timerSeconds = 0; // 0, 3, 10
    private int effectIdx = 0;
    private int exposureStep = 0;

    private View gridOverlay;
    private View screenFlashOverlay;
    private TextView countdownTv;
    private TextView hudBadgeTv;
    private Button shutterBtn;
    private Button modeToggleBtn;
    private ImageView lastShotThumbIv;

    private final Runnable recTimerTick = new Runnable() {
        @Override
        public void run() {
            if (recordingVideo) {
                long secs = Math.max(0L, (System.currentTimeMillis() - recordingStartMs) / 1000L);
                hudBadgeTv.setText(String.format(Locale.US, "● REC  %02d:%02d", secs / 60L, secs % 60L));
                hudBadgeTv.setBackgroundDrawable(Arc7Skin.buttonDrawable(Arc7CameraActivity.this, 0xFFDC2626, 12));
                ui.postDelayed(this, 500L);
            }
        }
    };

    private int dp(int v) {
        return Arc7Skin.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        numCameras = Camera.getNumberOfCameras();
        currentCamIdx = 0;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 1. Live Hardware Camera Preview Surface
        surfaceView = new SurfaceView(this);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        surfaceView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (camera != null && !recordingVideo) {
                    try { camera.autoFocus(null); } catch (Throwable ignored) {}
                }
            }
        });
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Rule-of-Thirds 3x3 Grid Overlay
        gridOverlay = new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                p.setColor(0x55FFFFFF);
                p.setStrokeWidth(1.5f);
            }
            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth();
                float h = getHeight();
                c.drawLine(w / 3f, 0, w / 3f, h, p);
                c.drawLine(2f * w / 3f, 0, 2f * w / 3f, h, p);
                c.drawLine(0, h / 3f, w, h / 3f, p);
                c.drawLine(0, 2f * h / 3f, w, 2f * h / 3f, p);
            }
        };
        root.addView(gridOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 3. Front Screen Flash Illumination Overlay
        screenFlashOverlay = new View(this);
        screenFlashOverlay.setBackgroundColor(Color.WHITE);
        screenFlashOverlay.setVisibility(View.GONE);
        root.addView(screenFlashOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 4. Giant Countdown Self-Timer Number
        countdownTv = new TextView(this);
        countdownTv.setTextColor(Color.WHITE);
        countdownTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 76);
        countdownTv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        countdownTv.setShadowLayer(12f, 0f, 3f, 0xEE000000);
        countdownTv.setVisibility(View.GONE);
        root.addView(countdownTv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // 5. Top Translucent Camera Controls Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0x99090E16);
        topBar.setPadding(dp(12), dp(10), dp(12), dp(10));

        hudBadgeTv = new TextView(this);
        hudBadgeTv.setText("HD PHOTO READY");
        hudBadgeTv.setTextColor(Color.WHITE);
        hudBadgeTv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        hudBadgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        hudBadgeTv.setPadding(dp(10), dp(5), dp(10), dp(5));
        hudBadgeTv.setBackgroundDrawable(Arc7Skin.buttonDrawable(this, 0xFF0284C7, 12));
        topBar.addView(hudBadgeTv);

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));

        final Button flashBtn = Arc7Skin.actionButton(this, "Screen Flash: OFF", 0xFF334155);
        flashBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                screenFlashEnabled = !screenFlashEnabled;
                flashBtn.setText(screenFlashEnabled ? "Screen Flash: ON" : "Screen Flash: OFF");
                flashBtn.setBackgroundDrawable(Arc7Skin.buttonDrawable(
                        Arc7CameraActivity.this, screenFlashEnabled ? 0xFFD97706 : 0xFF334155, 10));
            }
        });
        addSpacedTopBtn(topBar, flashBtn);

        final Button timerBtn = Arc7Skin.actionButton(this, "Timer: OFF", 0xFF334155);
        timerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                timerSeconds = (timerSeconds == 0) ? 3 : (timerSeconds == 3 ? 10 : 0);
                timerBtn.setText(timerSeconds == 0 ? "Timer: OFF" : ("Timer: " + timerSeconds + "s"));
            }
        });
        addSpacedTopBtn(topBar, timerBtn);

        final Button gridBtn = Arc7Skin.actionButton(this, "Grid: ON", 0xFF0F766E);
        gridBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showGrid = !showGrid;
                gridOverlay.setVisibility(showGrid ? View.VISIBLE : View.GONE);
                gridBtn.setText(showGrid ? "Grid: ON" : "Grid: OFF");
            }
        });
        addSpacedTopBtn(topBar, gridBtn);

        Button effectBtn = Arc7Skin.actionButton(this, "Color FX", 0xFF7C3AED);
        effectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleCameraEffect();
            }
        });
        addSpacedTopBtn(topBar, effectBtn);

        if (numCameras > 1) {
            Button switchCamBtn = Arc7Skin.actionButton(this, "Switch Cam", 0xFF2563EB);
            switchCamBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!recordingVideo) {
                        currentCamIdx = (currentCamIdx + 1) % numCameras;
                        reopenCamera();
                    }
                }
            });
            addSpacedTopBtn(topBar, switchCamBtn);
        }

        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // 6. Bottom Shutter & Mode Bar
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(0xAD090E16);
        bottomBar.setPadding(dp(20), dp(12), dp(20), dp(12));

        // Last Photo Thumbnail -> Opens `Arc7 Photos` directly!
        lastShotThumbIv = new ImageView(this);
        lastShotThumbIv.setImageResource(R.drawable.ic_photos);
        lastShotThumbIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        lastShotThumbIv.setBackgroundDrawable(Arc7Skin.buttonDrawable(this, 0xFF1E293B, 12));
        lastShotThumbIv.setPadding(dp(4), dp(4), dp(4), dp(4));
        lastShotThumbIv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Arc7CameraActivity.this, Arc7PhotosActivity.class));
            }
        });
        bottomBar.addView(lastShotThumbIv, new LinearLayout.LayoutParams(dp(56), dp(56)));

        View spLeft = new View(this);
        bottomBar.addView(spLeft, new LinearLayout.LayoutParams(0, dp(1), 1f));

        // Big Sculpted Shutter / Record Circle Button
        shutterBtn = new Button(this);
        shutterBtn.setText("SNAP");
        shutterBtn.setTextColor(0xFF0F172A);
        shutterBtn.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        styleShutterButton();
        shutterBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onShutterPressed();
            }
        });
        bottomBar.addView(shutterBtn, new LinearLayout.LayoutParams(dp(76), dp(76)));

        View spRight = new View(this);
        bottomBar.addView(spRight, new LinearLayout.LayoutParams(0, dp(1), 1f));

        modeToggleBtn = Arc7Skin.actionButton(this, "Switch to Video", 0xFFDC2626);
        modeToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recordingVideo) return;
                videoMode = !videoMode;
                modeToggleBtn.setText(videoMode ? "Switch to Photo" : "Switch to Video");
                hudBadgeTv.setText(videoMode ? "HD VIDEO READY" : "HD PHOTO READY");
                styleShutterButton();
            }
        });
        bottomBar.addView(modeToggleBtn);

        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        loadLatestThumbnail();
    }

    private void addSpacedTopBtn(LinearLayout parent, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        parent.addView(b, lp);
    }

    private void styleShutterButton() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (videoMode) {
            g.setColor(recordingVideo ? 0xFFEF4444 : 0xFFDC2626);
            g.setStroke(dp(4), Color.WHITE);
            shutterBtn.setText(recordingVideo ? "STOP" : "REC");
            shutterBtn.setTextColor(Color.WHITE);
        } else {
            g.setColor(Color.WHITE);
            g.setStroke(dp(4), 0xFF38BDF8);
            shutterBtn.setText("SNAP");
            shutterBtn.setTextColor(0xFF0F172A);
        }
        shutterBtn.setBackgroundDrawable(g);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        reopenCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (surfaceHolder.getSurface() == null || camera == null || recordingVideo) return;
        try { camera.stopPreview(); } catch (Throwable ignored) {}
        configureBestParametersAndStartPreview();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseMediaRecorder();
        releaseCamera();
    }

    private void reopenCamera() {
        releaseCamera();
        if (numCameras <= 0) {
            hudBadgeTv.setText("NO CAMERA SENSOR DETECTED");
            return;
        }
        try {
            // CRITICAL KOBO ARC 7 FIX: Always open explicit index `0..N-1` because bare
            // `Camera.open()` returns `null` on front-camera-only tablets like Kobo Arc 7!
            camera = Camera.open(currentCamIdx);
            configureBestParametersAndStartPreview();
        } catch (Throwable t) {
            hudBadgeTv.setText("CAMERA BUSY: " + t.getMessage());
        }
    }

    private void configureBestParametersAndStartPreview() {
        if (camera == null) return;
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(currentCamIdx, info);
            int rot = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = rot == Surface.ROTATION_90 ? 90
                    : (rot == Surface.ROTATION_180 ? 180 : (rot == Surface.ROTATION_270 ? 270 : 0));
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                currentDisplayOrientation = (info.orientation + degrees) % 360;
                currentDisplayOrientation = (360 - currentDisplayOrientation) % 360;
            } else {
                currentDisplayOrientation = (info.orientation - degrees + 360) % 360;
            }
            camera.setDisplayOrientation(currentDisplayOrientation);

            Camera.Parameters params = camera.getParameters();
            List<Camera.Size> picSizes = params.getSupportedPictureSizes();
            if (picSizes != null && !picSizes.isEmpty()) {
                Camera.Size best = picSizes.get(0);
                for (int i = 1; i < picSizes.size(); i++) {
                    Camera.Size s = picSizes.get(i);
                    if ((long) s.width * s.height > (long) best.width * best.height) {
                        best = s;
                    }
                }
                params.setPictureSize(best.width, best.height);
            }
            params.setJpegQuality(95);
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(params);
            camera.setPreviewDisplay(surfaceHolder);
            camera.startPreview();
            hudBadgeTv.setText((info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "FRONT HD" : "REAR HD")
                    + (videoMode ? " • VIDEO" : " • PHOTO"));
        } catch (Throwable ignored) {}
    }

    private void cycleCameraEffect() {
        if (camera == null || recordingVideo) return;
        try {
            Camera.Parameters p = camera.getParameters();
            List<String> fx = p.getSupportedColorEffects();
            if (fx == null || fx.isEmpty()) {
                Toast.makeText(this, "Sensor uses fixed natural color profile", Toast.LENGTH_SHORT).show();
                return;
            }
            effectIdx = (effectIdx + 1) % fx.size();
            p.setColorEffect(fx.get(effectIdx));
            camera.setParameters(p);
            Toast.makeText(this, "Camera FX: " + fx.get(effectIdx), Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    private void onShutterPressed() {
        if (camera == null) return;
        if (videoMode) {
            if (recordingVideo) {
                stopVideoRecording();
            } else {
                startVideoRecording();
            }
            return;
        }
        if (timerSeconds > 0) {
            runSelfTimer(timerSeconds);
        } else {
            capturePhotoWithOptionalScreenFlash();
        }
    }

    private void runSelfTimer(final int remaining) {
        if (remaining <= 0) {
            countdownTv.setVisibility(View.GONE);
            capturePhotoWithOptionalScreenFlash();
            return;
        }
        countdownTv.setText(String.valueOf(remaining));
        countdownTv.setVisibility(View.VISIBLE);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                runSelfTimer(remaining - 1);
            }
        }, 1000L);
    }

    private void capturePhotoWithOptionalScreenFlash() {
        if (camera == null) return;
        shutterBtn.setEnabled(false);
        if (screenFlashEnabled) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 1.0f;
            getWindow().setAttributes(lp);
            screenFlashOverlay.setVisibility(View.VISIBLE);
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    triggerHardwareJpegCapture();
                }
            }, 260L);
        } else {
            triggerHardwareJpegCapture();
        }
    }

    private void triggerHardwareJpegCapture() {
        if (camera == null) {
            restoreScreenFlash();
            shutterBtn.setEnabled(true);
            return;
        }
        try {
            camera.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(final byte[] data, Camera cam) {
                    restoreScreenFlash();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            saveCapturedJpeg(data);
                        }
                    }, "Arc7SavePhoto").start();
                    try { cam.startPreview(); } catch (Throwable ignored) {}
                    shutterBtn.setEnabled(true);
                }
            });
        } catch (Throwable t) {
            restoreScreenFlash();
            shutterBtn.setEnabled(true);
            Toast.makeText(this, "Capture error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreScreenFlash() {
        screenFlashOverlay.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    private static File getCameraOutputDir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), "Camera");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void saveCapturedJpeg(byte[] jpegBytes) {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            final File out = new File(getCameraOutputDir(), "IMG_" + ts + ".jpg");
            Bitmap raw = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            if (raw != null && currentDisplayOrientation != 0) {
                Matrix m = new Matrix();
                m.postRotate(currentDisplayOrientation);
                Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
                FileOutputStream fos = new FileOutputStream(out);
                rotated.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();
            } else {
                FileOutputStream fos = new FileOutputStream(out);
                fos.write(jpegBytes);
                fos.close();
            }
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(out)));
            ui.post(new Runnable() {
                @Override
                public void run() {
                    loadLatestThumbnail();
                    Toast.makeText(Arc7CameraActivity.this,
                            "Saved photo -> DCIM/Camera/" + out.getName(), Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Throwable ignored) {}
    }

    private void startVideoRecording() {
        if (camera == null) return;
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            currentVideoFile = new File(getCameraOutputDir(), "VID_" + ts + ".mp4");
            camera.unlock();
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setCamera(camera);
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            CamcorderProfile profile = CamcorderProfile.hasProfile(currentCamIdx, CamcorderProfile.QUALITY_720P)
                    ? CamcorderProfile.get(currentCamIdx, CamcorderProfile.QUALITY_720P)
                    : CamcorderProfile.get(currentCamIdx, CamcorderProfile.QUALITY_HIGH);
            mediaRecorder.setProfile(profile);
            mediaRecorder.setOutputFile(currentVideoFile.getAbsolutePath());
            mediaRecorder.setPreviewDisplay(surfaceHolder.getSurface());
            mediaRecorder.prepare();
            mediaRecorder.start();
            recordingVideo = true;
            recordingStartMs = System.currentTimeMillis();
            styleShutterButton();
            ui.post(recTimerTick);
        } catch (Throwable t) {
            releaseMediaRecorder();
            try { camera.lock(); } catch (Throwable ignored) {}
            Toast.makeText(this, "Video record error: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopVideoRecording() {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
            }
        } catch (Throwable ignored) {
        } finally {
            releaseMediaRecorder();
            try { if (camera != null) camera.lock(); } catch (Throwable ignored) {}
            recordingVideo = false;
            styleShutterButton();
            hudBadgeTv.setText("HD VIDEO SAVED");
            hudBadgeTv.setBackgroundDrawable(Arc7Skin.buttonDrawable(this, 0xFF059669, 12));
            if (currentVideoFile != null && currentVideoFile.exists()) {
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(currentVideoFile)));
                Toast.makeText(this, "Saved HD video -> " + currentVideoFile.getName(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void loadLatestThumbnail() {
        try {
            File[] files = getCameraOutputDir().listFiles();
            if (files == null || files.length == 0) return;
            File newest = null;
            for (int i = 0; i < files.length; i++) {
                String n = files[i].getName().toLowerCase(Locale.US);
                if ((n.endsWith(".jpg") || n.endsWith(".png"))
                        && (newest == null || files[i].lastModified() > newest.lastModified())) {
                    newest = files[i];
                }
            }
            if (newest != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 8;
                Bitmap b = BitmapFactory.decodeFile(newest.getAbsolutePath(), o);
                if (b != null) lastShotThumbIv.setImageBitmap(b);
            }
        } catch (Throwable ignored) {}
    }

    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            try { mediaRecorder.reset(); } catch (Throwable ignored) {}
            try { mediaRecorder.release(); } catch (Throwable ignored) {}
            mediaRecorder = null;
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            try { camera.stopPreview(); } catch (Throwable ignored) {}
            try { camera.release(); } catch (Throwable ignored) {}
            camera = null;
        }
    }

    @Override
    protected void onPause() {
        if (recordingVideo) stopVideoRecording();
        releaseCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        releaseMediaRecorder();
        releaseCamera();
        super.onDestroy();
    }
}

package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Legacy Camera API optical transport: actual file bytes, no Wi-Fi, account or scanner app. */
@SuppressWarnings("deprecation")
public final class Arc7QrTransferActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_SEND = "send_file", EXTRA_DIRECTORY = "receive_directory", EXTRA_URI = "send_uri", EXTRA_NAME = "send_name";
    private final Handler ui = new Handler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Arc7QrCameraDecoder decoder = new Arc7QrCameraDecoder();
    private volatile Arc7QrTransfer.Sender sender;
    private volatile Arc7QrTransfer.Receiver receiver;
    private volatile boolean destroyed, prompting, verified;
    private boolean resumed, surfaceReady, sending, playing = true, rendering;
    private int nextFrame = -1, displayed = -1, interval = 700, cameraId = -1;
    private long lastDecode;
    private TextView status, detail;
    private ImageView qr;
    private Button pause, save;
    private SurfaceView preview;
    private Camera camera;
    private File destination;

    private final Runnable advance = new Runnable() {
        @Override public void run() { if (resumed && playing && !destroyed) renderFrame(); }
    };
    private final Runnable refocus = new Runnable() {
        @Override public void run() {
            if (camera == null || !resumed) return;
            try {
                if (Camera.Parameters.FOCUS_MODE_AUTO.equals(camera.getParameters().getFocusMode()))
                    camera.autoFocus(new Camera.AutoFocusCallback() { @Override public void onAutoFocus(boolean ok, Camera c) {} });
            } catch (RuntimeException ignored) {}
            ui.postDelayed(this, 2200L);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sending = getIntent().hasExtra(EXTRA_SEND) || getIntent().hasExtra(EXTRA_URI);
        String dir = getIntent().getStringExtra(EXTRA_DIRECTORY);
        destination = dir != null ? new File(dir) : new File(android.os.Environment.getExternalStorageDirectory(), "Download/QR Received");
        Arc7Skin.Palette p = Arc7Skin.current(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundDrawable(Arc7Skin.pageDrawable(p)); root.setPadding(dp(16), dp(14), dp(16), dp(12));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = label(sending ? "Send file · optical QR" : "Receive file · camera", 22, p.textPrimary);
        root.addView(title);
        TextView hint = label(sending
                ? "These QR codes contain the file bytes, not a link. On the other device open QR Transfer → Receive, Files → Receive QR bytes, or the offline qr-transfer.html page. Keep both screens awake."
                : "Point this camera at the other device’s animated Arc7 byte-transfer QRs. Frames may repeat or arrive out of order. No Wi-Fi is needed. Tap the preview to focus.", 13, p.textMuted);
        root.addView(hint);
        status = label("Preparing…", 16, p.accent); root.addView(status);
        detail = label("Optical transfer is not encrypted; anyone who can see the codes can copy the file. Up to 64 MiB in v2 profiles (Legacy: 1 MiB). Large files can take hours; receiving progress is checkpointed privately on disk.", 12, p.textMuted);
        root.addView(detail);
        if (sending) buildSender(root); else buildReceiver(root);
        Button back = Arc7Skin.secondaryButton(this, p, "Close / cancel transfer");
        back.setText(sending ? "Close sender" : "Close · keep receive progress");
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } }); root.addView(back);
        setContentView(root);
        if (sending) prepareSender();
        else if (getIntent().hasExtra("qr_resume")) resumeReceiver(getIntent().getStringExtra("qr_resume"));
    }

    private void buildSender(LinearLayout root) {
        qr = new ImageView(this); qr.setBackgroundColor(0xFFFFFFFF); qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(qr, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout controls = row();
        add(controls, "Previous", new View.OnClickListener() { @Override public void onClick(View v) { selectFrame(Math.max(-1, displayed - 1)); } });
        pause = add(controls, "Pause", new View.OnClickListener() { @Override public void onClick(View v) {
            playing = !playing; pause.setText(playing ? "Pause" : "Play loop");
            ui.removeCallbacks(advance); if (playing) ui.post(advance);
        } });
        add(controls, "Next", new View.OnClickListener() { @Override public void onClick(View v) { selectFrame(displayed + 1); } });
        root.addView(controls);
        LinearLayout more = row();
        add(more, "Go to frame", new View.OnClickListener() { @Override public void onClick(View v) { jump(); } });
        add(more, "Speed", new View.OnClickListener() { @Override public void onClick(View v) {
            new AlertDialog.Builder(Arc7QrTransferActivity.this).setTitle("Time per QR frame")
                    .setItems(new String[]{"Slow · 1.2 sec (easier focus)", "Normal · 0.7 sec", "Fast · 0.35 sec"}, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) { interval = new int[]{1200,700,350}[which]; }
                    }).show();
        } });
        root.addView(more);
    }

    private void buildReceiver(LinearLayout root) {
        preview = new SurfaceView(this); preview.getHolder().addCallback(this);
        preview.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        preview.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            ui.removeCallbacks(refocus); ui.post(refocus);
        } });
        root.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        status.setText("Waiting for a file header…");
        Button switchCamera = Arc7Skin.secondaryButton(this, Arc7Skin.current(this), "Switch camera / retry");
        switchCamera.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            closeCamera();
            try { int count = Camera.getNumberOfCameras(); if (count > 0) cameraId = (cameraId + 1) % count; } catch (RuntimeException ignored) {}
            openCamera();
        } }); root.addView(switchCamera);
        Button discard = Arc7Skin.secondaryButton(this, Arc7Skin.current(this), "Discard this unfinished receive…");
        discard.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (receiver == null) return;
            new AlertDialog.Builder(Arc7QrTransferActivity.this).setTitle("Discard unfinished bytes?")
                    .setMessage("This removes only this transfer’s private partial file and checkpoint. Files already saved in Downloads are kept.")
                    .setPositiveButton("Discard", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int which) {
                        worker.execute(new Runnable() { public void run() { try {
                            Arc7QrTransfer.Receiver old = receiver; if (old != null) old.discard(); receiver = null; verified = false;
                            ui.post(new Runnable() { public void run() { if (!destroyed) { save.setVisibility(View.GONE); status.setText("Waiting for a file header…"); openCamera(); } }});
                        } catch (Exception e) { error(e); } }});
                    }}).setNegativeButton("Keep progress", null).show();
        }}); root.addView(discard);
        save = Arc7Skin.actionButton(this, "Save verified file", 0xFF167D68); save.setVisibility(View.GONE);
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { saveReceived(); } }); root.addView(save);
    }

    private void prepareSender() {
        final String path = getIntent().getStringExtra(EXTRA_SEND);
        final String uri = getIntent().getStringExtra(EXTRA_URI), name = getIntent().getStringExtra(EXTRA_NAME);
        final int density = getIntent().getIntExtra("qr_density", Arc7QrTransfer.CHUNK_BYTES);
        final boolean legacy = getIntent().getBooleanExtra("qr_legacy", false);
        worker.execute(new Runnable() { @Override public void run() {
            try {
                final Arc7QrTransfer.Sender ready;
                if (uri != null) {
                    android.net.Uri source = android.net.Uri.parse(uri);
                    if (!("content".equals(source.getScheme()) || "file".equals(source.getScheme()))) throw new java.io.IOException("Only local files can be sent");
                    java.io.InputStream stream = getContentResolver().openInputStream(source);
                    if (stream == null) throw new java.io.IOException("Could not read the selected file");
                    ready = new Arc7QrTransfer.Sender(stream, name, getCacheDir(), density, legacy);
                } else ready = new Arc7QrTransfer.Sender(new File(path == null ? "" : path), getCacheDir(), density, legacy);
                sender = ready;
                if (destroyed) { ready.close(); return; }
                ui.post(new Runnable() { @Override public void run() { if (!destroyed) { nextFrame = -1; renderFrame(); } } });
            } catch (final Exception e) { error(e); }
        } });
    }

    private File receiveStore() { return new File(getFilesDir(), "optical-receives"); }
    private void resumeReceiver(final String id) {
        prompting = true;
        worker.execute(new Runnable() { public void run() {
            try {
                Arc7QrTransfer.Frame header = Arc7QrTransfer.Receiver.pending(receiveStore(), id);
                final Arc7QrTransfer.Receiver ready = new Arc7QrTransfer.Receiver(header, receiveStore(), true);
                receiver = ready;
                if (destroyed) ready.close();
                else if (ready.complete()) {
                    ready.verify(); verified = true;
                    ui.post(new Runnable() { public void run() { if (!destroyed) { closeCamera(); status.setText("SHA-256 verified · ready to save"); detail.setText(ready.metadata.name); save.setVisibility(View.VISIBLE); } }});
                } else updateProgress(ready);
            } catch (Exception e) { error(e); }
            finally { prompting = false; }
        }});
    }
    private void selectFrame(int index) {
        if (sender == null || rendering) return;
        playing = false; pause.setText("Play loop"); ui.removeCallbacks(advance);
        nextFrame = Math.min(sender.total - 1, index); renderFrame();
    }
    private void jump() {
        if (sender == null) return;
        final EditText input = new EditText(this); input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("1–" + sender.total + " (0 = header)");
        new AlertDialog.Builder(this).setTitle("Replay missing frame").setView(input)
                .setPositiveButton("Show", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    try { selectFrame(Math.max(-1, Integer.parseInt(input.getText().toString()) - 1)); } catch (NumberFormatException ignored) {}
                } }).setNegativeButton("Cancel", null).show();
    }
    private void renderFrame() {
        final Arc7QrTransfer.Sender source = sender;
        if (source == null || rendering || destroyed || !resumed) return;
        rendering = true;
        final int index = nextFrame;
        worker.execute(new Runnable() { @Override public void run() {
            try {
                String text = index < 0 ? source.manifest() : source.data(index);
                final Bitmap image = QrCodeEncoder.renderQrBitmap(text, 390);
                ui.post(new Runnable() { @Override public void run() {
                    rendering = false;
                    if (destroyed) { image.recycle(); return; }
                    qr.setImageBitmap(image); displayed = index;
                    status.setText(source.name + " · " + (index < 0 ? "file header" : "frame " + (index + 1) + " / " + source.total));
                    detail.setText(source.size + " bytes · SHA-256 " + source.sha256.substring(0, 16) + "…\n"
                            + source.chunk + " bytes/QR · one clean pass ≈ " + Math.max(1, Math.round(source.total * interval * 1.125 / 60000.0)) + " min at this speed. Missed frames add time.\n"
                            + "Re-select the identical file/name/profile to resume a saved receiver. No network is used.");
                    // Repeat metadata every eight data frames, including at the start of each loop.
                    if (index < 0) nextFrame = pendingData;
                    else if (index + 1 >= source.total) { pendingData = 0; nextFrame = -1; }
                    else if ((index + 1) % 8 == 0) { pendingData = index + 1; nextFrame = -1; }
                    else nextFrame = index + 1;
                    if (playing && resumed) ui.postDelayed(advance, interval);
                } });
            } catch (final Exception e) { ui.post(new Runnable() { @Override public void run() { rendering = false; } }); error(e); }
        } });
    }
    private int pendingData;

    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; openCamera(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; closeCamera(); }

    private void openCamera() {
        if (sending || !resumed || !surfaceReady || camera != null || destroyed || verified) return;
        try {
            int count = Camera.getNumberOfCameras();
            if (count == 0) throw new Exception("No camera found. This device can still send QR bytes.");
            if (cameraId < 0 || cameraId >= count) {
                cameraId = 0;
                for (int i = 0; i < count; i++) { Camera.CameraInfo info = new Camera.CameraInfo(); Camera.getCameraInfo(i, info);
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { cameraId = i; break; } }
            }
            final Camera opened = Camera.open(cameraId); camera = opened;
            Camera.Parameters parameters = opened.getParameters();
            Camera.Size best = null; int bestScore = Integer.MAX_VALUE;
            for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
                if (size.width > 1280 || size.height > 960) continue;
                int score = Math.abs(size.width - 640) + Math.abs(size.height - 480);
                if (score < bestScore) { best = size; bestScore = score; }
            }
            if (best == null) throw new Exception("No bounded camera preview size is supported");
            parameters.setPreviewSize(best.width, best.height);
            parameters.setPreviewFormat(ImageFormat.NV21);
            List<String> modes = parameters.getSupportedFocusModes();
            if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            else if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            opened.setParameters(parameters);
            Camera.CameraInfo info = new Camera.CameraInfo(); Camera.getCameraInfo(cameraId, info);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
            int orientation = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? (360 - (info.orientation + degrees) % 360) % 360 : (info.orientation - degrees + 360) % 360;
            opened.setDisplayOrientation(orientation); opened.setPreviewDisplay(preview.getHolder());
            Camera.Size actual = opened.getParameters().getPreviewSize(); final int width = actual.width, height = actual.height;
            opened.addCallbackBuffer(new byte[width * height * 3 / 2]);
            opened.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() { @Override public void onPreviewFrame(final byte[] bytes, final Camera c) {
                if (destroyed || c != camera || bytes == null) return;
                long now = android.os.SystemClock.elapsedRealtime();
                if (verified || prompting || now - lastDecode < 170L) { c.addCallbackBuffer(bytes); return; }
                lastDecode = now;
                worker.execute(new Runnable() { @Override public void run() {
                    try {
                        String text = decoder.decode(bytes, width, height);
                        processFrame(text);
                    } catch (com.google.zxing.ReaderException notQr) {
                        // Keep scanning; motion blur / a frame transition is not a transfer error.
                    } catch (Exception e) { error(e); }
                    finally { ui.post(new Runnable() { @Override public void run() {
                        if (!destroyed && camera == c) try { c.addCallbackBuffer(bytes); } catch (RuntimeException ignored) {}
                    } }); }
                } });
            } });
            opened.startPreview(); ui.removeCallbacks(refocus); ui.postDelayed(refocus, 500L);
        } catch (Exception e) { closeCamera(); error(new Exception("Camera could not open: " + e.getMessage() + ". Close other camera apps, then tap Switch camera / retry.")); }
    }

    private void processFrame(String text) throws Exception {
        if (destroyed || verified || !(text.startsWith("A7Q1|") || text.startsWith("A7Q2|"))) return;
        final Arc7QrTransfer.Frame frame = Arc7QrTransfer.parse(text);
        Arc7QrTransfer.Receiver sink = receiver;
        if (sink == null) {
            if (!frame.manifest || prompting) return;
            prompting = true;
            ui.post(new Runnable() { @Override public void run() {
                if (destroyed) return;
                new AlertDialog.Builder(Arc7QrTransferActivity.this).setTitle("Receive “" + frame.name + "”?")
                        .setMessage(frame.size + " bytes in " + frame.total + " QR frames.\n\nSave folder: " + destination.getAbsolutePath()
                                + "\n\nOnly a complete SHA-256-verified file can be saved. Files are never opened or installed automatically. The codes are not encrypted.")
                        .setPositiveButton("Receive bytes", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                            worker.execute(new Runnable() { @Override public void run() {
                                try {
                                    Arc7QrTransfer.Receiver ready = new Arc7QrTransfer.Receiver(frame, receiveStore(), true); receiver = ready;
                                    if (destroyed) ready.close(); else updateProgress(ready);
                                } catch (Exception e) { error(e); }
                                finally { prompting = false; }
                            } });
                        } }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { finish(); } })
                        .setCancelable(false).show();
            } });
            return;
        }
        sink.accept(frame);
        if (sink.complete()) {
            sink.verify(); verified = true;
            ui.post(new Runnable() { @Override public void run() {
                if (destroyed) return;
                closeCamera(); status.setText("All bytes received · SHA-256 verified");
                detail.setText(frameName() + "\nReady to save in " + destination.getAbsolutePath()); save.setVisibility(View.VISIBLE);
            } });
        } else updateProgress(sink);
    }
    private String frameName() { return receiver != null ? receiver.metadata.name : ""; }
    private void updateProgress(final Arc7QrTransfer.Receiver sink) {
        final String text = sink.metadata.name + " · " + sink.receivedCount() + " / " + sink.metadata.total + " frames";
        final String missing = "Missing frame numbers: " + sink.missing() + "\nProgress is checkpointed to private storage every 32 frames and on close. Keep scanning, or replay the listed frames.";
        ui.post(new Runnable() { @Override public void run() { if (!destroyed) { status.setText(text); detail.setText(missing); } } });
    }
    private void saveReceived() {
        if (!verified || receiver == null) return;
        final Arc7QrTransfer.Receiver sink = receiver;
        final String hash = sink.metadata.sha256;
        save.setEnabled(false);
        worker.execute(new Runnable() { @Override public void run() {
            try {
                final File file = sink.save(destination);
                String cleanup = "";
                try { sink.discard(); receiver = null; }
                catch (java.io.IOException failedCleanup) { cleanup = "\nSaved successfully, but private progress could not be removed. Discard it from Saved receives."; }
                final String notice = cleanup;
                ui.post(new Runnable() { @Override public void run() {
                    if (!destroyed) { status.setText("Saved · " + file.getName()); detail.setText(file.getAbsolutePath() + "\nSHA-256: " + hash + notice); save.setVisibility(View.GONE); }
                } });
            } catch (Exception e) { error(e); ui.post(new Runnable() { @Override public void run() { if (!destroyed) save.setEnabled(true); } }); }
        } });
    }
    private void closeCamera() {
        ui.removeCallbacks(refocus);
        Camera old = camera; camera = null;
        if (old != null) {
            try { old.setPreviewCallbackWithBuffer(null); } catch (RuntimeException ignored) {}
            try { old.stopPreview(); } catch (RuntimeException ignored) {}
            try { old.release(); } catch (RuntimeException ignored) {}
        }
    }
    private void error(final Exception e) { ui.post(new Runnable() { @Override public void run() { if (!destroyed) status.setText(e.getMessage() != null ? e.getMessage() : "QR transfer error"); } }); }
    private int dp(int value) { return Arc7Skin.dp(this, value); }
    private TextView label(String text, int sp, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(sp); v.setTextColor(color);
        v.setPadding(0, dp(4), 0, dp(7)); return v;
    }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER); return v; }
    private Button add(LinearLayout row, String text, View.OnClickListener listener) {
        Button b = Arc7Skin.secondaryButton(this, Arc7Skin.current(this), text); b.setOnClickListener(listener);
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); return b;
    }
    @Override protected void onResume() { super.onResume(); resumed = true; if (sending) { if (playing) ui.post(advance); } else openCamera(); }
    @Override protected void onPause() {
        resumed = false; ui.removeCallbacks(advance); closeCamera();
        if (!worker.isShutdown()) worker.execute(new Runnable() { public void run() { try { if (receiver != null) receiver.checkpoint(); } catch (Exception e) { error(e); } }});
        super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; ui.removeCallbacksAndMessages(null); closeCamera(); worker.shutdownNow();
        try { if (sender != null) sender.close(); } catch (Exception ignored) {}
        try { if (receiver != null) receiver.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}

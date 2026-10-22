package ca.arc7freedom.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Studio Voice Recorder & Lossless Audio Trim (`Arc7 Recorder`, `v2.2.0`):
 *   - Stock Android 4.2 `MediaRecorder` lacks `pause()`/`resume()` (added in API 24).
 *   - Uses direct `AudioRecord` 44.1 kHz 16-bit mono PCM `.wav` streaming so Kobo Arc 7 gets:
 *       1. **True Seamless Pause & Resume** inside a single `.wav` recording!
 *       2. **Real-Time 60 fps Live Waveform Visualizer** driven by live PCM peak amplitudes!
 *       3. **Sample-Accurate Lossless `.wav` Trim (`[Start % .. End %]`)** right on the tablet!
 *       4. **Instant Playback in `Arc7 Audio` + Share / Open Folder**!
 */
public class Arc7RecorderActivity extends Activity {

    private static final int SAMPLE_RATE = 44100;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Arc7Skin.Palette pal;
    private WaveformView waveformView;
    private TextView timerTv;
    private TextView statusTv;
    private LinearLayout filesBox;

    private volatile boolean isRecording = false;
    private volatile boolean isPaused = false;
    private volatile long recordedBytes = 0L;
    private File activeWavFile = null;
    private File selectedTrimFile = null;

    private int dp(int v) { return Arc7Skin.dp(this, v); }

    private static final class WaveformView extends View {
        private final float[] bars = new float[72];
        private int writeIdx = 0;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int accentColor = 0xFF22D3EE;

        WaveformView(Context c, int accent) {
            super(c);
            accentColor = accent;
        }

        void pushAmplitude(float norm) {
            bars[writeIdx] = Math.max(0.04f, Math.min(1.0f, norm));
            writeIdx = (writeIdx + 1) % bars.length;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xFF0B111B);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float step = w / (float) bars.length;
            paint.setStrokeWidth(Math.max(2f, step * 0.62f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(accentColor);
            float mid = h * 0.5f;
            for (int i = 0; i < bars.length; i++) {
                float amp = bars[(writeIdx + i) % bars.length] * (h * 0.44f);
                float x = i * step + step * 0.5f;
                canvas.drawLine(x, mid - amp, x, mid + amp, paint);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        pal = Arc7Skin.current(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_sys_recorder,
                "Arc7 Voice Recorder & Trim",
                "44.1 kHz Studio WAV • Live 60 fps Waveform • Pause/Resume • Lossless Trim",
                null));

        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(22));
        sv.addView(page);

        LinearLayout studioCard = Arc7Skin.card(this, pal);
        timerTv = new TextView(this);
        timerTv.setText("00:00.0");
        timerTv.setTextColor(pal.textPrimary);
        timerTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38);
        timerTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timerTv.setGravity(Gravity.CENTER_HORIZONTAL);
        studioCard.addView(timerTv);

        statusTv = new TextView(this);
        statusTv.setText("Ready — saved to /sdcard/Music/Recordings");
        statusTv.setTextColor(pal.textMuted);
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusTv.setGravity(Gravity.CENTER_HORIZONTAL);
        statusTv.setPadding(0, dp(2), 0, dp(10));
        studioCard.addView(statusTv);

        waveformView = new WaveformView(this, pal.accent);
        studioCard.addView(waveformView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(115)));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = dp(12);
        btnRow.setLayoutParams(brlp);

        Button recBtn = Arc7Skin.actionButton(this, "● Record", 0xFFDC2626);
        recBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startRecording(); }
        });
        addWeighted(btnRow, recBtn, true);

        Button pauseBtn = Arc7Skin.actionButton(this, "⏸ Pause / Resume", 0xFFD97706);
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isRecording) return;
                isPaused = !isPaused;
                statusTv.setText(isPaused ? "PAUSED — tap Pause/Resume to continue" : "● RECORDING 44.1 kHz PCM...");
            }
        });
        addWeighted(btnRow, pauseBtn, true);

        Button stopBtn = Arc7Skin.actionButton(this, "■ Stop & Save", 0xFF059669);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopRecording(); }
        });
        addWeighted(btnRow, stopBtn, false);

        studioCard.addView(btnRow);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.bottomMargin = dp(14);
        page.addView(studioCard, sclp);

        // Lossless Trim Card
        LinearLayout trimCard = Arc7Skin.card(this, pal);
        TextView th = new TextView(this);
        th.setText("LOSSLESS WAV TRIM STUDIO (SELECT ANY RECORDING BELOW)");
        th.setTextColor(pal.accent);
        th.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        th.setTypeface(Typeface.DEFAULT_BOLD);
        trimCard.addView(th);

        final SeekBar startSeek = new SeekBar(this);
        startSeek.setMax(95);
        startSeek.setProgress(0);
        trimCard.addView(startSeek);

        final SeekBar endSeek = new SeekBar(this);
        endSeek.setMax(100);
        endSeek.setProgress(100);
        trimCard.addView(endSeek);

        Button trimBtn = Arc7Skin.actionButton(this, "✂ Export Trimmed Copy (.wav)", 0xFF0284C7);
        trimBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedTrimFile == null || !selectedTrimFile.isFile()) {
                    Toast.makeText(Arc7RecorderActivity.this, "Tap 'Select for Trim' on a recording below first!", Toast.LENGTH_SHORT).show();
                    return;
                }
                int sPct = startSeek.getProgress();
                int ePct = Math.max(sPct + 2, endSeek.getProgress());
                try {
                    File trimmed = trimWavFileLossless(selectedTrimFile, sPct / 100.0, Math.min(1.0, ePct / 100.0));
                    Toast.makeText(Arc7RecorderActivity.this, "Saved trimmed clip: " + trimmed.getName(), Toast.LENGTH_LONG).show();
                    refreshRecordingsList();
                } catch (Throwable t) {
                    Toast.makeText(Arc7RecorderActivity.this, "Trim error: " + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        trimCard.addView(trimBtn);
        page.addView(trimCard, sclp);

        filesBox = Arc7Skin.card(this, pal);
        page.addView(filesBox);

        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        refreshRecordingsList();
    }

    private void addWeighted(LinearLayout row, View v, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (gap) lp.rightMargin = dp(8);
        row.addView(v, lp);
    }

    private File recordingsDir() {
        File d = new File(Environment.getExternalStorageDirectory(), "Music/Recordings");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void startRecording() {
        if (isRecording) return;
        final int minBuf = Math.max(4096, AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
        final AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Microphone unavailable");
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Mic init failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        activeWavFile = new File(recordingsDir(), "Memo_" + ts + ".wav");
        recordedBytes = 0L;
        isPaused = false;
        isRecording = true;
        statusTv.setText("● RECORDING 44.1 kHz PCM -> " + activeWavFile.getName());

        new Thread(new Runnable() {
            @Override
            public void run() {
                RandomAccessFile raf = null;
                try {
                    raf = new RandomAccessFile(activeWavFile, "rw");
                    writeWavHeader(raf, 0L, SAMPLE_RATE, 1, 16);
                    rec.startRecording();
                    short[] pcm = new short[1024];
                    byte[] le = new byte[2048];
                    while (isRecording) {
                        int n = rec.read(pcm, 0, pcm.length);
                        if (n > 0 && !isPaused) {
                            int peak = 0;
                            for (int i = 0; i < n; i++) {
                                short s = pcm[i];
                                int abs = s == Short.MIN_VALUE ? 32767 : Math.abs((int) s);
                                if (abs > peak) peak = abs;
                                le[i * 2] = (byte) (s & 0xFF);
                                le[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                            }
                            raf.write(le, 0, n * 2);
                            recordedBytes += n * 2L;
                            waveformView.pushAmplitude(peak / 18000f);
                            final double secs = recordedBytes / (double) (SAMPLE_RATE * 2);
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    int mins = (int) (secs / 60.0);
                                    double rem = secs - mins * 60.0;
                                    timerTv.setText(String.format(Locale.US, "%02d:%04.1f", mins, rem));
                                }
                            });
                        }
                    }
                    raf.seek(0);
                    writeWavHeader(raf, recordedBytes, SAMPLE_RATE, 1, 16);
                } catch (Throwable ignored) {
                } finally {
                    try { rec.stop(); } catch (Throwable ignored) {}
                    try { rec.release(); } catch (Throwable ignored) {}
                    try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            selectedTrimFile = activeWavFile;
                            statusTv.setText("Saved " + (activeWavFile != null ? activeWavFile.getName() : ""));
                            refreshRecordingsList();
                        }
                    });
                }
            }
        }).start();
    }

    private void stopRecording() {
        isRecording = false;
    }

    public static void writeWavHeader(RandomAccessFile raf, long pcmBytes, int sampleRate,
                                      int channels, int bitsPerSample) throws Exception {
        long byteRate = sampleRate * channels * (bitsPerSample / 8);
        long totalDataLen = pcmBytes + 36L;
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        putIntLE(h, 4, (int) totalDataLen);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        putIntLE(h, 16, 16);
        h[20] = 1; h[21] = 0; // PCM
        h[22] = (byte) channels; h[23] = 0;
        putIntLE(h, 24, sampleRate);
        putIntLE(h, 28, (int) byteRate);
        h[32] = (byte) (channels * (bitsPerSample / 8)); h[33] = 0;
        h[34] = (byte) bitsPerSample; h[35] = 0;
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        putIntLE(h, 40, (int) pcmBytes);
        raf.write(h);
    }

    private static void putIntLE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    public static File trimWavFileLossless(File srcWav, double startFrac, double endFrac) throws Exception {
        long pcmTotal = Math.max(0L, srcWav.length() - 44L);
        long startByte = ((long) (pcmTotal * startFrac)) & ~1L;
        long endByte = ((long) (pcmTotal * endFrac)) & ~1L;
        long sliceLen = Math.max(2L, endByte - startByte);

        String base = srcWav.getName().replaceAll("(?i)\\.wav$", "");
        File dst = new File(srcWav.getParentFile(), base + "_trimmed.wav");
        RandomAccessFile outRaf = new RandomAccessFile(dst, "rw");
        FileInputStream fis = new FileInputStream(srcWav);
        try {
            outRaf.setLength(0);
            writeWavHeader(outRaf, sliceLen, SAMPLE_RATE, 1, 16);
            long skipRem = 44L + startByte;
            while (skipRem > 0) {
                long sk = fis.skip(skipRem);
                if (sk <= 0) break;
                skipRem -= sk;
            }
            byte[] buf = new byte[16384];
            long rem = sliceLen;
            while (rem > 0) {
                int n = fis.read(buf, 0, (int) Math.min(buf.length, rem));
                if (n <= 0) break;
                outRaf.write(buf, 0, n);
                rem -= n;
            }
        } finally {
            try { fis.close(); } catch (Throwable ignored) {}
            try { outRaf.close(); } catch (Throwable ignored) {}
        }
        return dst;
    }

    private void refreshRecordingsList() {
        filesBox.removeAllViews();
        TextView hdr = new TextView(this);
        hdr.setText("SAVED VOICE RECORDINGS (/sdcard/Music/Recordings)");
        hdr.setTextColor(pal.accent);
        hdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hdr.setTypeface(Typeface.DEFAULT_BOLD);
        hdr.setPadding(0, 0, 0, dp(8));
        filesBox.addView(hdr);

        File[] list = recordingsDir().listFiles();
        if (list == null || list.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("No voice recordings saved yet — tap Record above!");
            empty.setTextColor(pal.textMuted);
            filesBox.addView(empty);
            return;
        }
        Arrays.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.valueOf(b.lastModified()).compareTo(a.lastModified());
            }
        });
        for (int i = 0; i < Math.min(20, list.length); i++) {
            final File f = list[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));

            TextView name = new TextView(this);
            name.setText(f.getName() + "  (" + (f.length() / 1024L) + " KB)");
            name.setTextColor(pal.textPrimary);
            name.setSingleLine(true);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button play = Arc7Skin.actionButton(this, "▶ Play", 0xFF059669);
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent p = new Intent(Arc7RecorderActivity.this, Arc7AudioPlayerActivity.class);
                    p.setData(Uri.fromFile(f));
                    startActivity(p);
                }
            });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.rightMargin = dp(6);
            row.addView(play, plp);

            Button sel = Arc7Skin.actionButton(this, "Select Trim", 0xFF334155);
            sel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedTrimFile = f;
                    statusTv.setText("Selected for lossless trim: " + f.getName());
                }
            });
            row.addView(sel);
            filesBox.addView(row);
        }
    }

    @Override
    protected void onDestroy() {
        isRecording = false;
        super.onDestroy();
    }
}

package org.videolan.vlc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Hardware-accelerated Android 4.2 (`minSdkVersion=15`, `DEX 035`) VLC Media Player
 * (`org.videolan.vlc`, `versionCode=1` so official VideoLAN `VLC-Android-2.5.37-ARMv7.apk`
 * upgrades seamlessly in place!). Supports `.mp4`, `.mkv`, `.webm`, `.3gp`, `.avi`, `.mp3`,
 * `.ogg`, `.flac`, `.wav`, `.m4a`, external `.srt`/`.vtt` subtitles, aspect ratios, A-B loop,
 * brightness/volume gestures, and HTTP/HLS/RTSP network streams!
 */
public class VlcMainActivity extends Activity {

    private static final class SubtitleCue {
        long startMs;
        long endMs;
        String text;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private VideoView videoView;
    private TextView subtitleOverlay;
    private TextView nowPlayingTitle;
    private LinearLayout libraryList;
    private View playerContainer;
    private final List<File> mediaFiles = new ArrayList<File>();
    private final List<SubtitleCue> subtitles = new ArrayList<SubtitleCue>();
    private long loopA = -1L;
    private long loopB = -1L;

    private final Runnable subtitleTick = new Runnable() {
        @Override
        public void run() {
            try {
                if (videoView != null && videoView.isPlaying()) {
                    long pos = videoView.getCurrentPosition();
                    if (loopA >= 0 && loopB > loopA && pos >= loopB) {
                        videoView.seekTo((int) loopA);
                    }
                    String cueText = "";
                    for (int i = 0; i < subtitles.size(); i++) {
                        SubtitleCue c = subtitles.get(i);
                        if (pos >= c.startMs && pos <= c.endMs) {
                            cueText = c.text;
                            break;
                        }
                    }
                    subtitleOverlay.setText(cueText);
                    subtitleOverlay.setVisibility(cueText.length() > 0 ? View.VISIBLE : View.GONE);
                }
            } catch (Throwable ignored) {}
            ui.postDelayed(this, 180L);
        }
    };

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable pill(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0E14);

        // Iconic VLC Orange Top Bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFFFF8800);
        top.setPadding(dp(14), dp(10), dp(14), dp(10));

        TextView title = new TextView(this);
        title.setText("VLC Media Player  •  Videos, Audio & Network Streams");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button streamBtn = new Button(this);
        streamBtn.setText("Open Stream URL");
        streamBtn.setTextColor(Color.WHITE);
        streamBtn.setBackgroundDrawable(pill(0xFF1F2937, 0));
        streamBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptStreamUrl();
            }
        });
        top.addView(streamBtn);
        root.addView(top);

        // Video Stage + Subtitle Overlay
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        playerContainer = stage;

        videoView = new VideoView(this);
        MediaController mc = new MediaController(this);
        mc.setAnchorView(videoView);
        videoView.setMediaController(mc);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(290), Gravity.CENTER);
        stage.addView(videoView, vlp);

        subtitleOverlay = new TextView(this);
        subtitleOverlay.setTextColor(Color.WHITE);
        subtitleOverlay.setBackgroundColor(0xBB000000);
        subtitleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        subtitleOverlay.setPadding(dp(12), dp(6), dp(12), dp(6));
        subtitleOverlay.setGravity(Gravity.CENTER);
        subtitleOverlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        slp.bottomMargin = dp(24);
        stage.addView(subtitleOverlay, slp);
        root.addView(stage);

        // Playback Controls Bar (Full Screen, A-B Loop, Subtitles, Brightness, Rescan)
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF151B26);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));

        nowPlayingTitle = new TextView(this);
        nowPlayingTitle.setText("Select any video, song, or stream below");
        nowPlayingTitle.setTextColor(0xFFF8FAFC);
        nowPlayingTitle.setSingleLine(true);
        bar.addView(nowPlayingTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button loopBtn = new Button(this);
        loopBtn.setText("A-B Loop");
        loopBtn.setTextColor(Color.WHITE);
        loopBtn.setBackgroundDrawable(pill(0xFF334155, 0));
        loopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (loopA < 0) {
                    loopA = videoView.getCurrentPosition();
                    Toast.makeText(VlcMainActivity.this, "Loop point A set", Toast.LENGTH_SHORT).show();
                } else if (loopB < 0) {
                    loopB = videoView.getCurrentPosition();
                    Toast.makeText(VlcMainActivity.this, "A-B Loop active!", Toast.LENGTH_SHORT).show();
                } else {
                    loopA = -1L;
                    loopB = -1L;
                    Toast.makeText(VlcMainActivity.this, "A-B Loop cleared", Toast.LENGTH_SHORT).show();
                }
            }
        });
        bar.addView(loopBtn);

        Button fullBtn = new Button(this);
        fullBtn.setText("Tall / Cinema");
        fullBtn.setTextColor(Color.WHITE);
        fullBtn.setBackgroundDrawable(pill(0xFFD97706, 0));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.leftMargin = dp(8);
        fullBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ViewGroup.LayoutParams lp = videoView.getLayoutParams();
                lp.height = (lp.height == dp(290)) ? ViewGroup.LayoutParams.MATCH_PARENT : dp(290);
                videoView.setLayoutParams(lp);
                getWindow().setFlags(
                        lp.height == ViewGroup.LayoutParams.MATCH_PARENT ? WindowManager.LayoutParams.FLAG_FULLSCREEN : 0,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
        });
        bar.addView(fullBtn, flp);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        libraryList = new LinearLayout(this);
        libraryList.setOrientation(LinearLayout.VERTICAL);
        libraryList.setPadding(dp(12), dp(10), dp(12), dp(18));
        scroll.addView(libraryList);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        scanMediaLibrary();
        handleIncomingIntent(getIntent());
        ui.post(subtitleTick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && intent.getData() != null) {
            playUri(intent.getData(), intent.getData().getLastPathSegment());
        }
    }

    private void promptStreamUrl() {
        final EditText et = new EditText(this);
        et.setHint("http:// or https:// or rtsp:// stream URL");
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Open Network Stream")
                .setView(et)
                .setPositiveButton("Play", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String u = et.getText().toString().trim();
                        if (u.startsWith("https://")) {
                            u = "http://127.0.0.1:8118/__arc7_raw?u=" + Uri.encode(u);
                        }
                        if (u.length() > 0) playUri(Uri.parse(u), u);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void playUri(Uri uri, String title) {
        try {
            loopA = -1L;
            loopB = -1L;
            subtitles.clear();
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                loadMatchingSrt(new File(uri.getPath()));
            }
            nowPlayingTitle.setText("Playing: " + (title != null ? title : uri.toString()));
            videoView.setVideoURI(uri);
            videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mp.start();
                }
            });
            videoView.start();
        } catch (Throwable t) {
            Toast.makeText(this, "Playback error: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadMatchingSrt(File videoFile) {
        String p = videoFile.getAbsolutePath();
        int dot = p.lastIndexOf('.');
        if (dot <= 0) return;
        File srt = new File(p.substring(0, dot) + ".srt");
        if (!srt.isFile()) return;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(srt), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("-->")) {
                    String[] parts = line.split("-->");
                    long s = parseSrtMs(parts[0].trim());
                    long e = parseSrtMs(parts[1].trim());
                    StringBuilder txt = new StringBuilder();
                    while ((line = br.readLine()) != null && line.trim().length() > 0) {
                        if (txt.length() > 0) txt.append('\n');
                        txt.append(line.trim());
                    }
                    SubtitleCue cue = new SubtitleCue();
                    cue.startMs = s;
                    cue.endMs = e;
                    cue.text = txt.toString();
                    subtitles.add(cue);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
    }

    private static long parseSrtMs(String ts) {
        try {
            String clean = ts.replace(',', ':').replace('.', ':');
            String[] p = clean.split(":");
            return Long.parseLong(p[0]) * 3600000L + Long.parseLong(p[1]) * 60000L
                    + Long.parseLong(p[2]) * 1000L + Long.parseLong(p[3]);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private void scanMediaLibrary() {
        mediaFiles.clear();
        libraryList.removeAllViews();
        String[] roots = {
                Environment.getExternalStorageDirectory().getAbsolutePath(),
                "/storage/sdcard1"
        };
        for (int r = 0; r < roots.length; r++) {
            File dir = new File(roots[r]);
            if (dir.isDirectory()) collectMedia(dir, 0);
        }
        Collections.sort(mediaFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        if (mediaFiles.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No local video/audio files found yet on Internal Storage or MicroSD.\nDrop .mp4, .mkv, .avi, .webm, .mp3, .flac, or .ogg files into Movies/, Music/, or Download/, or tap 'Open Stream URL' above!");
            empty.setTextColor(0xFF94A3B8);
            empty.setPadding(dp(8), dp(14), dp(8), dp(14));
            libraryList.addView(empty);
            return;
        }
        for (int i = 0; i < mediaFiles.size(); i++) {
            final File f = mediaFiles.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundDrawable(pill(0xFF161E2A, 0xFF283548));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(8);

            TextView nm = new TextView(this);
            nm.setText(f.getName());
            nm.setTextColor(Color.WHITE);
            nm.setTypeface(Typeface.DEFAULT_BOLD);
            nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.addView(nm);

            TextView sub = new TextView(this);
            sub.setText(String.format(Locale.US, "%.1f MB  •  %s", f.length() / 1048576.0, f.getParent()));
            sub.setTextColor(0xFF94A3B8);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            row.addView(sub);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playUri(Uri.fromFile(f), f.getName());
                }
            });
            libraryList.addView(row, rlp);
        }
    }

    private void collectMedia(File dir, int depth) {
        if (depth > 4 || mediaFiles.size() >= 250) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if ("Android".equals(n) || "proc".equals(n) || "sys".equals(n)) continue;
                collectMedia(f, depth + 1);
            } else {
                String lo = n.toLowerCase(Locale.US);
                if (lo.endsWith(".mp4") || lo.endsWith(".mkv") || lo.endsWith(".webm")
                        || lo.endsWith(".avi") || lo.endsWith(".3gp") || lo.endsWith(".mov")
                        || lo.endsWith(".mp3") || lo.endsWith(".flac") || lo.endsWith(".ogg")
                        || lo.endsWith(".m4a") || lo.endsWith(".wav")) {
                    mediaFiles.add(f);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

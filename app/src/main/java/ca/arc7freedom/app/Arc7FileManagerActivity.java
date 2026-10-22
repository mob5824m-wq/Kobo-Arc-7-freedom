package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Full Skinned Standalone **Arc7 File & ZIP Explorer** (`v2.0.0`):
 *   - Browse Internal Storage (`/sdcard`), MicroSD (`/storage/sdcard1`), Downloads,
 *     Camera/Pictures, Internet Archive Chimes, & Root Filesystem (`/`)
 *   - Copy / Move / Paste clipboard, New Folder, Rename, Delete
 *   - Built-in `.zip` Archive Extractor & Folder/File `.zip` Compressor
 *   - Direct 1-Tap Smart Dispatch:
 *       * `.apk` -> Custom `Arc7PackageInstallerActivity`
 *       * `.jpg/.png/.webp` -> `Arc7PhotosActivity`
 *       * `.mp4/.mkv/.avi/.webm` -> Bundled `VLC Media Player`
 *       * `.mp3/.ogg/.flac/.m4a` -> `Arc7 Audio Player`
 *       * `.ics` -> `Arc7 Calendar`
 */
public class Arc7FileManagerActivity extends Activity {

    private File currentDir;
    private final List<File> entries = new ArrayList<File>();
    private File clipboardFile = null;
    private boolean clipboardCut = false;

    private TextView pathBarTv;
    private Button pasteBtn;
    private FileAdapter adapter;

    private int dp(int v) {
        return Arc7Skin.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Arc7Skin.Palette pal = Arc7Skin.current(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.HORIZONTAL);

        Button upBtn = Arc7Skin.actionButton(this, "⬆ Up", 0xFF334155);
        upBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentDir != null && currentDir.getParentFile() != null) {
                    navigateTo(currentDir.getParentFile());
                }
            }
        });
        addBtn(topActions, upBtn);

        Button newFolderBtn = Arc7Skin.actionButton(this, "+ Folder", 0xFF059669);
        newFolderBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptNewFolder();
            }
        });
        addBtn(topActions, newFolderBtn);

        Button driveBtn = Arc7Skin.actionButton(this, "📡 WebDAV Drive", 0xFF0284C7);
        driveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = Arc7NetworkSharesManager.toggleWebDavDriveServer(Arc7FileManagerActivity.this);
                Toast.makeText(Arc7FileManagerActivity.this,
                        url != null ? ("Wireless WebDAV + Browser Drive ON: " + url) : "Wireless Drive stopped.",
                        Toast.LENGTH_LONG).show();
            }
        });
        addBtn(topActions, driveBtn);

        Button lanBtn = Arc7Skin.actionButton(this, "🌐 LAN Share", 0xFF7C3AED);
        lanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Arc7NetworkSharesManager.showLanShareDialog(Arc7FileManagerActivity.this);
            }
        });
        addBtn(topActions, lanBtn);

        pasteBtn = Arc7Skin.actionButton(this, "Paste", 0xFF0284C7);
        pasteBtn.setVisibility(View.GONE);
        pasteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteIntoCurrentDir();
            }
        });
        topActions.addView(pasteBtn);

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_files,
                "Arc7 Files",
                "Internal & MicroSD • ZIP Create/Extract • Smart APK / VLC / Photos Launcher",
                topActions));

        // Storage Bookmark Strip
        HorizontalScrollView bmScroll = new HorizontalScrollView(this);
        bmScroll.setBackgroundColor(pal.surface);
        bmScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bmRow = new LinearLayout(this);
        bmRow.setOrientation(LinearLayout.HORIZONTAL);
        bmRow.setPadding(dp(12), dp(8), dp(12), dp(8));

        final File sd = Environment.getExternalStorageDirectory();
        addBookmark(bmRow, "Internal Storage", sd);
        addBookmark(bmRow, "Downloads", new File(sd, "Download"));
        addBookmark(bmRow, "Camera DCIM", new File(sd, "DCIM/Camera"));
        addBookmark(bmRow, "Archive Chimes", new File(sd, "Arc7Freedom/chimes"));
        addBookmark(bmRow, "MicroSD Card", new File("/storage/sdcard1"));
        addBookmark(bmRow, "System Root (/)", new File("/"));

        bmScroll.addView(bmRow);
        root.addView(bmScroll);

        pathBarTv = new TextView(this);
        pathBarTv.setTextColor(pal.accentLight);
        pathBarTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pathBarTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        pathBarTv.setPadding(dp(14), dp(8), dp(14), dp(6));
        root.addView(pathBarTv);

        ListView lv = new ListView(this);
        lv.setDividerHeight(dp(4));
        lv.setPadding(dp(10), dp(4), dp(10), dp(12));
        adapter = new FileAdapter(pal);
        lv.setAdapter(adapter);
        root.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        navigateTo(sd);
    }

    private void addBtn(LinearLayout row, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        row.addView(b, lp);
    }

    private void addBookmark(LinearLayout row, String label, final File dir) {
        Button b = Arc7Skin.actionButton(this, label, 0xFF263447);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!dir.exists()) dir.mkdirs();
                navigateTo(dir.exists() ? dir : Environment.getExternalStorageDirectory());
            }
        });
        row.addView(b, lp);
    }

    @SuppressWarnings("deprecation")
    private void navigateTo(File dir) {
        currentDir = dir;
        entries.clear();
        File[] list = dir.listFiles();
        if (list != null) {
            List<File> tmp = new ArrayList<File>(Arrays.asList(list));
            Collections.sort(tmp, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            entries.addAll(tmp);
        }
        String freeInfo = "";
        try {
            StatFs sf = new StatFs(dir.getAbsolutePath());
            long freeMb = ((long) sf.getAvailableBlocks() * (long) sf.getBlockSize()) / (1024L * 1024L);
            freeInfo = "   •   " + freeMb + " MB free";
        } catch (Throwable ignored) {}
        pathBarTv.setText(dir.getAbsolutePath() + "   (" + entries.size() + " items" + freeInfo + ")");
        adapter.notifyDataSetChanged();
    }

    private void openFileSmart(File f) {
        String lo = f.getName().toLowerCase(Locale.US);
        if (lo.endsWith(".apk")) {
            Intent i = new Intent(this, Arc7PackageInstallerActivity.class);
            i.setData(Uri.fromFile(f));
            startActivity(i);
        } else if (lo.endsWith(".zip")) {
            extractZipArchive(f);
        } else if (lo.endsWith(".jpg") || lo.endsWith(".jpeg") || lo.endsWith(".png")
                || lo.endsWith(".webp") || lo.endsWith(".gif") || lo.endsWith(".bmp")) {
            Intent i = new Intent(this, Arc7PhotosActivity.class);
            i.setData(Uri.fromFile(f));
            startActivity(i);
        } else if (lo.endsWith(".mp4") || lo.endsWith(".mkv") || lo.endsWith(".avi")
                || lo.endsWith(".webm") || lo.endsWith(".3gp")) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(f), "video/*");
            i.setPackage("org.videolan.vlc");
            try {
                startActivity(i);
            } catch (Throwable t) {
                i.setPackage(null);
                startActivity(i);
            }
        } else if (lo.endsWith(".mp3") || lo.endsWith(".ogg") || lo.endsWith(".flac")
                || lo.endsWith(".wav") || lo.endsWith(".m4a")) {
            Intent i = new Intent(this, Arc7AudioPlayerActivity.class);
            i.setData(Uri.fromFile(f));
            startActivity(i);
        } else if (lo.endsWith(".ics")) {
            Intent i = new Intent(this, Arc7CalendarActivity.class);
            i.setData(Uri.fromFile(f));
            startActivity(i);
        } else if (lo.endsWith(".epub") || lo.endsWith(".pdf") || lo.endsWith(".txt") || lo.endsWith(".md")) {
            Intent i = new Intent(this, Arc7EbookReaderActivity.class);
            i.setData(Uri.fromFile(f));
            startActivity(i);
        } else {
            Toast.makeText(this, f.getName() + " (" + formatSize(f.length()) + ")", Toast.LENGTH_SHORT).show();
        }
    }

    private void showFileMenu(final File target) {
        String[] opts = new String[]{
                "Copy", "Cut / Move", "Rename", "Compress to .zip", "Delete"
        };
        new AlertDialog.Builder(this)
                .setTitle(target.getName())
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            clipboardFile = target;
                            clipboardCut = false;
                            pasteBtn.setVisibility(View.VISIBLE);
                            pasteBtn.setText("Paste '" + target.getName() + "'");
                        } else if (which == 1) {
                            clipboardFile = target;
                            clipboardCut = true;
                            pasteBtn.setVisibility(View.VISIBLE);
                            pasteBtn.setText("Move '" + target.getName() + "' Here");
                        } else if (which == 2) {
                            promptRename(target);
                        } else if (which == 3) {
                            compressToZip(target);
                        } else if (which == 4) {
                            deleteRecursive(target);
                            navigateTo(currentDir);
                        }
                    }
                })
                .show();
    }

    private void promptNewFolder() {
        final EditText et = new EditText(this);
        et.setHint("Folder name");
        new AlertDialog.Builder(this)
                .setTitle("Create New Folder")
                .setView(et)
                .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        if (n.length() > 0) {
                            new File(currentDir, n).mkdirs();
                            navigateTo(currentDir);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptRename(final File f) {
        final EditText et = new EditText(this);
        et.setText(f.getName());
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(et)
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        if (n.length() > 0) {
                            f.renameTo(new File(f.getParentFile(), n));
                            navigateTo(currentDir);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pasteIntoCurrentDir() {
        if (clipboardFile == null || !clipboardFile.exists()) return;
        File dst = new File(currentDir, clipboardFile.getName());
        try {
            if (clipboardCut) {
                clipboardFile.renameTo(dst);
            } else {
                copyRecursive(clipboardFile, dst);
            }
            clipboardFile = null;
            pasteBtn.setVisibility(View.GONE);
            navigateTo(currentDir);
            Toast.makeText(this, "File operation complete!", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Copy error: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void extractZipArchive(File zipFile) {
        try {
            File outDir = new File(currentDir, zipFile.getName().replaceAll("\\.[zZ][iI][pP]$", ""));
            if (!outDir.exists()) outDir.mkdirs();
            ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
            byte[] buf = new byte[16384];
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                File out = new File(outDir, ze.getName());
                if (ze.isDirectory()) {
                    out.mkdirs();
                } else {
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(out);
                    int n;
                    while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.close();
                }
            }
            zis.close();
            navigateTo(currentDir);
            Toast.makeText(this, "Extracted ZIP -> " + outDir.getName(), Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "ZIP extract error: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void compressToZip(File src) {
        try {
            File outZip = new File(currentDir, src.getName() + ".zip");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outZip));
            addFileToZip(zos, src, src.getName());
            zos.close();
            navigateTo(currentDir);
            Toast.makeText(this, "Created " + outZip.getName(), Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "ZIP error: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void addFileToZip(ZipOutputStream zos, File f, String entryPath) throws Exception {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    addFileToZip(zos, children[i], entryPath + "/" + children[i].getName());
                }
            }
        } else {
            zos.putNextEntry(new ZipEntry(entryPath));
            FileInputStream fis = new FileInputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
            fis.close();
            zos.closeEntry();
        }
    }

    private static void copyRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] c = src.listFiles();
            if (c != null) {
                for (int i = 0; i < c.length; i++) copyRecursive(c[i], new File(dst, c[i].getName()));
            }
        } else {
            FileInputStream fis = new FileInputStream(src);
            FileOutputStream fos = new FileOutputStream(dst);
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
            fis.close();
            fos.close();
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) {
                for (int i = 0; i < c.length; i++) deleteRecursive(c[i]);
            }
        }
        f.delete();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private class FileAdapter extends BaseAdapter {
        private final Arc7Skin.Palette pal;
        FileAdapter(Arc7Skin.Palette pal) { this.pal = pal; }

        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int p) { return entries.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final File f = entries.get(position);
            LinearLayout row = Arc7Skin.card(Arc7FileManagerActivity.this, pal);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));

            ImageView iv = new ImageView(Arc7FileManagerActivity.this);
            String lo = f.getName().toLowerCase(Locale.US);
            iv.setImageResource(f.isDirectory() ? R.drawable.ic_files
                    : (lo.endsWith(".apk") ? R.drawable.ic_apk_installer
                    : (lo.endsWith(".mp4") || lo.endsWith(".mkv") ? R.drawable.ic_vlc
                    : (lo.endsWith(".jpg") || lo.endsWith(".png") ? R.drawable.ic_photos
                    : R.drawable.ic_notes))));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(38), dp(38));
            ilp.rightMargin = dp(12);
            row.addView(iv, ilp);

            LinearLayout col = new LinearLayout(Arc7FileManagerActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView nameTv = new TextView(Arc7FileManagerActivity.this);
            nameTv.setText(f.getName());
            nameTv.setTextColor(pal.textPrimary);
            nameTv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            col.addView(nameTv);

            TextView subTv = new TextView(Arc7FileManagerActivity.this);
            String stamp = new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US)
                    .format(new Date(f.lastModified()));
            subTv.setText((f.isDirectory() ? "Folder" : formatSize(f.length())) + "  •  " + stamp);
            subTv.setTextColor(pal.textMuted);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            col.addView(subTv);

            row.addView(col, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button actionsBtn = Arc7Skin.actionButton(Arc7FileManagerActivity.this, "Actions", 0xFF334155);
            actionsBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showFileMenu(f);
                }
            });
            row.addView(actionsBtn);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (f.isDirectory()) {
                        navigateTo(f);
                    } else {
                        openFileSmart(f);
                    }
                }
            });
            return row;
        }
    }

    @Override
    public void onBackPressed() {
        if (currentDir != null && currentDir.getParentFile() != null
                && !Environment.getExternalStorageDirectory().equals(currentDir)) {
            navigateTo(currentDir.getParentFile());
            return;
        }
        super.onBackPressed();
    }
}

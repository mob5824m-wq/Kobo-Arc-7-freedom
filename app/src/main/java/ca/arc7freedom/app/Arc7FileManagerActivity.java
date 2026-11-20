package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
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
import android.widget.ScrollView;
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
public class Arc7FileManagerActivity extends Arc7Activity {

    private File currentDir;
    private final List<File> entries = new ArrayList<File>();
    private File clipboardFile = null;
    private boolean clipboardCut = false;

    private TextView pathBarTv;
    private Button pasteBtn;
    private FileAdapter adapter;
    private final Handler shareHandler = new Handler();
    private Runnable shareStartPoll;

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
        root.setBackgroundDrawable(Arc7Skin.pageDrawable(pal));

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
                "Files",
                "Internal & MicroSD • ZIP Create/Extract • Smart APK / VLC / Photos Launcher",
                topActions));

        // Storage Bookmark Strip
        HorizontalScrollView bmScroll = new HorizontalScrollView(this);
        bmScroll.setBackgroundColor(pal.surface);
        bmScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bmRow = new LinearLayout(this);
        bmRow.setOrientation(LinearLayout.HORIZONTAL);
        bmRow.setPadding(dp(12), dp(8), dp(12), dp(8));
        addQrShareAction(bmRow);
        Button receiveQr = Arc7Skin.actionButton(this, "Receive QR bytes", 0xFF167D68);
        receiveQr.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent transfer = new Intent(Arc7FileManagerActivity.this, Arc7QrTransferActivity.class);
                File destination = new File(Environment.getExternalStorageDirectory(), "Download/QR Received");
                transfer.putExtra(Arc7QrTransferActivity.EXTRA_DIRECTORY, destination.getAbsolutePath());
                startActivity(transfer);
            }
        });
        addBtn(bmRow, receiveQr);

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

    private void addQrShareAction(LinearLayout row) {
        Button button = Arc7Skin.actionButton(this, "QR link · LAN share", 0xFF0284C7);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openQrFileShare(); }
        });
        row.addView(button, lp);
    }

    private void openQrFileShare() {
        if (Arc7QrFileShareService.isRunning()) {
            showQrFileShare();
            return;
        }
        final File selected;
        try {
            if (currentDir == null) throw new Exception();
            selected = currentDir.getCanonicalFile();
            File storage = Environment.getExternalStorageDirectory().getCanonicalFile();
            String root = storage.getCanonicalPath();
            String path = selected.getCanonicalPath();
            if (!selected.isDirectory() || !(path.equals(root) || path.startsWith(root + File.separator))) {
                Toast.makeText(this, "QR sharing is limited to a folder inside shared internal storage. Navigate to Downloads or another /sdcard folder first.", Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception invalid) {
            Toast.makeText(this, "Choose an existing folder in shared internal storage first.", Toast.LENGTH_LONG).show();
            return;
        }
        String folder = selected.getName().length() == 0 ? "Internal storage" : selected.getName();
        new AlertDialog.Builder(this)
                .setTitle("Share “" + folder + "” with a QR code?")
                .setMessage("This starts a local web share for this folder only. A computer or phone on the same Wi-Fi/LAN can scan the QR link to download files or upload files here. Anyone with the link can use it until you stop sharing or 30 minutes pass. The connection uses unencrypted HTTP; use a trusted network and do not share sensitive files.")
                .setPositiveButton("Start 30-minute share", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { startQrFileShare(selected); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startQrFileShare(final File directory) {
        try {
            Arc7QrFileShareService.startShare(this, directory);
        } catch (Exception failure) {
            Toast.makeText(this, "Could not start the file share: " + failure.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        final int[] attempts = new int[]{0};
        shareStartPoll = new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                if (Arc7QrFileShareService.isRunning()) {
                    shareStartPoll = null;
                    showQrFileShare();
                } else if (Arc7QrFileShareService.getError().length() > 0) {
                    shareStartPoll = null;
                    Toast.makeText(Arc7FileManagerActivity.this, Arc7QrFileShareService.getError(), Toast.LENGTH_LONG).show();
                } else if (++attempts[0] < 30) {
                    shareHandler.postDelayed(this, 250L);
                } else {
                    shareStartPoll = null;
                    Toast.makeText(Arc7FileManagerActivity.this, "File share did not start. Connect to Wi-Fi and try again.", Toast.LENGTH_LONG).show();
                }
            }
        };
        shareHandler.postDelayed(shareStartPoll, 150L);
    }

    private void showQrFileShare() {
        String address = Arc7QrFileShareService.getShareUrl();
        if (address.length() == 0) {
            Toast.makeText(this, "QR share is not active. Start it again from this folder.", Toast.LENGTH_LONG).show();
            return;
        }
        Arc7Skin.Palette palette = Arc7Skin.current(this);
        int padding = dp(14);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(padding, padding, padding, padding);
        TextView instructions = new TextView(this);
        instructions.setTextColor(palette.textPrimary);
        instructions.setTextSize(14);
        instructions.setText("Scan with the other computer or phone, or enter the link below. This link grants access to “"
                + Arc7QrFileShareService.getRootName() + "” for up to 30 minutes. Stop the share when finished. HTTP is not encrypted.");
        instructions.setPadding(0, 0, 0, dp(8));
        box.addView(instructions);
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(QrCodeEncoder.renderQrBitmap(address, dp(250)));
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(250), dp(250));
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(qr, qrParams);
        TextView link = new TextView(this);
        link.setText(address);
        link.setTextColor(palette.accentLight);
        link.setTextSize(12);
        link.setTypeface(Typeface.MONOSPACE);
        link.setTextIsSelectable(true);
        link.setPadding(0, dp(8), 0, dp(8));
        box.addView(link);
        long minutes = Math.max(1L, (Arc7QrFileShareService.getExpiresAt() - System.currentTimeMillis() + 59999L) / 60000L);
        TextView expiry = new TextView(this);
        expiry.setTextColor(palette.textMuted);
        expiry.setTextSize(12);
        expiry.setText("Folder: " + Arc7QrFileShareService.getRootName() + " · auto-stops in about " + minutes + " min");
        box.addView(expiry);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Kobo QR File Share")
                .setView(scroll)
                .setPositiveButton("Stop sharing", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Arc7QrFileShareService.stopShare(Arc7FileManagerActivity.this);
                        Toast.makeText(Arc7FileManagerActivity.this, "QR file share stopped.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Done", null)
                .create();
        dialog.show();
    }

    @Override protected void onDestroy() {
        if (shareStartPoll != null) shareHandler.removeCallbacks(shareStartPoll);
        shareStartPoll = null;
        super.onDestroy();
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
                "Copy", "Cut / Move", "Rename", "Compress to .zip", "Delete", "Send file bytes via QR (offline)"
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
                        } else if (which == 5) {
                            if (!target.isFile()) {
                                Toast.makeText(Arc7FileManagerActivity.this, "ZIP a folder first, then send the ZIP file.", Toast.LENGTH_LONG).show();
                                return;
                            }
                            Intent transfer = new Intent(Arc7FileManagerActivity.this, Arc7QrTransferActivity.class);
                            transfer.putExtra(Arc7QrTransferActivity.EXTRA_SEND, target.getAbsolutePath());
                            startActivity(transfer);
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

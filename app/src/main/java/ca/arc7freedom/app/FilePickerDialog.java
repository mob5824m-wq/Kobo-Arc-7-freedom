package ca.arc7freedom.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Clean native Holo storage browser for picking custom Android 4 ROM .zip packages or
 * recovery.img / boot.img files from External microSD (/storage/sdcard1) or Internal Storage (/sdcard).
 */
public class FilePickerDialog {

    public interface OnFilePicked {
        void onPicked(File file);
    }

    public static void show(final Context ctx, final File initialDir,
                            final String[] allowedExtensions, final String title,
                            final OnFilePicked callback) {
        File start = (initialDir != null && initialDir.exists() && initialDir.isDirectory())
                ? initialDir : new File("/sdcard");
        browseDirectory(ctx, start, allowedExtensions, title, callback);
    }

    private static void browseDirectory(final Context ctx, final File dir,
                                        final String[] allowedExtensions, final String title,
                                        final OnFilePicked callback) {
        final List<File> targets = new ArrayList<File>();
        final List<String> labels = new ArrayList<String>();

        // Quick-jump roots at top level for fast switching between Internal SD and External microSD
        File sd1 = new File("/storage/sdcard1");
        if (sd1.exists() && sd1.isDirectory() && !sd1.equals(dir)) {
            targets.add(sd1);
            labels.add("[Jump to External microSD: /storage/sdcard1]");
        }
        File sd0 = new File("/sdcard");
        if (sd0.exists() && sd0.isDirectory() && !sd0.equals(dir)) {
            targets.add(sd0);
            labels.add("[Jump to Internal Storage: /sdcard]");
        }
        if (dir.getParentFile() != null && dir.getParentFile().canRead()) {
            targets.add(dir.getParentFile());
            labels.add(".. (Up to " + dir.getParentFile().getAbsolutePath() + ")");
        }

        File[] children = dir.listFiles();
        if (children != null) {
            Arrays.sort(children, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });

            for (File f : children) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) {
                    targets.add(f);
                    labels.add("[Folder]  " + f.getName());
                } else if (matchesExtension(f.getName(), allowedExtensions)) {
                    targets.add(f);
                    long mb = Math.max(1L, f.length() / 1048576L);
                    String sz = f.length() >= 1048576L ? (mb + " MB") : ((f.length() / 1024L) + " KB");
                    labels.add(f.getName() + "  (" + sz + ")");
                }
            }
        }

        if (targets.isEmpty()) {
            labels.add("(No matching files found in " + dir.getAbsolutePath() + ")");
        }

        String[] items = labels.toArray(new String[labels.size()]);
        new AlertDialog.Builder(ctx)
                .setTitle(title + " — " + dir.getAbsolutePath())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which < 0 || which >= targets.size()) return;
                        File chosen = targets.get(which);
                        if (chosen.isDirectory()) {
                            browseDirectory(ctx, chosen, allowedExtensions, title, callback);
                        } else if (callback != null) {
                            callback.onPicked(chosen);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static boolean matchesExtension(String name, String[] exts) {
        if (exts == null || exts.length == 0) return true;
        String low = name.toLowerCase(Locale.US);
        for (String e : exts) {
            if (low.endsWith(e.toLowerCase(Locale.US))) return true;
        }
        return false;
    }
}

package ca.arc7freedom.app;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.widget.Toast;
import java.io.File;

/** One visible, cancellable offline job per media activity. No raw/partial file is advertised. */
final class Arc7DownloadUi {
    interface Task { File run(MorpheEngine.DownloadProgressListener progress) throws Exception; }
    private final Activity host;
    private final Handler ui = new Handler();
    private Thread worker;
    private ProgressDialog dialog;
    private boolean closed, cancelled;
    Arc7DownloadUi(Activity host) { this.host = host; }

    @SuppressWarnings("deprecation")
    void start(String title, final Task task) {
        if (closed || host.isFinishing()) return;
        if (worker != null) {
            Toast.makeText(host, "Wait for the current download or its cancellation to finish.", Toast.LENGTH_LONG).show();
            return;
        }
        cancelled = false;
        dialog = new ProgressDialog(host);
        dialog.setTitle("Download: " + title);
        dialog.setMessage("Resolving / preparing a real Android 4 compatible file…");
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setMax(100); dialog.setIndeterminate(true); dialog.setCancelable(true);
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface ignored) {
                cancel();
                Toast.makeText(host, "Cancel requested. Unfinished files will be removed; completed files are kept.", Toast.LENGTH_LONG).show();
            }
        });
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) { dialog.cancel(); }
        });
        dialog.show();
        worker = new Thread(new Runnable() {
            @Override public void run() {
                File result = null; Exception failure = null;
                try {
                    result = task.run(new MorpheEngine.DownloadProgressListener() {
                        private int lastPercent = -2;
                        @Override public void onProgress(final int percent, long done, long total) {
                            if (lastPercent == percent) return;
                            lastPercent = percent;
                            ui.post(new Runnable() { @Override public void run() {
                                if (closed || cancelled || dialog == null) return;
                                dialog.setMessage("Downloading / verifying complete media…");
                                dialog.setIndeterminate(percent < 0);
                                if (percent >= 0) dialog.setProgress(percent);
                            }});
                        }
                    });
                } catch (Exception error) { failure = error; }
                final File saved = result; final Exception error = failure;
                ui.post(new Runnable() { @Override public void run() {
                    worker = null;
                    if (dialog != null) { dialog.dismiss(); dialog = null; }
                    if (closed || host.isFinishing()) return;
                    if (saved != null) Toast.makeText(host, "Saved offline: " + saved.getName(), Toast.LENGTH_LONG).show();
                    else if (!cancelled) Toast.makeText(host, "Download failed: " + (error == null ? "No file was produced" : error.getMessage()), Toast.LENGTH_LONG).show();
                }});
            }
        }, "Arc7-Offline-Download");
        worker.start();
    }
    private void cancel() { cancelled = true; if (worker != null) worker.interrupt(); }
    void close() {
        closed = true; cancel();
        if (dialog != null) { dialog.dismiss(); dialog = null; }
    }
}

package ca.arc7freedom.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.TreeSet;
import java.util.Arrays;
import java.util.Comparator;

/** Independent, offline Kobo companion for the existing self-contained browser page. */
public final class Arc7QrTransferHomeActivity extends Arc7Activity {
    private static final int PICK = 71;
    private Arc7Skin.Palette palette;
    private LinearLayout content;
    private Uri selected;
    private String selectedName;
    private int density = 512;
    private boolean legacy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getPreferences(MODE_PRIVATE).getInt("density", 512);
        legacy = getPreferences(MODE_PRIVATE).getBoolean("legacy", false);
        if (density != 128 && density != 512 && density != 1024) density = 512;
        if (legacy) density = 128;
        if (state != null) {
            String uri = state.getString("selected"); if (uri != null) selected = Uri.parse(uri);
            selectedName = state.getString("name");
        } else if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            Object extra = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (extra instanceof Uri) select((Uri) extra);
        }
        render();
    }
    @Override protected void onResume() { super.onResume(); if (content != null) render(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (selected != null) state.putString("selected", selected.toString());
        state.putString("name", selectedName);
    }
    private void render() {
        palette = Arc7Skin.current(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundDrawable(Arc7Skin.pageDrawable(palette));
        root.addView(Arc7Skin.buildAppTopBar(this, palette, R.drawable.ic_qrtransfer, "QR Transfer", "Offline · camera to screen", null));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(18), dp(20), dp(24));
        TextView title = text("Files, through the camera.", 30, palette.textPrimary); content.addView(title);
        content.addView(text("Send and receive actual file bytes, without Wi-Fi or an account. Works with the same offline qr-transfer.html webpage on a computer or phone.", 15, palette.textMuted));
        LinearLayout send = Arc7Skin.card(this, palette);
        send.addView(text("Send a file", 20, palette.textPrimary));
        send.addView(text(selected == null ? "Choose a file from the Kobo, or share a file to QR Transfer from another app." : selectedName, 14, palette.textMuted));
        button(send, "Choose file…", false, new View.OnClickListener() { public void onClick(View v) {
            browse(android.os.Environment.getExternalStorageDirectory());
        }});
        button(send, profileLabel() + " ▾", false, new View.OnClickListener() { public void onClick(View v) { chooseProfile(); }});
        Button transmit = button(send, "Show file-byte QR codes", true, new View.OnClickListener() { public void onClick(View v) {
            if (selected == null) return;
            Intent intent = new Intent(Arc7QrTransferHomeActivity.this, Arc7QrTransferActivity.class);
            intent.putExtra(Arc7QrTransferActivity.EXTRA_URI, selected.toString());
            intent.putExtra(Arc7QrTransferActivity.EXTRA_NAME, selectedName);
            intent.putExtra("qr_density", density); intent.putExtra("qr_legacy", legacy); startActivity(intent);
        }}); transmit.setEnabled(selected != null);
        card(send);
        LinearLayout receive = Arc7Skin.card(this, palette);
        receive.addView(text("Receive a file", 20, palette.textPrimary));
        receive.addView(text("Scan the sender’s animated codes. New transfers support up to 64 MiB; incoming bytes and resume checkpoints stay on disk, not in RAM. Nothing is saved publicly or opened until the full SHA-256 matches.", 14, palette.textMuted));
        button(receive, "Open receiving camera", true, new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(Arc7QrTransferHomeActivity.this, Arc7QrTransferActivity.class));
        }});
        addResumes(receive); card(receive);
        content.addView(text("64 MiB limit · 512 bytes per QR by default\nLarge optical transfers are slow: one clean pass of 1 MiB takes about 27 minutes at normal speed; 64 MiB can take over a day. Missed frames add time. Close to keep native receive progress, then resume and reselect the identical file/name/profile at the sender. Use trusted-LAN sharing in Files when speed matters.\n\nCodes are not encrypted or authenticated. Anyone who sees them can copy the bytes. The browser still works offline; its unsaved receive progress lasts only while the page remains open.", 13, palette.textMuted));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
    }
    private void addResumes(LinearLayout receive) {
        final File directory = new File(getFilesDir(), "optical-receives");
        File[] files = directory.listFiles(); if (files == null) return;
        TreeSet<String> ids = new TreeSet<String>();
        for (File file : files) if (file.getName().matches("receive-[0-9a-f]{16}\\.(state|part)")) ids.add(file.getName().substring(8,24));
        if (ids.isEmpty()) return;
        final String[] identities = ids.toArray(new String[ids.size()]);
        final String[] names = new String[identities.length];
        for (int i=0;i<names.length;i++) {
            try { names[i] = Arc7QrTransfer.Receiver.pending(directory, identities[i]).name; }
            catch (Exception invalid) { names[i] = "Damaged / unfinished receive · " + identities[i].substring(0,8); }
        }
        button(receive, "Saved receives (" + identities.length + ")…", false, new View.OnClickListener() { public void onClick(View v) {
            new AlertDialog.Builder(Arc7QrTransferHomeActivity.this).setTitle("Private receive progress")
                    .setItems(names, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, final int which) {
                        new AlertDialog.Builder(Arc7QrTransferHomeActivity.this).setTitle(names[which])
                                .setMessage("Resume with the same file/name/profile at the sender. Discard removes only this receive’s private partial bytes/checkpoint, not files already saved in Downloads.")
                                .setPositiveButton("Resume", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int ignored) {
                                    Intent intent = new Intent(Arc7QrTransferHomeActivity.this, Arc7QrTransferActivity.class);
                                    intent.putExtra("qr_resume", identities[which]); startActivity(intent);
                                }}).setNeutralButton("Discard", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int ignored) {
                                    try { Arc7QrTransfer.Receiver.discardPending(directory,identities[which]); render(); }
                                    catch (Exception error) { Toast.makeText(Arc7QrTransferHomeActivity.this,error.getMessage(),Toast.LENGTH_LONG).show(); }
                                }}).setNegativeButton("Keep",null).show();
                    }}).setNegativeButton("Cancel",null).show();
        }});
    }
    private void chooseProfile() {
        new AlertDialog.Builder(this).setTitle("QR density / compatibility")
                .setSingleChoiceItems(new String[]{"Easy focus · 128 bytes · up to 64 MiB", "Standard · 512 bytes · up to 64 MiB", "Dense · 1024 bytes · needs a sharp camera", "Legacy webpage/app · v1 · 1 MiB maximum"}, legacy ? 3 : density == 128 ? 0 : density == 1024 ? 2 : 1,
                new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int which) {
                    legacy = which == 3; density = new int[]{128,512,1024,128}[which];
                    getPreferences(MODE_PRIVATE).edit().putInt("density",density).putBoolean("legacy",legacy).commit(); d.dismiss(); render();
                }}).setNegativeButton("Cancel",null).show();
    }
    private String profileLabel() { return legacy ? "Legacy · 1 MiB" : density == 128 ? "Easy focus · 64 MiB" : density == 1024 ? "Dense · 64 MiB" : "Standard · 64 MiB"; }
    /** Android 4 has no system Documents picker; this local browser works even without another app. */
    private void browse(final File directory) {
        File[] listed = directory.listFiles();
        if (listed == null) listed = new File[0];
        Arrays.sort(listed, new Comparator<File>() { public int compare(File a, File b) {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName());
        }});
        final File[] files = Arrays.copyOf(listed, Math.min(1000, listed.length));
        String[] labels = new String[files.length+2]; labels[0] = "Use another app's file picker…";
        labels[1] = "↑ Parent folder / storage devices";
        for (int i=0;i<files.length;i++) labels[i+2] = (files[i].isDirectory() ? "▸ " : "") + files[i].getName();
        new AlertDialog.Builder(this).setTitle(directory.getAbsolutePath())
                .setItems(labels, new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int index) {
                    if (index == 0) { systemPicker(); return; }
                    if (index == 1) { File parent=directory.getParentFile(); browse(parent==null ? directory : parent); return; }
                    File file = files[index-2];
                    if (file.isDirectory()) browse(file);
                    else if (!file.isFile() || !file.canRead()) Toast.makeText(Arc7QrTransferHomeActivity.this,"That file cannot be read",Toast.LENGTH_LONG).show();
                    else if (file.length()>Arc7QrTransfer.MAX_BYTES) Toast.makeText(Arc7QrTransferHomeActivity.this,"Choose a file up to 64 MiB",Toast.LENGTH_LONG).show();
                    else { select(Uri.fromFile(file)); render(); }
                }}).setNeutralButton("Enter a path…",new DialogInterface.OnClickListener(){ public void onClick(DialogInterface dialog,int ignored){
                    final android.widget.EditText input=new android.widget.EditText(Arc7QrTransferHomeActivity.this); input.setSingleLine(true);input.setText(directory.getAbsolutePath()+"/");
                    new AlertDialog.Builder(Arc7QrTransferHomeActivity.this).setTitle("Local file or folder").setView(input)
                            .setPositiveButton("Choose",new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int which){
                                File file=new File(input.getText().toString());if(file.isDirectory())browse(file);
                                else if(file.isFile()&&file.canRead()&&file.length()<=Arc7QrTransfer.MAX_BYTES){select(Uri.fromFile(file));render();}
                                else Toast.makeText(Arc7QrTransferHomeActivity.this,"Choose a readable file up to 64 MiB",Toast.LENGTH_LONG).show();
                            }}).setNegativeButton("Cancel",null).show();
                }}).setNegativeButton("Cancel",null).show();
    }
    private void systemPicker() {
        try { Intent pick=new Intent(Intent.ACTION_GET_CONTENT);pick.setType("*/*");pick.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(Intent.createChooser(pick,"File to send optically"),PICK); }
        catch (RuntimeException error) { Toast.makeText(this,"No other picker installed. Use the built-in storage browser.",Toast.LENGTH_LONG).show(); }
    }
    private void select(Uri uri) {
        if (uri == null || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme()))) return;
        String name = uri.getLastPathSegment(); Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException ignored) {} finally { if (cursor != null) cursor.close(); }
        try { selectedName = Arc7QrTransfer.safeName(name); selected = uri; }
        catch (Exception error) { Toast.makeText(this, "Could not select that file", Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (request == PICK && result == RESULT_OK && data != null) { select(data.getData()); render(); }
    }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setPadding(0,dp(5),0,dp(12)); return view; }
    private Button button(LinearLayout box,String label,boolean primary,View.OnClickListener listener) { Button b=primary?Arc7Skin.actionButton(this,label,palette.accent):Arc7Skin.secondaryButton(this,palette,label);b.setOnClickListener(listener);box.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));return b; }
    private void card(LinearLayout box) { LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(16);params.bottomMargin=dp(8);content.addView(box,params); }
    private int dp(int value) { return Arc7Skin.dp(this,value); }
}

package ca.arc7freedom.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local encrypted apps. Secrets are never written to preferences, state bundles or logs. */
public abstract class Arc7VaultActivity extends Arc7Activity {
    private final Handler ui=new Handler();
    private final List<EditText> sensitive=new ArrayList<EditText>();
    private final List<TextView> codeViews=new ArrayList<TextView>();
    private final List<JSONObject> codeAccounts=new ArrayList<JSONObject>();
    private Arc7VaultCrypto.Session session;
    private JSONArray entries;
    private LinearLayout root, list;
    private TextView status;
    private Arc7Skin.Palette palette;
    private AlertDialog dialog;
    private Thread worker;
    private final Object vaultIo=new Object();
    private volatile int generation;
    private volatile boolean resumed;
    private boolean busy;
    private String clipboardValue;
    protected abstract boolean authenticator();
    private String purpose() { return authenticator() ? "authenticator" : "passwords"; }
    private String title() { return authenticator() ? "Authenticator" : "Passwords"; }
    private File vault() { return new File(getFilesDir(),purpose()+".arc7vault"); }
    private final Runnable autoLock=new Runnable() { public void run() { lock(); } };
    private final Runnable clearClipboard=new Runnable() { public void run() { clearOwnedClipboard(); } };
    private final Runnable ticker=new Runnable() {
        public void run() {
            if(!resumed || session==null) return;
            long now=System.currentTimeMillis()/1000L;
            for(int i=0;i<codeViews.size();i++) {
                JSONObject a=codeAccounts.get(i);
                try {
                    int period=a.optInt("period",30);
                    codeViews.get(i).setText(Arc7Totp.generate(a.getString("secret"),a.optString("algorithm","SHA1"),a.optInt("digits",6),period,now)
                            + "   ·   " +(period-now%period)+"s");
                } catch(Exception invalid) { codeViews.get(i).setText("Invalid account — edit or remove"); }
            }
            ui.postDelayed(this,1000);
        }
    };
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (!("ca.arc7freedom."+purpose()).equals(getPackageName())) { finish(); return; }
        palette=Arc7Skin.current(this); render();
    }
    @Override protected void onResume() { super.onResume(); resumed=true; if(session!=null) { touch(); ui.removeCallbacks(ticker); ticker.run(); } }
    @Override protected void onStop() { resumed=false; lock(false); super.onStop(); }
    @Override protected void onDestroy() { lock(); ui.removeCallbacks(clearClipboard); clearOwnedClipboard(); super.onDestroy(); }
    @Override public void onUserInteraction() { super.onUserInteraction(); touch(); }
    private void touch() { ui.removeCallbacks(autoLock); if(session!=null) ui.postDelayed(autoLock,120000); }
    private void lock() { lock(true); }
    private void lock(boolean clearCopied) {
        synchronized(vaultIo) { ++generation; if(worker!=null) worker.interrupt(); } worker=null; busy=false;
        ui.removeCallbacks(autoLock); ui.removeCallbacks(ticker); if(clearCopied) clearOwnedClipboard();
        if(dialog!=null) { dialog.dismiss(); dialog=null; }
        for(EditText field:sensitive) field.setText(""); sensitive.clear();
        for(TextView field:codeViews) field.setText(""); codeViews.clear(); codeAccounts.clear();
        if(session!=null) session.close(); session=null; entries=null;
        if(!isFinishing() && root!=null) render();
    }
    private TextView text(LinearLayout box,String value) {
        TextView t=new TextView(this); t.setText(value); t.setTextColor(palette.textPrimary); t.setTextSize(14); t.setPadding(0,8,0,8); box.addView(t); return t;
    }
    private Button button(LinearLayout box,String label,View.OnClickListener click) {
        Button b=Arc7Skin.secondaryButton(this,palette,label); b.setOnClickListener(click); box.addView(b,new LinearLayout.LayoutParams(-1,-2)); return b;
    }
    private EditText input(LinearLayout box,String hint,String value,boolean secret) {
        EditText e=new EditText(this); e.setHint(hint); e.setSingleLine(!hint.equals("Notes"));
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setSaveEnabled(false); e.setFreezesText(false);
        if(secret) e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setText(value); box.addView(e); sensitive.add(e); return e;
    }
    @SuppressWarnings("deprecation") private void render() {
        codeViews.clear(); codeAccounts.clear();
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundDrawable(Arc7Skin.pageDrawable(palette));
        root.addView(Arc7Skin.buildAppTopBar(this,palette,authenticator() ? R.drawable.ic_authenticator : R.drawable.ic_passwords,title(),"Offline · encrypted · auto-lock",null));
        ScrollView scroll=new ScrollView(this); list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(16,8,16,24); scroll.addView(list); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        status=text(list,busy ? "Working… Key derivation may take time on this tablet." : session==null ? "Locked" : "Unlocked · locks when you leave or after 2 minutes idle");
        if(session==null) {
            text(list,"Android 4.x is no longer security-supported. Root, a compromised keyboard or malware can expose unlocked secrets. These apps have not had an independent security audit. Use a strong unique master password and keep encrypted backups elsewhere. There is no recovery or cloud sync.");
            if(busy) button(list,"Cancel operation",new View.OnClickListener(){ public void onClick(View v){ lock(); }});
            button(list,vault().exists() ? "Unlock" : "Create encrypted vault",new View.OnClickListener(){ public void onClick(View v){ if(!busy) promptUnlock(null); }});
            button(list,"Restore encrypted backup…",new View.OnClickListener(){ public void onClick(View v){ chooseBackup(); }});
            return;
        }
        button(list,authenticator() ? "Add 2FA account" : "Add password",new View.OnClickListener(){ public void onClick(View v){ edit(-1); }});
        button(list,"Import Proton Pass / Bitwarden export…",new View.OnClickListener(){ public void onClick(View v){ chooseImport(); }});
        button(list,"Lock now",new View.OnClickListener(){ public void onClick(View v){ lock(); }});
        button(list,"Export encrypted backup",new View.OnClickListener(){ public void onClick(View v){ exportBackup(); }});
        button(list,"Change master password",new View.OnClickListener(){ public void onClick(View v){ promptChangePassword(); }});
        if(authenticator()) text(list,"Codes need the correct device date and time. Enter a Base32 key or paste an otpauth://totp URI. You can import TOTP secrets included in an unencrypted Proton Pass or Bitwarden export. HOTP, QR scanning, push approvals and recovery codes are not supported. Keep your provider's recovery codes separately.");
        else text(list,"Imports are processed locally from an unencrypted Proton Pass or Bitwarden CSV/JSON export. Login TOTP secrets are kept with the password and can be copied as a current code; import the same file in Authenticator to keep codes there too.");
        if(entries.length()==0) text(list,"No entries yet. Your master password is never stored.");
        for(int i=0;i<entries.length();i++) {
            final int index=i; final JSONObject item=entries.optJSONObject(i); if(item==null) continue;
            LinearLayout card=Arc7Skin.card(this,palette); list.addView(card);
            text(card,item.optString("title","Untitled")+(authenticator() && item.optString("issuer").length()>0 ? " · "+item.optString("issuer") : ""));
            if(authenticator()) {
                TextView code=text(card,""); code.setTextSize(26); codeViews.add(code); codeAccounts.add(item);
                button(card,"Copy current code (clears in 30s)",new View.OnClickListener(){ public void onClick(View v){
                    try { copy(Arc7Totp.generate(item.getString("secret"),item.optString("algorithm","SHA1"),item.optInt("digits",6),item.optInt("period",30),System.currentTimeMillis()/1000)); }
                    catch(Exception e){ message("Cannot generate code. Check account settings and device time."); }
                }});
            } else {
                text(card,item.optString("username")+ (item.optString("url").length()>0 ? "\n"+item.optString("url") : ""));
                button(card,"Copy username",new View.OnClickListener(){ public void onClick(View v){ copy(item.optString("username")); }});
                button(card,"Copy password (clears in 30s)",new View.OnClickListener(){ public void onClick(View v){ copy(item.optString("password")); }});
                if(item.optString("totp").length()>0) button(card,"Copy current 2FA code (clears in 30s)",new View.OnClickListener(){ public void onClick(View v){
                    try { copy(Arc7Totp.generate(item.getString("totp"),item.optString("totp_algorithm","SHA1"),item.optInt("totp_digits",6),item.optInt("totp_period",30),System.currentTimeMillis()/1000)); }
                    catch(Exception invalid){ message("Cannot generate this 2FA code. Edit the entry and check the secret and device time."); }
                }});
            }
            button(card,"Edit / remove",new View.OnClickListener(){ public void onClick(View v){ edit(index); }});
        }
        ui.removeCallbacks(ticker); if(resumed) ticker.run(); touch();
    }
    private LinearLayout form() { LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(20,8,20,8); return b; }
    private ScrollView scrolled(LinearLayout b) { ScrollView s=new ScrollView(this); s.addView(b); return s; }
    private void promptUnlock(final byte[] imported) {
        if(busy) return;
        final boolean creating=imported==null && !vault().exists();
        LinearLayout b=form(); text(b,creating ? "At least 10 characters; a long passphrase is recommended. Forgotten passwords cannot be reset." : imported!=null ? "Backup master password. Successful restore replaces this app's current vault; back it up first." : "Enter your master password.");
        final EditText password=input(b,"Master password","",true);
        final EditText confirm=creating ? input(b,"Confirm master password","",true) : null;
        dialog=new AlertDialog.Builder(this).setTitle(creating ? "Create vault" : imported!=null ? "Restore and replace vault" : "Unlock "+title()).setView(scrolled(b))
            .setPositiveButton(creating ? "Create" : imported!=null ? "Restore / replace" : "Unlock",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener(){ public void onShow(DialogInterface ignored){ dialog.getButton(-1).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            final char[] chars=password.getText().toString().toCharArray();
            if(creating && (chars.length<10 || !password.getText().toString().equals(confirm.getText().toString()))) { Arc7VaultCrypto.wipe(chars); password.setError("Use 10+ matching characters"); return; }
            password.setText(""); if(confirm!=null) confirm.setText(""); dialog.dismiss(); dialog=null; sensitive.clear();
            busy=true; render(); final int request=++generation;
            worker=new Thread(new Runnable(){ public void run(){
                Arc7VaultCrypto.Session opened=null; byte[] plain=null;
                try {
                    final JSONArray data;
                    if(creating) { opened=Arc7VaultCrypto.create(chars,purpose()); data=new JSONArray(); plain="[]".getBytes("UTF-8"); }
                    else {
                        Arc7VaultCrypto.Opened result=Arc7VaultCrypto.open(imported!=null ? imported : Arc7VaultCrypto.read(new FileInputStream(vault())),chars,purpose());
                        opened=result.session; plain=result.plaintext; data=new JSONArray(new String(plain,"UTF-8")); validate(data);
                    }
                    if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    if(creating || imported!=null) saveCurrent(request,Arc7VaultCrypto.seal(opened,plain));
                    final Arc7VaultCrypto.Session ready=opened; opened=null;
                    ui.post(new Runnable(){ public void run(){ if(request!=generation || !resumed) { ready.close(); return; } session=ready; entries=data; busy=false; worker=null; render(); }});
                } catch(final Exception e) { ui.post(new Runnable(){ public void run(){ if(request!=generation) return; busy=false; worker=null; render(); message("Could not open vault. Check the master password, backup format and available storage. Existing data is retained."); }}); }
                finally { Arc7VaultCrypto.wipe(chars); Arc7VaultCrypto.wipe(plain); if(opened!=null) opened.close(); }
            }},"Arc7-Vault-Unlock"); worker.start();
        }}); }}); dialog.show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void validate(JSONArray data) throws Exception {
        if(data.length()>200) throw new IOException("At most 200 entries are supported");
        for(int i=0;i<data.length();i++) {
            JSONObject item=data.getJSONObject(i);
            if(item.optString("title").length()==0 || item.optString("title").length()>200) throw new IOException("Entry title is empty or too long");
            if(authenticator()) Arc7Totp.generate(item.getString("secret"),item.optString("algorithm","SHA1"),item.optInt("digits",6),item.optInt("period",30),0);
            else if(item.optString("totp").length()>0) Arc7Totp.generate(item.getString("totp"),item.optString("totp_algorithm","SHA1"),item.optInt("totp_digits",6),item.optInt("totp_period",30),0);
        }
    }
    private void edit(final int index) {
        if(session==null || busy) return;
        final JSONObject old=index>=0 ? entries.optJSONObject(index) : new JSONObject();
        final LinearLayout b=form(); final EditText title=input(b,"Title / account",old.optString("title"),false);
        final EditText user=input(b,authenticator() ? "Issuer (optional)" : "Username",old.optString(authenticator() ? "issuer" : "username"),false);
        final EditText secret=input(b,authenticator() ? "Base32 secret or otpauth://totp URI" : "Password",old.optString(authenticator() ? "secret" : "password"),true);
        final EditText passwordTotp=authenticator() ? null : input(b,"2FA secret or otpauth://totp URI (optional)",old.optString("totp"),true);
        final EditText url=input(b,authenticator() ? "Algorithm: SHA1, SHA256 or SHA512" : "Website (not opened automatically)",old.optString(authenticator() ? "algorithm" : "url",authenticator() ? "SHA1" : ""),false);
        final EditText notes=input(b,authenticator() ? "Digits (6 or 8)" : "Notes",authenticator() ? ""+old.optInt("digits",6) : old.optString("notes"),false);
        final EditText period=authenticator() ? input(b,"Period in seconds (15–120)",""+old.optInt("period",30),false) : null;
        if(!authenticator()) button(b,"Generate random 24-character password",new View.OnClickListener(){ public void onClick(View v){ try { secret.setText(Arc7VaultCrypto.password(24,true)); } catch(Exception e){ message("Secure entropy unavailable. Nothing generated."); } }});
        dialog=new AlertDialog.Builder(this).setTitle(index<0 ? "Add entry" : "Edit entry").setView(scrolled(b)).setPositiveButton("Save",null).setNegativeButton("Cancel",null)
                .setNeutralButton(index>=0 ? "Remove" : null,index>=0 ? new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int which){
                    dialog=new AlertDialog.Builder(Arc7VaultActivity.this).setTitle("Remove this entry?").setMessage("This cannot be undone. Keep a backup first.")
                    .setPositiveButton("Remove",new DialogInterface.OnClickListener(){ public void onClick(DialogInterface x,int w){ change(index,null); }}).setNegativeButton("Cancel",null).show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                }} : null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener(){ public void onShow(DialogInterface d){ dialog.getButton(-1).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            try {
                JSONObject item=new JSONObject(); String label=title.getText().toString().trim();
                if(authenticator()) {
                    String value=secret.getText().toString(); Arc7Totp.Account a=Arc7Totp.parse(value);
                    if(!value.startsWith("otpauth://")) { a.algorithm=Arc7Totp.normalizeAlgorithm(url.getText().toString()); a.digits=Integer.parseInt(notes.getText().toString()); a.period=Integer.parseInt(period.getText().toString()); }
                    Arc7Totp.generate(a.secret,a.algorithm,a.digits,a.period,0);
                    if(label.length()==0) label=a.label;
                    item.put("issuer",user.getText().length()>0 ? user.getText().toString() : a.issuer).put("secret",a.secret).put("algorithm",a.algorithm).put("digits",a.digits).put("period",a.period);
                } else {
                    item.put("username",user.getText().toString()).put("password",secret.getText().toString()).put("url",url.getText().toString()).put("notes",notes.getText().toString());
                    String rawTotp=passwordTotp.getText().toString().trim();
                    if(rawTotp.length()>0) {
                        Arc7Totp.Account twoFactor=Arc7Totp.parse(rawTotp);
                        item.put("totp",twoFactor.secret).put("totp_algorithm",twoFactor.algorithm).put("totp_digits",twoFactor.digits).put("totp_period",twoFactor.period);
                    }
                }
                if(label.length()==0 || label.length()>200) throw new Exception("Enter a title of 1–200 characters"); item.put("title",label);
                dialog.dismiss(); dialog=null; for(EditText f:sensitive) f.setText(""); sensitive.clear(); change(index,item);
            } catch(Exception invalid){ message(safeError(invalid)); }
        }}); }}); dialog.show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void change(int index,JSONObject replacement) {
        if(session==null || busy) return;
        try {
            final JSONArray next=new JSONArray();
            for(int i=0;i<entries.length();i++) { if(i==index) { if(replacement!=null) next.put(replacement); } else next.put(entries.get(i)); }
            if(index<0 && replacement!=null) next.put(replacement); validate(next);
            // Serialization and AES are bounded; expensive password derivation only runs in a worker.
            byte[] plain=next.toString().getBytes("UTF-8");
            try { Arc7VaultCrypto.atomicWrite(vault(),Arc7VaultCrypto.seal(session,plain)); } finally { Arc7VaultCrypto.wipe(plain); }
            entries=next; render();
        } catch(Exception e){ message("Not saved; the original vault is unchanged. "+safeError(e)); }
    }
    private void promptChangePassword() {
        if(session==null || busy) return;
        LinearLayout b=form(); final EditText p=input(b,"New master password","",true), c=input(b,"Confirm new master password","",true);
        dialog=new AlertDialog.Builder(this).setTitle("Change master password").setView(scrolled(b)).setPositiveButton("Change",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener(){ public void onShow(DialogInterface d){ dialog.getButton(-1).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            final char[] chars=p.getText().toString().toCharArray();
            if(chars.length<10 || !p.getText().toString().equals(c.getText().toString())) { Arc7VaultCrypto.wipe(chars); p.setError("Use 10+ matching characters"); return; }
            p.setText(""); c.setText(""); dialog.dismiss(); dialog=null; sensitive.clear();
            try {
                final byte[] plain=entries.toString().getBytes("UTF-8"); final int request=++generation; busy=true; status.setText("Changing master password…");
                worker=new Thread(new Runnable(){ public void run(){ Arc7VaultCrypto.Session next=null;
                    try {
                        next=Arc7VaultCrypto.create(chars,purpose()); if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                        saveCurrent(request,Arc7VaultCrypto.seal(next,plain)); final Arc7VaultCrypto.Session ready=next; next=null;
                        ui.post(new Runnable(){ public void run(){ if(request!=generation || !resumed) { ready.close(); return; } if(session!=null) session.close(); session=ready; busy=false; worker=null; render(); message("Password changed. Older exported backups still use their original password."); }});
                    } catch(final Exception e){ ui.post(new Runnable(){ public void run(){ if(request==generation) { busy=false; status.setText("Password change failed; unlock to confirm the saved vault."); message(safeError(e)); } }}); }
                    finally { if(next!=null) next.close(); Arc7VaultCrypto.wipe(chars); Arc7VaultCrypto.wipe(plain); }
                }},"Arc7-Vault-Rekey"); worker.start();
            } catch(Exception e){ Arc7VaultCrypto.wipe(chars); busy=false; message("Password not changed."); }
        }}); }}); dialog.show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void saveCurrent(int request,byte[] sealed) throws Exception {
        try { synchronized(vaultIo) {
            if(request!=generation || !resumed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Vault operation cancelled");
            Arc7VaultCrypto.atomicWrite(vault(),sealed);
        }} finally { Arc7VaultCrypto.wipe(sealed); }
    }
    private void copy(String value) {
        if(session==null) return;
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard==null) return; clipboard.setPrimaryClip(ClipData.newPlainText(title(),value)); clipboardValue=value;
        ui.removeCallbacks(clearClipboard); ui.postDelayed(clearClipboard,30000); message("Copied for 30 seconds. Other apps may read the clipboard.");
    }
    private void clearOwnedClipboard() {
        try {
            ClipboardManager c=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(clipboardValue!=null && c!=null && c.hasPrimaryClip() && c.getPrimaryClip().getItemCount()>0
                    && clipboardValue.contentEquals(c.getPrimaryClip().getItemAt(0).coerceToText(this))) c.setPrimaryClip(ClipData.newPlainText("",""));
        } catch(Exception ignored) {} clipboardValue=null;
    }
    private void chooseImport() {
        if(busy || session==null) return;
        try {
            Intent picker=new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(picker,"Choose a Proton Pass or Bitwarden export"),7202);
        } catch(Exception missing) { message("No file picker is installed. Copy a CSV or JSON export to the Kobo and try again."); }
    }
    private void importExport(Uri uri) {
        if(busy || session==null) return;
        byte[] bytes=null;
        try {
            if(!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) throw new IOException("Choose a local CSV or JSON export");
            bytes=Arc7VaultImporter.read(getContentResolver().openInputStream(uri));
            Arc7VaultImporter.Batch batch=Arc7VaultImporter.parse(bytes);
            Arc7VaultCrypto.wipe(bytes); bytes=null;
            final JSONArray incoming=authenticator() ? batch.authenticators : batch.passwords;
            if(incoming.length()==0) {
                if(authenticator() && batch.passwords.length()>0)
                    message("Found " + batch.passwords.length() + " login records but no valid TOTP secrets. Export TOTP fields from the provider or choose an Authenticator export.");
                else if(!authenticator() && batch.authenticators.length()>0)
                    message("Found " + batch.authenticators.length() + " TOTP codes but no login records. Import this file in Authenticator instead.");
                else message("No supported " + (authenticator() ? "TOTP accounts" : "login entries") + " were found. Choose an unencrypted Proton Pass or Bitwarden CSV/JSON export.");
                return;
            }
            final Arc7VaultImporter.Merge merge=Arc7VaultImporter.merge(entries,incoming,purpose());
            if(merge.added==0) { message("No new entries to import. All " + merge.duplicates + " matching entries were already in this vault."); return; }
            String summary="Found " + batch.passwords.length() + " login entries and " + batch.authenticators.length() + " valid 2FA accounts in " + batch.source + ".\n\n"
                    + "Import " + merge.added + " new " + (authenticator() ? "2FA account(s)" : "login(s)")
                    + " and skip " + merge.duplicates + " duplicate(s)? Existing entries will be kept; nothing is uploaded.";
            if(batch.invalidTotp>0) summary += "\n\n" + batch.invalidTotp + " invalid or unsupported TOTP value(s) will not be imported.";
            if(authenticator() && batch.passwords.length()>0) summary += "\n\nImport the same export in Passwords to add its logins there.";
            if(!authenticator() && batch.authenticators.length()>0) summary += "\n\nImport the same export in Authenticator to keep the 2FA accounts there too.";
            dialog=new AlertDialog.Builder(this).setTitle("Review local import")
                    .setMessage(summary)
                    .setPositiveButton("Import " + merge.added, new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int which){
                        try {
                            validate(merge.entries);
                            byte[] plain=merge.entries.toString().getBytes("UTF-8");
                            try { Arc7VaultCrypto.atomicWrite(vault(),Arc7VaultCrypto.seal(session,plain)); }
                            finally { Arc7VaultCrypto.wipe(plain); }
                            entries=merge.entries; render();
                            message("Imported " + merge.added + " entr" + (merge.added==1 ? "y" : "ies") + ". Existing vault data was merged, not replaced.");
                        } catch(Exception failure) { message("Import was not saved; existing vault data is unchanged. " + safeError(failure)); }
                    }})
                    .setNegativeButton("Cancel",null).create();
            dialog.show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } catch(Exception invalid) {
            Arc7VaultCrypto.wipe(bytes);
            message("Import failed; the existing vault is unchanged. " + safeError(invalid));
        }
    }
    private void chooseBackup() {
        if(busy) return;
        final EditText path=new EditText(this); path.setSingleLine(true);
        path.setHint("/sdcard/Download/Arc7Backups/backup.arc7vault"); path.setSaveEnabled(false);
        new AlertDialog.Builder(this).setTitle("Restore local encrypted backup").setView(path)
            .setMessage("Enter the full path to an .arc7vault file, or use an installed file picker. Exported backups are in Download/Arc7Backups. You will confirm its master password before replacing any data.")
            .setPositiveButton("Read local file",new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int w){
                String value=path.getText().toString().trim();
                if(!value.startsWith("/") || !value.endsWith(".arc7vault")) { message("Enter an absolute path to an .arc7vault backup."); return; }
                readBackup(Uri.fromFile(new File(value)));
            }}).setNeutralButton("File picker",new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int w){
                try { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),7201); }
                catch(Exception missing){ message("No file picker installed. Use the local file path instead."); }
            }}).setNegativeButton("Cancel",null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null) return;
        if(request==7201) readBackup(data.getData());
        else if(request==7202) importExport(data.getData());
    }
    private void readBackup(Uri uri) {
        if(busy) return;
        try {
            if(!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) throw new IOException("Use a local backup file");
            byte[] bytes=Arc7VaultCrypto.read(getContentResolver().openInputStream(uri)); promptUnlock(bytes);
        } catch(Exception invalid){ message("Could not read encrypted backup. "+safeError(invalid)); }
    }
    private void exportBackup() {
        dialog=new AlertDialog.Builder(this).setTitle("Export encrypted backup?").setMessage("Writes only encrypted data to Download/Arc7Backups. Anyone with the file can attempt to guess your master password. Copy it to a safe location; Android uninstall removes the private vault.")
            .setPositiveButton("Export encrypted file",new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int which){
                try {
                    File folder=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"Arc7Backups");
                    File out=new File(folder,purpose()+"-"+System.currentTimeMillis()+".arc7vault");
                    Arc7VaultCrypto.atomicWrite(out,Arc7VaultCrypto.read(new FileInputStream(vault()))); message("Encrypted backup saved:\n"+out.getAbsolutePath());
                } catch(Exception e){ message("Backup was not exported. "+safeError(e)); }
            }}).setNegativeButton("Cancel",null).show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private String safeError(Exception e) {
        String text=e.getMessage(); return text==null || text.length()>180 ? "Check input and available storage." : text;
    }
    private void message(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
}

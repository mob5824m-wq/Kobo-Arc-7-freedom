package ca.arc7freedom.support;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/** Private early bootstrap for repackaged F-Droid; exposes no provider data or callable actions. */
public final class SupportProvider extends ContentProvider {
    @Override public boolean onCreate() {
        if (getContext() != null && getContext().getApplicationContext() instanceof Application)
            AppSupport.install((Application)getContext().getApplicationContext());
        if(getContext()!=null && "eu.bubu1.fdroidclassic.debug".equals(getContext().getPackageName())) {
            java.io.File old=new java.io.File(getContext().getFilesDir(),"arc7-last-crash.txt");
            if(old.isFile())old.delete(); // obsolete unbounded/raw log only; never user data
        }
        return true;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String order) { return null; }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] arguments) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] arguments) { throw new UnsupportedOperationException(); }
}

package ca.arc7freedom.support;

import android.app.Application;

/** Generic bootstrap for Home, Aurora, upgrader and the source-built VLC fallback. */
public class SupportApplication extends Application {
    @Override public void onCreate() { super.onCreate(); AppSupport.install(this); }
}

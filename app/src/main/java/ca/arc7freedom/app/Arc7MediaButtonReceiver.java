package ca.arc7freedom.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import ca.arc7freedom.support.DebugLog;

/** Registered with AudioManager only by the focused/prepared owner, never by silent watcher APKs. */
public final class Arc7MediaButtonReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Arc7AudioService.acceptsMediaButtons() || intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent key = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (key == null || key.getAction() != KeyEvent.ACTION_DOWN || key.getRepeatCount() != 0) return;
        String action = actionFor(key.getKeyCode());
        if (action == null) return;
        DebugLog.number("audio.media_button", key.getKeyCode());
        try { context.startService(new Intent(context, Arc7AudioService.class).setAction(action));
            if (isOrderedBroadcast()) abortBroadcast();
        } catch (Exception failure) { DebugLog.error("audio.media_button_failed", failure); }
    }
    public static String actionFor(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return Arc7AudioService.ACTION_TOGGLE;
            case KeyEvent.KEYCODE_MEDIA_PLAY: return Arc7AudioService.ACTION_PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return Arc7AudioService.ACTION_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_NEXT: return Arc7AudioService.ACTION_NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return Arc7AudioService.ACTION_PREV;
            case KeyEvent.KEYCODE_MEDIA_REWIND: return Arc7AudioService.ACTION_REWIND_30;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return Arc7AudioService.ACTION_FFWD_30;
            case KeyEvent.KEYCODE_MEDIA_STOP: return Arc7AudioService.ACTION_STOP_DISMISS;
            default: return null;
        }
    }
}

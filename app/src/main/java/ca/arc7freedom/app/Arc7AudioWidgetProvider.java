package ca.arc7freedom.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Built-in Home Screen **Music & Now Playing** Widget (`Prev`, `Play/Pause`, `Next`, Open Active Player).
 * Displays ANY playing audio across `YouTube Music`, `YouTube`, `Music`, `Radio & Podcasts`,
 * `Voice Recorder`, and `VLC` via {@link NowPlayingManager}.
 */
public class Arc7AudioWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAll(context, appWidgetManager, appWidgetIds, null, false);
    }

    public static void refreshAll(Context context, String trackTitle, boolean playing) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            ComponentName cn = new ComponentName(context, Arc7AudioWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids != null && ids.length > 0) {
                updateAll(context, mgr, ids, trackTitle, playing);
            }
        } catch (Throwable ignored) {}
    }

    private static void updateAll(Context context, AppWidgetManager mgr, int[] ids,
                                  String trackTitle, boolean playing) {
        NowPlayingManager.Snapshot st = NowPlayingManager.getSnapshot(context);
        String title = trackTitle;
        boolean isPlaying = playing;
        String sub = "";

        if ((title == null || title.isEmpty()) && st != null && st.active && st.title != null && !st.title.isEmpty()) {
            title = st.title;
            isPlaying = st.playing;
            sub = (st.sourceApp != null && !st.sourceApp.isEmpty() ? st.sourceApp + "  •  " : "")
                    + (st.artist != null ? st.artist : "");
        }
        if (title == null || title.isEmpty()) {
            String last = context.getSharedPreferences("arc7_audio_bookmarks", Context.MODE_PRIVATE)
                    .getString("last_track", "");
            if (!last.isEmpty()) {
                int slash = last.lastIndexOf('/');
                title = slash >= 0 ? last.substring(slash + 1) : last;
            } else {
                title = "Music & Now Playing";
            }
        }
        if (sub.isEmpty()) {
            sub = isPlaying ? "Playing now  •  Tap to open player"
                    : "Paused / Ready  •  Tap Play or title to open";
        }

        Intent openUi = NowPlayingManager.buildOpenPlayerIntent(context, st);
        PendingIntent piOpen = PendingIntent.getActivity(
                context, 7201, openUi, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent rew = new Intent(context, Arc7AudioService.class);
        rew.setAction(Arc7AudioService.ACTION_PREV);
        PendingIntent piRew = PendingIntent.getService(
                context, 7202, rew, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent toggle = new Intent(context, Arc7AudioService.class);
        toggle.setAction(Arc7AudioService.ACTION_TOGGLE);
        PendingIntent piToggle = PendingIntent.getService(
                context, 7203, toggle, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent fwd = new Intent(context, Arc7AudioService.class);
        fwd.setAction(Arc7AudioService.ACTION_NEXT);
        PendingIntent piFwd = PendingIntent.getService(
                context, 7204, fwd, PendingIntent.FLAG_UPDATE_CURRENT);

        for (int i = 0; i < ids.length; i++) {
            RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_arc7_audio);
            rv.setTextViewText(R.id.widget_audio_title, title);
            rv.setTextViewText(R.id.widget_audio_sub, sub);
            rv.setTextViewText(R.id.widget_audio_toggle, isPlaying ? "Pause" : "Play");
            rv.setOnClickPendingIntent(R.id.widget_audio_root, piOpen);
            rv.setOnClickPendingIntent(R.id.widget_audio_icon, piOpen);
            rv.setOnClickPendingIntent(R.id.widget_audio_title, piOpen);
            rv.setOnClickPendingIntent(R.id.widget_audio_sub, piOpen);
            rv.setOnClickPendingIntent(R.id.widget_audio_rew, piRew);
            rv.setOnClickPendingIntent(R.id.widget_audio_toggle, piToggle);
            rv.setOnClickPendingIntent(R.id.widget_audio_fwd, piFwd);
            mgr.updateAppWidget(ids[i], rv);
        }
    }
}

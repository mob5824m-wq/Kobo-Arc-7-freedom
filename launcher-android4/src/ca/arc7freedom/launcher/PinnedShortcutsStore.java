package ca.arc7freedom.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists user-pinned app shortcuts AND live Android Home Screen AppWidgets across
 * 3 swipeable home desktop pages inside {@code SharedPreferences("vanilla_home_pages")}.
 */
public final class PinnedShortcutsStore {

    private static final String PREFS = "vanilla_home_pages";

    public static final class WidgetRecord {
        public final int appWidgetId;
        public final int heightDp;

        public WidgetRecord(int appWidgetId, int heightDp) {
            this.appWidgetId = appWidgetId;
            this.heightDp = heightDp;
        }
    }

    private PinnedShortcutsStore() {}

    public static List<String> loadPage(Context ctx, int pageIndex) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString("page_" + pageIndex, "");
        List<String> list = new ArrayList<String>();
        if (raw == null || raw.isEmpty()) return list;
        String[] parts = raw.split("\\n");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!p.isEmpty() && p.contains("/") && !list.contains(p)) {
                list.add(p);
            }
        }
        return list;
    }

    public static void addShortcut(Context ctx, int pageIndex, String pkg, String cls) {
        List<String> list = loadPage(ctx, pageIndex);
        String entry = pkg + "/" + cls;
        if (!list.contains(entry)) {
            list.add(entry);
            savePage(ctx, pageIndex, list);
        }
    }

    public static void removeShortcut(Context ctx, int pageIndex, String pkg, String cls) {
        List<String> list = loadPage(ctx, pageIndex);
        String entry = pkg + "/" + cls;
        if (list.remove(entry)) {
            savePage(ctx, pageIndex, list);
        }
    }

    private static void savePage(Context ctx, int pageIndex, List<String> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(entries.get(i));
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("page_" + pageIndex, sb.toString())
                .commit();
    }

    /* -------------------- Home Screen AppWidgets Per Page -------------------- */

    public static List<WidgetRecord> loadWidgets(Context ctx, int pageIndex) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString("widgets_page_" + pageIndex, "");
        List<WidgetRecord> out = new ArrayList<WidgetRecord>();
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split("\\n");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) continue;
            int sep = p.indexOf(':');
            try {
                if (sep > 0) {
                    int id = Integer.parseInt(p.substring(0, sep));
                    int h = Integer.parseInt(p.substring(sep + 1));
                    out.add(new WidgetRecord(id, Math.max(72, Math.min(360, h))));
                } else {
                    out.add(new WidgetRecord(Integer.parseInt(p), 130));
                }
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public static void addWidget(Context ctx, int pageIndex, int appWidgetId, int heightDp) {
        List<WidgetRecord> list = loadWidgets(ctx, pageIndex);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).appWidgetId == appWidgetId) return;
        }
        list.add(new WidgetRecord(appWidgetId, Math.max(80, Math.min(340, heightDp))));
        saveWidgets(ctx, pageIndex, list);
    }

    public static void resizeWidget(Context ctx, int pageIndex, int appWidgetId, int newHeightDp) {
        List<WidgetRecord> list = loadWidgets(ctx, pageIndex);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).appWidgetId == appWidgetId) {
                list.set(i, new WidgetRecord(appWidgetId, Math.max(80, Math.min(340, newHeightDp))));
                saveWidgets(ctx, pageIndex, list);
                return;
            }
        }
    }

    public static void removeWidgetEverywhere(Context ctx, int appWidgetId, int pageCount) {
        for (int p = 0; p < pageCount; p++) {
            List<WidgetRecord> list = loadWidgets(ctx, p);
            boolean changed = false;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).appWidgetId == appWidgetId) {
                    list.remove(i);
                    changed = true;
                }
            }
            if (changed) saveWidgets(ctx, p, list);
        }
    }

    private static void saveWidgets(Context ctx, int pageIndex, List<WidgetRecord> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(list.get(i).appWidgetId).append(':').append(list.get(i).heightDp);
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("widgets_page_" + pageIndex, sb.toString())
                .commit();
    }
}

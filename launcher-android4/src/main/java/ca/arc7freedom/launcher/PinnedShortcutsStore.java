package ca.arc7freedom.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists multi-page Home Desktops (`3..5` pages), Free-Cell Drag & Drop Grid Slots (`@empty`),
 * App Folders (`@folder:Name|pkg/cls;...`), Desktop Grid Column & Row Density (`4..7` cols, `2..5` rows),
 * Custom All-Apps Drawer Order, 4-Digit App PIN Lock, JSON Layout Backup & Restore,
 * AND live Android Home Screen AppWidgets inside {@code SharedPreferences("vanilla_home_pages")}.
 */
public final class PinnedShortcutsStore {

    private static final String PREFS = "vanilla_home_pages";
    public static final String FOLDER_PREFIX = "@folder:";
    public static final String EMPTY_SLOT = "@empty";

    public static final class WidgetRecord {
        public final int appWidgetId;
        public final int heightDp;

        public WidgetRecord(int appWidgetId, int heightDp) {
            this.appWidgetId = appWidgetId;
            this.heightDp = heightDp;
        }
    }

    private PinnedShortcutsStore() {}

    /* -------------------- Multi-Page Count & Grid Density (`v52`/`v53`) -------------------- */

    public static int getPageCount(Context ctx) {
        int n = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("page_count", 3);
        return Math.max(3, Math.min(5, n));
    }

    public static void setPageCount(Context ctx, int pages) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt("page_count", Math.max(3, Math.min(5, pages))).commit();
    }

    public static int getDesktopColumns(Context ctx) {
        int c = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("desktop_columns", 5);
        return Math.max(4, Math.min(7, c));
    }

    public static void setDesktopColumns(Context ctx, int cols) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt("desktop_columns", Math.max(4, Math.min(7, cols))).commit();
    }

    public static int getDesktopRows(Context ctx) {
        int r = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("desktop_rows", 3);
        return Math.max(2, Math.min(5, r));
    }

    public static void setDesktopRows(Context ctx, int rows) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt("desktop_rows", Math.max(2, Math.min(5, rows))).commit();
    }

    public static int getDesktopTotalCells(Context ctx) {
        return getDesktopColumns(ctx) * getDesktopRows(ctx);
    }

    /* -------------------- Desktop Shortcuts, Free-Cell Grid & App Folders -------------------- */

    public static boolean isEmptyToken(String token) {
        return token == null || token.trim().isEmpty() || EMPTY_SLOT.equals(token.trim());
    }

    public static boolean isFolderToken(String token) {
        return token != null && token.startsWith(FOLDER_PREFIX) && token.indexOf('|') > FOLDER_PREFIX.length();
    }

    public static String parseFolderName(String token) {
        if (!isFolderToken(token)) return "Folder";
        return token.substring(FOLDER_PREFIX.length(), token.indexOf('|'));
    }

    public static List<String> parseFolderMembers(String token) {
        List<String> out = new ArrayList<String>();
        if (!isFolderToken(token)) return out;
        String body = token.substring(token.indexOf('|') + 1);
        String[] parts = body.split(";");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!p.isEmpty() && p.contains("/")) out.add(p);
        }
        return out;
    }

    public static String buildFolderToken(String folderName, List<String> members) {
        String safeName = (folderName == null || folderName.trim().isEmpty())
                ? "Folder" : folderName.trim().replace("|", "-").replace("\n", " ");
        StringBuilder sb = new StringBuilder();
        sb.append(FOLDER_PREFIX).append(safeName).append('|');
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(members.get(i));
        }
        return sb.toString();
    }

    public static List<String> loadPage(Context ctx, int pageIndex) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString("page_" + pageIndex, "");
        List<String> list = new ArrayList<String>();
        if (raw == null || raw.isEmpty()) return list;
        String[] parts = raw.split("\\n");
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (EMPTY_SLOT.equals(p)) {
                list.add(EMPTY_SLOT);
            } else if (!p.isEmpty() && (p.contains("/") || isFolderToken(p)) && seen.add(p)) {
                list.add(p);
            }
        }
        return list;
    }

    /**
     * Loads a fixed-length desktop grid array of at least {@code totalCells} slots so users can
     * drag and drop icons onto ANY empty cell `(row, col)` anywhere on the Home Screen!
     */
    public static List<String> loadPageGridSlots(Context ctx, int pageIndex, int totalCells) {
        List<String> raw = loadPage(ctx, pageIndex);
        int targetLen = Math.max(totalCells, raw.size());
        List<String> out = new ArrayList<String>(targetLen);
        for (int i = 0; i < targetLen; i++) {
            if (i < raw.size() && !isEmptyToken(raw.get(i))) {
                out.add(raw.get(i));
            } else {
                out.add(EMPTY_SLOT);
            }
        }
        return out;
    }

    public static boolean hasAnyShortcut(Context ctx, int pageIndex) {
        List<String> list = loadPage(ctx, pageIndex);
        for (int i = 0; i < list.size(); i++) {
            if (!isEmptyToken(list.get(i))) return true;
        }
        return false;
    }

    public static void addShortcut(Context ctx, int pageIndex, String pkg, String cls) {
        addRawEntry(ctx, pageIndex, pkg + "/" + cls);
    }

    public static void addRawEntry(Context ctx, int pageIndex, String entry) {
        List<String> list = loadPage(ctx, pageIndex);
        if (list.contains(entry)) return;
        for (int i = 0; i < list.size(); i++) {
            if (isEmptyToken(list.get(i))) {
                list.set(i, entry);
                savePage(ctx, pageIndex, list);
                return;
            }
        }
        list.add(entry);
        savePage(ctx, pageIndex, list);
    }

    public static void removeShortcut(Context ctx, int pageIndex, String pkg, String cls) {
        removeRawEntry(ctx, pageIndex, pkg + "/" + cls);
    }

    public static void removeRawEntry(Context ctx, int pageIndex, String entry) {
        List<String> list = loadPage(ctx, pageIndex);
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            if (entry.equals(list.get(i))) {
                list.set(i, EMPTY_SLOT);
                changed = true;
            }
        }
        if (changed) savePage(ctx, pageIndex, trimTrailingEmpty(list));
    }

    public static void clearSlotAt(Context ctx, int pageIndex, int slotIndex, int totalCells) {
        List<String> slots = loadPageGridSlots(ctx, pageIndex, totalCells);
        if (slotIndex >= 0 && slotIndex < slots.size()) {
            slots.set(slotIndex, EMPTY_SLOT);
            savePage(ctx, pageIndex, trimTrailingEmpty(slots));
        }
    }

    /**
     * Moves an icon onto an empty grid slot OR cleanly swaps two occupied grid slots on `pageIndex`.
     */
    public static void moveOrSwapDesktopSlot(Context ctx, int pageIndex, int fromSlot, int toSlot, int totalCells) {
        int cap = Math.max(totalCells, Math.max(fromSlot, toSlot) + 1);
        List<String> slots = loadPageGridSlots(ctx, pageIndex, cap);
        if (fromSlot < 0 || toSlot < 0 || fromSlot >= slots.size() || toSlot >= slots.size() || fromSlot == toSlot) {
            return;
        }
        String moving = slots.get(fromSlot);
        String dest = slots.get(toSlot);
        if (isEmptyToken(moving)) return;
        slots.set(toSlot, moving);
        slots.set(fromSlot, isEmptyToken(dest) ? EMPTY_SLOT : dest);
        savePage(ctx, pageIndex, trimTrailingEmpty(slots));
    }

    /**
     * Moves a slot from `fromPage` to the first open cell on `toPage` (for cross-page drag/move!).
     */
    public static boolean moveDesktopSlotToPage(Context ctx, int fromPage, int fromSlot, int toPage, int totalCells) {
        if (fromPage == toPage) return false;
        List<String> src = loadPageGridSlots(ctx, fromPage, totalCells);
        if (fromSlot < 0 || fromSlot >= src.size()) return false;
        String moving = src.get(fromSlot);
        if (isEmptyToken(moving)) return false;
        src.set(fromSlot, EMPTY_SLOT);
        savePage(ctx, fromPage, trimTrailingEmpty(src));
        addRawEntry(ctx, toPage, moving);
        return true;
    }

    /**
     * Merges `dragSlot` into `targetSlot` as an App Folder (`@folder:...`) right on the Desktop Grid!
     */
    public static boolean mergeDesktopSlotsIntoFolder(Context ctx, int pageIndex,
                                                      int dragSlot, int targetSlot,
                                                      String folderName, int totalCells) {
        int cap = Math.max(totalCells, Math.max(dragSlot, targetSlot) + 1);
        List<String> slots = loadPageGridSlots(ctx, pageIndex, cap);
        if (dragSlot < 0 || targetSlot < 0 || dragSlot >= slots.size() || targetSlot >= slots.size()
                || dragSlot == targetSlot) {
            return false;
        }
        String dragged = slots.get(dragSlot);
        String target = slots.get(targetSlot);
        if (isEmptyToken(dragged) || isEmptyToken(target)) return false;

        List<String> members = new ArrayList<String>();
        String finalName = folderName;
        if (isFolderToken(target)) {
            if (finalName == null || finalName.trim().isEmpty()) finalName = parseFolderName(target);
            members.addAll(parseFolderMembers(target));
        } else {
            members.add(target);
        }
        if (isFolderToken(dragged)) {
            List<String> extra = parseFolderMembers(dragged);
            for (int i = 0; i < extra.size(); i++) {
                if (!members.contains(extra.get(i))) members.add(extra.get(i));
            }
        } else if (!members.contains(dragged)) {
            members.add(dragged);
        }
        if (members.size() < 2) return false;

        slots.set(dragSlot, EMPTY_SLOT);
        slots.set(targetSlot, buildFolderToken(
                (finalName == null || finalName.trim().isEmpty()) ? "Apps" : finalName, members));
        savePage(ctx, pageIndex, trimTrailingEmpty(slots));
        return true;
    }

    private static List<String> trimTrailingEmpty(List<String> in) {
        List<String> copy = new ArrayList<String>(in);
        while (!copy.isEmpty() && isEmptyToken(copy.get(copy.size() - 1))) {
            copy.remove(copy.size() - 1);
        }
        return copy;
    }

    public static void savePage(Context ctx, int pageIndex, List<String> entries) {
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

    /* -------------------- Custom All-Apps Drawer Order (`v53`) -------------------- */

    public static boolean isCustomDrawerSort(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("drawer_custom_sort", false);
    }

    public static void setCustomDrawerSort(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("drawer_custom_sort", enabled).commit();
    }

    public static List<String> loadDrawerOrder(Context ctx) {
        String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("drawer_order", "");
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split("\\n");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!p.isEmpty() && p.contains("/") && !out.contains(p)) out.add(p);
        }
        return out;
    }

    public static void saveDrawerOrder(Context ctx, List<String> componentKeys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < componentKeys.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(componentKeys.get(i));
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("drawer_custom_sort", true)
                .putString("drawer_order", sb.toString())
                .commit();
    }

    public static void moveDrawerOrderItem(Context ctx, List<String> currentKeys, int fromIdx, int toIdx) {
        if (fromIdx < 0 || toIdx < 0 || fromIdx >= currentKeys.size() || toIdx >= currentKeys.size()
                || fromIdx == toIdx) {
            return;
        }
        List<String> copy = new ArrayList<String>(currentKeys);
        String item = copy.remove(fromIdx);
        copy.add(toIdx, item);
        saveDrawerOrder(ctx, copy);
    }

    /* -------------------- 4-Digit App PIN Lock -------------------- */

    public static String getPinCode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("app_lock_pin", "");
    }

    public static void setPinCode(Context ctx, String pin) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("app_lock_pin", pin == null ? "" : pin.trim()).commit();
    }

    public static boolean isAppLocked(Context ctx, String pkg) {
        if (pkg == null) return false;
        String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("locked_pkgs", "");
        if (raw.isEmpty() || getPinCode(ctx).isEmpty()) return false;
        String[] arr = raw.split(",");
        for (int i = 0; i < arr.length; i++) if (pkg.equals(arr[i])) return true;
        return false;
    }

    public static void setAppLocked(Context ctx, String pkg, boolean locked) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new LinkedHashSet<String>();
        String raw = sp.getString("locked_pkgs", "");
        if (!raw.isEmpty()) {
            String[] arr = raw.split(",");
            for (int i = 0; i < arr.length; i++) if (!arr[i].isEmpty()) set.add(arr[i]);
        }
        if (locked) set.add(pkg); else set.remove(pkg);
        StringBuilder sb = new StringBuilder();
        for (String s : set) { if (sb.length() > 0) sb.append(','); sb.append(s); }
        sp.edit().putString("locked_pkgs", sb.toString()).commit();
    }

    /* -------------------- JSON Backup & Restore -------------------- */

    public static File backupFile() {
        File prop = System.getProperty("arc7.test.home.backup") != null
                ? new File(System.getProperty("arc7.test.home.backup")) : null;
        if (prop != null) return prop;
        File dir = new File(Environment.getExternalStorageDirectory(), "Backups");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "Arc7-Home-Layout.json");
    }

    public static File exportLayoutBackupJson(Context ctx) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 53);
        root.put("pageCount", getPageCount(ctx));
        root.put("desktopColumns", getDesktopColumns(ctx));
        root.put("desktopRows", getDesktopRows(ctx));
        root.put("customDrawerSort", isCustomDrawerSort(ctx));
        JSONArray pages = new JSONArray();
        for (int p = 0; p < 5; p++) {
            JSONArray arr = new JSONArray();
            List<String> items = loadPage(ctx, p);
            for (int i = 0; i < items.size(); i++) arr.put(items.get(i));
            pages.put(arr);
        }
        root.put("pages", pages);
        JSONArray drawerArr = new JSONArray();
        List<String> drawerItems = loadDrawerOrder(ctx);
        for (int i = 0; i < drawerItems.size(); i++) drawerArr.put(drawerItems.get(i));
        root.put("drawerOrder", drawerArr);

        JSONArray barItems = new JSONArray();
        AppBarConfigStore.Config bar = AppBarConfigStore.load(ctx);
        for (int i = 0; i < bar.items.size(); i++) barItems.put(bar.items.get(i));
        root.put("appBarItems", barItems);

        File out = backupFile();
        FileOutputStream fos = new FileOutputStream(out);
        try { fos.write(root.toString(2).getBytes("UTF-8")); }
        finally { fos.close(); }
        return out;
    }

    public static void importLayoutBackupJson(Context ctx) throws Exception {
        File f = backupFile();
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) baos.write(buf, 0, n);
        } finally {
            fis.close();
        }
        JSONObject root = new JSONObject(baos.toString("UTF-8"));
        setPageCount(ctx, root.optInt("pageCount", 3));
        setDesktopColumns(ctx, root.optInt("desktopColumns", 5));
        setDesktopRows(ctx, root.optInt("desktopRows", 3));
        JSONArray pages = root.optJSONArray("pages");
        if (pages != null) {
            for (int p = 0; p < Math.min(5, pages.length()); p++) {
                JSONArray arr = pages.getJSONArray(p);
                List<String> list = new ArrayList<String>();
                for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
                savePage(ctx, p, list);
            }
        }
        JSONArray drawerArr = root.optJSONArray("drawerOrder");
        if (drawerArr != null && drawerArr.length() > 0) {
            List<String> dlist = new ArrayList<String>();
            for (int i = 0; i < drawerArr.length(); i++) dlist.add(drawerArr.getString(i));
            saveDrawerOrder(ctx, dlist);
            setCustomDrawerSort(ctx, root.optBoolean("customDrawerSort", true));
        }
        JSONArray barItems = root.optJSONArray("appBarItems");
        if (barItems != null) {
            AppBarConfigStore.Config bar = AppBarConfigStore.load(ctx);
            bar.items.clear();
            for (int i = 0; i < barItems.length(); i++) bar.items.add(barItems.getString(i));
            AppBarConfigStore.save(ctx, bar);
        }
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
        for (int p = 0; p < Math.max(5, pageCount); p++) {
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

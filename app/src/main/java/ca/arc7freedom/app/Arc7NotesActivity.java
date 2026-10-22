package ca.arc7freedom.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Full Skinned Standalone **Arc7 Notes, Checklists & Sketchpad** App (`v2.0.0`):
 *   - Two-pane tablet notebook layout tailored to Kobo Arc 7 `1024x600` display
 *   - Instant auto-saving Markdown Notes + Interactive Checklists (`[ ]` / `[x]`)
 *   - Full Finger/Stylus Color Sketchpad Canvas (`Save Sketch to Arc7 Photos`)
 *   - 1-Tap `.md` Export to `/sdcard/Arc7Freedom/notes/` + Live Search Filter
 */
public class Arc7NotesActivity extends Activity {

    private static final String PREFS = "arc7_notes_v200";
    private static final String KEY_NOTES = "notes_json";

    private static class NoteItem {
        long id;
        String title = "";
        String body = "";
        long updatedMs;
    }

    private final List<NoteItem> notes = new ArrayList<NoteItem>();
    private int activeIdx = 0;
    private String searchQuery = "";

    private LinearLayout sidebarList;
    private EditText titleInput;
    private EditText bodyInput;
    private LinearLayout checklistBox;
    private LinearLayout editorPane;
    private LinearLayout sketchPane;
    private SketchCanvasView sketchView;
    private boolean suppressSave = false;

    private int dp(int v) {
        return Arc7Skin.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        loadNotes();
        Arc7Skin.Palette pal = Arc7Skin.current(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button newNoteBtn = Arc7Skin.actionButton(this, "+ New Note", 0xFF059669);
        newNoteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createNewNote("New Note", "");
            }
        });
        addTopBtn(actions, newNoteBtn);

        Button newListBtn = Arc7Skin.actionButton(this, "+ Checklist", 0xFF0284C7);
        newListBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createNewNote("Shopping / To-Do List", "- [ ] First item\n- [ ] Second item\n- [ ] Third item");
            }
        });
        addTopBtn(actions, newListBtn);

        final Button sketchToggleBtn = Arc7Skin.actionButton(this, "Sketchpad", 0xFF7C3AED);
        sketchToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean showingSketch = sketchPane.getVisibility() == View.VISIBLE;
                sketchPane.setVisibility(showingSketch ? View.GONE : View.VISIBLE);
                editorPane.setVisibility(showingSketch ? View.VISIBLE : View.GONE);
                sketchToggleBtn.setText(showingSketch ? "Sketchpad" : "Back to Notes");
            }
        });
        addTopBtn(actions, sketchToggleBtn);

        Button exportMdBtn = Arc7Skin.actionButton(this, "Export .md", 0xFFD97706);
        exportMdBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportCurrentNoteMarkdown();
            }
        });
        actions.addView(exportMdBtn);

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_notes,
                "Arc7 Notes & Sketchpad",
                "Auto-Saved Notes • Interactive Checklists • Color Sketchpad • Markdown Export",
                actions));

        // Main Split Content
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(12));

        // Left Sidebar (Search + Note List)
        LinearLayout leftCol = Arc7Skin.card(this, pal);
        EditText searchEt = new EditText(this);
        searchEt.setHint("Search notes...");
        searchEt.setHintTextColor(pal.textMuted);
        searchEt.setTextColor(pal.textPrimary);
        searchEt.setSingleLine(true);
        searchEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim().toLowerCase(Locale.US);
                rebuildSidebar();
            }
        });
        leftCol.addView(searchEt);

        ScrollView sideScroll = new ScrollView(this);
        sidebarList = new LinearLayout(this);
        sidebarList.setOrientation(LinearLayout.VERTICAL);
        sidebarList.setPadding(0, dp(8), 0, 0);
        sideScroll.addView(sidebarList);
        leftCol.addView(sideScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                dp(250), ViewGroup.LayoutParams.MATCH_PARENT);
        leftLp.rightMargin = dp(12);
        body.addView(leftCol, leftLp);

        // Right Pane A: Note + Interactive Checklist Editor
        editorPane = Arc7Skin.card(this, pal);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        titleInput = new EditText(this);
        titleInput.setHint("Note Title");
        titleInput.setTextColor(pal.textPrimary);
        titleInput.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        titleInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        titleInput.setSingleLine(true);
        titleRow.addView(titleInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button delNoteBtn = Arc7Skin.actionButton(this, "Delete", 0xFFDC2626);
        delNoteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (notes.size() > 1 && activeIdx >= 0 && activeIdx < notes.size()) {
                    notes.remove(activeIdx);
                    activeIdx = 0;
                    persistNotes();
                    rebuildSidebar();
                    bindActiveNote();
                }
            }
        });
        titleRow.addView(delNoteBtn);
        editorPane.addView(titleRow);

        checklistBox = new LinearLayout(this);
        checklistBox.setOrientation(LinearLayout.VERTICAL);
        editorPane.addView(checklistBox);

        bodyInput = new EditText(this);
        bodyInput.setHint("Write anything here — prefix lines with '- [ ] ' for interactive checkboxes!");
        bodyInput.setHintTextColor(pal.textMuted);
        bodyInput.setTextColor(pal.textPrimary);
        bodyInput.setGravity(Gravity.TOP | Gravity.LEFT);
        bodyInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        editorPane.addView(bodyInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextWatcher saver = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (suppressSave || activeIdx < 0 || activeIdx >= notes.size()) return;
                NoteItem item = notes.get(activeIdx);
                item.title = titleInput.getText().toString();
                item.body = bodyInput.getText().toString();
                item.updatedMs = System.currentTimeMillis();
                persistNotes();
                rebuildSidebar();
                rebuildInteractiveCheckboxes();
            }
        };
        titleInput.addTextChangedListener(saver);
        bodyInput.addTextChangedListener(saver);
        body.addView(editorPane, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // Right Pane B: Full Finger/Stylus Color Sketchpad
        sketchPane = Arc7Skin.card(this, pal);
        sketchPane.setVisibility(View.GONE);
        LinearLayout brushRow = new LinearLayout(this);
        brushRow.setOrientation(LinearLayout.HORIZONTAL);
        brushRow.setGravity(Gravity.CENTER_VERTICAL);

        final int[] inks = new int[]{Color.WHITE, 0xFF38BDF8, 0xFF34D399, 0xFFFBBF24, 0xFFF43F5E, 0xFFA855F7};
        final String[] inkNames = new String[]{"White", "Cyan", "Mint", "Gold", "Coral", "Violet"};
        sketchView = new SketchCanvasView(this);

        for (int i = 0; i < inks.length; i++) {
            final int col = inks[i];
            Button swatch = Arc7Skin.actionButton(this, inkNames[i], col == Color.WHITE ? 0xFF475569 : col);
            addTopBtn(brushRow, swatch);
            swatch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sketchView.setInkColor(col);
                }
            });
        }
        Button clearCanvasBtn = Arc7Skin.actionButton(this, "Clear", 0xFFDC2626);
        clearCanvasBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sketchView.clearCanvas(); }
        });
        addTopBtn(brushRow, clearCanvasBtn);

        Button savePngBtn = Arc7Skin.actionButton(this, "Save to Photos", 0xFF059669);
        savePngBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveSketchToGallery(); }
        });
        brushRow.addView(savePngBtn);
        sketchPane.addView(brushRow);
        sketchPane.addView(sketchView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(sketchPane, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);

        rebuildSidebar();
        bindActiveNote();
    }

    private void addTopBtn(LinearLayout row, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        row.addView(b, lp);
    }

    private void loadNotes() {
        notes.clear();
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_NOTES, "");
        try {
            if (raw.length() > 2) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    NoteItem n = new NoteItem();
                    n.id = o.optLong("id", System.currentTimeMillis() + i);
                    n.title = o.optString("title", "Untitled");
                    n.body = o.optString("body", "");
                    n.updatedMs = o.optLong("updatedMs", System.currentTimeMillis());
                    notes.add(n);
                }
            }
        } catch (Throwable ignored) {}
        if (notes.isEmpty()) {
            NoteItem welcome = new NoteItem();
            welcome.id = System.currentTimeMillis();
            welcome.title = "Welcome to Arc7 Notes v2.0.0";
            welcome.body = "- [x] Custom Arc7 OS Skin + High-DPI App Icons\n"
                    + "- [x] Bundled VLC Media Player + Photos + Camera\n"
                    + "- [ ] Sync all 20 alarm chimes from archive.org\n"
                    + "- [ ] Try Chromebook Wired Internet via USB cable!";
            welcome.updatedMs = System.currentTimeMillis();
            notes.add(welcome);
            persistNotes();
        }
    }

    private void persistNotes() {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < notes.size(); i++) {
                NoteItem n = notes.get(i);
                JSONObject o = new JSONObject();
                o.put("id", n.id);
                o.put("title", n.title);
                o.put("body", n.body);
                o.put("updatedMs", n.updatedMs);
                arr.put(o);
            }
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_NOTES, arr.toString()).commit();
        } catch (Throwable ignored) {}
    }

    private void createNewNote(String title, String body) {
        NoteItem n = new NoteItem();
        n.id = System.currentTimeMillis();
        n.title = title;
        n.body = body;
        n.updatedMs = System.currentTimeMillis();
        notes.add(0, n);
        activeIdx = 0;
        persistNotes();
        rebuildSidebar();
        bindActiveNote();
    }

    private void rebuildSidebar() {
        sidebarList.removeAllViews();
        for (int i = 0; i < notes.size(); i++) {
            final int idx = i;
            NoteItem n = notes.get(i);
            if (searchQuery.length() > 0
                    && !n.title.toLowerCase(Locale.US).contains(searchQuery)
                    && !n.body.toLowerCase(Locale.US).contains(searchQuery)) {
                continue;
            }
            Button row = Arc7Skin.actionButton(
                    this,
                    n.title.trim().isEmpty() ? "(Untitled Note)" : n.title,
                    idx == activeIdx ? 0xFF0284C7 : 0xFF1E293B);
            row.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activeIdx = idx;
                    rebuildSidebar();
                    bindActiveNote();
                }
            });
            sidebarList.addView(row, lp);
        }
    }

    private void bindActiveNote() {
        if (activeIdx < 0 || activeIdx >= notes.size()) return;
        suppressSave = true;
        NoteItem n = notes.get(activeIdx);
        titleInput.setText(n.title);
        bodyInput.setText(n.body);
        suppressSave = false;
        rebuildInteractiveCheckboxes();
    }

    private void rebuildInteractiveCheckboxes() {
        checklistBox.removeAllViews();
        if (activeIdx < 0 || activeIdx >= notes.size()) return;
        final String[] lines = notes.get(activeIdx).body.split("\n");
        for (int i = 0; i < lines.length; i++) {
            final int lineIdx = i;
            String trim = lines[i].trim();
            final boolean unchecked = trim.startsWith("- [ ] ");
            final boolean checked = trim.startsWith("- [x] ") || trim.startsWith("- [X] ");
            if (!unchecked && !checked) continue;
            CheckBox cb = new CheckBox(this);
            cb.setChecked(checked);
            cb.setText(trim.substring(6));
            cb.setTextColor(Color.WHITE);
            cb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean nowChecked = ((CheckBox) v).isChecked();
                    lines[lineIdx] = (nowChecked ? "- [x] " : "- [ ] ") + lines[lineIdx].trim().substring(6);
                    StringBuilder joined = new StringBuilder();
                    for (int j = 0; j < lines.length; j++) {
                        if (j > 0) joined.append('\n');
                        joined.append(lines[j]);
                    }
                    notes.get(activeIdx).body = joined.toString();
                    persistNotes();
                    bindActiveNote();
                }
            });
            checklistBox.addView(cb);
        }
    }

    private void exportCurrentNoteMarkdown() {
        if (activeIdx < 0 || activeIdx >= notes.size()) return;
        try {
            NoteItem n = notes.get(activeIdx);
            File dir = new File(Environment.getExternalStorageDirectory(), "Arc7Freedom/notes");
            if (!dir.exists()) dir.mkdirs();
            String safe = n.title.replaceAll("[^a-zA-Z0-9._-]+", "_");
            File out = new File(dir, (safe.isEmpty() ? "Note" : safe) + ".md");
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(("# " + n.title + "\n\n" + n.body + "\n").getBytes("UTF-8"));
            fos.close();
            Toast.makeText(this, "Exported Markdown -> " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Export failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveSketchToGallery() {
        Bitmap bmp = sketchView.snapshotBitmap();
        if (bmp == null) return;
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "Arc7Sketches");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File out = new File(dir, "SKETCH_" + ts + ".png");
            FileOutputStream fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(out)));
            Toast.makeText(this, "Saved sketch to Arc7 Photos (" + out.getName() + ")!", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Sketch save failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static class SketchCanvasView extends View {
        private static class Stroke {
            final Path path = new Path();
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }

        private final List<Stroke> strokes = new ArrayList<Stroke>();
        private Stroke activeStroke = null;
        private int currentColor = 0xFF38BDF8;

        SketchCanvasView(Context ctx) {
            super(ctx);
            setBackgroundColor(0xFF0A1019);
        }

        void setInkColor(int color) {
            currentColor = color;
        }

        void clearCanvas() {
            strokes.clear();
            invalidate();
        }

        Bitmap snapshotBitmap() {
            if (getWidth() <= 0 || getHeight() <= 0) return null;
            Bitmap b = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            draw(c);
            return b;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (int i = 0; i < strokes.size(); i++) {
                Stroke s = strokes.get(i);
                canvas.drawPath(s.path, s.paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            int a = event.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                Stroke s = new Stroke();
                s.paint.setStyle(Paint.Style.STROKE);
                s.paint.setStrokeCap(Paint.Cap.ROUND);
                s.paint.setStrokeJoin(Paint.Join.ROUND);
                s.paint.setStrokeWidth(7f);
                s.paint.setColor(currentColor);
                s.path.moveTo(x, y);
                strokes.add(s);
                activeStroke = s;
                invalidate();
            } else if (a == MotionEvent.ACTION_MOVE && activeStroke != null) {
                activeStroke.path.lineTo(x, y);
                invalidate();
            }
            return true;
        }
    }
}

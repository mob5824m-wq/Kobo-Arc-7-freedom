package ca.arc7freedom.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Arc7 Offline Arcade — rebuilt for Kobo Arc 7 (Android 4.2, v2.8.0 content refresh).
 *
 * Seven zero-ad, fully offline games in one well-organized app:
 *   1. Chess vs AI       — 8×8 board, pseudo-legal move validation, greedy 2-ply minimax
 *   2. 2048              — 4×4 tile merger with persistent best score
 *   3. Sudoku            — Latin-shifted generator, pencil/solve check, win counter
 *   4. Minesweeper       — 9×9, 12 mines, safe first tap, chord win detection
 *   5. Klondike Solitaire — 7 tableau piles, stock/waste, four foundations
 *   6. Snake             — classic grow-and-survive, speed ramps up, persistent best
 *   7. Breakout          — paddle + bricks, escalating levels, persistent best score
 *
 * The app opens on a game hub (cards with names, taglines and all-time bests) instead of
 * dumping you straight into chess. A consistent chrome (back, title, sound toggle, New Game),
 * short ToneGenerator sound effects, haptic feedback and per-game persistent bests make it
 * feel like a real arcade instead of a spreadsheet of boards.
 */
public class Arc7ArcadeActivity extends Arc7Activity {

    private static final String PREFS = "arc7_arcade";

    private static final int CHESS = 0;
    private static final int G2048 = 1;
    private static final int SUDOKU = 2;
    private static final int MINES = 3;
    private static final int SOLITAIRE = 4;
    private static final int SNAKE = 5;
    private static final int BREAKOUT = 6;

    private static final int SFX_CLICK = 0;
    private static final int SFX_MOVE = 1;
    private static final int SFX_GOOD = 2;
    private static final int SFX_BAD = 3;
    private static final int SFX_WIN = 4;

    private Arc7Skin.Palette pal;
    private int activeGame = -1; // -1 = hub
    private LinearLayout root;
    private LinearLayout stageBox;
    private TextView hudTv;
    private Button soundBtn;
    private final Random rng = new Random();
    private ToneGenerator toneGen;

    private static final class GameInfo {
        final int id;
        final String icon, name, tagline, bestKey, bestLabel;
        final int accent;
        GameInfo(int id, String icon, String name, String tagline, int accent, String bestKey, String bestLabel) {
            this.id = id; this.icon = icon; this.name = name; this.tagline = tagline;
            this.accent = accent; this.bestKey = bestKey; this.bestLabel = bestLabel;
        }
    }

    private static final GameInfo[] GAMES = new GameInfo[]{
            new GameInfo(CHESS, "♟", "Chess vs AI", "Classic 8×8 — outsmart the 2-ply engine", 0xFF8B5CF6, "best_chess_wins", "Wins"),
            new GameInfo(G2048, "🔢", "2048", "Swipe merges — reach the 2048 tile", 0xFFF97316, "best_2048", "Best"),
            new GameInfo(SUDOKU, "🧩", "Sudoku", "Latin-square puzzles, tap-fill 1–9", 0xFF06B6D4, "best_sudoku_solves", "Solved"),
            new GameInfo(MINES, "💣", "Minesweeper", "9×9 safe-first-tap minefield", 0xFF22C55E, "best_mines_wins", "Cleared"),
            new GameInfo(SOLITAIRE, "🃏", "Klondike Solitaire", "Stock, waste and four foundations", 0xFFEF4444, "best_solitaire_wins", "Won"),
            new GameInfo(SNAKE, "🐍", "Snake", "Grow without biting yourself", 0xFF84CC16, "best_snake_score", "Best"),
            new GameInfo(BREAKOUT, "🧱", "Breakout", "Clear every brick, level up", 0xFF3B82F6, "best_breakout_score", "Best"),
    };

    // Keep the game controls alive between turns. Updating their content in place avoids
    // tearing down/recreating the board (and losing focus/scroll position) after every move.
    private final Button[][] chessCells = new Button[8][8];
    private final TextView[][] tiles2048 = new TextView[4][4];
    private final Button[][] sudokuCells = new Button[9][9];
    private final Button[][] mineCells = new Button[9][9];
    private Button minesFlagButton;
    private Button solitaireStockButton, solitaireWasteButton;
    private LinearLayout solitaireColumns;

    // --- 1. Chess State ---
    private final char[][] chess = new char[8][8];
    private int chessSelR = -1, chessSelC = -1;
    private boolean chessOver = false;

    // --- 2. 2048 State ---
    private final int[][] g2048 = new int[4][4];
    private int score2048 = 0;

    // --- 3. Sudoku State ---
    private final int[][] sudokuSolution = new int[9][9];
    private final int[][] sudokuGrid = new int[9][9];
    private final boolean[][] sudokuFixed = new boolean[9][9];
    private int sudSelR = 0, sudSelC = 0;
    private boolean sudokuWon = false;

    // --- 4. Minesweeper State (9×9, 12 mines) ---
    private final boolean[][] mines = new boolean[9][9];
    private final boolean[][] revealed = new boolean[9][9];
    private final boolean[][] flagged = new boolean[9][9];
    private boolean flagMode = false;
    private boolean minesGenerated = false;
    private boolean minesGameOver = false;
    private boolean minesWon = false;

    // --- 5. Klondike Solitaire State (0..51: suit = card/13, rank = card%13 [0=A..12=K]) ---
    private final List<Integer> stock = new ArrayList<Integer>();
    private final List<Integer> waste = new ArrayList<Integer>();
    private final int[] foundationTop = new int[]{-1, -1, -1, -1};
    @SuppressWarnings("unchecked")
    private final List<Integer>[] tableau = new ArrayList[7];
    private final int[] hiddenCount = new int[7];
    private int solSelPile = -2; // -1 = waste, 0..6 = tableau
    private boolean solitaireWon = false;

    // --- 6/7. Live views for the loop-driven games ---
    private SnakeView snakeView;
    private BreakoutView breakoutView;

    private int dp(int v) { return Arc7Skin.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        pal = Arc7Skin.current(this);
        for (int i = 0; i < 7; i++) tableau[i] = new ArrayList<Integer>();
        initChess();
        init2048();
        initSudoku();
        initMines();
        initSolitaire();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        showHub();
    }

    /* ==================== Chrome: hub + game shells ==================== */

    private int best(String key) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getInt(key, 0);
    }

    private void bumpBest(String key, int candidate) {
        if (candidate > best(key)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(key, candidate).commit();
        }
    }

    private boolean soundOn() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("sound_on", true);
    }

    private void toggleSound() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("sound_on", !soundOn()).commit();
        if (soundBtn != null) soundBtn.setText(soundOn() ? "🔊" : "🔇");
        sfx(SFX_CLICK);
    }

    private void sfx(int kind) {
        if (!soundOn()) return;
        try {
            if (toneGen == null) toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            int tone = ToneGenerator.TONE_PROP_BEEP;
            switch (kind) {
                case SFX_MOVE: tone = ToneGenerator.TONE_DTMF_S; break;
                case SFX_GOOD: tone = ToneGenerator.TONE_PROP_ACK; break;
                case SFX_BAD: tone = ToneGenerator.TONE_PROP_NACK; break;
                case SFX_WIN: tone = ToneGenerator.TONE_CDMA_HIGH_L; break;
                default: tone = ToneGenerator.TONE_PROP_BEEP; break;
            }
            toneGen.startTone(tone, kind == SFX_WIN ? 240 : 60);
        } catch (Throwable ignored) {
            // Sound is a garnish, never a requirement.
        }
    }

    private void buzz(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms);
        } catch (Throwable ignored) {
            // VIBRATE is optional; silently skip on devices/builds without it.
        }
    }

    private void clearRoot() {
        stopGameLoops();
        snakeView = null;
        breakoutView = null;
        stageBox = null;
        hudTv = null;
        soundBtn = null;
        chessCellsReset();
        root.removeAllViews();
        root.setBackgroundDrawable(Arc7Skin.pageDrawable(pal));
    }

    private void stopGameLoops() {
        if (snakeView != null) snakeView.stopLoop();
        if (breakoutView != null) breakoutView.stopLoop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (snakeView != null) snakeView.resumeLoop();
        if (breakoutView != null) breakoutView.resumeLoop();
    }

    private void chessCellsReset() {
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) chessCells[r][c] = null;
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) tiles2048[r][c] = null;
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) sudokuCells[r][c] = null;
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) mineCells[r][c] = null;
        minesFlagButton = null;
        solitaireStockButton = solitaireWasteButton = null;
        solitaireColumns = null;
    }

    private void showHub() {
        activeGame = -1;
        clearRoot();

        Button sound = Arc7Skin.actionButton(this, soundOn() ? "🔊" : "🔇", 0xFF334155);
        sound.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSound(); }
        });
        soundBtn = sound;

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_arcade,
                "Games  •  Offline Arcade",
                "Seven zero-ad classics — no internet, no ads, no accounts",
                sound));

        ScrollView sv = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(10), dp(14), dp(24));

        TextView pick = new TextView(this);
        pick.setText("Pick a game — all-time bests are saved on this tablet");
        pick.setTextColor(pal.accent);
        pick.setTypeface(Typeface.DEFAULT_BOLD);
        pick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        pick.setPadding(0, dp(2), 0, dp(10));
        content.addView(pick);

        for (int row = 0; row < GAMES.length; row += 2) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 2 && row + col < GAMES.length; col++) {
                GameInfo game = GAMES[row + col];
                View card = buildGameCard(game);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(col == 0 ? 0 : dp(6), 0, col == 0 ? dp(6) : 0, dp(12));
                line.addView(card, lp);
            }
            content.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView footer = new TextView(this);
        footer.setText("Offline by design. Vibration feedback where the device supports it.\n"
                + "Every game runs without a network, without ads and without sign-in.");
        footer.setTextColor(0xFF94A3B8);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, dp(4), 0, 0);
        content.addView(footer);

        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private View buildGameCard(final GameInfo game) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(12), dp(16), dp(12), dp(14));
        card.setMinimumHeight(dp(150));
        card.setBackgroundDrawable(Arc7Skin.elevatedDrawable(this, pal, 18));

        TextView icon = new TextView(this);
        icon.setText(game.icon);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f);
        icon.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(icon);

        TextView name = new TextView(this);
        name.setText(game.name);
        name.setTextColor(Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(name);

        TextView tag = new TextView(this);
        tag.setText(game.tagline);
        tag.setTextColor(0xFFCBD5E1);
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tag.setGravity(Gravity.CENTER_HORIZONTAL);
        tag.setPadding(0, dp(3), 0, dp(8));
        card.addView(tag);

        TextView bestView = new TextView(this);
        bestView.setText(game.bestLabel + ": " + best(game.bestKey));
        bestView.setTextColor(game.accent);
        bestView.setTypeface(Typeface.DEFAULT_BOLD);
        bestView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        bestView.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(bestView);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sfx(SFX_CLICK);
                buzz(12);
                showGame(game.id);
            }
        });
        return card;
    }

    private void showGame(int gameId) {
        activeGame = gameId;
        clearRoot();
        GameInfo info = null;
        for (int i = 0; i < GAMES.length; i++) if (GAMES[i].id == gameId) info = GAMES[i];

        Button back = Arc7Skin.actionButton(this, "‹", 0xFF1F2937);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sfx(SFX_CLICK); showHub(); }
        });

        Button newBtn = Arc7Skin.actionButton(this, "↻", info != null ? info.accent : 0xFF059669);
        newBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        newBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sfx(SFX_CLICK); buzz(15); showGame(activeGame); }
        });

        Button sound = Arc7Skin.actionButton(this, soundOn() ? "🔊" : "🔇", 0xFF334155);
        sound.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSound(); }
        });
        soundBtn = sound;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(pal.surface);
        header.setPadding(dp(10), dp(8), dp(10), dp(8));
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        TextView title = new TextView(this);
        title.setText(info != null ? info.icon + "  " + info.name : "Games");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setGravity(Gravity.CENTER_VERTICAL);
        titleCol.addView(title);
        TextView tagline = new TextView(this);
        tagline.setText(info != null ? info.tagline : "");
        tagline.setTextColor(0xFFCBD5E1);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        titleCol.addView(tagline);
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tcp.leftMargin = dp(12);
        header.addView(titleCol, tcp);
        header.addView(sound, new LinearLayout.LayoutParams(dp(52), dp(48)));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(52), dp(48));
        nlp.leftMargin = dp(6);
        header.addView(newBtn, nlp);
        root.addView(header);

        hudTv = new TextView(this);
        hudTv.setTextColor(pal.accent);
        hudTv.setTypeface(Typeface.DEFAULT_BOLD);
        hudTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        hudTv.setGravity(Gravity.CENTER_HORIZONTAL);
        hudTv.setPadding(dp(12), dp(8), dp(12), dp(6));
        root.addView(hudTv);

        ScrollView scroller = new ScrollView(this);
        stageBox = new LinearLayout(this);
        stageBox.setOrientation(LinearLayout.VERTICAL);
        stageBox.setGravity(Gravity.CENTER_HORIZONTAL);
        stageBox.setPadding(dp(12), dp(4), dp(12), dp(20));
        scroller.addView(stageBox);
        root.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (gameId == CHESS) renderChess();
        else if (gameId == G2048) render2048();
        else if (gameId == SUDOKU) renderSudoku();
        else if (gameId == MINES) renderMines();
        else if (gameId == SOLITAIRE) renderSolitaire();
        else if (gameId == SNAKE) renderSnake();
        else if (gameId == BREAKOUT) renderBreakout();
        refreshActiveGame();
    }

    private void refreshActiveGame() {
        if (activeGame == CHESS) refreshChess();
        else if (activeGame == G2048) refresh2048();
        else if (activeGame == SUDOKU) refreshSudoku();
        else if (activeGame == MINES) refreshMines();
        else if (activeGame == SOLITAIRE) refreshSolitaire();
        else if (activeGame == SNAKE && hudTv != null) hudTv.setText(snakeHudText());
        else if (activeGame == BREAKOUT && hudTv != null) hudTv.setText(breakoutHudText());
    }

    @Override
    public void onBackPressed() {
        if (activeGame != -1) {
            sfx(SFX_CLICK);
            showHub();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        stopGameLoops();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopGameLoops();
        if (toneGen != null) {
            try { toneGen.release(); } catch (Throwable ignored) {}
            toneGen = null;
        }
        super.onDestroy();
    }

    /* ==================== 1. CHESS VS MINIMAX AI ==================== */

    private void initChess() {
        String back = "rnbqkbnr";
        for (int c = 0; c < 8; c++) {
            chess[0][c] = back.charAt(c);
            chess[1][c] = 'p';
            for (int r = 2; r < 6; r++) chess[r][c] = '.';
            chess[6][c] = 'P';
            chess[7][c] = Character.toUpperCase(back.charAt(c));
        }
        chessSelR = -1;
        chessSelC = -1;
        chessOver = false;
    }

    private static String chessGlyph(char p) {
        switch (p) {
            case 'K': return "♔"; case 'Q': return "♕"; case 'R': return "♖";
            case 'B': return "♗"; case 'N': return "♘"; case 'P': return "♙";
            case 'k': return "♚"; case 'q': return "♛"; case 'r': return "♜";
            case 'b': return "♝"; case 'n': return "♞"; case 'p': return "♟";
            default: return " ";
        }
    }

    private static int pieceVal(char p) {
        switch (Character.toLowerCase(p)) {
            case 'p': return 10; case 'n': return 30; case 'b': return 32;
            case 'r': return 50; case 'q': return 90; case 'k': return 900;
            default: return 0;
        }
    }

    public static boolean isPseudoLegalChessMove(char[][] b, int r0, int c0, int r1, int c1, boolean whiteTurn) {
        if (r0 == r1 && c0 == c1) return false;
        char src = b[r0][c0];
        if (src == '.' || Character.isUpperCase(src) != whiteTurn) return false;
        char dst = b[r1][c1];
        if (dst != '.' && Character.isUpperCase(dst) == whiteTurn) return false;
        int dr = r1 - r0, dc = c1 - c0;
        int adr = Math.abs(dr), adc = Math.abs(dc);
        switch (Character.toLowerCase(src)) {
            case 'p': {
                int dir = whiteTurn ? -1 : 1;
                int startR = whiteTurn ? 6 : 1;
                if (dc == 0 && dst == '.') {
                    if (dr == dir) return true;
                    return r0 == startR && dr == 2 * dir && b[r0 + dir][c0] == '.';
                }
                return adr == 1 && adc == 1 && dr == dir && dst != '.';
            }
            case 'n': return (adr == 2 && adc == 1) || (adr == 1 && adc == 2);
            case 'k': return adr <= 1 && adc <= 1;
            case 'b': return adr == adc && pathClear(b, r0, c0, r1, c1);
            case 'r': return (dr == 0 || dc == 0) && pathClear(b, r0, c0, r1, c1);
            case 'q': return (adr == adc || dr == 0 || dc == 0) && pathClear(b, r0, c0, r1, c1);
            default: return false;
        }
    }

    private static boolean pathClear(char[][] b, int r0, int c0, int r1, int c1) {
        int sr = r1 > r0 ? 1 : (r1 < r0 ? -1 : 0);
        int sc = c1 > c0 ? 1 : (c1 < c0 ? -1 : 0);
        int r = r0 + sr, c = c0 + sc;
        while (r != r1 || c != c1) {
            if (b[r][c] != '.') return false;
            r += sr; c += sc;
        }
        return true;
    }

    private void renderChess() {
        for (int r = 0; r < 8; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            for (int c = 0; c < 8; c++) {
                final int fr = r, fc = c;
                Button sq = new Button(this);
                char p = chess[r][c];
                sq.setText(chessGlyph(p));
                sq.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
                sq.setPadding(0, 0, 0, 0);
                sq.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { onChessTap(fr, fc); }
                });
                chessCells[r][c] = sq;
                row.addView(sq, new LinearLayout.LayoutParams(dp(46), dp(46)));
            }
            stageBox.addView(row);
        }
    }

    private void refreshChess() {
        if (hudTv == null) return;
        hudTv.setText(chessOver ? "Game Over — tap ↻ to play again!  (Wins: " + best("best_chess_wins") + ")"
                : "You play White ♔♕♖♗♘♙ — wins: " + best("best_chess_wins"));
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) {
            Button sq = chessCells[r][c];
            if (sq == null) continue;
            char p = chess[r][c];
            sq.setText(chessGlyph(p));
            sq.setTextColor(Character.isUpperCase(p) ? Color.WHITE : 0xFF0F172A);
            sq.setBackgroundColor((r == chessSelR && c == chessSelC) ? 0xFF0284C7
                    : (((r + c) % 2 == 1) ? 0xFF334155 : 0xFF64748B));
        }
    }

    private void onChessTap(int r, int c) {
        if (chessOver) return;
        if (chessSelR < 0) {
            if (chess[r][c] != '.' && Character.isUpperCase(chess[r][c])) {
                chessSelR = r; chessSelC = c;
                sfx(SFX_CLICK);
                refreshChess();
            }
            return;
        }
        if (isPseudoLegalChessMove(chess, chessSelR, chessSelC, r, c, true)) {
            char cap = chess[r][c];
            chess[r][c] = (chess[chessSelR][chessSelC] == 'P' && r == 0) ? 'Q' : chess[chessSelR][chessSelC];
            chess[chessSelR][chessSelC] = '.';
            chessSelR = -1; chessSelC = -1;
            if (cap == 'k') {
                chessOver = true;
                bumpBest("best_chess_wins", best("best_chess_wins") + 1);
                sfx(SFX_WIN);
                buzz(180);
                Toast.makeText(this, "Checkmate — White wins!", Toast.LENGTH_LONG).show();
            } else {
                if (cap != '.') sfx(SFX_GOOD); else sfx(SFX_MOVE);
                aiChessReply();
            }
        } else {
            chessSelR = -1; chessSelC = -1;
            sfx(SFX_BAD);
        }
        refreshChess();
    }

    private void aiChessReply() {
        int bestScore = -99999;
        int[] bestMove = null;
        for (int r0 = 0; r0 < 8; r0++) {
            for (int c0 = 0; c0 < 8; c0++) {
                if (chess[r0][c0] == '.' || Character.isUpperCase(chess[r0][c0])) continue;
                for (int r1 = 0; r1 < 8; r1++) {
                    for (int c1 = 0; c1 < 8; c1++) {
                        if (!isPseudoLegalChessMove(chess, r0, c0, r1, c1, false)) continue;
                        char savedDst = chess[r1][c1];
                        char savedSrc = chess[r0][c0];
                        chess[r1][c1] = (savedSrc == 'p' && r1 == 7) ? 'q' : savedSrc;
                        chess[r0][c0] = '.';
                        // 2-ply lookahead evaluation (Black material minus best immediate White capture)
                        int worstReply = 0;
                        for (int wr0 = 0; wr0 < 8; wr0++) {
                            for (int wc0 = 0; wc0 < 8; wc0++) {
                                if (chess[wr0][wc0] == '.' || !Character.isUpperCase(chess[wr0][wc0])) continue;
                                for (int wr1 = 0; wr1 < 8; wr1++) {
                                    for (int wc1 = 0; wc1 < 8; wc1++) {
                                        if (chess[wr1][wc1] != '.' && isPseudoLegalChessMove(chess, wr0, wc0, wr1, wc1, true)) {
                                            worstReply = Math.max(worstReply, pieceVal(chess[wr1][wc1]));
                                        }
                                    }
                                }
                            }
                        }
                        chess[r0][c0] = savedSrc;
                        chess[r1][c1] = savedDst;
                        int score = pieceVal(savedDst) * 2 - worstReply + rng.nextInt(3);
                        if (score > bestScore) {
                            bestScore = score;
                            bestMove = new int[]{r0, c0, r1, c1};
                        }
                    }
                }
            }
        }
        if (bestMove != null) {
            char cap = chess[bestMove[2]][bestMove[3]];
            chess[bestMove[2]][bestMove[3]] =
                    (chess[bestMove[0]][bestMove[1]] == 'p' && bestMove[2] == 7) ? 'q' : chess[bestMove[0]][bestMove[1]];
            chess[bestMove[0]][bestMove[1]] = '.';
            if (cap == 'K') {
                chessOver = true;
                sfx(SFX_BAD);
                buzz(160);
                Toast.makeText(this, "The engine takes your king — Black wins.", Toast.LENGTH_LONG).show();
            }
        }
    }

    /* ==================== 2. 2048 ==================== */

    private void init2048() {
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) g2048[r][c] = 0;
        score2048 = 0;
        spawn2048Tile();
        spawn2048Tile();
    }

    private void spawn2048Tile() {
        List<int[]> empty = new ArrayList<int[]>();
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 4; c++)
                if (g2048[r][c] == 0) empty.add(new int[]{r, c});
        if (!empty.isEmpty()) {
            int[] p = empty.get(rng.nextInt(empty.size()));
            g2048[p[0]][p[1]] = rng.nextInt(10) == 0 ? 4 : 2;
        }
    }

    private boolean has2048Move() {
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            if (g2048[r][c] == 0) return true;
            if (c + 1 < 4 && g2048[r][c] == g2048[r][c + 1]) return true;
            if (r + 1 < 4 && g2048[r][c] == g2048[r + 1][c]) return true;
        }
        return false;
    }

    public int slide2048(int dir) { // 0=Left, 1=Right, 2=Up, 3=Down
        int gained = 0;
        boolean moved = false;
        for (int i = 0; i < 4; i++) {
            int[] line = new int[4];
            for (int j = 0; j < 4; j++) {
                line[j] = dir == 0 ? g2048[i][j] : (dir == 1 ? g2048[i][3 - j]
                        : (dir == 2 ? g2048[j][i] : g2048[3 - j][i]));
            }
            List<Integer> comp = new ArrayList<Integer>();
            for (int v : line) if (v != 0) comp.add(v);
            List<Integer> merged = new ArrayList<Integer>();
            for (int k = 0; k < comp.size(); k++) {
                if (k + 1 < comp.size() && comp.get(k).equals(comp.get(k + 1))) {
                    int m = comp.get(k) * 2;
                    merged.add(m);
                    gained += m;
                    k++;
                } else {
                    merged.add(comp.get(k));
                }
            }
            while (merged.size() < 4) merged.add(0);
            for (int j = 0; j < 4; j++) {
                int nv = merged.get(j);
                if (nv != line[j]) moved = true;
                if (dir == 0) g2048[i][j] = nv;
                else if (dir == 1) g2048[i][3 - j] = nv;
                else if (dir == 2) g2048[j][i] = nv;
                else g2048[3 - j][i] = nv;
            }
        }
        if (moved) {
            score2048 += gained;
            bumpBest("best_2048", score2048);
            if (gained > 0) sfx(SFX_GOOD); else sfx(SFX_MOVE);
            spawn2048Tile();
            if (!has2048Move()) {
                sfx(SFX_BAD);
                buzz(120);
                Toast.makeText(this, "No moves left — board full! Tap ↻ for a new game.", Toast.LENGTH_LONG).show();
            }
        } else {
            sfx(SFX_BAD);
        }
        return gained;
    }

    private void refresh2048() {
        if (hudTv == null) return;
        hudTv.setText("2048  •  Score " + score2048 + "   •   Best " + best("best_2048"));
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            TextView tile = tiles2048[r][c];
            if (tile == null) continue;
            int val = g2048[r][c];
            tile.setText(val == 0 ? "" : String.valueOf(val));
            tile.setTextSize(TypedValue.COMPLEX_UNIT_SP, val >= 1024 ? 17 : (val >= 128 ? 19 : 22));
            tile.setBackgroundColor(val == 0 ? 0xFF1E293B
                    : (val >= 512 ? 0xFFD97706 : (val >= 64 ? 0xFF0284C7 : 0xFF334155)));
        }
    }

    private void render2048() {
        hudTv.setText("2048  •  Score " + score2048 + "   •   Best " + best("best_2048"));
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            for (int c = 0; c < 4; c++) {
                int val = g2048[r][c];
                TextView tv = new TextView(this);
                tv.setText(val == 0 ? "" : String.valueOf(val));
                tv.setGravity(Gravity.CENTER);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                tv.setTextColor(Color.WHITE);
                tv.setBackgroundColor(val == 0 ? 0xFF1E293B : (val >= 512 ? 0xFFD97706 : (val >= 64 ? 0xFF0284C7 : 0xFF334155)));
                tiles2048[r][c] = tv;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(74), dp(74));
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(tv, lp);
            }
            stageBox.addView(row);
        }
        LinearLayout dpad = new LinearLayout(this);
        dpad.setOrientation(LinearLayout.HORIZONTAL);
        dpad.setGravity(Gravity.CENTER_HORIZONTAL);
        dpad.setPadding(0, dp(12), 0, 0);
        String[] dirs = new String[]{"⬅ Left", "⬆ Up", "⬇ Down", "➡ Right"};
        final int[] codes = new int[]{0, 2, 3, 1};
        for (int i = 0; i < 4; i++) {
            final int dc = codes[i];
            Button b = Arc7Skin.actionButton(this, dirs[i], 0xFF0284C7);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(5), 0, dp(5), 0);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { slide2048(dc); refresh2048(); }
            });
            dpad.addView(b, lp);
        }
        stageBox.addView(dpad);
    }

    /* ==================== 3. SUDOKU ==================== */

    private void initSudoku() {
        List<Integer> perm = new ArrayList<Integer>();
        for (int d = 1; d <= 9; d++) perm.add(d);
        Collections.shuffle(perm, rng);
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                int idx = (r * 3 + r / 3 + c) % 9;
                sudokuSolution[r][c] = perm.get(idx);
                boolean clue = rng.nextInt(100) < 46;
                sudokuFixed[r][c] = clue;
                sudokuGrid[r][c] = clue ? sudokuSolution[r][c] : 0;
            }
        }
        sudokuWon = false;
    }

    private void refreshSudoku() {
        if (hudTv == null) return;
        hudTv.setText("Sudoku  •  Solved: " + best("best_sudoku_solves") + "  •  tap a cell, then 1–9");
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) {
            Button cell = sudokuCells[r][c];
            if (cell == null) continue;
            int value = sudokuGrid[r][c];
            cell.setText(value == 0 ? "" : String.valueOf(value));
            int boxShade = (((r / 3) + (c / 3)) % 2 == 0) ? 0xFF1E293B : 0xFF0F172A;
            cell.setBackgroundColor((r == sudSelR && c == sudSelC) ? 0xFF0284C7 : boxShade);
            cell.setTextColor(sudokuFixed[r][c] ? 0xFF94A3B8 : 0xFF38BDF8);
        }
    }

    private void renderSudoku() {
        hudTv.setText("Sudoku  •  Solved: " + best("best_sudoku_solves") + "  •  tap a cell, then 1–9");
        for (int r = 0; r < 9; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            for (int c = 0; c < 9; c++) {
                final int fr = r, fc = c;
                int v = sudokuGrid[r][c];
                Button cell = new Button(this);
                cell.setText(v == 0 ? "" : String.valueOf(v));
                cell.setPadding(0, 0, 0, 0);
                cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                boolean sel = (r == sudSelR && c == sudSelC);
                int boxShade = (((r / 3) + (c / 3)) % 2 == 0) ? 0xFF1E293B : 0xFF0F172A;
                cell.setBackgroundColor(sel ? 0xFF0284C7 : boxShade);
                cell.setTextColor(sudokuFixed[r][c] ? 0xFF94A3B8 : 0xFF38BDF8);
                sudokuCells[r][c] = cell;
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) { sudSelR = fr; sudSelC = fc; sfx(SFX_CLICK); refreshSudoku(); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                row.addView(cell, lp);
            }
            stageBox.addView(row);
        }
        LinearLayout numBar = new LinearLayout(this);
        numBar.setOrientation(LinearLayout.HORIZONTAL);
        numBar.setPadding(0, dp(10), 0, 0);
        for (int n = 1; n <= 9; n++) {
            final int num = n;
            Button nb = Arc7Skin.actionButton(this, String.valueOf(n), 0xFF334155);
            nb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!sudokuFixed[sudSelR][sudSelC]) {
                        sudokuGrid[sudSelR][sudSelC] = num;
                        sfx(SFX_MOVE);
                        checkSudokuWin();
                        refreshSudoku();
                    } else {
                        sfx(SFX_BAD);
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(3);
            numBar.addView(nb, lp);
        }
        stageBox.addView(numBar);
        LinearLayout clearRow = new LinearLayout(this);
        clearRow.setPadding(0, dp(8), 0, 0);
        Button clear = Arc7Skin.actionButton(this, "⌫ Clear cell", 0xFF1F2937);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!sudokuFixed[sudSelR][sudSelC]) { sudokuGrid[sudSelR][sudSelC] = 0; sfx(SFX_CLICK); refreshSudoku(); }
            }
        });
        clearRow.addView(clear);
        stageBox.addView(clearRow);
    }

    private void checkSudokuWin() {
        if (sudokuWon) return;
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (sudokuGrid[r][c] != sudokuSolution[r][c]) return;
        sudokuWon = true;
        bumpBest("best_sudoku_solves", best("best_sudoku_solves") + 1);
        sfx(SFX_WIN);
        buzz(200);
        Toast.makeText(this, "🎉 Sudoku Solved Perfectly!", Toast.LENGTH_LONG).show();
    }

    /* ==================== 4. MINESWEEPER ==================== */

    private void initMines() {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) {
                mines[r][c] = false; revealed[r][c] = false; flagged[r][c] = false;
            }
        minesGenerated = false;
        minesGameOver = false;
        minesWon = false;
    }

    private void placeMinesExcept(int safeR, int safeC) {
        int placed = 0;
        while (placed < 12) {
            int r = rng.nextInt(9), c = rng.nextInt(9);
            if ((Math.abs(r - safeR) <= 1 && Math.abs(c - safeC) <= 1) || mines[r][c]) continue;
            mines[r][c] = true;
            placed++;
        }
        minesGenerated = true;
    }

    private int adjMines(int r, int c) {
        int cnt = 0;
        for (int dr = -1; dr <= 1; dr++)
            for (int dc = -1; dc <= 1; dc++) {
                int nr = r + dr, nc = c + dc;
                if (nr >= 0 && nr < 9 && nc >= 0 && nc < 9 && mines[nr][nc]) cnt++;
            }
        return cnt;
    }

    private void floodReveal(int r, int c) {
        if (r < 0 || r >= 9 || c < 0 || c >= 9 || revealed[r][c] || flagged[r][c]) return;
        revealed[r][c] = true;
        if (adjMines(r, c) == 0) {
            for (int dr = -1; dr <= 1; dr++)
                for (int dc = -1; dc <= 1; dc++)
                    floodReveal(r + dr, c + dc);
        }
    }

    private void checkMinesWin() {
        if (minesWon || minesGameOver) return;
        int revealedSafe = 0;
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++)
            if (revealed[r][c] && !mines[r][c]) revealedSafe++;
        if (revealedSafe == 9 * 9 - 12) {
            minesWon = true;
            bumpBest("best_mines_wins", best("best_mines_wins") + 1);
            sfx(SFX_WIN);
            buzz(200);
            Toast.makeText(this, "🎉 Field cleared — every mine accounted for!", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshMines() {
        if (hudTv == null) return;
        hudTv.setText(minesGameOver ? "💥 Boom! Tap ↻ to retry  •  Cleared: " + best("best_mines_wins")
                : (minesWon ? "🎉 Field cleared! Cleared: " + best("best_mines_wins")
                    : "Minesweeper 9×9 • 12 mines • safe first tap • cleared: " + best("best_mines_wins")));
        if (minesFlagButton != null) {
            minesFlagButton.setText(flagMode ? "🚩 Mode: FLAG MINE" : "⛏ Mode: DIG SQUARE");
            minesFlagButton.setBackgroundColor(flagMode ? 0xFFD97706 : 0xFF0284C7);
        }
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) {
            Button cell = mineCells[r][c];
            if (cell == null) continue;
            if (revealed[r][c]) {
                if (mines[r][c]) {
                    cell.setText("💣"); cell.setBackgroundColor(0xFFDC2626);
                } else {
                    int n = adjMines(r, c);
                    cell.setText(n == 0 ? "" : String.valueOf(n));
                    cell.setBackgroundColor(0xFF0F172A); cell.setTextColor(0xFF38BDF8);
                }
            } else {
                cell.setText(flagged[r][c] ? "🚩" : "");
                cell.setBackgroundColor(0xFF334155);
            }
        }
    }

    private void renderMines() {
        hudTv.setText("Minesweeper 9×9 • 12 mines • safe first tap • cleared: " + best("best_mines_wins"));
        Button flagToggle = Arc7Skin.actionButton(
                this, flagMode ? "🚩 Mode: FLAG MINE" : "⛏ Mode: DIG SQUARE",
                flagMode ? 0xFFD97706 : 0xFF0284C7);
        minesFlagButton = flagToggle;
        flagToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { flagMode = !flagMode; sfx(SFX_CLICK); refreshMines(); }
        });
        stageBox.addView(flagToggle);

        for (int r = 0; r < 9; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            for (int c = 0; c < 9; c++) {
                final int fr = r, fc = c;
                Button cell = new Button(this);
                cell.setPadding(0, 0, 0, 0);
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (minesGameOver || minesWon) return;
                        if (flagMode) {
                            if (!revealed[fr][fc]) { flagged[fr][fc] = !flagged[fr][fc]; sfx(SFX_CLICK); }
                        } else {
                            if (flagged[fr][fc]) return;
                            if (!minesGenerated) placeMinesExcept(fr, fc);
                            if (mines[fr][fc]) {
                                revealed[fr][fc] = true;
                                minesGameOver = true;
                                sfx(SFX_BAD);
                                buzz(220);
                            } else {
                                floodReveal(fr, fc);
                                sfx(SFX_CLICK);
                            }
                        }
                        checkMinesWin();
                        refreshMines();
                    }
                });
                mineCells[r][c] = cell;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                row.addView(cell, lp);
            }
            stageBox.addView(row);
        }
    }

    /* ==================== 5. KLONDIKE SOLITAIRE ==================== */

    private void initSolitaire() {
        List<Integer> deck = new ArrayList<Integer>();
        for (int i = 0; i < 52; i++) deck.add(i);
        Collections.shuffle(deck, rng);
        stock.clear();
        waste.clear();
        for (int s = 0; s < 4; s++) foundationTop[s] = -1;
        int idx = 0;
        for (int col = 0; col < 7; col++) {
            tableau[col].clear();
            for (int k = 0; k <= col; k++) tableau[col].add(deck.get(idx++));
            hiddenCount[col] = col;
        }
        while (idx < 52) stock.add(deck.get(idx++));
        solSelPile = -2;
        solitaireWon = false;
    }

    private static String cardLabel(int c) {
        String[] ranks = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
        String[] suits = {"♠", "♥", "♣", "♦"};
        return ranks[c % 13] + suits[c / 13];
    }

    private static boolean isRed(int c) {
        int s = c / 13;
        return s == 1 || s == 3;
    }

    private void renderSolitaire() {
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setPadding(0, 0, 0, dp(10));

        solitaireStockButton = Arc7Skin.actionButton(this, "", 0xFF0284C7);
        solitaireStockButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!stock.isEmpty()) waste.add(stock.remove(stock.size() - 1));
                else while (!waste.isEmpty()) stock.add(waste.remove(waste.size() - 1));
                solSelPile = -2;
                sfx(SFX_CLICK);
                refreshSolitaire();
            }
        });
        topRow.addView(solitaireStockButton);

        solitaireWasteButton = Arc7Skin.actionButton(this, "", 0xFF334155);
        solitaireWasteButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (waste.isEmpty()) return;
                int top = waste.get(waste.size() - 1);
                if (foundationTop[top / 13] + 1 == (top % 13)) {
                    foundationTop[top / 13]++;
                    waste.remove(waste.size() - 1);
                    solSelPile = -2;
                    sfx(SFX_GOOD);
                } else {
                    solSelPile = -1;
                    sfx(SFX_CLICK);
                }
                refreshSolitaire();
            }
        });
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = dp(8);
        topRow.addView(solitaireWasteButton, wlp);
        stageBox.addView(topRow);

        solitaireColumns = new LinearLayout(this);
        solitaireColumns.setOrientation(LinearLayout.HORIZONTAL);
        stageBox.addView(solitaireColumns);
        refreshSolitaire();
    }

    private void refreshSolitaire() {
        int foundTotal = (foundationTop[0] + 1) + (foundationTop[1] + 1) + (foundationTop[2] + 1) + (foundationTop[3] + 1);
        if (foundTotal == 52 && !solitaireWon) {
            solitaireWon = true;
            bumpBest("best_solitaire_wins", best("best_solitaire_wins") + 1);
            sfx(SFX_WIN);
            buzz(240);
            Toast.makeText(this, "🎉 Klondike conquered — all four foundations complete!", Toast.LENGTH_LONG).show();
        }
        if (hudTv != null) {
            hudTv.setText("Klondike  •  Foundations " + foundTotal + "/52  •  Won: " + best("best_solitaire_wins"));
        }
        if (solitaireStockButton != null) solitaireStockButton.setText("🂠 Stock (" + stock.size() + ")");
        if (solitaireWasteButton != null) {
            solitaireWasteButton.setText(waste.isEmpty() ? "[Waste]" : cardLabel(waste.get(waste.size() - 1)));
            solitaireWasteButton.setBackgroundColor(solSelPile == -1 ? 0xFFD97706 : 0xFF334155);
        }
        if (solitaireColumns == null) return;
        solitaireColumns.removeAllViews();
        for (int col = 0; col < 7; col++) {
            final int fc = col;
            LinearLayout pile = new LinearLayout(this);
            pile.setOrientation(LinearLayout.VERTICAL);
            List<Integer> cards = tableau[col];
            if (cards.isEmpty()) {
                Button empty = Arc7Skin.actionButton(this, "[K]", 0xFF1E293B);
                empty.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { moveSelectedToColumn(fc); }
                });
                pile.addView(empty);
            } else {
                for (int k = 0; k < cards.size(); k++) {
                    int card = cards.get(k);
                    boolean hidden = k < hiddenCount[col];
                    Button cb = new Button(this);
                    cb.setText(hidden ? "▓" : cardLabel(card));
                    cb.setTextColor(hidden ? Color.GRAY : (isRed(card) ? 0xFFFCA5A5 : Color.WHITE));
                    cb.setBackgroundColor((solSelPile == col && k == cards.size() - 1) ? 0xFFD97706 : 0xFF1E293B);
                    cb.setPadding(dp(4), dp(4), dp(4), dp(4));
                    if (k == cards.size() - 1) {
                        cb.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) { onTableauTopTap(fc); }
                        });
                    }
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(44), dp(34));
                    clp.bottomMargin = dp(2);
                    pile.addView(cb, clp);
                }
            }
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.rightMargin = dp(4);
            solitaireColumns.addView(pile, plp);
        }
    }

    private void onTableauTopTap(int col) {
        if (solitaireWon) return;
        List<Integer> cards = tableau[col];
        int top = cards.get(cards.size() - 1);
        if (solSelPile == -2) {
            if (foundationTop[top / 13] + 1 == (top % 13)) {
                foundationTop[top / 13]++;
                cards.remove(cards.size() - 1);
                if (hiddenCount[col] > 0 && hiddenCount[col] >= cards.size()) hiddenCount[col] = Math.max(0, cards.size() - 1);
                sfx(SFX_GOOD);
            } else {
                solSelPile = col;
                sfx(SFX_CLICK);
            }
        } else {
            moveSelectedToColumn(col);
        }
        refreshSolitaire();
    }

    private void moveSelectedToColumn(int dstCol) {
        Integer moving = null;
        if (solSelPile == -1 && !waste.isEmpty()) moving = waste.get(waste.size() - 1);
        else if (solSelPile >= 0 && !tableau[solSelPile].isEmpty())
            moving = tableau[solSelPile].get(tableau[solSelPile].size() - 1);

        if (moving != null) {
            List<Integer> dst = tableau[dstCol];
            boolean ok = dst.isEmpty()
                    ? ((moving % 13) == 12)
                    : (isRed(moving) != isRed(dst.get(dst.size() - 1))
                       && (moving % 13) + 1 == (dst.get(dst.size() - 1) % 13));
            if (ok) {
                dst.add(moving);
                if (solSelPile == -1) waste.remove(waste.size() - 1);
                else {
                    tableau[solSelPile].remove(tableau[solSelPile].size() - 1);
                    if (hiddenCount[solSelPile] > 0 && hiddenCount[solSelPile] >= tableau[solSelPile].size()) {
                        hiddenCount[solSelPile] = Math.max(0, tableau[solSelPile].size() - 1);
                    }
                }
                sfx(SFX_MOVE);
            } else {
                sfx(SFX_BAD);
            }
        }
        solSelPile = -2;
        refreshSolitaire();
    }

    /* ==================== 6. SNAKE ==================== */

    private String snakeHudText() {
        int score = snakeView != null ? snakeView.getScore() : 0;
        boolean over = snakeView != null && snakeView.isFinished();
        return over
                ? "Snake  •  Game Over — score " + score + "  •  Best " + best("best_snake_score")
                : "Snake  •  Score " + score + "  •  Best " + best("best_snake_score") + "  •  arrows to steer";
    }

    private void renderSnake() {
        snakeView = new SnakeView(this);
        stageBox.addView(snakeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(400)));

        LinearLayout arrows = new LinearLayout(this);
        arrows.setOrientation(LinearLayout.HORIZONTAL);
        arrows.setGravity(Gravity.CENTER_HORIZONTAL);
        arrows.setPadding(0, dp(10), 0, 0);
        String[] labels = new String[]{"▲", "◀", "▶", "▼"};
        final int[] dirs = new int[]{0, 3, 1, 2};
        for (int i = 0; i < labels.length; i++) {
            final int d = dirs[i];
            Button b = Arc7Skin.actionButton(this, labels[i], 0xFF16A34A);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(52));
            lp.setMargins(dp(5), 0, dp(5), 0);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (snakeView != null) { snakeView.setDirection(d); sfx(SFX_MOVE); }
                }
            });
            arrows.addView(b, lp);
        }
        stageBox.addView(arrows);

        TextView hint = new TextView(this);
        hint.setText("First arrow press starts the run — speed ramps up as you eat.");
        hint.setTextColor(0xFF94A3B8);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(0, dp(8), 0, 0);
        stageBox.addView(hint);
        if (hudTv != null) hudTv.setText(snakeHudText());
    }

    /* ==================== 7. BREAKOUT ==================== */

    private String breakoutHudText() {
        int score = breakoutView != null ? breakoutView.getScore() : 0;
        int lives = breakoutView != null ? breakoutView.getLives() : 3;
        int level = breakoutView != null ? breakoutView.getLevel() : 1;
        boolean over = breakoutView != null && breakoutView.isFinished();
        return over
                ? "Breakout  •  Game Over — score " + score + "  •  Best " + best("best_breakout_score")
                : "Breakout  •  Score " + score + "  •  Level " + level + "  •  Lives " + repeatHearts(lives) + "  •  Best " + best("best_breakout_score");
    }

    private static String repeatHearts(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(0, n); i++) sb.append('♥');
        return sb.length() == 0 ? "0" : sb.toString();
    }

    private void renderBreakout() {
        breakoutView = new BreakoutView(this);
        stageBox.addView(breakoutView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(400)));

        LinearLayout nudge = new LinearLayout(this);
        nudge.setOrientation(LinearLayout.HORIZONTAL);
        nudge.setGravity(Gravity.CENTER_HORIZONTAL);
        nudge.setPadding(0, dp(10), 0, 0);
        String[] labels = new String[]{"◀  Paddle", "Paddle  ▶"};
        final int[] steps = new int[]{-1, 1};
        for (int i = 0; i < labels.length; i++) {
            final int step = steps[i];
            Button b = Arc7Skin.actionButton(this, labels[i], 0xFF2563EB);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(6), 0, dp(6), 0);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (breakoutView != null) { breakoutView.nudgePaddle(step); sfx(SFX_CLICK); }
                }
            });
            nudge.addView(b, lp);
        }
        stageBox.addView(nudge);

        TextView hint = new TextView(this);
        hint.setText("Drag your finger across the board — tap it to launch the ball.");
        hint.setTextColor(0xFF94A3B8);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(0, dp(8), 0, 0);
        stageBox.addView(hint);
        if (hudTv != null) hudTv.setText(breakoutHudText());
    }

    private final class SnakeView extends View implements Runnable {
        static final int COLS = 14;
        static final int ROWS = 16;
        private final List<int[]> body = new ArrayList<int[]>();
        private int direction = 1; // 0=up 1=right 2=down 3=left
        private int pendingDirection = 1;
        private int foodX = 3, foodY = 3;
        private boolean alive = false;
        private boolean finished = false;
        private int score = 0;
        private int tickMs = 190;
        private final Handler handler = new Handler();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        SnakeView(Context context) {
            super(context);
            setBackgroundColor(0xFF0B1120);
            reset();
        }

        void reset() {
            body.clear();
            for (int i = 0; i < 3; i++) body.add(new int[]{COLS / 2 - i, ROWS / 2});
            direction = 1;
            pendingDirection = 1;
            score = 0;
            tickMs = 190;
            alive = false;
            finished = false;
            spawnFood();
            invalidate();
        }

        int getScore() { return score; }
        boolean isFinished() { return finished; }

        void stopLoop() { handler.removeCallbacks(this); }

        void resumeLoop() {
            if (alive && !finished) {
                handler.removeCallbacks(this);
                handler.postDelayed(this, tickMs);
            }
        }

        void setDirection(int d) {
            if ((direction == 0 && d == 2) || (direction == 2 && d == 0)
                    || (direction == 1 && d == 3) || (direction == 3 && d == 1)) return;
            pendingDirection = d;
            if (!alive && !finished) {
                alive = true;
                handler.removeCallbacks(this);
                handler.postDelayed(this, tickMs);
            }
        }

        private void spawnFood() {
            List<int[]> free = new ArrayList<int[]>();
            for (int x = 0; x < COLS; x++) {
                for (int y = 0; y < ROWS; y++) {
                    if (!hitsBody(x, y)) free.add(new int[]{x, y});
                }
            }
            if (free.isEmpty()) { finished = true; alive = false; return; }
            int[] pick = free.get(rng.nextInt(free.size()));
            foodX = pick[0];
            foodY = pick[1];
        }

        private boolean hitsBody(int x, int y) {
            for (int i = 0; i < body.size(); i++) {
                if (body.get(i)[0] == x && body.get(i)[1] == y) return true;
            }
            return false;
        }

        @Override
        public void run() {
            if (!alive || finished) return;
            direction = pendingDirection;
            int[] head = body.get(0);
            int nx = head[0], ny = head[1];
            if (direction == 0) ny--;
            else if (direction == 1) nx++;
            else if (direction == 2) ny++;
            else nx--;
            if (nx < 0 || nx >= COLS || ny < 0 || ny >= ROWS || hitsBody(nx, ny)) {
                alive = false;
                finished = true;
                bumpBest("best_snake_score", score);
                sfx(SFX_BAD);
                buzz(180);
                if (hudTv != null) hudTv.setText(snakeHudText());
                invalidate();
                return;
            }
            body.add(0, new int[]{nx, ny});
            if (nx == foodX && ny == foodY) {
                score++;
                tickMs = Math.max(95, tickMs - 7);
                bumpBest("best_snake_score", score);
                sfx(SFX_GOOD);
                buzz(20);
                spawnFood();
                if (hudTv != null) hudTv.setText(snakeHudText());
            } else {
                body.remove(body.size() - 1);
            }
            invalidate();
            handler.postDelayed(this, tickMs);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            int cell = Math.min(w / COLS, h / ROWS);
            int ox = (w - cell * COLS) / 2;
            int oy = (h - cell * ROWS) / 2;
            fill.setColor(0xFF111A2E);
            canvas.drawRect(ox, oy, ox + cell * COLS, oy + cell * ROWS, fill);
            fill.setColor(0xFF1B2740);
            for (int x = 0; x <= COLS; x++) {
                canvas.drawRect(ox + x * cell, oy, ox + x * cell + 1, oy + cell * ROWS, fill);
            }
            for (int y = 0; y <= ROWS; y++) {
                canvas.drawRect(ox, oy + y * cell, ox + cell * COLS, oy + y * cell + 1, fill);
            }
            fill.setColor(0xFFEF4444);
            canvas.drawCircle(ox + foodX * cell + cell / 2f, oy + foodY * cell + cell / 2f, cell * 0.36f, fill);
            for (int i = body.size() - 1; i >= 0; i--) {
                int[] s = body.get(i);
                fill.setColor(i == 0 ? 0xFFA3E635 : 0xFF22C55E);
                float inset = i == 0 ? 1f : 2f;
                RectF rect = new RectF(ox + s[0] * cell + inset, oy + s[1] * cell + inset,
                        ox + (s[0] + 1) * cell - inset, oy + (s[1] + 1) * cell - inset);
                canvas.drawRoundRect(rect, cell * 0.3f, cell * 0.3f, fill);
            }
            if (finished) {
                fill.setColor(0xAA0B1120);
                canvas.drawRect(ox, oy, ox + cell * COLS, oy + cell * ROWS, fill);
                fill.setColor(0xFFF8FAFC);
                fill.setTextAlign(Paint.Align.CENTER);
                fill.setTextSize(dp(22));
                canvas.drawText("Game Over", ox + cell * COLS / 2f, oy + cell * ROWS / 2f - dp(6), fill);
                fill.setTextSize(dp(14));
                fill.setColor(0xFF94A3B8);
                canvas.drawText("Score " + score + "  •  tap ↻ to play again", ox + cell * COLS / 2f, oy + cell * ROWS / 2f + dp(20), fill);
            }
        }
    }

    private final class BreakoutView extends View implements Runnable {
        private int paddleX = 90; // 0..100 percentage of width
        private float ballX = 50, ballY = 82; // percentage coords
        private float vx = 0f, vy = 0f;
        private boolean launched = false;
        private boolean alive = true;
        private boolean finished = false;
        private int lives = 3;
        private int level = 1;
        private int score = 0;
        private int bricksLeft = 0;
        private int[][] brickAlive = new int[4][6]; // rows x cols
        private int[][] brickColor = new int[4][6];
        private final Handler handler = new Handler();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        BreakoutView(Context context) {
            super(context);
            setBackgroundColor(0xFF0B1120);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            resetLevel();
        }

        int getScore() { return score; }
        int getLives() { return lives; }
        int getLevel() { return level; }
        boolean isFinished() { return finished; }

        void stopLoop() { handler.removeCallbacks(this); }

        void resumeLoop() {
            if (!finished && launched) {
                handler.removeCallbacks(this);
                handler.postDelayed(this, 33);
            }
        }

        void nudgePaddle(int step) {
            paddleX = clamp(paddleX + step * 14, 2, 98);
            if (!launched) ballX = paddleX;
            invalidate();
        }

        void launch() {
            if (launched || finished) return;
            launched = true;
            float speed = 2.1f + level * 0.35f;
            vx = (rng.nextBoolean() ? 1f : -1f) * (0.9f + rng.nextFloat() * 0.7f);
            vy = -speed;
            sfx(SFX_MOVE);
            handler.removeCallbacks(this);
            handler.postDelayed(this, 33);
        }

        void resetLevel() {
            lives = lives <= 0 ? 3 : lives;
            bricksLeft = 0;
            int[] palette = {0xFFEF4444, 0xFFF97316, 0xFFEAB308, 0xFF22C55E};
            for (int r = 0; r < brickAlive.length; r++) {
                for (int c = 0; c < brickAlive[r].length; c++) {
                    brickAlive[r][c] = 1;
                    brickColor[r][c] = palette[r];
                    bricksLeft++;
                }
            }
            ballX = 50; ballY = 82;
            vx = 0; vy = 0;
            launched = false;
            alive = true;
            invalidate();
        }

        void fullReset() {
            score = 0;
            level = 1;
            lives = 3;
            finished = false;
            resetLevel();
            if (hudTv != null) hudTv.setText(breakoutHudText());
        }

        private int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (finished) {
                if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    fullReset();
                    launch();
                }
                return true;
            }
            float w = Math.max(1, getWidth());
            int pct = (int) ((event.getX() / w) * 100);
            paddleX = clamp(pct, 2, 98);
            if (!launched) ballX = paddleX;
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) launch();
            invalidate();
            return true;
        }

        @Override
        public void run() {
            if (finished) return;
            if (launched) {
                ballX += vx;
                ballY += vy;
                if (ballX < 4) { ballX = 4; vx = -vx; sfx(SFX_CLICK); }
                else if (ballX > 96) { ballX = 96; vx = -vx; sfx(SFX_CLICK); }
                if (ballY < 4) { ballY = 4; vy = -vy; sfx(SFX_CLICK); }
                // paddle bounce zone
                if (vy > 0 && ballY >= 78 && ballY <= 84 && Math.abs(ballX - paddleX) <= 13) {
                    vy = -Math.abs(vy);
                    vx = (ballX - paddleX) * 0.16f;
                    sfx(SFX_CLICK);
                }
                if (ballY > 96) {
                    lives--;
                    sfx(SFX_BAD);
                    buzz(120);
                    if (lives <= 0) {
                        finished = true;
                        bumpBest("best_breakout_score", score);
                        if (hudTv != null) hudTv.setText(breakoutHudText());
                        invalidate();
                        return;
                    }
                    ballX = paddleX; ballY = 82; vx = 0; vy = 0; launched = false;
                    if (hudTv != null) hudTv.setText(breakoutHudText());
                }
                // brick collision
                int col = (int) (ballX / (100f / 6f));
                int row = (int) (ballY / 9f);
                if (col >= 0 && col < 6 && row >= 0 && row < 4 && brickAlive[row][col] == 1) {
                    brickAlive[row][col] = 0;
                    bricksLeft--;
                    score += 10 + (3 - row) * 5;
                    bumpBest("best_breakout_score", score);
                    vy = -vy;
                    sfx(SFX_GOOD);
                    if (bricksLeft == 0) {
                        level++;
                        score += 100;
                        sfx(SFX_WIN);
                        buzz(160);
                        resetLevel();
                    }
                    if (hudTv != null) hudTv.setText(breakoutHudText());
                }
                invalidate();
            }
            handler.postDelayed(this, 33);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            fill.setColor(0xFF0B1120);
            canvas.drawRect(0, 0, w, h, fill);
            float bw = w / 7.4f;
            float bh = h * 0.055f;
            float gap = w * 0.012f;
            float top = h * 0.06f;
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 6; c++) {
                    if (brickAlive[r][c] != 1) continue;
                    fill.setColor(brickColor[r][c]);
                    float x = w * 0.055f + c * (bw + gap);
                    float y = top + r * (bh + gap);
                    canvas.drawRoundRect(new RectF(x, y, x + bw, y + bh), dp(5), dp(5), fill);
                }
            }
            // paddle
            fill.setColor(0xFFE0F2FE);
            float pw = w * 0.21f, ph = h * 0.028f;
            float px = (paddleX / 100f) * w - pw / 2f;
            float py = h * 0.86f;
            canvas.drawRoundRect(new RectF(px, py, px + pw, py + ph), ph / 2f, ph / 2f, fill);
            // ball
            fill.setColor(0xFFFFFFFF);
            float bx = (ballX / 100f) * w;
            float by = (ballY / 100f) * h;
            canvas.drawCircle(bx, by, Math.max(dp(4), w * 0.012f), fill);
            if (!launched && !finished) {
                fill.setColor(0xFF94A3B8);
                fill.setTextAlign(Paint.Align.CENTER);
                fill.setTextSize(dp(13));
                canvas.drawText("Launch the ball!", w / 2f, h * 0.66f, fill);
            }
            if (finished) {
                fill.setColor(0xAA0B1120);
                canvas.drawRect(0, 0, w, h, fill);
                fill.setColor(0xFFF8FAFC);
                fill.setTextAlign(Paint.Align.CENTER);
                fill.setTextSize(dp(22));
                canvas.drawText("Game Over", w / 2f, h / 2f - dp(4), fill);
                fill.setTextSize(dp(13));
                fill.setColor(0xFF94A3B8);
                canvas.drawText("Score " + score + "  •  tap to restart", w / 2f, h / 2f + dp(20), fill);
            }
        }
    }
}

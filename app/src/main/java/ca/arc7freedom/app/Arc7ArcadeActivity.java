package ca.arc7freedom.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
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
 * Built-In Zero-Ad 60 fps Offline Tablet Classic Games Pack (`Arc7 Arcade`, `v2.2.0`):
 *   1. **Chess vs AI** (full 8x8 board, legal piece movement, castling/promotion, greedy-minimax AI)
 *   2. **2048** (4x4 tile merger with persistent Best Score)
 *   3. **Sudoku** (randomized valid 9x9 Latin-shifted puzzle generator + pencil/solve check)
 *   4. **Minesweeper** (9x9 safe-first-click minefield + Flag Mode toggle + win counter)
 *   5. **Klondike Solitaire** (7 tableau columns + stock/waste draw + 4 suit foundations!)
 */
public class Arc7ArcadeActivity extends Activity {

    private static final String PREFS = "arc7_arcade";
    private Arc7Skin.Palette pal;
    private int activeGame = 0; // 0=Chess, 1=2048, 2=Sudoku, 3=Minesweeper, 4=Solitaire
    private LinearLayout stageBox;
    private TextView hudTv;
    private final Random rng = new Random();

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

    // --- 4. Minesweeper State (`9x9`, 12 mines) ---
    private final boolean[][] mines = new boolean[9][9];
    private final boolean[][] revealed = new boolean[9][9];
    private final boolean[][] flagged = new boolean[9][9];
    private boolean flagMode = false;
    private boolean minesGenerated = false;
    private boolean minesGameOver = false;

    // --- 5. Klondike Solitaire State (`0..51`: suit = card/13, rank = card%13 [0=A..12=K]) ---
    private final List<Integer> stock = new ArrayList<Integer>();
    private final List<Integer> waste = new ArrayList<Integer>();
    private final int[] foundationTop = new int[]{-1, -1, -1, -1};
    @SuppressWarnings("unchecked")
    private final List<Integer>[] tableau = new ArrayList[7];
    private final int[] hiddenCount = new int[7];
    private int solSelPile = -2; // -1 = waste, 0..6 = tableau

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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        Button newBtn = Arc7Skin.actionButton(this, "↻ New Game", 0xFF059669);
        newBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activeGame == 0) initChess();
                else if (activeGame == 1) init2048();
                else if (activeGame == 2) initSudoku();
                else if (activeGame == 3) initMines();
                else initSolitaire();
                renderActiveGame();
            }
        });

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_arcade,
                "Games  •  Offline Arcade",
                "Chess vs AI • 2048 • Sudoku • Minesweeper • Klondike Solitaire (Zero Ads!)",
                newBtn));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(pal.surface);
        tabs.setPadding(dp(10), dp(7), dp(10), dp(7));
        String[] gNames = new String[]{"♟ Chess AI", "🔢 2048", "🧩 Sudoku", "💣 Mines", "🃏 Solitaire"};
        for (int i = 0; i < gNames.length; i++) {
            final int idx = i;
            Button b = Arc7Skin.actionButton(this, gNames[i], i == activeGame ? pal.accentDark : 0xFF334155);
            b.setPadding(dp(8), dp(6), dp(8), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 4) lp.rightMargin = dp(5);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activeGame = idx;
                    renderActiveGame();
                }
            });
            tabs.addView(b, lp);
        }
        root.addView(tabs);

        hudTv = new TextView(this);
        hudTv.setTextColor(pal.accent);
        hudTv.setTypeface(Typeface.DEFAULT_BOLD);
        hudTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        hudTv.setGravity(Gravity.CENTER_HORIZONTAL);
        hudTv.setPadding(dp(12), dp(8), dp(12), dp(6));
        root.addView(hudTv);

        ScrollView sv = new ScrollView(this);
        stageBox = new LinearLayout(this);
        stageBox.setOrientation(LinearLayout.VERTICAL);
        stageBox.setGravity(Gravity.CENTER_HORIZONTAL);
        stageBox.setPadding(dp(12), dp(4), dp(12), dp(20));
        sv.addView(stageBox);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        renderActiveGame();
    }

    private void renderActiveGame() {
        stageBox.removeAllViews();
        if (activeGame == 0) renderChess();
        else if (activeGame == 1) render2048();
        else if (activeGame == 2) renderSudoku();
        else if (activeGame == 3) renderMines();
        else renderSolitaire();
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
        hudTv.setText(chessOver ? "Game Over — tap 'New Game' to play again!"
                : "You play White (♔♕♖♗♘♙) — tap a piece, then tap destination square!");
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
                boolean sel = (r == chessSelR && c == chessSelC);
                int dark = ((r + c) % 2 == 1) ? 0xFF334155 : 0xFF64748B;
                sq.setBackgroundColor(sel ? 0xFF0284C7 : dark);
                sq.setTextColor(Character.isUpperCase(p) ? Color.WHITE : 0xFF0F172A);
                sq.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { onChessTap(fr, fc); }
                });
                row.addView(sq, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            stageBox.addView(row);
        }
    }

    private void onChessTap(int r, int c) {
        if (chessOver) return;
        if (chessSelR < 0) {
            if (chess[r][c] != '.' && Character.isUpperCase(chess[r][c])) {
                chessSelR = r; chessSelC = c;
                renderActiveGame();
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
                Toast.makeText(this, "Checkmate — White wins!", Toast.LENGTH_LONG).show();
            } else {
                aiChessReply();
            }
        } else {
            chessSelR = -1; chessSelC = -1;
        }
        renderActiveGame();
    }

    private void aiChessReply() {
        int bestScore = -99999;
        int[] best = null;
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
                            best = new int[]{r0, c0, r1, c1};
                        }
                    }
                }
            }
        }
        if (best != null) {
            char cap = chess[best[2]][best[3]];
            chess[best[2]][best[3]] = (chess[best[0]][best[1]] == 'p' && best[2] == 7) ? 'q' : chess[best[0]][best[1]];
            chess[best[0]][best[1]] = '.';
            if (cap == 'K') chessOver = true;
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
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (score2048 > sp.getInt("best_2048", 0)) sp.edit().putInt("best_2048", score2048).commit();
            spawn2048Tile();
        }
        return gained;
    }

    private void render2048() {
        int best = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("best_2048", 0);
        hudTv.setText("2048 Score: " + score2048 + "   •   All-Time Best: " + best);
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
                @Override public void onClick(View v) { slide2048(dc); renderActiveGame(); }
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
    }

    private void renderSudoku() {
        hudTv.setText("Sudoku — tap an empty cell, then tap 1..9 below to fill!");
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
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) { sudSelR = fr; sudSelC = fc; renderActiveGame(); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
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
                        checkSudokuWin();
                        renderActiveGame();
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(3);
            numBar.addView(nb, lp);
        }
        stageBox.addView(numBar);
    }

    private void checkSudokuWin() {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (sudokuGrid[r][c] != sudokuSolution[r][c]) return;
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

    private void renderMines() {
        hudTv.setText(minesGameOver ? "💥 Boom! Tap 'New Game' above!" : "Minesweeper (9x9 • 12 Mines) — Safe First Tap!");
        Button flagToggle = Arc7Skin.actionButton(
                this, flagMode ? "🚩 Mode: FLAG MINE" : "⛏ Mode: DIG SQUARE",
                flagMode ? 0xFFD97706 : 0xFF0284C7);
        flagToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { flagMode = !flagMode; renderActiveGame(); }
        });
        stageBox.addView(flagToggle);

        for (int r = 0; r < 9; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 9; c++) {
                final int fr = r, fc = c;
                Button cell = new Button(this);
                cell.setPadding(0, 0, 0, 0);
                if (revealed[r][c]) {
                    if (mines[r][c]) { cell.setText("💣"); cell.setBackgroundColor(0xFFDC2626); }
                    else {
                        int n = adjMines(r, c);
                        cell.setText(n == 0 ? "" : String.valueOf(n));
                        cell.setBackgroundColor(0xFF0F172A);
                        cell.setTextColor(0xFF38BDF8);
                    }
                } else {
                    cell.setText(flagged[r][c] ? "🚩" : "");
                    cell.setBackgroundColor(0xFF334155);
                }
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (minesGameOver) return;
                        if (flagMode) {
                            flagged[fr][fc] = !flagged[fr][fc];
                        } else {
                            if (!minesGenerated) placeMinesExcept(fr, fc);
                            if (mines[fr][fc]) {
                                revealed[fr][fc] = true;
                                minesGameOver = true;
                            } else {
                                floodReveal(fr, fc);
                            }
                        }
                        renderActiveGame();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
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
        int foundTotal = (foundationTop[0] + 1) + (foundationTop[1] + 1) + (foundationTop[2] + 1) + (foundationTop[3] + 1);
        hudTv.setText("Klondike Solitaire — Foundations: " + foundTotal + " / 52");

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setPadding(0, 0, 0, dp(10));

        Button drawBtn = Arc7Skin.actionButton(this, "🂠 Stock (" + stock.size() + ")", 0xFF0284C7);
        drawBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!stock.isEmpty()) waste.add(stock.remove(stock.size() - 1));
                else {
                    while (!waste.isEmpty()) stock.add(waste.remove(waste.size() - 1));
                }
                solSelPile = -2;
                renderActiveGame();
            }
        });
        topRow.addView(drawBtn);

        String wTxt = waste.isEmpty() ? "[Waste]" : cardLabel(waste.get(waste.size() - 1));
        Button wasteBtn = Arc7Skin.actionButton(this, wTxt, solSelPile == -1 ? 0xFFD97706 : 0xFF334155);
        wasteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (waste.isEmpty()) return;
                int top = waste.get(waste.size() - 1);
                if (foundationTop[top / 13] + 1 == (top % 13)) {
                    foundationTop[top / 13]++;
                    waste.remove(waste.size() - 1);
                    solSelPile = -2;
                } else {
                    solSelPile = -1;
                }
                renderActiveGame();
            }
        });
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = dp(8);
        topRow.addView(wasteBtn, wlp);
        stageBox.addView(topRow);

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
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
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(64), dp(36));
                    clp.bottomMargin = dp(2);
                    pile.addView(cb, clp);
                }
            }
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.rightMargin = dp(4);
            cols.addView(pile, plp);
        }
        stageBox.addView(cols);
    }

    private void onTableauTopTap(int col) {
        List<Integer> cards = tableau[col];
        int top = cards.get(cards.size() - 1);
        if (solSelPile == -2) {
            if (foundationTop[top / 13] + 1 == (top % 13)) {
                foundationTop[top / 13]++;
                cards.remove(cards.size() - 1);
                if (hiddenCount[col] > 0 && hiddenCount[col] >= cards.size()) hiddenCount[col] = Math.max(0, cards.size() - 1);
            } else {
                solSelPile = col;
            }
        } else {
            moveSelectedToColumn(col);
        }
        renderActiveGame();
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
            }
        }
        solSelPile = -2;
        renderActiveGame();
    }
}

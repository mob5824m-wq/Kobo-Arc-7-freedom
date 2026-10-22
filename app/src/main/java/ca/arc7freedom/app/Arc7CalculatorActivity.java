package ca.arc7freedom.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full Skinned Standalone **Arc7 Calculator & Unit Converter** (`v2.0.0`):
 *   - Scientific & Standard keypad (`sin`, `cos`, `tan`, `ln`, `log`, `√`, `^`, `!`, `π`, `e`, `(`, `)`)
 *   - `DEG` / `RAD` angle toggle + Memory register (`MC`, `MR`, `M+`, `M-`)
 *   - Tappable Calculation History Tape + 1-Tap Copy Result
 *   - Built-in Live Unit Converter (`Temperature`, `Length`, `Weight/Mass`, `Digital Storage`, `Speed`)
 */
public class Arc7CalculatorActivity extends Activity {

    private StringBuilder expr = new StringBuilder();
    private boolean degreeMode = true;
    private double memoryReg = 0.0;
    private final List<String> history = new ArrayList<String>();

    private TextView exprTv;
    private TextView livePreviewTv;
    private LinearLayout historyStrip;
    private LinearLayout calcBody;
    private LinearLayout convBody;

    private int dp(int v) {
        return Arc7Skin.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Arc7Skin.Palette pal = Arc7Skin.current(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        LinearLayout modeBar = new LinearLayout(this);
        modeBar.setOrientation(LinearLayout.HORIZONTAL);

        final Button tabCalcBtn = Arc7Skin.actionButton(this, "Scientific Calc", 0xFF0284C7);
        final Button tabConvBtn = Arc7Skin.actionButton(this, "Unit Converter", 0xFF334155);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.rightMargin = dp(8);
        modeBar.addView(tabCalcBtn, tlp);
        modeBar.addView(tabConvBtn);

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_calculator,
                "Calculator",
                "Scientific Engine • History Tape • Live Unit Converter",
                modeBar));

        calcBody = buildCalculatorBody(pal);
        convBody = buildConverterBody(pal);
        convBody.setVisibility(View.GONE);

        tabCalcBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calcBody.setVisibility(View.VISIBLE);
                convBody.setVisibility(View.GONE);
                tabCalcBtn.setBackgroundDrawable(Arc7Skin.buttonDrawable(Arc7CalculatorActivity.this, 0xFF0284C7, 10));
                tabConvBtn.setBackgroundDrawable(Arc7Skin.buttonDrawable(Arc7CalculatorActivity.this, 0xFF334155, 10));
            }
        });
        tabConvBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calcBody.setVisibility(View.GONE);
                convBody.setVisibility(View.VISIBLE);
                tabConvBtn.setBackgroundDrawable(Arc7Skin.buttonDrawable(Arc7CalculatorActivity.this, 0xFF0284C7, 10));
                tabCalcBtn.setBackgroundDrawable(Arc7Skin.buttonDrawable(Arc7CalculatorActivity.this, 0xFF334155, 10));
            }
        });

        root.addView(calcBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(convBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        updateDisplay();
    }

    private LinearLayout buildCalculatorBody(final Arc7Skin.Palette pal) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(12), dp(8), dp(12), dp(12));

        // Display Card
        LinearLayout lcd = Arc7Skin.card(this, pal);
        lcd.setPadding(dp(16), dp(12), dp(16), dp(12));

        HorizontalScrollView histScroll = new HorizontalScrollView(this);
        histScroll.setHorizontalScrollBarEnabled(false);
        historyStrip = new LinearLayout(this);
        historyStrip.setOrientation(LinearLayout.HORIZONTAL);
        histScroll.addView(historyStrip);
        lcd.addView(histScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        exprTv = new TextView(this);
        exprTv.setTextColor(pal.textPrimary);
        exprTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        exprTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        exprTv.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        exprTv.setSingleLine(true);
        exprTv.setPadding(0, dp(6), 0, dp(2));
        exprTv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Arc7 Calc", exprTv.getText()));
                    Toast.makeText(Arc7CalculatorActivity.this, "Copied to clipboard!", Toast.LENGTH_SHORT).show();
                }
            }
        });
        lcd.addView(exprTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        livePreviewTv = new TextView(this);
        livePreviewTv.setTextColor(pal.accentLight);
        livePreviewTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        livePreviewTv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        livePreviewTv.setGravity(Gravity.RIGHT);
        lcd.addView(livePreviewTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lcdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lcdLp.bottomMargin = dp(8);
        wrap.addView(lcd, lcdLp);

        // 7-Column Scientific + Numeric Matrix
        final String[][] rows = new String[][]{
                {"DEG", "sin(", "cos(", "7", "8", "9", "÷"},
                {"π",   "tan(", "√(",   "4", "5", "6", "×"},
                {"e",   "ln(",  "log(", "1", "2", "3", "-"},
                {"^",   "(",    ")",    "0", ".", "⌫", "+"},
                {"MC",  "MR",   "M+",   "!", "%", "C", "="}
        };

        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < rows.length; r++) {
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            rlp.bottomMargin = dp(6);
            for (int c = 0; c < rows[r].length; c++) {
                final String token = rows[r][c];
                final Button b = new Button(this);
                b.setText(token);
                b.setAllCaps(false);
                b.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                b.setTextColor(Color.WHITE);
                int bg = resolveKeyColor(token, pal);
                b.setBackgroundDrawable(Arc7Skin.buttonDrawable(this, bg, 12));
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        handleKeyPress(token, b);
                    }
                });
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (c < rows[r].length - 1) klp.rightMargin = dp(6);
                rowView.addView(b, klp);
            }
            keypad.addView(rowView, rlp);
        }
        wrap.addView(keypad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return wrap;
    }

    private int resolveKeyColor(String k, Arc7Skin.Palette pal) {
        if ("=".equals(k)) return 0xFF059669;
        if ("C".equals(k) || "⌫".equals(k)) return 0xFFDC2626;
        if ("+".equals(k) || "-".equals(k) || "×".equals(k) || "÷".equals(k)) return pal.accent;
        if (k.length() == 1 && ((k.charAt(0) >= '0' && k.charAt(0) <= '9') || k.charAt(0) == '.')) {
            return 0xFF1E293B;
        }
        return 0xFF334155;
    }

    private void handleKeyPress(String token, Button btn) {
        if ("C".equals(token)) {
            expr.setLength(0);
        } else if ("⌫".equals(token)) {
            if (expr.length() > 0) expr.deleteCharAt(expr.length() - 1);
        } else if ("DEG".equals(token) || "RAD".equals(token)) {
            degreeMode = !degreeMode;
            btn.setText(degreeMode ? "DEG" : "RAD");
        } else if ("MC".equals(token)) {
            memoryReg = 0.0;
            Toast.makeText(this, "Memory cleared", Toast.LENGTH_SHORT).show();
        } else if ("MR".equals(token)) {
            expr.append(formatNumber(memoryReg));
        } else if ("M+".equals(token)) {
            Double v = tryEval(expr.toString());
            if (v != null) memoryReg += v;
        } else if ("=".equals(token)) {
            String raw = expr.toString().trim();
            Double v = tryEval(raw);
            if (v != null && !Double.isNaN(v) && !Double.isInfinite(v)) {
                final String ans = formatNumber(v);
                addHistoryChip(raw + " = " + ans, ans);
                expr.setLength(0);
                expr.append(ans);
            } else {
                livePreviewTv.setText("Syntax Error");
                return;
            }
        } else {
            expr.append(token);
        }
        updateDisplay();
    }

    private void addHistoryChip(String label, final String value) {
        history.add(0, label);
        historyStrip.removeAllViews();
        for (int i = 0; i < Math.min(history.size(), 10); i++) {
            final String item = history.get(i);
            final String ans = item.substring(item.lastIndexOf('=') + 1).trim();
            Button chip = Arc7Skin.actionButton(this, item, 0xFF263447);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    expr.setLength(0);
                    expr.append(ans);
                    updateDisplay();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            historyStrip.addView(chip, lp);
        }
    }

    private void updateDisplay() {
        String s = expr.length() == 0 ? "0" : expr.toString();
        exprTv.setText(s);
        if (expr.length() == 0) {
            livePreviewTv.setText(degreeMode ? "Angle: Degrees • Tap number to copy" : "Angle: Radians • Tap number to copy");
            return;
        }
        Double v = tryEval(s);
        if (v != null && !Double.isNaN(v) && !Double.isInfinite(v)) {
            livePreviewTv.setText("= " + formatNumber(v));
        } else {
            livePreviewTv.setText("");
        }
    }

    private static String formatNumber(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        DecimalFormat df = new DecimalFormat("0.##########");
        return df.format(d);
    }

    private Double tryEval(String raw) {
        try {
            return new MathParser(raw, degreeMode).parse();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Clean recursive-descent scientific expression parser. */
    private static class MathParser {
        private final String s;
        private final boolean deg;
        private int pos = 0;

        MathParser(String raw, boolean deg) {
            this.s = raw.replace("×", "*").replace("÷", "/").replace(" ", "");
            this.deg = deg;
        }

        double parse() {
            double v = parseAddSub();
            if (pos < s.length()) throw new IllegalArgumentException();
            return v;
        }

        private double parseAddSub() {
            double x = parseMulDiv();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '+') { pos++; x += parseMulDiv(); }
                else if (c == '-') { pos++; x -= parseMulDiv(); }
                else break;
            }
            return x;
        }

        private double parseMulDiv() {
            double x = parsePower();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '*') { pos++; x *= parsePower(); }
                else if (c == '/') { pos++; x /= parsePower(); }
                else break;
            }
            return x;
        }

        private double parsePower() {
            double x = parsePostfix();
            if (pos < s.length() && s.charAt(pos) == '^') {
                pos++;
                x = Math.pow(x, parsePower());
            }
            return x;
        }

        private double parsePostfix() {
            double x = parseUnary();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '!') {
                    pos++;
                    double f = 1.0;
                    for (int i = 2; i <= (int) Math.round(x) && i <= 170; i++) f *= i;
                    x = f;
                } else if (c == '%') {
                    pos++;
                    x = x / 100.0;
                } else {
                    break;
                }
            }
            return x;
        }

        private double parseUnary() {
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
                return -parseUnary();
            }
            if (pos < s.length() && s.charAt(pos) == '+') {
                pos++;
                return parseUnary();
            }
            return parseAtom();
        }

        private double parseAtom() {
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                double v = parseAddSub();
                if (pos < s.length() && s.charAt(pos) == ')') pos++;
                return v;
            }
            if (s.startsWith("sin(", pos)) { pos += 4; double a = closeArg(); return Math.sin(deg ? Math.toRadians(a) : a); }
            if (s.startsWith("cos(", pos)) { pos += 4; double a = closeArg(); return Math.cos(deg ? Math.toRadians(a) : a); }
            if (s.startsWith("tan(", pos)) { pos += 4; double a = closeArg(); return Math.tan(deg ? Math.toRadians(a) : a); }
            if (s.startsWith("√(", pos))   { pos += 2; return Math.sqrt(closeArg()); }
            if (s.startsWith("ln(", pos))  { pos += 3; return Math.log(closeArg()); }
            if (s.startsWith("log(", pos)) { pos += 4; return Math.log10(closeArg()); }
            if (s.startsWith("π", pos))    { pos += 1; return Math.PI; }
            if (s.startsWith("e", pos) && (pos + 1 >= s.length() || !Character.isDigit(s.charAt(pos + 1)))) {
                pos += 1;
                return Math.E;
            }
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) throw new IllegalArgumentException();
            return Double.parseDouble(s.substring(start, pos));
        }

        private double closeArg() {
            double v = parseAddSub();
            if (pos < s.length() && s.charAt(pos) == ')') pos++;
            return v;
        }
    }

    private LinearLayout buildConverterBody(Arc7Skin.Palette pal) {
        ScrollView sv = new ScrollView(this);
        LinearLayout card = Arc7Skin.card(this, pal);

        final String[] categories = new String[]{
                "Temperature (°C / °F / K)",
                "Length (km / m / cm / mi / ft / in)",
                "Mass / Weight (kg / g / lb / oz)",
                "Digital Data (MB / GB / TB / KB)"
        };
        final String[][] units = new String[][]{
                {"Celsius (°C)", "Fahrenheit (°F)", "Kelvin (K)"},
                {"Kilometers (km)", "Meters (m)", "Centimeters (cm)", "Miles (mi)", "Feet (ft)", "Inches (in)"},
                {"Kilograms (kg)", "Grams (g)", "Pounds (lb)", "Ounces (oz)"},
                {"Megabytes (MB)", "Gigabytes (GB)", "Terabytes (TB)", "Kilobytes (KB)"}
        };
        final double[][] toBase = new double[][]{
                {1, 1, 1},
                {1000.0, 1.0, 0.01, 1609.344, 0.3048, 0.0254},
                {1.0, 0.001, 0.45359237, 0.02834952},
                {1.0, 1024.0, 1048576.0, 1.0 / 1024.0}
        };

        final Spinner catSpinner = new Spinner(this);
        catSpinner.setAdapter(new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_dropdown_item, categories));
        card.addView(catSpinner);

        final EditText inputEt = new EditText(this);
        inputEt.setText("1");
        inputEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        inputEt.setTextColor(pal.textPrimary);
        inputEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        card.addView(inputEt);

        final Spinner unitSpinner = new Spinner(this);
        card.addView(unitSpinner);

        final TextView resultsTv = new TextView(this);
        resultsTv.setTextColor(pal.accentLight);
        resultsTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        resultsTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        resultsTv.setPadding(dp(4), dp(12), dp(4), dp(4));
        card.addView(resultsTv);

        final Runnable recompute = new Runnable() {
            @Override
            public void run() {
                int cat = catSpinner.getSelectedItemPosition();
                int uIdx = Math.max(0, unitSpinner.getSelectedItemPosition());
                double val;
                try {
                    val = Double.parseDouble(inputEt.getText().toString().trim());
                } catch (Throwable t) {
                    resultsTv.setText("Enter a number above to convert instantly.");
                    return;
                }
                StringBuilder out = new StringBuilder();
                if (cat == 0) {
                    double c = uIdx == 0 ? val : (uIdx == 1 ? (val - 32.0) * 5.0 / 9.0 : val - 273.15);
                    out.append(String.format(Locale.US, "Celsius:     %.2f °C\n", c));
                    out.append(String.format(Locale.US, "Fahrenheit:  %.2f °F\n", c * 9.0 / 5.0 + 32.0));
                    out.append(String.format(Locale.US, "Kelvin:      %.2f K", c + 273.15));
                } else {
                    double base = val * toBase[cat][uIdx];
                    for (int i = 0; i < units[cat].length; i++) {
                        out.append(String.format(Locale.US, "%-20s %s\n",
                                units[cat][i] + ":", formatNumber(base / toBase[cat][i])));
                    }
                }
                resultsTv.setText(out.toString().trim());
            }
        };

        catSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                unitSpinner.setAdapter(new ArrayAdapter<String>(
                        Arc7CalculatorActivity.this,
                        android.R.layout.simple_spinner_dropdown_item, units[pos]));
                recompute.run();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        unitSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { recompute.run(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        inputEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { recompute.run(); }
        });

        sv.addView(card);
        LinearLayout host = new LinearLayout(this);
        host.setOrientation(LinearLayout.VERTICAL);
        host.setPadding(dp(12), dp(12), dp(12), dp(12));
        host.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return host;
    }
}

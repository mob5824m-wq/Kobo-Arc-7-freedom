package ca.arc7freedom.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Two-row media header: branding + one overflow, then a full-width search row. API15. */
@SuppressWarnings("deprecation")
public final class Arc7MediaHeader extends LinearLayout {
    private final EditText input;
    private final Button modeButton;

    public Arc7MediaHeader(Context context, Arc7Skin.Palette palette, int icon, String title,
                           String hint, String mode, final Runnable submit, final Runnable more) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(2), dp(12), dp(6));
        setBackgroundDrawable(Arc7Skin.headerDrawable(palette));

        LinearLayout brand = new LinearLayout(context);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(context);
        logo.setImageResource(icon);
        brand.addView(logo, new LayoutParams(dp(26), dp(26)));
        TextView name = new TextView(context);
        name.setText(title); name.setTextColor(Color.WHITE); name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT_BOLD); name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END); name.setPadding(dp(8), 0, dp(8), 0);
        brand.addView(name, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button overflow = button("More", "More " + title + " options");
        overflow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { more.run(); }
        });
        brand.addView(overflow, new LayoutParams(dp(56), dp(48)));
        addView(brand, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout search = new LinearLayout(context);
        search.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(context);
        input.setHint(hint); input.setHintTextColor(0xFFB2B7C5); input.setTextColor(Color.WHITE);
        input.setSingleLine(true); input.setTextSize(14); input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setContentDescription(hint);
        input.setBackgroundDrawable(Arc7Skin.cardDrawable(context, palette, 24));
        input.setPadding(dp(12), dp(7), dp(12), dp(7));
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView view, int action, KeyEvent event) {
                if (action == EditorInfo.IME_ACTION_SEARCH || (event != null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                    submit.run(); return true;
                }
                return false;
            }
        });
        search.addView(input, new LayoutParams(0, dp(48), 1f));
        if (mode != null) {
            modeButton = button(mode + " ▾", "Search type: " + mode);
            LayoutParams params = new LayoutParams(dp(88), dp(48)); params.leftMargin = dp(6);
            search.addView(modeButton, params);
        } else modeButton = null;
        Button go = button("Go", "Search " + title);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submit.run(); }
        });
        LayoutParams goParams = new LayoutParams(dp(48), dp(48)); goParams.leftMargin = dp(6);
        search.addView(go, goParams);
        addView(search, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private Button button(String label, String description) {
        Button button = Arc7Skin.actionButton(getContext(), label, 0xFF293244);
        button.setTextSize(12); button.setSingleLine(true); button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(0); button.setMinimumHeight(0);
        button.setPadding(dp(4), 0, dp(4), 0); button.setContentDescription(description);
        return button;
    }

    public EditText getSearchInput() { return input; }
    public Button getModeButton() { return modeButton; }
    private int dp(int value) { return Arc7Skin.dp(getContext(), value); }
}

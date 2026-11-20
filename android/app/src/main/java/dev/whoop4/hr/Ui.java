package dev.whoop4.hr;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colors + tiny view helpers (the UI is built in code, no layout XML). Dark theme. */
final class Ui {
    private Ui() {}
    static final int BG = 0xFF0F1115, CARD = 0xFF181B21, FG = 0xFFECEEF2, MUTED = 0xFF9AA1AD, LINE = 0xFF2A2E37,
            ACCENT = 0xFFFF5A4F, OK = 0xFF4ADE80, WARN = 0xFFFBBF24, BAD = 0xFFF87171;

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout card(Context c, String title) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD); bg.setCornerRadius(dp(c, 14)); bg.setStroke(dp(c, 1), LINE);
        l.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 12);
        l.setLayoutParams(lp);
        if (title != null) {
            TextView h = text(c, 12, MUTED, true);
            h.setText(title.toUpperCase(java.util.Locale.ROOT));
            h.setLetterSpacing(0.06f);
            h.setPadding(0, 0, 0, dp(c, 8));
            l.addView(h);
        }
        return l;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? BG : FG);
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 10));
        if (primary) bg.setColor(FG); else { bg.setColor(0); bg.setStroke(dp(c, 1), LINE); }
        b.setBackground(bg);
        b.setMinHeight(dp(c, 48));
        return b;
    }

    static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    static LinearLayout.LayoutParams weight(Context c, int marginStartDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(c, marginStartDp));
        return lp;
    }

    /** A "label over big value" stat cell; returns the value TextView. */
    static TextView stat(Context c, LinearLayout parent, String label) {
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(c, 13, MUTED, false); l.setText(label);
        TextView v = text(c, 18, FG, true); v.setText("--");
        cell.addView(l); cell.addView(v);
        parent.addView(cell, weight(c, 0));
        return v;
    }
}

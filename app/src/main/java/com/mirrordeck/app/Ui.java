package com.mirrordeck.app;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small programmatic-UI helpers so the prototype needs no layout XML. */
public final class Ui {
    public static final int BG = Color.parseColor("#101418");
    public static final int CARD = Color.parseColor("#1B222B");
    public static final int TEXT = Color.parseColor("#ECEFF1");
    public static final int MUTED = Color.parseColor("#90A4AE");
    public static final int ACCENT = Color.parseColor("#0288D1");
    public static final int ACCENT2 = Color.parseColor("#00695C");

    private Ui() {}

    public static LinearLayout column(Activity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(a, 20);
        root.setPadding(pad, pad, pad, pad);
        return root;
    }

    public static TextView title(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(26);
        t.setGravity(Gravity.START);
        t.setPadding(0, 0, 0, dp(a, 6));
        return t;
    }

    public static TextView body(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(MUTED);
        t.setTextSize(15);
        t.setPadding(0, 0, 0, dp(a, 14));
        return t;
    }

    public static TextView status(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(15);
        t.setPadding(0, dp(a, 10), 0, dp(a, 10));
        return t;
    }

    public static Button bigButton(Activity a, String text, int color) {
        Button b = new Button(a);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17);
        b.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 60));
        lp.setMargins(0, 0, 0, dp(a, 14));
        b.setLayoutParams(lp);
        return b;
    }

    public static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}

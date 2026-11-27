package org.telegram.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: shared building blocks for the Privacy screens (hub, devices, passkeys). */
public class AeroPrivacyKit {

    public static final int DANGER = 0xFFD94A6B;
    public static final int SUCCESS = 0xFF2BA970;

    public static int ink() { return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText); }
    public static int muted() { return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText); }
    public static int surface() { return Theme.getColor(Theme.key_windowBackgroundWhite); }
    public static int background() { return Theme.getColor(Theme.key_windowBackgroundGray); }

    /** scrolling page; the returned scroll view hosts {@code out[0]}, a vertical column to fill */
    public static ScrollView page(Context c, LinearLayout[] out) {
        ScrollView scroll = new ScrollView(c);
        scroll.setBackgroundColor(background());
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        out[0] = root;
        return scroll;
    }

    /** rounded white surface */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(AeroUi.round(surface(), 18));
        return l;
    }

    public static void add(LinearLayout parent, View child, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(bottomDp);
        parent.addView(child, lp);
    }

    public static TextView sectionLabel(Context c, String text) {
        TextView t = AeroUi.caption(c, text, muted());
        t.setPadding(dp(6), dp(4), dp(6), dp(8));
        return t;
    }

    /** tinted rounded square with an icon */
    public static View chip(Context c, int iconRes, int color, int sizeDp) {
        FrameLayout chip = new FrameLayout(c);
        chip.setBackground(AeroUi.round(AeroUi.alpha(color, 30), sizeDp * 0.3f));
        ImageView icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setColorFilter(color);
        chip.addView(icon, LayoutHelper.createFrame((int) (sizeDp * 0.5f), (int) (sizeDp * 0.5f), Gravity.CENTER));
        chip.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return chip;
    }

    /** small rounded status label */
    public static TextView pill(Context c, String text, int color) {
        TextView t = AeroUi.text(c, text, 11.5f, color, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(9), dp(3), dp(9), dp(3));
        t.setBackground(AeroUi.round(AeroUi.alpha(color, 30), 20));
        return t;
    }

    public static void restyle(TextView pill, String text, int color) {
        pill.setText(text);
        pill.setTextColor(color);
        pill.setBackground(AeroUi.round(AeroUi.alpha(color, 30), 20));
    }

    /** filled or outlined pill-shaped action button */
    public static TextView button(Context c, String label, int color, boolean filled, final Runnable action) {
        TextView t = AeroUi.text(c, label, 15, filled ? Color.WHITE : color, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(filled
                ? AeroUi.ripple(AeroUi.round(color, 14), 14)
                : AeroUi.ripple(AeroUi.roundStroke(Color.TRANSPARENT, 14, AeroUi.alpha(color, 120), 1.2f), 14));
        t.setOnClickListener(v -> action.run());
        t.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(50)));
        return t;
    }

    /** compact text button used inside list rows */
    public static TextView smallButton(Context c, String label, int color, final Runnable action) {
        TextView t = AeroUi.text(c, label, 13, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setBackground(AeroUi.ripple(AeroUi.roundStroke(Color.TRANSPARENT, 16, AeroUi.alpha(color, 110), 1f), 16));
        t.setOnClickListener(v -> action.run());
        t.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(32)));
        return t;
    }

    public static View divider(Context c, int startMarginDp) {
        View v = new View(c);
        v.setBackgroundColor(AeroUi.alpha(muted(), 40));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
        lp.leftMargin = dp(startMarginDp);
        v.setLayoutParams(lp);
        return v;
    }

    public static View loading(Context c, int accent) {
        FrameLayout f = new FrameLayout(c);
        ProgressBar pb = new ProgressBar(c);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(ColorStateList.valueOf(accent));
        f.addView(pb, LayoutHelper.createFrame(34, 34, Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(220)));
        return f;
    }

    /** centred icon + title + subtitle (+ optional action) for empty / error states */
    public static View message(Context c, int iconRes, int color, String title, String sub, String actionLabel, Runnable action) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(32), dp(24), dp(28));
        col.addView(chip(c, iconRes, color, 64));
        TextView t = AeroUi.text(c, title, 17, ink(), true);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(16);
        col.addView(t, tl);
        TextView s = AeroUi.text(c, sub, 13.5f, muted(), false);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = dp(6);
        col.addView(s, sl);
        if (actionLabel != null && action != null) {
            TextView b = button(c, actionLabel, color, true, action);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(180), dp(46));
            bl.topMargin = dp(20);
            col.addView(b, bl);
        }
        return col;
    }

    public static String when(int unixSeconds) {
        return new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(new Date(unixSeconds * 1000L));
    }

    public static String day(int unixSeconds) {
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(unixSeconds * 1000L));
    }

    public static String ago(int unixSeconds) {
        long diff = System.currentTimeMillis() / 1000L - unixSeconds;
        if (diff < 90) return "just now";
        if (diff < 3600) return (diff / 60) + " min ago";
        if (diff < 86400) return (diff / 3600) + " h ago";
        if (diff < 7 * 86400) return (diff / 86400) + " d ago";
        return day(unixSeconds);
    }
}

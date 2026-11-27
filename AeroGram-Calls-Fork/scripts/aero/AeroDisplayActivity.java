package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: "Text & display size" (reached from the side menu). Text size is live; display size applies after a short restart of the screen. */
public class AeroDisplayActivity extends BaseFragment {

    private LinearLayout root;
    private Context ctx;
    private int pendingDisplay;
    private TextView inBubble, outBubble, applyBtn;

    @Override
    public View createView(Context context) {
        ctx = context;
        pendingDisplay = AeroPrefs.displayScale();
        actionBar.setBackButtonImage(R.drawable.aero_ic_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Text & display size");
        actionBar.setBackgroundColor(AeroPrefs.accent());
        actionBar.setItemsColor(Color.WHITE, false);
        actionBar.setTitleColor(Color.WHITE);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scroll.setVerticalScrollBarEnabled(false);
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroll;
        build();
        return fragmentView;
    }

    private int textPrimary() { return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText); }
    private int textSecondary() { return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText); }

    private LinearLayout card(String label, String subtitle) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        c.setBackground(AeroUi.round(Theme.getColor(Theme.key_windowBackgroundWhite), 18));
        c.addView(AeroUi.caption(ctx, label, textSecondary()));
        if (subtitle != null) {
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.topMargin = dp(4);
            c.addView(AeroUi.text(ctx, subtitle, 13, textSecondary(), false), sl);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        root.addView(c, lp);
        return c;
    }

    private TextView bubble(String text, int bg, int fg, float sizeDp) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(AeroUi.round(bg, 14));
        return t;
    }

    private SeekBar slider(int max, int progress, int accent, SeekBar.OnSeekBarChangeListener l) {
        SeekBar bar = new SeekBar(ctx);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.getProgressDrawable().setTint(accent);
        bar.getThumb().setTint(accent);
        bar.setOnSeekBarChangeListener(l);
        return bar;
    }

    private TextView chip(String text, int accent) {
        TextView c = AeroUi.text(ctx, text, 12.5f, accent, true);
        c.setGravity(Gravity.CENTER);
        c.setMinWidth(dp(48));
        c.setBackground(AeroUi.round(AeroUi.alpha(accent, 26), 12));
        c.setPadding(dp(10), dp(4), dp(10), dp(4));
        return c;
    }

    private void build() {
        root.removeAllViews();
        final int accent = AeroPrefs.accent();

        // ---------- TEXT SIZE ----------
        LinearLayout tCard = card("TEXT SIZE", "Message text size. Changes apply to your chats right away.");
        FrameLayout preview = new FrameLayout(ctx);
        preview.setBackground(AeroPrefs.wallpaper(AeroPrefs.wall(), AeroPrefs.opacity()) != null
                ? AeroPrefs.wallpaper(AeroPrefs.wall(), AeroPrefs.opacity())
                : AeroUi.round(Theme.getColor(Theme.key_windowBackgroundGray), 14));
        inBubble = bubble("Hey, how are you?", Color.WHITE, 0xFF1B1B1B, AeroPrefs.textSize());
        outBubble = bubble("Doing great, you?", accent, Color.WHITE, AeroPrefs.textSize());
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.TOP);
        il.setMargins(dp(12), dp(12), 0, 0);
        FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.BOTTOM);
        ol.setMargins(0, 0, dp(12), dp(12));
        preview.addView(inBubble, il);
        preview.addView(outBubble, ol);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(132));
        pl.topMargin = dp(14);
        tCard.addView(preview, pl);

        LinearLayout tRow = new LinearLayout(ctx);
        tRow.setGravity(Gravity.CENTER_VERTICAL);
        tRow.addView(AeroUi.text(ctx, "A", 12, textSecondary(), true));
        final TextView tChip = chip(String.valueOf(AeroPrefs.textSize()), accent);
        SeekBar tBar = slider(AeroPrefs.TEXT_MAX - AeroPrefs.TEXT_MIN, AeroPrefs.textSize() - AeroPrefs.TEXT_MIN, accent,
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar s, int v, boolean user) {
                        int size = AeroPrefs.TEXT_MIN + v;
                        AeroPrefs.setTextSize(size);
                        tChip.setText(String.valueOf(size));
                        inBubble.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
                        outBubble.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
                    }
                    @Override public void onStartTrackingTouch(SeekBar s) { }
                    @Override public void onStopTrackingTouch(SeekBar s) { }
                });
        tRow.addView(tBar, new LinearLayout.LayoutParams(0, -2, 1f));
        tRow.addView(AeroUi.text(ctx, "A", 22, textSecondary(), true));
        LinearLayout.LayoutParams tcl = new LinearLayout.LayoutParams(-2, -2);
        tcl.leftMargin = dp(10);
        tRow.addView(tChip, tcl);
        LinearLayout.LayoutParams trl = new LinearLayout.LayoutParams(-1, -2);
        trl.topMargin = dp(10);
        tCard.addView(tRow, trl);

        // ---------- DISPLAY SIZE ----------
        LinearLayout dCard = card("DISPLAY SIZE", "Scales everything in the app: text, buttons and icons.");
        FrameLayout dPrev = new FrameLayout(ctx);
        dPrev.setBackground(AeroUi.round(Theme.getColor(Theme.key_windowBackgroundGray), 14));
        dPrev.setClipChildren(true);
        final LinearLayout mini = new LinearLayout(ctx);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setPadding(dp(14), dp(10), dp(14), dp(10));
        mini.setBackground(AeroUi.round(Theme.getColor(Theme.key_windowBackgroundWhite), 12));
        View av = new View(ctx);
        av.setBackground(AeroUi.round(accent, 22));
        mini.addView(av, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout lines = new LinearLayout(ctx);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.addView(AeroUi.text(ctx, "Alex", 16, textPrimary(), true));
        lines.addView(AeroUi.text(ctx, "See you tomorrow, okay?", 14, textSecondary(), false));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.leftMargin = dp(12);
        mini.addView(lines, ll);
        dPrev.addView(mini, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        LinearLayout.LayoutParams dpl = new LinearLayout.LayoutParams(-1, dp(120));
        dpl.topMargin = dp(14);
        dCard.addView(dPrev, dpl);
        mini.setScaleX(pendingDisplay / 100f);
        mini.setScaleY(pendingDisplay / 100f);

        LinearLayout dRow = new LinearLayout(ctx);
        dRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView dChip = chip(pendingDisplay + "%", accent);
        int steps = (AeroPrefs.DISPLAY_MAX - AeroPrefs.DISPLAY_MIN) / AeroPrefs.DISPLAY_STEP;
        SeekBar dBar = slider(steps, (pendingDisplay - AeroPrefs.DISPLAY_MIN) / AeroPrefs.DISPLAY_STEP, accent,
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar s, int v, boolean user) {
                        pendingDisplay = AeroPrefs.DISPLAY_MIN + v * AeroPrefs.DISPLAY_STEP;
                        dChip.setText(pendingDisplay + "%");
                        mini.animate().scaleX(pendingDisplay / 100f).scaleY(pendingDisplay / 100f).setDuration(120).start();
                        updateApply();
                    }
                    @Override public void onStartTrackingTouch(SeekBar s) { }
                    @Override public void onStopTrackingTouch(SeekBar s) { }
                });
        dRow.addView(AeroUi.text(ctx, "Smaller", 12, textSecondary(), true));
        LinearLayout.LayoutParams dbl = new LinearLayout.LayoutParams(0, -2, 1f);
        dbl.leftMargin = dp(8);
        dbl.rightMargin = dp(8);
        dRow.addView(dBar, dbl);
        dRow.addView(AeroUi.text(ctx, "Larger", 12, textSecondary(), true));
        LinearLayout.LayoutParams dcl = new LinearLayout.LayoutParams(-2, -2);
        dcl.leftMargin = dp(10);
        dRow.addView(dChip, dcl);
        LinearLayout.LayoutParams drl = new LinearLayout.LayoutParams(-1, -2);
        drl.topMargin = dp(10);
        dCard.addView(dRow, drl);

        applyBtn = AeroUi.text(ctx, "Apply  \u00B7  the screen restarts once", 14, Color.WHITE, true);
        applyBtn.setGravity(Gravity.CENTER);
        applyBtn.setBackground(AeroUi.ripple(AeroUi.round(accent, 14), 14));
        applyBtn.setOnClickListener(v -> {
            AeroPrefs.setDisplayScale(pendingDisplay);
            Activity a = getParentActivity();
            if (a != null) a.recreate();
        });
        LinearLayout.LayoutParams abl = new LinearLayout.LayoutParams(-1, dp(46));
        abl.topMargin = dp(14);
        dCard.addView(applyBtn, abl);
        updateApply();

        // ---------- RESET ----------
        TextView reset = AeroUi.text(ctx, "Reset to default", 14, accent, true);
        reset.setGravity(Gravity.CENTER);
        reset.setBackground(AeroUi.ripple(AeroUi.round(AeroUi.alpha(accent, 22), 14), 14));
        reset.setOnClickListener(v -> {
            AeroPrefs.setTextSize(AeroPrefs.TEXT_DEFAULT);
            boolean displayChanged = AeroPrefs.displayScale() != 100;
            pendingDisplay = 100;
            if (displayChanged) {
                AeroPrefs.setDisplayScale(100);
                Activity a = getParentActivity();
                if (a != null) { a.recreate(); return; }
            }
            build();
        });
        root.addView(reset, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    private void updateApply() {
        if (applyBtn == null) return;
        boolean changed = pendingDisplay != AeroPrefs.displayScale();
        applyBtn.setVisibility(changed ? View.VISIBLE : View.GONE);
    }
}

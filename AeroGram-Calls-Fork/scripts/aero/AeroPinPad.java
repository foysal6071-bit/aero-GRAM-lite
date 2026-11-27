package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: 6-digit PIN pad (title, dots, keypad). */
public class AeroPinPad extends LinearLayout {
    public static final int LENGTH = 6;

    public interface Listener {
        void onComplete(String pin);
    }

    private final StringBuilder pin = new StringBuilder();
    private final TextView title, subtitle;
    private final View dots;
    private final Listener listener;
    private boolean enabled = true;
    private int dotColorOverride = 0;

    public AeroPinPad(Context c, String titleText, Listener l) {
        super(c);
        listener = l;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        final int accent = AeroPrefs.accent();

        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(AeroUi.round(AeroUi.alpha(accent, 30), 32));
        android.widget.ImageView lock = new android.widget.ImageView(c);
        lock.setImageResource(org.telegram.messenger.R.drawable.aero_ic_lock);
        lock.setColorFilter(accent);
        badge.addView(lock, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        addView(badge, new LayoutParams(dp(64), dp(64)));

        title = AeroUi.text(c, titleText, 19, Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), true);
        LayoutParams tl = new LayoutParams(-2, -2);
        tl.topMargin = dp(18);
        addView(title, tl);

        subtitle = AeroUi.text(c, " ", 13, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), false);
        subtitle.setGravity(Gravity.CENTER);
        LayoutParams sl = new LayoutParams(-2, -2);
        sl.topMargin = dp(6);
        addView(subtitle, sl);

        dots = new View(c) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                float gap = dp(22), r = dp(6);
                float total = gap * (LENGTH - 1);
                float x0 = (getWidth() - total) / 2f, cy = getHeight() / 2f;
                for (int i = 0; i < LENGTH; i++) {
                    boolean filled = i < pin.length();
                    p.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
                    p.setStrokeWidth(dp(1.6f));
                    p.setColor(dotColorOverride != 0 ? dotColorOverride : (filled ? accent : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)));
                    canvas.drawCircle(x0 + gap * i, cy, r, p);
                }
            }
        };
        LayoutParams dl = new LayoutParams(dp(180), dp(24));
        dl.topMargin = dp(26);
        addView(dots, dl);

        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"", "0", "⌫"}};
        for (String[] row : rows) {
            LinearLayout r = new LinearLayout(c);
            for (String k : row) {
                View b;
                if (k.equals("⌫")) {
                    android.widget.ImageView ic = new android.widget.ImageView(c);
                    ic.setImageResource(org.telegram.messenger.R.drawable.aero_backspace);
                    ic.setScaleType(android.widget.ImageView.ScaleType.CENTER);
                    ic.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    ic.setContentDescription("Delete");
                    b = ic;
                } else {
                    TextView t = AeroUi.text(c, k, 26, Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false);
                    t.setGravity(Gravity.CENTER);
                    b = t;
                }
                if (!k.isEmpty()) {
                    b.setBackground(AeroUi.ripple(AeroUi.round(AeroUi.alpha(accent, 14), 36), 36));
                    b.setOnClickListener(v -> press(k));
                    if (k.equals("⌫")) b.setOnLongClickListener(v -> { clear(); return true; });
                }
                LayoutParams bl = new LayoutParams(dp(72), dp(72));
                bl.setMargins(dp(10), dp(6), dp(10), dp(6));
                r.addView(b, bl);
            }
            LayoutParams rl = new LayoutParams(-2, -2);
            addView(r, rl);
        }
    }

    private void press(String k) {
        if (!enabled) return;
        if (k.equals("⌫")) {
            if (pin.length() > 0) pin.setLength(pin.length() - 1);
        } else if (pin.length() < LENGTH) {
            pin.append(k);
        }
        dots.invalidate();
        if (pin.length() == LENGTH) {
            final String value = pin.toString();
            AndroidUtilities.runOnUIThread(() -> listener.onComplete(value), 90);
        }
    }

    public void setSubtitle(String s, boolean error) {
        subtitle.setText(s.isEmpty() ? " " : s);
        subtitle.setTextColor(error ? 0xFFD94A6B : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
    }

    public void setInputEnabled(boolean e) {
        enabled = e;
    }

    public void clear() {
        pin.setLength(0);
        dots.invalidate();
    }

    /** wrong entry: shake the dots, then clear */
    public void fail(String message) {
        setSubtitle(message, true);
        AndroidUtilities.shakeView(dots);
        AndroidUtilities.runOnUIThread(this::clear, 220);
    }
}

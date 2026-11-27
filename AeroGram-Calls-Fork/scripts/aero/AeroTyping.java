package org.telegram.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import java.util.WeakHashMap;

import static org.telegram.messenger.AndroidUtilities.dp;

/**
 * AeroGram: modern "typing..." dots. Three dots jump one after another with a springy
 * jump + landing squash, a soft glow at the peak and a gentle colour/size pulse.
 * Used by TypingDotsDrawable (chat header + chat list).
 */
public class AeroTyping {
    private static final WeakHashMap<Drawable, Integer> COLORS = new WeakHashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final long PERIOD = 1300L;   // one full cycle
    private static final long STAGGER = 170L;   // delay between dots
    private static final float JUMP = 0.62f;    // share of the cycle used by the jump, the rest is rest

    /** remember the text colour each drawable was given, so the dots match the status text */
    public static void setColor(Drawable d, int color) {
        COLORS.put(d, color);
    }

    private static float smooth(float x) {
        return x * x * (3f - 2f * x);
    }

    /** @return true when the dots were drawn (the stock drawing is then skipped) */
    public static boolean draw(Drawable d, Canvas canvas) {
        try {
            Integer c = COLORS.get(d);
            int color = c != null ? c : AeroPrefs.accent();
            int baseAlpha = (color >>> 24) & 0xFF;
            if (baseAlpha == 0) baseAlpha = 255;
            int rgb = color & 0x00FFFFFF;
            long now = SystemClock.uptimeMillis();
            float cy = d.getBounds().top + dp(10);
            for (int i = 0; i < 3; i++) {
                long t = (now - i * STAGGER) % PERIOD;
                if (t < 0) t += PERIOD;
                float x = t / (float) PERIOD;

                float lift = 0f;      // 0..1 height of the jump
                float squash = 0f;    // 0..1 landing squash
                if (x < JUMP) {
                    float u = x / JUMP;                              // 0..1 inside the jump
                    // fast take-off, floaty top, quick drop
                    lift = (float) Math.sin(Math.PI * Math.pow(u, 0.85));
                    lift = smooth(Math.max(0f, Math.min(1f, lift)));
                    if (u > 0.88f) squash = (u - 0.88f) / 0.12f;     // about to land: stretch
                } else {
                    float u = (x - JUMP) / (1f - JUMP);              // 0..1 inside the rest
                    // small rebound right after landing, then calm
                    float damp = (float) Math.exp(-5f * u);
                    lift = 0.14f * damp * Math.abs((float) Math.sin(Math.PI * u * 2.2f));
                    if (u < 0.22f) squash = 1f - u / 0.22f;          // landing squash fades out
                }

                float cx = dp(4f) + dp(6f) * i;
                float y = cy - dp(4f) * lift;
                float r = dp(1.7f) + dp(0.8f) * lift;
                float rx = r * (1f + 0.28f * squash * (1f - lift));  // wider when it hits the ground
                float ry = r * (1f - 0.22f * squash * (1f - lift));

                // soft glow at the top of the jump
                if (lift > 0.15f) {
                    int ga = Math.round(baseAlpha * 0.22f * lift);
                    PAINT.setColor(rgb | (ga << 24));
                    canvas.drawCircle(cx, y, r * 2.1f, PAINT);
                }
                int a = Math.round(baseAlpha * (0.45f + 0.55f * Math.min(1f, lift * 1.15f + 0.12f)));
                PAINT.setColor(rgb | (a << 24));
                canvas.drawOval(cx - rx, y - ry, cx + rx, y + ry, PAINT);
            }
            d.invalidateSelf();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}

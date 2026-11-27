package org.telegram.ui;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MessageObject;

import static org.telegram.messenger.AndroidUtilities.dp;

/**
 * AeroGram: animated upload progress for photo / video / file / voice bubbles.
 * - slim bar at the bottom of the bubble: gradient fill + moving shimmer + glowing knob
 * - pill at the bottom-left: ring + live percent ("43%"), pops in with an overshoot, ends with a green tick
 * - progress is eased every frame, so the number and the bar glide instead of jumping
 * One instance per ChatMessageCell. Drawn from ChatMessageCell.drawStatusDrawable (see patch_telegram.py).
 */
public class AeroUploadProgress {
    private static final long NONE = Long.MIN_VALUE;

    private long key = NONE;
    private float shown;
    private float appear;
    private long startedAt;
    private long lastTime;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF arc = new RectF();
    private final Path tick = new Path();
    private final Matrix matrix = new Matrix();
    private final OvershootInterpolator overshoot = new OvershootInterpolator(1.7f);

    private Shader fillShader;
    private float shaderL = -1, shaderR = -1;
    private int shaderColor;
    private Shader shimmerShader;
    private float percentWidth = -1;

    public AeroUploadProgress() {
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setColor(0xFFFFFFFF);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    private void reset() {
        key = NONE;
        shown = 0f;
        appear = 0f;
        lastTime = 0;
    }

    private static int withAlpha(int color, float a) {
        int al = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (color & 0x00FFFFFF) | (al << 24);
    }

    private static int lighten(int color, float f) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        r += (255 - r) * f;
        g += (255 - g) * f;
        b += (255 - b) * f;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * @param bubbleLeft  left edge of the bubble (cell coordinates)
     * @param bubbleRight right edge of the bubble
     * @param bottom      bottom edge of the bubble
     */
    public void draw(Canvas canvas, View host, MessageObject mo, float bubbleLeft, float bubbleRight, float bottom) {
        if (mo == null || mo.messageOwner == null) {
            if (key != NONE) reset();
            return;
        }
        String path = mo.messageOwner.attachPath;
        boolean sending = path != null && path.length() > 0 && mo.isSending();
        if (!sending) {
            if (key != NONE) reset();
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (key != mo.getId()) {
            reset();
            key = mo.getId();
            startedAt = now;
        }

        Float p = ImageLoader.getInstance().getFileProgress(path);
        boolean known = p != null;
        float target = known ? Math.max(0f, Math.min(1f, p)) : 0f;

        long dt = lastTime == 0 ? 16 : Math.min(50, now - lastTime);
        lastTime = now;
        // ease towards the real value (frame-rate independent)
        shown += (target - shown) * (1f - (float) Math.exp(-dt / 110f));
        if (target >= 1f && shown > 0.994f) shown = 1f;

        // avoid a flash for tiny uploads that finish immediately
        boolean visible = known || now - startedAt > 450;
        if (!visible) {
            host.invalidate();
            return;
        }
        appear = Math.min(1f, appear + dt / 320f);
        float pop = Math.max(0f, overshoot.getInterpolation(appear));
        float fade = Math.min(1f, appear * 2.2f);

        float barL = bubbleLeft + dp(10);
        float barR = bubbleRight - dp(10);
        float barW = barR - barL;
        if (barW < dp(48)) {
            host.invalidate();
            return;
        }
        float barH = dp(3.5f);
        float barB = bottom - dp(6);
        float barT = barB - barH;
        float cy = (barT + barB) / 2f;
        int accent = AeroPrefs.accent();

        // ---- track
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(withAlpha(0x2E000000, fade));
        rect.set(barL, barT, barR, barB);
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint);

        // ---- fill (or indeterminate sweep while the file is being prepared)
        if (known) {
            float w = Math.max(barH, barW * shown);
            float fillR = barL + w;
            if (fillShader == null || shaderL != barL || shaderR != barR || shaderColor != accent) {
                fillShader = new LinearGradient(barL, 0, barR, 0, accent, lighten(accent, 0.45f), Shader.TileMode.CLAMP);
                shaderL = barL;
                shaderR = barR;
                shaderColor = accent;
            }
            paint.setShader(fillShader);
            paint.setAlpha(Math.round(255 * fade));
            rect.set(barL, barT, fillR, barB);
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint);

            // moving shimmer
            if (shimmerShader == null) {
                shimmerShader = new LinearGradient(0, 0, dp(46), 0,
                        new int[]{0x00FFFFFF, 0xB3FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            }
            float phase = (now % 1100L) / 1100f;
            matrix.setTranslate(barL - dp(46) + (w + dp(46)) * phase, 0);
            shimmerShader.setLocalMatrix(matrix);
            paint.setShader(shimmerShader);
            paint.setAlpha(Math.round(255 * fade));
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint);
            paint.setShader(null);

            // glowing knob at the leading edge
            if (shown < 0.999f) {
                float kr = dp(4.2f) * pop;
                paint.setColor(withAlpha(accent, 0.28f * fade));
                canvas.drawCircle(fillR, cy, kr + dp(2.2f), paint);
                paint.setColor(withAlpha(0xFFFFFFFF, fade));
                canvas.drawCircle(fillR, cy, kr, paint);
            }
        } else {
            float seg = Math.min(dp(70), barW * 0.35f);
            float ph = (now % 1200L) / 1200f;
            float x = barL - seg + (barW + seg) * ph;
            canvas.save();
            canvas.clipRect(barL, barT - dp(1), barR, barB + dp(1));
            paint.setColor(withAlpha(accent, fade));
            rect.set(x, barT, x + seg, barB);
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint);
            canvas.restore();
        }

        // ---- percent pill (bottom-left, above the bar)
        textPaint.setTextSize(dp(11.5f));
        if (percentWidth < 0) percentWidth = textPaint.measureText("100%");
        boolean done = known && shown >= 1f;
        String label = known ? Math.round(shown * 100f) + "%" : "Preparing";
        float textW = known ? percentWidth : textPaint.measureText(label);
        float ringR = dp(7);
        float padX = dp(8);
        float gap = dp(6);
        float pillW = padX + ringR * 2 + gap + textW + padX;
        float pillH = dp(24);
        float pillL = barL;
        float pillB = barT - dp(6);
        float pillT = pillB - pillH;

        canvas.save();
        canvas.scale(pop, pop, pillL, pillB);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0x99000000, fade));
        rect.set(pillL, pillT, pillL + pillW, pillB);
        canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, paint);

        float rcx = pillL + padX + ringR;
        float rcy = (pillT + pillB) / 2f;
        if (done) {
            paint.setColor(withAlpha(0xFF3DDC84, fade));
            canvas.drawCircle(rcx, rcy, ringR, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.6f));
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(withAlpha(0xFFFFFFFF, fade));
            tick.reset();
            tick.moveTo(rcx - ringR * 0.42f, rcy + ringR * 0.02f);
            tick.lineTo(rcx - ringR * 0.1f, rcy + ringR * 0.36f);
            tick.lineTo(rcx + ringR * 0.46f, rcy - ringR * 0.3f);
            canvas.drawPath(tick, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f));
            paint.setColor(withAlpha(0x55FFFFFF, fade));
            canvas.drawCircle(rcx, rcy, ringR - dp(1), paint);
            paint.setColor(withAlpha(0xFFFFFFFF, fade));
            arc.set(rcx - ringR + dp(1), rcy - ringR + dp(1), rcx + ringR - dp(1), rcy + ringR - dp(1));
            if (known) {
                canvas.drawArc(arc, -90f, 360f * shown, false, paint);
            } else {
                canvas.drawArc(arc, (now % 900L) / 900f * 360f, 100f, false, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        textPaint.setAlpha(Math.round(255 * fade));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(label, rcx + ringR + gap, rcy - (fm.ascent + fm.descent) / 2f, textPaint);
        canvas.restore();

        host.invalidate(); // keep animating while the upload runs
    }
}

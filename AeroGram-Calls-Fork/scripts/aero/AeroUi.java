package org.telegram.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: small shared helpers so the Aero screens look consistent. */
public class AeroUi {

    public static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    public static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public static GradientDrawable roundStroke(int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = round(fill, radiusDp);
        g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }

    /** content + touch ripple, clipped to the same corner radius */
    public static Drawable ripple(Drawable content, float radiusDp) {
        GradientDrawable mask = round(0xFFFFFFFF, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(0x1F000000), content, mask);
    }

    public static TextView caption(Context c, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setLetterSpacing(0.09f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    public static TextView text(Context c, String text, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** round badge with a tick, used to mark the selected option */
    public static View check(Context c, final int fill) {
        return new View(c) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();

            @Override
            protected void onDraw(Canvas canvas) {
                float w = getWidth(), h = getHeight();
                p.setStyle(Paint.Style.FILL);
                p.setColor(fill);
                canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(0xFFFFFFFF);
                p.setStrokeWidth(w * 0.11f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                path.reset();
                path.moveTo(w * 0.28f, h * 0.52f);
                path.lineTo(w * 0.44f, h * 0.67f);
                path.lineTo(w * 0.73f, h * 0.35f);
                canvas.drawPath(path, p);
            }
        };
    }
}

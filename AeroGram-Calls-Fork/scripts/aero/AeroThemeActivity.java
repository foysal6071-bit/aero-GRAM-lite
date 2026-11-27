package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.io.FileOutputStream;
import java.io.InputStream;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: Theme screen (Light / Dark / Auto, accent colour, wallpaper, wallpaper opacity). */
public class AeroThemeActivity extends BaseFragment {

    private static final int PICK_WALLPAPER = 9101;
    private LinearLayout root;
    private Context ctx;

    @Override
    public View createView(Context context) {
        ctx = context;
        actionBar.setBackButtonImage(R.drawable.aero_ic_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Theme");
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
    private int hairline() { return Theme.getColor(Theme.key_divider); }

    private LinearLayout card(String label, String subtitle) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        c.setBackground(AeroUi.round(Theme.getColor(Theme.key_windowBackgroundWhite), 18));
        c.addView(AeroUi.caption(ctx, label, textSecondary()));
        if (subtitle != null) {
            TextView s = AeroUi.text(ctx, subtitle, 13, textSecondary(), false);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.topMargin = dp(4);
            c.addView(s, sl);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        root.addView(c, lp);
        return c;
    }

    /** miniature chat screen used as the preview of a theme mode */
    private View modePreview(final int mode) {
        final int accent = AeroPrefs.accent();
        return new View(ctx) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path clip = new Path();

            private void draw(Canvas c, float l, float w, float h, boolean dark) {
                c.save();
                c.clipRect(l, 0, l + w, h);
                p.setColor(dark ? 0xFF161C24 : 0xFFF2F4F8);
                c.drawRect(l, 0, l + w, h, p);
                p.setColor(accent);
                c.drawRect(l, 0, l + w, h * 0.2f, p);
                p.setColor(dark ? 0xFF2A3440 : 0xFFFFFFFF);
                c.drawRoundRect(new RectF(l + w * 0.1f, h * 0.34f, l + w * 0.62f, h * 0.5f), dp(4), dp(4), p);
                p.setColor(dark ? 0xFF3B6FB8 : accent);
                p.setAlpha(dark ? 255 : 210);
                c.drawRoundRect(new RectF(l + w * 0.38f, h * 0.58f, l + w * 0.9f, h * 0.74f), dp(4), dp(4), p);
                p.setAlpha(255);
                c.restore();
            }

            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight();
                clip.reset();
                clip.addRoundRect(new RectF(0, 0, w, h), dp(10), dp(10), Path.Direction.CW);
                c.clipPath(clip);
                if (mode == 2) {
                    draw(c, 0, w / 2f, h, false);
                    draw(c, w / 2f, w / 2f, h, true);
                } else {
                    draw(c, 0, w, h, mode == 1);
                }
            }
        };
    }

    private void build() {
        root.removeAllViews();
        final int accent = AeroPrefs.accent();

        // ---------- APPEARANCE ----------
        LinearLayout themeCard = card("APPEARANCE", "Light, dark, or follow your phone.");
        LinearLayout modes = new LinearLayout(ctx);
        String[] names = {"Light", "Dark", "Auto"};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            final boolean sel = AeroPrefs.mode() == i;
            LinearLayout tile = new LinearLayout(ctx);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(8), dp(8), dp(8), dp(10));
            tile.setBackground(AeroUi.ripple(sel
                    ? AeroUi.roundStroke(AeroUi.alpha(accent, 20), 14, accent, 2)
                    : AeroUi.roundStroke(Color.TRANSPARENT, 14, hairline(), 1), 14));
            FrameLayout pw = new FrameLayout(ctx);
            pw.addView(modePreview(i), new FrameLayout.LayoutParams(-1, -1));
            if (sel) {
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.RIGHT | Gravity.TOP);
                cl.setMargins(0, dp(4), dp(4), 0);
                pw.addView(AeroUi.check(ctx, accent), cl);
            }
            tile.addView(pw, new LinearLayout.LayoutParams(-1, dp(66)));
            TextView tv = AeroUi.text(ctx, names[i], 13, sel ? accent : textPrimary(), sel);
            LinearLayout.LayoutParams tvl = new LinearLayout.LayoutParams(-2, -2);
            tvl.topMargin = dp(8);
            tile.addView(tv, tvl);
            tile.setOnClickListener(v -> {
                AeroPrefs.applyMode(mode);
                if (fragmentView != null && root != null) build();
            });
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) ml.leftMargin = dp(10);
            modes.addView(tile, ml);
        }
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(14);
        themeCard.addView(modes, mlp);

        // divider + accent
        View div = new View(ctx);
        div.setBackgroundColor(hairline());
        LinearLayout.LayoutParams dvl = new LinearLayout.LayoutParams(-1, 1);
        dvl.topMargin = dp(18);
        themeCard.addView(div, dvl);

        TextView at = AeroUi.text(ctx, "Accent colour", 14, textPrimary(), true);
        LinearLayout.LayoutParams atl = new LinearLayout.LayoutParams(-2, -2);
        atl.topMargin = dp(14);
        themeCard.addView(at, atl);

        LinearLayout dots = new LinearLayout(ctx);
        for (int i = 0; i < AeroPrefs.ACCENTS.length; i++) {
            final int idx = i;
            final boolean sel = AeroPrefs.accentIndex() == i;
            FrameLayout dot = new FrameLayout(ctx);
            dot.setBackground(AeroUi.ripple(AeroUi.round(AeroPrefs.ACCENTS[i], 20), 20));
            if (sel) {
                dot.addView(AeroUi.check(ctx, AeroUi.alpha(0xFF000000, 0x33)), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            }
            dot.setOnClickListener(v -> {
                AeroPrefs.setAccent(idx);
                actionBar.setBackgroundColor(AeroPrefs.accent());
                build();
            });
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(40), dp(40));
            if (i > 0) dl.leftMargin = dp(12);
            dots.addView(dot, dl);
        }
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(-2, -2);
        dotsLp.topMargin = dp(12);
        themeCard.addView(dots, dotsLp);

        // ---------- WALLPAPER ----------
        LinearLayout wallCard = card("WALLPAPER", "Chat background.");
        LinearLayout walls = new LinearLayout(ctx);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            final boolean sel = AeroPrefs.wall() == i;
            FrameLayout tile = new FrameLayout(ctx);
            if (i < AeroPrefs.WALLS.length) {
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, AeroPrefs.WALLS[i]);
                g.setCornerRadius(dp(12));
                tile.setBackground(g);
                tile.setForeground(AeroUi.ripple(sel ? AeroUi.roundStroke(Color.TRANSPARENT, 12, accent, 2.5f) : AeroUi.round(Color.TRANSPARENT, 12), 12));
                if (sel) {
                    FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.RIGHT | Gravity.TOP);
                    cl.setMargins(0, dp(6), dp(6), 0);
                    tile.addView(AeroUi.check(ctx, accent), cl);
                }
                tile.setOnClickListener(v -> { AeroPrefs.setWall(idx); build(); });
            } else {
                boolean custom = AeroPrefs.wall() == AeroPrefs.WALL_CUSTOM;
                tile.setBackground(AeroUi.ripple(AeroUi.roundStroke(custom ? AeroUi.alpha(accent, 20) : Theme.getColor(Theme.key_windowBackgroundGray), 12, custom ? accent : hairline(), custom ? 2.5f : 1), 12));
                TextView plus = AeroUi.text(ctx, "+", 28, custom ? accent : textSecondary(), false);
                plus.setGravity(Gravity.CENTER);
                tile.addView(plus, new FrameLayout.LayoutParams(-1, -1));
                tile.setOnClickListener(v -> pickCustomWallpaper());
            }
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, dp(92), 1f);
            if (i > 0) wl.leftMargin = dp(10);
            walls.addView(tile, wl);
        }
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = dp(14);
        wallCard.addView(walls, wlp);

        // picture wallpapers (nothing is applied until the user taps one)
        LinearLayout pics = new LinearLayout(ctx);
        for (int k = 0; k < 2; k++) {
            final int wallId = k == 0 ? AeroPrefs.WALL_LAVENDER : AeroPrefs.WALL_PINK;
            final boolean psel = AeroPrefs.wallChosen() && AeroPrefs.wall() == wallId;
            FrameLayout ptile = new FrameLayout(ctx);
            android.widget.ImageView iv = new android.widget.ImageView(ctx);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            android.graphics.BitmapFactory.Options po = new android.graphics.BitmapFactory.Options();
            po.inSampleSize = 4;
            iv.setImageBitmap(android.graphics.BitmapFactory.decodeResource(ctx.getResources(), AeroPrefs.pictureRes(wallId), po));
            ptile.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            ptile.setClipToOutline(true);
            ptile.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            ptile.setForeground(AeroUi.ripple(psel ? AeroUi.roundStroke(Color.TRANSPARENT, 12, accent, 2.5f) : AeroUi.round(Color.TRANSPARENT, 12), 12));
            if (psel) {
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.RIGHT | Gravity.TOP);
                cl.setMargins(0, dp(6), dp(6), 0);
                ptile.addView(AeroUi.check(ctx, accent), cl);
            }
            ptile.setOnClickListener(v -> { AeroPrefs.setWall(wallId); AeroPrefs.setOpacity(100); build(); });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, dp(170), 1f);
            if (k > 0) pl.leftMargin = dp(10);
            pics.addView(ptile, pl);
        }
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(10);
        wallCard.addView(pics, plp);

        // ---------- OPACITY ----------
        LinearLayout opCard = card("WALLPAPER OPACITY", "Drag the slider to preview your chat.");
        final FrameLayout preview = new FrameLayout(ctx);
        preview.setBackground(AeroPrefs.wallpaper(AeroPrefs.wall(), AeroPrefs.opacity()));
        preview.setClipToOutline(true);
        preview.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
            }
        });
        TextView in = bubble("Hey, how are you?", Color.WHITE, 0xFF1B1B1B);
        TextView out = bubble("Doing great, you?", accent, Color.WHITE);
        FrameLayout.LayoutParams inl = new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.TOP);
        inl.setMargins(dp(12), dp(14), 0, 0);
        FrameLayout.LayoutParams outl = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.BOTTOM);
        outl.setMargins(0, 0, dp(12), dp(14));
        preview.addView(in, inl);
        preview.addView(out, outl);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(128));
        pl.topMargin = dp(14);
        opCard.addView(preview, pl);

        LinearLayout sliderRow = new LinearLayout(ctx);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView pct = AeroUi.text(ctx, AeroPrefs.opacity() + "%", 12.5f, accent, true);
        pct.setGravity(Gravity.CENTER);
        pct.setBackground(AeroUi.round(AeroUi.alpha(accent, 26), 12));
        pct.setPadding(dp(10), dp(4), dp(10), dp(4));
        SeekBar bar = new SeekBar(ctx);
        bar.setMax(100);
        bar.setProgress(AeroPrefs.opacity());
        bar.getProgressDrawable().setTint(accent);
        bar.getThumb().setTint(accent);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                AeroPrefs.setOpacity(v);
                pct.setText(v + "%");
                preview.setBackground(AeroPrefs.wallpaper(AeroPrefs.wall(), v));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        sliderRow.addView(bar, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams pcl = new LinearLayout.LayoutParams(-2, -2);
        pcl.leftMargin = dp(8);
        sliderRow.addView(pct, pcl);
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-1, -2);
        srl.topMargin = dp(10);
        opCard.addView(sliderRow, srl);
    }

    private TextView bubble(String text, int bg, int fg) {
        TextView t = AeroUi.text(ctx, text, 13, fg, false);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(AeroUi.round(bg, 16));
        return t;
    }

    private void pickCustomWallpaper() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(i, PICK_WALLPAPER);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_WALLPAPER || resultCode != Activity.RESULT_OK || data == null || data.getData() == null || getParentActivity() == null) return;
        try (InputStream is = getParentActivity().getContentResolver().openInputStream(data.getData())) {
            android.graphics.Bitmap full = android.graphics.BitmapFactory.decodeStream(is);
            if (full == null) return;
            float scale = Math.min(1f, 1600f / Math.max(full.getWidth(), full.getHeight()));
            android.graphics.Bitmap b = scale < 1f ? android.graphics.Bitmap.createScaledBitmap(full, Math.round(full.getWidth() * scale), Math.round(full.getHeight() * scale), true) : full;
            try (FileOutputStream os = new FileOutputStream(AeroPrefs.customFile())) {
                b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, os);
            }
            AeroPrefs.setWall(AeroPrefs.WALL_CUSTOM);
            build();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}

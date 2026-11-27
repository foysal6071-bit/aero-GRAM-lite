package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;

/** AeroGram: own appearance settings (theme mode, accent, wallpaper, wallpaper opacity). */
public class AeroPrefs {
    public static final int[] ACCENTS = {0xFF1F6FE5, 0xFF2BA970, 0xFF8A5CD6, 0xFFE8903A, 0xFFD94A6B};
    public static final int[][] WALLS = {
            {0xFFB3C8F5, 0xFFE6D9F2},
            {0xFFA8E0C0, 0xFFE3F2B8},
            {0xFFF6BE9A, 0xFFF2A0A8}
    };
    public static final int WALL_CUSTOM = 3;
    /** bundled picture wallpapers (only used once the user picks them) */
    public static final int WALL_LAVENDER = 4, WALL_PINK = 5;

    public static int pictureRes(int wall) {
        if (wall == WALL_LAVENDER) return org.telegram.messenger.R.drawable.aero_wall_lavender;
        if (wall == WALL_PINK) return org.telegram.messenger.R.drawable.aero_wall_pink;
        return 0;
    }

    private static SharedPreferences p() {
        return MessagesController.getGlobalMainSettings();
    }

    public static int mode() { return p().getInt("aero_mode", 2); }          // 0 light, 1 dark, 2 auto
    public static int accentIndex() { return Math.min(Math.max(p().getInt("aero_accent", 0), 0), ACCENTS.length - 1); }
    public static int accent() { return ACCENTS[accentIndex()]; }
    public static int wall() { return p().getInt("aero_wall", 0); }
    public static int opacity() { return p().getInt("aero_wall_opacity", 62); }
    public static boolean wallChosen() { return p().contains("aero_wall"); }

    public static void setAccent(int i) { p().edit().putInt("aero_accent", i).apply(); }
    public static void setWall(int i) { p().edit().putInt("aero_wall", i).apply(); }
    public static void setOpacity(int v) { p().edit().putInt("aero_wall_opacity", v).apply(); }

    // ---------- text size / display size ----------
    public static final int TEXT_MIN = 12, TEXT_MAX = 30, TEXT_DEFAULT = 16;
    public static final int DISPLAY_MIN = 80, DISPLAY_MAX = 130, DISPLAY_STEP = 5;

    /** chat message text size (same value Telegram itself uses: SharedConfig.fontSize) */
    public static int textSize() { return SharedConfig.fontSize; }

    public static void setTextSize(int v) {
        v = Math.max(TEXT_MIN, Math.min(TEXT_MAX, v));
        SharedConfig.fontSize = v;
        p().edit().putInt("fons_size", v).commit();
        try {
            Theme.chat_msgTextPaint.setTextSize(org.telegram.messenger.AndroidUtilities.dp(v));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** whole-app display size in percent (80..130), applied on the next activity start */
    public static int displayScale() {
        try {
            return Math.max(DISPLAY_MIN, Math.min(DISPLAY_MAX, p().getInt("aero_display", 100)));
        } catch (Throwable e) {
            return 100;
        }
    }

    public static void setDisplayScale(int percent) {
        p().edit().putInt("aero_display", Math.max(DISPLAY_MIN, Math.min(DISPLAY_MAX, percent))).commit();
    }

    /** Called from LaunchActivity.attachBaseContext: scales density, so every dp / sp in the app grows or shrinks together. */
    public static Context scaleContext(Context base) {
        try {
            int pct = displayScale();
            if (pct == 100) return base;
            Configuration c = new Configuration(base.getResources().getConfiguration());
            c.densityDpi = Math.max(120, Math.round(c.densityDpi * pct / 100f));
            return base.createConfigurationContext(c);
        } catch (Throwable e) {
            return base;
        }
    }

    /** AeroGram speaks English: switch the app language to English once (the user can still pick another one later). */
    public static void ensureEnglish() {
        try {
            if (p().getBoolean("aero_lang_done", false)) return;
            p().edit().putBoolean("aero_lang_done", true).commit();
            LocaleController lc = LocaleController.getInstance();
            LocaleController.LocaleInfo en = lc.getLanguageFromDict("en");
            if (en != null && lc.getCurrentLocaleInfo() != en) {
                lc.applyLanguage(en, true, false, UserConfig.selectedAccount);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static String modeName() {
        int m = mode();
        return m == 0 ? "Light" : m == 1 ? "Dark" : "Auto";
    }

    public static File customFile() {
        return new File(ApplicationLoader.getFilesDirFixed(), "aero_wallpaper.jpg");
    }

    /** Applies Light / Dark / Auto through the real Telegram night-mode machinery. */
    public static void applyMode(int m) {
        p().edit().putInt("aero_mode", m).apply();
        try {
            if (m == 2) {
                Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_SYSTEM;
                Theme.saveAutoNightThemeConfig();
                Theme.checkAutoNightThemeConditions(true);
            } else {
                Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_NONE;
                Theme.saveAutoNightThemeConfig();
                Theme.ThemeInfo t = Theme.getTheme(m == 1 ? "Dark Blue" : "Blue");
                if (t != null) {
                    Theme.applyTheme(t, m == 1);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Wallpaper drawable at the given opacity (0..100) drawn over the plain theme background. */
    public static Drawable wallpaper(int wall, int opacity) {
        Drawable top = null;
        if (wall >= 0 && wall < WALLS.length) {
            top = new GradientDrawable(GradientDrawable.Orientation.TL_BR, WALLS[wall]);
        } else if (pictureRes(wall) != 0) {
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap b = android.graphics.BitmapFactory.decodeResource(ApplicationLoader.applicationContext.getResources(), pictureRes(wall), o);
                if (b != null) top = new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), b);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        } else if (wall == WALL_CUSTOM && customFile().exists()) {
            try {
                Bitmap b = android.graphics.BitmapFactory.decodeFile(customFile().getAbsolutePath());
                if (b != null) top = new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), b);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (top == null) return null;
        top.setAlpha(Math.round(255f * opacity / 100f));
        return new LayerDrawable(new Drawable[]{new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray)), top});
    }

    /** Used by ChatActivity: our wallpaper if the user picked one, otherwise the default. */
    public static Drawable overrideWallpaper(Drawable def) {
        if (!wallChosen()) return def;
        // called on every frame: hand back the same object until a setting changes
        String key = wall() + ":" + opacity() + ":" + Theme.getColor(Theme.key_windowBackgroundGray) + ":" + customFile().lastModified();
        if (!key.equals(cachedKey)) {
            cachedKey = key;
            cached = wallpaper(wall(), opacity());
        }
        return cached != null ? cached : def;
    }

    private static String cachedKey;
    private static Drawable cached;

    /** Link colour that follows the Aero accent, nudged only as far as needed to stay readable on the bubble. */
    public static int linkColor(int background) {
        final int color = accent() | 0xFF000000;
        final int bg = background | 0xFF000000;
        try {
            if (androidx.core.graphics.ColorUtils.calculateContrast(color, bg) >= 3.0) return color;
            final int target = androidx.core.graphics.ColorUtils.calculateLuminance(bg) > 0.5 ? 0xFF000000 : 0xFFFFFFFF;
            for (int i = 1; i <= 10; i++) {
                int c = androidx.core.graphics.ColorUtils.blendARGB(color, target, i / 10f);
                if (androidx.core.graphics.ColorUtils.calculateContrast(c, bg) >= 3.0) return c;
            }
            return target;
        } catch (Throwable e) {
            return color;
        }
    }

    /** Called from Theme.getColor for link keys. Returns 0 when the key is not ours. */
    public static int linkOverride(int key) {
        if (key == Theme.key_chat_messageLinkIn) return linkColor(Theme.getColor(Theme.key_chat_inBubble));
        if (key == Theme.key_chat_messageLinkOut) return linkColor(Theme.getColor(Theme.key_chat_outBubble));
        if (key == Theme.key_windowBackgroundWhiteLinkText) return linkColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        if (key == Theme.key_dialogTextLink) return linkColor(Theme.getColor(Theme.key_dialogBackground));
        return 0;
    }
}

package org.telegram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.Theme;

import java.security.MessageDigest;
import java.security.SecureRandom;

/** AeroGram: 6-digit app passcode (storage + lock screen shown when the app opens / returns from background). */
public class AeroPasscode {
    private static final long GRACE_MS = 10_000;
    private static final int MAX_FAILS = 5;
    private static final long LOCKOUT_MS = 30_000;

    private static boolean unlockedOnce = false;
    private static boolean showing = false;
    private static long pausedAt = 0;
    private static int fails = 0;
    private static long lockedUntil = 0;

    private static SharedPreferences p() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean enabled() {
        return p().getString("aero_pc_hash", "").length() > 0;
    }

    private static String hash(String salt, String pin) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + pin).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void set(String pin) {
        byte[] s = new byte[16];
        new SecureRandom().nextBytes(s);
        StringBuilder sb = new StringBuilder();
        for (byte b : s) sb.append(String.format("%02x", b));
        String salt = sb.toString();
        p().edit().putString("aero_pc_salt", salt).putString("aero_pc_hash", hash(salt, pin)).apply();
        unlockedOnce = true;
    }

    public static boolean check(String pin) {
        String salt = p().getString("aero_pc_salt", "");
        return hash(salt, pin).equals(p().getString("aero_pc_hash", ""));
    }

    public static void clear() {
        p().edit().remove("aero_pc_hash").remove("aero_pc_salt").apply();
    }

    public static long lockoutLeftSeconds() {
        long left = lockedUntil - System.currentTimeMillis();
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    /** returns true when the entry was right; handles the wrong-attempt counter */
    public static boolean attempt(String pin) {
        if (lockoutLeftSeconds() > 0) return false;
        if (check(pin)) {
            fails = 0;
            return true;
        }
        if (++fails >= MAX_FAILS) {
            fails = 0;
            lockedUntil = System.currentTimeMillis() + LOCKOUT_MS;
        }
        return false;
    }

    // ---- app lifecycle hooks (LaunchActivity) ----
    public static void onPause() {
        if (!showing) pausedAt = System.currentTimeMillis();
    }

    public static void onResume(Activity a) {
        try {
            if (!enabled() || showing || a == null || a.isFinishing()) return;
            boolean need = !unlockedOnce || (pausedAt > 0 && System.currentTimeMillis() - pausedAt > GRACE_MS);
            if (need) showLock(a);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** status bar + navigation bar take the app theme colour, icons light/dark to match */
    private static void themeBars(Window w, int bg) {
        try {
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
            double lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0;
            boolean light = lum > 0.5;
            android.view.View dv = w.getDecorView();
            int f = dv.getSystemUiVisibility();
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                f = light ? (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                f = light ? (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
            dv.setSystemUiVisibility(f);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void showLock(final Activity a) {
        showing = true;
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar);
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final AeroPinPad[] pad = new AeroPinPad[1];
        final Runnable[] tick = new Runnable[1];
        pad[0] = new AeroPinPad(a, "Enter passcode", pin -> {
            if (attempt(pin)) {
                unlockedOnce = true;
                showing = false;
                pausedAt = 0;
                if (tick[0] != null) AndroidUtilities.cancelRunOnUIThread(tick[0]);
                try { d.dismiss(); } catch (Throwable ignore) { }
                return;
            }
            long left = lockoutLeftSeconds();
            if (left > 0) {
                pad[0].setInputEnabled(false);
                pad[0].fail("Too many attempts");
                tick[0] = new Runnable() {
                    @Override
                    public void run() {
                        long l = lockoutLeftSeconds();
                        if (l > 0) {
                            pad[0].setSubtitle("Try again in " + l + "s", true);
                            AndroidUtilities.runOnUIThread(this, 1000);
                        } else {
                            pad[0].setSubtitle("", false);
                            pad[0].setInputEnabled(true);
                        }
                    }
                };
                AndroidUtilities.runOnUIThread(tick[0], 600);
            } else {
                pad[0].fail("Wrong passcode");
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(pad[0], lp);
        d.setContentView(root);
        d.setOnKeyListener((dd, code, ev) -> {
            if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
                a.moveTaskToBack(true);
                return true;
            }
            return false;
        });
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            themeBars(w, Theme.getColor(Theme.key_windowBackgroundWhite));
        }
        d.show();
        long left = lockoutLeftSeconds();
        if (left > 0) pad[0].setSubtitle("Try again in " + left + "s", true);
    }
}

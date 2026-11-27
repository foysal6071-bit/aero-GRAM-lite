package org.telegram.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: slide-in side menu (profile header + "Personalise > Theme"). */
public class AeroDrawer {

    public static void show(final BaseFragment fragment) {
        final Context ctx = fragment.getParentActivity();
        if (ctx == null) return;
        final TLRPC.User user = UserConfig.getInstance(fragment.getCurrentAccount()).getCurrentUser();

        final Dialog dialog = new Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar);
        final FrameLayout root = new FrameLayout(ctx);
        final View scrim = new View(ctx);
        scrim.setBackgroundColor(0x80000000);
        scrim.setAlpha(0f);
        root.addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final int width = Math.min(dp(330), (int) (AndroidUtilities.displaySize.x * 0.78f));
        final LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        panel.setClickable(true);
        root.addView(panel, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT));

        // header
        final int accent = AeroPrefs.accent();
        FrameLayout header = new FrameLayout(ctx);
        header.setBackgroundColor(accent);
        header.setPadding(dp(20), AndroidUtilities.statusBarHeight + dp(24), dp(20), dp(20));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        header.addView(info, new FrameLayout.LayoutParams(-1, -2));

        FrameLayout ring = new FrameLayout(ctx);
        ring.setBackground(AeroUi.round(0x66FFFFFF, 36));
        BackupImageView avatar = new BackupImageView(ctx);
        avatar.setRoundRadius(dp(32));
        if (user != null) avatar.setForUserOrChat(user, new AvatarDrawable(user));
        ring.addView(avatar, LayoutHelper.createFrame(64, 64, Gravity.CENTER));
        info.addView(ring, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView name = AeroUi.text(ctx, user != null ? UserObject.getUserName(user) : "", 18, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = dp(14);
        info.addView(name, nlp);

        String ph = "";
        try {
            if (user != null && user.phone != null && user.phone.length() > 0) ph = PhoneFormat.getInstance().format("+" + user.phone);
        } catch (Throwable ignore) {
        }
        String un = user != null ? UserObject.getPublicUsername(user) : null;
        if (un != null && un.length() > 0) ph = ph.isEmpty() ? "@" + un : ph + "  \u00B7  @" + un;
        TextView phone = AeroUi.text(ctx, ph, 13, 0xCCFFFFFF, false);
        phone.setSingleLine(true);
        info.addView(phone, new LinearLayout.LayoutParams(-2, -2));

        FrameLayout edit = new FrameLayout(ctx);
        edit.setBackground(AeroUi.ripple(AeroUi.round(0x33FFFFFF, 18), 18));
        ImageView pencil = new ImageView(ctx);
        pencil.setImageResource(R.drawable.aero_ic_edit);
        pencil.setColorFilter(Color.WHITE);
        edit.addView(pencil, LayoutHelper.createFrame(18, 18, Gravity.CENTER));
        header.addView(edit, LayoutHelper.createFrame(36, 36, Gravity.RIGHT | Gravity.TOP));

        // scrollable settings list, grouped
        final ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(8));
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        list.addView(section(ctx, "ACCOUNT"));
        final View editRow = row(ctx, R.drawable.aero_ic_user, "Edit profile", "", accent);
        list.addView(editRow, rowLp());

        list.addView(section(ctx, "APPEARANCE"));
        final View themeRow = row(ctx, R.drawable.aero_ic_palette, "Theme", AeroPrefs.modeName(), accent);
        list.addView(themeRow, rowLp());
        final View displayRow = row(ctx, R.drawable.aero_ic_text, "Text & display size", AeroPrefs.textSize() + "  \u00B7  " + AeroPrefs.displayScale() + "%", accent);
        list.addView(displayRow, rowLp());

        list.addView(section(ctx, "NOTIFICATIONS"));
        final View soundRow = row(ctx, R.drawable.aero_ic_bell, "Sounds & ringtone", "", accent);
        list.addView(soundRow, rowLp());
        final View notifyRow = row(ctx, R.drawable.aero_ic_bell, "Notification types", "", accent);
        list.addView(notifyRow, rowLp());
        final View hideRow = row(ctx, R.drawable.aero_ic_lock, "Hide notification content", "", accent);
        final Switch hideSwitch = new Switch(ctx);
        hideSwitch.setChecked(AeroNotify.on(AeroNotify.HIDE));
        hideSwitch.getThumbDrawable().setTint(hideSwitch.isChecked() ? accent : 0xFFB0B6BE);
        hideSwitch.getTrackDrawable().setTint(hideSwitch.isChecked() ? accent : 0xFF8C929A);
        hideSwitch.setOnCheckedChangeListener((b, checked) -> {
            AeroNotify.set(AeroNotify.HIDE, checked);
            hideSwitch.getThumbDrawable().setTint(checked ? accent : 0xFFB0B6BE);
            hideSwitch.getTrackDrawable().setTint(checked ? accent : 0xFF8C929A);
        });
        ((LinearLayout) hideRow).addView(hideSwitch, new LinearLayout.LayoutParams(-2, -2));
        hideRow.setOnClickListener(v -> hideSwitch.toggle());
        list.addView(hideRow, rowLp());

        list.addView(section(ctx, "PRIVACY & SECURITY"));
        final View privacyRow = row(ctx, R.drawable.aero_ic_shield, "Privacy", "", accent);
        list.addView(privacyRow, rowLp());
        final View pcRow = row(ctx, R.drawable.aero_ic_lock, "Passcode", AeroPasscode.enabled() ? "On" : "Off", accent);
        list.addView(pcRow, rowLp());

        list.addView(section(ctx, "DATA"));
        final View downloadsRow = row(ctx, R.drawable.aero_ic_download, "Downloads", "", accent);
        list.addView(downloadsRow, rowLp());

        TextView foot = AeroUi.text(ctx, "AeroGram  \u00B7  unofficial", 12, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), false);
        foot.setPadding(dp(20), dp(10), dp(20), dp(16) + AndroidUtilities.navigationBarHeight);
        panel.addView(foot);

        final Runnable[] after = new Runnable[1];
        final boolean[] closing = new boolean[1];
        final Runnable close = () -> {
            if (closing[0]) return;
            closing[0] = true;
            scrim.animate().alpha(0f).setDuration(180).start();
            panel.animate().translationX(-width).setDuration(200).withEndAction(() -> {
                try { dialog.dismiss(); } catch (Throwable ignore) { }
                if (after[0] != null) after[0].run();
            }).start();
        };
        scrim.setOnClickListener(v -> close.run());
        edit.setOnClickListener(v -> { after[0] = () -> fragment.presentFragment(new AeroEditProfileActivity()); close.run(); });
        header.setOnClickListener(v -> { after[0] = () -> fragment.presentFragment(new AeroEditProfileActivity()); close.run(); });
        themeRow.setOnClickListener(v -> { after[0] = () -> fragment.presentFragment(new AeroThemeActivity()); close.run(); });
        displayRow.setOnClickListener(v -> { after[0] = () -> fragment.presentFragment(new AeroDisplayActivity()); close.run(); });
        downloadsRow.setOnClickListener(v -> { after[0] = () -> fragment.presentFragment(new AeroDownloadsActivity()); close.run(); });
        notifyRow.setOnClickListener(v -> { after[0] = () -> fragment.presentFragment(new AeroNotifyActivity()); close.run(); });
        soundRow.setOnClickListener(v -> { after[0] = () -> fragment.presentFragment(new AeroSoundActivity()); close.run(); });
        pcRow.setOnClickListener(v -> { after[0] = () -> fragment.presentFragment(new AeroPasscodeActivity()); close.run(); });
        privacyRow.setOnClickListener(v -> { after[0] = () -> fragment.presentFragment(new AeroPrivacyActivity()); close.run(); });
        editRow.setOnClickListener(v -> { after[0] = () -> fragment.presentFragment(new AeroEditProfileActivity()); close.run(); });
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                close.run();
                return true;
            }
            return false;
        });

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        panel.setTranslationX(-width);
        dialog.show();
        panel.post(() -> {
            panel.animate().translationX(0).setDuration(220).start();
            scrim.animate().alpha(1f).setDuration(220).start();
        });
    }

    private static LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        return lp;
    }

    private static View section(Context ctx, String t) {
        TextView sec = AeroUi.caption(ctx, t, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        sec.setPadding(dp(20), dp(16), dp(20), dp(6));
        return sec;
    }

    private static View row(Context ctx, int iconRes, String title, String value, int accent) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(16), 0);
        row.setBackground(AeroUi.ripple(AeroUi.round(Color.TRANSPARENT, 12), 12));
        FrameLayout chip = new FrameLayout(ctx);
        chip.setBackground(AeroUi.round(AeroUi.alpha(accent, 28), 10));
        if (iconRes > 0) {
            ImageView icon = new ImageView(ctx);
            icon.setImageResource(iconRes);
            icon.setColorFilter(accent);
            chip.addView(icon, LayoutHelper.createFrame(20, 20, Gravity.CENTER));
        } else {
            TextView aa = AeroUi.text(ctx, "Aa", 15, accent, true);
            aa.setGravity(Gravity.CENTER);
            chip.addView(aa, LayoutHelper.createFrame(-2, -2, Gravity.CENTER));
        }
        row.addView(chip, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView t = AeroUi.text(ctx, title, 15, Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(14);
        row.addView(t, tl);
        if (value != null && !value.isEmpty()) {
            row.addView(AeroUi.text(ctx, value, 13, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), false));
        }
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1, dp(52));
        row.setLayoutParams(outer);
        return row;
    }
}

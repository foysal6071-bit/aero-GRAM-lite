package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: Ringtone & notification sounds. Writes the same preferences Telegram itself reads. */
public class AeroSoundActivity extends BaseFragment {

    private static final int PICK_RINGTONE = 9102;
    private static final int PICK_NOTIFICATION = 9103;
    private LinearLayout root;
    private Context ctx;

    @Override
    public View createView(Context context) {
        ctx = context;
        actionBar.setBackButtonImage(R.drawable.aero_ic_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Sounds & ringtone");
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

    private void build() {
        root.removeAllViews();
        final SharedPreferences prefs = getNotificationsSettings();

        LinearLayout calls = card("CALLS", "Ringtone for incoming calls.");
        calls.addView(row(R.drawable.aero_ic_phone, "Call ringtone",
                soundName(prefs.getString("CallsRingtone", "Default")), v -> pickSound(PICK_RINGTONE)), rowLp());

        LinearLayout messages = card("MESSAGE NOTIFICATIONS", "Sound for messages in private chats, groups and channels.");
        String sound = prefs.getString("GlobalSound", "Default");
        boolean same = sound.equals(prefs.getString("GroupSound", "Default")) && sound.equals(prefs.getString("ChannelSound", "Default"));
        messages.addView(row(R.drawable.aero_ic_bell, "Notification sound",
                same ? soundName(sound) : "Mixed", v -> pickSound(PICK_NOTIFICATION)), rowLp());

        LinearLayout inApp = card("IN CHAT", "Sounds played while a chat is open.");
        final Switch toggle = new Switch(ctx);
        toggle.setChecked(prefs.getBoolean("EnableInChatSound", true));
        toggle.getThumbDrawable().setTint(toggle.isChecked() ? AeroPrefs.accent() : 0xFFB0B6BE);
        toggle.getTrackDrawable().setTint(toggle.isChecked() ? AeroPrefs.accent() : 0xFF8C929A);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            getNotificationsSettings().edit().putBoolean("EnableInChatSound", checked).apply();
            getNotificationsController().setInChatSoundEnabled(checked);
            toggle.getThumbDrawable().setTint(checked ? AeroPrefs.accent() : 0xFFB0B6BE);
            toggle.getTrackDrawable().setTint(checked ? AeroPrefs.accent() : 0xFF8C929A);
        });
        LinearLayout switchRow = row(R.drawable.aero_ic_bell, "In-chat sounds", null, v -> toggle.toggle());
        switchRow.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        inApp.addView(switchRow, rowLp());
    }

    private String soundName(String stored) {
        if (stored == null || stored.equals("Default")) return "Default";
        if (stored.equals("NoSound")) return "Silent";
        return stored;
    }

    private LinearLayout card(String label, String subtitle) {
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(10), dp(16), dp(10), dp(8));
        c.setBackground(AeroUi.round(Theme.getColor(Theme.key_windowBackgroundWhite), 18));
        TextView title = AeroUi.caption(ctx, label, gray);
        title.setPadding(dp(8), 0, dp(8), 0);
        c.addView(title);
        TextView s = AeroUi.text(ctx, subtitle, 13, gray, false);
        s.setPadding(dp(8), dp(4), dp(8), dp(8));
        c.addView(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        root.addView(c, lp);
        return c;
    }

    private LinearLayout.LayoutParams rowLp() {
        return new LinearLayout.LayoutParams(-1, dp(64));
    }

    private LinearLayout row(int iconRes, String title, String value, View.OnClickListener click) {
        final int accent = AeroPrefs.accent();
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(8), 0);
        row.setBackground(AeroUi.ripple(AeroUi.round(Color.TRANSPARENT, 12), 12));
        FrameLayout chip = new FrameLayout(ctx);
        chip.setBackground(AeroUi.round(AeroUi.alpha(accent, 28), 12));
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(accent);
        chip.addView(icon, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        row.addView(chip, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(AeroUi.text(ctx, title, 15, Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), true));
        if (value != null) {
            TextView v = AeroUi.text(ctx, value, 13, accent, false);
            v.setSingleLine(true);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(v);
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(14);
        row.addView(texts, tl);
        if (value != null) {
            row.addView(AeroUi.text(ctx, "›", 22, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), false));
        }
        row.setOnClickListener(click);
        return row;
    }

    private void pickSound(int request) {
        try {
            final boolean call = request == PICK_RINGTONE;
            final int type = call ? RingtoneManager.TYPE_RINGTONE : RingtoneManager.TYPE_NOTIFICATION;
            final Uri def = call ? Settings.System.DEFAULT_RINGTONE_URI : Settings.System.DEFAULT_NOTIFICATION_URI;
            Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, def);
            String path = getNotificationsSettings().getString(call ? "CallsRingtonePath" : "GlobalSoundPath", null);
            Uri current = null;
            if (path == null || path.equals("Default")) {
                current = def;
            } else if (!path.equals("NoSound")) {
                current = Uri.parse(path);
            }
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current);
            startActivityForResult(i, request);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_RINGTONE && requestCode != PICK_NOTIFICATION) return;
        if (resultCode != Activity.RESULT_OK || data == null || getParentActivity() == null) return;
        final boolean call = requestCode == PICK_RINGTONE;
        Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        String name = "NoSound";
        String path = "NoSound";
        if (uri != null) {
            path = uri.toString();
            if (uri.equals(call ? Settings.System.DEFAULT_RINGTONE_URI : Settings.System.DEFAULT_NOTIFICATION_URI)) {
                name = "Default";
                // message sounds use the keyword "Default"; calls expect the real uri
                if (!call) path = "Default";
            } else {
                name = "Custom";
                try {
                    Ringtone r = RingtoneManager.getRingtone(getParentActivity(), uri);
                    if (r != null) name = r.getTitle(getParentActivity());
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }
        SharedPreferences.Editor editor = getNotificationsSettings().edit();
        if (call) {
            editor.putString("CallsRingtone", name);
            editor.putString("CallsRingtonePath", path);
            editor.apply();
        } else {
            for (String key : new String[]{"GlobalSound", "GroupSound", "ChannelSound"}) {
                editor.putString(key, name);
                editor.putString(key + "Path", path);
                editor.remove(key + "DocId");
            }
            editor.commit();
            for (int type : new int[]{NotificationsController.TYPE_PRIVATE, NotificationsController.TYPE_GROUP, NotificationsController.TYPE_CHANNEL}) {
                getNotificationsController().updateServerNotificationsSettings(type);
                getNotificationsController().deleteNotificationChannelGlobal(type);
            }
        }
        if (root != null) build();
    }
}

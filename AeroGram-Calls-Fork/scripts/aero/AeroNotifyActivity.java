package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: Notification types screen (messages, voice messages, audio calls, video calls). */
public class AeroNotifyActivity extends BaseFragment {

    private Context ctx;
    private LinearLayout root;

    @Override
    public View createView(Context context) {
        ctx = context;
        actionBar.setBackButtonImage(R.drawable.aero_ic_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Notifications");
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

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            AeroPermissions.ensure(getParentActivity(), "android.permission.POST_NOTIFICATIONS", "Notifications are turned off for this app.");
        }
        LinearLayout chats = card("CHATS", "Turn a type off and it never notifies you (no sound, popup or badge).");
        chats.addView(switchRow(R.drawable.aero_ic_text, "Messages", "Text, photos, files and everything else", AeroNotify.MSG), rowLp());
        chats.addView(switchRow(R.drawable.aero_ic_send, "Voice messages", "Voice and audio messages", AeroNotify.VOICE), rowLp());
        LinearLayout calls = card("CALLS", "Off = the call does not ring or vibrate, and missed-call alerts are hidden.");
        calls.addView(switchRow(R.drawable.aero_ic_phone, "Audio calls", "Incoming and missed voice calls", AeroNotify.AUDIO), rowLp());
        calls.addView(switchRow(R.drawable.aero_ic_video, "Video calls", "Incoming and missed video calls", AeroNotify.VIDEO), rowLp());
        LinearLayout bub = card("FLOATING VIDEO CALL", "Leave the app during a video call and the other person floats on screen. Tap = buttons, throw down = end call.");
        bub.addView(switchRow(R.drawable.aero_ic_video, "Floating video bubble", "Needs \"Display over other apps\"", AeroNotify.BUBBLE), rowLp());
        LinearLayout privacy = card("PRIVACY", "On = the notification shows only \"New message\" instead of the text.");
        privacy.addView(switchRow(R.drawable.aero_ic_lock, "Hide notification content", "Hide message text in notifications", AeroNotify.HIDE), rowLp());
        return fragmentView;
    }

    private void tint(Switch s, boolean checked) {
        s.getThumbDrawable().setTint(checked ? AeroPrefs.accent() : 0xFFB0B6BE);
        s.getTrackDrawable().setTint(checked ? AeroPrefs.accent() : 0xFF8C929A);
    }

    private LinearLayout switchRow(int iconRes, String title, String sub, final String key) {
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
        texts.addView(AeroUi.text(ctx, sub, 12.5f, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), false));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(14);
        row.addView(texts, tl);
        final Switch sw = new Switch(ctx);
        sw.setChecked(AeroNotify.on(key));
        tint(sw, sw.isChecked());
        sw.setOnCheckedChangeListener((b, checked) -> {
            AeroNotify.set(key, checked);
            tint(sw, checked);
            if (checked && AeroNotify.BUBBLE.equals(key) && !AeroPermissions.canOverlay(ctx)) AeroPermissions.ensureOverlay(getParentActivity());
        });
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(v -> sw.toggle());
        return row;
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
        return new LinearLayout.LayoutParams(-1, dp(68));
    }
}

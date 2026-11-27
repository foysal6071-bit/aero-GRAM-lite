package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: Edit profile (first name, optional last name, username with live availability check). */
public class AeroEditProfileActivity extends BaseFragment {

    private static final int done_button = 1;
    private EditText first, last, username;
    private TextView status;
    private String lastChecked;
    private boolean usernameOk = true;
    private Runnable checkRunnable;
    private int checkReq;
    private String originalUsername = "";

    @Override
    public View createView(Context context) {
        final int accent = AeroPrefs.accent();
        actionBar.setBackButtonImage(R.drawable.aero_ic_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Edit profile");
        actionBar.setBackgroundColor(accent);
        actionBar.setItemsColor(Color.WHITE, false);
        actionBar.setTitleColor(Color.WHITE);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == done_button) save();
            }
        });

        TLRPC.User user = getUserConfig().getCurrentUser();
        originalUsername = user != null && UserObject.getPublicUsername(user) != null ? UserObject.getPublicUsername(user) : "";

        FrameLayout wrap = new FrameLayout(context);
        wrap.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        wrap.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 68));

        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(24), dp(16), dp(16));
        scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout ring = new FrameLayout(context);
        ring.setBackground(AeroUi.roundStroke(Theme.getColor(Theme.key_windowBackgroundWhite), 52, AeroUi.alpha(accent, 90), 2));
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(44));
        if (user != null) avatar.setForUserOrChat(user, new AvatarDrawable(user));
        ring.addView(avatar, LayoutHelper.createFrame(88, 88, Gravity.CENTER));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(100), dp(100));
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(ring, alp);
        TextView who = AeroUi.text(context, user != null ? UserObject.getUserName(user) : "", 17, Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), true);
        who.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = dp(10);
        wlp.bottomMargin = dp(18);
        col.addView(who, wlp);

        first = field(context, col, "First name", user != null ? user.first_name : "", false);
        last = field(context, col, "Last name (optional)", user != null ? user.last_name : "", false);
        col.addView(hint(context, "Your name is shown in chats and to your contacts."));

        username = field(context, col, "Username", originalUsername.isEmpty() ? "" : "@" + originalUsername, false);
        username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        status.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        status.setGravity(Gravity.RIGHT);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        slp.rightMargin = dp(14);
        // overlay the status on the username card
        ((FrameLayout) username.getTag()).addView(status, slp);
        col.addView(hint(context, "Use a\u2013z, 0\u20139 and underscores, at least 5 characters. People can find you by this username."));

        username.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { checkUsername(s.toString()); }
        });

        TextView save = AeroUi.text(context, "Save", 15, Color.WHITE, true);
        save.setGravity(Gravity.CENTER);
        save.setBackground(AeroUi.ripple(AeroUi.round(accent, 14), 14));
        save.setOnClickListener(v -> save());
        wrap.addView(save, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 52, Gravity.BOTTOM, 16, 0, 16, 16));

        fragmentView = wrap;
        return fragmentView;
    }

    private EditText field(Context c, LinearLayout parent, String label, String text, boolean unused) {
        final int accent = AeroPrefs.accent();
        final FrameLayout box = new FrameLayout(c);
        final int fill = Theme.getColor(Theme.key_windowBackgroundWhite);
        final int line = Theme.getColor(Theme.key_divider);
        box.setBackground(AeroUi.roundStroke(fill, 14, line, 1));
        LinearLayout inner = new LinearLayout(c);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(9), dp(16), dp(9));
        final TextView l = AeroUi.text(c, label, 12, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), false);
        inner.addView(l);
        EditText e = new EditText(c);
        e.setBackground(null);
        e.setPadding(0, dp(2), 0, 0);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        e.setHighlightColor(AeroUi.alpha(accent, 60));
        e.setText(text == null ? "" : text);
        e.setOnFocusChangeListener((v, focused) -> {
            box.setBackground(AeroUi.roundStroke(fill, 14, focused ? accent : line, focused ? 2 : 1));
            l.setTextColor(focused ? accent : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        });
        inner.addView(e, new LinearLayout.LayoutParams(-1, -2));
        box.addView(inner, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        parent.addView(box, lp);
        e.setTag(box);
        return e;
    }

    private TextView hint(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setLineSpacing(dp(2), 1f);
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        t.setPadding(dp(6), dp(8), dp(6), dp(4));
        return t;
    }

    private String cleanUsername() {
        String s = username.getText().toString().trim();
        return s.startsWith("@") ? s.substring(1) : s;
    }

    private void setStatus(String text, int color) {
        status.setText(text);
        status.setTextColor(color);
        status.setVisibility(text.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        status.setBackground(AeroUi.round(AeroUi.alpha(color == Color.TRANSPARENT ? 0xFF888888 : color, 28), 10));
        status.setPadding(dp(10), dp(3), dp(10), dp(3));
    }

    private void checkUsername(String raw) {
        String name = raw.trim();
        if (name.startsWith("@")) name = name.substring(1);
        if (checkRunnable != null) { AndroidUtilities.cancelRunOnUIThread(checkRunnable); checkRunnable = null; }
        if (checkReq != 0) { getConnectionsManager().cancelRequest(checkReq, true); checkReq = 0; }
        lastChecked = name;
        if (name.isEmpty() || name.equals(originalUsername)) {
            usernameOk = true;
            setStatus("", Color.TRANSPARENT);
            return;
        }
        if (!name.matches("[a-zA-Z0-9_]+") || name.length() < 5 || Character.isDigit(name.charAt(0))) {
            usernameOk = false;
            setStatus("Invalid", 0xFFD94A6B);
            return;
        }
        usernameOk = false;
        setStatus("Checking...", Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        final String n = name;
        checkRunnable = () -> {
            TL_account.checkUsername req = new TL_account.checkUsername();
            req.username = n;
            checkReq = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                checkReq = 0;
                if (!n.equals(lastChecked) || status == null) return;
                if (error == null && response instanceof TLRPC.TL_boolTrue) {
                    usernameOk = true;
                    setStatus("✓ Available", 0xFF1E9E55);
                } else {
                    usernameOk = false;
                    setStatus("Taken", 0xFFD94A6B);
                }
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        };
        AndroidUtilities.runOnUIThread(checkRunnable, 400);
    }

    private void save() {
        final String f = first.getText().toString().trim();
        final String l = last.getText().toString().trim();
        if (f.isEmpty()) {
            AndroidUtilities.shakeView(first);
            return;
        }
        if (!usernameOk) {
            AndroidUtilities.shakeView(username);
            return;
        }
        TLRPC.User cur = getUserConfig().getCurrentUser();
        if (cur != null && (!f.equals(cur.first_name) || !l.equals(cur.last_name == null ? "" : cur.last_name))) {
            TL_account.updateProfile req = new TL_account.updateProfile();
            req.flags = 3;
            cur.first_name = req.first_name = f;
            cur.last_name = req.last_name = l;
            TLRPC.User u2 = getMessagesController().getUser(getUserConfig().getClientUserId());
            if (u2 != null) { u2.first_name = f; u2.last_name = l; }
            getUserConfig().saveConfig(true);
            getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
            getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
            getConnectionsManager().sendRequest(req, (response, error) -> { });
        }
        final String name = cleanUsername();
        if (!name.equals(originalUsername)) {
            TL_account.updateUsername req = new TL_account.updateUsername();
            req.username = name;
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (error == null && response instanceof TLRPC.User) {
                    ArrayList<TLRPC.User> users = new ArrayList<>();
                    users.add((TLRPC.User) response);
                    getMessagesController().putUsers(users, false);
                    getMessagesStorage().putUsersAndChats(users, null, false, true);
                    getUserConfig().saveConfig(true);
                    finishFragment();
                } else {
                    setStatus("Could not save", 0xFFD94A6B);
                }
            }));
        } else {
            finishFragment();
        }
    }
}

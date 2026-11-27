package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.PasskeysController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: passkeys. List, add and remove the passkeys that can sign in to this account. */
public class AeroPasskeysActivity extends BaseFragment {

    private Context ctx;
    private boolean destroyed;
    private LinearLayout root;

    private boolean loading = true;
    private boolean failed;
    private final ArrayList<TL_account.Passkey> passkeys = new ArrayList<>();
    private final Set<String> busy = new HashSet<>();

    private boolean supported() {
        return Build.VERSION.SDK_INT >= 28 && BuildVars.SUPPORTS_PASSKEYS;
    }

    @Override
    public View createView(Context context) {
        ctx = context;
        actionBar.setBackButtonImage(R.drawable.aero_ic_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Passkeys");
        actionBar.setBackgroundColor(AeroPrefs.accent());
        actionBar.setItemsColor(Color.WHITE, false);
        actionBar.setTitleColor(Color.WHITE);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        final LinearLayout[] holder = new LinearLayout[1];
        ScrollView scroll = AeroPrivacyKit.page(context, holder);
        root = holder[0];
        fragmentView = scroll;
        if (supported()) {
            render();
            load();
        } else {
            loading = false;
            render();
        }
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    // ---------------------------------------------------------------- data

    private void load() {
        loading = true;
        failed = false;
        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(new TL_account.getPasskeys(), AndroidUtilities::runOnUIThread, (res, err) -> {
            if (destroyed) return;
            loading = false;
            if (res == null) {
                failed = true;
            } else {
                passkeys.clear();
                passkeys.addAll(res.passkeys);
            }
            render();
        });
    }

    private int max() {
        try {
            return getMessagesController().config.passkeysAccountPasskeysMax.get();
        } catch (Throwable t) {
            return 5;
        }
    }

    private void add() {
        if (!supported() || getParentActivity() == null) return;
        if (passkeys.size() >= max()) {
            Toast.makeText(ctx, "You can have up to " + max() + " passkeys. Remove one to add another.", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT < 28) return;
        PasskeysController.create(getParentActivity(), currentAccount, (passkey, error) -> {
            if (destroyed) return;
            if (error != null) {
                if ("CANCELLED".equalsIgnoreCase(error)) return;
                String msg;
                if ("EMPTY".equalsIgnoreCase(error)) {
                    msg = "No passkey provider was found on this device. Set up a screen lock or a password manager that supports passkeys, then try again.";
                } else {
                    msg = "The passkey couldn't be created (" + error + ").\n\nPasskeys are tied to the official Telegram app, so some unofficial builds can't register them.";
                }
                new AlertDialog.Builder(ctx).setTitle("Couldn't add passkey").setMessage(msg).setPositiveButton("OK", null).show();
            } else if (passkey != null) {
                try {
                    getMessagesController().removeSuggestion(0, "SETUP_PASSKEY");
                } catch (Throwable ignore) {
                }
                passkeys.add(passkey);
                Toast.makeText(ctx, "Passkey added", Toast.LENGTH_SHORT).show();
                render();
            }
        });
    }

    private void remove(final TL_account.Passkey p) {
        new AlertDialog.Builder(ctx)
                .setTitle("Remove passkey?")
                .setMessage("“" + name(p) + "” will no longer be able to sign in to your account.")
                .setPositiveButton("Remove", (d, w) -> {
                    busy.add(p.id);
                    render();
                    TL_account.deletePasskey req = new TL_account.deletePasskey();
                    req.id = p.id;
                    ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
                        if (destroyed) return;
                        busy.remove(p.id);
                        if (err == null && !(res instanceof TLRPC.TL_boolFalse)) {
                            passkeys.remove(p);
                            Toast.makeText(ctx, "Passkey removed", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(ctx, "Couldn't remove the passkey. Please try again.", Toast.LENGTH_LONG).show();
                        }
                        render();
                    });
                })
                .setNegativeButton("Cancel", null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
    }

    // ---------------------------------------------------------------- ui

    private void render() {
        if (root == null) return;
        root.removeAllViews();
        final int accent = AeroPrefs.accent();

        if (!supported()) {
            LinearLayout card = AeroPrivacyKit.card(ctx);
            card.addView(AeroPrivacyKit.message(ctx, R.drawable.aero_ic_key, accent, "Passkeys aren't available",
                    "This feature needs Android 9 or newer on a device that supports passkeys.", null, null));
            AeroPrivacyKit.add(root, card, 0);
            return;
        }
        if (loading) {
            root.addView(AeroPrivacyKit.loading(ctx, accent));
            return;
        }
        if (failed) {
            LinearLayout card = AeroPrivacyKit.card(ctx);
            card.addView(AeroPrivacyKit.message(ctx, R.drawable.aero_ic_key, accent, "Couldn't load passkeys",
                    "Check your connection and try again.", "Retry", () -> { loading = true; render(); load(); }));
            AeroPrivacyKit.add(root, card, 0);
            return;
        }

        if (passkeys.isEmpty()) {
            LinearLayout card = AeroPrivacyKit.card(ctx);
            card.addView(AeroPrivacyKit.message(ctx, R.drawable.aero_ic_key, accent, "Sign in without a code",
                    "A passkey lets you log in with your fingerprint, face or screen lock. It can't be phished and there is nothing to remember.",
                    "Add passkey", this::add));
            AeroPrivacyKit.add(root, card, 18);
            benefits();
            return;
        }

        LinearLayout hero = AeroPrivacyKit.card(ctx);
        hero.setPadding(dp(18), dp(16), dp(18), dp(16));
        hero.setBackground(AeroUi.round(AeroUi.alpha(accent, 24), 18));
        hero.addView(AeroUi.text(ctx, passkeys.size() + (passkeys.size() == 1 ? " passkey" : " passkeys"), 18, AeroPrivacyKit.ink(), true));
        TextView sub = AeroUi.text(ctx, "Use them to sign in with your fingerprint, face or screen lock.", 13.5f, AeroPrivacyKit.muted(), false);
        sub.setPadding(0, dp(4), 0, 0);
        hero.addView(sub);
        AeroPrivacyKit.add(root, hero, 18);

        root.addView(AeroPrivacyKit.sectionLabel(ctx, "YOUR PASSKEYS"));
        LinearLayout list = AeroPrivacyKit.card(ctx);
        list.setPadding(dp(8), dp(4), dp(8), dp(4));
        for (int i = 0; i < passkeys.size(); i++) {
            if (i > 0) list.addView(AeroPrivacyKit.divider(ctx, 62));
            list.addView(row(passkeys.get(i)));
        }
        AeroPrivacyKit.add(root, list, 14);

        if (passkeys.size() < max()) {
            AeroPrivacyKit.add(root, AeroPrivacyKit.button(ctx, "Add passkey", accent, true, this::add), 10);
        } else {
            TextView full = AeroUi.text(ctx, "You've reached the limit of " + max() + " passkeys.", 12.5f, AeroPrivacyKit.muted(), false);
            full.setGravity(Gravity.CENTER);
            root.addView(full, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void benefits() {
        LinearLayout card = AeroPrivacyKit.card(ctx);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        String[] lines = {"Faster sign-in with biometrics", "Protected against phishing", "Stored safely by your device or password manager"};
        for (int i = 0; i < lines.length; i++) {
            LinearLayout line = new LinearLayout(ctx);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(6), 0, dp(6));
            line.addView(AeroUi.check(ctx, AeroPrivacyKit.SUCCESS), new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView t = AeroUi.text(ctx, lines[i], 14, AeroPrivacyKit.ink(), false);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.leftMargin = dp(12);
            line.addView(t, tl);
            card.addView(line);
        }
        AeroPrivacyKit.add(root, card, 0);
    }

    private View row(final TL_account.Passkey p) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(12), dp(8), dp(12));
        row.addView(AeroPrivacyKit.chip(ctx, R.drawable.aero_ic_key, AeroPrefs.accent(), 44));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = AeroUi.text(ctx, name(p), 15.5f, AeroPrivacyKit.ink(), true);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(n);
        TextView created = AeroUi.text(ctx, "Added " + AeroPrivacyKit.day(p.date), 12.5f, AeroPrivacyKit.muted(), false);
        created.setPadding(0, dp(2), 0, 0);
        texts.addView(created);
        TextView used = AeroUi.text(ctx, p.last_usage_date > 0 ? "Last used " + AeroPrivacyKit.ago(p.last_usage_date) : "Never used", 12.5f, AeroPrivacyKit.muted(), false);
        texts.addView(used);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(14);
        tl.rightMargin = dp(8);
        row.addView(texts, tl);

        final boolean working = busy.contains(p.id);
        TextView b = AeroPrivacyKit.smallButton(ctx, working ? "…" : "Remove", AeroPrivacyKit.DANGER, working ? () -> { } : () -> remove(p));
        b.setAlpha(working ? 0.45f : 1f);
        row.addView(b);
        return row;
    }

    private static String name(TL_account.Passkey p) {
        return TextUtils.isEmpty(p.name) ? "Passkey" : p.name;
    }
}

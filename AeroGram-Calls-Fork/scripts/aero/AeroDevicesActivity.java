package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: logged-in devices. See every active session and terminate it. */
public class AeroDevicesActivity extends BaseFragment {

    private Context ctx;
    private boolean destroyed;
    private LinearLayout root;

    private boolean loading = true;
    private boolean failed;
    private TLRPC.TL_authorization current;
    private final ArrayList<TLRPC.TL_authorization> pending = new ArrayList<>();
    private final ArrayList<TLRPC.TL_authorization> others = new ArrayList<>();
    private final Set<Long> busy = new HashSet<>();
    private boolean terminatingAll;

    @Override
    public View createView(Context context) {
        ctx = context;
        actionBar.setBackButtonImage(R.drawable.aero_ic_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Logged-in devices");
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
        render();
        load();
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
        ConnectionsManager.getInstance(currentAccount).sendRequest(new TL_account.getAuthorizations(), (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (destroyed) return;
            loading = false;
            if (error != null || !(response instanceof TL_account.authorizations)) {
                failed = true;
            } else {
                current = null;
                pending.clear();
                others.clear();
                for (TLRPC.TL_authorization a : ((TL_account.authorizations) response).authorizations) {
                    if (a.current) current = a;
                    else if (a.password_pending) pending.add(a);
                    else others.add(a);
                }
                Collections.sort(others, (x, y) -> Integer.compare(y.date_active, x.date_active));
            }
            render();
        }));
    }

    private void terminate(final TLRPC.TL_authorization a) {
        new AlertDialog.Builder(ctx)
                .setTitle("Terminate session?")
                .setMessage(title(a) + " will be logged out of your account. You can sign in again at any time.")
                .setPositiveButton("Terminate", (d, w) -> {
                    busy.add(a.hash);
                    render();
                    TL_account.resetAuthorization req = new TL_account.resetAuthorization();
                    req.hash = a.hash;
                    ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                        if (destroyed) return;
                        busy.remove(a.hash);
                        if (error == null && response instanceof TLRPC.TL_boolTrue) {
                            others.remove(a);
                            pending.remove(a);
                            Toast.makeText(ctx, "Device terminated", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(ctx, friendly(error), Toast.LENGTH_LONG).show();
                        }
                        render();
                    }));
                })
                .setNegativeButton("Cancel", null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
    }

    private void terminateAll() {
        new AlertDialog.Builder(ctx)
                .setTitle("Terminate all other sessions?")
                .setMessage("Every device except this one will be logged out immediately.")
                .setPositiveButton("Terminate all", (d, w) -> {
                    terminatingAll = true;
                    render();
                    ConnectionsManager.getInstance(currentAccount).sendRequest(new TLRPC.TL_auth_resetAuthorizations(), (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                        if (destroyed) return;
                        terminatingAll = false;
                        if (error == null && response instanceof TLRPC.TL_boolTrue) {
                            others.clear();
                            pending.clear();
                            Toast.makeText(ctx, "All other sessions terminated", Toast.LENGTH_SHORT).show();
                            render();
                            load();
                        } else {
                            Toast.makeText(ctx, friendly(error), Toast.LENGTH_LONG).show();
                            render();
                        }
                    }));
                })
                .setNegativeButton("Cancel", null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
    }

    private static String friendly(TLRPC.TL_error e) {
        if (e == null || e.text == null) return "Something went wrong. Please try again.";
        if (e.text.contains("FRESH_RESET_AUTHORISATION_FORBIDDEN"))
            return "For security, sessions can only be terminated 24 hours after you signed in on this device.";
        if (e.text.startsWith("FLOOD_WAIT")) return "Too many attempts. Please wait a moment.";
        return "Couldn't complete the action (" + e.text + ")";
    }

    // ---------------------------------------------------------------- ui

    private void render() {
        if (root == null) return;
        root.removeAllViews();
        final int accent = AeroPrefs.accent();

        if (loading) {
            root.addView(AeroPrivacyKit.loading(ctx, accent));
            return;
        }
        if (failed) {
            LinearLayout card = AeroPrivacyKit.card(ctx);
            card.addView(AeroPrivacyKit.message(ctx, R.drawable.aero_ic_devices, accent, "Couldn't load devices",
                    "Check your connection and try again.", "Retry", () -> { loading = true; render(); load(); }));
            AeroPrivacyKit.add(root, card, 0);
            return;
        }

        // summary
        int total = (current != null ? 1 : 0) + pending.size() + others.size();
        LinearLayout summary = AeroPrivacyKit.card(ctx);
        summary.setPadding(dp(18), dp(16), dp(18), dp(16));
        summary.setBackground(AeroUi.round(AeroUi.alpha(accent, 24), 18));
        TextView big = AeroUi.text(ctx, total + (total == 1 ? " active device" : " active devices"), 18, AeroPrivacyKit.ink(), true);
        summary.addView(big);
        TextView small = AeroUi.text(ctx, others.isEmpty() && pending.isEmpty()
                ? "Your account is only signed in on this device."
                : "Terminate any session you don't recognise.", 13.5f, AeroPrivacyKit.muted(), false);
        small.setPadding(0, dp(4), 0, 0);
        summary.addView(small);
        AeroPrivacyKit.add(root, summary, 18);

        if (current != null) {
            root.addView(AeroPrivacyKit.sectionLabel(ctx, "THIS DEVICE"));
            LinearLayout card = AeroPrivacyKit.card(ctx);
            card.setPadding(dp(8), dp(4), dp(8), dp(4));
            card.addView(deviceRow(current, true));
            AeroPrivacyKit.add(root, card, 18);
        }

        if (!pending.isEmpty()) {
            root.addView(AeroPrivacyKit.sectionLabel(ctx, "WAITING FOR PASSWORD"));
            AeroPrivacyKit.add(root, listCard(pending), 18);
        }

        if (!others.isEmpty()) {
            root.addView(AeroPrivacyKit.sectionLabel(ctx, "OTHER DEVICES"));
            AeroPrivacyKit.add(root, listCard(others), 14);

            TextView all = AeroPrivacyKit.button(ctx, terminatingAll ? "Terminating…" : "Terminate all other sessions",
                    AeroPrivacyKit.DANGER, false, terminatingAll ? () -> { } : this::terminateAll);
            all.setAlpha(terminatingAll ? 0.5f : 1f);
            AeroPrivacyKit.add(root, all, 10);
            TextView hint = AeroUi.text(ctx, "You'll stay signed in on this device.", 12.5f, AeroPrivacyKit.muted(), false);
            hint.setGravity(Gravity.CENTER);
            root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private LinearLayout listCard(ArrayList<TLRPC.TL_authorization> list) {
        LinearLayout card = AeroPrivacyKit.card(ctx);
        card.setPadding(dp(8), dp(4), dp(8), dp(4));
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) card.addView(AeroPrivacyKit.divider(ctx, 62));
            card.addView(deviceRow(list.get(i), false));
        }
        return card;
    }

    private View deviceRow(final TLRPC.TL_authorization a, boolean isCurrent) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(12), dp(8), dp(12));
        row.addView(AeroPrivacyKit.chip(ctx, R.drawable.aero_ic_devices, platformColor(a), 44));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView name = AeroUi.text(ctx, title(a), 15.5f, AeroPrivacyKit.ink(), true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);

        TextView app = AeroUi.text(ctx, appLine(a), 12.5f, AeroPrivacyKit.muted(), false);
        app.setSingleLine(true);
        app.setEllipsize(TextUtils.TruncateAt.END);
        app.setPadding(0, dp(2), 0, 0);
        texts.addView(app);

        String where = placeLine(a);
        if (!where.isEmpty()) {
            TextView loc = AeroUi.text(ctx, where, 12.5f, AeroPrivacyKit.muted(), false);
            loc.setSingleLine(true);
            loc.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(loc);
        }

        TextView status;
        if (isCurrent) status = AeroUi.text(ctx, "● Online now", 12.5f, AeroPrivacyKit.SUCCESS, true);
        else status = AeroUi.text(ctx, "Last active " + AeroPrivacyKit.ago(a.date_active), 12.5f, AeroPrivacyKit.muted(), false);
        status.setPadding(0, dp(3), 0, 0);
        texts.addView(status);

        if (a.unconfirmed) {
            TextView warn = AeroPrivacyKit.pill(ctx, "New login", AeroPrivacyKit.DANGER);
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
            wl.topMargin = dp(6);
            texts.addView(warn, wl);
        }

        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(14);
        tl.rightMargin = dp(8);
        row.addView(texts, tl);

        if (!isCurrent) {
            final boolean working = busy.contains(a.hash) || terminatingAll;
            TextView b = AeroPrivacyKit.smallButton(ctx, busy.contains(a.hash) ? "…" : "Terminate", AeroPrivacyKit.DANGER,
                    working ? () -> { } : () -> terminate(a));
            b.setAlpha(working ? 0.45f : 1f);
            row.addView(b);
        }
        return row;
    }

    private static String title(TLRPC.TL_authorization a) {
        if (!TextUtils.isEmpty(a.device_model)) return a.device_model;
        if (!TextUtils.isEmpty(a.app_name)) return a.app_name;
        return "Unknown device";
    }

    private static String appLine(TLRPC.TL_authorization a) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(a.app_name)) sb.append(a.app_name);
        if (!TextUtils.isEmpty(a.app_version)) sb.append(sb.length() > 0 ? " " : "").append(a.app_version);
        String os = ((a.platform == null ? "" : a.platform) + " " + (a.system_version == null ? "" : a.system_version)).trim();
        if (!os.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(os);
        return sb.toString();
    }

    private static String placeLine(TLRPC.TL_authorization a) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(a.ip)) sb.append(a.ip);
        String place = !TextUtils.isEmpty(a.region) && !TextUtils.isEmpty(a.country) ? a.region + ", " + a.country
                : !TextUtils.isEmpty(a.country) ? a.country : (a.region == null ? "" : a.region);
        if (!place.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(place);
        return sb.toString();
    }

    private static int platformColor(TLRPC.TL_authorization a) {
        String s = ((a.platform == null ? "" : a.platform) + " " + (a.device_model == null ? "" : a.device_model) + " " + (a.app_name == null ? "" : a.app_name)).toLowerCase();
        if (s.contains("android")) return 0xFF2BA970;
        if (s.contains("ios") || s.contains("iphone") || s.contains("ipad") || s.contains("mac")) return 0xFF5B6B8C;
        if (s.contains("windows")) return 0xFF1F6FE5;
        if (s.contains("linux") || s.contains("ubuntu")) return 0xFF8A5CD6;
        if (s.contains("web") || s.contains("chrome") || s.contains("firefox") || s.contains("safari") || s.contains("edge")) return 0xFFE8903A;
        return AeroPrefs.accent();
    }
}

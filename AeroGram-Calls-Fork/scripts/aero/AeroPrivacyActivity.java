package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: Privacy hub. Password, passkeys and logged-in devices in one place. */
public class AeroPrivacyActivity extends BaseFragment {

    private Context ctx;
    private boolean destroyed;

    private TL_account.Password password;
    private int passkeyCount = -1;
    private int deviceCount = -1;

    private TextView passwordPill;
    private TextView passwordSub;
    private TextView passkeyPill;
    private TextView devicePill;
    private TextView passcodePill;

    @Override
    public View createView(Context context) {
        ctx = context;
        actionBar.setBackButtonImage(R.drawable.aero_ic_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Privacy");
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
        fragmentView = AeroPrivacyKit.page(context, holder);
        final LinearLayout root = holder[0];
        final int accent = AeroPrefs.accent();

        // hero
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(20), dp(22), dp(20), dp(22));
        hero.setBackground(AeroUi.round(AeroUi.alpha(accent, 24), 20));
        hero.addView(AeroPrivacyKit.chip(context, R.drawable.aero_ic_shield, accent, 60));
        TextView title = AeroUi.text(context, "Keep your account secure", 18, AeroPrivacyKit.ink(), true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(14);
        hero.addView(title, tl);
        TextView sub = AeroUi.text(context, "Manage how you sign in and which devices can access your account.", 13.5f, AeroPrivacyKit.muted(), false);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = dp(6);
        hero.addView(sub, sl);
        AeroPrivacyKit.add(root, hero, 18);

        // sign-in methods
        root.addView(AeroPrivacyKit.sectionLabel(context, "SIGN-IN"));
        LinearLayout signIn = AeroPrivacyKit.card(context);
        signIn.setPadding(dp(8), dp(6), dp(8), dp(6));

        passwordPill = AeroPrivacyKit.pill(context, "…", AeroPrivacyKit.muted());
        View passwordRow = row(R.drawable.aero_ic_lock, accent, "Password", "Two-step verification", passwordPill, v -> openPassword());
        passwordSub = (TextView) ((LinearLayout) ((LinearLayout) passwordRow).getChildAt(1)).getChildAt(1);
        signIn.addView(passwordRow);
        signIn.addView(AeroPrivacyKit.divider(context, 62));

        passkeyPill = AeroPrivacyKit.pill(context, "…", AeroPrivacyKit.muted());
        signIn.addView(row(R.drawable.aero_ic_key, accent, "Passkeys", "Sign in with fingerprint, face or screen lock", passkeyPill,
                v -> presentFragment(new AeroPasskeysActivity())));
        AeroPrivacyKit.add(root, signIn, 18);

        signIn.addView(AeroPrivacyKit.divider(context, 62));
        passcodePill = AeroPrivacyKit.pill(context, "\u2026", AeroPrivacyKit.muted());
        signIn.addView(row(R.drawable.aero_ic_keypad, accent, "Passcode", "6-digit lock for opening the app", passcodePill,
                v -> presentFragment(new AeroPasscodeActivity())));
        updatePasscodePill();

        // devices
        root.addView(AeroPrivacyKit.sectionLabel(context, "DEVICES"));
        LinearLayout devices = AeroPrivacyKit.card(context);
        devices.setPadding(dp(8), dp(6), dp(8), dp(6));
        devicePill = AeroPrivacyKit.pill(context, "…", AeroPrivacyKit.muted());
        devices.addView(row(R.drawable.aero_ic_devices, accent, "Logged-in devices", "See and terminate active sessions", devicePill,
                v -> presentFragment(new AeroDevicesActivity())));
        AeroPrivacyKit.add(root, devices, 18);

        TextView tip = AeroUi.text(context, "Tip: if you see a device you don't recognise, terminate it and change your password right away.", 12.5f, AeroPrivacyKit.muted(), false);
        tip.setPadding(dp(8), 0, dp(8), 0);
        tip.setLineSpacing(0, 1.15f);
        root.addView(tip);

        refresh();
        return fragmentView;
    }

    private View row(int icon, int accent, String title, String subtitle, TextView pill, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        row.setBackground(AeroUi.ripple(AeroUi.round(Color.TRANSPARENT, 12), 12));
        row.addView(AeroPrivacyKit.chip(ctx, icon, accent, 42));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(AeroUi.text(ctx, title, 15.5f, AeroPrivacyKit.ink(), true));
        TextView s = AeroUi.text(ctx, subtitle, 12.5f, AeroPrivacyKit.muted(), false);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(14);
        tl.rightMargin = dp(8);
        row.addView(texts, tl);

        row.addView(pill, new LinearLayout.LayoutParams(-2, -2));
        TextView chevron = AeroUi.text(ctx, "›", 22, AeroPrivacyKit.muted(), false);
        chevron.setPadding(dp(8), 0, dp(2), 0);
        row.addView(chevron);
        row.setOnClickListener(click);
        return row;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ctx != null) {
            updatePasscodePill();
            refresh();
        }
    }

    private void updatePasscodePill() {
        if (passcodePill == null) return;
        if (AeroPasscode.enabled()) AeroPrivacyKit.restyle(passcodePill, "On", AeroPrivacyKit.SUCCESS);
        else AeroPrivacyKit.restyle(passcodePill, "Off", AeroPrivacyKit.muted());
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private void refresh() {
        ConnectionsManager cm = ConnectionsManager.getInstance(currentAccount);

        cm.sendRequestTyped(new TL_account.getPassword(), AndroidUtilities::runOnUIThread, (res, err) -> {
            if (destroyed || res == null) return;
            password = res;
            TwoStepVerificationActivity.initPasswordNewAlgo(password);
            if (password.has_password) {
                AeroPrivacyKit.restyle(passwordPill, "On", AeroPrivacyKit.SUCCESS);
                passwordSub.setText("Change or remove your password");
            } else {
                AeroPrivacyKit.restyle(passwordPill, "Off", AeroPrivacyKit.muted());
                passwordSub.setText("Add an extra layer of protection");
            }
        });

        if (Build.VERSION.SDK_INT >= 28 && BuildVars.SUPPORTS_PASSKEYS) {
            cm.sendRequestTyped(new TL_account.getPasskeys(), AndroidUtilities::runOnUIThread, (res, err) -> {
                if (destroyed || res == null) return;
                passkeyCount = res.passkeys.size();
                if (passkeyCount == 0) AeroPrivacyKit.restyle(passkeyPill, "None", AeroPrivacyKit.muted());
                else AeroPrivacyKit.restyle(passkeyPill, String.valueOf(passkeyCount), AeroPrefs.accent());
            });
        } else {
            AeroPrivacyKit.restyle(passkeyPill, "Unavailable", AeroPrivacyKit.muted());
        }

        cm.sendRequest(new TL_account.getAuthorizations(), (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (destroyed || !(response instanceof TL_account.authorizations)) return;
            deviceCount = ((TL_account.authorizations) response).authorizations.size();
            AeroPrivacyKit.restyle(devicePill, deviceCount + (deviceCount == 1 ? " device" : " devices"), AeroPrefs.accent());
        }));
    }

    private void openPassword() {
        if (password == null) {
            Toast.makeText(ctx, "Loading… please try again in a moment", Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        if (!TwoStepVerificationActivity.canHandleCurrentPassword(password, false)) {
            Toast.makeText(ctx, "Please update the app to manage your password", Toast.LENGTH_LONG).show();
            return;
        }
        if (password.has_password) {
            // Telegram's own flow: verify current password, change it, hint, recovery e-mail, turn off
            TwoStepVerificationActivity f = new TwoStepVerificationActivity();
            f.setPassword(password);
            presentFragment(f);
        } else {
            int type = TextUtils.isEmpty(password.email_unconfirmed_pattern)
                    ? TwoStepVerificationSetupActivity.TYPE_INTRO
                    : TwoStepVerificationSetupActivity.TYPE_EMAIL_CONFIRM;
            presentFragment(new TwoStepVerificationSetupActivity(type, password));
        }
    }
}

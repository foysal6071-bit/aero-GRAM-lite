package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: turn the 6-digit passcode on, change it or turn it off. */
public class AeroPasscodeActivity extends BaseFragment {
    private FrameLayout holder;
    private Context ctx;

    @Override
    public View createView(Context context) {
        ctx = context;
        actionBar.setBackButtonImage(R.drawable.aero_ic_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Passcode");
        actionBar.setBackgroundColor(AeroPrefs.accent());
        actionBar.setItemsColor(Color.WHITE, false);
        actionBar.setTitleColor(Color.WHITE);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        holder = new FrameLayout(context);
        holder.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = holder;
        if (AeroPasscode.enabled()) showMenu(); else showNew();
        return fragmentView;
    }

    private void setContent(View v) {
        holder.removeAllViews();
        holder.addView(v, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    }

    private void showPad(String title, String subtitle, AeroPinPad.Listener l) {
        AeroPinPad pad = new AeroPinPad(ctx, title, l);
        pad.setSubtitle(subtitle, false);
        setContent(pad);
    }

    private void showMenu() {
        final int accent = AeroPrefs.accent();
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(AeroPrivacyKit.chip(ctx, R.drawable.aero_ic_lock, accent, 72));
        TextView head = AeroUi.text(ctx, "Passcode is on", 20, AeroPrivacyKit.ink(), true);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.topMargin = dp(18);
        col.addView(head, hl);
        TextView sub = AeroUi.text(ctx, "AeroGram asks for your 6-digit passcode when you open the app or come back after a short break.", 13.5f, AeroPrivacyKit.muted(), false);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(280), -2);
        sl.topMargin = dp(8);
        sl.bottomMargin = dp(24);
        col.addView(sub, sl);
        col.addView(button("Change passcode", accent, true, () -> verify(this::showNew)));
        col.addView(button("Turn off passcode", AeroPrivacyKit.DANGER, false, () -> verify(() -> {
            AeroPasscode.clear();
            Toast.makeText(ctx, "Passcode turned off", Toast.LENGTH_SHORT).show();
            finishFragment();
        })));
        setContent(col);
    }

    private View button(String label, int color, boolean filled, Runnable r) {
        TextView t = AeroPrivacyKit.button(ctx, label, color, filled, r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(280), dp(52));
        lp.topMargin = dp(12);
        t.setLayoutParams(lp);
        return t;
    }

    private void verify(Runnable ok) {
        final AeroPinPad[] pad = new AeroPinPad[1];
        pad[0] = new AeroPinPad(ctx, "Enter current passcode", pin -> {
            if (AeroPasscode.attempt(pin)) {
                ok.run();
            } else if (AeroPasscode.lockoutLeftSeconds() > 0) {
                pad[0].fail("Too many attempts. Try again in " + AeroPasscode.lockoutLeftSeconds() + "s");
            } else {
                pad[0].fail("Wrong passcode");
            }
        });
        pad[0].setSubtitle("Confirm it's you to continue", false);
        setContent(pad[0]);
    }

    private void showNew() {
        showPad("Enter new passcode", "Choose a 6-digit passcode", this::showConfirm);
    }

    private void showConfirm(final String first) {
        final AeroPinPad[] pad = new AeroPinPad[1];
        pad[0] = new AeroPinPad(ctx, "Confirm passcode", pin -> {
            if (pin.equals(first)) {
                AeroPasscode.set(pin);
                Toast.makeText(ctx, "Passcode saved", Toast.LENGTH_SHORT).show();
                finishFragment();
            } else {
                showPad("Enter new passcode", "Passcodes didn't match. Try again", this::showConfirm);
            }
        });
        pad[0].setSubtitle("Enter it once more to confirm", false);
        setContent(pad[0]);
    }
}

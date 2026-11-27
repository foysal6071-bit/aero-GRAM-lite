package org.telegram.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * AeroGram: permissions.
 *  1) first launch: asks for everything the app can use (microphone, camera, contacts, photos/videos/audio,
 *     notifications, Bluetooth for calls), then explains "Display over other apps" (floating video call).
 *  2) later: every feature asks again at the moment it is used (ensure / ensureOverlay). If Android no longer shows
 *     the system question ("don't ask again"), a dialog sends the person to the app's permission settings.
 */
public class AeroPermissions {
    private static final int CODE = 9200;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static boolean overlayAskedThisCall;

    private static SharedPreferences p() {
        return MessagesController.getGlobalMainSettings();
    }

    private static boolean granted(Context c, String perm) {
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static Set<String> declared(Context c) {
        Set<String> s = new HashSet<>();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions != null) s.addAll(Arrays.asList(pi.requestedPermissions));
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return s;
    }

    private static ArrayList<String> missing(Context c) {
        ArrayList<String> want = new ArrayList<>();
        want.add(Manifest.permission.RECORD_AUDIO);
        want.add(Manifest.permission.CAMERA);
        want.add(Manifest.permission.READ_CONTACTS);
        if (Build.VERSION.SDK_INT >= 33) {
            want.add("android.permission.POST_NOTIFICATIONS");
            want.add("android.permission.READ_MEDIA_IMAGES");
            want.add("android.permission.READ_MEDIA_VIDEO");
            want.add("android.permission.READ_MEDIA_AUDIO");
        } else {
            want.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= 28) want.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 31) want.add("android.permission.BLUETOOTH_CONNECT");
        Set<String> decl = declared(c);
        ArrayList<String> out = new ArrayList<>();
        for (String s : want) if (decl.contains(s) && !granted(c, s)) out.add(s);
        return out;
    }

    // ------------------------------------------------------------ hooks (LaunchActivity)

    /** every app resume: first launch -> ask everything once; video call without overlay permission -> ask now */
    public static void onResume(final Activity a) {
        try {
            if (!p().getBoolean("aero_perm_first_done", false)) {
                p().edit().putBoolean("aero_perm_first_done", true).apply();
                H.postDelayed(() -> askAllNow(a), 700);
                return;
            }
            boolean video = AeroCallBubble.videoCallActive();
            if (!video) {
                overlayAskedThisCall = false;
            } else if (!overlayAskedThisCall && AeroNotify.on(AeroNotify.BUBBLE) && !canOverlay(a)) {
                overlayAskedThisCall = true;
                H.postDelayed(() -> ensureOverlay(a), 500);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** LaunchActivity.onRequestPermissionsResult: after the first-launch questions, explain the overlay permission */
    public static void onResult(Activity a, int requestCode) {
        if (requestCode == CODE) H.postDelayed(() -> ensureOverlay(a), 400);
    }

    private static void askAllNow(Activity a) {
        try {
            if (a == null || a.isFinishing()) return;
            ArrayList<String> m = missing(a);
            for (String s : m) p().edit().putBoolean("aero_perm_req_" + s, true).apply();
            if (!m.isEmpty() && Build.VERSION.SDK_INT >= 23) {
                a.requestPermissions(m.toArray(new String[0]), CODE);
            } else {
                ensureOverlay(a);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------ use-time requests

    /**
     * Call right before a feature needs a permission.
     * @return true when it is already granted (go ahead); false when it was just requested (try again after the answer)
     */
    public static boolean ensure(final Activity a, final String perm, final String why) {
        try {
            if (a == null || granted(a, perm)) return true;
            if (Build.VERSION.SDK_INT < 23) return true;
            boolean askedBefore = p().getBoolean("aero_perm_req_" + perm, false);
            if (!askedBefore || a.shouldShowRequestPermissionRationale(perm)) {
                p().edit().putBoolean("aero_perm_req_" + perm, true).apply();
                a.requestPermissions(new String[]{perm}, CODE + 1);
            } else {
                // Android will not show the question again: send the person to the settings page
                openSettingsDialog(a, why);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    private static void openSettingsDialog(final Activity a, String why) {
        if (a.isFinishing()) return;
        new AlertDialog.Builder(a)
                .setTitle("Permission needed")
                .setMessage(why + "\n\nOpen settings > Permissions and allow it.")
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()));
                        a.startActivity(i);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    public static boolean canOverlay(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** "Display over other apps" (floating video call): explains why, then opens the system page */
    public static void ensureOverlay(final Activity a) {
        try {
            if (a == null || a.isFinishing() || canOverlay(a) || !AeroNotify.on(AeroNotify.BUBBLE)) return;
            new AlertDialog.Builder(a)
                    .setTitle("Floating video call")
                    .setMessage("Allow \"Display over other apps\" so the other person floats on your screen when you leave the app during a video call.")
                    .setPositiveButton("Allow", (d, w) -> AeroCallBubble.askPermission(a))
                    .setNegativeButton("Not now", null)
                    .show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}

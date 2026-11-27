package org.telegram.ui;

import android.content.SharedPreferences;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * AeroGram: notification types. Four switches (all ON by default): messages, voice messages, audio calls, video calls.
 * NotificationsController.processNewMessages() calls filter() first, so a switched-off type never reaches the
 * notification (no sound, no popup, no badge). Incoming calls stay visible but do not ring when their switch is off.
 */
public class AeroNotify {
    public static final String MSG = "aero_n_msg", VOICE = "aero_n_voice", AUDIO = "aero_n_audio", VIDEO = "aero_n_video";
    /** "Hide notification content" (message text is replaced by "New message"). ON by default. */
    public static final String HIDE = "aero_hide_content";
    /** floating video-call bubble when the app is in the background (ON by default) */
    public static final String BUBBLE = "aero_call_bubble";

    private static SharedPreferences p() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean on(String key) {
        return p().getBoolean(key, true);
    }

    public static void set(String key, boolean v) {
        p().edit().putBoolean(key, v).apply();
        if (HIDE.equals(key)) apply();
    }

    /** writes the preview preferences Telegram itself reads (private chats, groups, channels) for every account */
    public static void apply() {
        try {
            boolean show = !on(HIDE);
            for (int a = 0; a < UserConfig.MAX_ACCOUNTS; a++) {
                MessagesController.getNotificationsSettings(a).edit()
                        .putBoolean("EnablePreviewAll", show)
                        .putBoolean("EnablePreviewGroup", show)
                        .putBoolean("EnablePreviewChannel", show)
                        .apply();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** called on every app resume: keeps hiding active (also for accounts added later) while the switch is ON */
    public static void enforce() {
        if (on(HIDE)) apply();
    }

    private static boolean allOn() {
        return on(MSG) && on(VOICE) && on(AUDIO) && on(VIDEO);
    }

    public static boolean allowed(MessageObject m) {
        try {
            if (m == null || m.messageOwner == null) return true;
            if (m.messageOwner.action instanceof TLRPC.TL_messageActionPhoneCall) {
                return ((TLRPC.TL_messageActionPhoneCall) m.messageOwner.action).video ? on(VIDEO) : on(AUDIO);
            }
            if (m.isVoice()) return on(VOICE);
            return on(MSG);
        } catch (Throwable e) {
            FileLog.e(e);
            return true;
        }
    }

    /** removes switched-off types from the list; @return true when nothing is left (the caller must stop) */
    public static boolean filter(ArrayList<MessageObject> list, CountDownLatch latch) {
        try {
            if (list == null || allOn()) return false;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (!allowed(list.get(i))) list.remove(i);
            }
            if (list.isEmpty()) {
                if (latch != null) latch.countDown();
                return true;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    /** @return true when an incoming call of this kind must not ring / vibrate */
    public static boolean silentRing(boolean video) {
        return !on(video ? VIDEO : AUDIO);
    }
}

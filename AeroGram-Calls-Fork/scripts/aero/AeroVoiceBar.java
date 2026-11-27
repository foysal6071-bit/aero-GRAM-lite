package org.telegram.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

import static org.telegram.messenger.AndroidUtilities.dp;

/**
 * AeroGram: voice recording bar for private chats.
 * Hold the mic: release sends, swipe left cancels. Tap the mic: delete / pause / send buttons.
 * Recording and sending go through Telegram's own MediaController and SendMessagesHelper.
 */
public class AeroVoiceBar extends View implements NotificationCenter.NotificationCenterDelegate {

    public interface Host {
        MessageObject reply();
        TL_stories.StoryItem story();
        void beforeRecord();
        void sent();
    }

    private static final int[] EVENTS = {
            NotificationCenter.recordStartError, NotificationCenter.recordStopped, NotificationCenter.recordProgressChanged,
            NotificationCenter.recordPaused, NotificationCenter.recordResumed, NotificationCenter.audioDidSent,
            NotificationCenter.audioRecordTooShort
    };

    private static AeroVoiceBar active;

    /** true while one of our recordings is on screen; Telegram's own recording UI must stay away */
    public static boolean isActive() {
        return active != null;
    }

    private final ViewGroup parent;
    private final ChatActivity chat;
    private final Host host;
    private final int account;
    private final long dialogId;
    private final int guid;

    private boolean holding = true;
    private boolean paused;
    private boolean closed;
    private float downX, dragX;
    private long downAt, runningSince, recordedMs;
    private TLRPC.TL_document pausedAudio;
    private String pausedPath;
    private final ArrayList<Float> levels = new ArrayList<>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Drawable trash, send;

    /**
     * Called with every touch event of the mic button.
     * Returns the bar while it owns the gesture, or null to let Telegram handle the touch.
     */
    public static AeroVoiceBar onMicTouch(AeroVoiceBar current, MotionEvent e, View mic, ViewGroup parent, ChatActivity chat, boolean allowed, Host host) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            if (active != null && !active.closed) return active;
            if (!allowed || chat == null || chat.getParentActivity() == null || chat.getCurrentUser() == null
                    || chat.getCurrentEncryptedChat() != null || chat.getChatMode() != 0 || chat.isInScheduleMode()
                    || MessagesController.getInstance(chat.getCurrentAccount()).getSendPaidMessagesStars(chat.getDialogId()) > 0
                    || MediaController.getInstance().isRecordingAudio()) {
                return null;
            }
            if (Build.VERSION.SDK_INT >= 23 && chat.getParentActivity().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                AeroPermissions.ensure(chat.getParentActivity(), Manifest.permission.RECORD_AUDIO, "The microphone is needed to record voice messages.");
                return null;
            }
            AeroVoiceBar bar = new AeroVoiceBar(parent, chat, host);
            bar.start(e.getRawX());
            mic.getParent().requestDisallowInterceptTouchEvent(true);
            return bar;
        }
        if (current == null) return null;
        if (!current.closed && current.holding) {
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                current.dragX = Math.min(0, e.getRawX() - current.downX);
                if (current.dragX < -dp(110)) current.cancel();
                current.invalidate();
            } else if (e.getAction() == MotionEvent.ACTION_UP) {
                if (SystemClock.elapsedRealtime() - current.downAt < 350) {
                    current.lock(); // a tap
                } else {
                    current.send();
                }
            } else if (e.getAction() == MotionEvent.ACTION_CANCEL) {
                current.lock();
            }
        }
        return current;
    }

    private AeroVoiceBar(ViewGroup parent, ChatActivity chat, Host host) {
        super(parent.getContext());
        this.parent = parent;
        this.chat = chat;
        this.host = host;
        account = chat.getCurrentAccount();
        dialogId = chat.getDialogId();
        // our own id, so Telegram's recording interface ignores these events
        guid = -100000 - chat.getClassGuid();
        trash = getResources().getDrawable(R.drawable.aero_ic_trash).mutate();
        send = getResources().getDrawable(R.drawable.aero_ic_send).mutate();
        text.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    private void start(float rawX) {
        active = this;
        downX = rawX;
        downAt = runningSince = SystemClock.elapsedRealtime();
        for (int id : EVENTS) NotificationCenter.getInstance(account).addObserver(this, id);
        parent.addView(this, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76), Gravity.BOTTOM));
        setKeepScreenOn(true);
        host.beforeRecord();
        MediaController.getInstance().startRecording(account, dialogId, host.reply(), chat.getThreadMessage(), host.story(), guid, true,
                chat.getMessageChatSendParams(), chat.getSendMonoForumPeerId(), chat.getSendMessageSuggestionParams());
        MessagesController.getInstance(account).sendTyping(dialogId, 0, 1, 0);
    }

    private void lock() {
        holding = false;
        dragX = 0;
        ViewGroup.LayoutParams lp = getLayoutParams();
        lp.height = dp(52);
        setLayoutParams(lp);
        invalidate();
    }

    private long duration() {
        return recordedMs + (paused ? 0 : SystemClock.elapsedRealtime() - runningSince);
    }

    private void forgetDraft() {
        MediaDataController.getInstance(account).pushDraftVoiceMessage(dialogId, chat.isTopic ? chat.getTopicId() : 0, null);
    }

    private void cancel() {
        if (closed) return;
        if (paused) {
            MediaController.getInstance().cleanRecording(true);
        } else {
            MediaController.getInstance().stopRecording(0, false, 0, false, 0);
        }
        forgetDraft();
        close();
    }

    private void send() {
        if (closed) return;
        if (paused) {
            if (pausedAudio == null) return; // the preview file is still being written
            MediaController.getInstance().cleanRecording(false);
            forgetDraft();
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(pausedAudio, null, pausedPath, dialogId,
                    host.reply(), chat.getThreadMessage(), null, null, null, null, true, 0, 0, 0, null, null, false);
            params.sendMessageChatArguments = chat.getMessageChatSendParams();
            params.monoForumPeer = chat.getSendMonoForumPeerId();
            params.suggestionParams = chat.getSendMessageSuggestionParams();
            params.replyToStoryItem = host.story();
            SendMessagesHelper.getInstance(account).sendMessage(params);
        } else {
            // very short recordings are dropped by MediaController itself
            MediaController.getInstance().stopRecording(1, true, 0, false, 0);
        }
        host.sent();
        close();
    }

    private void togglePause() {
        if (paused && pausedAudio == null) return;
        if (!paused) {
            recordedMs = duration();
            paused = true;
            pausedAudio = null;
        }
        // when resuming, "paused" is cleared by the recordResumed event
        MediaController.getInstance().toggleRecordingPause(false);
        invalidate();
    }

    private void close() {
        if (closed) return;
        closed = true;
        if (active == this) active = null;
        for (int id : EVENTS) NotificationCenter.getInstance(account).removeObserver(this, id);
        setKeepScreenOn(false);
        setVisibility(GONE);
        post(() -> parent.removeView(this));
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (closed) return;
        if (id == NotificationCenter.recordPaused) {
            if (!paused) {
                recordedMs = duration();
                paused = true;
            }
            holding = false;
        } else if (id == NotificationCenter.recordResumed) {
            paused = false;
            pausedAudio = null;
            runningSince = SystemClock.elapsedRealtime();
        } else {
            if (args.length == 0 || !(args[0] instanceof Integer) || (Integer) args[0] != guid) return;
            if (id == NotificationCenter.recordProgressChanged) {
                levels.add((float) Math.min(1.0, ((Double) args[1]) / 1800.0));
                if (levels.size() > 200) levels.remove(0);
            } else if (id == NotificationCenter.audioDidSent) {
                if (args.length > 2 && args[1] instanceof TLRPC.TL_document) {
                    pausedAudio = (TLRPC.TL_document) args[1];
                    pausedPath = (String) args[2];
                    if (!holding) lock();
                }
            } else if (id == NotificationCenter.recordStopped) {
                // stopped from outside (call, time limit): keep nothing half-open on screen
                if (!paused) close();
            } else {
                close();
            }
        }
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && !holding) {
            float x = e.getX();
            if (x < dp(52)) {
                cancel();
            } else if (x > getWidth() - dp(56)) {
                send();
            } else if (x < dp(112)) {
                togglePause();
            }
        }
        return true;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE && !closed && !paused && runningSince > 0 && isAttachedToWindow()) {
            lock();
            togglePause();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        // leaving the chat throws an unfinished recording away; a paused one stays as Telegram's draft
        if (!closed) {
            if (!paused) MediaController.getInstance().stopRecording(0, false, 0, false, 0);
            close();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        final float w = getWidth(), h = getHeight();
        final int accent = AeroPrefs.accent();
        c.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final float cy = h - dp(26);

        if (holding) {
            text.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            text.setTextSize(dp(14));
            c.drawText("Swipe left to cancel or release to send", w / 2f + dragX / 3f, dp(18), text);
        }

        trash.setColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN);
        trash.setBounds(dp(14), (int) cy - dp(13), dp(40), (int) cy + dp(13));
        trash.draw(c);

        final float left = dp(52), right = w - (holding ? dp(8) : dp(56));
        paint.setColor(accent);
        rect.set(left, cy - dp(22), right, cy + dp(22));
        c.drawRoundRect(rect, dp(22), dp(22), paint);

        // pause / resume
        final float bx = left + dp(28);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(bx, cy, dp(15), paint);
        paint.setColor(accent);
        if (paused) {
            path.reset();
            path.moveTo(bx - dp(4), cy - dp(7));
            path.lineTo(bx + dp(7), cy);
            path.lineTo(bx - dp(4), cy + dp(7));
            path.close();
            c.drawPath(path, paint);
        } else {
            c.drawRoundRect(bx - dp(6), cy - dp(6.5f), bx - dp(2), cy + dp(6.5f), dp(1), dp(1), paint);
            c.drawRoundRect(bx + dp(2), cy - dp(6.5f), bx + dp(6), cy + dp(6.5f), dp(1), dp(1), paint);
        }

        // waveform: newest level on the right, empty slots are dots
        final float from = left + dp(52), to = right - dp(56), step = dp(6);
        final int slots = Math.max(0, (int) ((to - from) / step));
        paint.setColor(0xFFFFFFFF);
        for (int i = 0; i < slots; i++) {
            int index = levels.size() - slots + i;
            float level = index >= 0 ? levels.get(index) : 0;
            float half = dp(1.5f) + level * dp(12);
            float x = from + i * step;
            c.drawRoundRect(x, cy - half, x + dp(3), cy + half, dp(1.5f), dp(1.5f), paint);
        }

        final long s = duration() / 1000;
        text.setColor(0xFFFFFFFF);
        text.setTextSize(dp(15));
        c.drawText(s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60, right - dp(28), cy + dp(5.5f), text);

        if (!holding) {
            send.setColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN);
            send.setBounds((int) w - dp(44), (int) cy - dp(14), (int) w - dp(16), (int) cy + dp(14));
            send.draw(c);
        }
        if (!closed && !paused) postInvalidateDelayed(100);
    }
}

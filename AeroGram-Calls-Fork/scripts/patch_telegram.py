#!/usr/bin/env python3
"""Telegram Android (DrKLO) source patcher. env: VARIANT(audio|video) APP_ID APP_HASH APP_NAME [SRC=Telegram]
কোনো pattern না মিললে build fail করবে (যাতে চুপচাপ unpatched APK না বের হয়)।"""
import os, re, sys, pathlib

SRC = pathlib.Path(os.environ.get("SRC", "Telegram"))
FAILS = []
def fail(msg):
    print("FAIL: " + msg)
    FAILS.append(msg)
V = os.environ["VARIANT"]
APP_ID = os.environ["APP_ID"]
APP_HASH = os.environ["APP_HASH"]
APP_NAME = os.environ.get("APP_NAME", "我的爱")
assert V in ("audio", "video"), V
USE_CUSTOM = bool(APP_ID and APP_HASH)
assert not APP_ID or APP_ID.isdigit(), "APP_ID must be a number"

def sub_file(path, pattern, repl, flags=0, required=True):
    p = SRC / path
    if not p.exists():
        if required: fail(f"{path} not found")
        return 0
    t = p.read_text(encoding="utf-8")
    t2, n = re.subn(pattern, repl, t, flags=flags)
    if n == 0 and required: fail(f"pattern not found in {path}: {pattern[:90]}")
    if n: p.write_text(t2, encoding="utf-8")
    print(f"ok  {path}: {n} change(s)")
    return n

# 1) আলাদা applicationId (audio/video পাশাপাশি install হবে)
n = 0
for g in ("TMessagesProj_AppStandalone/build.gradle", "TMessagesProj_AppStandalone/build.gradle.kts",
          "TMessagesProj/build.gradle", "TMessagesProj/build.gradle.kts"):
    n += sub_file(g, r'applicationId(\s*=?\s*)["\'][^"\']+["\']',
                  lambda m: f'applicationId{m.group(1)}"org.aerogram.{V}"', required=False)
n += sub_file("gradle.properties", r'APP_PACKAGE\s*=\s*\S+', f'APP_PACKAGE=org.aerogram.{V}', required=False)
if n == 0: sys.exit("FAIL: applicationId/APP_PACKAGE not found in any gradle file")

# 2) নিজের API credentials
# secrets না দিলে source-এর ডিফল্ট credentials থাকে; login-এ শুধু phone + OTP লাগে
B = "TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java"
if USE_CUSTOM:
    sub_file(B, r'APP_ID\s*=\s*\d+\s*;', f'APP_ID = {APP_ID};')
    sub_file(B, r'APP_HASH\s*=\s*"[^"]*"\s*;', f'APP_HASH = "{APP_HASH}";')
else:
    print("skip credentials: using Telegram source defaults")

# 3) App নাম
sub_file("TMessagesProj/src/main/res/values/strings.xml",
         r'(<string name="AppName"[^>]*>)[^<]*(</string>)', lambda m: m.group(1) + APP_NAME + m.group(2))

# 4) Audio-only: video call শুরু করার সব পথ VoIPHelper.startCall-এ বন্ধ
if V == "audio":
    p = next((c for c in ("TMessagesProj/src/main/java/org/telegram/ui/Components/voip/VoIPHelper.java",
                          "TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPHelper.java") if (SRC / c).exists()), None)
    if p is None: sys.exit("FAIL: VoIPHelper.java not found")
    sig = re.compile(r'(static\s+void\s+startCall\s*\([^)]*boolean\s+videoCall[^)]*\)\s*(?:throws[^{]*)?\{)')
    text = (SRC / p).read_text(encoding="utf-8")
    def ins(m):
        extra = " canVideoCall = false;" if "canVideoCall" in m.group(1) else ""
        return m.group(1) + "\n        videoCall = false;" + extra + " // AeroGram audio-only"
    text2, n = sig.subn(ins, text)
    if n == 0: sys.exit("FAIL: VoIPHelper.startCall(videoCall) not found - source structure changed")
    (SRC / p).write_text(text2, encoding="utf-8")
    print(f"ok  {p}: {n} startCall overload(s) forced audio-only")

# 5) Messenger-style message status (private chats): sending=hollow circle, sent/delivered=filled circle+tick,
#    seen=small avatar of the other person on the LAST seen outgoing message only
CELL = "TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java"
CHAT = "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"

HELPER = """
    private ImageReceiver aeroSeenAvatar;
    private AvatarDrawable aeroSeenAvatarDrawable;
    private Paint aeroStatusPaint;
    private int aeroExtra;
    private AeroUploadProgress aeroUp;

    // AeroGram: animated upload percent (photo / video / file / voice)
    private void aeroDrawUpload(Canvas canvas, float layoutHeight, float timeYOffset) {
        MessageObject mo = currentMessageObject;
        if (mo == null) return;
        if (aeroUp == null) aeroUp = new AeroUploadProgress();
        aeroUp.draw(canvas, this, mo, getBackgroundDrawableLeft(), getBackgroundDrawableRight(), layoutHeight + timeYOffset);
    }

    // AeroGram: Messenger-style status. returns true when handled
    private boolean aeroDrawStatus(Canvas canvas, boolean drawCheck1, boolean drawCheck2, boolean drawClock, boolean drawError, float alpha, boolean bigRadius, float timeYOffset, float layoutHeight, float progress) {
        MessageObject mo = currentMessageObject;
        if (mo == null || drawError || !mo.isOutOwner()) return false;
        long did = mo.getDialogId();
        if (did <= 0 || did == UserConfig.getInstance(currentAccount).getClientUserId()) return false;
        if (!drawClock && !drawCheck1 && !drawCheck2) return false;
        boolean wantRow = delegate != null && delegate.isAeroStatusRow(this);
        if ((aeroExtra > 0) != wantRow) {
            post(this::requestLayout);
        }
        if (!wantRow) return true;
        float r = dp(7f) * (0.5f + 0.5f * progress);
        // Messenger-style: icon row BELOW the bubble, right-aligned
        float cx = layoutWidth - dp(16) - r;
        float cy = layoutHeight + dp(11) + timeYOffset;
        boolean media = false;
        if (aeroStatusPaint == null) {
            aeroStatusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            aeroStatusPaint.setStrokeCap(Paint.Cap.ROUND);
        }
        int color = getThemedColor(Theme.key_chat_inTimeText);
        int a = (int) (255 * alpha);
        if (drawClock) {
            aeroStatusPaint.setStyle(Paint.Style.STROKE);
            aeroStatusPaint.setStrokeWidth(dp(1.2f));
            aeroStatusPaint.setColor(color);
            aeroStatusPaint.setAlpha(a);
            canvas.drawCircle(cx, cy, r - dp(0.6f), aeroStatusPaint);
            invalidate();
            return true;
        }
        if (drawCheck1 && drawCheck2) {
            boolean last = delegate != null && delegate.isLastSeenOutgoing(this);
            if (!last) return true;
            if (aeroSeenAvatar == null) {
                aeroSeenAvatar = new ImageReceiver(this);
                aeroSeenAvatar.setAllowLoadingOnAttachedOnly(true);
                aeroSeenAvatarDrawable = new AvatarDrawable();
                if (attachedToWindow) aeroSeenAvatar.onAttachedToWindow();
            }
            TLRPC.User u = MessagesController.getInstance(currentAccount).getUser(did);
            float rr = r + dp(1);
            aeroSeenAvatarDrawable.setInfo(currentAccount, u);
            aeroSeenAvatar.setRoundRadius((int) rr);
            aeroSeenAvatar.setForUserOrChat(u, aeroSeenAvatarDrawable);
            aeroSeenAvatar.setImageCoords(cx - rr, cy - rr, rr * 2, rr * 2);
            aeroSeenAvatar.setAlpha(alpha);
            aeroSeenAvatar.draw(canvas);
            return true;
        }
        aeroStatusPaint.setStyle(Paint.Style.FILL);
        aeroStatusPaint.setColor(color);
        aeroStatusPaint.setAlpha(a);
        canvas.drawCircle(cx, cy, r, aeroStatusPaint);
        aeroStatusPaint.setStyle(Paint.Style.STROKE);
        aeroStatusPaint.setStrokeWidth(dp(1.3f));
        aeroStatusPaint.setColor(Color.WHITE);
        aeroStatusPaint.setAlpha(a);
        canvas.drawLine(cx - r * 0.42f, cy + r * 0.02f, cx - r * 0.1f, cy + r * 0.35f, aeroStatusPaint);
        canvas.drawLine(cx - r * 0.1f, cy + r * 0.35f, cx + r * 0.45f, cy - r * 0.3f, aeroStatusPaint);
        return true;
    }

"""

def edit(path, pattern, repl, count=1):
    p = SRC / path
    s = p.read_text(encoding="utf-8")
    s2, n = re.subn(pattern, repl, s, count=count, flags=re.S)
    if n != 1:
        fail(f"status patch pattern not found in {path}: {pattern[:90]}")
        return
    p.write_text(s2, encoding="utf-8")
    print(f"ok  {path}: status patch")

# reserve an extra row under the bubble for the last outgoing / last seen message
edit(CELL, r'(        additionalPaddingHeight = Math\.max\(0, resultHeight - normHeight\);\n)(\n        setMeasuredDimension\()',
     lambda m: m.group(1) + "        aeroExtra = (delegate != null && delegate.isAeroStatusRow(this)) ? dp(22) : 0;\n        resultHeight += aeroExtra;\n        additionalPaddingHeight += aeroExtra;\n" + m.group(2))
# helper + early branch in drawStatusDrawable
edit(CELL, r'(    private void drawStatusDrawable\(Canvas canvas, boolean drawCheck1, boolean drawCheck2, boolean drawClock, boolean drawError, float alpha, boolean bigRadius, float timeYOffset, float layoutHeight, float progress, boolean moveCheck, boolean drawSelectionBackground\) \{)',
     lambda m: HELPER + m.group(1) + "\n        aeroDrawUpload(canvas, layoutHeight, timeYOffset);\n        if (aeroDrawStatus(canvas, drawCheck1, drawCheck2, drawClock, drawError, alpha, bigRadius, timeYOffset, layoutHeight, progress)) return;")
# attach / detach of avatar receiver
edit(CELL, r'(        avatarImage\.onDetachedFromWindow\(\);\n)(        if \(compactForwardRow != null\) \{)',
     lambda m: m.group(1) + "        if (aeroSeenAvatar != null) aeroSeenAvatar.onDetachedFromWindow();\n" + m.group(2))
edit(CELL, r'(        avatarImage\.onAttachedToWindow\(\);\n)(        if \(compactForwardRow != null\) \{)',
     lambda m: m.group(1) + "        if (aeroSeenAvatar != null) aeroSeenAvatar.onAttachedToWindow();\n" + m.group(2))
# delegate default
edit(CELL, r'(        default boolean shouldDrawThreadProgress\(ChatMessageCell cell, boolean delayed\) \{)',
     lambda m: "        default boolean isLastSeenOutgoing(ChatMessageCell cell) {\n            return false;\n        }\n\n        default boolean isLastOutgoing(ChatMessageCell cell) {\n            return false;\n        }\n\n        default boolean isAeroStatusRow(ChatMessageCell cell) {\n            return false;\n        }\n\n" + m.group(1))
# ChatActivity implementation
IMPL = """        private boolean aeroPrivate() {
            return currentUser != null && currentUser.id != getUserConfig().getClientUserId() && currentEncryptedChat == null;
        }

        @Override
        public boolean isLastOutgoing(ChatMessageCell cell) {
            MessageObject m = cell.getMessageObject();
            if (m == null || !aeroPrivate()) return false;
            for (int i = 0; i < messages.size(); i++) {
                MessageObject o = messages.get(i);
                if (o.isOutOwner() && !o.isSendError()) {
                    return o.getId() == m.getId();
                }
            }
            return false;
        }

        @Override
        public boolean isLastSeenOutgoing(ChatMessageCell cell) {
            MessageObject m = cell.getMessageObject();
            if (m == null || !aeroPrivate()) return false;
            for (int i = 0; i < messages.size(); i++) {
                MessageObject o = messages.get(i);
                if (o.isOutOwner() && !o.isSending() && !o.isSendError() && !o.isUnread()) {
                    return o.getId() == m.getId();
                }
            }
            return false;
        }

        @Override
        public boolean isAeroStatusRow(ChatMessageCell cell) {
            return isLastOutgoing(cell) || isLastSeenOutgoing(cell);
        }

"""
edit(CHAT, r'(        @Override\n        public boolean shouldDrawThreadProgress\(ChatMessageCell cell, boolean delayed\) \{)',
     lambda m: IMPL + m.group(1))


# 6) "+" button (add by phone / @username) and "Add nickname" in chat-list long-press menu
DLG = "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java"
PLUS_XML = """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:height="32dp" android:width="32dp" android:viewportHeight="24" android:viewportWidth="24">
    <path android:pathData="M12,5v14M5,12h14" android:strokeColor="#FFFFFFFF" android:strokeWidth="2.2" android:strokeLineCap="round" android:fillColor="#00000000"/>
</vector>
"""
(SRC / "TMessagesProj/src/main/res/drawable/aero_fab_plus.xml").write_text(PLUS_XML, encoding="utf-8")
print("ok  aero_fab_plus.xml written")

AERO_JAVA = """    private EditText aeroInput(String hint, String text) {
        final EditText input = new EditText(getParentActivity());
        input.setHint(hint);
        input.setSingleLine(true);
        if (text != null) { input.setText(text); input.setSelection(text.length()); }
        input.setPadding(dp(24), dp(12), dp(24), dp(12));
        input.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        return input;
    }

    private void aeroAddContactDialog() {
        if (getParentActivity() == null) return;
        final EditText input = aeroInput("+8801XXXXXXXXX or @username", null);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle("Add chat");
        b.setView(input);
        b.setPositiveButton("Add", (d, w) -> {
            String s = input.getText().toString().trim();
            if (s.isEmpty()) return;
            String digits = s.replaceAll("[\\\\s\\\\-()]", "");
            if (digits.matches("\\\\+?\\\\d{5,}")) {
                NewContactBottomSheet sheet = new NewContactBottomSheet(DialogsActivity.this, getContext());
                sheet.setInitialPhoneNumber(digits.startsWith("+") ? digits : "+" + digits, true);
                sheet.show();
            } else {
                String u = s.replace("https://t.me/", "").replace("http://t.me/", "").replace("t.me/", "");
                if (u.startsWith("@")) u = u.substring(1);
                getMessagesController().openByUserName(u, DialogsActivity.this, 1);
            }
        });
        b.setNeutralButton("Contacts", (d, w) -> aeroOpenContacts());
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void aeroNicknameDialog() {
        if (getParentActivity() == null || selectedDialogs.size() != 1) return;
        final TLRPC.User user = getMessagesController().getUser(selectedDialogs.get(0));
        if (user == null) return;
        final EditText input = aeroInput("Nickname", user.first_name);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle("Add nickname");
        b.setView(input);
        b.setPositiveButton("Save", (d, w) -> {
            String nick = input.getText().toString().trim();
            if (nick.isEmpty()) return;
            user.first_name = nick;
            user.last_name = "";
            getContactsController().addContact(user, false);
            hideActionMode(true);
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void aeroGone(View v, int ignored) {
        if (v != null) v.setVisibility(View.GONE);
    }

    private void openWriteContacts() {
        aeroAddContactDialog();
    }

    private void aeroOpenContacts() {
"""

def edit2(path, pattern, repl):
    p = SRC / path
    s = p.read_text(encoding="utf-8")
    s2, n = re.subn(pattern, repl, s, count=1, flags=re.S)
    if n != 1:
        fail(f"pattern not found in {path}: {pattern[:90]}")
        return
    p.write_text(s2, encoding="utf-8")
    print(f"ok  {path}: aero patch")

edit2(DLG, r'    private void openWriteContacts\(\) \{\n', lambda m: AERO_JAVA)
edit2(DLG, r'floatingButton3\.setImageResource\(R\.drawable\.filled_fab_compose_32\);', lambda m: 'floatingButton3.setImageResource(R.drawable.aero_fab_plus);')
edit2(DLG, r'(    private final static int pin2 = 108;\n)', lambda m: m.group(1) + '    private final static int aero_nick = 9001;\n    private ActionBarMenuSubItem aeroNickItem;\n')
edit2(DLG, r'(        blockItem = otherItem\.addSubItem\(block, [^\n]*\n)',
      lambda m: m.group(1) + '        aeroNickItem = otherItem.addSubItem(aero_nick, R.drawable.msg_edit, "Add nickname");\n')
edit2(DLG, r'(\} else if \()(id == pin \|\| id == read \|\| id == delete)',
      lambda m: '} else if (id == aero_nick) {\n                    aeroNicknameDialog();\n                ' + m.group(1) + m.group(2))
edit2(DLG, r'(        if \(removeFromFolderItem != null\) \{\n            boolean cantRemoveFromFolder)',
      lambda m: '        if (aeroNickItem != null) {\n            aeroNickItem.setVisibility(selectedDialogs.size() == 1 && selectedDialogs.get(0) > 0 ? View.VISIBLE : View.GONE);\n        }\n' + m.group(1))


# 6b) chat-list selection mode: remove Pin + Archive (top icons) and Mark as read + Block user (3-dot menu).
#     Items stay created (other code uses them) but are forced GONE: every X.setVisibility(..) becomes aeroGone(X, ..)
_dp = SRC / DLG
_dt = _dp.read_text(encoding="utf-8")
_hid = []
for _nm in ("pinItem", "pin2Item", "archiveItem", "readItem", "blockItem"):
    if re.search(r"\b" + _nm + r"\s*=", _dt):
        _dt, _c = re.subn(r"\b" + _nm + r"\.setVisibility\(", "aeroGone(" + _nm + ", ", _dt)
        _hid.append(_nm)
        print(f"ok  DialogsActivity: {_nm} hidden ({_c} setVisibility call(s) rerouted)")
    else:
        print(f"WARN: {_nm} not found in DialogsActivity - not hidden")
if _hid:
    _dt, _c = re.subn(r"(        blockItem = otherItem\.addSubItem\(block, [^\n]*\n)",
                      lambda m: m.group(1) + "".join(f"        aeroGone({n}, 0);\n" for n in _hid), _dt, count=1)
    print(f"ok  DialogsActivity: initial hide ({_c})")
_dp.write_text(_dt, encoding="utf-8")

# 7) video: chat header shows a video-call icon next to the audio-call icon (audio variant: unchanged)
if V == "video":
    CA = "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
    edit2(CA, r'(    private ActionBarMenu\.LazyItem audioCallIconItem;\n)', lambda m: m.group(1) + "    private ActionBarMenu.LazyItem aeroVideoIconItem;\n")
    edit2(CA, r'(                audioCallIconItem\.setContentDescription\(LocaleController\.getString\(R\.string\.Call\)\);\n)',
          lambda m: m.group(1) + "                aeroVideoIconItem = menu.lazilyAddItem(video_call, R.drawable.msg_videocall, themeDelegate);\n                aeroVideoIconItem.setContentDescription(LocaleController.getString(R.string.VideoCall));\n")
    s = (SRC / CA).read_text(encoding="utf-8")
    s, n = re.subn(r'^(\s*)audioCallIconItem\.setVisibility\((.*)\);$',
                   lambda m: f"{m.group(0)}\n{m.group(1)}if (aeroVideoIconItem != null) aeroVideoIconItem.setVisibility({m.group(2)});", s, flags=re.M)
    if n < 8: sys.exit(f"FAIL: expected >=8 audioCallIconItem.setVisibility sites, got {n}")
    (SRC / CA).write_text(s, encoding="utf-8")
    print(f"ok  {CA}: video icon mirrored at {n} site(s)")


# 8) quick white-heart button in the message bar (replaces Messenger's thumbs-up): sends the quick emoji; long-press changes it
AERO_EMOJI_JAVA = """    private String aeroQuickEmoji() {
        String e = MessagesController.getGlobalMainSettings().getString("aero_quick_emoji", "\\uD83E\\uDD0D");
        return (e == null || e.isEmpty()) ? "\\uD83E\\uDD0D" : e;
    }

    private void aeroChangeQuickEmoji() {
        final android.widget.EditText input = new android.widget.EditText(getContext());
        input.setSingleLine(true);
        input.setText(aeroQuickEmoji());
        input.setSelection(input.getText().length());
        input.setTextSize(24);
        input.setGravity(Gravity.CENTER);
        input.setPadding(dp(24), dp(12), dp(24), dp(12));
        input.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(getContext(), resourcesProvider);
        b.setTitle("Quick emoji");
        b.setView(input);
        b.setPositiveButton("Save", (d, w) -> {
            String s = input.getText().toString().trim();
            if (s.isEmpty() || s.length() > 12) return;
            MessagesController.getGlobalMainSettings(
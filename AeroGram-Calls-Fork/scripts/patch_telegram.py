#!/usr/bin/env python3
"""Telegram Android (DrKLO) source patcher. env: VARIANT(audio|video) APP_ID APP_HASH APP_NAME [SRC=Telegram]
কোনো pattern না মিললে build fail করবে (যাতে চুপচাপ unpatched APK না বের হয়)।"""
import os, re, sys, pathlib

SRC = pathlib.Path(os.environ.get("SRC", "Telegram"))
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
        if required: sys.exit(f"FAIL: {path} not found")
        return 0
    t = p.read_text(encoding="utf-8")
    t2, n = re.subn(pattern, repl, t, flags=flags)
    if n == 0 and required: sys.exit(f"FAIL: pattern not found in {path}: {pattern}")
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
    if n != 1: sys.exit(f"FAIL: status patch pattern not found in {path}: {pattern[:60]}")
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
    if n != 1: sys.exit(f"FAIL: pattern not found in {path}: {pattern[:70]}")
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
            MessagesController.getGlobalMainSettings().edit().putString("aero_quick_emoji", s).apply();
        });
        b.setNeutralButton("Reset", (d, w) -> {
            MessagesController.getGlobalMainSettings().edit().remove("aero_quick_emoji").apply();
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        b.show();
    }

"""
EV = "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
edit2(EV, r'(    private void updateFieldRight\(int attachVisible\) \{)',
      lambda m: AERO_EMOJI_JAVA + m.group(1))


# 9) Messenger-style left icon row in the message bar: [+] [gallery] (no camera), field shifted right
for name, path in (("aero_plus_circle", "M16,4C9.4,4 4,9.4 4,16s5.4,12 12,12 12,-5.4 12,-12S22.6,4 16,4zM22,17.5h-4.5V22h-3v-4.5H10v-3h4.5V10h3v4.5H22z"),
                   ("aero_heart", "M16,27.5l-1.7,-1.5C8.4,20.7 4.5,17.2 4.5,12.9 4.5,9.4 7.2,6.7 10.7,6.7c2,0 3.9,0.9 5.3,2.4 1.4,-1.5 3.3,-2.4 5.3,-2.4 3.5,0 6.2,2.7 6.2,6.2 0,4.3 -3.9,7.8 -9.8,13.1z"),
                   ("aero_gallery", "M24,6H8C6.9,6 6,6.9 6,8v16c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8C26,6.9 25.1,6 24,6zM9.5,22l4,-5.2 3,3.9 4.2,-5.4 4.8,6.7z")):
    (SRC / f"TMessagesProj/src/main/res/drawable/{name}.xml").write_text(
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:height="28dp" android:width="28dp" android:viewportHeight="32" android:viewportWidth="32">\n'
        f'    <path android:fillColor="#FFFFFF" android:pathData="{path}"/>\n</vector>\n', encoding="utf-8")
print("ok  aero icons written")
BACKSPACE_XML = """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:height="24dp" android:width="24dp" android:viewportHeight="24" android:viewportWidth="24">
    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="1.7" android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000" android:pathData="M9.4,5.5H18.5A2.5,2.5 0 0,1 21,8V16A2.5,2.5 0 0,1 18.5,18.5H9.4A2.5,2.5 0 0,1 7.5,17.6L3.2,13.1A1.6,1.6 0 0,1 3.2,10.9L7.5,6.4A2.5,2.5 0 0,1 9.4,5.5Z"/>
    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="1.7" android:strokeLineCap="round" android:fillColor="#00000000" android:pathData="M12.6,9.6L16.8,13.8M16.8,9.6L12.6,13.8"/>
</vector>
"""
(SRC / "TMessagesProj/src/main/res/drawable/aero_backspace.xml").write_text(BACKSPACE_XML, encoding="utf-8")
print("ok  aero_backspace.xml written")

edit2(EV, r'(        textFieldContainer\.addView\(frameLayout, LayoutHelper\.createFrame\(LayoutHelper\.MATCH_PARENT, LayoutHelper\.WRAP_CONTENT, Gravity\.BOTTOM, 0, 0, DEFAULT_HEIGHT, 0\)\);\n)',
      lambda m: """        final boolean aeroLeftRow = chatMode == 0 && parentFragment != null && !isStories;
        textFieldContainer.addView(frameLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, aeroLeftRow ? 80 : 0, 0, DEFAULT_HEIGHT, 0));
        if (aeroLeftRow) {
            final int[] aeroIcons = {R.drawable.aero_plus_circle, R.drawable.aero_gallery};
            for (int aeroI = 0; aeroI < aeroIcons.length; aeroI++) {
                ImageView aeroBtn = new ImageView(context);
                aeroBtn.setImageResource(aeroIcons[aeroI]);
                aeroBtn.setScaleType(ImageView.ScaleType.CENTER);
                aeroBtn.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_chat_messagePanelIcons), PorterDuff.Mode.SRC_IN));
                aeroBtn.setContentDescription(aeroI == 0 ? "Attach" : "Gallery");
                aeroBtn.setOnClickListener(v -> {
                    if (delegate != null) delegate.didPressAttachButton();
                });
                textFieldContainer.addView(aeroBtn, LayoutHelper.createFrame(40, DEFAULT_HEIGHT, Gravity.BOTTOM | Gravity.LEFT, aeroI * 40, 0, 0, 0));
            }
        }
""" )


# 9b) message bar: no heart / paperclip inside the field; mic takes that spot; quick emoji takes the old mic spot
edit2(EV, r'messageEditTextContainer\.addView\(attachButton, (LayoutHelper\.createFrame\(DEFAULT_HEIGHT, DEFAULT_HEIGHT, Gravity\.BOTTOM \| Gravity\.RIGHT\))\);',
      lambda m: 'if (!aeroLeftRow) messageEditTextContainer.addView(attachButton, ' + m.group(1) + ');')
edit2(EV, r'sendButtonContainer\.addView\(audioVideoButtonContainer, LayoutHelper\.createFrame\(DEFAULT_HEIGHT, DEFAULT_HEIGHT, Gravity\.RIGHT \| Gravity\.BOTTOM\)\);\n',
      lambda m: """sendButtonContainer.addView(audioVideoButtonContainer, LayoutHelper.createFrame(DEFAULT_HEIGHT, DEFAULT_HEIGHT, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, aeroLeftRow ? DEFAULT_HEIGHT : 0, 0));
        if (aeroLeftRow) {
            final android.widget.TextView aeroEmoji = new android.widget.TextView(context);
            aeroEmoji.setGravity(Gravity.CENTER);
            aeroEmoji.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 24);
            aeroEmoji.setTextColor(0xFFFFFFFF);
            aeroEmoji.setText(aeroQuickEmoji());
            aeroEmoji.setContentDescription("Send quick emoji");
            aeroEmoji.setOnClickListener(v -> {
                if (messageEditText != null && messageEditText.length() == 0) {
                    messageEditText.setText(aeroQuickEmoji());
                    sendMessage();
                }
            });
            aeroEmoji.setOnLongClickListener(v -> {
                aeroChangeQuickEmoji();
                return true;
            });
            sendButtonContainer.addView(aeroEmoji, 0, LayoutHelper.createFrame(DEFAULT_HEIGHT, DEFAULT_HEIGHT, Gravity.RIGHT | Gravity.BOTTOM));
            // follows the mic: shown only while the field is empty and nothing is being recorded
            final boolean[] aeroCut = {false};
            sendButtonContainer.getViewTreeObserver().addOnPreDrawListener(() -> {
                boolean show = audioVideoButtonContainer.getVisibility() == VISIBLE && audioVideoButtonContainer.getAlpha() > 0.5f
                        && !recordingAudioVideo && (messageEditText == null || messageEditText.length() == 0)
                        && (recordedAudioPanel == null || recordedAudioPanel.getVisibility() != VISIBLE);
                int visibility = show ? VISIBLE : GONE;
                if (aeroEmoji.getVisibility() != visibility) aeroEmoji.setVisibility(visibility);
                if (!android.text.TextUtils.equals(aeroEmoji.getText(), aeroQuickEmoji())) aeroEmoji.setText(aeroQuickEmoji());
                // the input bubble stops before the emoji, so the emoji has no background
                boolean cut = show && aeroEmoji.isShown();
                if (cut != aeroCut[0]) {
                    for (android.view.ViewParent p = getParent(); p != null; p = p.getParent()) {
                        if (p instanceof org.telegram.ui.Components.chat.ChatInputViewsContainer) {
                            ((org.telegram.ui.Components.chat.ChatInputViewsContainer) p).setInputBubbleOffsets(0, cut ? dp(DEFAULT_HEIGHT) : 0);
                            aeroCut[0] = cut;
                            break;
                        }
                    }
                }
                return true;
            });
        }
""")

# 9c) mic circle follows the app theme (Aero accent colour)
edit2(EV, r'paint\.setColor\(getThemedColor\(Theme\.key_chat_messagePanelSend\)\);(\n                    final float margin = dpf2\(3\);)',
      lambda m: 'paint.setColor(org.telegram.ui.AeroPrefs.accent());' + m.group(1))

# 10) chat 3-dot menu (modern rounded dots): "Wallpaper" + "Change quick emoji"
DOTS_XML = """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:height="24dp" android:width="24dp" android:viewportHeight="24" android:viewportWidth="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M12,2.9a2.1,2.1 0 1,0 0,4.2a2.1,2.1 0 1,0 0,-4.2zM12,9.9a2.1,2.1 0 1,0 0,4.2a2.1,2.1 0 1,0 0,-4.2zM12,16.9a2.1,2.1 0 1,0 0,4.2a2.1,2.1 0 1,0 0,-4.2z"/>
</vector>
"""
(SRC / "TMessagesProj/src/main/res/drawable/aero_dots.xml").write_text(DOTS_XML, encoding="utf-8")
print("ok  aero_dots.xml written")
edit2(EV, r'    private void aeroChangeQuickEmoji\(\)', lambda m: '    public void aeroChangeQuickEmoji()')
edit2(CHAT, r'(    private final static int video_call = 33;\n)', lambda m: m.group(1) + "    private final static int aero_emoji = 9002;\n")
edit2(CHAT, r'(                \} else if \(id == call \|\| id == video_call\) \{)',
      lambda m: "                } else if (id == aero_emoji) {\n                    if (chatActivityEnterView != null) chatActivityEnterView.aeroChangeQuickEmoji();\n" + m.group(1))
edit2(CHAT, r'(            if \(currentUser != null && chatMode != MODE_SAVED\) \{\n                headerItem\.lazilyAddSubItem\(call,)',
      lambda m: '            if (chatMode == 0) {\n                headerItem.setIcon(R.drawable.aero_dots);\n                if (themeDelegate.isThemeChangeAvailable(true)) {\n                    headerItem.lazilyAddSubItem(change_colors, R.drawable.msg_background, "Wallpaper");\n                }\n                headerItem.lazilyAddSubItem(aero_emoji, R.drawable.msg_edit, "Change quick emoji");\n            }\n' + m.group(1))

# the original "Set Wallpapers" row stays only for other chat modes (no duplicate)
edit2(CHAT, r'if \(themeDelegate\.isThemeChangeAvailable\(true\)\) \{(\n                headerItem\.lazilyAddSubItem\(change_colors, R\.drawable\.msg_background, LocaleController)',
      lambda m: 'if (chatMode != 0 && themeDelegate.isThemeChangeAvailable(true)) {' + m.group(1))

# 10b) chat 3-dot menu: remove Clear history, Delete chat, Block user (whole statement, any args)
_n = sub_file(CHAT, r'^[ \t]*headerItem\.(?:lazilyAddSubItem|addSubItem)\(\s*(?:clear_history|delete_chat|block_user|block)\b[^;]*;[ \t]*\n', '', flags=re.M, required=False)
if _n == 0:
    print("WARN: Clear history / Delete chat / Block user lines not found in ChatActivity - check upstream structure")

# 11) attach (+) menu in normal chats: remove Poll, Contact, Music (other branches e.g. message editing are untouched)
ATT = "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java"
OLD = ("                if (pollsEnabled) {\n                    pollButton = buttonsCount++;\n                }\n"
       "                if (plainTextEnabled) {\n                    contactButton = buttonsCount++;\n                }\n")
edit2(ATT, re.escape(OLD), lambda m: "                // AeroGram: poll and contact removed from attach menu (music kept, shown as Audio)\n")
edit2(ATT, r'getString\(R\.string\.AttachMusic\), GlassTabView\.TabAnimation\.MUSIC\)', lambda m: '"Audio", GlassTabView.TabAnimation.MUSIC)')

# 12) attach (+) menu: add Camera and Video buttons (own SVG icons); in-app camera media stays inside the app (never added to phone gallery)
GTV = "TMessagesProj/src/main/java/org/telegram/ui/Components/glass/GlassTabView.java"
PHL = "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertPhotoLayout.java"
AU = "TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java"
for name, body in (("aero_tab_camera", "M4,8.5A2.5,2.5 0 0 1 6.5,6h1.6l1.1,-1.6A1.5,1.5 0 0 1 10.4,3.7h3.2a1.5,1.5 0 0 1 1.2,0.7L15.9,6h1.6A2.5,2.5 0 0 1 20,8.5v8A2.5,2.5 0 0 1 17.5,19h-11A2.5,2.5 0 0 1 4,16.5zM12,9.2a3.4,3.4 0 1 0 0,6.8a3.4,3.4 0 0 0 0,-6.8z"),
                   ("aero_tab_video", "M4.5,6.5h9A2.5,2.5 0 0 1 16,9v6a2.5,2.5 0 0 1 -2.5,2.5h-9A2.5,2.5 0 0 1 2,15V9a2.5,2.5 0 0 1 2.5,-2.5zM17.5,10.2l4,-2.4a0.6,0.6 0 0 1 0.9,0.5v7.4a0.6,0.6 0 0 1 -0.9,0.5l-4,-2.4z")):
    (SRC / f"TMessagesProj/src/main/res/drawable/{name}.xml").write_text(
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:height="24dp" android:width="24dp" android:viewportHeight="24" android:viewportWidth="24">\n'
        f'    <path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="{body}"/>\n</vector>\n', encoding="utf-8")
edit2(GTV, r'(        LINK\(TabAnimationType\.STATIC, R\.drawable\.tabs_link_24\),\n)',
      lambda m: m.group(1) + "        AERO_CAMERA(TabAnimationType.STATIC, R.drawable.aero_tab_camera),\n        AERO_VIDEO(TabAnimationType.STATIC, R.drawable.aero_tab_video),\n")
edit2(ATT, r'(        private int documentButton;\n)', lambda m: m.group(1) + "        private int aeroCameraButton = -1;\n        private int aeroVideoButton = -1;\n")
edit2(ATT, r'(            buttonsCount = 0;\n            galleryButton = -1;\n)', lambda m: m.group(1) + "            aeroCameraButton = -1;\n            aeroVideoButton = -1;\n")
edit2(ATT, r'(                documentButton = buttonsCount\+\+;\n)(                if \(MessagesController\.getInstance\(currentAccount\)\.config\.walletAvailable)',
      lambda m: m.group(1) + "                if (photosEnabled) {\n                    aeroCameraButton = buttonsCount++;\n                }\n                if (videosEnabled) {\n                    aeroVideoButton = buttonsCount++;\n                }\n" + m.group(2))
edit2(ATT, r'(                    \} else if \(position == moneyButton\) \{\n)',
      lambda m: ('                    } else if (position == aeroCameraButton) {\n                        attachButton.setTextAndIcon(101, "Camera", GlassTabView.TabAnimation.AERO_CAMERA);\n                        attachButton.setTag(101);\n'
                 '                    } else if (position == aeroVideoButton) {\n                        attachButton.setTextAndIcon(102, "Video", GlassTabView.TabAnimation.AERO_VIDEO);\n                        attachButton.setTag(102);\n') + m.group(1))
edit2(ATT, r'(                \} else if \(num == LAYOUT_TYPE_MONEY\) \{\n)',
      lambda m: ('                } else if (num == 101 || num == 102) {\n                    showLayout(photoLayout);\n                    photoLayout.aeroOpenCamera(num == 102);\n') + m.group(1))
edit2(PHL, r'(    public void checkCamera\(boolean request\) \{)',
      lambda m: """    // AeroGram: open the in-app camera straight from the attach menu
    public void aeroOpenCamera(boolean video) {
        AndroidUtilities.runOnUIThread(() -> {
            if (parentAlert.destroyed) return;
            if (cameraView == null) checkCamera(true);
            openCameraWithPermissionCheck();
            if (video) {
                android.widget.Toast.makeText(getContext(), "Hold the shutter button to record video", android.widget.Toast.LENGTH_SHORT).show();
            }
        }, 400);
    }

""" + m.group(1))
# camera captures are not added to the phone gallery and are stored in the app's private (.nomedia) cache
edit2(PHL, r'                        AndroidUtilities\.addMediaToGallery\(entry\.path\);\n', lambda m: "                        // AeroGram: keep camera media inside the app\n")
edit2(PHL, r'                    AndroidUtilities\.addMediaToGallery\(currentPicturePath\);\n', lambda m: "                    // AeroGram: keep camera media inside the app\n")
edit2(AU, r'File publicDir = FileLoader\.getDirectory\(FileLoader\.MEDIA_DIR_IMAGE_PUBLIC\);', lambda m: "File publicDir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_IMAGE);")
edit2(AU, r'(    private static File getAlbumDir\(boolean secretChat\) \{\n)', lambda m: m.group(1) + "        if (true) return FileLoader.getDirectory(FileLoader.MEDIA_DIR_VIDEO);\n")

# 13) AeroGram appearance: side menu (hamburger), Theme screen, Edit profile screen
#     (own classes live in scripts/aero/*.java and are copied into org.telegram.ui)
AERO_SRC = pathlib.Path(__file__).resolve().parent / "aero"
UI_DIR = SRC / "TMessagesProj/src/main/java/org/telegram/ui"
for jf in ("AeroPinPad.java", "AeroPasscode.java", "AeroPasscodeActivity.java", "AeroUi.java", "AeroPrefs.java", "AeroDrawer.java", "AeroThemeActivity.java", "AeroEditProfileActivity.java", "AeroSoundActivity.java", "AeroVoiceBar.java", "AeroPrivacyKit.java", "AeroPrivacyActivity.java", "AeroDevicesActivity.java", "AeroPasskeysActivity.java", "AeroDownloadsActivity.java", "AeroUploadProgress.java", "AeroTyping.java", "AeroDisplayActivity.java", "AeroNotify.java", "AeroNotifyActivity.java", "AeroCallBubble.java", "AeroPermissions.java"):
    (UI_DIR / jf).write_text((AERO_SRC / jf).read_text(encoding="utf-8"), encoding="utf-8")
print("ok  aero appearance classes copied")

# hamburger icon on the main chat list (only the plain list: not search/folder/select/community)
edit2(DLG, r'(            if \(searchString != null \|\| folderId != 0 \|\| communityId != 0\) \{\n                actionBar\.setBackButtonDrawable\(backDrawable = new BackDrawable\(false\)\);\n            \})',
      lambda m: m.group(1) + " else if (initialDialogsType == DIALOGS_TYPE_DEFAULT) {\n                actionBar.setBackButtonDrawable(new MenuDrawable());\n            }")
# tapping it opens the side menu
edit2(DLG, r'(                    \} else if \(onlySelect \|\| folderId != 0 \|\| communityId != 0\) \{\n                        finishFragment\(\);\n                    \})',
      lambda m: m.group(1) + " else if (searchString == null && initialDialogsType == DIALOGS_TYPE_DEFAULT) {\n                        AeroDrawer.show(DialogsActivity.this);\n                    }")

# chat wallpaper chosen in the Theme screen
edit2(CHAT, r'isCompactMode\(\) \? contentView\.getCompactBackground\(\) : Theme\.getCachedWallpaper\(\);',
      lambda m: 'isCompactMode() ? contentView.getCompactBackground() : AeroPrefs.overrideWallpaper(Theme.getCachedWallpaper());')

# a wallpaper set for one chat wins; otherwise the Theme screen wallpaper is drawn
edit2(CHAT, r'Drawable drawable = themeDelegate\.getWallpaperDrawable\(\);\n            return drawable != null \? drawable : super\.getNewDrawable\(\);',
      lambda m: 'Drawable drawable = themeDelegate.getWallpaperDrawable();\n            if (drawable == null) drawable = super.getNewDrawable();\n            return themeDelegate.backgroundDrawable == null ? AeroPrefs.overrideWallpaper(drawable) : drawable;')

# 14) voice message recording UI: mic circle, waves, cancel text and send button follow the Aero accent colour
EVF = SRC / EV
t = EVF.read_text(encoding="utf-8")
t, n1 = re.subn(r'getThemedColor\(Theme\.key_chat_messagePanelVoiceBackground\)', 'org.telegram.ui.AeroPrefs.accent()', t)
t, n2 = re.subn(r'getThemedColor\(Theme\.key_chat_recordVoiceCancel\)', 'org.telegram.ui.AeroPrefs.accent()', t)
t, n3 = re.subn(r'backgroundPaint\.setColor\(Theme\.getColor\(Theme\.key_chat_messagePanelSend, resourcesProvider\)\);', 'backgroundPaint.setColor(org.telegram.ui.AeroPrefs.accent());', t)
if n1 < 4 or n2 < 1 or n3 < 1: sys.exit(f"FAIL: voice recolor patterns changed ({n1},{n2},{n3})")
EVF.write_text(t, encoding="utf-8")
print(f"ok  {EV}: voice UI recolored ({n1}+{n2}+{n3})")

# 14b) voice recording bar (hold: release to send / swipe to cancel, tap: delete / pause / send)
edit2(EV, r'(    private Runnable recordAudioVideoRunnable = new Runnable\(\) \{)',
      lambda m: "    private org.telegram.ui.AeroVoiceBar aeroVoice;\n\n" + m.group(1))
edit2(EV, r'(            public boolean onTouchEvent\(MotionEvent motionEvent\) \{\n)(?=(?:(?!public boolean onTouchEvent).)*?delegate\.needStartRecordAudio)',
      lambda m: m.group(1) + """                aeroVoice = org.telegram.ui.AeroVoiceBar.onMicTouch(aeroVoice, motionEvent, this, textFieldContainer, parentFragment,
                        !isStories && !recordingAudioVideo && !isInVideoMode() && !audioVideoButtonContainerForbidden && editingMessageObject == null && audioToSend == null,
                        new org.telegram.ui.AeroVoiceBar.Host() {
                            @Override
                            public MessageObject reply() {
                                return replyingMessageObject;
                            }

                            @Override
                            public org.telegram.tgnet.tl.TL_stories.StoryItem story() {
                                return delegate != null ? delegate.getReplyToStory() : null;
                            }

                            @Override
                            public void beforeRecord() {
                                if (delegate != null) delegate.onPreAudioVideoRecord();
                            }

                            @Override
                            public void sent() {
                                if (delegate != null) delegate.onMessageSend(null, true, 0, 0, 0);
                            }
                        });
                if (aeroVoice != null) {
                    return true;
                }
""")
edit2(EV, r'(        \} else if \(id == NotificationCenter\.recordResumed\) \{\n)',
      lambda m: m.group(1) + "            if (org.telegram.ui.AeroVoiceBar.isActive()) return;\n")

# 15) 6-digit passcode lock: ask on cold start and when returning from background
LA = "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
edit2(LA, r'(        super\.onPause\(\);\n        isResumed = false;\n)', lambda m: m.group(1) + "        AeroPasscode.onPause();\n")
edit2(LA, r'(        super\.onResume\(\);\n        WidgetMedia\.onAppPaused\(false\);\n)', lambda m: m.group(1) + "        AeroPasscode.onResume(this);\n")

# 16) chat folders: remove every way to create a new folder (existing folders keep working)
ui = "TMessagesProj/src/main/java/org/telegram/ui/"
# long-press selection menu and chat preview menu: no "Add to folder"
edit2(DLG, r'if \(folderId == 1 \|\| (filterTabsView != null && getFilterTabsVisibilityFactor)', lambda m: 'if (false && (folderId == 1) || false && ' + m.group(1))
edit2(DLG, r'boolean hasFolders = communityId == 0 &&', lambda m: 'boolean hasFolders = false && communityId == 0 &&')
# long-press on the "All chats" tab: no "Edit folders" (it leads to the create screen)
edit2(DLG, r'\.add\(R\.drawable\.msg_edit, (defaultTab \? LocaleController\.getString\(R\.string\.FilterEditAll\))',
      lambda m: '.addIf(!defaultTab, R.drawable.msg_edit, ' + m.group(1))
# no "try folders" hint
edit2(DLG, r'(    private void showFiltersHint\(\) \{\n)', lambda m: m.group(1) + "        if (true) return;\n")
# no "Chat Folders" entry in settings and settings search
sub_file(ui + "SettingsActivity.java", r'\n[ \t]*items\.add\(SettingCell\.Factory\.of\(7, [^\n]*SettingsFolders[^\n]*', '')
sub_file(ui + "ProfileActivity.java", r'filtersRow = rowCount\+\+;', 'filtersRow = -1;')
sub_file(ui + "ProfileActivity.java", r'\n[ \t]*new SearchResult\(70[01], [^\n]*FiltersSetupActivity[^\n]*', '')
# safety net for deep links and other entry points: the folder setup screen never opens
edit2(ui + "FiltersSetupActivity.java", r'(public boolean onFragmentCreate\(\) \{\n)', lambda m: m.group(1) + "        if (true) return false; // AeroGram: folder creation removed\n")

# 17) links: always open in the phone's own browser (no in-app browser, no custom tab); link colour follows the Aero accent
MC = "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java"
sub_file(MC, r'(public boolean isWebBrowserOpenInApp\(String url\) \{\n)[^\n]*\n', lambda m: m.group(1) + "        return false; // AeroGram: external browser only\n")
sub_file(MC, r'(public boolean isWebBrowserUseCustomTabs\(\) \{\n)[^\n]*\n', lambda m: m.group(1) + "        return false; // AeroGram: plain system browser\n")
sub_file("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java",
         r'(    private static int getColorFromTable\(SparseIntArray currentColors, int key, boolean\[\] isDefault\) \{\n)',
         lambda m: m.group(1) + "        { int aeroLink = org.telegram.ui.AeroPrefs.linkOverride(key); if (aeroLink != 0) return aeroLink; }\n")

# 18) Downloads: icon at the top right of the chat list opens AeroDownloadsActivity
edit2(DLG, r'(            downloadsItem\.setVisibility\(View\.GONE\);\n)',
      lambda m: m.group(1) + "            ActionBarMenuItem aeroDownloadsItem = menu.addItem(9010, R.drawable.msg_download);\n            aeroDownloadsItem.setContentDescription(\"Downloads\");\n")
edit2(DLG, r'(                \} else if \(id == 3\) \{\n                    showSearch\(true, true, true\);)',
      lambda m: "                } else if (id == 9010) {\n                    presentFragment(new AeroDownloadsActivity());\n" + m.group(1))

# 19) typing indicator: bouncing-dot wave (chat header + chat list). The stock draw() is skipped when AeroTyping draws.
TD = "TMessagesProj/src/main/java/org/telegram/ui/Components/TypingDotsDrawable.java"
edit2(TD, r'(public void draw\((?:@NonNull\s+)?Canvas canvas\) \{\n)',
      lambda m: m.group(1) + "        if (org.telegram.ui.AeroTyping.draw(this, canvas)) return;\n")
# remember the colour the drawable was given (so the dots match the status text); optional
sub_file(TD, r'(public void setColor\(int color\) \{\n)',
         lambda m: m.group(1) + "        org.telegram.ui.AeroTyping.setColor(this, color);\n", required=False)

# 20) display size: scale density for the whole app (AeroPrefs.displayScale, set from the side menu: Text & display size)
LAUNCH = SRC / (ui + "LaunchActivity.java")
_lt = LAUNCH.read_text(encoding="utf-8")
_m = re.search(r'void attachBaseContext\((?:final\s+)?(?:android\.content\.)?Context (\w+)\)\s*\{\n', _lt)
if _m:
    _v = _m.group(1)
    _lt = _lt[:_m.end()] + f"        {_v} = org.telegram.ui.AeroPrefs.scaleContext({_v});\n" + _lt[_m.end():]
    print("ok  LaunchActivity: existing attachBaseContext wrapped")
else:
    _lt, _n = re.subn(r'(public class LaunchActivity extends [^{]*\{\n)',
                      lambda m: m.group(1) + "    @Override\n    protected void attachBaseContext(android.content.Context aeroBase) {\n        super.attachBaseContext(org.telegram.ui.AeroPrefs.scaleContext(aeroBase));\n    }\n\n",
                      _lt, count=1)
    if _n != 1: sys.exit("FAIL: LaunchActivity class declaration not found")
    print("ok  LaunchActivity: attachBaseContext added")
LAUNCH.write_text(_lt, encoding="utf-8")

# 21) premium icon set: our own aero_ic_* vectors (24dp, rounded line style with a soft duotone fill, tinted by the app)
RES = SRC / "TMessagesProj/src/main/res"
ICONS = AERO_SRC / "res" / "drawable"
(RES / "drawable").mkdir(parents=True, exist_ok=True)
_n = 0
for _f in sorted(ICONS.glob("aero_ic_*.xml")):
    (RES / "drawable" / _f.name).write_text(_f.read_text(encoding="utf-8"), encoding="utf-8")
    _n += 1
print(f"ok  {_n} aero_ic_* icons installed")
# the same icons replace Telegram's own icon of that name everywhere (old PNG/XML of any density removed first),
# so the whole app uses one consistent set. Names that do not exist upstream are simply unused.
CORE_ICONS = {
    "ic_ab_back": "back", "ic_ab_search": "search", "ic_ab_other": "dots", "ic_close_white": "close",
    "msg_edit": "edit", "msg_theme": "palette", "msg_download": "download", "msg_notifications": "bell",
    "msg_videocall": "video", "msg_calls": "phone", "ic_call": "phone", "msg_secret": "lock", "msg2_secret": "shield",
    "msg_delete": "trash", "msg_shareout": "share", "msg_openin": "external", "msg_background": "image",
    "msg_pin_code": "keypad", "msg2_permissions": "key", "msg2_devices": "devices", "send_plane_24": "send",
}
_r = 0
for _core, _ours in CORE_ICONS.items():
    for _old in RES.glob(f"drawable*/{_core}.*"):
        _old.unlink()
        _r += 1
    (RES / "drawable" / f"{_core}.xml").write_text((ICONS / f"aero_ic_{_ours}.xml").read_text(encoding="utf-8"), encoding="utf-8")
print(f"ok  {len(CORE_ICONS)} core icons replaced ({_r} old files removed)")

# 22) English: switch the app language to English once (first launch after install)
edit2(LA, r'(        AeroPasscode\.onResume\(this\);\n)', lambda m: m.group(1) + "        AeroPrefs.ensureEnglish();\n")

# 23) long-press menu on a message: only Pin / Copy / Reply / Delete (everything else is filtered out of the popup)
_cp = SRC / CHAT
_ct = _cp.read_text(encoding="utf-8")
_names = set(re.findall(r'\bOPTION_([A-Z0-9_]+)\s*=', _ct))
for _n in ("REPLY", "COPY", "DELETE"):
    if _n not in _names: sys.exit(f"FAIL: ChatActivity.OPTION_{_n} not found - source structure changed")
_allowed = sorted(n for n in _names if n in ("REPLY", "COPY", "DELETE") or re.fullmatch(r'(UN)?PIN(_MESSAGE)?', n))
if not any("PIN" in n for n in _allowed): print("WARN: no OPTION_PIN found - Pin will not be in the menu")
_cm = re.search(r'private void createMenu\(', _ct)
if not _cm: sys.exit("FAIL: ChatActivity.createMenu not found")
_loop = re.compile(r'\n([ \t]*)(for \(int a = 0, N = items\.size\(\); a < N; a\+\+\) \{)')
_lm = _loop.search(_ct, _cm.end())
if not _lm: sys.exit("FAIL: createMenu item loop not found - source structure changed")
_cond = " && ".join(f"aeroO != OPTION_{n}" for n in _allowed)
_ind = _lm.group(1)
_code = (f"\n{_ind}// AeroGram: message long-press menu = Pin, Copy, Reply, Delete only\n"
         f"{_ind}for (int aeroI = options.size() - 1; aeroI >= 0; aeroI--) {{\n"
         f"{_ind}    int aeroO = options.get(aeroI);\n"
         f"{_ind}    if ({_cond}) {{\n"
         f"{_ind}        items.remove(aeroI);\n{_ind}        options.remove(aeroI);\n{_ind}        icons.remove(aeroI);\n"
         f"{_ind}    }}\n{_ind}}}\n"
         f"{_ind}if (options.isEmpty()) return;\n")
_ct = _ct[:_lm.start()] + _code + _ct[_lm.start() + 0:]
_cp.write_text(_ct, encoding="utf-8")
print("ok  ChatActivity: long-press menu limited to", ", ".join(_allowed))

# 24) launcher icon: replace every ic_launcher* (all densities, round / alternate icons, adaptive xml) with our icon
ICON_DIR = AERO_SRC / "res" / "icon"
DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
_stems_png, _stems_xml, _removed = set(), set(), 0
for _f in list(RES.glob("mipmap*/ic_launcher*")) + list(RES.glob("drawable*/ic_launcher*")):
    _stems_xml.add(_f.stem) if "anydpi" in _f.parent.name else _stems_png.add(_f.stem)
_stems_png.add("ic_launcher"); _stems_png.add("ic_launcher_round")
_stems_xml.add("ic_launcher"); _stems_xml.add("ic_launcher_round")
for _f in list(RES.glob("mipmap*/ic_launcher*")):
    _f.unlink(); _removed += 1
for _stem in sorted(_stems_png | _stems_xml):
    if _stem.endswith("_foreground") or _stem.endswith("_background"): continue
    for _d in DENS:
        (RES / f"mipmap-{_d}").mkdir(parents=True, exist_ok=True)
        (RES / f"mipmap-{_d}" / f"{_stem}.png").write_bytes((ICON_DIR / f"ic_launcher_{_d}.png").read_bytes())
(RES / "mipmap-xxxhdpi" / "aero_launcher_fg.png").write_bytes((ICON_DIR / "ic_launcher_fg.png").read_bytes())
(RES / "values").mkdir(parents=True, exist_ok=True)
(RES / "values" / "aero_launcher_colors.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="aero_launcher_bg">#2A0070</color></resources>\n', encoding="utf-8")
(RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
_adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
             '    <background android:drawable="@color/aero_launcher_bg"/>\n'
             '    <foreground android:drawable="@mipmap/aero_launcher_fg"/>\n</adaptive-icon>\n')
for _stem in sorted(_stems_xml):
    if _stem.endswith("_foreground") or _stem.endswith("_background"): continue
    (RES / "mipmap-anydpi-v26" / f"{_stem}.xml").write_text(_adaptive, encoding="utf-8")
print(f"ok  launcher icon replaced ({_removed} old files removed, {len(_stems_png | _stems_xml)} icon name(s))")

# 25) bundled chat wallpapers (lavender / pink): only available in Theme > Wallpaper, never applied by default
(RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
for _w in sorted((AERO_SRC / "res" / "wall").glob("aero_wall_*.jpg")):
    (RES / "drawable-nodpi" / _w.name).write_bytes(_w.read_bytes())
print("ok  bundled chat wallpapers installed")

# 26) smaller APK: keep only the CPU types in ABIS (default arm64-v8a + armeabi-v7a = nearly every phone since 2015).
# x86 / x86_64 are only for emulators/Chromebooks and each ABI adds ~15-20 MB of native libraries.
ABIS = [a.strip() for a in os.environ.get("ABIS", "arm64-v8a").split(",") if a.strip()]
for a in ABIS: assert a in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"), a
_abi_list = ", ".join(f"'{a}'" for a in ABIS)
_hits = 0
for _g in ("TMessagesProj/build.gradle", "TMessagesProj/build.gradle.kts",
           "TMessagesProj_AppStandalone/build.gradle", "TMessagesProj_AppStandalone/build.gradle.kts"):
    _hits += sub_file(_g, r"(abiFilters\s*=?\s*(?:\+=\s*)?(?:setOf\(|listOf\()?\s*)(?:[\"'][\w-]+[\"']\s*,?\s*)+", lambda m: m.group(1) + _abi_list + " ", required=False)
    _hits += sub_file(_g, r"(include\s+)(?:[\"'][\w-]+[\"']\s*,?\s*)+(?=\n\s*(?:universalApk|\}))", lambda m: m.group(1) + _abi_list + "\n", required=False)
    # shrink unused resources wherever code shrinking is already on
    sub_file(_g, r"(minifyEnabled\s*=?\s*true\b)(?![^\n]*\n\s*shrinkResources)", lambda m: m.group(1) + "\n            shrinkResources true", required=False)
print(f"ok  ABIs limited to {ABIS} ({_hits} gradle spot(s))" if _hits else "WARN: no abiFilters found - APK keeps all ABIs (check the gradle files)")

# 27) notification types (messages / voice / audio calls / video calls), switches live in AeroNotifyActivity
sub_file("TMessagesProj/src/main/java/org/telegram/messenger/NotificationsController.java",
         r"(public void processNewMessages\(ArrayList<MessageObject> (\w+), boolean \w+, boolean \w+, CountDownLatch (\w+)\) \{\n)",
         lambda m: m.group(1) + f"        if (org.telegram.ui.AeroNotify.filter({m.group(2)}, {m.group(3)})) return; // AeroGram: notification types\n")
_ring = sub_file("TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java",
         r"(public void startRinging\(\) \{\n)",
         lambda m: m.group(1) + "        if (org.telegram.ui.AeroNotify.silentRing(privateCall != null && privateCall.video)) return; // AeroGram: calls switched off do not ring\n",
         required=False)
if not _ring: print("WARN: VoIPService.startRinging not found - call switches only hide missed-call alerts")

# 28) "Hide notification content" (default ON): enforced on every resume so it also covers new accounts
edit2(LA, r'(        AeroPrefs\.ensureEnglish\(\);\n)', lambda m: m.group(1) + "        AeroNotify.enforce();\n")

# 29) floating video-call bubble: show when the app goes to the background, remove when it comes back
edit2(LA, r'(        AeroNotify\.enforce\(\);\n)', lambda m: m.group(1) + "        AeroCallBubble.onAppResumed();\n")
_bp = sub_file(LA, r"(protected void onPause\(\) \{\n\s*super\.onPause\(\);\n)",
               lambda m: m.group(1) + "        AeroCallBubble.onAppPaused();\n", required=False)
if not _bp: print("WARN: LaunchActivity.onPause not found - floating video bubble will not show")
_man = SRC / "TMessagesProj/src/main/AndroidManifest.xml"
_mt = _man.read_text(encoding="utf-8")
if "android.permission.SYSTEM_ALERT_WINDOW" not in _mt:
    _mt, _n = re.subn(r"(<uses-permission )", '<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>\n    \\1', _mt, count=1)
    if _n: _man.write_text(_mt, encoding="utf-8"); print("ok  manifest: SYSTEM_ALERT_WINDOW added")
    else: print("WARN: could not add SYSTEM_ALERT_WINDOW to the manifest")

# 30) permissions: ask everything on first launch, ask again at use time (voice, notifications, floating video call)
edit2(LA, r'(        AeroCallBubble\.onAppResumed\(\);\n)', lambda m: m.group(1) + "        AeroPermissions.onResume(this);\n")
_pr = sub_file(LA, r"(public void onRequestPermissionsResult\(int (\w+), String\[\] \w+, int\[\] \w+\) \{\n)",
               lambda m: m.group(1) + f"        org.telegram.ui.AeroPermissions.onResult(this, {m.group(2)});\n", required=False)
if not _pr: print("WARN: LaunchActivity.onRequestPermissionsResult not found - the overlay explanation will not follow the first-launch questions")

# 31) smallest safe APK
# a) code shrinking + resource shrinking on every non-debug build type
for _g in ("TMessagesProj/build.gradle", "TMessagesProj_AppStandalone/build.gradle"):
    sub_file(_g, r"minifyEnabled\s+false", "minifyEnabled true", required=False)
    sub_file(_g, r"(minifyEnabled\s+true\b)(?![^\n]*\n\s*shrinkResources)", lambda m: m.group(1) + "\n            shrinkResources true", required=False)
# b) keep only English strings and one screen density (phones scale the nearest one; saves a lot of PNGs)
sub_file("TMessagesProj/build.gradle", r"(defaultConfig\s*\{\n)", lambda m: m.group(1) + '        resConfigs "en", "xxhdpi"\n', required=False)
# c) no native debug symbols
sub_file("TMessagesProj/build.gradle", r"(defaultConfig\s*\{\n)", lambda m: m.group(1) + "        ndk { debugSymbolLevel 'NONE' }\n", required=False)
# d) native libraries stored compressed inside the APK
_mf = SRC / "TMessagesProj/src/main/AndroidManifest.xml"
_mt = _mf.read_text(encoding="utf-8")
if "android:extractNativeLibs" in _mt:
    _mt = re.sub(r'android:extractNativeLibs="false"', 'android:extractNativeLibs="true"', _mt)
else:
    _mt = re.sub(r"(<application\b)", r'\1 android:extractNativeLibs="true"', _mt, count=1)
_mf.write_text(_mt, encoding="utf-8")
# e) R8 must not rename what AeroGram reaches by reflection (floating video call bubble)
_pg = SRC / "TMessagesProj/proguard-rules.pro"
_pg.parent.mkdir(parents=True, exist_ok=True)
with open(_pg, "a", encoding="utf-8") as _f:
    _f.write("\n# AeroGram: reflection targets\n"
             "-keep class org.telegram.messenger.voip.VoIPService { *; }\n"
             "-keep class org.telegram.ui.Components.voip.VoIPTextureView { *; }\n"
             "-keep class org.telegram.tgnet.TLRPC$PhoneCall { *; }\n"
             "-keep class org.telegram.tgnet.TLRPC$TL_phoneCall* { *; }\n"
             "-keep class org.telegram.ui.Aero* { *; }\n")
print("ok  size: shrink + en/xxhdpi + no debug symbols + compressed libs + keep rules")

print("PATCH DONE:", V)

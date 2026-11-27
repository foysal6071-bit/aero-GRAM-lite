# AeroGram – Telegram fork (Audio / Video calls)

Builds the official Telegram Android (DrKLO) source and patches it at build time:
- Separate app IDs: `org.aerogram.audio` / `org.aerogram.video` (install side by side)
- Your own `api_id` / `api_hash`, app name "我的爱" (change `APP_NAME` in the workflow)
- **audio**: every way to start a video call is blocked (`VoIPHelper.startCall`); **video**: audio and video calls
- 64-bit APK by default (arm64-v8a only; set `ABIS` in the workflow for universal or 32-bit) with code + resource shrinking, English strings, xxhdpi only, Zopfli recompression. For a smaller APK set `ABIS` in the workflow to one CPU type (`armeabi-v7a` = 32-bit phones, `arm64-v8a` = 64-bit phones)
- App language is switched to English on first launch (you can still change it in Telegram's language settings)

## Setup
1. Push these files to the root of a GitHub repo.
2. Settings → Secrets → Actions (all optional; without `TG_API_ID` / `TG_API_HASH` the source defaults are used and login only needs phone number + code): `TG_API_ID`, `TG_API_HASH`, `GOOGLE_SERVICES_JSON`, `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`.
3. Actions → Run workflow → `video` / `audio` / `both`.
4. The APK appears under Releases (without a keystore an auto-generated key signs it; use your own keystore for updates).

## Side menu (hamburger) – settings, grouped
| Group | Item |
|---|---|
| ACCOUNT | Edit profile (name, live username check) |
| APPEARANCE | Theme (Light / Dark / Auto, accent, wallpaper, opacity) · Text & display size |
| NOTIFICATIONS | Floating video bubble (imo style, ON by default) · Hide notification content (switch, ON by default) · Notification types (switches: messages · voice messages · audio calls · video calls) · Sounds & ringtone (call ringtone, notification sound, in-chat sounds) |
| PRIVACY & SECURITY | Privacy (password, passkeys, logged-in devices) · Passcode (6-digit lock) |
| DATA | Downloads (all downloaded photos / videos / audio / files) |

- **Text & display size**: text size 12–30 applies to chats immediately; display size 80–130% scales the whole app (tap Apply, the screen restarts once).

## Chat
- Upload progress: animated bar + live percent pill under every photo / video / file / voice message while it is being sent (`AeroUploadProgress.java`)
- Chat wallpapers: 3 gradients, your own photo, plus two bundled picture wallpapers (lavender, pink) in Theme > Wallpaper. Nothing is applied until you tap one; picking a picture sets opacity to 100%.
- Typing indicator: modern springy three-dot jump (glow + landing squash) in the chat header and chat list (`AeroTyping.java`)
- Permissions: asked once on first launch (microphone, camera, contacts, media, notifications, Bluetooth, then "Display over other apps"); if denied, asked again when the feature is used (voice message, notification screen, video call). Permanently denied -> a dialog opens the app settings (`AeroPermissions.java`)
- Video call + app in background: the other person floats on screen (needs "Display over other apps"); tap = expand / hang up buttons, drag = move, throw down = end call (`AeroCallBubble.java`)
- Long-press a message: the menu has only Pin, Copy, Reply and Delete
- Messenger-style message status (private chats), quick emoji, voice bar (hold to send, swipe left to cancel), per-chat wallpaper
- Chat folder creation is removed (existing folders keep working); links open in the phone's own browser

## App icon
The launcher icon comes from `scripts/aero/res/icon/` (all densities + adaptive foreground, background #2A0070). Replace those PNGs to change it.

## Icons
One consistent premium icon set: 24dp rounded line icons with a soft duotone fill (`scripts/aero/res/drawable/aero_ic_*.xml`). They are used on all AeroGram screens and also replace Telegram's icon of the same name for the most visible places (back, search, menu, close, edit, theme, download, notifications, video, call, lock, shield, delete, share, …). Add more by extending `CORE_ICONS` in `scripts/patch_telegram.py`.

## Known limitations
- Passkey add is tied to the official Telegram app signature; adding can fail in a fork (list / remove work).
- The patch is regex based; if the upstream structure changes the build fails on purpose instead of producing an unpatched APK.
- The audio variant may still show the video button; tapping it starts an audio call. Incoming video calls from others are not blocked separately.
- Without `google-services.json` background push notifications may be unreliable.
- GPLv2: keep the source open, do not use Telegram's name or logo, keep the "unofficial" notice.
- A build takes about 1–2 hours.

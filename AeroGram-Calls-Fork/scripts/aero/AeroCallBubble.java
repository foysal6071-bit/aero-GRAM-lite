package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.telegram.messenger.AndroidUtilities.dp;

/**
 * AeroGram: floating video-call bubble (imo style). While a video call is running and the app goes to the
 * background, the other person's video floats on top of every app.
 *   tap          -> shows [expand] and [hang up] buttons (hide again on tap / after a few seconds)
 *   drag         -> move it, it snaps to the left or right edge
 *   throw down   -> ends the call
 * Needs "Display over other apps". Everything that touches Telegram's call classes uses reflection, so a changed
 * upstream signature can never break the build; the bubble then simply stays plain (no video) or does not show.
 */
public class AeroCallBubble {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static FrameLayout bubble;
    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static LinearLayout controls;
    private static Object renderer, oldLocal, oldRemote;
    private static boolean sinksTaken;

    private static final Runnable SHOW = AeroCallBubble::showNow;
    private static final Runnable HIDE_CONTROLS = () -> {
        if (controls != null) controls.setVisibility(View.GONE);
    };
    private static final Runnable WATCH = new Runnable() {
        @Override
        public void run() {
            if (bubble == null) return;
            if (service() == null) {
                remove(false);
                return;
            }
            H.postDelayed(this, 700);
        }
    };

    // ------------------------------------------------------------ public hooks (LaunchActivity)

    public static void onAppPaused() {
        H.removeCallbacks(SHOW);
        H.postDelayed(SHOW, 350);
    }

    public static void onAppResumed() {
        H.removeCallbacks(SHOW);
        remove(true);
    }

    /** asks for "Display over other apps" (called when the switch is turned on) */
    public static void askPermission(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
                Toast.makeText(ctx, "Allow \"Display over other apps\" for the floating video call", Toast.LENGTH_LONG).show();
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------ reflection helpers

    private static Object service() {
        try {
            Class<?> c = Class.forName("org.telegram.messenger.voip.VoIPService");
            return c.getMethod("getSharedInstance").invoke(null);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Field field(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    private static Method method(Class<?> c, String name, int params) {
        for (; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == params) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** true while a video call is running */
    public static boolean videoCallActive() {
        Object svc = service();
        return svc != null && isVideo(svc);
    }

    private static boolean isVideo(Object svc) {
        try {
            Field f = field(svc.getClass(), "privateCall");
            Object call = f != null ? f.get(svc) : null;
            if (call != null) {
                Field v = field(call.getClass(), "video");
                if (v != null && v.getBoolean(call)) return true;
            }
        } catch (Throwable ignore) {
        }
        try {
            Method m = method(svc.getClass(), "getVideoState", 1);
            if (m != null && ((Number) m.invoke(svc, false)).intValue() == 2) return true;
        } catch (Throwable ignore) {
        }
        return false;
    }

    // ------------------------------------------------------------ show / remove

    private static void showNow() {
        try {
            if (bubble != null || !AeroNotify.on(AeroNotify.BUBBLE)) return;
            Context app = ApplicationLoader.applicationContext;
            if (Build.VERSION.SDK_INT < 26 || !Settings.canDrawOverlays(app)) return;
            Object svc = service();
            if (svc == null || !isVideo(svc)) return;

            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            final int w = dp(112), h = dp(164);
            bubble = new FrameLayout(app);
            bubble.setBackgroundColor(0xFF14102B);
            bubble.setClipToOutline(true);
            bubble.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(18));
                }
            });
            bubble.setElevation(dp(10));

            addVideo(app, svc);
            addControls(app);

            lp = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = app.getResources().getDisplayMetrics().widthPixels - w - dp(12);
            lp.y = dp(110);
            bubble.setOnTouchListener(new Touch());
            wm.addView(bubble, lp);
            H.postDelayed(WATCH, 700);
        } catch (Throwable e) {
            FileLog.e(e);
            bubble = null;
        }
    }

    /** the other person's video, drawn by Telegram's own texture view (found by reflection) */
    private static void addVideo(Context app, Object svc) {
        try {
            Class<?> vc = Class.forName("org.telegram.ui.Components.voip.VoIPTextureView");
            View video = null;
            for (Constructor<?> k : vc.getConstructors()) {
                Class<?>[] t = k.getParameterTypes();
                if (t.length < 1 || t[0] != Context.class) continue;
                boolean ok = true;
                for (int i = 1; i < t.length; i++) if (t[i] != boolean.class) ok = false;
                if (!ok) continue;
                Object[] a = new Object[t.length];
                a[0] = app;
                for (int i = 1; i < t.length; i++) a[i] = Boolean.FALSE;
                video = (View) k.newInstance(a);
                break;
            }
            Field rf = field(vc, "renderer");
            if (video == null || rf == null) return;
            renderer = rf.get(video);
            Method set = method(svc.getClass(), "setSinks", 2);
            if (set == null || renderer == null) return;
            try {
                Field lf = field(svc.getClass(), "localSink"), rmf = field(svc.getClass(), "remoteSink");
                oldLocal = lf != null ? lf.get(svc) : null;
                oldRemote = rmf != null ? rmf.get(svc) : null;
            } catch (Throwable ignore) {
            }
            set.invoke(svc, oldLocal, renderer);
            sinksTaken = true;
            bubble.addView(video, new FrameLayout.LayoutParams(-1, -1));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void addControls(Context app) {
        controls = new LinearLayout(app);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setBackgroundColor(0x66000000);
        controls.setVisibility(View.GONE);
        controls.addView(circle(app, R.drawable.aero_ic_external, 0xFF3A3F55, 0, v -> openApp()), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(40), dp(40));
        hl.leftMargin = dp(14);
        controls.addView(circle(app, R.drawable.aero_ic_phone, 0xFFE5484D, 135, v -> hangUp()), hl);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, dp(62), Gravity.BOTTOM);
        bubble.addView(controls, cl);
    }

    private static View circle(Context app, int icon, int color, float rotation, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(app);
        f.setBackground(AeroUi.round(color, 20));
        ImageView i = new ImageView(app);
        i.setImageResource(icon);
        i.setColorFilter(Color.WHITE);
        i.setRotation(rotation);
        f.addView(i, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        f.setOnClickListener(click);
        return f;
    }

    private static void openApp() {
        try {
            Context app = ApplicationLoader.applicationContext;
            Intent i = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                app.startActivity(i);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void hangUp() {
        try {
            Object svc = service();
            if (svc != null) {
                Method m = method(svc.getClass(), "hangUp", 0);
                if (m != null) m.invoke(svc);
                else Toast.makeText(ApplicationLoader.applicationContext, "Could not end the call from here", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        remove(false);
    }

    private static void remove(boolean restoreSinks) {
        H.removeCallbacks(WATCH);
        H.removeCallbacks(HIDE_CONTROLS);
        try {
            if (sinksTaken) {
                Object svc = service();
                if (svc != null && restoreSinks && (oldLocal != null || oldRemote != null)) {
                    Method set = method(svc.getClass(), "setSinks", 2);
                    if (set != null) set.invoke(svc, oldLocal, oldRemote);
                }
                if (svc != null && restoreSinks && oldLocal == null && oldRemote == null) {
                    Method set = method(svc.getClass(), "setSinks", 2);
                    if (set != null) set.invoke(svc, null, null);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            if (renderer != null) {
                Method rel = method(renderer.getClass(), "release", 0);
                if (rel != null) rel.invoke(renderer);
            }
        } catch (Throwable ignore) {
        }
        try {
            if (bubble != null && wm != null) wm.removeView(bubble);
        } catch (Throwable ignore) {
        }
        bubble = null;
        controls = null;
        renderer = oldLocal = oldRemote = null;
        sinksTaken = false;
    }

    // ------------------------------------------------------------ gestures

    private static class Touch implements View.OnTouchListener {
        private float downX, downY, startX, startY;
        private boolean moved;
        private VelocityTracker vt;
        private final int slop = ViewConfiguration.get(ApplicationLoader.applicationContext).getScaledTouchSlop();

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (bubble == null) return false;
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.abs(dx) + Math.abs(dy) > slop) moved = true;
                    if (moved) {
                        lp.x = Math.round(startX + dx);
                        lp.y = Math.round(startY + dy);
                        try {
                            wm.updateViewLayout(bubble, lp);
                        } catch (Throwable ignore) {
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity(), vy = vt.getYVelocity();
                    vt.recycle();
                    vt = null;
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) return true;
                    int screenH = ApplicationLoader.applicationContext.getResources().getDisplayMetrics().heightPixels;
                    if (!moved) {
                        toggleControls();
                    } else if ((vy > dp(900) && vy > Math.abs(vx) * 1.2f) || lp.y > screenH - dp(120)) {
                        throwDown(screenH);
                    } else {
                        snapToEdge();
                    }
                    return true;
                }
            }
            return true;
        }
    }

    private static void toggleControls() {
        if (controls == null) return;
        H.removeCallbacks(HIDE_CONTROLS);
        if (controls.getVisibility() == View.VISIBLE) {
            controls.setVisibility(View.GONE);
        } else {
            controls.setVisibility(View.VISIBLE);
            H.postDelayed(HIDE_CONTROLS, 3500);
        }
    }

    private static void snapToEdge() {
        final int screenW = ApplicationLoader.applicationContext.getResources().getDisplayMetrics().widthPixels;
        final int target = (lp.x + lp.width / 2 < screenW / 2) ? dp(12) : screenW - lp.width - dp(12);
        ValueAnimator a = ValueAnimator.ofInt(lp.x, target);
        a.setDuration(180);
        a.addUpdateListener(an -> {
            if (bubble == null) return;
            lp.x = (Integer) an.getAnimatedValue();
            try {
                wm.updateViewLayout(bubble, lp);
            } catch (Throwable ignore) {
            }
        });
        a.start();
    }

    /** the bubble flies down off the screen, then the call is ended */
    private static void throwDown(int screenH) {
        ValueAnimator a = ValueAnimator.ofInt(lp.y, screenH + dp(40));
        a.setDuration(220);
        a.addUpdateListener(an -> {
            if (bubble == null) return;
            lp.y = (Integer) an.getAnimatedValue();
            try {
                wm.updateViewLayout(bubble, lp);
            } catch (Throwable ignore) {
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                hangUp();
            }
        });
        a.start();
    }
}

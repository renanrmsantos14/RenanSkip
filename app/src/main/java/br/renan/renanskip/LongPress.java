package br.renan.renanskip;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Usa o "ouvinte de toque longo no volume" do próprio Android (MediaSessionManager, API de
 * sistema). Com ele registrado, o sistema aplica sozinho o toque curto como volume e só
 * entrega ao app o aperto longo, sem mexer no volume. Exige liberar uma vez pelo ADB:
 *   adb shell pm grant br.renan.renanskip android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER
 */
final class LongPress {
    static final String PERMISSION = "android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER";

    interface Callback { boolean onLongPress(KeyEvent e); } // false = devolve o evento ao sistema

    private final MediaSessionManager msm;
    private final AudioManager audio;
    private final Handler handler;
    private final Class<?> listenerType;
    private final Method setListener, dispatch;
    private final Object listener;

    static boolean granted(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** null se não houver permissão ou a API não existir neste Android. */
    static LongPress start(Context c, Handler h, Callback cb) {
        if (!granted(c)) return null;
        try {
            LongPress lp = new LongPress(c, h, cb);
            lp.register(true);
            Log.d("RenanSkip", "modo toque longo ativo");
            return lp;
        } catch (Throwable t) {
            Log.w("RenanSkip", "toque longo indisponível", t);
            return null;
        }
    }

    private LongPress(Context c, Handler h, Callback cb) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("Landroid/media/session/MediaSessionManager");
        msm = c.getSystemService(MediaSessionManager.class);
        audio = c.getSystemService(AudioManager.class);
        handler = h;
        listenerType = Class.forName("android.media.session.MediaSessionManager$OnVolumeKeyLongPressListener");
        setListener = MediaSessionManager.class.getMethod("setOnVolumeKeyLongPressListener", listenerType, Handler.class);
        dispatch = MediaSessionManager.class.getMethod("dispatchVolumeKeyEvent", KeyEvent.class, int.class, boolean.class);
        listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, (p, m, a) -> {
            if ("onVolumeKeyLongPress".equals(m.getName())) {
                KeyEvent e = (KeyEvent) a[0];
                if (!cb.onLongPress(e)) passThrough(e);
                return null;
            }
            if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
            if ("equals".equals(m.getName())) return p == a[0];
            return "LongPressListener";
        });
    }

    private void register(boolean on) throws Exception {
        setListener.invoke(msm, on ? listener : null, on ? handler : null);
    }

    /** Fora das condições (desbloqueado, sem música): o sistema trata a tecla como de costume. */
    private void passThrough(KeyEvent e) {
        try {
            register(false);
            dispatch.invoke(msm, e, AudioManager.USE_DEFAULT_STREAM_TYPE, false);
            register(true);
        } catch (Throwable t) {
            Log.w("RenanSkip", "falha ao repassar tecla", t);
        }
    }

    void stop() {
        try { register(false); } catch (Throwable ignored) { }
    }
}

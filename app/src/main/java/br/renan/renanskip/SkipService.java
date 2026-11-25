package br.renan.renanskip;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;

/**
 * Com a tela desligada o Android não entrega o evento da tecla de volume a apps comuns,
 * mas o volume de mídia continua mudando. Segurar a tecla gera mudanças em sequência rápida
 * (auto-repetição); um toque gera uma só. Detectamos a sequência, trocamos a faixa, vibramos
 * e travamos o volume no valor de antes do aperto enquanto a tecla continuar segurada.
 */
public class SkipService extends Service {
    static final String PREFS = "prefs", KEY_ENABLED = "enabled", KEY_ONLY_LOCKED = "only_locked";
    /** Estado lido pela tela (mesmo processo). */
    static volatile boolean running, longPressActive;
    private static final String ACTION_VOLUME = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";

    private static final long REPEAT_MS = 250;     // intervalo máximo entre passos da auto-repetição
    private static final int REPEATS_TO_FIRE = 1;  // 1ª repetição rápida já conta como "segurado"
    private static final long RESTART_WINDOW_MS = 3000; // players recomeçam a faixa se já passou disso
    private static final long NEW_PRESS_MS = 1000; // pausa que marca um novo aperto
    private static final long SETTLE_MS = 350;     // sem passos por esse tempo = tecla solta

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private long lastEvent, ignoreUntil, lastPreviousAt, lastSkipAt;
    private static final long SKIP_GRACE_MS = 5000;
    private int direction, fastCount, pressStartVolume;
    private boolean fired, longFired;
    private LongPress longPress;
    private final Runnable release = this::onRelease;
    private static final long UPDATE_EVERY_MS = 6 * 60 * 60 * 1000L;
    private final Runnable updateCheck = new Runnable() {
        @Override public void run() {
            new Thread(SkipService.this::notifyUpdate).start();
            handler.postDelayed(this, UPDATE_EVERY_MS);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { onVolume(i); }
    };

    @Override public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        longPress = LongPress.start(this, handler, this::onLongPress);
        running = true;
        longPressActive = longPress != null;
        if (longPress == null) { // sem a permissão do ADB: detecta pela mudança de volume
            IntentFilter f = new IntentFilter(ACTION_VOLUME);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(receiver, f);
        }
        handler.postDelayed(updateCheck, 60_000);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        if (longPress == null && LongPress.granted(this)) { // permissão dada pelo ADB com o serviço rodando
            longPress = LongPress.start(this, handler, this::onLongPress);
            if (longPress != null) unregisterReceiver(receiver);
            longPressActive = longPress != null;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        longPressActive = false;
        if (longPress != null) longPress.stop();
        else unregisterReceiver(receiver);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Modo toque longo: o sistema só entrega o aperto longo; o volume não muda. */
    private boolean onLongPress(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) { boolean was = longFired; longFired = false; return was; }
        if (longFired) return true; // repetições do mesmo aperto, mesmo com o player trocando de faixa
        long now = SystemClock.elapsedRealtime();
        // logo após trocar, o player para por um instante e isMusicActive() fica falso
        boolean playing = audio.isMusicActive() || now - lastSkipAt < SKIP_GRACE_MS;
        if (!playing || !allowedNow()) return false;
        longFired = true;
        lastSkipAt = now;
        boolean next = e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP;
        if (next) sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); else previous();
        vibrate(next);
        return true;
    }

    private void onVolume(Intent i) {
        if (i.getIntExtra(EXTRA_TYPE, -1) != AudioManager.STREAM_MUSIC) return;
        int value = i.getIntExtra(EXTRA_VALUE, -1), prev = i.getIntExtra(EXTRA_PREV, -1);
        if (value < 0 || prev < 0 || value == prev) return;
        long now = SystemClock.elapsedRealtime();
        if (now < ignoreUntil) return; // mudança feita por nós mesmos

        if (fired) { // tecla ainda segurada após disparar: desfaz cada passo na hora
            if (value == pressStartVolume) return; // é o nosso próprio ajuste
            lastEvent = now;
            lockVolume();
            handler.removeCallbacks(release);
            handler.postDelayed(release, SETTLE_MS);
            return;
        }

        int d = Integer.signum(value - prev);
        long gap = now - lastEvent;
        if (d != direction || gap > NEW_PRESS_MS) pressStartVolume = prev;
        fastCount = (d == direction && gap <= REPEAT_MS) ? fastCount + 1 : 0;
        direction = d;
        lastEvent = now;

        if (fastCount >= REPEATS_TO_FIRE && allowedNow()) {
            fired = true;
            lockVolume();
            if (d > 0) sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); else previous();
            vibrate(d > 0);
            handler.postDelayed(release, SETTLE_MS);
        }
    }

    private boolean allowedNow() {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ONLY_LOCKED, true)) return true;
        boolean locked = getSystemService(KeyguardManager.class).isKeyguardLocked();
        boolean screenOff = !getSystemService(PowerManager.class).isInteractive();
        return locked || screenOff;
    }

    private void lockVolume() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, pressStartVolume, 0);
    }

    private void onRelease() {
        ignoreUntil = SystemClock.elapsedRealtime() + 300;
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != pressStartVolume) lockVolume();
        fired = false;
        fastCount = 0;
    }

    /** Players recomeçam a faixa no "anterior" se ela já passou de ~3 s; aí mandamos dois. */
    private void previous() {
        long now = SystemClock.elapsedRealtime();
        boolean justWentBack = now - lastPreviousAt < RESTART_WINDOW_MS;
        lastPreviousAt = now;
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        if (!justWentBack) handler.postDelayed(() -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS), 300);
    }

    private void sendMediaKey(int code) {
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    /** Próxima: uma vibração. Anterior: duas curtas. */
    private void vibrate(boolean next) {
        Vibrator v = getSystemService(Vibrator.class);
        if (v == null) return;
        long[] pattern = next ? new long[]{0, 150} : new long[]{0, 80, 90, 80};
        v.vibrate(VibrationEffect.createWaveform(pattern, -1));
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("svc", "Serviço ativo", NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "svc")
                .setSmallIcon(android.R.drawable.ic_media_next)
                .setContentTitle("RenanSkip ativo")
                .setContentText("Segure volume + para próxima, volume − para anterior")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    /** Avisa por notificação quando há versão nova (uma vez por versão). */
    private void notifyUpdate() {
        try {
            Updater.Release r = Updater.newer();
            android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (r == null || r.version.equals(p.getString("notified", ""))) return;
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("upd", "Atualizações", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            nm.notify(2, new Notification.Builder(this, "upd")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("RenanSkip v" + r.version + " disponível")
                    .setContentText("Toque para atualizar")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build());
            p.edit().putString("notified", r.version).apply();
        } catch (Exception ignored) { } // sem internet: tenta no próximo ciclo
    }

    static void sync(Context c) {
        Intent i = new Intent(c, SkipService.class);
        if (c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)) c.startForegroundService(i);
        else c.stopService(i);
    }
}

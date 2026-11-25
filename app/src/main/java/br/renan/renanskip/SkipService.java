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
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Display;
import android.view.KeyEvent;

import java.util.List;

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

    /*
     * Com a tela 100% apagada o player toca em "offload" (o chip de áudio toca sozinho) e o
     * processador dorme logo após a tecla descer. O sistema só reconhece o toque longo por um
     * temporizador interno, que não dispara com o processador dormindo. Então mantemos o
     * processador acordado só enquanto a tela está apagada e há música tocando.
     */
    private PowerManager.WakeLock awake;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            boolean off = Intent.ACTION_SCREEN_OFF.equals(i.getAction());
            Diag.log(c, off ? "tela apagou" : "tela acendeu");
            updateWakeLock();
            handler.removeCallbacks(heartbeat);
            if (off) handler.postDelayed(heartbeat, HEARTBEAT_MS);
        }
    };
    /*
     * Com a tela 100% apagada (não no AOD) e o ouvinte de toque longo ativo, o HyperOS engole as
     * teclas de volume: nem o ouvinte recebe nem o volume muda. Então soltamos o ouvinte com o
     * display em STATE_OFF e detectamos o aperto longo pela mudança de volume.
     */
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayRemoved(int id) { }
        @Override public void onDisplayChanged(int id) { if (id == Display.DEFAULT_DISPLAY) updateListener(); }
    };

    private void updateListener() {
        if (longPress == null) return;
        Display d = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        boolean on = d == null || d.getState() != Display.STATE_OFF;
        if (on == longPress.enabled()) return;
        longPress.setEnabled(on);
        Diag.log(this, on ? "ouvinte de toque longo ativo" : "tela 100% apagada: ouvinte solto, detecção pelo volume");
    }

    /** Com a tela apagada: se as linhas "vivo" somem ou atrasam, o sistema congelou o app. */
    private static final long HEARTBEAT_MS = 20_000;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            Diag.log(SkipService.this, "vivo · música=" + audio.isMusicActive() + " · wakelock=" + awake.isHeld());
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };
    private final AudioManager.AudioPlaybackCallback playback = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            updateWakeLock();
            // ao pausar, isMusicActive() ainda fica verdadeiro por alguns segundos
            handler.postDelayed(SkipService.this::updateWakeLock, 5000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        longPress = LongPress.start(this, handler, this::onLongPress);
        running = true;
        longPressActive = longPress != null;
        // Mudança de volume: modo principal sem a permissão do ADB. Com a permissão, vale só com a
        // tela 100% apagada, quando o ouvinte de toque longo fica solto (ver updateListener).
        IntentFilter f = new IntentFilter(ACTION_VOLUME);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, f);
        handler.postDelayed(updateCheck, 60_000);

        awake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RenanSkip:teclas");
        awake.setReferenceCounted(false);
        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, sf);
        audio.registerAudioPlaybackCallback(playback, handler);
        getSystemService(DisplayManager.class).registerDisplayListener(displayListener, handler);
        updateListener();
        updateWakeLock();
        Diag.log(this, "serviço iniciou · toque longo=" + (longPress != null) + " · v" + BuildConfig.VERSION_NAME);
    }

    private void updateWakeLock() {
        boolean screenOff = !getSystemService(PowerManager.class).isInteractive();
        boolean need = screenOff && audio.isMusicActive();
        if (need && !awake.isHeld()) { awake.acquire(); Diag.log(this, "wakelock ligado"); }
        else if (!need && awake.isHeld()) { awake.release(); Diag.log(this, "wakelock desligado"); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        if (longPress == null && LongPress.granted(this)) { // permissão dada pelo ADB com o serviço rodando
            longPress = LongPress.start(this, handler, this::onLongPress);
            longPressActive = longPress != null;
            updateListener();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        Diag.log(this, "serviço parou");
        running = false;
        longPressActive = false;
        if (longPress != null) longPress.stop();
        unregisterReceiver(receiver);
        handler.removeCallbacksAndMessages(null);
        audio.unregisterAudioPlaybackCallback(playback);
        getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        unregisterReceiver(screenReceiver);
        if (awake.isHeld()) awake.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Modo toque longo: o sistema só entrega o aperto longo; o volume não muda. */
    private boolean onLongPress(KeyEvent e) {
        boolean handled = handleLongPress(e);
        Diag.log(this, "tecla " + (e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ? "+" : "−")
                + (e.getAction() == KeyEvent.ACTION_DOWN ? " desceu" : " subiu") + " rep=" + e.getRepeatCount()
                + " atraso=" + (SystemClock.uptimeMillis() - e.getEventTime()) + "ms"
                + " música=" + audio.isMusicActive() + " permitido=" + allowedNow()
                + (handled ? " → tratou" : " → volume"));
        return handled;
    }

    private boolean handleLongPress(KeyEvent e) {
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
        boolean interactive = getSystemService(PowerManager.class).isInteractive();
        Diag.log(this, "volume " + i.getIntExtra(EXTRA_PREV, -1) + "→" + i.getIntExtra(EXTRA_VALUE, -1)
                + (interactive ? " · tela acesa" : " · tela apagada"));
        if (longPress != null && longPress.enabled()) return; // tela acesa/AOD: o ouvinte de toque longo cuida
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

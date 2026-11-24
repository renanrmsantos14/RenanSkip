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
import android.media.AudioPlaybackConfiguration;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * Com a tela bloqueada o Android manda as teclas de volume para a sessão de mídia "remota"
 * (como um Chromecast) em vez de mudar o volume. Enquanto a música toca e o celular está
 * bloqueado, mantemos uma sessão assim: toque na tecla = 1 passo de volume (aplicado por nós);
 * tecla segurada (chega auto-repetição) = troca a faixa, sem mexer no volume.
 */
public class SkipService extends Service {
    static final String PREFS = "prefs", KEY_ENABLED = "enabled", KEY_ONLY_LOCKED = "only_locked";

    private static final long TAP_FALLBACK_MS = 700; // sem repetição nem soltura até aqui = toque
    private static final long REASSERT_MS = 3000;    // retoma as teclas se o player tomar a prioridade
    private static final long RESTART_WINDOW_MS = 3000; // players recomeçam a faixa se já passou disso

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private MediaSession session;
    private boolean sessionActive, pressing, fired;
    private int pressDir;
    private long lastPreviousAt;

    private final Runnable tapFallback = () -> { if (pressing && !fired) step(pressDir); pressing = false; };
    private final Runnable reassert = new Runnable() {
        @Override public void run() {
            if (!sessionActive) return;
            claimKeys();
            handler.postDelayed(this, REASSERT_MS);
        }
    };
    private static final long UPDATE_EVERY_MS = 6 * 60 * 60 * 1000L;
    private final Runnable updateCheck = new Runnable() {
        @Override public void run() {
            new Thread(SkipService.this::notifyUpdate).start();
            handler.postDelayed(this, UPDATE_EVERY_MS);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };
    private final AudioManager.AudioPlaybackCallback playback = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            handler.postDelayed(SkipService.this::refresh, 300);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        session = new MediaSession(this, "RenanSkip");
        session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, max,
                audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            @Override public void onAdjustVolume(int direction) { onKey(direction); }
        });
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen, f);
        audio.registerAudioPlaybackCallback(playback, handler);
        handler.postDelayed(updateCheck, 60_000);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        refresh();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        unregisterReceiver(screen);
        audio.unregisterAudioPlaybackCallback(playback);
        handler.removeCallbacksAndMessages(null);
        session.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Só pega as teclas com música tocando e (por padrão) celular bloqueado; fora disso o volume é o normal. */
    private void refresh() {
        boolean onlyLocked = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ONLY_LOCKED, true);
        boolean locked = getSystemService(KeyguardManager.class).isKeyguardLocked()
                || !getSystemService(PowerManager.class).isInteractive();
        boolean want = audio.isMusicActive() && (!onlyLocked || locked);
        if (want == sessionActive) return;
        sessionActive = want;
        Log.d("RenanSkip", "sessão ativa=" + want);
        session.setActive(want);
        handler.removeCallbacks(reassert);
        if (want) handler.post(reassert);
    }

    /** Sessão "tocando" vai para o topo da fila de volume; a troca PAUSED→PLAYING força isso. */
    private void claimKeys() {
        session.setPlaybackState(state(PlaybackState.STATE_PAUSED));
        session.setPlaybackState(state(PlaybackState.STATE_PLAYING));
    }

    private static PlaybackState state(int s) {
        return new PlaybackState.Builder().setState(s, 0, 1f, SystemClock.elapsedRealtime()).build();
    }

    /** direction: +1/-1 a cada aperto e auto-repetição; 0 ao soltar. */
    private void onKey(int direction) {
        Log.d("RenanSkip", "onKey dir=" + direction + " pressing=" + pressing + " fired=" + fired);
        if (direction == 0) {
            handler.removeCallbacks(tapFallback);
            if (pressing && !fired) step(pressDir);
            pressing = false;
            return;
        }
        if (!pressing) {
            pressing = true;
            fired = false;
            pressDir = direction;
            handler.postDelayed(tapFallback, TAP_FALLBACK_MS);
            return;
        }
        if (fired) return; // uma troca por aperto
        fired = true;
        handler.removeCallbacks(tapFallback);
        if (pressDir > 0) sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
        else previous();
        vibrate(pressDir > 0);
        handler.postDelayed(this::claimKeys, 1000); // o player costuma retomar a prioridade ao trocar
    }

    private void step(int dir) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);
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

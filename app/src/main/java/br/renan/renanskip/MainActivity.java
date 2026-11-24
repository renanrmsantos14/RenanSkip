package br.renan.renanskip;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private Button battery, update;
    private TextView mode;
    private Updater.Release release;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        SharedPreferences p = getSharedPreferences(SkipService.PREFS, MODE_PRIVATE);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("RenanSkip");
        title.setTextSize(28);
        col.addView(title);

        TextView how = new TextView(this);
        how.setText("Com a música tocando e o celular bloqueado:\n• Segure volume + → próxima faixa\n• Segure volume − → faixa anterior\n\nAo segurar, o volume volta ao valor de antes do aperto; o celular vibra 1x na próxima e 2x na anterior.\nUm toque rápido continua só mudando o volume.");
        how.setTextSize(16);
        how.setPadding(0, pad / 2, 0, pad);
        col.addView(how);

        Switch on = new Switch(this);
        on.setText("Ativado");
        on.setTextSize(18);
        on.setChecked(p.getBoolean(SkipService.KEY_ENABLED, false));
        on.setOnCheckedChangeListener((v, c) -> {
            p.edit().putBoolean(SkipService.KEY_ENABLED, c).apply();
            SkipService.sync(this);
        });
        col.addView(on);

        CheckBox locked = new CheckBox(this);
        locked.setText("Só com a tela bloqueada");
        locked.setChecked(p.getBoolean(SkipService.KEY_ONLY_LOCKED, true));
        locked.setOnCheckedChangeListener((v, c) -> p.edit().putBoolean(SkipService.KEY_ONLY_LOCKED, c).apply());
        col.addView(locked);

        battery = new Button(this);
        battery.setText("Liberar bateria (sem restrições)");
        battery.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        col.addView(battery);

        Button app = new Button(this);
        app.setText("Abrir configurações do app (Autostart)");
        app.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        col.addView(app);

        update = new Button(this);
        update.setVisibility(android.view.View.GONE);
        update.setOnClickListener(v -> installUpdate());
        col.addView(update);

        TextView tip = new TextView(this);
        tip.setText("No Poco (HyperOS/MIUI): ative Autostart e deixe Economia de bateria em \"Sem restrições\", senão o sistema mata o serviço. Funciona abaixo do volume máximo (no máximo, segurar + não gera mudança de volume).");
        tip.setTextSize(13);
        tip.setPadding(0, pad, 0, 0);
        col.addView(tip);

        mode = new TextView(this);
        mode.setTextSize(13);
        mode.setTextIsSelectable(true);
        mode.setPadding(0, pad, 0, 0);
        col.addView(mode);

        TextView ver = new TextView(this);
        ver.setText("v" + BuildConfig.VERSION_NAME + " · " + BuildConfig.BUILD_TIME);
        ver.setTextSize(11);
        ver.setAlpha(0.5f);
        ver.setClickable(false);
        ver.setFocusable(false);

        FrameLayout root = new FrameLayout(this);
        root.addView(col);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(pad, pad, pad, pad);
        root.addView(ver, lp);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        boolean ignoring = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        battery.setEnabled(!ignoring);
        if (ignoring) battery.setText("Bateria liberada ✓");
        mode.setText(LongPress.granted(this)
                ? "Modo sem mexer no volume: ativo ✓"
                : "Para o volume não mexer ao segurar, libere uma vez pelo computador (ADB):\nadb shell pm grant " + getPackageName() + " " + LongPress.PERMISSION);
        SkipService.sync(this);
        checkUpdate();
    }

    private void checkUpdate() {
        new Thread(() -> {
            try {
                Updater.Release r = Updater.newer();
                runOnUiThread(() -> {
                    release = r;
                    update.setVisibility(r == null ? android.view.View.GONE : android.view.View.VISIBLE);
                    if (r != null) { update.setEnabled(true); update.setText("Atualizar para v" + r.version); }
                });
            } catch (Exception ignored) { } // sem internet: tenta de novo na próxima abertura
        }).start();
    }

    private void installUpdate() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Permita instalar apps do RenanSkip e volte aqui", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        Updater.Release r = release;
        update.setEnabled(false);
        update.setText("Baixando v" + r.version + "…");
        new Thread(() -> {
            try {
                Updater.install(getApplicationContext(), r);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    update.setEnabled(true);
                    update.setText("Atualizar para v" + r.version);
                    Toast.makeText(this, "Falha na atualização: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}

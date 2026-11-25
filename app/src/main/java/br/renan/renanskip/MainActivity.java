package br.renan.renanskip;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Tela única, monocromática: estado, como usar e ajustes. UI feita em código (sem AndroidX). */
public class MainActivity extends Activity {
    // Paleta (claro / escuro): monocromático quente, um único acento para "ativo".
    private int bg, surface, line, ink, muted, okFg, okBg, warnFg, warnBg;
    private float density;
    private final PathInterpolator easeOut = new PathInterpolator(.23f, 1f, .32f, 1f);

    private SharedPreferences prefs;
    private Switch enabled;
    private TextView statusLine, modePill, batteryPill, updateTitle;
    private View statusDot, updateCard;
    private Updater.Release release;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        prefs = getSharedPreferences(SkipService.PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        palette();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(48), dp(20), dp(56));

        // Cabeçalho
        TextView title = text("RenanSkip", 34, ink, true);
        title.setLetterSpacing(-0.02f);
        TextView sub = text("Troca de faixa pelo botão de volume, com o celular bloqueado.", 15, muted, false);
        sub.setPadding(0, dp(6), 0, dp(28));
        col.addView(title);
        col.addView(sub);

        // Atualização (só aparece quando há versão nova)
        updateCard = card();
        LinearLayout uRow = row();
        updateTitle = text("", 15, ink, true);
        uRow.addView(updateTitle, new LinearLayout.LayoutParams(0, -2, 1));
        uRow.addView(text("Instalar →", 14, ink, false));
        ((ViewGroup) updateCard).addView(uRow);
        updateCard.setVisibility(View.GONE);
        pressable(updateCard, v -> installUpdate());
        col.addView(updateCard, spaced(12));

        // Estado principal
        LinearLayout hero = card();
        LinearLayout hRow = row();
        LinearLayout hText = new LinearLayout(this);
        hText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout hTitle = new LinearLayout(this);
        hTitle.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        hTitle.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView ht = text("Ativado", 20, ink, true);
        ht.setPadding(dp(10), 0, 0, 0);
        hTitle.addView(ht);
        statusLine = text("", 14, muted, false);
        statusLine.setPadding(0, dp(4), 0, 0);
        hText.addView(hTitle);
        hText.addView(statusLine);
        hRow.addView(hText, new LinearLayout.LayoutParams(0, -2, 1));
        enabled = toggle(prefs.getBoolean(SkipService.KEY_ENABLED, false));
        enabled.setOnCheckedChangeListener((v, c) -> {
            prefs.edit().putBoolean(SkipService.KEY_ENABLED, c).apply();
            SkipService.sync(this);
            renderStatus();
        });
        hRow.addView(enabled);
        hero.addView(hRow);
        pressable(hero, v -> enabled.toggle());
        col.addView(hero, spaced(28));

        // Como usar
        col.addView(label("Como usar"));
        LinearLayout how = card();
        how.addView(keyRow("+", "Segure volume +", "Próxima faixa · vibra 1x"));
        how.addView(divider());
        how.addView(keyRow("−", "Segure volume −", "Faixa anterior · vibra 2x"));
        col.addView(how, spaced(8));
        TextView note = text("Um toque rápido continua mudando o volume normalmente.", 13, muted, false);
        note.setPadding(dp(4), 0, dp(4), dp(28));
        col.addView(note);

        // Ajustes
        col.addView(label("Ajustes"));
        LinearLayout set = card();
        Switch onlyLocked = toggle(prefs.getBoolean(SkipService.KEY_ONLY_LOCKED, true));
        onlyLocked.setOnCheckedChangeListener((v, c) -> {
            prefs.edit().putBoolean(SkipService.KEY_ONLY_LOCKED, c).apply();
            renderStatus();
        });
        View lockedRow = settingRow("Só com a tela bloqueada", "Desbloqueado, o volume funciona como sempre.", onlyLocked);
        pressable(lockedRow, v -> onlyLocked.toggle());
        set.addView(lockedRow);
        set.addView(divider());

        modePill = pill();
        View modeRow = settingRow("Sem mexer no volume", "Segurar não altera o volume. Libera uma vez pelo ADB.", modePill);
        pressable(modeRow, v -> { if (!LongPress.granted(this)) copyAdbCommand(); });
        set.addView(modeRow);
        set.addView(divider());

        batteryPill = pill();
        View batRow = settingRow("Bateria sem restrições", "Evita que o HyperOS encerre o serviço.", batteryPill);
        pressable(batRow, v -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        set.addView(batRow);
        set.addView(divider());

        TextView open = text("Abrir →", 14, ink, false);
        View autoRow = settingRow("Autostart", "No HyperOS, ligue em Configurações do app.", open);
        pressable(autoRow, v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        set.addView(autoRow);
        col.addView(set, spaced(0));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(col);

        TextView ver = text("v" + BuildConfig.VERSION_NAME + " · " + BuildConfig.BUILD_TIME, 11, muted, false);
        ver.setAlpha(0.7f);
        ver.setClickable(false);
        ver.setFocusable(false);
        ver.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        root.addView(scroll);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(dp(16), dp(16), dp(16), dp(16));
        root.addView(ver, lp);
        setContentView(root);
        systemBars();
        enter(col);
    }

    @Override protected void onResume() {
        super.onResume();
        boolean ignoring = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        setPill(batteryPill, ignoring ? "Liberada" : "Liberar", ignoring);
        setPill(modePill, LongPress.granted(this) ? "Ativo" : "Copiar ADB", LongPress.granted(this));
        renderStatus();
        SkipService.sync(this);
        checkUpdate();
    }

    private void renderStatus() {
        boolean on = prefs.getBoolean(SkipService.KEY_ENABLED, false);
        boolean onlyLocked = prefs.getBoolean(SkipService.KEY_ONLY_LOCKED, true);
        statusLine.setText(!on ? "Desligado. O volume funciona normalmente."
                : onlyLocked ? "Pronto. Segure o volume com a tela bloqueada."
                : "Pronto. Segure o volume com a música tocando.");
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(on ? okFg : line);
        statusDot.setBackground(dot);
        if (on && ValueAnimator.areAnimatorsEnabled()) { // pequeno "pulso" ao ligar
            statusDot.setScaleX(0.4f);
            statusDot.setScaleY(0.4f);
            statusDot.animate().scaleX(1).scaleY(1).setDuration(260).setInterpolator(easeOut).start();
        }
    }

    private void copyAdbCommand() {
        String cmd = "adb shell pm grant " + getPackageName() + " " + LongPress.PERMISSION;
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("adb", cmd));
        Toast.makeText(this, "Comando do ADB copiado", Toast.LENGTH_SHORT).show();
    }

    private void checkUpdate() {
        new Thread(() -> {
            try {
                Updater.Release r = Updater.newer();
                runOnUiThread(() -> {
                    release = r;
                    boolean show = r != null;
                    if (show) updateTitle.setText("Versão " + r.version + " disponível");
                    if (show && updateCard.getVisibility() != View.VISIBLE) {
                        updateCard.setVisibility(View.VISIBLE);
                        fadeUp(updateCard, 0);
                    } else if (!show) updateCard.setVisibility(View.GONE);
                });
            } catch (Exception ignored) { } // sem internet: tenta de novo na próxima abertura
        }).start();
    }

    private void installUpdate() {
        Updater.Release r = release;
        if (r == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Permita instalar apps do RenanSkip e volte aqui", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        updateCard.setEnabled(false);
        updateTitle.setText("Baixando " + r.version + "…");
        new Thread(() -> {
            try {
                Updater.install(getApplicationContext(), r);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    updateCard.setEnabled(true);
                    updateTitle.setText("Versão " + r.version + " disponível");
                    Toast.makeText(this, "Falha na atualização: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ---------- visual ----------

    private void palette() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xFF111110 : 0xFFF7F6F3;
        surface = dark ? 0xFF1A1A19 : 0xFFFFFFFF;
        line = dark ? 0xFF2C2C2A : 0xFFE9E8E4;
        ink = dark ? 0xFFEDEDEC : 0xFF1A1A19;
        muted = dark ? 0xFF9B9A97 : 0xFF787774;
        okFg = dark ? 0xFF7FC48A : 0xFF346538;
        okBg = dark ? 0xFF1E2B20 : 0xFFEDF3EC;
        warnFg = dark ? 0xFFE3B66B : 0xFF956400;
        warnBg = dark ? 0xFF2E2615 : 0xFFFBF3DB;
    }

    private void systemBars() {
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (!dark) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    /** Entrada em cascata: sobe 12dp e aparece, 40ms entre blocos. Desligado se o sistema reduz animações. */
    private void enter(LinearLayout col) {
        for (int i = 0; i < col.getChildCount(); i++) fadeUp(col.getChildAt(i), i * 40L);
    }

    private void fadeUp(View v, long delay) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(12));
        v.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(320).setInterpolator(easeOut).start();
    }

    /** Toque: encolhe 3% ao pressionar e volta ao soltar, com ripple sutil. */
    private void pressable(View v, View.OnClickListener click) {
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setForeground(new RippleDrawable(ColorStateList.valueOf(ink & 0x14FFFFFF), null, v.getBackground() != null ? v.getBackground() : new GradientDrawable()));
        v.setOnTouchListener((view, e) -> {
            if (!ValueAnimator.areAnimatorsEnabled()) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN)
                view.animate().scaleX(0.985f).scaleY(0.985f).setStartDelay(0).setDuration(100).setInterpolator(easeOut).start();
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(160).setInterpolator(easeOut).start();
            return false;
        });
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable();
        d.setColor(surface);
        d.setCornerRadius(dp(16));
        d.setStroke(Math.max(1, dp(1) / 2), line);
        c.setBackground(d);
        c.setClipToOutline(true);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), dp(16), dp(18), dp(16));
        r.setMinimumHeight(dp(56));
        return r;
    }

    private View keyRow(String key, String titleText, String subText) {
        LinearLayout r = row();
        TextView cap = text(key, 18, ink, true);
        cap.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        cap.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10));
        d.setStroke(Math.max(1, dp(1) / 2), line);
        d.setColor(bg);
        cap.setBackground(d);
        r.addView(cap, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), 0, 0, 0);
        t.addView(text(titleText, 16, ink, true));
        TextView s = text(subText, 14, muted, false);
        s.setPadding(0, dp(2), 0, 0);
        t.addView(s);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        return r;
    }

    private View settingRow(String titleText, String subText, View end) {
        LinearLayout r = row();
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(0, 0, dp(12), 0);
        t.addView(text(titleText, 16, ink, false));
        TextView s = text(subText, 13, muted, false);
        s.setPadding(0, dp(2), 0, 0);
        t.addView(s);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(end);
        return r;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        lp.setMarginStart(dp(18));
        d.setLayoutParams(lp);
        return d;
    }

    private TextView label(String s) {
        TextView t = text(s.toUpperCase(), 12, muted, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), 0, 0, dp(10));
        return t;
    }

    private TextView pill() {
        TextView p = text("", 12, ink, true);
        p.setPadding(dp(10), dp(4), dp(10), dp(4));
        return p;
    }

    private void setPill(TextView p, String s, boolean ok) {
        p.setText(s);
        p.setTextColor(ok ? okFg : warnFg);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(999));
        d.setColor(ok ? okBg : warnBg);
        p.setBackground(d);
    }

    private Switch toggle(boolean checked) {
        Switch s = new Switch(this);
        s.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{surface, 0xFFFFFFFF}));
        s.setTrackTintList(new ColorStateList(states, new int[]{ink, line}));
        s.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC);
        return s;
    }

    private TextView text(String s, float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private LinearLayout.LayoutParams spaced(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    private int dp(float v) { return Math.round(v * density); }
}

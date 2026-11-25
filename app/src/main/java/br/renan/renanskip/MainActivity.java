package br.renan.renanskip;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Tela única, monocromática: estado, como usar e ajustes. UI feita em código (sem AndroidX). */
public class MainActivity extends Activity {
    // Paleta (claro / escuro): neutra, um único acento para "ativo" (lima da logo no escuro).
    private int bg, surface, line, ink, muted, okFg, okBg, warnFg, warnBg;
    private boolean dark;
    private float density;

    private SharedPreferences prefs;
    private TextView statusTitle, statusLine, modePill, batteryPill, updateTitle;
    private LinearLayout hero, settings;
    private Chevrons heroIcon;
    private View updateCard, lockedRow, modeRow, batRow, autoRow;
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

        // Cabeçalho: marca C3
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(new Chevrons(dark ? 0xFF26282D : 0xFF111214, 0x80FFFFFF, 0xFFC8FF3D), new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView name = text("", 22, ink, false);
        name.setTypeface(Typeface.MONOSPACE);
        SpannableStringBuilder n = new SpannableStringBuilder("renan›skip");
        n.setSpan(new ForegroundColorSpan(muted), 0, 5, 0);
        n.setSpan(new StyleSpan(Typeface.BOLD), 6, 10, 0);
        name.setText(n);
        name.setPadding(dp(12), 0, 0, 0);
        brand.addView(name);
        col.addView(brand, spaced(28));

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

        // Estado: o cartão inteiro é o botão de ligar/desligar
        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(22), dp(22), dp(22), dp(22));
        hero.setBackground(heroBg(false));
        heroIcon = new Chevrons(0, line, muted);
        hero.addView(heroIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        statusTitle = text("", 28, ink, true);
        statusTitle.setLetterSpacing(-0.02f);
        statusTitle.setPadding(0, dp(14), 0, 0);
        hero.addView(statusTitle);
        statusLine = text("", 15, muted, false);
        statusLine.setPadding(0, dp(2), 0, 0);
        hero.addView(statusLine);
        pressable(hero, v -> {
            prefs.edit().putBoolean(SkipService.KEY_ENABLED, !prefs.getBoolean(SkipService.KEY_ENABLED, false)).apply();
            SkipService.sync(this);
            renderStatus();
        });
        col.addView(hero, spaced(28));

        // Como usar
        col.addView(label("Como usar"));
        LinearLayout how = card();
        how.setOrientation(LinearLayout.HORIZONTAL);
        how.addView(keyRow("+", "Próxima", "Vibra 1x"), new LinearLayout.LayoutParams(0, -2, 1));
        View vDiv = new View(this);
        vDiv.setBackgroundColor(line);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(Math.max(1, dp(1) / 2), -1);
        vlp.setMargins(0, dp(14), 0, dp(14));
        how.addView(vDiv, vlp);
        how.addView(keyRow("−", "Anterior", "Vibra 2x"), new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(how, spaced(8));
        TextView note = text("Toque rápido continua mudando o volume.", 13, muted, false);
        note.setPadding(dp(4), 0, dp(4), dp(28));
        col.addView(note);

        // Ajustes: só o que ainda precisa de atenção (ver renderSettings)
        col.addView(label("Ajustes"));
        settings = card();
        Switch onlyLocked = toggle(prefs.getBoolean(SkipService.KEY_ONLY_LOCKED, true));
        onlyLocked.setOnCheckedChangeListener((v, c) -> {
            prefs.edit().putBoolean(SkipService.KEY_ONLY_LOCKED, c).apply();
            renderStatus();
        });
        lockedRow = settingRow("Só com a tela bloqueada", "Desbloqueado, o volume fica normal.", onlyLocked);
        pressable(lockedRow, v -> onlyLocked.toggle());

        modePill = pill();
        modeRow = settingRow("Sem mexer no volume", "Libere uma vez pelo ADB.", modePill);
        pressable(modeRow, v -> copyAdbCommand());

        batteryPill = pill();
        batRow = settingRow("Bateria sem restrições", "Evita que o HyperOS feche o app.", batteryPill);
        pressable(batRow, v -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));

        autoRow = settingRow("Autostart", "Ligue nas configurações do app.", text("Abrir →", 14, ink, false));
        pressable(autoRow, v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        col.addView(settings, spaced(0));

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
    }

    @Override protected void onResume() {
        super.onResume();
        boolean ignoring = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        setPill(batteryPill, "Liberar", false);
        setPill(modePill, "Copiar ADB", false);
        renderSettings(ignoring, LongPress.granted(this));
        renderStatus();
        SkipService.sync(this);
        checkUpdate();
    }

    private void renderStatus() {
        boolean on = prefs.getBoolean(SkipService.KEY_ENABLED, false);
        boolean onlyLocked = prefs.getBoolean(SkipService.KEY_ONLY_LOCKED, true);
        statusTitle.setText(on ? "Ativado" : "Desligado");
        statusLine.setText(!on ? "Toque para ativar."
                : onlyLocked ? "Segure o volume com a tela bloqueada."
                : "Segure o volume com a música tocando.");
        int onInk = 0xFF0E0F11;
        statusTitle.setTextColor(on ? onInk : ink);
        statusLine.setTextColor(on ? (onInk & 0xB3FFFFFF) : muted);
        heroIcon.set(0, on ? (onInk & 0x59FFFFFF) : line, on ? onInk : muted);
        Drawable fg = hero.getForeground();
        hero.setBackground(heroBg(on));
        hero.setForeground(fg);
        hero.setContentDescription((on ? "Ativado" : "Desligado") + ". Toque para " + (on ? "desligar" : "ativar") + ".");
    }

    /** Mostra só os ajustes pendentes; "Só com a tela bloqueada" e Autostart (não detectável) ficam sempre. */
    private void renderSettings(boolean batteryOk, boolean modeOk) {
        settings.removeAllViews();
        settings.addView(lockedRow);
        if (!modeOk) { settings.addView(divider()); settings.addView(modeRow); }
        if (!batteryOk) { settings.addView(divider()); settings.addView(batRow); }
        settings.addView(divider());
        settings.addView(autoRow);
    }

    /** Ligado: lima da marca, cheio. Desligado: cartão neutro com borda. */
    private GradientDrawable heroBg(boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(20));
        d.setColor(on ? 0xFFC8FF3D : surface);
        if (!on) d.setStroke(Math.max(1, dp(1) / 2), line);
        return d;
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
                    updateCard.setVisibility(show ? View.VISIBLE : View.GONE);
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
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xFF0E0F11 : 0xFFF7F6F3;
        surface = dark ? 0xFF1A1B1E : 0xFFFFFFFF;
        line = dark ? 0xFF2B2D32 : 0xFFE9E8E4;
        ink = dark ? 0xFFF2F3F5 : 0xFF1A1A19;
        muted = dark ? 0xFFA3A7AE : 0xFF6B6A67;
        okFg = dark ? 0xFFC8FF3D : 0xFF346538;
        okBg = dark ? 0xFF232B14 : 0xFFEDF3EC;
        warnFg = dark ? 0xFFF5B94A : 0xFF8A5C00;
        warnBg = dark ? 0xFF2E2410 : 0xFFFBF3DB;
    }

    private void systemBars() {
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!dark) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    /** Toque: só o ripple do sistema, sem animação extra. */
    private void pressable(View v, View.OnClickListener click) {
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setForeground(new RippleDrawable(ColorStateList.valueOf(ink & 0x14FFFFFF), null, v.getBackground() != null ? v.getBackground() : new GradientDrawable()));
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
        r.setPadding(dp(14), dp(14), dp(14), dp(14));
        TextView cap = text(key, 18, ink, true);
        cap.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        cap.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10));
        d.setStroke(Math.max(1, dp(1) / 2), line);
        d.setColor(bg);
        cap.setBackground(d);
        r.addView(cap, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(12), 0, 0, 0);
        t.addView(text(titleText, 15, ink, true));
        TextView s = text(subText, 13, muted, false);
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
        s.setThumbTintList(new ColorStateList(states, new int[]{dark ? bg : surface, dark ? muted : 0xFFFFFFFF}));
        s.setTrackTintList(new ColorStateList(states, new int[]{dark ? okFg : ink, line}));
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

    /** Logo C3 desenhada em código: chevron de trás + chevron da frente, opcionalmente num quadrado. */
    private class Chevrons extends View {
        private int bgc, back, front;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Chevrons(int bg, int back, int front) { super(MainActivity.this); set(bg, back, front); }

        void set(int bg, int back, int front) { bgc = bg; this.back = back; this.front = front; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float s = getWidth() / 100f;
            if (Color.alpha(bgc) != 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(bgc);
                c.drawRoundRect(0, 0, getWidth(), getHeight(), 24 * s, 24 * s, p);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(10 * s);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            chevron(c, s, 31, back);
            chevron(c, s, 51, front);
        }

        private void chevron(Canvas c, float s, float x, int color) {
            path.reset();
            path.moveTo(x * s, 32 * s);
            path.lineTo((x + 18) * s, 50 * s);
            path.lineTo(x * s, 68 * s);
            p.setColor(color);
            c.drawPath(path, p);
        }
    }
}

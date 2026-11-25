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
    private int bg, surface, line, ink, muted, okFg, okBg, warnFg, warnBg, errFg, errBg;
    private boolean dark;
    private float density;

    private SharedPreferences prefs;
    private TextView statusTitle, statusLine, updateTitle;
    private View statusDot, updateCard;
    private Toggle enabled;
    private LinearLayout reqs;
    private Updater.Release release;
    private TextView diagText;

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

        // Estado: uma linha, com resumo dos requisitos e o interruptor
        LinearLayout status = card();
        LinearLayout sRow = row();
        LinearLayout sText = new LinearLayout(this);
        sText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout sTitle = new LinearLayout(this);
        sTitle.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        sTitle.addView(statusDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        statusTitle = text("", 20, ink, true);
        statusTitle.setPadding(dp(10), 0, 0, 0);
        sTitle.addView(statusTitle);
        statusLine = text("", 14, muted, false);
        statusLine.setPadding(0, dp(2), 0, 0);
        sText.addView(sTitle);
        sText.addView(statusLine);
        sRow.addView(sText, new LinearLayout.LayoutParams(0, -2, 1));
        enabled = new Toggle(prefs.getBoolean(SkipService.KEY_ENABLED, false), on -> {
            prefs.edit().putBoolean(SkipService.KEY_ENABLED, on).apply();
            SkipService.sync(this);
            render();
            statusDot.postDelayed(this::render, 700); // o serviço sobe em seguida
        });
        sRow.addView(enabled, new LinearLayout.LayoutParams(dp(52), dp(32)));
        status.addView(sRow);
        pressable(status, v -> enabled.toggle());
        col.addView(status, spaced(28));

        // Requisitos: cada item com estado (ok, pendente, erro) e a ação para resolver
        col.addView(label("Requisitos"));
        reqs = card();
        col.addView(reqs, spaced(28));

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

        // Preferência
        LinearLayout pref = card();
        Toggle onlyLocked = new Toggle(prefs.getBoolean(SkipService.KEY_ONLY_LOCKED, true), on -> {
            prefs.edit().putBoolean(SkipService.KEY_ONLY_LOCKED, on).apply();
            render();
        });
        LinearLayout lr = row();
        LinearLayout lt = new LinearLayout(this);
        lt.setOrientation(LinearLayout.VERTICAL);
        lt.addView(text("Só com a tela bloqueada", 16, ink, false));
        TextView ls = text("Desbloqueado, o volume fica normal.", 13, muted, false);
        ls.setPadding(0, dp(2), 0, 0);
        lt.addView(ls);
        lr.addView(lt, new LinearLayout.LayoutParams(0, -2, 1));
        lr.addView(onlyLocked, new LinearLayout.LayoutParams(dp(52), dp(32)));
        pref.addView(lr);
        pressable(pref, v -> onlyLocked.toggle());
        col.addView(pref, spaced(28));

        // Diagnóstico: eventos recentes do serviço, para copiar e mandar
        col.addView(label("Diagnóstico"));
        LinearLayout diag = card();
        diagText = text("", 11, muted, false);
        diagText.setTypeface(Typeface.MONOSPACE);
        diagText.setTextIsSelectable(true);
        diagText.setPadding(dp(18), dp(14), dp(18), dp(4));
        diag.addView(diagText);
        LinearLayout dr = row();
        dr.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView copy = text("Copiar", 13, ink, true);
        copy.setPadding(dp(12), dp(6), dp(12), dp(6));
        pressable(copy, v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("diag", String.join("\n", Diag.read(this))));
            Toast.makeText(this, "Diagnóstico copiado", Toast.LENGTH_SHORT).show();
        });
        TextView clear = text("Limpar", 13, muted, true);
        clear.setPadding(dp(12), dp(6), dp(12), dp(6));
        pressable(clear, v -> { Diag.clear(this); render(); });
        dr.addView(clear);
        dr.addView(copy);
        diag.addView(dr);
        col.addView(diag, spaced(48));

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
        SkipService.sync(this);
        render();
        statusDot.postDelayed(this::render, 700);
        checkUpdate();
    }

    private static final int OK = 0, WARN = 1, ERR = 2, INFO = 3;

    /** Estado + requisitos. Conta como problema só o que dá para detectar (Autostart não dá). */
    private void render() {
        java.util.List<String> d = Diag.read(this);
        java.util.List<String> last = d.subList(Math.max(0, d.size() - 30), d.size());
        diagText.setText(last.isEmpty() ? "Sem eventos ainda." : String.join("\n", last));
        boolean on = prefs.getBoolean(SkipService.KEY_ENABLED, false);
        boolean adb = LongPress.granted(this);
        boolean battery = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        boolean running = SkipService.running;

        reqs.removeAllViews();
        int errors = 0, pending = 0;

        if (on) {
            reqs.addView(reqRow(running ? OK : ERR, "Serviço",
                    running ? "Rodando." : "Parado.",
                    running ? null : "Reiniciar", v -> { SkipService.sync(this); statusDot.postDelayed(this::render, 700); }));
            reqs.addView(divider());
            if (!running) errors++;
        }

        boolean lpFailed = on && running && adb && !SkipService.longPressActive;
        int adbState = lpFailed ? ERR : adb ? OK : WARN;
        reqs.addView(reqRow(adbState, "Sem mexer no volume",
                lpFailed ? "Permissão dada, mas não ativou. Reinicie o serviço."
                        : adb ? "Permissão concedida."
                        : "Sem ela o volume pisca e volta. Libere uma vez pelo ADB.",
                lpFailed ? "Reiniciar" : adb ? null : "Copiar ADB",
                v -> { if (lpFailed) restartService(); else if (!adb) copyAdbCommand(); }));
        if (adbState == ERR) errors++; else if (adbState == WARN) pending++;

        reqs.addView(divider());
        reqs.addView(reqRow(battery ? OK : WARN, "Bateria sem restrições",
                battery ? "Liberada." : "O HyperOS pode encerrar o app.",
                battery ? null : "Liberar",
                v -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())))));
        if (!battery) pending++;

        reqs.addView(divider());
        reqs.addView(reqRow(INFO, "Autostart", "Confira nas configurações.", "Abrir",
                v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));

        int state = !on ? INFO : errors > 0 ? ERR : pending > 0 ? WARN : OK;
        statusTitle.setText(on ? "Ativado" : "Desligado");
        statusLine.setText(!on ? "Ligue para usar o volume como controle."
                : errors > 0 ? (errors == 1 ? "1 erro nos requisitos" : errors + " erros nos requisitos")
                : pending > 0 ? (pending == 1 ? "Funciona, com 1 pendência" : "Funciona, com " + pending + " pendências")
                : "Tudo pronto.");
        statusLine.setTextColor(state == ERR ? errFg : muted);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(stateFg(state, muted));
        statusDot.setBackground(dot);
        enabled.setOn(on);
    }

    private void restartService() {
        stopService(new Intent(this, SkipService.class));
        SkipService.sync(this);
        statusDot.postDelayed(this::render, 700);
    }

    private int stateFg(int st, int fallback) {
        return st == OK ? okFg : st == WARN ? warnFg : st == ERR ? errFg : fallback;
    }

    /** Linha de requisito: selo (✓ ! ✕ ·), título, uma frase e, se houver, a ação. */
    private View reqRow(int st, String titleText, String subText, String action, View.OnClickListener click) {
        LinearLayout r = row();
        r.setGravity(Gravity.TOP);
        TextView badge = text(st == OK ? "✓" : st == WARN ? "!" : st == ERR ? "✕" : "·", 13, stateFg(st, muted), true);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable bd = new GradientDrawable();
        bd.setShape(GradientDrawable.OVAL);
        bd.setColor(st == OK ? okBg : st == WARN ? warnBg : st == ERR ? errBg : line);
        badge.setBackground(bd);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(24), dp(24));
        blp.topMargin = dp(1);
        r.addView(badge, blp);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.addView(text(titleText, 15, ink, true));
        TextView s = text(subText, 13, st == ERR ? errFg : muted, false);
        s.setPadding(0, dp(2), 0, 0);
        t.addView(s);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView a = text(action, 13, st == ERR ? errFg : st == WARN ? warnFg : ink, true);
            a.setPadding(dp(12), dp(6), dp(12), dp(6));
            GradientDrawable ad = new GradientDrawable();
            ad.setCornerRadius(dp(999));
            ad.setColor(st == ERR ? errBg : st == WARN ? warnBg : bg);
            if (st == INFO) ad.setStroke(Math.max(1, dp(1) / 2), line);
            a.setBackground(ad);
            r.addView(a);
            pressable(r, click);
        }
        return r;
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
        errFg = dark ? 0xFFFF7A70 : 0xFFB42318;
        errBg = dark ? 0xFF3A1815 : 0xFFFDECEA;
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

    /** Interruptor desenhado: ligado = trilho cheio (lima no escuro, tinta no claro); desligado = contorno forte. */
    private class Toggle extends View {
        private boolean on;
        private final java.util.function.Consumer<Boolean> onChange;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Toggle(boolean on, java.util.function.Consumer<Boolean> onChange) {
            super(MainActivity.this);
            this.on = on;
            this.onChange = onChange;
            setClickable(true);
            setOnClickListener(v -> toggle());
        }

        void toggle() {
            on = !on;
            invalidate();
            onChange.accept(on);
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
        }

        void setOn(boolean v) { on = v; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f, sw = dp(2);
            if (on) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(dark ? okFg : ink);
                c.drawRoundRect(0, 0, w, h, r, r, p);
                p.setColor(dark ? bg : 0xFFFFFFFF);
                c.drawCircle(w - r, r, r - dp(4), p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw);
                p.setColor(muted);
                c.drawRoundRect(sw / 2, sw / 2, w - sw / 2, h - sw / 2, r, r, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(r, r, r - dp(8), p);
            }
        }

        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(Switch.class.getName());
            info.setCheckable(true);
            info.setChecked(on);
        }
    }
}

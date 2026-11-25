package br.renan.renanskip;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Registro curto de eventos (últimas linhas) para diagnosticar sem ADB; visto na tela do app. */
final class Diag {
    private static final int MAX = 200;
    private static final String FILE = "diag.txt";
    private static final SimpleDateFormat FMT = new SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.ROOT);

    static synchronized void log(Context c, String msg) {
        Log.d("RenanSkip", msg);
        List<String> lines = read(c);
        lines.add(FMT.format(new Date()) + "  " + msg);
        if (lines.size() > MAX) lines = lines.subList(lines.size() - MAX, lines.size());
        try { Files.write(file(c).toPath(), lines, StandardCharsets.UTF_8); } catch (Exception ignored) { }
    }

    static synchronized List<String> read(Context c) {
        try { return new ArrayList<>(Files.readAllLines(file(c).toPath(), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    static synchronized void clear(Context c) { file(c).delete(); }

    private static File file(Context c) { return new File(c.getFilesDir(), FILE); }
}

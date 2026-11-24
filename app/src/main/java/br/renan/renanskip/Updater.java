package br.renan.renanskip;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Atualização pelo GitHub Releases: compara a versão, baixa o APK, confere o SHA-256 e instala. */
final class Updater {
    static final String REPO = "renanrmsantos14/RenanSkip";

    static final class Release {
        final String version, apkUrl, shaUrl;
        Release(String v, String a, String s) { version = v; apkUrl = a; shaUrl = s; }
    }

    /** Bloqueante: chamar fora da thread principal. Retorna null se já está na última versão. */
    static Release newer() throws IOException {
        try {
            JSONObject json = new JSONObject(new String(get("https://api.github.com/repos/" + REPO + "/releases/latest"), "UTF-8"));
            String version = json.getString("tag_name").replaceFirst("^v", "");
            if (!isNewer(version, BuildConfig.VERSION_NAME)) return null;
            String apk = null, sha = null;
            JSONArray assets = json.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.getString("name"), url = a.getString("browser_download_url");
                if (name.endsWith(".apk")) apk = url;
                else if (name.endsWith(".apk.sha256")) sha = url;
            }
            if (apk == null || sha == null) throw new IOException("a versão " + version + " não tem APK e hash");
            return new Release(version, apk, sha);
        } catch (org.json.JSONException e) {
            throw new IOException("resposta inválida do GitHub", e);
        }
    }

    static boolean isNewer(String candidate, String current) {
        String[] a = candidate.split("\\."), b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? parse(a[i]) : 0, y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Bloqueante: baixa, confere o hash e abre a confirmação de instalação do Android. */
    static void install(Context c, Release r) throws IOException {
        String expected = new String(get(r.shaUrl), "UTF-8").trim().split("\\s+")[0].toLowerCase();
        File apk = new File(c.getCacheDir(), "update.apk");
        String actual = download(r.apkUrl, apk);
        if (!actual.equals(expected)) {
            apk.delete();
            throw new IOException("hash do APK não confere");
        }
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        int id = pi.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("apk", 0, apk.length())) {
                copy(in, out);
                s.fsync(out);
            }
            Intent cb = new Intent(c, InstallReceiver.class);
            PendingIntent p = PendingIntent.getBroadcast(c, id, cb, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(p.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        if (con.getResponseCode() != 200) throw new IOException("GitHub respondeu " + con.getResponseCode());
        return con;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toByteArray();
        } finally { con.disconnect(); }
    }

    private static String download(String url, File dest) throws IOException {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) { out.write(buf, 0, n); md.update(buf, 0, n); }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally { con.disconnect(); }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    public static class InstallReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "Atualização falhou: " + msg, Toast.LENGTH_LONG).show();
            }
        }
    }

    private Updater() {}
}

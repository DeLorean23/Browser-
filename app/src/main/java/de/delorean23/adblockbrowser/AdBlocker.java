package de.delorean23.adblockbrowser;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Domain-Blocker: eingebaute Liste (assets/adhosts.txt) plus optional
 * eine heruntergeladene Hosts-Datei (StevenBlack, ca. 80.000 Einträge).
 */
public class AdBlocker {

    public static final String UPDATE_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final String UPDATE_FILE = "hosts_update.txt";

    private final Context ctx;
    private volatile Set<String> hosts = new HashSet<>();

    public AdBlocker(Context context) {
        ctx = context.getApplicationContext();
    }

    public boolean hasUpdateFile() {
        return new File(ctx.getFilesDir(), UPDATE_FILE).exists();
    }

    public int size() {
        return hosts.size();
    }

    /** Lädt die Listen. Im Hintergrund-Thread aufrufen. */
    public void load() {
        Set<String> set = new HashSet<>();
        try (InputStream in = ctx.getAssets().open("adhosts.txt")) {
            parse(in, set);
        } catch (IOException ignored) {
        }
        File f = new File(ctx.getFilesDir(), UPDATE_FILE);
        if (f.exists()) {
            try (InputStream in = new FileInputStream(f)) {
                parse(in, set);
            } catch (IOException ignored) {
            }
        }
        hosts = set;
    }

    /** Lädt die große Filterliste herunter. Im Hintergrund-Thread aufrufen. */
    public int update() throws IOException {
        File tmp = new File(ctx.getFilesDir(), UPDATE_FILE + ".tmp");
        HttpURLConnection con = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        try {
            if (con.getResponseCode() != 200) {
                throw new IOException("HTTP " + con.getResponseCode());
            }
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } finally {
            con.disconnect();
        }
        File target = new File(ctx.getFilesDir(), UPDATE_FILE);
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) throw new IOException("Speichern fehlgeschlagen");
        load();
        return hosts.size();
    }

    /** Prüft den Host und alle übergeordneten Domains (ads.x.com → x.com). */
    public boolean isBlocked(String host) {
        if (host == null || host.isEmpty()) return false;
        Set<String> set = hosts;
        String h = host.toLowerCase(Locale.ROOT);
        while (true) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
            if (h.indexOf('.') < 0) return false; // nur noch TLD übrig
        }
    }

    private static void parse(InputStream in, Set<String> out) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            String domain;
            if (parts.length >= 2 && (parts[0].equals("0.0.0.0") || parts[0].equals("127.0.0.1"))) {
                domain = parts[1];
            } else {
                domain = parts[0];
            }
            domain = domain.toLowerCase(Locale.ROOT);
            if (domain.equals("localhost") || domain.equals("0.0.0.0")
                    || domain.startsWith("localhost.") || domain.indexOf('.') < 0) continue;
            out.add(domain);
        }
    }
}

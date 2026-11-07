package de.delorean23.tube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Kleiner Web-Server auf dem Handy für Chromecast in hoher Qualität:
 * liefert eine DASH-Beschreibung und reicht Bild- und Tonspur von YouTube durch.
 * Läuft als Vordergrund-Dienst, solange gecastet wird.
 * Created by DeLorean23
 */
public class CastServer extends Service {

    private static final String CHANNEL = "cast";
    private static final int NOTIFY_ID = 4500;
    private static final long CHUNK = 4L * 1024 * 1024;
    private static final String ACTION_STOP = "stop";

    private static ServerSocket server;
    private static ExecutorService pool;
    private static volatile String token;
    private static volatile String videoUrl, audioUrl, manifest;
    private static volatile String title = "";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private SessionManagerListener<CastSession> sessionListener;

    // ------------------------------------------------------------------ Vorbereiten

    /** Nicht im UI-Thread aufrufen. Liefert die Adresse der DASH-Beschreibung für den Chromecast. */
    public static synchronized String prepare(Context c, StreamInfo info) throws Exception {
        if (Streams.isLive(info)) throw new Exception("Live-Streams nur direkt");
        VideoStream v = pickVideo(info);
        AudioStream a = Streams.bestAudio(info, true);
        if (v == null || a == null) throw new Exception("keine passende Bild-/Tonspur");
        ItagItem vi = v.getItagItem(), ai = a.getItagItem();
        if (!hasIndex(vi) || !hasIndex(ai)) throw new Exception("YouTube liefert für dieses Video keine Index-Daten");
        String ip = wifiIp();
        if (ip == null) throw new Exception("Handy ist nicht im WLAN");

        startServer();
        token = randomToken();
        videoUrl = v.getContent();
        audioUrl = a.getContent();
        title = info.getName();
        String base = "http://" + ip + ":" + server.getLocalPort() + "/" + token + "/";

        double dur = info.getDuration();
        if (vi.getApproxDurationMs() > 0) dur = vi.getApproxDurationMs() / 1000.0;
        String vCodec = v.getCodec() != null ? v.getCodec() : vi.getCodec();
        String aCodec = a.getCodec() != null ? a.getCodec() : ai.getCodec();
        int channels = ai.getAudioChannels() > 0 ? ai.getAudioChannels() : 2;
        int rate = ai.getSampleRate() > 0 ? ai.getSampleRate() : 44100;

        StringBuilder m = new StringBuilder();
        m.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
                .append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" ")
                .append("minBufferTime=\"PT1.500S\" mediaPresentationDuration=\"PT")
                .append(String.format(Locale.US, "%.3f", dur)).append("S\">\n")
                .append(" <Period id=\"0\" start=\"PT0S\">\n")
                .append("  <AdaptationSet id=\"0\" contentType=\"video\" mimeType=\"video/mp4\" ")
                .append("subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\">\n")
                .append("   <Representation id=\"v\" bandwidth=\"").append(Math.max(1, vi.getBitrate()))
                .append("\" codecs=\"").append(vCodec)
                .append("\" width=\"").append(v.getWidth()).append("\" height=\"").append(v.getHeight())
                .append("\" frameRate=\"").append(v.getFps() > 0 ? v.getFps() : 30).append("\">\n")
                .append("    <BaseURL>").append(base).append("v</BaseURL>\n")
                .append("    <SegmentBase indexRange=\"").append(vi.getIndexStart()).append('-')
                .append(vi.getIndexEnd()).append("\"><Initialization range=\"")
                .append(vi.getInitStart()).append('-').append(vi.getInitEnd()).append("\"/></SegmentBase>\n")
                .append("   </Representation>\n  </AdaptationSet>\n")
                .append("  <AdaptationSet id=\"1\" contentType=\"audio\" mimeType=\"audio/mp4\" lang=\"und\" ")
                .append("subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\">\n")
                .append("   <Representation id=\"a\" bandwidth=\"").append(Math.max(1, ai.getBitrate()))
                .append("\" codecs=\"").append(aCodec)
                .append("\" audioSamplingRate=\"").append(rate).append("\">\n")
                .append("    <AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"")
                .append(channels).append("\"/>\n")
                .append("    <BaseURL>").append(base).append("a</BaseURL>\n")
                .append("    <SegmentBase indexRange=\"").append(ai.getIndexStart()).append('-')
                .append(ai.getIndexEnd()).append("\"><Initialization range=\"")
                .append(ai.getInitStart()).append('-').append(ai.getInitEnd()).append("\"/></SegmentBase>\n")
                .append("   </Representation>\n  </AdaptationSet>\n")
                .append(" </Period>\n</MPD>\n");
        manifest = m.toString();

        ContextCompat.startForegroundService(c, new Intent(c, CastServer.class));
        return base + "manifest.mpd";
    }

    private static boolean hasIndex(ItagItem i) {
        return i != null && i.getIndexEnd() > 0 && i.getInitEnd() > 0;
    }

    /** H.264 (läuft auf jedem Chromecast), höchstens 1080p (bei Hochkant-Videos die kurze Seite). */
    private static VideoStream pickVideo(StreamInfo info) {
        VideoStream best = null;
        int bestShort = 0;
        for (VideoStream v : Streams.videoChoices(info, true).values()) {
            if (!v.isVideoOnly()) continue;
            int w = v.getWidth() > 0 ? v.getWidth() : Streams.height(v) * 16 / 9;
            int h = Streams.height(v);
            int shortSide = Math.min(w, h);
            if (shortSide > 1080) continue;
            if (shortSide > bestShort) {
                bestShort = shortSide;
                best = v;
            }
        }
        return best;
    }

    private static String randomToken() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    private static String wifiIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress ad : Collections.list(ni.getInetAddresses())) {
                    if (!(ad instanceof Inet4Address) || !ad.isSiteLocalAddress()) continue;
                    String name = ni.getName().toLowerCase(Locale.US);
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                        return ad.getHostAddress();
                    }
                    if (fallback == null) fallback = ad.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Server

    private static synchronized void startServer() throws IOException {
        if (server != null && !server.isClosed()) return;
        try {
            server = new ServerSocket(8723);
        } catch (IOException e) {
            server = new ServerSocket(0);
        }
        pool = Executors.newCachedThreadPool();
        final ServerSocket ss = server;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    pool.execute(() -> handle(s));
                } catch (IOException e) {
                    break;
                }
            }
        }, "cast-server");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void stopServer() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
        if (pool != null) pool.shutdownNow();
        pool = null;
        token = null;
    }

    private static void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];

            if (method.equals("OPTIONS")) {
                write(out, "HTTP/1.1 204 No Content\r\n" + cors()
                        + "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
                        + "Access-Control-Allow-Headers: Range, Content-Type, Origin, Accept\r\n"
                        + "Access-Control-Max-Age: 86400\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            String tk = token;
            if (tk == null || !path.startsWith("/" + tk + "/")) {
                write(out, "HTTP/1.1 404 Not Found\r\n" + cors() + "Content-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            String file = path.substring(tk.length() + 2);
            boolean head = method.equals("HEAD");
            if (file.startsWith("manifest.mpd")) {
                byte[] body = manifest.getBytes(StandardCharsets.UTF_8);
                write(out, "HTTP/1.1 200 OK\r\n" + cors() + "Content-Type: application/dash+xml\r\n"
                        + "Content-Length: " + body.length + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n");
                if (!head) out.write(body);
                out.flush();
                return;
            }
            String upstream = file.equals("v") ? videoUrl : file.equals("a") ? audioUrl : null;
            if (upstream == null) {
                write(out, "HTTP/1.1 404 Not Found\r\n" + cors() + "Content-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            proxy(upstream, file.equals("v") ? "video/mp4" : "audio/mp4", headers.get("range"), head, out);
        } catch (Throwable ignored) {
            // Chromecast hat die Verbindung abgebrochen (z. B. beim Spulen) – normal
        }
    }

    private static void proxy(String url, String type, @Nullable String range, boolean head, OutputStream out)
            throws IOException {
        long start = 0, end = -1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String r = range.substring(6).split(",")[0].trim();
            int dash = r.indexOf('-');
            try {
                if (dash == 0) {
                    // "bytes=-500" (letzte Bytes) – selten, wird wie ab 0 behandelt
                    start = 0;
                } else {
                    start = Long.parseLong(r.substring(0, dash));
                    if (dash < r.length() - 1) end = Long.parseLong(r.substring(dash + 1));
                }
                partial = true;
            } catch (NumberFormatException ignored) {
            }
        }

        // erstes Stück holen (liefert auch die Gesamtgröße)
        long firstEnd = end >= 0 ? Math.min(end, start + CHUNK - 1) : start + CHUNK - 1;
        if (head) firstEnd = start;
        Response r = fetch(url, start, firstEnd);
        try {
            if (r.code() == 416) {
                write(out, "HTTP/1.1 416 Range Not Satisfiable\r\n" + cors() + "Content-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            if (!r.isSuccessful()) {
                write(out, "HTTP/1.1 502 Bad Gateway\r\n" + cors() + "Content-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            long total = -1;
            String cr = r.header("Content-Range");
            if (cr != null && cr.contains("/")) {
                try {
                    total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (total < 0 && r.code() == 200 && r.body() != null) total = r.body().contentLength();
            if (total < 0) throw new IOException("Größe unbekannt");
            if (start >= total) {
                write(out, "HTTP/1.1 416 Range Not Satisfiable\r\n" + cors()
                        + "Content-Range: bytes */" + total + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            long last = end >= 0 ? Math.min(end, total - 1) : total - 1;
            long length = last - start + 1;

            StringBuilder h = new StringBuilder();
            if (partial) {
                h.append("HTTP/1.1 206 Partial Content\r\n")
                        .append("Content-Range: bytes ").append(start).append('-').append(last).append('/').append(total).append("\r\n");
            } else {
                h.append("HTTP/1.1 200 OK\r\n");
            }
            h.append(cors())
                    .append("Content-Type: ").append(type).append("\r\n")
                    .append("Content-Length: ").append(length).append("\r\n")
                    .append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
            write(out, h.toString());
            if (head) {
                out.flush();
                return;
            }

            byte[] buf = new byte[64 * 1024];
            long pos = start;
            ResponseBody body = r.body();
            if (body == null) throw new IOException("leer");
            InputStream is = body.byteStream();
            if (r.code() == 200 && start > 0) {
                long skip = start;
                while (skip > 0) {
                    long n = is.skip(skip);
                    if (n <= 0) throw new IOException("Spulen nicht möglich");
                    skip -= n;
                }
            }
            while (pos <= last) {
                int n = is.read(buf, 0, (int) Math.min(buf.length, last - pos + 1));
                if (n < 0) {
                    // nächstes Stück von YouTube holen
                    r.close();
                    r = fetch(url, pos, Math.min(last, pos + CHUNK - 1));
                    if (!r.isSuccessful() || r.body() == null) throw new IOException("HTTP " + r.code());
                    is = r.body().byteStream();
                    continue;
                }
                out.write(buf, 0, n);
                pos += n;
            }
            out.flush();
        } finally {
            r.close();
        }
    }

    private static Response fetch(String url, long start, long end) throws IOException {
        IOException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                return NetDownloader.get().client()
                        .newCall(NetDownloader.get().mediaRequest(url, start, end)).execute();
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private static String cors() {
        return "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n";
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("Zeile zu lang");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    // ------------------------------------------------------------------ Dienst

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tube:cast");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tube:cast");
            wifiLock.acquire();
        }
        CastContext cc = Caster.ctx(this);
        if (cc != null) {
            sessionListener = new EndListener();
            cc.getSessionManager().addSessionManagerListener(sessionListener, CastSession.class);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Chromecast",
                    NotificationManager.IMPORTANCE_LOW));
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CastServer.class).setAction(ACTION_STOP), piFlags);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, PlayerActivity.class), piFlags);
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Wird an den Fernseher gesendet")
                .setContentText(title)
                .setContentIntent(open)
                .addAction(0, "Beenden", stop)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFY_ID, n);
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            CastSession s = Caster.session(this);
            RemoteMediaClient rmc = s != null ? s.getRemoteMediaClient() : null;
            if (rmc != null) rmc.stop();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        CastContext cc = Caster.ctx(this);
        if (cc != null && sessionListener != null) {
            cc.getSessionManager().removeSessionManagerListener(sessionListener, CastSession.class);
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    /** Fernseher getrennt: Server beenden. */
    private final class EndListener implements SessionManagerListener<CastSession> {
        @Override public void onSessionEnded(@NonNull CastSession s, int error) { stopSelf(); }
        @Override public void onSessionStarting(@NonNull CastSession s) { }
        @Override public void onSessionStarted(@NonNull CastSession s, @NonNull String id) { }
        @Override public void onSessionStartFailed(@NonNull CastSession s, int error) { }
        @Override public void onSessionEnding(@NonNull CastSession s) { }
        @Override public void onSessionResuming(@NonNull CastSession s, @NonNull String id) { }
        @Override public void onSessionResumed(@NonNull CastSession s, boolean wasSuspended) { }
        @Override public void onSessionResumeFailed(@NonNull CastSession s, int error) { stopSelf(); }
        @Override public void onSessionSuspended(@NonNull CastSession s, int reason) { }
    }
}

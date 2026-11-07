package de.delorean23.tube;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;

import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

/**
 * Chromecast: beim Senden wird gefragt, ob in hoher Qualität (über das Handy)
 * oder direkt in 360p (Handy kann danach aus) abgespielt werden soll.
 * Created by DeLorean23
 */
public final class Caster {

    private Caster() {
    }

    public static CastContext ctx(Context c) {
        try {
            return CastContext.getSharedInstance(c.getApplicationContext());
        } catch (Throwable e) {
            return null; // keine Google-Play-Dienste
        }
    }

    public static void setupButton(Context c, View button) {
        if (!(button instanceof MediaRouteButton)) return;
        if (ctx(c) == null) {
            button.setVisibility(View.GONE);
            return;
        }
        try {
            CastButtonFactory.setUpMediaRouteButton(c.getApplicationContext(), (MediaRouteButton) button);
        } catch (Throwable e) {
            button.setVisibility(View.GONE);
        }
    }

    public static CastSession session(Context c) {
        CastContext cc = ctx(c);
        if (cc == null) return null;
        CastSession s = cc.getSessionManager().getCurrentCastSession();
        return s != null && s.isConnected() ? s : null;
    }

    public static boolean connected(Context c) {
        return session(c) != null;
    }

    private static String deviceName(CastSession s) {
        try {
            return s.getCastDevice() != null ? s.getCastDevice().getFriendlyName() : "Fernseher";
        } catch (Throwable e) {
            return "Fernseher";
        }
    }

    public interface Started {
        void onStarted();
    }

    /** Fragt nach der Variante und schickt das Video an den Chromecast. */
    public static void cast(Activity a, StreamInfo info, String url, long positionMs, Started started) {
        CastSession s = session(a);
        if (s == null) {
            Toast.makeText(a, "Erst auf das Cast-Symbol tippen und den Fernseher wählen",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (info == null) {
            Toast.makeText(a, "Video wird noch geladen …", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Auf " + deviceName(s) + " abspielen")
                .setItems(new String[]{
                        "Hohe Qualität (bis 1080p, läuft über das Handy – App offen lassen, Display darf aus)",
                        "Direkt (360p, Handy kann danach aus)",
                        "Auf dem Handy bleiben"}, (d, which) -> {
                    if (which == 0) castHigh(a, info, url, positionMs, started);
                    else if (which == 1) castDirect(a, info, positionMs, started);
                })
                .show();
    }

    private static void castDirect(Activity a, StreamInfo info, long pos, Started started) {
        VideoStream v = Streams.muxedMp4(info);
        if (v == null) {
            Toast.makeText(a, "Für dieses Video gibt es keine Direkt-Version – bitte „Hohe Qualität“ nehmen",
                    Toast.LENGTH_LONG).show();
            return;
        }
        load(a, info, v.getContent(), "video/mp4", pos, started);
    }

    private static void castHigh(Activity a, StreamInfo info, String url, long pos, Started started) {
        Toast.makeText(a, "Wird vorbereitet …", Toast.LENGTH_SHORT).show();
        App.IO.execute(() -> {
            try {
                String manifest = CastServer.prepare(a, info);
                App.MAIN.post(() -> load(a, info, manifest, "application/dash+xml", pos, started));
            } catch (Throwable e) {
                App.MAIN.post(() -> new AlertDialog.Builder(a)
                        .setMessage("Hohe Qualität geht hier nicht: " + e.getMessage()
                                + "\n\nStattdessen direkt (360p) senden?")
                        .setPositiveButton("Direkt senden", (d, w) -> castDirect(a, info, pos, started))
                        .setNegativeButton("Abbrechen", null)
                        .show());
            }
        });
    }

    private static void load(Activity a, StreamInfo info, String mediaUrl, String type, long pos,
                             Started started) {
        CastSession s = session(a);
        RemoteMediaClient rmc = s != null ? s.getRemoteMediaClient() : null;
        if (rmc == null) {
            Toast.makeText(a, "Verbindung zum Fernseher verloren", Toast.LENGTH_LONG).show();
            return;
        }
        MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        md.putString(MediaMetadata.KEY_TITLE, info.getName());
        if (info.getUploaderName() != null) md.putString(MediaMetadata.KEY_SUBTITLE, info.getUploaderName());
        String th = Util.thumb(info.getThumbnails());
        if (th != null) md.addImage(new WebImage(Uri.parse(th)));

        MediaInfo.Builder mi = new MediaInfo.Builder(mediaUrl)
                .setStreamType(Streams.isLive(info) ? MediaInfo.STREAM_TYPE_LIVE : MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(type)
                .setMetadata(md);
        if (info.getDuration() > 0) mi.setStreamDuration(info.getDuration() * 1000L);

        rmc.load(new MediaLoadRequestData.Builder()
                .setMediaInfo(mi.build())
                .setAutoplay(true)
                .setCurrentTime(Math.max(0, pos))
                .build())
                .setResultCallback(r -> {
                    if (!r.getStatus().isSuccess()) {
                        Toast.makeText(a, "Fernseher konnte das Video nicht laden (Fehler "
                                + r.getStatus().getStatusCode() + ")", Toast.LENGTH_LONG).show();
                    }
                });
        Toast.makeText(a, "Läuft auf " + deviceName(s), Toast.LENGTH_SHORT).show();
        if (started != null) started.onStarted();
    }
}

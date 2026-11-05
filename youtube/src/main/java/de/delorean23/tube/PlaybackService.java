package de.delorean23.tube;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.List;

/**
 * Wiedergabe im Hintergrund: läuft weiter, wenn die App im Hintergrund ist oder der Bildschirm aus ist.
 * Steuerung über Benachrichtigung und Sperrbildschirm.
 * Created by DeLorean23
 */
public class PlaybackService extends MediaSessionService {

    /** mediaId-Format: Video-URL + "\n" + gewünschte Höhe + "\n" + (a = nur Ton | v) */
    public static String mediaId(String url, int height, boolean audioOnly) {
        return url + "\n" + height + "\n" + (audioOnly ? "a" : "v");
    }

    /** Was gerade aufgelöst wurde (für die Anzeige im Player). */
    public static final class Resolved {
        public final String audioUrl;
        @Nullable public final String videoUrl;
        @Nullable public final String hlsUrl;
        public final int height;

        Resolved(String audioUrl, @Nullable String videoUrl, @Nullable String hlsUrl, int height) {
            this.audioUrl = audioUrl;
            this.videoUrl = videoUrl;
            this.hlsUrl = hlsUrl;
            this.height = height;
        }
    }

    private static volatile int currentHeight;

    public static int currentHeight() {
        return currentHeight;
    }

    private MediaSession session;
    private ExoPlayer player;

    @Override
    public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new SourceFactory())
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                // abgelaufene Links: beim nächsten Mal neu laden
                MediaItem cur = player.getCurrentMediaItem();
                if (cur != null) App.forgetStreamInfo(cur.mediaId.split("\n")[0]);
            }
        });

        Intent open = new Intent(this, PlayerActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        session = new MediaSession.Builder(this, player)
                .setSessionActivity(pi)
                .setCallback(new SessionCallback())
                .build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // App aus der Übersicht gewischt: weiterspielen, wenn gerade etwas läuft
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ Links auflösen

    private final class SessionCallback implements MediaSession.Callback {
        @NonNull
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(
                @NonNull MediaSession mediaSession,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull List<MediaItem> mediaItems) {
            SettableFuture<List<MediaItem>> future = SettableFuture.create();
            App.IO.execute(() -> {
                try {
                    List<MediaItem> out = new ArrayList<>();
                    for (MediaItem mi : mediaItems) out.add(resolve(mi));
                    future.set(out);
                } catch (Exception e) {
                    future.setException(e);
                }
            });
            return future;
        }
    }

    private MediaItem resolve(MediaItem in) throws Exception {
        String[] parts = in.mediaId.split("\n");
        String url = parts[0];
        int height = parts.length > 1 ? Integer.parseInt(parts[1]) : 720;
        boolean audioOnly = parts.length > 2 && parts[2].equals("a");

        StreamInfo info = App.streamInfo(url);
        Resolved res;
        if (Streams.isLive(info) && info.getHlsUrl() != null && !info.getHlsUrl().isEmpty()) {
            res = new Resolved(null, null, info.getHlsUrl(), 0);
        } else {
            AudioStream a = Streams.bestAudio(info, true);
            VideoStream v = audioOnly ? null : Streams.videoFor(info, height);
            if (a == null && v == null) throw new Exception("Keine abspielbare Spur gefunden");
            String videoUrl = v != null ? v.getContent() : null;
            String audioUrl = a != null ? a.getContent() : null;
            if (v != null && !v.isVideoOnly()) audioUrl = null; // Ton ist schon drin
            res = new Resolved(audioUrl, videoUrl, null, v != null ? Streams.height(v) : 0);
        }
        currentHeight = res.height;

        MediaMetadata.Builder md = in.mediaMetadata.buildUpon()
                .setTitle(info.getName())
                .setArtist(info.getUploaderName())
                .setDisplayTitle(info.getName());
        String thumb = Util.thumb(info.getThumbnails());
        if (thumb != null) md.setArtworkUri(Uri.parse(thumb));

        String main = res.hlsUrl != null ? res.hlsUrl
                : (res.videoUrl != null ? res.videoUrl : res.audioUrl);
        return in.buildUpon()
                .setUri(main)
                .setMediaMetadata(md.build())
                .setTag(res)
                .build();
    }

    /** Baut aus getrennter Video- und Tonspur eine gemeinsame Quelle. */
    private static final class SourceFactory implements MediaSource.Factory {
        private final ProgressiveMediaSource.Factory progressive =
                new ProgressiveMediaSource.Factory(new YtDataSource.Factory());
        private final HlsMediaSource.Factory hls = new HlsMediaSource.Factory(
                new DefaultHttpDataSource.Factory().setUserAgent(NetDownloader.USER_AGENT));

        @NonNull
        @Override
        public MediaSource.Factory setDrmSessionManagerProvider(
                @NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
            progressive.setDrmSessionManagerProvider(drmSessionManagerProvider);
            hls.setDrmSessionManagerProvider(drmSessionManagerProvider);
            return this;
        }

        @NonNull
        @Override
        public MediaSource.Factory setLoadErrorHandlingPolicy(
                @NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
            progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
            hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
            return this;
        }

        @NonNull
        @Override
        public int[] getSupportedTypes() {
            return new int[]{C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS};
        }

        @NonNull
        @Override
        public MediaSource createMediaSource(@NonNull MediaItem item) {
            Object tag = item.localConfiguration != null ? item.localConfiguration.tag : null;
            if (!(tag instanceof Resolved)) {
                return progressive.createMediaSource(item);
            }
            Resolved r = (Resolved) tag;
            if (r.hlsUrl != null) {
                return hls.createMediaSource(item.buildUpon().setUri(r.hlsUrl).build());
            }
            if (r.videoUrl != null && r.audioUrl != null) {
                MediaSource video = progressive.createMediaSource(
                        item.buildUpon().setUri(r.videoUrl).build());
                MediaSource audio = progressive.createMediaSource(
                        item.buildUpon().setUri(r.audioUrl).build());
                return new MergingMediaSource(true, video, audio);
            }
            String only = r.videoUrl != null ? r.videoUrl : r.audioUrl;
            return progressive.createMediaSource(item.buildUpon().setUri(only).build());
        }
    }
}

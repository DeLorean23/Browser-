package de.delorean23.tube;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

/**
 * Macht aus einer YouTube-URL abspielbare Quellen (getrennte Bild- und Tonspur, Live über HLS).
 * Wird vom Hintergrund-Player und von den Shorts benutzt.
 * Created by DeLorean23
 */
public final class TubeSources {

    private TubeSources() {
    }

    /** mediaId-Format: Video-URL + "\n" + gewünschte Höhe + "\n" + (a = nur Ton | v) */
    public static String mediaId(String url, int height, boolean audioOnly) {
        return url + "\n" + height + "\n" + (audioOnly ? "a" : "v");
    }

    public static String urlOf(MediaItem item) {
        return item == null ? null : item.mediaId.split("\n")[0];
    }

    /** Was gerade aufgelöst wurde. */
    public static final class Resolved {
        @Nullable public final String audioUrl;
        @Nullable public final String videoUrl;
        @Nullable public final String hlsUrl;
        public final int height;

        Resolved(@Nullable String audioUrl, @Nullable String videoUrl, @Nullable String hlsUrl, int height) {
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

    /** Nicht im UI-Thread aufrufen. */
    public static MediaItem resolve(MediaItem in) throws Exception {
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
    public static final class SourceFactory implements MediaSource.Factory {
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

package de.delorean23.tube;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Auswahl der passenden Video- und Audiospuren. Created by DeLorean23 */
public final class Streams {

    private Streams() {
    }

    private static boolean usable(Stream s) {
        return s != null && s.isUrl() && s.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP
                && s.getContent() != null && !s.getContent().isEmpty();
    }

    public static int height(VideoStream v) {
        int h = v.getHeight();
        if (h <= 0) h = Util.height(v.getResolution());
        return h;
    }

    private static boolean isAvc(VideoStream v) {
        String c = v.getCodec();
        return c != null && c.startsWith("avc");
    }

    private static boolean originalTrack(AudioStream a) {
        AudioTrackType t = a.getAudioTrackType();
        return t == null || t == AudioTrackType.ORIGINAL;
    }

    /** Beste AAC/M4A-Tonspur (für MP3-Umwandlung, MP4-Video und Wiedergabe). */
    public static AudioStream bestAudio(StreamInfo info, boolean m4aOnly) {
        AudioStream best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AudioStream a : info.getAudioStreams()) {
            if (!usable(a)) continue;
            boolean m4a = a.getFormat() == MediaFormat.M4A;
            if (m4aOnly && !m4a) continue;
            int score = a.getAverageBitrate();
            if (score <= 0) score = a.getBitrate() / 1000;
            if (m4a) score += 1000;                 // AAC läuft überall
            if (originalTrack(a)) score += 10000;   // Originalton statt Synchronisation
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
        }
        if (best == null && m4aOnly) return bestAudio(info, false);
        return best;
    }

    /** Alle Videoqualitäten (Höhe -> Stream), absteigend. forDownload: nur MP4/H.264 (für MP4-Datei). */
    public static LinkedHashMap<Integer, VideoStream> videoChoices(StreamInfo info, boolean forDownload) {
        Map<Integer, VideoStream> map = new java.util.HashMap<>();
        List<VideoStream> all = new ArrayList<>();
        all.addAll(info.getVideoOnlyStreams());
        all.addAll(info.getVideoStreams());
        for (VideoStream v : all) {
            if (!usable(v)) continue;
            int h = height(v);
            if (h <= 0) continue;
            boolean mp4 = v.getFormat() == MediaFormat.MPEG_4;
            if (forDownload && !(mp4 && (isAvc(v) || !v.isVideoOnly()))) continue;
            VideoStream old = map.get(h);
            if (old == null || better(v, old)) map.put(h, v);
        }
        List<Integer> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys, Collections.reverseOrder());
        LinkedHashMap<Integer, VideoStream> out = new LinkedHashMap<>();
        for (Integer k : keys) out.put(k, map.get(k));
        return out;
    }

    /** Bevorzugt: H.264 (alte Handys können das in Hardware), dann MP4, dann mehr fps. */
    private static boolean better(VideoStream a, VideoStream b) {
        int sa = score(a), sb = score(b);
        return sa > sb;
    }

    private static int score(VideoStream v) {
        int s = 0;
        if (isAvc(v)) s += 100;
        if (v.getFormat() == MediaFormat.MPEG_4) s += 50;
        if (!v.isVideoOnly()) s += 5;  // fertige Datei mit Ton
        s += Math.min(v.getFps(), 60) / 10;
        return s;
    }

    /** Videospur für die Wiedergabe: höchste Qualität <= wanted. */
    public static VideoStream videoFor(StreamInfo info, int wantedHeight) {
        LinkedHashMap<Integer, VideoStream> choices = videoChoices(info, false);
        VideoStream fallback = null;
        for (Map.Entry<Integer, VideoStream> e : choices.entrySet()) {
            fallback = e.getValue();
            if (e.getKey() <= wantedHeight) return e.getValue();
        }
        return fallback; // nur höhere vorhanden -> niedrigste davon
    }

    public static boolean isLive(StreamInfo info) {
        switch (info.getStreamType()) {
            case LIVE_STREAM:
            case AUDIO_LIVE_STREAM:
                return true;
            default:
                return false;
        }
    }
}

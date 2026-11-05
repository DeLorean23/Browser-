package de.delorean23.tube;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.schabi.newpipe.extractor.Image;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Created by DeLorean23 */
public final class Util {

    private static final Pattern ID = Pattern.compile(
            "(?:v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})");
    private static final Pattern CHANNEL = Pattern.compile("/channel/(UC[A-Za-z0-9_-]{22})");

    private Util() {
    }

    public static String videoId(String url) {
        if (url == null) return null;
        Matcher m = ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static String channelId(String url) {
        if (url == null) return null;
        Matcher m = CHANNEL.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static String watchUrl(String id) {
        return "https://www.youtube.com/watch?v=" + id;
    }

    /** Sucht einen YouTube-Link in einem geteilten Text. */
    public static String findYoutubeUrl(String text) {
        if (text == null) return null;
        String id = videoId(text);
        return id != null ? watchUrl(id) : null;
    }

    public static String thumb(List<Image> images) {
        if (images == null || images.isEmpty()) return null;
        Image best = null;
        for (Image i : images) {
            if (best == null) {
                best = i;
                continue;
            }
            int h = i.getHeight();
            int bh = best.getHeight();
            // bevorzugt etwa 360 Pixel Höhe: gut genug und spart Daten
            if (h > 0 && (bh <= 0 || Math.abs(h - 360) < Math.abs(bh - 360))) best = i;
        }
        return best.getUrl();
    }

    public static String duration(long seconds) {
        if (seconds <= 0) return "";
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return String.format(Locale.GERMANY, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.GERMANY, "%d:%02d", m, s);
    }

    public static String count(long n) {
        if (n < 0) return "";
        if (n >= 1_000_000_000L) return trim(n / 1e9) + " Mrd.";
        if (n >= 1_000_000L) return trim(n / 1e6) + " Mio.";
        if (n >= 1_000L) return trim(n / 1e3) + " Tsd.";
        return String.valueOf(n);
    }

    private static String trim(double d) {
        String s = d >= 100 ? String.format(Locale.GERMANY, "%.0f", d)
                : String.format(Locale.GERMANY, "%.1f", d);
        return s.endsWith(",0") ? s.substring(0, s.length() - 2) : s;
    }

    public static String views(long n) {
        return n < 0 ? "" : count(n) + " Aufrufe";
    }

    public static boolean isOnWifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Exception e) {
            return true;
        }
    }

    /** Zahl aus "720p60" -> 720 */
    public static int height(String resolution) {
        if (resolution == null) return 0;
        int i = 0, n = 0;
        while (i < resolution.length() && Character.isDigit(resolution.charAt(i))) {
            n = n * 10 + (resolution.charAt(i) - '0');
            i++;
        }
        return n;
    }

    public static String safeFileName(String s) {
        if (s == null || s.trim().isEmpty()) return "video";
        String r = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (r.length() > 120) r = r.substring(0, 120);
        return r;
    }
}

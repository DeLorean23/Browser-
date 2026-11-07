package de.delorean23.tube;

import android.content.Context;
import android.content.SharedPreferences;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Woher die Shorts kommen: neue Shorts der abonnierten Kanäle oder Shorts-Suche.
 * Created by DeLorean23
 */
public final class ShortsSource {

    private ShortsSource() {
    }

    // ------------------------------------------------------------------ Abos

    private static final OkHttpClient NO_REDIRECT = NetDownloader.get().client().newBuilder()
            .followRedirects(false).followSslRedirects(false).build();

    private static SharedPreferences cache() {
        return App.get().getSharedPreferences("shorts_cache", Context.MODE_PRIVATE);
    }

    /** Neue Shorts aus den Abos (aus dem Abo-Feed herausgefiltert). Nicht im UI-Thread aufrufen. */
    public static List<Item> fromSubscriptions(boolean refresh) throws Exception {
        List<Item> feed = Feed.load(refresh);
        List<Item> candidates = new ArrayList<>();
        for (Item it : feed) {
            if (it.url != null && Util.videoId(it.url) != null) candidates.add(it);
            if (candidates.size() >= 150) break;
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> checks = new ArrayList<>();
        for (Item it : candidates) {
            final String id = Util.videoId(it.url);
            checks.add(pool.submit(() -> isShort(id)));
        }
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            try {
                if (checks.get(i).get(20, TimeUnit.SECONDS)) {
                    Item it = candidates.get(i);
                    it.shorts = true;
                    out.add(it);
                }
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        return out;
    }

    /**
     * YouTube-Trick: youtube.com/shorts/ID antwortet bei echten Shorts direkt,
     * bei normalen Videos mit Weiterleitung auf /watch. Ergebnis wird gemerkt.
     */
    private static boolean isShort(String id) {
        SharedPreferences c = cache();
        if (c.contains(id)) return c.getBoolean(id, false);
        Request req = new Request.Builder()
                .url("https://www.youtube.com/shorts/" + id)
                .head()
                .header("User-Agent", NetDownloader.USER_AGENT)
                .header("Cookie", "SOCS=CAI; CONSENT=PENDING+999")
                .build();
        try (Response r = NO_REDIRECT.newCall(req).execute()) {
            Boolean result = null;
            if (r.code() == 200) {
                result = true;
            } else if (r.isRedirect()) {
                String loc = r.header("Location", "");
                if (loc.contains("/watch")) result = false;
            }
            if (result == null) return false; // unklar (z. B. Zustimmungsseite): nicht merken
            c.edit().putBoolean(id, result).apply();
            return result;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ Suche

    public static final class SearchResult {
        public final List<Item> items;
        public final Page next;

        SearchResult(List<Item> items, Page next) {
            this.items = items;
            this.next = next;
        }
    }

    public static SearchQueryHandler query(String topic) throws Exception {
        return ServiceList.YouTube.getSearchQHFactory()
                .fromQuery(topic + " #shorts", Collections.emptyList(), "");
    }

    public static SearchResult search(SearchQueryHandler q) throws Exception {
        SearchInfo info = SearchInfo.getInfo(ServiceList.YouTube, q);
        return new SearchResult(filter(info.getRelatedItems()), info.hasNextPage() ? info.getNextPage() : null);
    }

    public static SearchResult more(SearchQueryHandler q, Page page) throws Exception {
        ListExtractor.InfoItemsPage<InfoItem> p = SearchInfo.getMoreItems(ServiceList.YouTube, q, page);
        return new SearchResult(filter(p.getItems()), p.hasNextPage() ? p.getNextPage() : null);
    }

    /** Nur echte Shorts behalten (oder sehr kurze Videos, falls YouTube sie nicht kennzeichnet). */
    private static List<Item> filter(List<InfoItem> items) {
        List<Item> out = new ArrayList<>();
        for (InfoItem ii : items) {
            if (!(ii instanceof StreamInfoItem)) continue;
            StreamInfoItem s = (StreamInfoItem) ii;
            long d = s.getDuration();
            boolean shortForm = s.isShortFormContent()
                    || (s.getUrl() != null && s.getUrl().contains("/shorts/"))
                    || (d > 0 && d <= 75);
            if (!shortForm) continue;
            Item it = Item.from(s);
            if (it == null || it.live) continue;
            it.shorts = true;
            out.add(it);
        }
        return out;
    }
}

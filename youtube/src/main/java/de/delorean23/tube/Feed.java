package de.delorean23.tube;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Abo-Feed: neue Videos der abonnierten Kanäle (über die RSS-Feeds von YouTube, spart API-Kontingent).
 * Created by DeLorean23
 */
public final class Feed {

    private static List<String[]> subs;
    private static List<Item> cache;
    private static long cacheTime;

    private Feed() {
    }

    public static synchronized void clear() {
        subs = null;
        cache = null;
    }

    public static synchronized List<String[]> subscriptions(boolean refresh) throws Exception {
        if (subs == null || refresh) subs = Account.subscriptions();
        return subs;
    }

    public static List<Item> load(boolean refresh) throws Exception {
        synchronized (Feed.class) {
            if (!refresh && cache != null && System.currentTimeMillis() - cacheTime < 10 * 60_000L) {
                return cache;
            }
        }
        List<String[]> channels = subscriptions(refresh);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<List<Item>>> futures = new ArrayList<>();
        for (String[] ch : channels) {
            futures.add(pool.submit(() -> rss(ch[0])));
        }
        List<Item> all = new ArrayList<>();
        for (Future<List<Item>> f : futures) {
            try {
                all.addAll(f.get(30, TimeUnit.SECONDS));
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        Collections.sort(all, (a, b) -> Long.compare(b.published, a.published));
        if (all.size() > 300) all = new ArrayList<>(all.subList(0, 300));
        synchronized (Feed.class) {
            cache = all;
            cacheTime = System.currentTimeMillis();
        }
        return all;
    }

    private static List<Item> rss(String channelId) throws Exception {
        List<Item> out = new ArrayList<>();
        Request req = new Request.Builder()
                .url("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId)
                .header("User-Agent", NetDownloader.USER_AGENT)
                .build();
        try (Response r = NetDownloader.get().client().newCall(req).execute()) {
            if (!r.isSuccessful() || r.body() == null) return out;
            InputStream in = r.body().byteStream();
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            Item cur = null;
            String channelName = null;
            boolean inAuthor = false;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                String name = p.getName();
                if (ev == XmlPullParser.START_TAG) {
                    if ("entry".equals(name)) {
                        cur = new Item();
                        cur.uploaderUrl = "https://www.youtube.com/channel/" + channelId;
                        cur.uploader = channelName;
                    } else if ("author".equals(name)) {
                        inAuthor = true;
                    } else if (cur == null) {
                        if (inAuthor && "name".equals(name)) channelName = p.nextText();
                    } else {
                        if ("yt:videoId".equals(name) || "videoId".equals(name)) {
                            cur.url = Util.watchUrl(p.nextText().trim());
                        } else if ("title".equals(name) && cur.title == null) {
                            cur.title = p.nextText();
                        } else if ("name".equals(name) && inAuthor) {
                            cur.uploader = p.nextText();
                        } else if ("published".equals(name)) {
                            String d = p.nextText();
                            try {
                                cur.published = fmt.parse(d.substring(0, 19)).getTime();
                                cur.date = new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
                                        .format(new java.util.Date(cur.published));
                            } catch (Exception ignored) {
                            }
                        } else if ("media:thumbnail".equals(name) || "thumbnail".equals(name)) {
                            cur.thumb = p.getAttributeValue(null, "url");
                        } else if ("media:statistics".equals(name) || "statistics".equals(name)) {
                            try {
                                cur.views = Long.parseLong(p.getAttributeValue(null, "views"));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("author".equals(name)) {
                        inAuthor = false;
                    } else if ("entry".equals(name) && cur != null) {
                        // Shorts sind im Feed nicht erkennbar; alles übernehmen
                        if (cur.url != null) out.add(cur);
                        cur = null;
                    }
                }
                ev = p.next();
            }
        }
        return out;
    }
}

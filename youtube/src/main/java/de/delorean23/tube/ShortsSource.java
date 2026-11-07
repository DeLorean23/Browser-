package de.delorean23.tube;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Woher die Shorts kommen: aus dem Shorts-Bereich der Kanäle.
 * "Abos" = deine Kanäle (zuletzt aktive zuerst), Thema = passende Kanäle aus der Suche.
 * Created by DeLorean23
 */
public final class ShortsSource {

    private static final int CHANNELS_PER_LOAD = 6;

    private ShortsSource() {
    }

    /** Merkt sich, welche Kanäle schon geladen wurden (zum Weiterblättern). */
    public static final class Cursor {
        final boolean subs;
        final int perChannel;
        SearchQueryHandler query;
        Page searchPage;
        boolean searchStarted;
        final List<String> channels = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        int used;

        Cursor(boolean subs, int perChannel) {
            this.subs = subs;
            this.perChannel = perChannel;
        }

        public boolean hasMore() {
            return used < channels.size() || (!subs && (!searchStarted || searchPage != null));
        }
    }

    /** Shorts der abonnierten Kanäle. Nicht im UI-Thread aufrufen. */
    public static Cursor subscriptions() throws Exception {
        Cursor c = new Cursor(true, 6);
        // Reihenfolge: Kanäle mit den neuesten Uploads zuerst
        Set<String> order = new LinkedHashSet<>();
        try {
            for (Item it : Feed.load(false)) {
                if (it.uploaderUrl != null) order.add(it.uploaderUrl);
            }
        } catch (Exception ignored) {
        }
        for (String[] s : Feed.subscriptions(false)) {
            order.add("https://www.youtube.com/channel/" + s[0]);
        }
        c.channels.addAll(order);
        return c;
    }

    public static Cursor topic(String topic) throws Exception {
        Cursor c = new Cursor(false, 8);
        c.query = ServiceList.YouTube.getSearchQHFactory().fromQuery(topic,
                Collections.singletonList(YoutubeSearchQueryHandlerFactory.CHANNELS), "");
        return c;
    }

    /** Lädt die nächsten Shorts (aus den nächsten paar Kanälen, gemischt). */
    public static List<Item> next(Cursor c) throws Exception {
        if (!c.subs && c.used >= c.channels.size()) moreChannels(c);
        int from = c.used;
        int to = Math.min(c.channels.size(), from + CHANNELS_PER_LOAD);
        c.used = to;
        List<String> batch = c.channels.subList(from, to);
        if (batch.isEmpty()) return new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(CHANNELS_PER_LOAD);
        List<Future<List<Item>>> futures = new ArrayList<>();
        for (String ch : batch) futures.add(pool.submit(() -> channelShorts(ch, c.perChannel)));
        List<List<Item>> lists = new ArrayList<>();
        for (Future<List<Item>> f : futures) {
            try {
                lists.add(f.get(40, TimeUnit.SECONDS));
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();

        // abwechselnd aus den Kanälen nehmen, damit es gemischt ist
        List<Item> out = new ArrayList<>();
        boolean any = true;
        for (int i = 0; any; i++) {
            any = false;
            for (List<Item> l : lists) {
                if (i < l.size()) {
                    any = true;
                    Item it = l.get(i);
                    String id = Util.videoId(it.url);
                    if (id != null && c.seen.add(id)) out.add(it);
                }
            }
        }
        return out;
    }

    private static void moreChannels(Cursor c) throws Exception {
        if (!c.searchStarted) {
            c.searchStarted = true;
            SearchInfo info = SearchInfo.getInfo(ServiceList.YouTube, c.query);
            addChannels(c, info.getRelatedItems());
            c.searchPage = info.hasNextPage() ? info.getNextPage() : null;
        } else if (c.searchPage != null) {
            ListExtractor.InfoItemsPage<InfoItem> p =
                    SearchInfo.getMoreItems(ServiceList.YouTube, c.query, c.searchPage);
            addChannels(c, p.getItems());
            c.searchPage = p.hasNextPage() ? p.getNextPage() : null;
        }
    }

    private static void addChannels(Cursor c, List<InfoItem> items) {
        for (InfoItem ii : items) {
            if (ii instanceof ChannelInfoItem && ii.getUrl() != null && !c.channels.contains(ii.getUrl())) {
                c.channels.add(ii.getUrl());
            }
        }
    }

    /** Die neuesten Shorts eines Kanals (Shorts-Bereich der Kanalseite). */
    private static List<Item> channelShorts(String channelUrl, int max) throws Exception {
        ChannelInfo ch = ChannelInfo.getInfo(ServiceList.YouTube, channelUrl);
        ListLinkHandler tab = null;
        for (ListLinkHandler t : ch.getTabs()) {
            if (t.getContentFilters().contains(ChannelTabs.SHORTS)) {
                tab = t;
                break;
            }
        }
        List<Item> out = new ArrayList<>();
        if (tab == null) return out;
        ChannelTabInfo info = ChannelTabInfo.getInfo(ServiceList.YouTube, tab);
        String avatar = Util.thumb(ch.getAvatars());
        for (InfoItem ii : info.getRelatedItems()) {
            if (!(ii instanceof StreamInfoItem)) continue;
            Item it = Item.from(ii);
            if (it == null || it.url == null) continue;
            it.shorts = true;
            if (it.uploader == null || it.uploader.isEmpty()) it.uploader = ch.getName();
            if (it.uploaderUrl == null) it.uploaderUrl = channelUrl;
            it.extra = avatar;
            out.add(it);
            if (out.size() >= max) break;
        }
        return out;
    }
}

package de.delorean23.tube;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;

/** Kanal, Playlist, "Mag ich", Verlauf oder Abo-Liste. Created by DeLorean23 */
public class ListActivity extends AppCompatActivity {

    public static final int MODE_CHANNEL = 1, MODE_PLAYLIST = 2, MODE_API_PLAYLIST = 3,
            MODE_LIKED = 4, MODE_HISTORY = 5, MODE_SUBS = 6;

    public static Intent intent(Context c, int mode, String arg, String title) {
        return new Intent(c, ListActivity.class)
                .putExtra("mode", mode)
                .putExtra("arg", arg)
                .putExtra("title", title);
    }

    private int mode;
    private String arg;
    private ItemAdapter adapter;
    private SwipeRefreshLayout refresh;
    private TextView emptyText, btnSubscribe, title;
    private int loadToken;
    private boolean loadingMore;

    // Weiterblättern
    private Page next;
    private ListLinkHandler channelTab;

    private String channelId;
    private String subscriptionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        mode = getIntent().getIntExtra("mode", MODE_HISTORY);
        arg = getIntent().getStringExtra("arg");

        title = findViewById(R.id.listTitle);
        title.setText(getIntent().getStringExtra("title"));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        emptyText = findViewById(R.id.emptyText);
        btnSubscribe = findViewById(R.id.btnSubscribe);
        refresh = findViewById(R.id.refresh);
        refresh.setColorSchemeResources(R.color.accent);
        refresh.setProgressBackgroundColorSchemeResource(R.color.card);
        refresh.setOnRefreshListener(this::load);

        RecyclerView list = findViewById(R.id.list);
        adapter = new ItemAdapter(this, this::open);
        list.setLayoutManager(ItemAdapter.layout(this, adapter));
        list.setAdapter(adapter);
        adapter.setOnNearEnd(this::loadMore);

        if (mode == MODE_CHANNEL) {
            channelId = Util.channelId(arg);
            btnSubscribe.setOnClickListener(v -> toggleSubscribe());
            if (Account.isLoggedIn() && channelId != null) checkSubscription();
        }

        refresh.setRefreshing(true);
        load();
    }

    private void open(Item it) {
        if (it.type == Item.VIDEO) {
            startActivity(PlayerActivity.intent(this, it.url, it));
        } else if (it.type == Item.CHANNEL) {
            startActivity(intent(this, MODE_CHANNEL, it.url, it.title));
        } else if (it.apiPlaylistId != null) {
            startActivity(intent(this, MODE_API_PLAYLIST, it.apiPlaylistId, it.title));
        } else {
            startActivity(intent(this, MODE_PLAYLIST, it.url, it.title));
        }
    }

    private void done(int token, List<Item> items) {
        App.MAIN.post(() -> {
            if (token != loadToken) return;
            refresh.setRefreshing(false);
            adapter.set(items);
            emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            emptyText.setText(R.string.empty);
        });
    }

    private void fail(int token, Exception e) {
        App.MAIN.post(() -> {
            if (token != loadToken) return;
            refresh.setRefreshing(false);
            emptyText.setVisibility(View.VISIBLE);
            emptyText.setText(getString(R.string.error_load) + "\n\n" + e.getMessage());
        });
    }

    private void load() {
        final int token = ++loadToken;
        next = null;
        App.IO.execute(() -> {
            try {
                List<Item> items = new ArrayList<>();
                switch (mode) {
                    case MODE_HISTORY:
                        items.addAll(History.all());
                        break;
                    case MODE_LIKED:
                        items.addAll(Account.likedVideos());
                        break;
                    case MODE_API_PLAYLIST:
                        items.addAll(Account.playlistItems(arg));
                        break;
                    case MODE_SUBS:
                        for (String[] s : Feed.subscriptions(true)) {
                            Item it = new Item();
                            it.type = Item.CHANNEL;
                            it.url = "https://www.youtube.com/channel/" + s[0];
                            it.title = s[1];
                            it.thumb = s[2];
                            it.extra = "Kanal";
                            items.add(it);
                        }
                        break;
                    case MODE_PLAYLIST: {
                        PlaylistInfo info = PlaylistInfo.getInfo(ServiceList.YouTube, arg);
                        for (StreamInfoItem s : info.getRelatedItems()) add(items, s);
                        next = info.hasNextPage() ? info.getNextPage() : null;
                        break;
                    }
                    case MODE_CHANNEL: {
                        ChannelInfo ch = ChannelInfo.getInfo(ServiceList.YouTube, arg);
                        if (channelId == null) channelId = ch.getId();
                        App.MAIN.post(() -> title.setText(ch.getName()));
                        ListLinkHandler videosTab = null;
                        for (ListLinkHandler t : ch.getTabs()) {
                            if (t.getContentFilters().contains(ChannelTabs.VIDEOS)) {
                                videosTab = t;
                                break;
                            }
                        }
                        if (videosTab == null && !ch.getTabs().isEmpty()) videosTab = ch.getTabs().get(0);
                        if (videosTab != null) {
                            ChannelTabInfo tabInfo = ChannelTabInfo.getInfo(ServiceList.YouTube, videosTab);
                            for (InfoItem ii : tabInfo.getRelatedItems()) add(items, ii);
                            channelTab = videosTab;
                            next = tabInfo.hasNextPage() ? tabInfo.getNextPage() : null;
                        }
                        if (Account.isLoggedIn() && channelId != null) {
                            App.MAIN.post(this::checkSubscription);
                        }
                        break;
                    }
                    default:
                        break;
                }
                done(token, items);
            } catch (Throwable e) {
                fail(token, e);
            }
        });
    }

    private static void add(List<Item> out, InfoItem ii) {
        Item it = Item.from(ii);
        if (it != null) out.add(it);
    }

    private void loadMore() {
        if (loadingMore || next == null) return;
        if (mode != MODE_PLAYLIST && mode != MODE_CHANNEL) return;
        loadingMore = true;
        final Page page = next;
        final int token = loadToken;
        App.IO.execute(() -> {
            try {
                List<Item> more = new ArrayList<>();
                Page n;
                if (mode == MODE_PLAYLIST) {
                    ListExtractor.InfoItemsPage<StreamInfoItem> p =
                            PlaylistInfo.getMoreItems(ServiceList.YouTube, arg, page);
                    for (StreamInfoItem s : p.getItems()) add(more, s);
                    n = p.hasNextPage() ? p.getNextPage() : null;
                } else {
                    ListExtractor.InfoItemsPage<InfoItem> p =
                            ChannelTabInfo.getMoreItems(ServiceList.YouTube, channelTab, page);
                    for (InfoItem ii : p.getItems()) add(more, ii);
                    n = p.hasNextPage() ? p.getNextPage() : null;
                }
                App.MAIN.post(() -> {
                    if (token != loadToken) return;
                    next = n;
                    adapter.add(more);
                    loadingMore = false;
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> loadingMore = false);
            }
        });
    }

    // ------------------------------------------------------------ Abonnieren

    private void checkSubscription() {
        if (channelId == null) return;
        App.IO.execute(() -> {
            try {
                String id = Account.subscriptionId(channelId);
                App.MAIN.post(() -> {
                    subscriptionId = id;
                    updateSubscribe();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void updateSubscribe() {
        btnSubscribe.setVisibility(View.VISIBLE);
        if (subscriptionId != null) {
            btnSubscribe.setText("Abonniert");
            btnSubscribe.setBackgroundResource(R.drawable.chip_bg);
            btnSubscribe.setTextColor(getResources().getColor(R.color.text));
        } else {
            btnSubscribe.setText("Abonnieren");
            btnSubscribe.setBackgroundResource(R.drawable.sub_bg);
            btnSubscribe.setTextColor(getResources().getColor(R.color.black));
        }
    }

    private void toggleSubscribe() {
        if (channelId == null) return;
        final String sub = subscriptionId;
        App.IO.execute(() -> {
            try {
                String newId = null;
                if (sub != null) Account.unsubscribe(sub);
                else newId = Account.subscribe(channelId);
                final String id = newId;
                App.MAIN.post(() -> {
                    subscriptionId = id;
                    updateSubscribe();
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }
}

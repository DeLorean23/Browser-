package de.delorean23.tube;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shorts: Vollbild hochkant, nach oben wischen = nächstes Short.
 * Quellen: Shorts deiner Abos oder Shorts-Suche nach Themen.
 * Created by DeLorean23
 */
public class ShortsActivity extends AppCompatActivity {

    private static final String SRC_SUBS = "@abos";
    private static final String SRC_SEARCH = "@suche";

    private RecyclerView pager;
    private LinearLayoutManager lm;
    private PagerSnapHelper snap;
    private ShortsAdapter adapter;
    private LinearLayout chips;
    private View emptyBox, progress;
    private TextView emptyText;

    private ExoPlayer player;
    private int current = -1;
    private int playToken;
    private Holder currentHolder;

    private final LinkedHashMap<String, String> sources = new LinkedHashMap<>();
    private String source;
    private SearchQueryHandler query;
    private Page next;
    private boolean loadingMore;
    private int loadToken;

    private final Map<String, StreamInfo> infos = new HashMap<>();
    private final Map<String, String> ratings = new HashMap<>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (currentHolder != null && player != null) {
                long d = player.getDuration();
                currentHolder.progress.setProgress(d > 0 && d != C.TIME_UNSET
                        ? (int) (player.getCurrentPosition() * 1000 / d) : 0);
            }
            pager.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shorts);
        pager = findViewById(R.id.pager);
        chips = findViewById(R.id.chips);
        emptyBox = findViewById(R.id.emptyBox);
        emptyText = findViewById(R.id.emptyText);
        progress = findViewById(R.id.progress);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        lm = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        pager.setLayoutManager(lm);
        adapter = new ShortsAdapter();
        pager.setAdapter(adapter);
        snap = new PagerSnapHelper();
        snap.attachToRecyclerView(pager);
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) onSettled();
            }
        });

        // Ton holt sich den Audio-Fokus: ein laufendes Video im Hintergrund pausiert dann automatisch
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new TubeSources.SourceFactory())
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .build();
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (currentHolder != null) {
                    currentHolder.loading.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                    if (state == Player.STATE_READY) currentHolder.thumb.setVisibility(View.INVISIBLE);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (currentHolder != null) currentHolder.loading.setVisibility(View.GONE);
                Toast.makeText(ShortsActivity.this, "Short kann nicht abgespielt werden – weiterwischen",
                        Toast.LENGTH_SHORT).show();
            }
        });

        setupSources();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (current >= 0 && player.getMediaItemCount() > 0) player.play();
        pager.removeCallbacks(tick);
        pager.post(tick);
    }

    @Override
    protected void onPause() {
        player.pause();
        pager.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        player.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------ Quellen

    private void setupSources() {
        if (Account.isLoggedIn()) sources.put(SRC_SUBS, "Abos");
        sources.put("Musik", "Musik");
        sources.put("Gaming", "Gaming");
        sources.put("Lustig", "Lustig");
        sources.put("Sport", "Sport");
        sources.put("Tiere", "Tiere");
        sources.put("Autos", "Autos");
        sources.put("Essen", "Essen");
        sources.put(SRC_SEARCH, "Suchen …");
        chips.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (Map.Entry<String, String> e : sources.entrySet()) {
            TextView c = new TextView(this);
            c.setText(e.getValue());
            c.setTextSize(14);
            c.setGravity(android.view.Gravity.CENTER);
            c.setPadding((int) (14 * d), 0, (int) (14 * d), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (int) (32 * d));
            lp.rightMargin = (int) (8 * d);
            c.setLayoutParams(lp);
            c.setTag(e.getKey());
            c.setOnClickListener(v -> {
                String key = (String) v.getTag();
                if (key.equals(SRC_SEARCH)) askSearch();
                else select(key, null);
            });
            chips.addView(c);
        }
        select(sources.keySet().iterator().next(), null);
    }

    private void updateChips() {
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView c = (TextView) chips.getChildAt(i);
            boolean on = c.getTag().equals(source);
            c.setBackgroundResource(on ? R.drawable.chip_on_bg : R.drawable.chip_bg);
            c.setTextColor(getResources().getColor(on ? R.color.black : R.color.text));
        }
    }

    private void askSearch() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("z. B. DeLorean");
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(p, p / 2, p, 0);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Shorts suchen")
                .setView(box)
                .setPositiveButton("Suchen", (d, w) -> {
                    String q = et.getText().toString().trim();
                    if (!q.isEmpty()) select(SRC_SEARCH, q);
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void select(String key, String searchText) {
        source = key;
        updateChips();
        final int token = ++loadToken;
        player.stop();
        player.clearMediaItems();
        current = -1;
        currentHolder = null;
        next = null;
        query = null;
        adapter.set(new ArrayList<>());
        emptyBox.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        final String topic = searchText != null ? searchText : key;
        App.IO.execute(() -> {
            try {
                List<Item> items;
                SearchQueryHandler q = null;
                Page n = null;
                if (key.equals(SRC_SUBS)) {
                    items = ShortsSource.fromSubscriptions(false);
                } else {
                    q = ShortsSource.query(topic);
                    ShortsSource.SearchResult r = ShortsSource.search(q);
                    items = r.items;
                    n = r.next;
                }
                final SearchQueryHandler fq = q;
                final Page fn = n;
                App.MAIN.post(() -> {
                    if (token != loadToken) return;
                    progress.setVisibility(View.GONE);
                    query = fq;
                    next = fn;
                    adapter.set(items);
                    if (items.isEmpty()) {
                        emptyBox.setVisibility(View.VISIBLE);
                        emptyText.setText(key.equals(SRC_SUBS)
                                ? "In deinen Abos gibt es gerade keine neuen Shorts."
                                : "Keine Shorts gefunden.");
                    } else {
                        pager.scrollToPosition(0);
                        pager.post(this::onSettled);
                    }
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> {
                    if (token != loadToken) return;
                    progress.setVisibility(View.GONE);
                    emptyBox.setVisibility(View.VISIBLE);
                    emptyText.setText("Laden fehlgeschlagen:\n" + e.getMessage());
                });
            }
        });
    }

    private void loadMore() {
        if (loadingMore || next == null || query == null) return;
        loadingMore = true;
        final int token = loadToken;
        final SearchQueryHandler q = query;
        final Page page = next;
        App.IO.execute(() -> {
            try {
                ShortsSource.SearchResult r = ShortsSource.more(q, page);
                App.MAIN.post(() -> {
                    loadingMore = false;
                    if (token != loadToken) return;
                    next = r.next;
                    adapter.add(r.items);
                    // nur Kurzvideos gefunden? weiterblättern
                    if (r.items.isEmpty() && next != null) loadMore();
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> loadingMore = false);
            }
        });
    }

    // ------------------------------------------------------------ Abspielen

    private void onSettled() {
        View v = snap.findSnapView(lm);
        if (v == null) return;
        int pos = lm.getPosition(v);
        if (pos == current || pos < 0) return;
        RecyclerView.ViewHolder vh = pager.findViewHolderForAdapterPosition(pos);
        if (!(vh instanceof Holder)) return;
        play(pos, (Holder) vh);
        if (pos >= adapter.getItemCount() - 3) loadMore();
    }

    private void play(int pos, Holder h) {
        if (currentHolder != null) {
            currentHolder.video.setPlayer(null);
            currentHolder.thumb.setVisibility(View.VISIBLE);
            currentHolder.paused.setVisibility(View.GONE);
            currentHolder.loading.setVisibility(View.GONE);
        }
        current = pos;
        currentHolder = h;
        h.video.setPlayer(player);
        h.loading.setVisibility(View.VISIBLE);
        player.stop();
        player.clearMediaItems();
        final int token = ++playToken;
        final Item it = adapter.get(pos);
        final boolean wifi = Util.isOnWifi(this);
        App.IO.execute(() -> {
            try {
                MediaItem mi = TubeSources.resolve(new MediaItem.Builder()
                        .setMediaId(TubeSources.mediaId(it.url, wifi ? 1280 : 854, false)).build());
                StreamInfo info = App.streamInfo(it.url);
                App.MAIN.post(() -> {
                    if (token != playToken || isFinishing()) return;
                    infos.put(it.url, info);
                    h.bindInfo(info);
                    player.setMediaItem(mi);
                    player.prepare();
                    player.play();
                    Item hist = new Item();
                    hist.url = Util.watchUrl(Util.videoId(it.url));
                    hist.title = info.getName();
                    hist.uploader = info.getUploaderName();
                    hist.uploaderUrl = info.getUploaderUrl();
                    hist.thumb = it.thumb;
                    hist.duration = info.getDuration();
                    History.add(hist);
                    if (Account.isLoggedIn()) loadRating(it.url, h);
                });
                // nächstes Short schon mal vorbereiten
                if (pos + 1 < adapter.getItemCount()) {
                    try {
                        App.streamInfo(adapter.get(pos + 1).url);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable e) {
                App.MAIN.post(() -> {
                    if (token != playToken) return;
                    h.loading.setVisibility(View.GONE);
                    Toast.makeText(this, "Short nicht verfügbar: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void togglePause() {
        if (currentHolder == null) return;
        if (player.isPlaying()) {
            player.pause();
            currentHolder.paused.setVisibility(View.VISIBLE);
        } else {
            player.play();
            currentHolder.paused.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------ Like / Teilen / Download

    private void loadRating(String url, Holder h) {
        final String vid = Util.videoId(url);
        App.IO.execute(() -> {
            try {
                String r = Account.getRating(vid);
                App.MAIN.post(() -> {
                    ratings.put(url, r);
                    if (h.url != null && h.url.equals(url)) h.updateLike();
                });
            } catch (Throwable ignored) {
            }
        });
    }

    private void toggleLike(Holder h) {
        if (!Account.isLoggedIn()) {
            Toast.makeText(this, "Zum Liken auf der Startseite oben rechts mit Google anmelden",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String url = h.url;
        final String vid = Util.videoId(url);
        final String newRating = "like".equals(ratings.get(url)) ? "none" : "like";
        ratings.put(url, newRating);
        h.updateLike();
        App.IO.execute(() -> {
            try {
                Account.rate(vid, newRating);
            } catch (Throwable e) {
                App.MAIN.post(() -> {
                    ratings.put(url, "like".equals(newRating) ? "none" : "like");
                    h.updateLike();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ------------------------------------------------------------ Liste

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb, paused;
        final PlayerView video;
        final ProgressBar loading, progress;
        final TextView channel, title, like;
        String url;
        StreamInfo info;

        @SuppressLint("ClickableViewAccessibility")
        Holder(View v) {
            super(v);
            thumb = v.findViewById(R.id.sThumb);
            paused = v.findViewById(R.id.sPaused);
            video = v.findViewById(R.id.sVideo);
            loading = v.findViewById(R.id.sLoading);
            progress = v.findViewById(R.id.sProgress);
            channel = v.findViewById(R.id.sChannel);
            title = v.findViewById(R.id.sTitle);
            like = v.findViewById(R.id.sLike);
            v.findViewById(R.id.sTouch).setOnClickListener(x -> togglePause());
            like.setOnClickListener(x -> toggleLike(this));
            v.findViewById(R.id.sDownload).setOnClickListener(x ->
                    DownloadDialog.show(ShortsActivity.this, info, url));
            v.findViewById(R.id.sShare).setOnClickListener(x -> {
                String id = Util.videoId(url);
                if (id == null) return;
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "https://youtube.com/shorts/" + id), "Teilen"));
            });
            channel.setOnClickListener(x -> {
                String cu = info != null ? info.getUploaderUrl() : null;
                if (cu != null) startActivity(ListActivity.intent(ShortsActivity.this,
                        ListActivity.MODE_CHANNEL, cu, info.getUploaderName()));
            });
        }

        void bind(Item it) {
            url = it.url;
            info = infos.get(it.url);
            title.setText(it.title);
            channel.setText(it.uploader != null ? "@" + it.uploader.replace("@", "") : "");
            like.setText("Mag ich");
            progress.setProgress(0);
            paused.setVisibility(View.GONE);
            loading.setVisibility(View.GONE);
            thumb.setVisibility(View.VISIBLE);
            String th = it.thumb;
            String id = Util.videoId(it.url);
            if (id != null) th = "https://i.ytimg.com/vi/" + id + "/oar2.jpg"; // hochkant-Vorschau
            final String fallback = it.thumb;
            Glide.with(ShortsActivity.this).load(th)
                    .error(Glide.with(ShortsActivity.this).load(fallback))
                    .into(thumb);
            if (info != null) bindInfo(info);
            updateLike();
        }

        void bindInfo(StreamInfo si) {
            info = si;
            title.setText(si.getName());
            if (si.getUploaderName() != null) channel.setText("@" + si.getUploaderName().replace("@", ""));
            long likes = si.getLikeCount();
            like.setText(likes >= 0 ? Util.count(likes) : "Mag ich");
        }

        void updateLike() {
            boolean on = "like".equals(ratings.get(url));
            like.setCompoundDrawablesWithIntrinsicBounds(0, on ? R.drawable.ic_like_on : R.drawable.ic_like, 0, 0);
        }
    }

    private final class ShortsAdapter extends RecyclerView.Adapter<Holder> {
        private final List<Item> items = new ArrayList<>();

        void set(List<Item> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        void add(List<Item> list) {
            // doppelte vermeiden
            List<Item> fresh = new ArrayList<>();
            for (Item it : list) {
                boolean dup = false;
                for (Item o : items) {
                    if (o.url != null && o.url.equals(it.url)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) fresh.add(it);
            }
            int start = items.size();
            items.addAll(fresh);
            notifyItemRangeInserted(start, fresh.size());
        }

        Item get(int pos) {
            return items.get(pos);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_short, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            h.bind(items.get(position));
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            if (h == currentHolder) {
                h.video.setPlayer(null);
            }
        }
    }
}

package de.delorean23.tube;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.common.util.concurrent.ListenableFuture;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Videoseite mit Player, Like, Abo, Download, Chromecast und Hintergrund-Wiedergabe.
 * Video nach unten ziehen (oder Zurück) = Mini-Player.
 * Created by DeLorean23
 */
public class PlayerActivity extends AppCompatActivity {

    public static Intent intent(Context c, String url, Item item) {
        Intent i = new Intent(c, PlayerActivity.class).putExtra("url", url);
        if (item != null) {
            i.putExtra("title", item.title).putExtra("uploader", item.uploader)
                    .putExtra("thumb", item.thumb);
        }
        return i;
    }

    private PlayerView playerView;
    private DragFrame playerBox;
    private View root, loading, header;
    private RecyclerView details;
    private ItemAdapter related;
    private TextView vTitle, vMeta, vDesc, chName, chSubs, btnSubscribe, btnLike, btnAudio;
    private ImageView chAvatar;

    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;

    private String url;
    private StreamInfo info;
    private String pendingUrl;
    private boolean audioOnly;
    private String rating = "none";
    private String subscriptionId;
    private String channelId;
    private boolean descExpanded;
    private boolean minimizing;
    private String castAskedFor;
    private SessionManagerListener<CastSession> castListener;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        prefs = Playback.prefs();
        audioOnly = Playback.audioOnly();

        root = findViewById(R.id.root);
        playerView = findViewById(R.id.playerView);
        playerBox = findViewById(R.id.playerBox);
        loading = findViewById(R.id.playerLoading);
        details = findViewById(R.id.details);

        // LayoutManager zuerst setzen, sonst stürzt das Aufbauen des Kopfteils ab
        details.setLayoutManager(new LinearLayoutManager(this));
        header = LayoutInflater.from(this).inflate(R.layout.player_header, details, false);
        vTitle = header.findViewById(R.id.vTitle);
        vMeta = header.findViewById(R.id.vMeta);
        vDesc = header.findViewById(R.id.vDesc);
        chName = header.findViewById(R.id.chName);
        chSubs = header.findViewById(R.id.chSubs);
        chAvatar = header.findViewById(R.id.chAvatar);
        btnSubscribe = header.findViewById(R.id.btnSubscribe);
        btnLike = header.findViewById(R.id.btnLike);
        btnAudio = header.findViewById(R.id.btnAudio);

        related = new ItemAdapter(this, it -> {
            if (it.type == Item.VIDEO) openVideo(it.url, it);
            else if (it.type == Item.CHANNEL)
                startActivity(ListActivity.intent(this, ListActivity.MODE_CHANNEL, it.url, it.title));
            else startActivity(ListActivity.intent(this, ListActivity.MODE_PLAYLIST, it.url, it.title));
        });
        related.setCompact(true);
        related.setHeader(header);
        details.setAdapter(related);

        btnLike.setOnClickListener(v -> toggleLike());
        btnSubscribe.setOnClickListener(v -> toggleSubscribe());
        header.findViewById(R.id.btnDownload).setOnClickListener(v -> DownloadDialog.show(this, info, url));
        header.findViewById(R.id.btnQuality).setOnClickListener(v -> askQuality());
        header.findViewById(R.id.btnShare).setOnClickListener(v -> share());
        header.findViewById(R.id.btnCast).setOnClickListener(v -> castNow());
        btnAudio.setOnClickListener(v -> toggleAudioOnly());
        header.findViewById(R.id.channelRow).setOnClickListener(v -> {
            if (info != null && info.getUploaderUrl() != null) {
                startActivity(ListActivity.intent(this, ListActivity.MODE_CHANNEL,
                        info.getUploaderUrl(), info.getUploaderName()));
            }
        });
        vDesc.setOnClickListener(v -> {
            descExpanded = !descExpanded;
            vDesc.setMaxLines(descExpanded ? Integer.MAX_VALUE : 3);
        });
        vDesc.setMovementMethod(LinkMovementMethod.getInstance());

        playerView.setFullscreenButtonClickListener(full -> setOrientation(full
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
        findViewById(R.id.btnMinimize).setOnClickListener(v -> minimize());
        Caster.setupButton(this, findViewById(R.id.castButton));
        setupDrag();

        updateAudioButton();
        applyOrientation(getResources().getConfiguration());
        handleIntent(getIntent());
        connect();
    }

    /** Android 8.0 erlaubt bei durchsichtigen Fenstern keine feste Ausrichtung. */
    private void setOrientation(int o) {
        if (Build.VERSION.SDK_INT == 26) return;
        try {
            setRequestedOrientation(o);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        String u = i.getStringExtra("url");
        if (u == null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            u = i.getData().toString();
        }
        if (u == null && Intent.ACTION_SEND.equals(i.getAction())) {
            u = Util.findYoutubeUrl(i.getStringExtra(Intent.EXTRA_TEXT));
            if (u == null) {
                Toast.makeText(this, "Kein YouTube-Link gefunden", Toast.LENGTH_LONG).show();
                if (info == null) finish();
                return;
            }
        }
        if (u == null) return; // aus Mini-Player/Benachrichtigung: aktuelles Video zeigen
        String id = Util.videoId(u);
        if (id != null) u = Util.watchUrl(id);
        i.removeExtra("url");

        String t = i.getStringExtra("title");
        vTitle.setText(t != null ? t : "Wird geladen …");
        chName.setText(t != null ? i.getStringExtra("uploader") : "");
        vMeta.setText("");
        chSubs.setText("");
        chAvatar.setImageDrawable(null);
        openVideo(u, null);
    }

    private void openVideo(String u, Item it) {
        if (it != null) {
            vTitle.setText(it.title);
            chName.setText(it.uploader);
        }
        url = u;
        info = null;
        rating = "none";
        subscriptionId = null;
        channelId = null;
        descExpanded = false;
        vDesc.setMaxLines(3);
        related.set(null);
        details.scrollToPosition(0);
        if (controller != null) {
            if (!isCurrent(u)) startPlayback(u, 0);
        } else {
            pendingUrl = u;
        }
        loadInfo(u);
    }

    /** Läuft genau dieses Video schon (z. B. aus dem Mini-Player wieder groß gemacht)? */
    private boolean isCurrent(String u) {
        if (controller == null) return false;
        MediaItem cur = controller.getCurrentMediaItem();
        String cu = TubeSources.urlOf(cur);
        return cu != null && u != null && cu.equals(u) && controller.getPlaybackState() != Player.STATE_IDLE;
    }

    // ------------------------------------------------------------ Player

    private void connect() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
            } catch (Throwable e) {
                Toast.makeText(this, "Player nicht verfügbar: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            if (!isFinishing() && hasWindowFocusOrResumed()) playerView.setPlayer(controller);
            controller.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    loading.setVisibility(View.GONE);
                    Toast.makeText(PlayerActivity.this, "Wiedergabefehler: "
                            + error.getMessage(), Toast.LENGTH_LONG).show();
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state != Player.STATE_IDLE) loading.setVisibility(View.GONE);
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (isPlaying) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
            Playback.setVideo(controller, true);
            if (pendingUrl != null) {
                String u = pendingUrl;
                pendingUrl = null;
                if (!isCurrent(u)) startPlayback(u, 0);
            } else if (url == null) {
                // aus Mini-Player/Benachrichtigung: laufendes Video anzeigen
                MediaItem cur = controller.getCurrentMediaItem();
                if (cur != null) {
                    url = TubeSources.urlOf(cur);
                    CharSequence t = controller.getMediaMetadata().title;
                    vTitle.setText(t != null ? t : "Wird geladen …");
                    loadInfo(url);
                } else {
                    finish();
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private boolean resumed;

    private boolean hasWindowFocusOrResumed() {
        return resumed;
    }

    private int wantedHeight() {
        boolean wifi = Util.isOnWifi(this);
        return prefs.getInt(wifi ? "height_wifi" : "height_mobile", wifi ? 720 : 480);
    }

    private void startPlayback(String u, long positionMs) {
        if (controller == null) return;
        loading.setVisibility(View.VISIBLE);
        MediaMetadata.Builder md = new MediaMetadata.Builder();
        CharSequence t = vTitle.getText();
        if (t != null && t.length() > 0) md.setTitle(t);
        MediaItem item = new MediaItem.Builder()
                .setMediaId(TubeSources.mediaId(u, wantedHeight(), audioOnly))
                .setMediaMetadata(md.build())
                .build();
        controller.setMediaItem(item, positionMs);
        controller.prepare();
        if (Caster.connected(this)) {
            // läuft schon auf dem Fernseher: nicht zusätzlich am Handy abspielen
            controller.pause();
        } else {
            controller.play();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (controller != null) Playback.setVideo(controller, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (controller != null) playerView.setPlayer(controller);
        CastContext cc = Caster.ctx(this);
        if (cc != null) {
            castListener = new CastListener();
            cc.getSessionManager().addSessionManagerListener(castListener, CastSession.class);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        // Bild an den Mini-Player abgeben
        playerView.setPlayer(null);
        CastContext cc = Caster.ctx(this);
        if (cc != null && castListener != null) {
            cc.getSessionManager().removeSessionManagerListener(castListener, CastSession.class);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // App im Hintergrund oder Bildschirm aus: Ton läuft weiter, Bild wird nicht mehr geladen
        if (controller != null && App.visibleScreens() == 0) Playback.setVideo(controller, false);
    }

    @Override
    protected void onDestroy() {
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyOrientation(newConfig);
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void applyOrientation(Configuration c) {
        boolean land = c.orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) playerBox.getLayoutParams();
        if (land) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.weight = 1;
            details.setVisibility(View.GONE);
            playerView.setFullscreenButtonState(true);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            int w = getResources().getDisplayMetrics().widthPixels;
            lp.height = w * 9 / 16;
            lp.weight = 0;
            details.setVisibility(View.VISIBLE);
            playerView.setFullscreenButtonState(false);
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        playerBox.setLayoutParams(lp);
        playerBox.setDragEnabled(!land);
    }

    @Override
    public void onBackPressed() {
        if (isLandscape()) {
            setOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            playerView.postDelayed(() ->
                    setOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED), 1500);
            return;
        }
        minimize();
    }

    // ------------------------------------------------------------ Verkleinern (Mini-Player)

    private void setupDrag() {
        playerBox.setListener(new DragFrame.Listener() {
            @Override
            public void onDrag(float dy) {
                applyDrag(dy);
            }

            @Override
            public void onRelease(float dy, float velocityY) {
                float h = root.getHeight();
                if (dy > h * 0.2f || velocityY > 1500) {
                    minimize();
                } else {
                    root.animate().translationY(0).setDuration(180).start();
                    playerBox.animate().scaleX(1).scaleY(1).translationX(0).setDuration(180).start();
                    details.animate().alpha(1).setDuration(180).start();
                    setBackgroundAlpha(255);
                }
            }
        });
    }

    private void applyDrag(float dy) {
        float h = Math.max(1, root.getHeight());
        float p = Math.min(1f, dy / (h * 0.6f));
        root.setTranslationY(dy);
        playerBox.setPivotX(playerBox.getWidth());
        playerBox.setPivotY(0);
        float s = 1f - 0.5f * p;
        playerBox.setScaleX(s);
        playerBox.setScaleY(s);
        details.setAlpha(Math.max(0f, 1f - p * 2f));
        setBackgroundAlpha((int) (255 * (1f - p)));
    }

    private void setBackgroundAlpha(int a) {
        Drawable bg = root.getBackground();
        if (bg != null) bg.mutate().setAlpha(Math.max(0, Math.min(255, a)));
    }

    /** Video schrumpft nach unten rechts, die Seite darunter wird sichtbar, Ton/Bild laufen weiter. */
    private void minimize() {
        if (minimizing) return;
        minimizing = true;
        if (isTaskRoot()) {
            // per Link geöffnet: Startseite darunter öffnen, damit der Mini-Player dort weiterläuft
            startActivity(new Intent(this, MainActivity.class));
        }
        float density = getResources().getDisplayMetrics().density;
        float miniW = 200 * density, miniH = 113 * density, margin = 12 * density, bottomBar = 56 * density;
        float boxW = Math.max(1, playerBox.getWidth());
        float s = miniW / boxW;
        float targetTop = root.getHeight() - bottomBar - margin - miniH;
        playerBox.setPivotX(playerBox.getWidth());
        playerBox.setPivotY(0);
        DecelerateInterpolator di = new DecelerateInterpolator();
        details.animate().alpha(0).setDuration(150).start();
        setBackgroundAlpha(0);
        playerBox.animate().scaleX(s).scaleY(s).translationX(-margin).setInterpolator(di).setDuration(220).start();
        root.animate().translationY(targetTop).setInterpolator(di).setDuration(220)
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, R.anim.fade_out);
                }).start();
    }

    // ------------------------------------------------------------ Chromecast

    private void castNow() {
        long pos = controller != null ? controller.getCurrentPosition() : 0;
        Caster.cast(this, info, url, pos, () -> {
            castAskedFor = url;
            if (controller != null) controller.pause();
        });
    }

    /** Neues Video, während der Fernseher verbunden ist: einmal fragen. */
    private void maybeAskCast() {
        if (info != null && url != null && !url.equals(castAskedFor) && Caster.connected(this)) {
            castAskedFor = url;
            castNow();
        }
    }

    private final class CastListener implements SessionManagerListener<CastSession> {
        @Override public void onSessionStarted(@NonNull CastSession s, @NonNull String id) {
            castAskedFor = null;
            maybeAskCast();
        }
        @Override public void onSessionResumed(@NonNull CastSession s, boolean wasSuspended) { }
        @Override public void onSessionStarting(@NonNull CastSession s) { }
        @Override public void onSessionStartFailed(@NonNull CastSession s, int error) {
            Toast.makeText(PlayerActivity.this, "Verbindung zum Fernseher fehlgeschlagen", Toast.LENGTH_LONG).show();
        }
        @Override public void onSessionEnding(@NonNull CastSession s) { }
        @Override public void onSessionEnded(@NonNull CastSession s, int error) { castAskedFor = null; }
        @Override public void onSessionResuming(@NonNull CastSession s, @NonNull String id) { }
        @Override public void onSessionResumeFailed(@NonNull CastSession s, int error) { }
        @Override public void onSessionSuspended(@NonNull CastSession s, int reason) { }
    }

    // ------------------------------------------------------------ Infos

    private void loadInfo(String u) {
        App.IO.execute(() -> {
            try {
                StreamInfo si = App.streamInfo(u);
                App.MAIN.post(() -> {
                    if (!u.equals(url) || isFinishing()) return;
                    showInfo(si);
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> {
                    if (!u.equals(url)) return;
                    loading.setVisibility(View.GONE);
                    vMeta.setText("Fehler beim Laden: " + e.getMessage());
                });
            }
        });
    }

    private void showInfo(StreamInfo si) {
        info = si;
        vTitle.setText(si.getName());
        StringBuilder meta = new StringBuilder();
        if (Streams.isLive(si)) meta.append("LIVE");
        else if (si.getViewCount() >= 0) meta.append(Util.views(si.getViewCount()));
        if (si.getTextualUploadDate() != null) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(si.getTextualUploadDate());
        }
        vMeta.setText(meta);
        chName.setText(si.getUploaderName());
        long subs = si.getUploaderSubscriberCount();
        chSubs.setText(subs >= 0 ? Util.count(subs) + " Abonnenten" : "");
        String av = Util.thumb(si.getUploaderAvatars());
        if (av != null) Glide.with(this).load(av).circleCrop().into(chAvatar);
        long likes = si.getLikeCount();
        btnLike.setText(likes >= 0 ? Util.count(likes) : "Mag ich");

        Description d = si.getDescription();
        if (d != null && d.content() != null && !d.content().isEmpty()) {
            vDesc.setVisibility(View.VISIBLE);
            if (d.type() == Description.Type.HTML) {
                vDesc.setText(Build.VERSION.SDK_INT >= 24
                        ? Html.fromHtml(d.content(), Html.FROM_HTML_MODE_LEGACY)
                        : Html.fromHtml(d.content()));
            } else {
                vDesc.setText(d.content());
            }
        } else {
            vDesc.setVisibility(View.GONE);
        }

        List<Item> rel = new ArrayList<>();
        for (InfoItem ii : si.getRelatedItems()) {
            Item it = Item.from(ii);
            if (it != null) rel.add(it);
        }
        related.set(rel);

        Item h = new Item();
        h.url = url;
        h.title = si.getName();
        h.uploader = si.getUploaderName();
        h.uploaderUrl = si.getUploaderUrl();
        h.thumb = Util.thumb(si.getThumbnails());
        h.duration = si.getDuration();
        History.add(h);

        channelId = Util.channelId(si.getUploaderUrl());
        updateLike();
        updateSubscribe();
        if (Account.isLoggedIn()) loadAccountState(url);
        maybeAskCast();
    }

    // ------------------------------------------------------------ Konto-Aktionen

    private void loadAccountState(String u) {
        final String vid = Util.videoId(u);
        final String ch = channelId;
        App.IO.execute(() -> {
            try {
                String r = vid != null ? Account.getRating(vid) : "none";
                String s = ch != null ? Account.subscriptionId(ch) : null;
                App.MAIN.post(() -> {
                    if (!u.equals(url)) return;
                    rating = r;
                    subscriptionId = s;
                    updateLike();
                    updateSubscribe();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void updateLike() {
        boolean on = "like".equals(rating);
        btnLike.setCompoundDrawablesWithIntrinsicBounds(on ? R.drawable.ic_like_on : R.drawable.ic_like,
                0, 0, 0);
    }

    private void updateSubscribe() {
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

    private boolean needLogin() {
        if (Account.isLoggedIn()) return false;
        Toast.makeText(this, "Bitte zuerst auf der Startseite oben rechts mit Google anmelden",
                Toast.LENGTH_LONG).show();
        return true;
    }

    private void toggleLike() {
        if (needLogin() || url == null) return;
        final String vid = Util.videoId(url);
        final String newRating = "like".equals(rating) ? "none" : "like";
        rating = newRating;
        updateLike();
        App.IO.execute(() -> {
            try {
                Account.rate(vid, newRating);
            } catch (Throwable e) {
                App.MAIN.post(() -> {
                    rating = "like".equals(newRating) ? "none" : "like";
                    updateLike();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void toggleSubscribe() {
        if (needLogin() || channelId == null) return;
        final String sub = subscriptionId;
        App.IO.execute(() -> {
            try {
                String id = null;
                if (sub != null) Account.unsubscribe(sub);
                else id = Account.subscribe(channelId);
                final String nid = id;
                App.MAIN.post(() -> {
                    subscriptionId = nid;
                    updateSubscribe();
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void share() {
        if (url == null) return;
        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url);
        if (info != null) s.putExtra(Intent.EXTRA_SUBJECT, info.getName());
        startActivity(Intent.createChooser(s, "Teilen"));
    }

    // ------------------------------------------------------------ Qualität / Nur Ton

    private void updateAudioButton() {
        btnAudio.setCompoundDrawablesWithIntrinsicBounds(
                audioOnly ? R.drawable.ic_headphones_on : R.drawable.ic_headphones, 0, 0, 0);
    }

    private void toggleAudioOnly() {
        audioOnly = !audioOnly;
        prefs.edit().putBoolean("audio_only", audioOnly).apply();
        updateAudioButton();
        Toast.makeText(this, audioOnly ? "Nur Ton (spart Daten)" : "Video an", Toast.LENGTH_SHORT).show();
        restartAtCurrentPosition();
    }

    private void restartAtCurrentPosition() {
        if (controller == null || url == null) return;
        long pos = controller.getCurrentPosition();
        startPlayback(url, pos);
        Playback.setVideo(controller, true);
    }

    private void askQuality() {
        if (info == null) {
            Toast.makeText(this, "Video wird noch geladen …", Toast.LENGTH_SHORT).show();
            return;
        }
        LinkedHashMap<Integer, VideoStream> choices = Streams.videoChoices(info, false);
        if (choices.isEmpty()) {
            Toast.makeText(this, "Keine Auswahl verfügbar", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Integer> heights = new ArrayList<>(choices.keySet());
        String[] labels = new String[heights.size()];
        int current = TubeSources.currentHeight();
        int checked = -1;
        for (int i = 0; i < heights.size(); i++) {
            int h = heights.get(i);
            VideoStream v = choices.get(h);
            labels[i] = Streams.label(v);
            if (h == current) checked = i;
        }
        final boolean wifi = Util.isOnWifi(this);
        new AlertDialog.Builder(this)
                .setTitle("Qualität (" + (wifi ? "WLAN" : "mobile Daten") + ")")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    prefs.edit().putInt(wifi ? "height_wifi" : "height_mobile", heights.get(which)).apply();
                    if (audioOnly) {
                        audioOnly = false;
                        prefs.edit().putBoolean("audio_only", false).apply();
                        updateAudioButton();
                    }
                    d.dismiss();
                    restartAtCurrentPosition();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }
}

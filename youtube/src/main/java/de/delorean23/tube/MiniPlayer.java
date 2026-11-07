package de.delorean23.tube;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Schwebendes Mini-Video unten rechts (Startseite, Kanäle, Playlists).
 * Antippen = wieder groß, X = Wiedergabe beenden, verschiebbar mit dem Finger.
 * Created by DeLorean23
 */
public final class MiniPlayer {

    private final Activity activity;
    private final View root;
    private final PlayerView video;
    private final ImageView thumb;
    private final ImageButton play;
    private final ProgressBar progress;
    private ListenableFuture<MediaController> future;
    private MediaController controller;
    private boolean resumed;
    private String shownThumb;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            root.postDelayed(this, 1000);
        }
    };

    public MiniPlayer(Activity a, ViewGroup host) {
        activity = a;
        root = LayoutInflater.from(a).inflate(R.layout.mini_player, host, false);
        host.addView(root);
        video = root.findViewById(R.id.miniVideo);
        thumb = root.findViewById(R.id.miniThumb);
        play = root.findViewById(R.id.miniPlay);
        progress = root.findViewById(R.id.miniProgress);
        root.setClipToOutline(true);

        play.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.isPlaying()) controller.pause();
            else {
                if (controller.getPlaybackState() == Player.STATE_ENDED) controller.seekTo(0);
                controller.play();
            }
        });
        root.findViewById(R.id.miniClose).setOnClickListener(v -> close());
        setupTouch(root.findViewById(R.id.miniTouch));
        connect();
    }

    private void connect() {
        SessionToken token = new SessionToken(activity, new ComponentName(activity, PlaybackService.class));
        future = new MediaController.Builder(activity, token).buildAsync();
        future.addListener(() -> {
            try {
                controller = future.get();
            } catch (Throwable e) {
                return;
            }
            controller.addListener(new Player.Listener() {
                @Override
                public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
                    update();
                }
            });
            if (resumed) attach();
            update();
        }, ContextCompat.getMainExecutor(activity));
    }

    /** Läuft gerade etwas, das man als Mini-Player zeigen sollte? */
    private boolean hasMedia() {
        return controller != null && controller.getCurrentMediaItem() != null
                && controller.getPlaybackState() != Player.STATE_IDLE;
    }

    private void update() {
        boolean show = hasMedia() && resumed;
        root.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        play.setImageResource(controller.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        MediaItem cur = controller.getCurrentMediaItem();
        MediaMetadata md = controller.getMediaMetadata();
        String th = md.artworkUri != null ? md.artworkUri.toString() : null;
        if (th == null && cur != null) {
            String id = Util.videoId(TubeSources.urlOf(cur));
            if (id != null) th = "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
        }
        if (th != null && !th.equals(shownThumb)) {
            shownThumb = th;
            Glide.with(activity).load(th).centerCrop().into(thumb);
        }
        updateProgress();
    }

    private void updateProgress() {
        if (controller == null || root.getVisibility() != View.VISIBLE) return;
        long dur = controller.getDuration();
        long pos = controller.getCurrentPosition();
        progress.setProgress(dur > 0 && dur != C.TIME_UNSET ? (int) (pos * 1000 / dur) : 0);
    }

    private void attach() {
        if (controller == null) return;
        video.setPlayer(controller);
        Playback.setVideo(controller, true);
    }

    private void close() {
        if (controller != null) {
            controller.stop();
            controller.clearMediaItems();
        }
        root.setVisibility(View.GONE);
    }

    private void expand() {
        Intent i = new Intent(activity, PlayerActivity.class);
        activity.startActivity(i);
        activity.overridePendingTransition(R.anim.slide_up, 0);
    }

    // ------------------------------------------------------------ Lebenszyklus (aus der Activity)

    public void onResume() {
        resumed = true;
        attach();
        update();
        root.removeCallbacks(tick);
        root.postDelayed(tick, 1000);
    }

    public void onPause() {
        resumed = false;
        root.removeCallbacks(tick);
        video.setPlayer(null);
    }

    public void onStop() {
        // App ganz im Hintergrund oder Display aus: nur noch Ton laden
        if (controller != null && App.visibleScreens() == 0) Playback.setVideo(controller, false);
    }

    public void release() {
        root.removeCallbacks(tick);
        if (future != null) MediaController.releaseFuture(future);
    }

    // ------------------------------------------------------------ Verschieben / Antippen

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouch(View touch) {
        final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        final float[] start = new float[4];
        final boolean[] moved = {false};
        touch.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawX();
                    start[1] = e.getRawY();
                    start[2] = root.getTranslationX();
                    start[3] = root.getTranslationY();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - start[0], dy = e.getRawY() - start[1];
                    if (!moved[0] && Math.hypot(dx, dy) < slop) return true;
                    moved[0] = true;
                    View parent = (View) root.getParent();
                    // innerhalb des sichtbaren Bereichs halten
                    float minX = -(root.getLeft()), maxX = parent.getWidth() - root.getRight();
                    float minY = -(root.getTop()), maxY = parent.getHeight() - root.getBottom();
                    root.setTranslationX(clamp(start[2] + dx, minX, maxX));
                    root.setTranslationY(clamp(start[3] + dy, minY, maxY));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) expand();
                    else snapX();
                    return true;
                default:
                    return true;
            }
        });
    }

    /** Nach dem Loslassen an den linken oder rechten Rand schieben. */
    private void snapX() {
        View parent = (View) root.getParent();
        float centerX = root.getLeft() + root.getTranslationX() + root.getWidth() / 2f;
        float target = centerX < parent.getWidth() / 2f ? -root.getLeft() + dp(12) : 0;
        root.animate().translationX(target).setDuration(180).start();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Hilfsfunktion: Startet die Wiedergabe-Activity groß (z. B. aus der Benachrichtigung). */
    public static void open(Context c) {
        c.startActivity(new Intent(c, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}

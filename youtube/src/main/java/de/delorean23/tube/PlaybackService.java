package de.delorean23.tube;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.ArrayList;
import java.util.List;

/**
 * Wiedergabe im Hintergrund: läuft weiter, wenn die App im Hintergrund ist oder der Bildschirm aus ist.
 * Steuerung über Benachrichtigung und Sperrbildschirm.
 * Created by DeLorean23
 */
public class PlaybackService extends MediaSessionService {

    private MediaSession session;
    private ExoPlayer player;

    @Override
    public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new TubeSources.SourceFactory())
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                // abgelaufene Links: beim nächsten Mal neu laden
                MediaItem cur = player.getCurrentMediaItem();
                if (cur != null) App.forgetStreamInfo(TubeSources.urlOf(cur));
            }
        });

        Intent open = new Intent(this, PlayerActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        session = new MediaSession.Builder(this, player)
                .setSessionActivity(pi)
                .setCallback(new SessionCallback())
                .build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // App aus der Übersicht gewischt: weiterspielen, wenn gerade etwas läuft
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private static final class SessionCallback implements MediaSession.Callback {
        @NonNull
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(
                @NonNull MediaSession mediaSession,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull List<MediaItem> mediaItems) {
            SettableFuture<List<MediaItem>> future = SettableFuture.create();
            App.IO.execute(() -> {
                try {
                    List<MediaItem> out = new ArrayList<>();
                    for (MediaItem mi : mediaItems) out.add(TubeSources.resolve(mi));
                    future.set(out);
                } catch (Throwable e) {
                    future.setException(e);
                }
            });
            return future;
        }
    }
}

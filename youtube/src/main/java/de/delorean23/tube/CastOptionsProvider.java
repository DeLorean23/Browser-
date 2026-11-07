package de.delorean23.tube;

import android.content.Context;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import com.google.android.gms.cast.framework.media.CastMediaOptions;
import com.google.android.gms.cast.framework.media.NotificationOptions;

import java.util.List;

/** Chromecast: Standard-Empfänger von Google, Steuerung in der Benachrichtigung. Created by DeLorean23 */
public class CastOptionsProvider implements OptionsProvider {

    @Override
    public CastOptions getCastOptions(Context context) {
        NotificationOptions notification = new NotificationOptions.Builder()
                .setTargetActivityClassName(PlayerActivity.class.getName())
                .build();
        CastMediaOptions media = new CastMediaOptions.Builder()
                .setNotificationOptions(notification)
                .build();
        return new CastOptions.Builder()
                .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                .setCastMediaOptions(media)
                .setStopReceiverApplicationWhenEndingSession(true)
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        return null;
    }
}

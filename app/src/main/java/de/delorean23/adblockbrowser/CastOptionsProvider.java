package de.delorean23.adblockbrowser;

import android.content.Context;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import com.google.android.gms.cast.framework.media.CastMediaOptions;
import com.google.android.gms.cast.framework.media.NotificationOptions;

import java.util.List;

/** Chromecast-Einstellungen: Standard-Empfänger von Google, Steuerung in der Benachrichtigung. */
public class CastOptionsProvider implements OptionsProvider {

    @Override
    public CastOptions getCastOptions(Context context) {
        NotificationOptions notification = new NotificationOptions.Builder()
                .setTargetActivityClassName(MainActivity.class.getName())
                .build();
        CastMediaOptions media = new CastMediaOptions.Builder()
                .setNotificationOptions(notification)
                .build();
        return new CastOptions.Builder()
                .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                .setCastMediaOptions(media)
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        return null;
    }
}

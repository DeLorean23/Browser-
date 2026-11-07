package de.delorean23.tube;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Google-Konto (Anmelden über die normale Google-Kontoauswahl) und YouTube Data API v3:
 * Abos, Playlists, "Mag ich", Abonnieren und Liken.
 * Created by DeLorean23
 */
public final class Account {

    public static final String SCOPE = "https://www.googleapis.com/auth/youtube";
    private static final String API = "https://www.googleapis.com/youtube/v3/";
    private static final String PREFS = "account";

    private static String token;
    private static long tokenTime;

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private Account() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isLoggedIn() {
        return prefs().getBoolean("logged_in", false);
    }

    public static String name() {
        return prefs().getString("name", null);
    }

    public static String avatar() {
        return prefs().getString("avatar", null);
    }

    private static AuthorizationRequest request() {
        return AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
                .build();
    }

    /**
     * Startet die Anmeldung. Öffnet bei Bedarf die Google-Kontoauswahl
     * (Ergebnis kommt über {@link #handleResult}).
     */
    public static void login(Activity a, Launcher launcher, Callback cb) {
        Identity.getAuthorizationClient(a).authorize(request())
                .addOnSuccessListener(result -> {
                    if (result.hasResolution() && result.getPendingIntent() != null) {
                        launcher.launch(result.getPendingIntent());
                    } else {
                        finishLogin(result, cb);
                    }
                })
                .addOnFailureListener(e -> cb.onResult(false, explain(e)));
    }

    public interface Launcher {
        void launch(android.app.PendingIntent pi);
    }

    public static void handleResult(Activity a, Intent data, Callback cb) {
        try {
            AuthorizationResult r = Identity.getAuthorizationClient(a)
                    .getAuthorizationResultFromIntent(data);
            finishLogin(r, cb);
        } catch (Throwable e) {
            cb.onResult(false, explain(e));
        }
    }

    private static void finishLogin(AuthorizationResult r, Callback cb) {
        String t = r.getAccessToken();
        if (t == null) {
            cb.onResult(false, "Kein Zugriff erhalten");
            return;
        }
        setToken(t);
        prefs().edit().putBoolean("logged_in", true).apply();
        App.IO.execute(() -> {
            try {
                JSONObject o = get("channels?part=snippet&mine=true");
                JSONArray items = o.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    JSONObject sn = items.getJSONObject(0).getJSONObject("snippet");
                    prefs().edit()
                            .putString("name", sn.optString("title"))
                            .putString("avatar", sn.optJSONObject("thumbnails") != null
                                    ? thumb(sn.getJSONObject("thumbnails")) : null)
                            .apply();
                } else {
                    prefs().edit().putString("name", "Google-Konto").apply();
                }
                App.MAIN.post(() -> cb.onResult(true, null));
            } catch (Throwable e) {
                App.MAIN.post(() -> cb.onResult(true, null));
            }
        });
    }

    private static String explain(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("10:") || m.contains("DEVELOPER_ERROR")) {
            return "Google-Login ist noch nicht eingerichtet (Client-ID in der Google Cloud Console fehlt "
                    + "oder SHA-1 stimmt nicht). Siehe README.";
        }
        if (m.contains("12501") || m.contains("16:") || m.contains("CANCELED")) {
            return "Anmeldung abgebrochen";
        }
        return "Anmeldung fehlgeschlagen: " + m;
    }

    public static void logout() {
        final String t = token;
        token = null;
        prefs().edit().clear().apply();
        Feed.clear();
        if (t != null) {
            App.IO.execute(() -> {
                try {
                    GoogleAuthUtil.clearToken(App.get(), t);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static synchronized void setToken(String t) {
        token = t;
        tokenTime = System.currentTimeMillis();
    }

    /** Holt einen gültigen Token (ohne Fenster). Nicht im UI-Thread aufrufen. */
    private static synchronized String token(boolean forceNew) throws IOException {
        if (!forceNew && token != null && System.currentTimeMillis() - tokenTime < 45 * 60_000L) {
            return token;
        }
        if (forceNew && token != null) {
            try {
                GoogleAuthUtil.clearToken(App.get(), token);
            } catch (Exception ignored) {
            }
        }
        try {
            AuthorizationResult r = Tasks.await(
                    Identity.getAuthorizationClient(App.get()).authorize(request()),
                    30, TimeUnit.SECONDS);
            if (r.hasResolution() || r.getAccessToken() == null) {
                throw new IOException("Bitte neu anmelden");
            }
            setToken(r.getAccessToken());
            return token;
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Google-Konto nicht erreichbar: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- API

    private static JSONObject call(String method, String path, String jsonBody) throws IOException {
        if (!isLoggedIn()) throw new IOException("Nicht angemeldet");
        for (int attempt = 0; attempt < 2; attempt++) {
            String t = token(attempt > 0);
            Request.Builder b = new Request.Builder()
                    .url(API + path)
                    .header("Authorization", "Bearer " + t);
            RequestBody body = null;
            if (jsonBody != null) {
                body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));
            } else if (method.equals("POST")) {
                body = RequestBody.create(new byte[0]);
            }
            b.method(method, body);
            try (Response r = NetDownloader.get().client().newCall(b.build()).execute()) {
                String text = r.body() != null ? r.body().string() : "";
                if (r.code() == 401 && attempt == 0) continue;
                if (!r.isSuccessful()) {
                    String msg = "Fehler " + r.code();
                    try {
                        msg = new JSONObject(text).getJSONObject("error").optString("message", msg);
                    } catch (Exception ignored) {
                    }
                    throw new IOException(msg);
                }
                if (text.isEmpty()) return new JSONObject();
                return new JSONObject(text);
            } catch (org.json.JSONException e) {
                throw new IOException(e);
            }
        }
        throw new IOException("Bitte neu anmelden");
    }

    private static JSONObject get(String path) throws IOException {
        return call("GET", path, null);
    }

    private static String thumb(JSONObject thumbnails) {
        for (String k : new String[]{"medium", "high", "default", "standard", "maxres"}) {
            JSONObject o = thumbnails.optJSONObject(k);
            if (o != null) return o.optString("url", null);
        }
        return null;
    }

    /** Alle abonnierten Kanäle: [channelId, Name, Bild] */
    public static List<String[]> subscriptions() throws IOException {
        List<String[]> out = new ArrayList<>();
        String page = null;
        int guard = 0;
        do {
            JSONObject o = get("subscriptions?part=snippet&mine=true&maxResults=50&order=alphabetical"
                    + (page != null ? "&pageToken=" + page : ""));
            JSONArray items = o.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject sn = items.optJSONObject(i).optJSONObject("snippet");
                    if (sn == null) continue;
                    JSONObject res = sn.optJSONObject("resourceId");
                    if (res == null) continue;
                    String th = sn.optJSONObject("thumbnails") != null
                            ? thumb(sn.optJSONObject("thumbnails")) : null;
                    out.add(new String[]{res.optString("channelId"), sn.optString("title"), th});
                }
            }
            page = o.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null && ++guard < 40);
        return out;
    }

    public static List<Item> myPlaylists() throws IOException {
        List<Item> out = new ArrayList<>();
        String page = null;
        int guard = 0;
        do {
            JSONObject o = get("playlists?part=snippet,contentDetails&mine=true&maxResults=50"
                    + (page != null ? "&pageToken=" + page : ""));
            JSONArray items = o.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject p = items.optJSONObject(i);
                    JSONObject sn = p.optJSONObject("snippet");
                    if (sn == null) continue;
                    Item it = new Item();
                    it.type = Item.PLAYLIST;
                    it.apiPlaylistId = p.optString("id");
                    it.url = "https://www.youtube.com/playlist?list=" + it.apiPlaylistId;
                    it.title = sn.optString("title");
                    it.thumb = sn.optJSONObject("thumbnails") != null
                            ? thumb(sn.optJSONObject("thumbnails")) : null;
                    JSONObject cd = p.optJSONObject("contentDetails");
                    it.extra = (cd != null ? cd.optInt("itemCount") : 0) + " Videos";
                    out.add(it);
                }
            }
            page = o.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null && ++guard < 10);
        return out;
    }

    /** Videos einer eigenen (auch privaten) Playlist. */
    public static List<Item> playlistItems(String playlistId) throws IOException {
        List<Item> out = new ArrayList<>();
        String page = null;
        int guard = 0;
        do {
            JSONObject o = get("playlistItems?part=snippet&maxResults=50&playlistId=" + playlistId
                    + (page != null ? "&pageToken=" + page : ""));
            JSONArray items = o.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject sn = items.optJSONObject(i).optJSONObject("snippet");
                    if (sn == null) continue;
                    JSONObject res = sn.optJSONObject("resourceId");
                    if (res == null || !res.has("videoId")) continue;
                    String title = sn.optString("title");
                    if (title.equals("Private video") || title.equals("Deleted video")) continue;
                    Item it = new Item();
                    it.url = Util.watchUrl(res.optString("videoId"));
                    it.title = title;
                    it.uploader = sn.optString("videoOwnerChannelTitle", null);
                    String oc = sn.optString("videoOwnerChannelId", null);
                    if (oc != null) it.uploaderUrl = "https://www.youtube.com/channel/" + oc;
                    it.thumb = sn.optJSONObject("thumbnails") != null
                            ? thumb(sn.optJSONObject("thumbnails")) : null;
                    out.add(it);
                }
            }
            page = o.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null && ++guard < 20);
        return out;
    }

    public static List<Item> likedVideos() throws IOException {
        List<Item> out = new ArrayList<>();
        String page = null;
        int guard = 0;
        do {
            JSONObject o = get("videos?part=snippet,statistics&myRating=like&maxResults=50"
                    + (page != null ? "&pageToken=" + page : ""));
            JSONArray items = o.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject v = items.optJSONObject(i);
                    JSONObject sn = v.optJSONObject("snippet");
                    if (sn == null) continue;
                    Item it = new Item();
                    it.url = Util.watchUrl(v.optString("id"));
                    it.title = sn.optString("title");
                    it.uploader = sn.optString("channelTitle", null);
                    it.uploaderUrl = "https://www.youtube.com/channel/" + sn.optString("channelId");
                    it.thumb = sn.optJSONObject("thumbnails") != null
                            ? thumb(sn.optJSONObject("thumbnails")) : null;
                    JSONObject st = v.optJSONObject("statistics");
                    if (st != null) it.views = st.optLong("viewCount", -1);
                    out.add(it);
                }
            }
            page = o.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null && ++guard < 6);
        return out;
    }

    /** "like", "dislike" oder "none" */
    public static String getRating(String videoId) throws IOException {
        JSONObject o = get("videos/getRating?id=" + videoId);
        JSONArray items = o.optJSONArray("items");
        if (items != null && items.length() > 0) {
            return items.optJSONObject(0).optString("rating", "none");
        }
        return "none";
    }

    public static void rate(String videoId, String rating) throws IOException {
        call("POST", "videos/rate?id=" + videoId + "&rating=" + rating, null);
    }

    /** Liefert die Abo-ID, wenn der Kanal abonniert ist, sonst null. */
    public static String subscriptionId(String channelId) throws IOException {
        JSONObject o = get("subscriptions?part=id&mine=true&forChannelId=" + channelId);
        JSONArray items = o.optJSONArray("items");
        if (items != null && items.length() > 0) return items.optJSONObject(0).optString("id");
        return null;
    }

    public static String subscribe(String channelId) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("snippet", new JSONObject()
                            .put("resourceId", new JSONObject()
                                    .put("kind", "youtube#channel")
                                    .put("channelId", channelId)));
            JSONObject o = call("POST", "subscriptions?part=snippet", body.toString());
            Feed.clear();
            return o.optString("id", null);
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    public static void unsubscribe(String subscriptionId) throws IOException {
        call("DELETE", "subscriptions?id=" + subscriptionId, null);
        Feed.clear();
    }
}

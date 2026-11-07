package de.delorean23.tube;

import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

/** Ein Eintrag in einer Liste (Video, Kanal oder Playlist). Created by DeLorean23 */
public final class Item {

    public static final int VIDEO = 0, CHANNEL = 1, PLAYLIST = 2;

    public int type = VIDEO;
    public String url;
    public String title;
    public String uploader;
    public String uploaderUrl;
    public String thumb;
    public long duration = -1;
    public long views = -1;
    public String date;
    public boolean live;
    /** YouTube Short (hochkant) */
    public boolean shorts;
    /** Für Videos aus dem RSS-Feed: zum Sortieren */
    public long published;
    /** Für eigene Playlists (YouTube-Konto) */
    public String apiPlaylistId;
    public String extra;

    public static Item from(InfoItem ii) {
        Item it = new Item();
        it.url = ii.getUrl();
        it.title = ii.getName();
        it.thumb = Util.thumb(ii.getThumbnails());
        if (ii instanceof StreamInfoItem) {
            StreamInfoItem s = (StreamInfoItem) ii;
            it.type = VIDEO;
            it.uploader = s.getUploaderName();
            it.uploaderUrl = s.getUploaderUrl();
            it.duration = s.getDuration();
            it.views = s.getViewCount();
            it.date = s.getTextualUploadDate();
            StreamType st = s.getStreamType();
            it.live = st == StreamType.LIVE_STREAM || st == StreamType.AUDIO_LIVE_STREAM;
            it.shorts = s.isShortFormContent() || (it.url != null && it.url.contains("/shorts/"));
        } else if (ii instanceof ChannelInfoItem) {
            ChannelInfoItem c = (ChannelInfoItem) ii;
            it.type = CHANNEL;
            long subs = c.getSubscriberCount();
            it.extra = subs >= 0 ? Util.count(subs) + " Abonnenten" : "Kanal";
        } else if (ii instanceof PlaylistInfoItem) {
            PlaylistInfoItem p = (PlaylistInfoItem) ii;
            it.type = PLAYLIST;
            it.uploader = p.getUploaderName();
            long n = p.getStreamCount();
            it.extra = n >= 0 ? n + " Videos" : "Playlist";
        } else {
            return null;
        }
        return it;
    }

    public String subtitle() {
        if (type == CHANNEL) return extra != null ? extra : "";
        StringBuilder sb = new StringBuilder();
        if (uploader != null && !uploader.isEmpty()) sb.append(uploader);
        if (type == PLAYLIST) {
            if (extra != null) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(extra);
            }
            return sb.toString();
        }
        if (live) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("LIVE");
        } else if (views >= 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(Util.views(views));
        }
        if (date != null && !date.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(date);
        }
        return sb.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("t", type);
        o.put("u", url);
        o.put("n", title);
        o.putOpt("up", uploader);
        o.putOpt("uu", uploaderUrl);
        o.putOpt("th", thumb);
        o.put("d", duration);
        return o;
    }

    public static Item fromJson(JSONObject o) {
        Item it = new Item();
        it.type = o.optInt("t", VIDEO);
        it.url = o.optString("u", null);
        it.title = o.optString("n", "");
        it.uploader = o.optString("up", null);
        it.uploaderUrl = o.optString("uu", null);
        it.thumb = o.optString("th", null);
        it.duration = o.optLong("d", -1);
        return it;
    }
}

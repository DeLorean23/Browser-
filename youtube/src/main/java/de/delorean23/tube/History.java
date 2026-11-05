package de.delorean23.tube;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Lokaler Wiedergabeverlauf (die YouTube-API gibt den Konto-Verlauf nicht heraus). Created by DeLorean23 */
public final class History {

    private static final int MAX = 200;

    private History() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences("history", Context.MODE_PRIVATE);
    }

    public static synchronized List<Item> all() {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Item.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void add(Item it) {
        if (it == null || it.url == null) return;
        try {
            List<Item> list = all();
            String id = Util.videoId(it.url);
            for (int i = list.size() - 1; i >= 0; i--) {
                if (id != null && id.equals(Util.videoId(list.get(i).url))) list.remove(i);
            }
            list.add(0, it);
            JSONArray a = new JSONArray();
            for (int i = 0; i < Math.min(MAX, list.size()); i++) a.put(list.get(i).toJson());
            prefs().edit().putString("list", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static synchronized void clear() {
        prefs().edit().clear().apply();
    }
}

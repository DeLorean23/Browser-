package de.delorean23.tube;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

/** Liste mit Videos (große Karten) und Kanälen/Playlists (Zeilen). Created by DeLorean23 */
public class ItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnItem {
        void onItem(Item item);
    }

    public interface OnNearEnd {
        void onNearEnd();
    }

    private static final int T_HEADER = 0, T_VIDEO = 1, T_ROW = 2;

    private final Activity activity;
    private final List<Item> items = new ArrayList<>();
    private final OnItem click;
    @Nullable private OnItem longClick;
    @Nullable private View header;
    @Nullable private OnNearEnd nearEnd;
    private boolean compact;

    public ItemAdapter(Activity activity, OnItem click) {
        this.activity = activity;
        this.click = click;
    }

    public void setHeader(@Nullable View header) {
        this.header = header;
        notifyDataSetChanged();
    }

    public void setOnNearEnd(@Nullable OnNearEnd n) {
        nearEnd = n;
    }

    public void setOnLongClick(@Nullable OnItem l) {
        longClick = l;
    }

    /** Videos als kleine Zeilen anzeigen (z. B. unter dem Player). */
    public void setCompact(boolean c) {
        compact = c;
    }

    public void set(List<Item> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void add(List<Item> list) {
        int start = getItemCount();
        items.addAll(list);
        notifyItemRangeInserted(start, list.size());
    }

    public int size() {
        return items.size();
    }

    private int off() {
        return header != null ? 1 : 0;
    }

    @Override
    public int getItemCount() {
        return items.size() + off();
    }

    @Override
    public int getItemViewType(int position) {
        if (header != null && position == 0) return T_HEADER;
        Item it = items.get(position - off());
        return it.type == Item.VIDEO && !compact ? T_VIDEO : T_ROW;
    }

    /** Spaltenzahl im Raster (Tablet: mehrere Spalten). */
    public static GridLayoutManager layout(Activity a, ItemAdapter adapter) {
        float dp = a.getResources().getDisplayMetrics().widthPixels
                / a.getResources().getDisplayMetrics().density;
        int cols = adapter.compact ? 1 : Math.max(1, (int) (dp / 340));
        GridLayoutManager glm = new GridLayoutManager(a, cols);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == T_VIDEO ? 1 : cols;
            }
        });
        return glm;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title, subtitle, duration;

        Holder(View v) {
            super(v);
            ImageView t = v.findViewById(R.id.thumb);
            if (t == null) t = v.findViewById(R.id.icon);
            thumb = t;
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
            duration = v.findViewById(R.id.duration);
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == T_HEADER) {
            View h = header;
            if (h.getParent() instanceof ViewGroup) ((ViewGroup) h.getParent()).removeView(h);
            h.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(h) {
            };
        }
        int layout = viewType == T_VIDEO ? R.layout.item_video : R.layout.item_row;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
        if (!(vh instanceof Holder)) return;
        Holder h = (Holder) vh;
        Item it = items.get(position - off());
        h.title.setText(it.title);
        h.subtitle.setText(it.subtitle());
        if (h.duration != null) {
            String d = it.live ? "LIVE" : Util.duration(it.duration);
            h.duration.setText(d);
            h.duration.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
        }
        ViewGroup.LayoutParams lp = h.thumb.getLayoutParams();
        float dens = activity.getResources().getDisplayMetrics().density;
        if (h.thumb.getId() == R.id.icon) {
            if (it.type == Item.CHANNEL) {
                lp.width = (int) (54 * dens);
            } else {
                lp.width = (int) (96 * dens);
            }
            h.thumb.setLayoutParams(lp);
        }
        if (it.extra != null && it.extra.startsWith("@icon:")) {
            // feste Symbole in der Bibliothek
            Glide.with(activity).clear(h.thumb);
            h.thumb.setScaleType(ImageView.ScaleType.CENTER);
            h.thumb.setImageResource(Integer.parseInt(it.extra.substring(6)));
            h.subtitle.setText("");
        } else if (it.thumb != null) {
            h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (it.type == Item.CHANNEL) {
                Glide.with(activity).load(it.thumb).circleCrop().into(h.thumb);
            } else {
                Glide.with(activity).load(it.thumb).centerCrop().into(h.thumb);
            }
        } else {
            Glide.with(activity).clear(h.thumb);
            h.thumb.setImageDrawable(null);
        }
        h.itemView.setOnClickListener(v -> click.onItem(it));
        h.itemView.setOnLongClickListener(v -> {
            if (longClick == null) return false;
            longClick.onItem(it);
            return true;
        });
        if (nearEnd != null && position >= getItemCount() - 4) {
            OnNearEnd n = nearEnd;
            h.itemView.post(n::onNearEnd);
        }
    }
}

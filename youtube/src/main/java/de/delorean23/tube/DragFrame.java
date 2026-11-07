package de.delorean23.tube;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Rahmen um das Video: erkennt "nach unten ziehen" (zum Verkleinern), Antippen geht weiter an den Player.
 * Created by DeLorean23
 */
public class DragFrame extends FrameLayout {

    public interface Listener {
        void onDrag(float dy);

        void onRelease(float dy, float velocityY);
    }

    private Listener listener;
    private boolean enabledDrag = true;
    private final int slop;
    private float downX, downY;
    private boolean dragging;
    private VelocityTracker vt;

    public DragFrame(Context context) {
        this(context, null);
    }

    public DragFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        slop = ViewConfiguration.get(context).getScaledTouchSlop() * 2;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setDragEnabled(boolean e) {
        enabledDrag = e;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!enabledDrag || listener == null) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                dragging = false;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(ev);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(ev);
                float dy = ev.getRawY() - downY;
                float dx = Math.abs(ev.getRawX() - downX);
                if (dy > slop && dy > dx * 1.5f) {
                    dragging = true;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return super.onTouchEvent(ev);
        if (vt != null) vt.addMovement(ev);
        float dy = Math.max(0, ev.getRawY() - downY);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                listener.onDrag(dy);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float v = 0;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    v = vt.getYVelocity();
                    vt.recycle();
                    vt = null;
                }
                dragging = false;
                listener.onRelease(dy, v);
                return true;
            default:
                return true;
        }
    }
}

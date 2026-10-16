package ua.sister.energy;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Дві сторінки з гортанням пальцем (без бібліотек підтримки). */
final class Pager extends FrameLayout {
    interface Listener { void onPage(int p); }
    private final int slop;
    private float x0, y0, lastX; private boolean drag;
    private int page; private Listener listener;
    private VelocityTracker vt;

    Pager(Context c) { super(c); slop = ViewConfiguration.get(c).getScaledTouchSlop() * 2; }

    void setListener(Listener l) { listener = l; }
    int page() { return page; }

    @Override protected void onLayout(boolean ch, int l, int t, int r, int b) { super.onLayout(ch, l, t, r, b); place(0); }

    private void place(float dx) {
        int w = getWidth();
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setTranslationX((i - page) * w + dx);
    }

    void go(int p) {
        p = Math.max(0, Math.min(getChildCount() - 1, p));
        page = p; int w = getWidth();
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).animate().translationX((i - page) * w).setDuration(220).start();
        if (listener != null) listener.onPage(page);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: x0 = lastX = e.getX(); y0 = e.getY(); drag = false; break;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - x0, dy = e.getY() - y0;
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) { drag = true; return true; }
                break;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: x0 = e.getX(); return true;
            case MotionEvent.ACTION_MOVE: place(e.getX() - x0); return true;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                float dx = e.getX() - x0; vt.computeCurrentVelocity(1000); float v = vt.getXVelocity();
                vt.recycle(); vt = null;
                int p = page;
                if (dx < -getWidth() / 4f || v < -1200) p++; else if (dx > getWidth() / 4f || v > 1200) p--;
                go(p); return true;
        }
        return true;
    }

    View pageView(int i) { return getChildAt(i); }
}

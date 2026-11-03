package tv.gallery.nuviohome;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The vertical list of rows. It ignores Android's own "scroll the focused card into view" request, because the
 * screen lines the focused row up itself (title at the top); doing both overshot and hid the row's title.
 */
final class RowsLayout extends LinearLayoutManager {
    private final Context ctx;
    private int current = -1;
    RowsLayout(Context c) { super(c); ctx = c; }

    /** Puts row index at the top, animated. Moving sideways within the same row does nothing. */
    void showRow(RecyclerView rv, int index) {
        if (index < 0 || index == current) return;
        current = index;
        androidx.recyclerview.widget.LinearSmoothScroller s = new androidx.recyclerview.widget.LinearSmoothScroller(ctx) {
            @Override protected int getVerticalSnapPreference() { return SNAP_TO_START; }
            @Override protected float calculateSpeedPerPixel(android.util.DisplayMetrics dm) { return 60f / dm.densityDpi; }
        };
        s.setTargetPosition(index);
        startSmoothScroll(s);
    }
    void reset() { current = -1; }

    /**
     * Up/Down from a card to the next row lands on the card at the same place on screen. Android's own search
     * measures from the zoomed card and often picked the card to the right. false: not between two card rows
     * (e.g. up into the hero), so Android handles it.
     */
    static boolean moveVertical(RecyclerView outer, View focused, boolean down) {
        View card = focused; android.view.ViewParent p = focused.getParent();
        while (p instanceof View && !(p instanceof RecyclerView)) { card = (View) p; p = p.getParent(); }
        if (!(p instanceof RecyclerView) || p == outer) return false;
        RecyclerView inner = (RecyclerView) p;
        View box = inner; android.view.ViewParent q = inner.getParent();
        while (q instanceof View && q != outer) { box = (View) q; q = q.getParent(); }
        if (q != outer) return false;
        int pos = outer.getChildAdapterPosition(box);
        if (pos == RecyclerView.NO_POSITION) return false;
        RecyclerView.ViewHolder vh = outer.findViewHolderForAdapterPosition(pos + (down ? 1 : -1));
        if (vh == null) return false;
        RecyclerView target = cardsIn(vh.itemView);
        if (target == null) return false;
        int[] a = new int[2], b = new int[2]; inner.getLocationOnScreen(a); target.getLocationOnScreen(b);
        int x = a[0] + card.getLeft();
        View best = null; int bd = Integer.MAX_VALUE;
        for (int i = 0; i < target.getChildCount(); i++) {
            View c = target.getChildAt(i); if (!c.isFocusable()) continue;
            int d = Math.abs(b[0] + c.getLeft() - x); if (d < bd) { bd = d; best = c; }
        }
        return best != null && best.requestFocus();
    }
    private static RecyclerView cardsIn(View v) {
        if (v instanceof RecyclerView) return (RecyclerView) v;
        if (v instanceof android.view.ViewGroup) { android.view.ViewGroup g = (android.view.ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) { RecyclerView r = cardsIn(g.getChildAt(i)); if (r != null) return r; } }
        return null;
    }
    @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate, boolean focusedChildVisible) { return false; }
    @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate) { return false; }
}

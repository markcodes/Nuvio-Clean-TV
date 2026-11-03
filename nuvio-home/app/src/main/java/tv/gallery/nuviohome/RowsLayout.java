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
    /** Keep a screen of rows laid out above and below, so Up and Down always find the row they move to. */
    @Override protected void calculateExtraLayoutSpace(RecyclerView.State state, int[] extra) { int h = getHeight(); extra[0] = h; extra[1] = h; }

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
        // otherwise the tile under this one that starts at or before it: never further right
        int x = a[0] + card.getLeft() + Rows.dp(outer.getContext(), 8);
        View under = null, first = null; int ul = Integer.MIN_VALUE, fl = Integer.MAX_VALUE;
        for (int i = 0; i < target.getChildCount(); i++) {
            View c = target.getChildAt(i); if (!c.isFocusable()) continue;
            int l = b[0] + c.getLeft();
            if (l + c.getWidth() <= 0) continue;   // scrolled off the left
            if (l <= x && l > ul) { ul = l; under = c; }
            if (l < fl) { fl = l; first = c; }
        }
        View best = under != null ? under : first;
        return best != null && best.requestFocus();
    }

    /** True on the last card of a sideways row, where Right should do nothing (Android would jump to another row). */
    static boolean atRowEnd(View f) {
        View card = f; android.view.ViewParent p = f.getParent();
        while (p instanceof View) {
            if (p instanceof RecyclerView) {
                RecyclerView rv = (RecyclerView) p;
                if (!(rv.getLayoutManager() instanceof LinearLayoutManager) || rv.getLayoutManager() instanceof RowsLayout
                        || ((LinearLayoutManager) rv.getLayoutManager()).getOrientation() != RecyclerView.HORIZONTAL || rv.getAdapter() == null) return false;
                return rv.getChildAdapterPosition(card) == rv.getAdapter().getItemCount() - 1;
            }
            if (p instanceof android.widget.LinearLayout && ((View) p).getParent() instanceof android.widget.HorizontalScrollView) {
                android.widget.LinearLayout row = (android.widget.LinearLayout) p;
                return row.indexOfChild(card) == row.getChildCount() - 1;
            }
            card = (View) p; p = p.getParent();
        }
        return false;
    }
    private static RecyclerView cardsIn(View v) {
        if (v instanceof RecyclerView) return (RecyclerView) v;
        if (v instanceof android.view.ViewGroup) { android.view.ViewGroup g = (android.view.ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) { RecyclerView r = cardsIn(g.getChildAt(i)); if (r != null) return r; } }
        return null;
    }
    @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate, boolean focusedChildVisible) { return false; }
    @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate) { return false; }
}

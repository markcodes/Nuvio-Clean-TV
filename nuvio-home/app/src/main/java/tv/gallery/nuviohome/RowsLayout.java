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
    @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate, boolean focusedChildVisible) { return false; }
    @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate) { return false; }
}

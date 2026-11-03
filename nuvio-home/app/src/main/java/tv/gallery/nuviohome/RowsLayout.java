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
    RowsLayout(Context c) { super(c); }
    @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate, boolean focusedChildVisible) { return false; }
    @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate) { return false; }
}

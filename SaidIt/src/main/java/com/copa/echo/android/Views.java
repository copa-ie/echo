package com.copa.echo.android;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

public class Views {
    public static void search(ViewGroup viewGroup, SearchViewCallback callback)
{
        final int cnt = viewGroup.getChildCount();
        for(int i = 0; i < cnt; ++i) {
            final View child = viewGroup.getChildAt(i);
            if(child instanceof ViewGroup) {
                search((ViewGroup) child, callback);
            }
            callback.onView(child, viewGroup);
        }

    }

    public static interface SearchViewCallback {
        public void onView(View view, ViewGroup parent);
    }

    /**
     * Height of the status bar, for screens that draw behind it.
     * Three screens had their own copy of this.
     */
    public static int statusBarHeight(Context context) {
        final int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return (id > 0) ? context.getResources().getDimensionPixelSize(id) : 0;
    }

    /** Applies the app typefaces to every text view in the tree, honouring a "bold" tag. */
    public static void applyFonts(ViewGroup root, final Context context) {
        search(root, new SearchViewCallback() {
            @Override
            public void onView(View view, ViewGroup parent) {
                if (view instanceof android.widget.TextView) {
                    ((android.widget.TextView) view).setTypeface(
                            "bold".equals(view.getTag()) ? Fonts.bold(context) : Fonts.regular(context));
                }
            }
        });
    }
}

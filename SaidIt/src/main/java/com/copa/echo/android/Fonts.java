package com.copa.echo.android;

import android.content.Context;
import android.graphics.Typeface;

/**
 * The app's two typefaces, loaded once.
 *
 * Typeface.createFromAsset parses the font file, and every screen used to call it on each open,
 * twice, plus once per dialog. They never change, so they are cached for the process.
 */
public final class Fonts {

    private static volatile Typeface bold;
    private static volatile Typeface regular;

    private Fonts() {
    }

    public static Typeface bold(Context context) {
        if (bold == null) {
            bold = Typeface.createFromAsset(context.getAssets(), "RobotoCondensedBold.ttf");
        }
        return bold;
    }

    public static Typeface regular(Context context) {
        if (regular == null) {
            regular = Typeface.createFromAsset(context.getAssets(), "RobotoCondensed-Regular.ttf");
        }
        return regular;
    }
}

package app.tvhome.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

/** Outfit (SIL Open Font License, assets/fonts/OFL.txt) at a given weight. */
final class Fonts {
    private static final SparseArray<Typeface> cache = new SparseArray<>();

    private Fonts() {}

    static Typeface get(Context context, int weight) {
        Typeface face = cache.get(weight);
        if (face == null) {
            face = new Typeface.Builder(context.getAssets(), "fonts/outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
            if (face == null) face = Typeface.create("sans-serif-light", Typeface.NORMAL);
            cache.put(weight, face);
        }
        return face;
    }
}

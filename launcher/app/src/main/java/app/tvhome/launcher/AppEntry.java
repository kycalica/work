package app.tvhome.launcher;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** One launchable app. */
final class AppEntry {
    final String packageName;
    final String label;
    final Intent launchIntent;
    /** The app's 16:9 TV banner, or null when it only has an icon. */
    final Drawable banner;
    final Drawable icon;
    /** Colour sampled from the banner (or icon), used for the ambient glow. */
    final int color;

    AppEntry(String packageName, String label, Intent launchIntent, Drawable banner, Drawable icon, int color) {
        this.packageName = packageName;
        this.label = label;
        this.launchIntent = launchIntent;
        this.banner = banner;
        this.icon = icon;
        this.color = color;
    }
}

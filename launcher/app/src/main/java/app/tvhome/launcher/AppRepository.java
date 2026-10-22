package app.tvhome.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds launchable apps and splits them into the dock and the grid. */
final class AppRepository {
    static final class Result {
        final List<AppEntry> dock;
        final List<AppEntry> grid;

        Result(List<AppEntry> dock, List<AppEntry> grid) {
            this.dock = dock;
            this.grid = grid;
        }
    }

    private AppRepository() {}

    /** Slow (loads drawables): call it off the main thread. */
    static Result load(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, AppEntry> apps = new LinkedHashMap<>();
        collect(context, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, apps);
        if (context.getResources().getBoolean(R.bool.include_phone_apps)) {
            collect(context, pm, Intent.CATEGORY_LAUNCHER, apps);
        }
        Set<String> hidden = new HashSet<>(Arrays.asList(context.getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(context.getPackageName());
        for (String pkg : hidden) apps.remove(pkg);

        List<AppEntry> sorted = new ArrayList<>(apps.values());
        Collator collator = Collator.getInstance();
        sorted.sort((a, b) -> collator.compare(a.label, b.label));

        int columns = columns(context);
        List<AppEntry> dock = new ArrayList<>();
        for (String pkg : context.getResources().getStringArray(R.array.dock_apps)) {
            AppEntry app = apps.get(pkg);
            if (app != null && !dock.contains(app) && dock.size() < columns) dock.add(app);
        }
        List<AppEntry> grid = new ArrayList<>();
        for (AppEntry app : sorted) {
            if (dock.contains(app)) continue;
            if (dock.size() < columns) dock.add(app);
            else grid.add(app);
        }
        return new Result(dock, grid);
    }

    static int columns(Context context) {
        int columns = context.getResources().getInteger(R.integer.columns);
        return Math.max(4, Math.min(7, columns));
    }

    private static void collect(Context context, PackageManager pm, String category, Map<String, AppEntry> out) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            ActivityInfo activity = info.activityInfo;
            if (out.containsKey(activity.packageName)) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setComponent(new ComponentName(activity.packageName, activity.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable banner = activity.loadBanner(pm);
            if (banner == null) banner = activity.applicationInfo.loadBanner(pm);
            Drawable icon = activity.loadIcon(pm);
            String label = String.valueOf(info.loadLabel(pm));
            out.put(activity.packageName, new AppEntry(activity.packageName, label, launch, banner, icon,
                    sampleColor(banner != null ? banner : icon)));
        }
    }

    /** Average colour of a drawable, weighted towards saturated pixels, kept bright enough to glow. */
    static int sampleColor(Drawable drawable) {
        if (drawable == null) return Color.WHITE;
        Bitmap bitmap = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, 16, 9);
        drawable.draw(canvas);
        float r = 0, g = 0, b = 0, total = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int c = bitmap.getPixel(x, y);
                if (Color.alpha(c) < 64) continue;
                Color.colorToHSV(c, hsv);
                float weight = 0.15f + hsv[1] * hsv[2];
                r += Color.red(c) * weight;
                g += Color.green(c) * weight;
                b += Color.blue(c) * weight;
                total += weight;
            }
        }
        bitmap.recycle();
        if (total == 0) return Color.WHITE;
        int color = Color.rgb((int) (r / total), (int) (g / total), (int) (b / total));
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(hsv[2], 0.75f);
        return Color.HSVToColor(hsv);
    }
}

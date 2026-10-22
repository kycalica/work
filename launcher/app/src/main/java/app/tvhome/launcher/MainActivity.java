package app.tvhome.launcher;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TileAdapter.Listener {
    private static final String PREFS = "launcher";
    private static final String KEY_LAST_PACKAGE = "last_package";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private RecyclerView grid;
    private GridLayoutManager layoutManager;
    private TileAdapter adapter;
    private View highlight;
    private View topBar;
    private TextView greeting;
    private TextView clock;
    private TextView date;
    private TextView[] pills;
    private int columns;

    private int lastPosition = -1;
    private int lastDockPosition = -1;
    private int lastPill = 0;
    private String lastPackage;
    private View focusedTile;
    private ValueAnimator highlightAnimator;
    private final RectF highlightRect = new RectF();
    private PopupWindow inputsPopup;
    private int laidOutWidth;
    private int laidOutHeight;
    private boolean appsLoaded;

    // ---- Lifecycle ----

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        columns = AppRepository.columns(this);
        lastPackage = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_PACKAGE, null);

        grid = findViewById(R.id.grid);
        highlight = findViewById(R.id.highlight);
        topBar = findViewById(R.id.top_bar);
        greeting = findViewById(R.id.greeting);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        pills = new TextView[] {findViewById(R.id.pill_inputs), findViewById(R.id.pill_wifi), findViewById(R.id.pill_settings)};

        greeting.setTypeface(Fonts.get(this, 400));
        clock.setTypeface(Fonts.get(this, 200));
        date.setTypeface(Fonts.get(this, 300));
        for (int i = 0; i < pills.length; i++) {
            int index = i;
            pills[i].setTypeface(Fonts.get(this, 500));
            pills[i].setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    lastPill = index;
                    hideHighlight();
                }
            });
        }
        pills[0].setOnClickListener(v -> inputsPopup = InputsMenu.show(this, v));
        pills[1].setOnClickListener(v -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        pills[2].setOnClickListener(v -> openSettings(Settings.ACTION_SETTINGS));

        adapter = new TileAdapter(this, columns, this);
        layoutManager = new GridLayoutManager(this, columns) {
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                    @NonNull android.graphics.Rect rect, boolean immediate, boolean focusedChildVisible) {
                return false; // scrolling is done by scrollIntoView, one row per key press
            }
        };
        layoutManager.setSpanSizeLookup(adapter.spanSizeLookup());
        grid.setLayoutManager(layoutManager);
        grid.setItemAnimator(null);
        grid.setAdapter(adapter);
        grid.addItemDecoration(new DockBand());
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                updateTopBarFade();
                if (highlightAnimator == null || !highlightAnimator.isRunning()) snapHighlight();
            }
        });
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != laidOutWidth || b - t != laidOutHeight) {
                laidOutWidth = r - l;
                laidOutHeight = b - t;
                handler.post(this::updateHeights);
            }
        });

        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        register(packageReceiver, packages);

        loadApps();
        loadWallpaper();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        register(timeReceiver, time);
        updateClock();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        updateNetworkPill();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appsLoaded && (getCurrentFocus() == null || focusedTile == null && !isPill(getCurrentFocus()))) {
            restoreFocus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (lastPackage != null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_PACKAGE, lastPackage).apply();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(timeReceiver);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        executor.shutdownNow();
        super.onDestroy();
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        // Home is the bottom of the stack: Back never closes it (dispatchKeyEvent handles it).
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the dock.
        if (inputsPopup != null) inputsPopup.dismiss();
        if (appsLoaded) focusPosition(lastDockPosition >= 0 ? lastDockPosition : adapter.dockStart());
    }

    private void register(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    // ---- Apps ----

    private void loadApps() {
        executor.execute(() -> {
            AppRepository.Result result = AppRepository.load(getApplicationContext());
            handler.post(() -> {
                if (isDestroyed()) return;
                boolean first = !appsLoaded;
                adapter.setApps(result.dock, result.grid);
                appsLoaded = true;
                int pos = lastPackage == null ? -1 : adapter.positionOf(lastPackage);
                lastPosition = pos >= 0 ? pos : adapter.dockStart();
                if (lastDockPosition < 0 || adapter.rowOf(lastDockPosition) != 0) {
                    lastDockPosition = adapter.rowOf(lastPosition) == 0 ? lastPosition : adapter.dockStart();
                }
                if (first || !isPill(getCurrentFocus())) grid.post(this::restoreFocus);
            });
        });
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
        }
    };

    @Override
    public void onTileFocused(int position, View view, AppEntry app) {
        lastPosition = position;
        lastPackage = app.packageName;
        boolean dock = adapter.rowOf(position) == 0;
        if (dock) lastDockPosition = position;
        adapter.setDockTitle(dock ? app : null);
        moveHighlight(view);
    }

    @Override
    public void onTileClicked(AppEntry app) {
        try {
            startActivity(app.launchIntent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, getString(R.string.cannot_open, app.label), Toast.LENGTH_LONG).show();
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            if (!Settings.ACTION_SETTINGS.equals(action)) openSettings(Settings.ACTION_SETTINGS);
        }
    }

    // ---- Layout ----

    /** Size the header so the dock sits at the bottom of the screen, and measure one tile for it. */
    private void updateHeights() {
        if (laidOutWidth == 0) return;
        int contentWidth = laidOutWidth - grid.getPaddingLeft() - grid.getPaddingRight();
        View probe = getLayoutInflater().inflate(R.layout.item_tile, grid, false);
        ((TextView) probe.findViewById(R.id.label)).setText("Ag");
        probe.measure(View.MeasureSpec.makeMeasureSpec(contentWidth / columns, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int tileHeight = probe.getMeasuredHeight();
        int bottom = getResources().getDimensionPixelSize(R.dimen.dock_bottom)
                + getResources().getDimensionPixelSize(R.dimen.dock_band_pad);
        adapter.setHeights(Math.max(0, laidOutHeight - tileHeight - bottom),
                getResources().getDimensionPixelSize(R.dimen.grid_gap) + bottom,
                laidOutHeight / 2);
        if (appsLoaded) grid.post(this::restoreFocus);
    }

    private void updateTopBarFade() {
        int offset = grid.computeVerticalScrollOffset();
        float fade = Math.max(0f, 1f - offset / (laidOutHeight * 0.25f + 1f));
        topBar.setAlpha(fade);
        for (TextView pill : pills) pill.setFocusable(fade > 0.5f);
    }

    /** The slightly lighter rounded band behind the dock row. */
    private final class DockBand extends RecyclerView.ItemDecoration {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float pad = getResources().getDimension(R.dimen.dock_band_pad);
        private final float radius = getResources().getDimension(R.dimen.dock_band_radius);

        DockBand() {
            paint.setColor(getColor(R.color.dock_band));
        }

        @Override
        public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                int position = parent.getChildAdapterPosition(child);
                if (position >= adapter.dockStart() && position < adapter.dockStart() + columns
                        && adapter.getItemViewType(position) == TileAdapter.TYPE_TILE) {
                    rect.set(parent.getPaddingLeft() - pad, child.getTop() - pad,
                            parent.getWidth() - parent.getPaddingRight() + pad, child.getBottom() + pad);
                    c.drawRoundRect(rect, radius, radius, paint);
                    return;
                }
            }
        }
    }

    // ---- D-pad navigation: one row per press, no surprises ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || (inputsPopup != null && inputsPopup.isShowing())) {
            return super.dispatchKeyEvent(event);
        }
        int key = event.getKeyCode();
        View focus = getCurrentFocus();
        if (key == KeyEvent.KEYCODE_BACK) {
            // Home has nowhere to go back to: return to the dock instead.
            if (appsLoaded && (isPill(focus) || adapter.rowOf(lastPosition) > 0)) {
                focusPosition(lastDockPosition >= 0 ? lastDockPosition : adapter.dockStart());
            }
            return true;
        }
        if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN
                && key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return super.dispatchKeyEvent(event);
        }
        if (isPill(focus)) {
            int index = indexOfPill(focus);
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && index > 0) pills[index - 1].requestFocus();
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && index < pills.length - 1) pills[index + 1].requestFocus();
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && appsLoaded) {
                focusPosition(lastDockPosition >= 0 ? lastDockPosition : adapter.dockStart());
            }
            return true; // the ends of the top bar never fall through to the tiles
        }
        View item = focus == null ? null : grid.findContainingItemView(focus);
        int position = item == null ? RecyclerView.NO_POSITION : grid.getChildAdapterPosition(item);
        int row = position == RecyclerView.NO_POSITION ? -1 : adapter.rowOf(position);
        if (row < 0) {
            if (appsLoaded) restoreFocus();
            return true;
        }
        int column = adapter.columnOf(position);
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (column > 0) focusPosition(adapter.positionAt(row, column - 1));
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (column < adapter.rowLength(row) - 1) focusPosition(adapter.positionAt(row, column + 1));
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row == 0) {
                    pills[lastPill].setFocusable(true);
                    pills[lastPill].requestFocus();
                } else {
                    int up = row - 1;
                    focusPosition(adapter.positionAt(up, Math.min(column, adapter.rowLength(up) - 1)));
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row + 1 < adapter.rowCount()) {
                    int down = row + 1;
                    focusPosition(adapter.positionAt(down, Math.min(column, adapter.rowLength(down) - 1)));
                }
                break;
        }
        return true;
    }

    private boolean isPill(View view) {
        return indexOfPill(view) >= 0;
    }

    private int indexOfPill(View view) {
        for (int i = 0; i < pills.length; i++) if (pills[i] == view) return i;
        return -1;
    }

    private void restoreFocus() {
        if (adapter.rowOf(lastPosition) < 0) lastPosition = adapter.dockStart();
        if (adapter.rowOf(lastPosition) < 0) {
            pills[0].requestFocus(); // no apps at all
            return;
        }
        focusPosition(lastPosition);
    }

    private void focusPosition(int position) {
        View view = layoutManager.findViewByPosition(position);
        if (view == null) {
            // Off screen: jump there first, then focus once it is laid out.
            if (adapter.rowOf(position) == 0) layoutManager.scrollToPositionWithOffset(0, 0);
            else layoutManager.scrollToPositionWithOffset(position, (int) (laidOutHeight * 0.3f));
            grid.post(() -> {
                View laidOut = layoutManager.findViewByPosition(position);
                if (laidOut != null) {
                    laidOut.requestFocus();
                    scrollIntoView(position, laidOut);
                }
            });
            return;
        }
        view.requestFocus();
        scrollIntoView(position, view);
    }

    private void scrollIntoView(int position, View view) {
        int dy;
        if (adapter.rowOf(position) == 0) {
            dy = -grid.computeVerticalScrollOffset();
        } else {
            dy = view.getTop() - (int) (laidOutHeight * 0.3f);
        }
        if (dy != 0) grid.smoothScrollBy(0, dy);
    }

    // ---- The glass highlight ----

    private void moveHighlight(View tile) {
        boolean wasVisible = highlight.getVisibility() == View.VISIBLE && focusedTile != null;
        focusedTile = tile;
        if (highlightAnimator != null) highlightAnimator.cancel();
        if (!wasVisible) {
            snapHighlight();
            highlight.setAlpha(0f);
            highlight.setVisibility(View.VISIBLE);
            highlight.animate().alpha(1f).setDuration(150).start();
            return;
        }
        RectF from = new RectF(highlightRect);
        highlightAnimator = ValueAnimator.ofFloat(0f, 1f);
        highlightAnimator.setDuration(320);
        highlightAnimator.setInterpolator(new OvershootInterpolator(1.2f));
        highlightAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            RectF to = targetRect(focusedTile);
            if (to == null) return;
            applyHighlight(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                    from.width() + (to.width() - from.width()) * t, from.height() + (to.height() - from.height()) * t);
        });
        highlightAnimator.start();
    }

    private void snapHighlight() {
        RectF to = focusedTile == null ? null : targetRect(focusedTile);
        if (to != null) applyHighlight(to.left, to.top, to.width(), to.height());
    }

    private void hideHighlight() {
        focusedTile = null;
        if (highlightAnimator != null) highlightAnimator.cancel();
        highlight.setVisibility(View.INVISIBLE);
        adapter.setDockTitle(null);
    }

    /** Where the highlight goes: the tile's card, scaled like the focused tile, plus a margin. */
    private RectF targetRect(View tile) {
        if (tile == null || tile.getParent() != grid) return null;
        View card = tile.findViewById(R.id.card);
        float pivotX = grid.getLeft() + tile.getLeft() + tile.getWidth() / 2f;
        float pivotY = grid.getTop() + tile.getTop() + tile.getHeight() / 2f;
        float left = grid.getLeft() + tile.getLeft() + card.getLeft();
        float top = grid.getTop() + tile.getTop() + card.getTop();
        float s = TileAdapter.FOCUS_SCALE;
        float pad = getResources().getDimension(R.dimen.highlight_pad);
        float l = pivotX + (left - pivotX) * s - pad;
        float t = pivotY + (top - pivotY) * s - pad;
        return new RectF(l, t, l + card.getWidth() * s + 2 * pad, t + card.getHeight() * s + 2 * pad);
    }

    private void applyHighlight(float left, float top, float width, float height) {
        highlightRect.set(left, top, left + width, top + height);
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        int w = Math.round(width), h = Math.round(height);
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            highlight.setLayoutParams(lp);
        }
        highlight.setTranslationX(left);
        highlight.setTranslationY(top);
    }

    // ---- Clock, greeting, date ----

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(DateFormat.format(h24 ? "HH:mm" : "h:mm", now));
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), now));
        int hour = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(hour < 5 ? R.string.good_evening : hour < 12 ? R.string.good_morning
                : hour < 18 ? R.string.good_afternoon : R.string.good_evening);
    }

    // ---- Wi-Fi pill: updated on network changes, never polled ----

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
            handler.post(MainActivity.this::updateNetworkPill);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateNetworkPill);
        }
    };

    @SuppressWarnings("deprecation") // WifiManager.getConnectionInfo is the only call that works back to Android 8
    private void updateNetworkPill() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        String text;
        if (caps == null) {
            text = getString(R.string.offline);
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            text = getString(R.string.ethernet);
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            text = getString(R.string.wifi);
            WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equals(ssid)) {
                text = ssid.replaceAll("^\"|\"$", "");
            }
        } else {
            text = getString(R.string.wifi);
        }
        pills[1].setText(text);
    }

    // ---- Wallpaper: optional, read once ----

    /** Uses Android/data/app.tvhome.launcher/files/wallpaper.jpg (or .png) when present; pitch black otherwise. */
    private void loadWallpaper() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        int width = getResources().getDisplayMetrics().widthPixels;
        executor.execute(() -> {
            File file = new File(dir, "wallpaper.jpg");
            if (!file.exists()) file = new File(dir, "wallpaper.png");
            if (!file.exists()) return;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / (options.inSampleSize * 2) >= width) options.inSampleSize *= 2;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap == null) return;
            handler.post(() -> {
                ImageView view = findViewById(R.id.wallpaper);
                view.setImageBitmap(bitmap);
                view.setColorFilter(Color.argb(90, 0, 0, 0), PorterDuff.Mode.SRC_ATOP); // keep text readable
                view.setVisibility(View.VISIBLE);
            });
        });
    }
}

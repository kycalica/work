package app.tvhome.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter positions: 0 header, then one dock row of {@code columns} slots
 * (empty fillers if there are fewer dock apps), a gap, the grid apps, and
 * bottom padding. Rows for navigation: row 0 is the dock, rows 1.. the grid.
 */
final class TileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final int TYPE_HEADER = 0;
    static final int TYPE_TILE = 1;
    static final int TYPE_FILLER = 2;
    static final int TYPE_SPACE = 3;
    static final float FOCUS_SCALE = 1.08f;

    interface Listener {
        void onTileFocused(int position, View view, AppEntry app);
        void onTileClicked(AppEntry app);
    }

    private final Context context;
    private final int columns;
    private final Listener listener;
    private List<AppEntry> dock = new ArrayList<>();
    private List<AppEntry> grid = new ArrayList<>();
    private int headerHeight;
    private int gapHeight;
    private int bottomHeight;
    private HeaderHolder header;
    private AppEntry dockTitleApp;
    private int glowColor = Color.TRANSPARENT;
    private ValueAnimator glowAnimator;

    TileAdapter(Context context, int columns, Listener listener) {
        this.context = context;
        this.columns = columns;
        this.listener = listener;
    }

    void setApps(List<AppEntry> dock, List<AppEntry> grid) {
        this.dock = dock;
        this.grid = grid;
        notifyDataSetChanged();
    }

    void setHeights(int header, int gap, int bottom) {
        headerHeight = header;
        gapHeight = gap;
        bottomHeight = bottom;
        notifyDataSetChanged();
    }

    GridLayoutManager.SpanSizeLookup spanSizeLookup() {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int type = getItemViewType(position);
                return type == TYPE_HEADER || type == TYPE_SPACE ? columns : 1;
            }
        };
    }

    // ---- Layout of positions and rows ----

    int dockStart() { return 1; }
    int gridStart() { return 2 + columns; }

    int rowCount() { return 1 + (grid.size() + columns - 1) / columns; }

    int rowLength(int row) {
        if (row == 0) return dock.size();
        return Math.min(columns, grid.size() - (row - 1) * columns);
    }

    int positionAt(int row, int column) {
        return row == 0 ? dockStart() + column : gridStart() + (row - 1) * columns + column;
    }

    /** Row of a tile position, or -1 when the position is not a tile. */
    int rowOf(int position) {
        if (position >= dockStart() && position < dockStart() + dock.size()) return 0;
        int g = position - gridStart();
        if (g >= 0 && g < grid.size()) return 1 + g / columns;
        return -1;
    }

    int columnOf(int position) {
        return rowOf(position) == 0 ? position - dockStart() : (position - gridStart()) % columns;
    }

    AppEntry appAt(int position) {
        int d = position - dockStart();
        if (d >= 0 && d < columns) return d < dock.size() ? dock.get(d) : null;
        int g = position - gridStart();
        return g >= 0 && g < grid.size() ? grid.get(g) : null;
    }

    int positionOf(String packageName) {
        for (int i = 0; i < dock.size(); i++) if (dock.get(i).packageName.equals(packageName)) return dockStart() + i;
        for (int i = 0; i < grid.size(); i++) if (grid.get(i).packageName.equals(packageName)) return gridStart() + i;
        return -1;
    }

    // ---- Dock title and glow ----

    void setDockTitle(AppEntry app) {
        if (app == dockTitleApp) return;
        dockTitleApp = app;
        int from = glowColor;
        int to = app == null ? Color.TRANSPARENT : app.color;
        if (glowAnimator != null) glowAnimator.cancel();
        glowAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        glowAnimator.setDuration(350);
        glowAnimator.addUpdateListener(a -> {
            glowColor = (int) a.getAnimatedValue();
            if (header != null) applyGlow(header);
        });
        glowAnimator.start();
        if (header != null) {
            header.title.animate().cancel();
            header.title.setAlpha(0f);
            header.title.setText(app == null ? "" : app.label);
            header.title.animate().alpha(1f).setDuration(220).start();
        }
    }

    private void applyGlow(HeaderHolder h) {
        int c = glowColor;
        int center = Color.argb((int) (Color.alpha(c) * 0.30f), Color.red(c), Color.green(c), Color.blue(c));
        h.glowDrawable.setColors(new int[] {center, Color.TRANSPARENT});
    }

    // ---- RecyclerView.Adapter ----

    @Override
    public int getItemCount() {
        return gridStart() + grid.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) return TYPE_HEADER;
        if (position == gridStart() - 1 || position == getItemCount() - 1) return TYPE_SPACE;
        return appAt(position) == null ? TYPE_FILLER : TYPE_TILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        switch (viewType) {
            case TYPE_HEADER:
                return new HeaderHolder(inflater.inflate(R.layout.item_header, parent, false));
            case TYPE_TILE:
                return new TileHolder(inflater.inflate(R.layout.item_tile, parent, false));
            default:
                View space = new View(context);
                space.setFocusable(false);
                return new RecyclerView.ViewHolder(space) {};
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderHolder) {
            HeaderHolder h = (HeaderHolder) holder;
            header = h;
            h.itemView.getLayoutParams().height = headerHeight;
            h.title.setText(dockTitleApp == null ? "" : dockTitleApp.label);
            applyGlow(h);
        } else if (holder instanceof TileHolder) {
            ((TileHolder) holder).bind(appAt(position));
        } else {
            int type = getItemViewType(position);
            int height = type == TYPE_FILLER ? 1 : position == getItemCount() - 1 ? bottomHeight : gapHeight;
            ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
            if (lp == null) {
                holder.itemView.setLayoutParams(new GridLayoutManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            } else {
                lp.height = height;
            }
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder == header) header = null;
    }

    final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final GradientDrawable glowDrawable;

        HeaderHolder(View view) {
            super(view);
            title = view.findViewById(R.id.dock_title);
            title.setTypeface(Fonts.get(context, 300));
            glowDrawable = new GradientDrawable();
            glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            glowDrawable.setGradientRadius(360 * view.getResources().getDisplayMetrics().density);
            view.findViewById(R.id.glow).setBackground(glowDrawable);
        }
    }

    final class TileHolder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView image;
        final TextView label;
        AppEntry app;

        TileHolder(View view) {
            super(view);
            card = view.findViewById(R.id.card);
            image = view.findViewById(R.id.image);
            label = view.findViewById(R.id.label);
            label.setTypeface(Fonts.get(context, 400));
            view.setOnClickListener(v -> {
                if (app != null) listener.onTileClicked(app);
            });
            view.setOnFocusChangeListener((v, hasFocus) -> {
                float scale = hasFocus ? FOCUS_SCALE : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(180).start();
                label.animate().alpha(hasFocus ? 1f : 0f).setDuration(180).start();
                if (hasFocus && app != null) {
                    listener.onTileFocused(getBindingAdapterPosition(), v, app);
                }
            });
        }

        void bind(AppEntry app) {
            this.app = app;
            label.setText(app.label);
            itemView.setContentDescription(app.label);
            boolean focused = itemView.hasFocus() || itemView.isFocused();
            itemView.setScaleX(focused ? FOCUS_SCALE : 1f);
            itemView.setScaleY(focused ? FOCUS_SCALE : 1f);
            label.setAlpha(focused ? 1f : 0f);
            if (app.banner != null) {
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setPadding(0, 0, 0, 0);
                image.setImageDrawable(app.banner);
                card.setBackgroundColor(Color.BLACK);
            } else {
                // Icon-only app: the icon centred on a dark card tinted with its colour.
                int pad = (int) (card.getResources().getDisplayMetrics().density * 22);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setPadding(pad, pad, pad, pad);
                image.setImageDrawable(app.icon);
                int c = app.color;
                card.setBackgroundColor(Color.rgb(Color.red(c) / 5 + 20, Color.green(c) / 5 + 20, Color.blue(c) / 5 + 20));
            }
        }
    }
}

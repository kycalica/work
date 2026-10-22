package app.tvhome.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** The compact glass dropdown under the Inputs pill: HDMI ports, AV and Live TV. */
final class InputsMenu {
    private static final class Entry {
        final String label;
        final Intent intent;
        final int order;

        Entry(String label, Intent intent, int order) {
            this.label = label;
            this.intent = intent;
            this.order = order;
        }
    }

    private InputsMenu() {}

    static PopupWindow show(Activity activity, View anchor) {
        List<Entry> entries = entries(activity);
        float dp = activity.getResources().getDisplayMetrics().density;

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundResource(R.drawable.dropdown);
        int pad = (int) (8 * dp);
        list.setPadding(pad, pad, pad, pad);
        list.setMinimumWidth((int) (220 * dp));

        PopupWindow popup = new PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(12 * dp);

        if (entries.isEmpty()) {
            list.addView(row(activity, activity.getString(R.string.no_inputs), false));
        }
        for (Entry entry : entries) {
            TextView row = row(activity, entry.label, true);
            row.setOnClickListener(v -> {
                popup.dismiss();
                open(activity, entry);
            });
            list.addView(row);
        }
        popup.showAsDropDown(anchor, 0, (int) (10 * dp), Gravity.END);
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
        return popup;
    }

    private static TextView row(Context context, String text, boolean focusable) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView row = new TextView(context);
        row.setText(text);
        row.setTypeface(Fonts.get(context, 400));
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        row.setTextColor(context.getColor(R.color.text_primary));
        row.setFocusable(focusable);
        row.setBackgroundResource(R.drawable.dropdown_item);
        row.setPadding((int) (18 * dp), (int) (12 * dp), (int) (18 * dp), (int) (12 * dp));
        return row;
    }

    private static List<Entry> entries(Context context) {
        List<Entry> entries = new ArrayList<>();
        TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        boolean haveTuner = false;
        if (manager != null) {
            for (TvInputInfo info : manager.getTvInputList()) {
                // Skip hidden inputs and the per-device children HDMI-CEC adds under a port.
                if (info.isHidden(context) || info.getParentId() != null) continue;
                Intent intent;
                String label;
                int order;
                if (info.isPassthroughInput()) {
                    intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()));
                    label = String.valueOf(info.loadLabel(context));
                    order = info.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1;
                } else if (info.getType() == TvInputInfo.TYPE_TUNER) {
                    if (haveTuner) continue;
                    haveTuner = true;
                    intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(info.getId()));
                    label = context.getString(R.string.live_tv);
                    order = 2;
                } else {
                    continue;
                }
                entries.add(new Entry(label, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), order));
            }
        }
        if (!haveTuner && tvAppIntent(context) != null) {
            entries.add(new Entry(context.getString(R.string.live_tv), tvAppIntent(context), 2));
        }
        entries.sort((a, b) -> a.order != b.order ? a.order - b.order : a.label.compareTo(b.label));
        return entries;
    }

    private static Intent tvAppIntent(Context context) {
        String pkg = context.getString(R.string.tv_app_package);
        if (TextUtils.isEmpty(pkg)) return null;
        Intent intent = context.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
        return intent == null ? null : intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static void open(Activity activity, Entry entry) {
        try {
            activity.startActivity(entry.intent);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Some manufacturer TV apps ignore the standard intent: fall back to the TV app itself.
        }
        Intent fallback = tvAppIntent(activity);
        if (fallback != null && fallback != entry.intent) {
            try {
                activity.startActivity(fallback);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Reported below.
            }
        }
        Toast.makeText(activity, activity.getString(R.string.cannot_open, entry.label), Toast.LENGTH_LONG).show();
    }
}

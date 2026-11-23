package cn.zhe.chaojibeauty;
import android.content.*;
import android.database.*;
import android.net.Uri;

/** Read-only feature switches shared with SystemUI. */
public final class FeatureProvider extends ContentProvider {
    static final String[] CONTAINER_KEYS = {"folder_glass","suggestion_glass","pager_glass","assistant_glass","assistant_slide","assistant_right","recents_clear_glass"};
    static final Uri CONTAINERS = Uri.parse("content://cn.zhe.chaojibeauty.features/containers");
    static final Uri ICONS = Uri.parse("content://cn.zhe.chaojibeauty.features/status_icons");
    static final Uri DOCK = Uri.parse("content://cn.zhe.chaojibeauty.features/fold_dock");
    static final Uri FEATURES = Uri.parse("content://cn.zhe.chaojibeauty.features/state");
    static final Uri DISPLAY = Uri.parse("content://cn.zhe.chaojibeauty.features/display_style");
    static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("features", Context.MODE_PRIVATE);
    }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort) {
        if (CONTAINERS.equals(uri)) {
            SharedPreferences state = prefs(getContext());
            MatrixCursor cursor = new MatrixCursor(CONTAINER_KEYS);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String key : CONTAINER_KEYS) row.add(state.getBoolean(key, false) ? 1 : 0);
            return cursor;
        }
        if (FEATURES.equals(uri)) {
            SharedPreferences state = prefs(getContext());
            MatrixCursor cursor = new MatrixCursor(new String[]{"icons","stack","notification_glass","lock_center","widget_material","time_weight","date_weight","display_style","control_glass"});
            cursor.addRow(new Object[]{state.getBoolean("icons",false) ? 1 : 0, state.getBoolean("stack",false) ? 1 : 0,
                    state.getBoolean("notification_glass",false) ? 1 : 0, state.getBoolean("lock_center",false) ? 1 : 0,
                    state.getBoolean("widget_material",false) ? 1 : 0,
                    state.getInt("time_weight",ClockTypography.DEFAULT_WEIGHT), state.getInt("date_weight",ClockTypography.DEFAULT_WEIGHT),state.getInt("display_style",1),state.getBoolean("control_glass",false)?1:0});
            return cursor;
        }
        if (DOCK.equals(uri)) {
            SharedPreferences state = prefs(getContext());
            MatrixCursor cursor = new MatrixCursor(new String[]{"enabled","glass"});
            cursor.addRow(new Object[]{state.getBoolean("fold_dock",false) ? 1 : 0, state.getBoolean("dock_glass",false) ? 1 : 0});
            return cursor;
        }
        if (!ICONS.equals(uri)) throw new IllegalArgumentException("Unknown feature");
        MatrixCursor cursor = new MatrixCursor(new String[]{"enabled"});
        cursor.addRow(new Object[]{prefs(getContext()).getBoolean("icons",false) ? 1 : 0});
        return cursor;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] args) {
        if (!DISPLAY.equals(uri)) throw new UnsupportedOperationException();
        if (!"com.android.systemui".equals(getCallingPackage())) throw new SecurityException();
        Integer style = v.getAsInteger("display_style");
        if (style == null || style < 0 || style > 2) throw new IllegalArgumentException("display_style");
        prefs(getContext()).edit().putInt("display_style", style).apply();
        getContext().getContentResolver().notifyChange(FEATURES, null);
        return 1;
    }
    @Override public int delete(Uri uri, String s, String[] args) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
}

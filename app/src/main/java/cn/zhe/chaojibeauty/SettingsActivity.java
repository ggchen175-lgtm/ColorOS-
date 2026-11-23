package cn.zhe.chaojibeauty;
import android.app.Activity;
import android.os.Bundle;
import android.widget.Switch;

public final class SettingsActivity extends Activity {
    @Override protected void onStart() {
        super.onStart();
        bindDisplayStyle();
        getContentResolver().notifyChange(FeatureProvider.DOCK, null);
        getContentResolver().notifyChange(FeatureProvider.CONTAINERS, null);
        // Package updates may start SystemUI before Android releases the provider.
        getContentResolver().notifyChange(FeatureProvider.FEATURES, null);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.settings);
        bindSwitch(R.id.control_glass,"control_glass");
        bindSwitch(R.id.recents_clear_glass,"recents_clear_glass");
        bindSwitch(R.id.folder_glass, "folder_glass");
        bindSwitch(R.id.suggestion_glass, "suggestion_glass");
        bindSwitch(R.id.pager_glass, "pager_glass");
        bindSwitch(R.id.assistant_glass, "assistant_glass");
        bindSwitch(R.id.assistant_slide, "assistant_slide");
        bindSwitch(R.id.assistant_right, "assistant_right");
        bindSwitch(R.id.icons, "icons");
        bindSwitch(R.id.stack, "stack");
        bindSwitch(R.id.notification_glass, "notification_glass");
        bindSwitch(R.id.lock_center, "lock_center");
        findViewById(R.id.clock_weights).setOnClickListener(v -> showClockWeights());
        bindSwitch(R.id.widget_material, "widget_material");
        bindSwitch(R.id.dock, "fold_dock");
        bindSwitch(R.id.dock_glass, "dock_glass");
    }
    private void bindDisplayStyle() {
        android.widget.Button display = findViewById(R.id.display_style);
        android.content.SharedPreferences prefs = FeatureProvider.prefs(this);
        String[] styles = getResources().getStringArray(R.array.display_styles);
        int selected = Math.max(0, Math.min(2, prefs.getInt("display_style", 1)));
        display.setText(getString(R.string.display_style_current, styles[selected]));
        display.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.display_style).setSingleChoiceItems(styles, prefs.getInt("display_style", 1),
                        (dialog, which) -> {
                            prefs.edit().putInt("display_style", which).apply();
                            display.setText(getString(R.string.display_style_current, styles[which]));
                            getContentResolver().notifyChange(FeatureProvider.FEATURES, null);
                            dialog.dismiss();
                        }).show());
    }
    private void showClockWeights() {
        android.view.View panel=getLayoutInflater().inflate(R.layout.clock_weights,null);
        android.content.SharedPreferences prefs=FeatureProvider.prefs(this);
        android.widget.Spinner time=bindWeight(panel,R.id.time_weight,R.id.time_sample,false,prefs.getInt("time_weight",ClockTypography.DEFAULT_WEIGHT));
        android.widget.Spinner date=bindWeight(panel,R.id.date_weight,R.id.date_sample,true,prefs.getInt("date_weight",ClockTypography.DEFAULT_WEIGHT));
        new android.app.AlertDialog.Builder(this).setTitle(R.string.clock_weights).setView(panel)
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(android.R.string.ok,(dialog,which) -> {
                    prefs.edit().putInt("time_weight",time.getSelectedItemPosition())
                            .putInt("date_weight",date.getSelectedItemPosition()).apply();
                    getContentResolver().notifyChange(FeatureProvider.FEATURES,null);
                }).show();
    }

    private android.widget.Spinner bindWeight(android.view.View panel,int picker,int sample,boolean subtitle,int initial) {
        android.widget.Spinner spinner=panel.findViewById(picker);
        android.widget.TextView preview=panel.findViewById(sample);
        spinner.setSelection(ClockTypography.choice(initial));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View v,int index,long id) {
                preview.setTypeface(ClockTypography.face(subtitle,index));
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        return spinner;
    }

    private void bindSwitch(int id, String key) {
        Switch toggle = findViewById(id);
        toggle.setChecked(FeatureProvider.prefs(this).getBoolean(key, false));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            FeatureProvider.prefs(this).edit().putBoolean(key, checked).apply();
            if (java.util.Arrays.asList(FeatureProvider.CONTAINER_KEYS).contains(key)) {
                getContentResolver().notifyChange(FeatureProvider.CONTAINERS, null);
                return;
            }
            getContentResolver().notifyChange(("fold_dock".equals(key) || "dock_glass".equals(key)) ? FeatureProvider.DOCK : FeatureProvider.FEATURES, null);
            if ("icons".equals(key)) getContentResolver().notifyChange(FeatureProvider.ICONS, null);
        });
    }
}

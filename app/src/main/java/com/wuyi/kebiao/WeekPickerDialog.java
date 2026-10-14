package com.wuyi.kebiao;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 周次选择弹层。
 *
 * 为什么不用 WebView 里的 window.prompt()：那需要 WebChromeClient.onJsPrompt
 * 才会弹出，而当前只设置了一个默认的 WebChromeClient，prompt() 会毫无反应，
 * 变成一个静默无响应的假功能。这里改用原生 BottomSheet，行为可控。
 *
 * 周次数据来自教务系统页面回传的 weeks 列表，不在客户端硬编码 1..20。
 */
public class WeekPickerDialog {

    public interface OnWeekSelected {
        /** weekValue 为空字符串表示「全部」。 */
        void onSelected(String weekValue, String label);
    }

    private WeekPickerDialog() {
    }

    /**
     * @param weeksJson 形如 [{"value":"7","label":"第七周"}, ...]，可为 null
     */
    public static void show(@NonNull Context context, String weeksJson, String currentLabel,
                            @NonNull OnWeekSelected callback) {
        final BottomSheetDialog dialog = new BottomSheetDialog(context);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * context.getResources().getDisplayMetrics().density);
        container.setPadding(0, pad, 0, pad);

        TextView title = new TextView(context);
        title.setText("选择周次");
        title.setTextSize(16f);
        title.setPadding(pad * 3, pad * 2, pad * 3, pad * 2);
        container.addView(title);

        // 「全部」始终排在第一位（教务系统里 zc 为空即全部）
        addRow(context, container, "", "全部", currentLabel, dialog, callback);

        try {
            JSONArray arr = new JSONArray(weeksJson == null ? "[]" : weeksJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String value = o.optString("value", "");
                String label = o.optString("label", "");
                if (value.isEmpty() || label.isEmpty()) continue;
                addRow(context, container, value, label, currentLabel, dialog, callback);
            }
        } catch (Exception ignored) {
            // 列表解析失败时至少保留「全部」一项，不至于弹出一个空面板
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(container);
        dialog.setContentView(scroll);
        dialog.show();
    }

    private static void addRow(Context context, LinearLayout parent, final String value,
                               final String label, String currentLabel,
                               final BottomSheetDialog dialog, final OnWeekSelected callback) {
        View row = LayoutInflater.from(context).inflate(R.layout.item_week_option, parent, false);
        TextView tv = row.findViewById(R.id.week_label);
        tv.setText(label);
        // 当前周次高亮显示
        if (label != null && label.equals(currentLabel)) {
            tv.setTextColor(context.getResources().getColor(R.color.brand_primary));
            row.findViewById(R.id.week_check).setVisibility(View.VISIBLE);
        }
        row.setOnClickListener(v -> {
            dialog.dismiss();
            callback.onSelected(value, label);
        });
        parent.addView(row);
    }
}

package com.wuyi.kebiao;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WeekViewActivity extends AppCompatActivity {

    private LinearLayout tableContent;
    private List<Course> courses;
    private Gson gson = new Gson();

    private final String[] timeSlots = {"第一二节", "第三四节", "第五六节", "第七八节", "第九十节"};
    private final String[] days = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private final int[] cellIds = {R.id.cell_mon, R.id.cell_tue, R.id.cell_wed, R.id.cell_thu, R.id.cell_fri, R.id.cell_sat, R.id.cell_sun};
    private final int[] tvIds = {R.id.tv_mon, R.id.tv_tue, R.id.tv_wed, R.id.tv_thu, R.id.tv_fri, R.id.tv_sat, R.id.tv_sun};

    /*
      课程格子的配色资源 id。真实色值定义在 res/values/colors.xml，
      按课程名 hash 取模分配，同一门课每次颜色一样。

      ⚠ 这两个数组必须等长，且顺序一一对应（SOFT_BG[i] 配 DARK_TEXT[i]），
      新增颜色时两边都要加。用 colors.xml 而不是硬编码的原因：
      以后换配色只改资源文件，不用碰 Java。
    */
    private static final int[] COURSE_SOFT_RES = {
            R.color.course_soft_0, R.color.course_soft_1, R.color.course_soft_2,
            R.color.course_soft_3, R.color.course_soft_4, R.color.course_soft_5,
            R.color.course_soft_6
    };
    private static final int[] COURSE_TEXT_RES = {
            R.color.course_text_0, R.color.course_text_1, R.color.course_text_2,
            R.color.course_text_3, R.color.course_text_4, R.color.course_text_5,
            R.color.course_text_6
    };

    // 解析后的实际色值，在 onCreate 里填充（此时才有 Context 可读资源）
    private final int[] softBg = new int[COURSE_SOFT_RES.length];
    private final int[] darkText = new int[COURSE_TEXT_RES.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_week_view);
        resolveDesignColors();
        setupToolbar();
        tableContent = findViewById(R.id.table_content);
        loadCourses();
    }

    /**
     * 把 colors.xml 里的设计变量解析成实际色值。
     * 用 ContextCompat.getColor 而不是 Context.getColor：后者要 API 23，
     * 而本项目 minSdk 是 21。
     */
    private void resolveDesignColors() {
        for (int i = 0; i < COURSE_SOFT_RES.length; i++) {
            softBg[i] = ContextCompat.getColor(this, COURSE_SOFT_RES[i]);
            darkText[i] = ContextCompat.getColor(this, COURSE_TEXT_RES[i]);
        }
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void loadCourses() {
        String coursesJson = getIntent().getStringExtra("courses_json");
        if (coursesJson == null) {
            showEmptyState("暂无课表数据\n\n请先登录并更新课表");
            return;
        }
        Type type = new TypeToken<List<Course>>() {}.getType();
        courses = gson.fromJson(coursesJson, type);
        if (courses == null || courses.isEmpty()) {
            showEmptyState("暂无课表数据\n\n请先登录并更新课表");
            return;
        }
        buildTable();
    }

    private void showEmptyState(String msg) {
        tableContent.removeAllViews();
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 64, 0, 0);
        tv.setTextSize(14f);
        tv.setTextColor(0xFF757575);
        tableContent.addView(tv);
    }

    private void buildTable() {
        tableContent.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        Map<String, List<Course>> slotMap = groupByTimeSlot();

        for (String slot : timeSlots) {
            View row = getLayoutInflater().inflate(R.layout.item_week_row, tableContent, false);
            Map<String, Course> dayCourseMap = mapByDay(slotMap.get(slot));

            for (int i = 0; i < 7; i++) {
                LinearLayout cell = row.findViewById(cellIds[i]);
                TextView tv = row.findViewById(tvIds[i]);
                Course c = dayCourseMap.get(days[i]);
                setupCell(cell, tv, c, slot, days[i], density);
            }
            tableContent.addView(row);
        }
    }

    private Map<String, List<Course>> groupByTimeSlot() {
        Map<String, List<Course>> map = new HashMap<>();
        for (String s : timeSlots) map.put(s, new ArrayList<>());
        for (Course c : courses) {
            if (c.timeSlot != null && map.containsKey(c.timeSlot)) {
                map.get(c.timeSlot).add(c);
            }
        }
        return map;
    }

    private Map<String, Course> mapByDay(List<Course> list) {
        Map<String, Course> map = new HashMap<>();
        if (list == null) return map;
        for (Course c : list) {
            if (c.dayOfWeek != null) map.put(c.dayOfWeek, c);
        }
        return map;
    }

    private void setupCell(LinearLayout cell, TextView tv, Course c, String slot, String day, float d) {
        // 空格子与边框的颜色（设计变量，随主题切换）
        final int gridEmptyColor = ContextCompat.getColor(this, R.color.grid_empty);
        final int gridStrokeColor = ContextCompat.getColor(this, R.color.grid_stroke);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(8 * d); // 8dp 圆角

        if (c != null) {
            // 根据课程名 hash 分配固定颜色，同一门课每次颜色一样
            int colorIdx = Math.abs(c.name.hashCode()) % softBg.length;

            bg.setColor(softBg[colorIdx]);
            bg.setStroke(1, 0x15000000); // 极淡的边框

            // 处理地点换行，把换行符换成空格避免显示错乱
            String loc = (c.location == null) ? "" : c.location.replace('\n', ' ');
            tv.setText(c.name + "\n" + loc);
            tv.setTextColor(darkText[colorIdx]);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setTextSize(12f);

            cell.setOnClickListener(v -> showCourseDetail(c));
        } else {
            // 空单元格：浅灰背景 + 淡边框（夜间自动压暗）
            bg.setColor(gridEmptyColor);
            bg.setStroke(1, gridStrokeColor);
            tv.setText("");
            cell.setOnClickListener(v ->
                    Toast.makeText(this, day + " " + slot + " 暂无课程", Toast.LENGTH_SHORT).show()
            );
        }
        cell.setBackground(bg);
    }

    private void showCourseDetail(Course c) {
        String teacher = (c.teacher == null || c.teacher.isEmpty()) ? "未安排" : c.teacher;
        String location = (c.location == null || c.location.isEmpty()) ? "未安排" : c.location;
        String week = (c.weekRange == null || c.weekRange.isEmpty()) ? "全周" : c.weekRange;
        String date = (c.dayDate == null) ? "" : c.dayDate;

        String message = "👨‍🏫 教师：" + teacher + "\n\n"
                + "📍 地点：" + location + "\n\n"
                + "⏰ 时间：" + c.dayOfWeek + " " + c.timeSlot + "\n\n"
                + "📅 周次：" + week + "\n\n"
                + "📆 日期：" + date;

        new AlertDialog.Builder(this)
                .setTitle("📚 " + c.name)
                .setMessage(message)
                .setPositiveButton("关闭", null)
                .show();
    }

    // Course 数据类，保持与你原有字段一致
    public static class Course {
        public String name;
        public String teacher;
        public String location;
        public String weekRange;
        public String dayOfWeek;
        public String dayDate;
        public String timeSlot;
        public String sections;

        public Course() {
            weekRange = "";
        }
    }
}
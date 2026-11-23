package com.wuyi.kebiao;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 周次页：顶部周次卡 + 下面整块可双向滑动的课表网格。
 *
 * <p>一次拿全学期的课，切周只是换一个过滤条件，不联网、不等待 —— 这是
 * 把取数从"抓页面"换成"读接口"之后额外赚到的能力。
 */
public class WeekFragment extends Fragment implements ScheduleStore.Listener {

    /** 教务系统一学期最多排到 20 周，先按这个上限画周次选择。 */
    private static final int MAX_WEEK = 20;

    private TextView currentWeekView;
    private TextView pickWeekView;
    private TimetableGridView grid;
    /** 正在联网请求的那一周，避免每帧重复发请求。 */
    private int requestingWeek = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_week, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        currentWeekView = view.findViewById(R.id.current_week);
        pickWeekView = view.findViewById(R.id.pick_week);
        grid = view.findViewById(R.id.grid);

        view.findViewById(R.id.pick_week_row).setOnClickListener(v -> showWeekPicker());
        grid.setOnCourseClickListener(this::showCourseDetail);

        ScheduleStore.get().addListener(this);
        render();
    }

    @Override
    public void onDestroyView() {
        ScheduleStore.get().removeListener(this);
        super.onDestroyView();
    }

    @Override
    public void onScheduleChanged() {
        if (isAdded()) {
            render();
        }
    }

    private void render() {
        if (grid == null) {
            return;
        }
        ScheduleStore store = ScheduleStore.get();
        TimetableRepository.Snapshot snap = store.getSnapshot();
        if (snap == null) {
            currentWeekView.setText("--");
            pickWeekView.setText(getString(R.string.week_value, 0));
            grid.setCourses(new ArrayList<>());
            return;
        }
        int week = store.getViewingWeek();
        currentWeekView.setText(getString(R.string.week_value, snap.currentWeek));
        pickWeekView.setText(getString(R.string.week_value, week));

        // 每一周都以教务系统该周的真实课表为准。没取过就联网取一次，
        // 取回来会经 ScheduleStore 回调本方法重新渲染。
        if (!store.hasWeek(week) && requestingWeek != week) {
            requestingWeek = week;
            grid.setCourses(new ArrayList<>());
            pickWeekView.setText(getString(R.string.week_value, week) + " · 加载中…");
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).loadWeek(week);
            }
            return;
        }
        grid.setCourses(coursesIn(snap, week));
    }

    /**
     * 把"某一周该上的课"挑出来。
     * 取过该周就用真实周视图（含调课），没取过才退回推算 —— 规则都在解析层。
     */
    private List<TimetableParser.Course> coursesIn(TimetableRepository.Snapshot snap, int week) {
        return snap.coursesInWeek(week);
    }

    private void showWeekPicker() {
        int count = MAX_WEEK;
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            labels[i] = getString(R.string.week_value, i + 1);
        }
        int current = ScheduleStore.get().getViewingWeek();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.week_picker_title)
                .setSingleChoiceItems(labels, Math.max(0, current - 1), (dialog, which) -> {
                    requestingWeek = -1;
                    ScheduleStore.get().setViewingWeek(which + 1);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCourseDetail(TimetableParser.Course c) {
        StringBuilder sb = new StringBuilder();
        if (c.location != null && !c.location.isEmpty()) {
            sb.append(c.location);
        }
        if (c.teacher != null && !c.teacher.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(c.teacher);
        }
        if (c.sections != null && !c.sections.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(c.sections);
        }
        String weeks = c.weeks != null ? c.weeks.display() : c.weekRange;
        if (weeks != null && !weeks.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(getString(R.string.week_list_prefix, weeks));
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(c.name)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}

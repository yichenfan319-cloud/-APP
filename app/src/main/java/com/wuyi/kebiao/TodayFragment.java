package com.wuyi.kebiao;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.List;
import java.util.Locale;

/**
 * 本日页：顶栏 / 粒子球 + 状态 / 今天要上的课。
 *
 * <p>数据来源只有一个 {@link ScheduleStore}，页面自己不联网也不解析，
 * 这样三页看到的东西永远一致。
 */
public class TodayFragment extends Fragment implements ScheduleStore.Listener {

    private ThinkingOrbView orb;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView todayCount;
    private LinearLayout todayList;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_today, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        orb = view.findViewById(R.id.orb);
        statusTitle = view.findViewById(R.id.status_title);
        statusDetail = view.findViewById(R.id.status_detail);
        todayCount = view.findViewById(R.id.today_count);
        todayList = view.findViewById(R.id.today_list);

        view.findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), SettingsActivity.class)));

        ScheduleStore.get().addListener(this);
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
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
        if (orb == null) {
            return;
        }
        ScheduleStore store = ScheduleStore.get();
        ThinkingOrbView.State state = store.getState();
        orb.setState(state);
        orb.setInteractive(true);

        statusTitle.setText(titleOf(state));
        String detail = store.getDetail();
        statusDetail.setText(detail == null || detail.isEmpty()
                ? getString(R.string.status_detail_idle) : detail);

        List<TimetableParser.Course> today = store.coursesOn(System.currentTimeMillis());
        todayCount.setText(getString(R.string.course_count, today.size()));

        todayList.removeAllViews();
        if (today.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(R.string.no_course_today);
            empty.setTextSize(14f);
            empty.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    todayList, R.attr.colorTextHint, 0xFF999999));
            empty.setPadding(0, Math.round(dp(26)), 0, Math.round(dp(26)));
            todayList.addView(empty);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < today.size(); i++) {
            if (i > 0) {
                todayList.addView(divider());
            }
            todayList.addView(courseCard(inflater, today.get(i)));
        }
    }

    private View courseCard(LayoutInflater inflater, TimetableParser.Course c) {
        View v = inflater.inflate(R.layout.item_course_card, todayList, false);
        TextView slot = v.findViewById(R.id.slot_range);
        TextView name = v.findViewById(R.id.course_name);
        TextView loc = v.findViewById(R.id.course_location);
        TextView teacher = v.findViewById(R.id.course_teacher);
        TextView weeks = v.findViewById(R.id.course_weeks);

        int from = Math.min(c.sectionFrom, c.sectionTo);
        int to = Math.max(c.sectionFrom, c.sectionTo);
        slot.setText(from == to ? String.valueOf(from) : from + "-" + to);
        name.setText(c.name == null ? "" : c.name);
        loc.setText(c.location == null ? "" : c.location);
        teacher.setText(c.teacher == null ? "" : c.teacher);
        String w = c.weeks != null ? c.weeks.display() : c.weekRange;
        weeks.setText(w == null || w.isEmpty() ? "" : getString(R.string.week_list_prefix, w));
        return v;
    }

    private View divider() {
        View v = new View(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) dp(0.7f)));
        v.setLayoutParams(lp);
        v.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                todayList, R.attr.colorDivider, 0xFFE0E0E0));
        return v;
    }

    private String titleOf(ThinkingOrbView.State s) {
        switch (s) {
            case THINKING:
                return getString(R.string.status_thinking);
            case SEARCHING:
                return getString(R.string.status_searching);
            case DONE:
                return getString(R.string.status_done);
            case ERROR:
                return getString(R.string.status_error);
            case IDLE:
            default:
                return getString(R.string.status_idle);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 供以后显示"今天是几月几号周几"用，暂时没挂到界面上。 */
    @SuppressWarnings("unused")
    static String todayLabel() {
        return String.format(Locale.CHINA, "%tF", System.currentTimeMillis());
    }
}

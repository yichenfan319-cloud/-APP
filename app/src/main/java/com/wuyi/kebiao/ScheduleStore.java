package com.wuyi.kebiao;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 课表的内存态。
 *
 * <p>三页都要读同一份数据，而取数发生在 Activity 里的一张隐藏 WebView 后面，
 * 所以中间放一个进程内单例：谁改了数据就 {@link #notifyChanged()}，页面各自重画。
 * 不引入 LiveData/ViewModel 是因为只有一份状态、且生命周期跟 Activity 同长。
 */
public final class ScheduleStore {

    public interface Listener {
        void onScheduleChanged();
    }

    private static final ScheduleStore INSTANCE = new ScheduleStore();

    public static ScheduleStore get() {
        return INSTANCE;
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private TimetableRepository.Snapshot snapshot;
    private ThinkingOrbView.State state = ThinkingOrbView.State.IDLE;
    private String detail = "";
    /** 这一周用户想看第几周；0 表示跟随 snapshot.currentWeek。 */
    private int viewingWeek;

    private ScheduleStore() {
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        for (Listener l : listeners) {
            l.onScheduleChanged();
        }
    }

    public TimetableRepository.Snapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(TimetableRepository.Snapshot snap) {
        snapshot = snap;
        viewingWeek = snap == null ? 0 : snap.currentWeek;
        notifyChanged();
    }

    public ThinkingOrbView.State getState() {
        return state;
    }

    public String getDetail() {
        return detail;
    }

    public void setState(ThinkingOrbView.State s, String detailText) {
        state = s;
        detail = detailText == null ? "" : detailText;
        notifyChanged();
    }

    public int getViewingWeek() {
        if (viewingWeek > 0) {
            return viewingWeek;
        }
        return snapshot == null ? 0 : snapshot.currentWeek;
    }

    public void setViewingWeek(int week) {
        viewingWeek = week;
        notifyChanged();
    }

    /** 某一天该上的课（按节次排好）。日期用 System.currentTimeMillis 的当天。 */
    public List<TimetableParser.Course> coursesOn(long date) {
        return snapshot == null ? java.util.Collections.<TimetableParser.Course>emptyList()
                : snapshot.coursesOn(date);
    }
}

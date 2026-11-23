package com.wuyi.kebiao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 调课回归：当前周必须用"周视图"的数据，而不是拿学期视图推算。
 *
 * <p>背景：有同学反馈老师调了课，App 还显示原来的课表。原因是旧实现只取
 * {@code zc=（全部）} 一次，然后本地按周次区间推算。调课这种临时变动记在
 * "某一周"上，"全部"视图不一定带着它，而"周视图"（{@code zc=当周}）就是
 * 教务系统自己页面显示给学生的内容。
 *
 * <p>这套测试锁两件事：
 * <ol>
 *   <li>拿到周视图时，当前周的一切判断都以它为唯一来源；</li>
 *   <li>拿不到周视图时，退回学期视图推算，行为与以前一致。</li>
 * </ol>
 */
public class TimetableAdjustmentTest {

    /** 2026-2027 学年第 1 学期的第一周周一。 */
    private static final long FIRST_MONDAY = date(2026, 8, 31);
    /** 2026-11-23 是第 13 周的周一。 */
    private static final long W13_MON = date(2026, 11, 23);
    private static final long W13_TUE = date(2026, 11, 24);

    private static String allHtml;
    private static String week13Html;

    @BeforeClass
    public static void load() throws IOException {
        allHtml = read("kb_all_2026_2.html");
        week13Html = read("kb_week13_2026_2.html");
    }

    private static long date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, month - 1, day, 0, 0, 0);
        return c.getTimeInMillis();
    }

    private static String read(String name) throws IOException {
        InputStream in = TimetableAdjustmentTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull("测试夹具缺失：" + name, in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 夹具本身

    @Test
    public void 学期视图是十五门课周视图是十一门课() {
        assertEquals(15, TimetableParser.parse(allHtml).courses.size());
        assertEquals(11, TimetableParser.parse(week13Html).courses.size());
    }

    @Test
    public void 两个视图的表头都带第十三周日期() {
        TimetableParser.Result a = TimetableParser.parse(allHtml);
        TimetableParser.Result w = TimetableParser.parse(week13Html);
        assertEquals("11-23", a.days.get(0).date);
        assertEquals("11-23", w.days.get(0).date);
        assertEquals("11-29", w.days.get(6).date);
    }

    // ---------------------------------------------------------------- 有周视图

    @Test
    public void 有周视图时当前周以它为准() {
        TimetableParser.Result all = TimetableParser.parse(allHtml);
        TimetableParser.Result week = TimetableParser.parse(week13Html);
        TimetableRepository.Snapshot snap = new TimetableRepository.Snapshot(
                all.courses, week.courses, all.days, FIRST_MONDAY, 13);

        assertTrue(snap.currentWeekFromServer());
        assertEquals(13, snap.weekOf(W13_MON));
        assertEquals(3, snap.coursesOn(W13_MON).size());
        assertEquals(11, snap.coursesInWeek(13).size());
    }

    @Test
    public void 周视图里没有的课就当没排上() {
        // 模拟"老师把周一这门调走了"：周视图只留一门周二的课，
        // 学期视图里周一仍然有三门 —— 必须听周视图的。
        TimetableParser.Result all = TimetableParser.parse(allHtml);
        TimetableParser.Result week = TimetableParser.parse(week13Html);

        List<TimetableParser.Course> trimmed = new ArrayList<>();
        for (TimetableParser.Course c : week.courses) {
            if (c.dayIndex == 2) {          // 只保留周二的
                trimmed.add(c);
            }
        }
        assertFalse("周视图里应当有周二的课", trimmed.isEmpty());

        TimetableRepository.Snapshot snap = new TimetableRepository.Snapshot(
                all.courses, trimmed, all.days, FIRST_MONDAY, 13);

        assertEquals("周一应该被清空", 0, snap.coursesOn(W13_MON).size());
        assertEquals("周二保留", trimmed.size(), snap.coursesOn(W13_TUE).size());
    }

    @Test
    public void 周视图能多出一门学期视图里没有的课() {
        // 模拟"临时加课/补课"：周视图里多一门，学期视图推算不出来。
        TimetableParser.Result all = TimetableParser.parse(allHtml);
        TimetableParser.Result week = TimetableParser.parse(week13Html);

        // 挑一门确实排在周一的课来加（不能拿 get(0) 碰运气，它可能在别的星期）
        TimetableParser.Course extra = null;
        for (TimetableParser.Course c : week.courses) {
            if (c.dayIndex == 1) {
                extra = c;
                break;
            }
        }
        assertNotNull("周视图里应当有周一的课", extra);

        List<TimetableParser.Course> withExtra = new ArrayList<>(week.courses);
        withExtra.add(extra);              // 同一门放两次，数量可比

        TimetableRepository.Snapshot base = new TimetableRepository.Snapshot(
                all.courses, week.courses, all.days, FIRST_MONDAY, 13);
        TimetableRepository.Snapshot patched = new TimetableRepository.Snapshot(
                all.courses, withExtra, all.days, FIRST_MONDAY, 13);

        List<TimetableParser.Course> before = base.coursesOn(W13_MON);
        List<TimetableParser.Course> after = patched.coursesOn(W13_MON);
        assertEquals(before.size() + 1, after.size());
    }

    // ---------------------------------------------------------------- 无周视图

    @Test
    public void 没有周视图时退回学期视图推算() {
        TimetableParser.Result all = TimetableParser.parse(allHtml);
        TimetableRepository.Snapshot snap = new TimetableRepository.Snapshot(
                all.courses, null, all.days, FIRST_MONDAY, 13);

        assertFalse(snap.currentWeekFromServer());
        assertEquals(11, snap.coursesInWeek(13).size());
        assertEquals(3, snap.coursesOn(W13_MON).size());
    }

    @Test
    public void 有周视图的第十四周仍然走学期视图推算() {
        // 周视图只覆盖当前周。第 14 周必须还能算出来，不能因为"有周视图"就丢掉。
        TimetableParser.Result all = TimetableParser.parse(allHtml);
        TimetableParser.Result week = TimetableParser.parse(week13Html);
        TimetableRepository.Snapshot snap = new TimetableRepository.Snapshot(
                all.courses, week.courses, all.days, FIRST_MONDAY, 13);

        assertEquals("第十四周应有 12 门", 12, snap.coursesInWeek(14).size());
        long w14Mon = date(2026, 11, 30);
        assertEquals(14, snap.weekOf(w14Mon));
        assertEquals(3, snap.coursesOn(w14Mon).size());
    }

    @Test
    public void 周视图与学期视图推算正常情况下应当一致() {
        // 我们自己的账号没调过课，两个来源就该给出同样的结果。
        // 如果这条挂了，说明解析或周次判断有回归，而不是"有调课"。
        TimetableParser.Result all = TimetableParser.parse(allHtml);
        TimetableParser.Result week = TimetableParser.parse(week13Html);
        TimetableRepository.Snapshot snap = new TimetableRepository.Snapshot(
                all.courses, week.courses, all.days, FIRST_MONDAY, 13);

        for (int day = 1; day <= 7; day++) {
            long d = W13_MON + (day - 1) * 86400000L;
            int fromWeek = snap.coursesOn(d).size();
            // 用学期视图单独算一遍
            int fromAll = 0;
            for (TimetableParser.Course c : all.courses) {
                if (c.dayIndex == day && c.weeks.contains(13)) {
                    fromAll++;
                }
            }
            assertEquals("第 13 周第 " + day + " 天两个来源不一致", fromAll, fromWeek);
        }
    }

    // ------------------------------------------------- 会话内按周缓存（真实链接）

    @Test
    public void 当前周一开始就算取过() {
        TimetableParser.Result all = TimetableParser.parse(allHtml);
        TimetableParser.Result week = TimetableParser.parse(week13Html);
        TimetableRepository.Snapshot snap = new TimetableRepository.Snapshot(
                all.courses, week.courses, all.days, FIRST_MONDAY, 13);

        assertTrue("当前周由服务端给过，应当视为已取", snap.hasWeek(13));
        assertFalse("第 14 周还没取过", snap.hasWeek(14));
    }

    @Test
    public void 取回某一周后切到那一周不再走推算() {
        TimetableParser.Result all = TimetableParser.parse(allHtml);
        TimetableParser.Result week = TimetableParser.parse(week13Html);
        TimetableRepository.Snapshot snap = new TimetableRepository.Snapshot(
                null, week.courses, all.days, FIRST_MONDAY, 13);

        // 没有任何学期视图，第 14 周本来应该是空的
        assertEquals(0, snap.coursesInWeek(14).size());

        // 现在把第 14 周的真实课表塞进去（模拟联网取回）
        List<TimetableParser.Course> w14 = new ArrayList<>();
        for (TimetableParser.Course c : all.courses) {
            if (c.weeks.contains(14)) {
                w14.add(c);
            }
        }
        snap.putWeek(14, w14);

        assertTrue(snap.hasWeek(14));
        assertEquals(w14.size(), snap.coursesInWeek(14).size());
        long w14Mon = date(2026, 11, 30);
        assertEquals(3, snap.coursesOn(w14Mon).size());
    }

    @Test
    public void 没有学期视图时也不会凭空生成课表() {
        TimetableParser.Result week = TimetableParser.parse(week13Html);
        TimetableRepository.Snapshot snap = new TimetableRepository.Snapshot(
                null, week.courses, week.days, FIRST_MONDAY, 13);

        assertEquals(11, snap.coursesInWeek(13).size());
        assertEquals(0, snap.coursesInWeek(12).size());
        assertEquals(3, snap.coursesOn(W13_MON).size());
        assertEquals(0, snap.coursesOn(W13_MON - 7 * 86400000L).size());
    }
}

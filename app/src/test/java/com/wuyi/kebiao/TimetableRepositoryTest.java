package com.wuyi.kebiao;

import static org.junit.Assert.assertEquals;
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
 * 验证"两次请求 + 本地算日期"的核心逻辑。
 *
 * <p>不联网：用 2026-2027-1 学期的真实"全部视图"夹具构造 Snapshot，
 * 第一周周一按已知事实取 2026-08-31（第 1 周 09-01 是周二）。
 */
public class TimetableRepositoryTest {

    private static TimetableRepository.Snapshot snapshot;

    @BeforeClass
    public static void load() throws IOException {
        TimetableParser.Result all = TimetableParser.parse(readResource("/kb_all_current.html"));
        // 2026-08-31 是第 1 周周一
        snapshot = new TimetableRepository.Snapshot(
                all.courses, all.days, ms(2026, 8, 31), 12);
    }

    // ------------------------------------------------------------ 日期换算

    @Test
    public void 周次按第一周周一推算() {
        assertEquals("第 1 周周一", 1, snapshot.weekOf(ms(2026, 8, 31)));
        assertEquals("第 1 周周日", 1, snapshot.weekOf(ms(2026, 9, 6)));
        assertEquals("第 2 周周一", 2, snapshot.weekOf(ms(2026, 9, 7)));
        assertEquals("第 12 周周五", 12, snapshot.weekOf(ms(2026, 11, 20)));
        assertEquals("第 17 周周一", 17, snapshot.weekOf(ms(2026, 12, 21)));
        assertEquals("第 20 周周一", 20, snapshot.weekOf(ms(2027, 1, 11)));
    }

    @Test
    public void 星期索引是周一起始() {
        assertEquals("2026-08-31 是周一", 1, snapshot.dayIndex(ms(2026, 8, 31)));
        assertEquals("2026-09-05 是周六", 6, snapshot.dayIndex(ms(2026, 9, 5)));
        assertEquals("2026-09-06 是周日", 7, snapshot.dayIndex(ms(2026, 9, 6)));
        assertEquals("2026-11-20 是周五", 5, snapshot.dayIndex(ms(2026, 11, 20)));
    }

    @Test
    public void 服务器表头日期能反推当前周() {
        // 表头 11-16..11-22，落在第 12 周
        assertEquals("11-16", snapshot.currentWeekDays.get(0).date);
        assertEquals(12, snapshot.currentWeek);
        assertEquals(12, snapshot.weekOf(TimetableRepository.resolveDate("11-20",
                ms(2026, 11, 20))));
    }

    // ------------------------------------------------------------ 取某天的课

    @Test
    public void 今天_2026年11月20日周五() {
        List<String> names = names(snapshot.coursesOn(ms(2026, 11, 20)));
        assertEquals("第12周周五两门，实际=" + names, 2, names.size());
        assertTrue(names.contains("大学英语3"));
        assertTrue(names.contains("数字逻辑"));
    }

    @Test
    public void 明天_周六无课() {
        assertEquals(0, snapshot.coursesOn(ms(2026, 11, 21)).size());
    }

    @Test
    public void 周三有四门且按节次排序() {
        List<TimetableParser.Course> list = snapshot.coursesOn(ms(2026, 11, 18));
        assertEquals(4, list.size());
        for (int i = 1; i < list.size(); i++) {
            assertTrue("应按起始节次升序",
                    list.get(i - 1).sectionFrom <= list.get(i).sectionFrom);
        }
    }

    @Test
    public void 单周周三少了双周课() {
        List<String> names = names(snapshot.coursesOn(ms(2026, 11, 11)));
        assertEquals(3, names.size());
        assertTrue("第11周是单周，不该有双周课", !names.contains("马克思主义基本原理"));
    }

    @Test
    public void 整周分组有七天() {
        List<List<TimetableParser.Course>> week = snapshot.wholeWeek();
        assertEquals(7, week.size());
    }

    @Test
    public void 学期开始前不返回课() {
        // 第一周周一之前
        assertEquals(0, snapshot.coursesOn(ms(2026, 8, 24)).size());
    }

    // ------------------------------------------------------------ 纯函数

    @Test
    public void 月份日期按最接近的年份解析() {
        long today = ms(2026, 11, 20);
        // 同一年内
        assertEquals(TimetableRepository.formatDate(ms(2026, 9, 1)),
                TimetableRepository.formatDate(TimetableRepository.resolveDate("09-01", today)));
        // 表头写 01-04 时，应理解为下一年年初（离 11-20 更近的 1 月是 2027）
        assertEquals("2027-01-04",
                TimetableRepository.formatDate(TimetableRepository.resolveDate("01-04", today)));
        // 非法输入
        assertEquals(Long.MIN_VALUE, TimetableRepository.resolveDate("", today));
        assertEquals(Long.MIN_VALUE, TimetableRepository.resolveDate("abc", today));
        assertEquals(Long.MIN_VALUE, TimetableRepository.resolveDate("13-40", today));
    }

    @Test
    public void 所在周的周一() {
        assertEquals(TimetableRepository.formatDate(ms(2026, 11, 16)),
                TimetableRepository.formatDate(TimetableRepository.mondayOf(ms(2026, 11, 20))));
        // 周日应回到同一周的周一，而不是下一周
        assertEquals(TimetableRepository.formatDate(ms(2026, 11, 16)),
                TimetableRepository.formatDate(TimetableRepository.mondayOf(ms(2026, 11, 22))));
    }

    @Test
    public void 多数表决取众数() {
        List<Long> v = new ArrayList<>();
        v.add(100L);
        v.add(200L);
        v.add(200L);
        v.add(300L);
        assertEquals(200L, TimetableRepository.majority(v));
    }

    // ------------------------------------------------------------ 辅助

    private static List<String> names(List<TimetableParser.Course> list) {
        List<String> out = new ArrayList<>();
        for (TimetableParser.Course c : list) {
            out.add(c.name);
        }
        return out;
    }

    /** 构造某天 00:00 的毫秒值（月份 1 起）。 */
    private static long ms(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day, 0, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = TimetableRepositoryTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("资源不存在: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

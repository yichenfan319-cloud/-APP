package com.wuyi.kebiao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 2026-2027-1 学期的真实数据验证"按周次过滤"。
 *
 * <p>夹具 {@code kb_all_current.html} 是 {@code loadkb.jsp?zc=}（周次参数留空 = 全部）
 * 的原样响应，表头日期 11-16 ~ 11-22 即第 12 周，共 15 门课。
 *
 * <p>这个学期引入了旧夹具里没有的周次形态：
 * <ul>
 *   <li>{@code [1-16双周]}  —— 仅双周</li>
 *   <li>{@code [1,3,5,...,15周]} 与 {@code [2,4,6,...,16周]} 同一门课拆成两份</li>
 * </ul>
 */
public class TimetableWeekFilterTest {

    private static TimetableParser.Result all;

    @BeforeClass
    public static void load() throws IOException {
        all = TimetableParser.parse(readResource("/kb_all_current.html"));
    }

    // ------------------------------------------------------------ 基本解析

    @Test
    public void 全部视图解析出十五门课() {
        assertEquals(15, all.courses.size());
    }

    @Test
    public void 全部视图表头带当前周日期() {
        assertEquals(7, all.days.size());
        assertEquals("周一", all.days.get(0).name);
        assertEquals("11-16", all.days.get(0).date);
        assertEquals("周五", all.days.get(4).name);
        assertEquals("11-20", all.days.get(4).date);
        assertEquals("周日", all.days.get(6).name);
        assertEquals("11-22", all.days.get(6).date);
    }

    @Test
    public void 全部视图没有告警() {
        if (!all.warnings.isEmpty()) {
            fail("不应有告警，实际: " + all.warnings);
        }
    }

    // ------------------------------------------------------------ 双周 / 单周

    @Test
    public void 双周课被识别为EVEN() {
        TimetableParser.Course marx = find("马克思主义基本原理", 3, "[1-16双周]");
        assertNotNull("应有周三的马克思主义基本原理[1-16双周]", marx);
        assertEquals(TimetableParser.WeekKind.EVEN, marx.weeks.kind);
        assertEquals(1, marx.weeks.from);
        assertEquals(16, marx.weeks.to);
        // 第 12 周是双周 -> 上；第 7 周是单周 -> 不上
        assertTrue("第12周应上", marx.weeks.contains(12));
        assertFalse("第7周不应上", marx.weeks.contains(7));
    }

    @Test
    public void 单周文本被识别为ODD() {
        // 直接测纯函数，夹具里暂时只有双周，但单周是同一条代码路径
        TimetableParser.WeekRange w = TimetableParser.parseWeekRangeFull("[1-16单周] 星期三");
        assertEquals(TimetableParser.WeekKind.ODD, w.kind);
        assertFalse("第12周(双)不应上", w.contains(12));
        assertTrue("第7周(单)应上", w.contains(7));
    }

    /**
     * 回归：曾经先 strip 掉结尾的"周"字再判 endsWith("双周")，
     * "1-16双周" 变成 "1-16双" 后判断永远不成立，
     * 结果双周课被当成"每周都上"。
     */
    @Test
    public void 双周不能被当成每周都上() {
        TimetableParser.WeekRange even = TimetableParser.parseWeekRangeFull("[1-16双周]");
        assertTrue("双周课在第 11 周不应出现", !even.contains(11));
        assertTrue("双周课在第 12 周应出现", even.contains(12));
        // 反向确认：区间型才是每周都上
        assertTrue(TimetableParser.parseWeekRangeFull("[1-16周]").contains(11));
    }

    // ------------------------------------------------------------ 按周过滤

    @Test
    public void 第十二周周五有两门课() {
        List<String> names = namesOn(5, 12);
        assertEquals("2026-11-20 是第12周周五", 2, names.size());
        assertTrue("应含数字逻辑[11-14周], 实际=" + names, names.contains("数字逻辑"));
        assertTrue("应含大学英语3(偶数周), 实际=" + names, names.contains("大学英语3"));
    }

    @Test
    public void 第七周周五只上单周的英语课() {
        // 第 7 周是单周：大学英语3[1,3,5,...,15周] 命中，[2,4,...,16周] 不命中；
        // 数字逻辑[11-14周] 也不命中。所以只剩 1 门。
        List<String> names = namesOn(5, 7);
        assertEquals("第7周周五应只有 1 门，实际=" + names, 1, names.size());
        assertEquals("大学英语3", names.get(0));
    }

    @Test
    public void 第十二周周三有四门课() {
        List<String> names = namesOn(3, 12);
        assertEquals("第12周周三(双周) 应有 4 门，实际=" + names, 4, names.size());
        assertTrue(names.contains("数据结构"));
        assertTrue(names.contains("数据结构实验"));
        assertTrue(names.contains("概率论与数理统计"));
        assertTrue("双周课第12周应上", names.contains("马克思主义基本原理"));
    }

    @Test
    public void 第十一周周三没有双周课() {
        List<String> names = namesOn(3, 11);
        assertEquals("第11周周三(单周) 应有 3 门，实际=" + names, 3, names.size());
        assertFalse("双周课第11周不应出现", names.contains("马克思主义基本原理"));
    }

    @Test
    public void 周一三门前两门周才开始的课() {
        List<String> names = namesOn(1, 12);
        assertEquals(3, names.size());
        assertTrue(names.contains("线性代数"));
        assertTrue(names.contains("数字逻辑"));
        assertTrue(names.contains("毛泽东思想和中国特色社会主义理论体系概论"));

        // 这些课都是 [2-17周]，第 1 周不该有
        assertEquals("第1周周一应为空", 0, namesOn(1, 1).size());
    }

    @Test
    public void 周末第十二周无课() {
        assertEquals(0, namesOn(6, 12).size());
        assertEquals(0, namesOn(7, 12).size());
        // 但体育与健康3[15-16周] 在周日
        List<String> sun16 = namesOn(7, 16);
        assertEquals("第16周周日应有 1 门，实际=" + sun16, 1, sun16.size());
        assertEquals("体育与健康3", sun16.get(0));
    }

    @Test
    public void 第十七周只剩三门前半学期的课() {
        int total = 0;
        for (int day = 1; day <= 7; day++) {
            total += namesOn(day, 17).size();
        }
        assertEquals("第17周应共 3 门，实际算出 " + total, 3, total);
    }

    @Test
    public void 第十八周以后全部无课() {
        for (int week = 18; week <= 20; week++) {
            for (int day = 1; day <= 7; day++) {
                assertEquals("第" + week + "周第" + day + "天应为空",
                        0, namesOn(day, week).size());
            }
        }
    }

    // ------------------------------------------------------------ 纯函数

    @Test
    public void 周次四种形态() {
        assertEquals(TimetableParser.WeekKind.RANGE,
                TimetableParser.parseWeekRangeFull("[2-17周] 星期一").kind);
        assertEquals(TimetableParser.WeekKind.LIST,
                TimetableParser.parseWeekRangeFull("[1,3,5周] 星期五").kind);
        assertEquals(TimetableParser.WeekKind.EVEN,
                TimetableParser.parseWeekRangeFull("[1-16双周]").kind);
        assertEquals(TimetableParser.WeekKind.ODD,
                TimetableParser.parseWeekRangeFull("[1-16单周]").kind);
        assertEquals(TimetableParser.WeekKind.ALL,
                TimetableParser.parseWeekRangeFull("没写周次").kind);
    }

    @Test
    public void 周次展示文本() {
        assertEquals("2-17", TimetableParser.parseWeekRangeFull("[2-17周]").display());
        assertEquals("1,3,5", TimetableParser.parseWeekRangeFull("[1,3,5周]").display());
        assertEquals("1-16双", TimetableParser.parseWeekRangeFull("[1-16双周]").display());
        assertEquals("1-16单", TimetableParser.parseWeekRangeFull("[1-16单周]").display());
    }

    @Test
    public void matchesWeek直接吃原始文本() {
        assertTrue(TimetableParser.matchesWeek("[2-17周] 星期一", 5));
        assertFalse(TimetableParser.matchesWeek("[2-17周] 星期一", 1));
        assertTrue(TimetableParser.matchesWeek("[1-16双周] 星期三", 12));
        assertFalse(TimetableParser.matchesWeek("[1-16双周] 星期三", 11));
    }

    // ------------------------------------------------------------ 辅助

    /** 第 {@code dayIndex} 列（1=周一）、第 {@code week} 周要上的课名。 */
    private static List<String> namesOn(int dayIndex, int week) {
        List<String> out = new ArrayList<>();
        for (TimetableParser.Course c : all.courses) {
            if (c.dayIndex == dayIndex && c.weeks.contains(week)) {
                out.add(c.name);
            }
        }
        return out;
    }

    private static TimetableParser.Course find(String name, int dayIndex, String weekText) {
        for (TimetableParser.Course c : all.courses) {
            // weekText 传的是周次片段（如 "[1-16双周]"），真实文本还带" 星期三"后缀
            if (name.equals(c.name) && c.dayIndex == dayIndex && c.weekText.contains(weekText)) {
                return c;
            }
        }
        return null;
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = TimetableWeekFilterTest.class.getResourceAsStream(path)) {
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

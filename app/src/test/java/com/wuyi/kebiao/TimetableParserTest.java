package com.wuyi.kebiao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用真实教务系统片段跑解析器。
 *
 * <p>夹具 kb_fragment.html 是从学生端个人中心页面里 {@code #kbLoading} 的 innerHTML
 * 原样抽出来的，也就是 {@code mainV_index_loadkb.jsp} 返回的内容本体，
 * 对应 2025-2026-2 学期第二周（03-16 ~ 03-22），共 18 门课。
 */
public class TimetableParserTest {

    private static TimetableParser.Result result;

    @BeforeClass
    public static void load() throws IOException {
        String html = readResource("/kb_fragment.html");
        result = TimetableParser.parse(html);
    }

    // ------------------------------------------------------------ 表头

    @Test
    public void 表头是一周七天且带服务端日期() {
        assertEquals("表头应有 7 天", 7, result.days.size());

        String[] expectedNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        String[] expectedDates = {"03-16", "03-17", "03-18", "03-19", "03-20", "03-21", "03-22"};
        for (int i = 0; i < 7; i++) {
            assertEquals("第 " + (i + 1) + " 天名字", expectedNames[i], result.days.get(i).name);
            assertEquals("第 " + (i + 1) + " 天日期", expectedDates[i], result.days.get(i).date);
        }
    }

    // ------------------------------------------------------------ 数量与告警

    @Test
    public void 解析出十八门课() {
        assertEquals(18, result.courses.size());
    }

    @Test
    public void 没有告警() {
        if (!result.warnings.isEmpty()) {
            fail("不应有告警，实际: " + result.warnings);
        }
    }

    @Test
    public void 每门课字段都完整() {
        for (TimetableParser.Course c : result.courses) {
            assertTrue(c.name + " 缺教师", !c.teacher.isEmpty());
            assertTrue(c.name + " 缺地点", !c.location.isEmpty());
            assertTrue(c.name + " 缺周次", !c.weekRange.isEmpty());
            assertTrue(c.name + " 缺星期", !c.dayOfWeek.isEmpty());
            assertTrue(c.name + " 缺日期", !c.date.isEmpty());
            assertTrue(c.name + " 缺节次", c.sectionFrom > 0);
        }
    }

    // ------------------------------------------------------------ 列位置 = 星期

    @Test
    public void 列位置决定星期而不是文本() {
        TimetableParser.Course p = find("大学物理B", "1-2");
        assertNotNull(p);
        assertEquals("周一", p.dayOfWeek);
        assertEquals(1, p.dayIndex);
        assertEquals("03-16", p.date);

        TimetableParser.Course sat = null;
        for (TimetableParser.Course c : result.courses) {
            if ("创业基础".equals(c.name) && "周六".equals(c.dayOfWeek)) {
                sat = c;
            }
        }
        assertNotNull("应有周六的创业基础", sat);
        assertEquals(6, sat.dayIndex);
        assertEquals("03-21", sat.date);
        assertEquals("线上平台(非教室)高校邦", sat.location);
    }

    /**
     * 回归：旧实现 {@code tds[dayIdx]} 不跳过 rowspan 占位，
     * 在"周五 大学物理实验B rowspan=2"之后的下一行会整体左移一列。
     * 本测试确认第七八节那一行的课程星期没有被挪动。
     */
    @Test
    public void rowspan之后列不左移() {
        TimetableParser.Course moral = find("思想道德与法治", "7-8");
        assertNotNull(moral);
        assertEquals("思想道德与法治 应在周一", "周一", moral.dayOfWeek);

        TimetableParser.Course math = find("高等数学B2", "7-8");
        assertNotNull(math);
        assertEquals("高等数学B2(7-8) 应在周四", "周四", math.dayOfWeek);
        assertEquals(4, math.dayIndex);
    }

    // ------------------------------------------------------------ 节次与 rowspan

    @Test
    public void 节次以文本为准而非rowspan() {
        TimetableParser.Course lab = find("大学物理实验B", "5-7");
        assertNotNull("大学物理实验B 三节(5~7小节)", lab);
        assertEquals(5, lab.sectionFrom);
        assertEquals(7, lab.sectionTo);
        assertEquals("表格占两行", 2, lab.span);
        assertEquals("但节数以文本为准，是三节", "5-7", lab.sections);
    }

    // ------------------------------------------------------------ 地点去重

    @Test
    public void 地点整段重复被折叠() {
        TimetableParser.Course phys = find("大学物理B", "1-2");
        assertNotNull(phys);
        assertEquals("瑞樟11-108[基础型智慧教室]", phys.location);
    }

    @Test
    public void 地点内嵌重复被折叠() {
        TimetableParser.Course pe = find("体育与健康2", "5-6");
        assertNotNull(pe);
        assertEquals("瑞樟体育场[匹克球场]", pe.location);
    }

    @Test
    public void 没有残留的重复前缀() {
        for (TimetableParser.Course c : result.courses) {
            assertTrue(c.name + " 地点仍有重复: " + c.location,
                    !c.location.contains("瑞樟11瑞樟11")
                            && !c.location.contains("瑞樟10瑞樟10")
                            && !c.location.contains("瑞樟8瑞樟8")
                            && !c.location.contains("瑞樟体育场瑞樟"));
        }
    }

    // ------------------------------------------------------------ 周次

    @Test
    public void 周次区间与枚举都能解析() {
        TimetableParser.Course ranged = find("大学物理B", "1-2");
        assertNotNull(ranged);
        assertEquals("2-17", ranged.weekRange);

        TimetableParser.Course listed = null;
        for (TimetableParser.Course c : result.courses) {
            if ("军事理论".equals(c.name)) {
                listed = c;
            }
        }
        assertNotNull(listed);
        assertEquals("1,3,5,7,9,11,13,15", listed.weekRange);

        for (TimetableParser.Course c : result.courses) {
            assertTrue(c.name + " 周次多了重复的周字: " + c.weekRange,
                    !c.weekRange.contains("周周"));
        }
    }

    // ------------------------------------------------------------ 纯函数

    @Test
    public void 去重函数的边界() {
        assertEquals("瑞樟4-101", TimetableParser.dedupPrefix("瑞樟4瑞樟4-101"));
        assertEquals("体育馆[篮球场]", TimetableParser.dedupPrefix("体育馆体育馆[篮球场]"));
        assertEquals("瑞樟体育场[匹克球场]", TimetableParser.dedupPrefix("瑞樟体育场瑞樟[匹克球场]"));
        // 正常短词不能被误伤
        assertEquals("未知地点", TimetableParser.dedupPrefix("未知地点"));
        assertEquals("", TimetableParser.dedupPrefix(""));
        assertEquals("", TimetableParser.dedupPrefix(null));
        // 幂等
        String once = TimetableParser.dedupPrefix("瑞樟11瑞樟11-108[基础型智慧教室]");
        assertEquals(once, TimetableParser.dedupPrefix(once));
    }

    @Test
    public void 节次解析() {
        assertEquals(1, TimetableParser.parseSections("01~02小节")[0]);
        assertEquals(2, TimetableParser.parseSections("01~02小节")[1]);
        assertEquals(5, TimetableParser.parseSections("05~07小节")[0]);
        assertEquals(7, TimetableParser.parseSections("05~07小节")[1]);
        assertEquals(0, TimetableParser.parseSections("没数字")[0]);
    }

    @Test
    public void 周次文本解析() {
        assertEquals("2-17", TimetableParser.parseWeekRange("[2-17周] 星期一"));
        assertEquals("1,3,5", TimetableParser.parseWeekRange("[1,3,5周] 星期五"));
        assertEquals("", TimetableParser.parseWeekRange("没有方括号"));
    }

    // ------------------------------------------------------------ 辅助

    private static TimetableParser.Course find(String name, String sections) {
        for (TimetableParser.Course c : result.courses) {
            if (name.equals(c.name) && sections.equals(c.sections)) {
                return c;
            }
        }
        return null;
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = TimetableParserTest.class.getResourceAsStream(path)) {
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

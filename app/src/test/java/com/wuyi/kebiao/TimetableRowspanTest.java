package com.wuyi.kebiao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 列号必须是"逐列占用"推出来的，不能靠数第几个 td。
 *
 * <p>真实 HTML 里，一个跨两节的课带 {@code rowspan="2"}，它会吃掉下一行的那个
 * {@code <td>}（5 行的表里，第 4 行就只有 7 个 td）。旧实现用
 * {@code col += rowspan} 前进列号，于是在同一行里把它右边的课全部右移，
 * 又在下一行里把被占列之后的课全部左移 —— 表现就是"周五下午的课跑到周六"。
 *
 * <p>这套用例用一段最小 HTML 把两个方向都钉死：
 * <ol>
 *   <li>同一行：周一那门跨两节，右边的周五课必须仍然是周五；</li>
 *   <li>下一行：周一被占，只有 6 个格子，里面的周六课必须仍然是周六。</li>
 * </ol>
 */
public class TimetableRowspanTest {

    private static final String[] DAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] DAY_DATES = {"11-23", "11-24", "11-25", "11-26", "11-27", "11-28", "11-29"};

    /** 造一个课块。 */
    private static String course(String name, String sections, String weekText) {
        return "<ul class=\"courselists qz-flex-col\">"
                + "<li class=\"courselists-item qz-hasCourse-1\">"
                + "<div class=\"qz-hasCourse-title qz-ellipse\">" + name + "</div>"
                + "<div class=\"qz-hasCourse-detaillists\">"
                + "<div class=\"qz-hasCourse-detailitem\">" + sections + "</div>"
                + "<div class=\"qz-hasCourse-detailitem\">" + weekText + "</div>"
                + "</div></li></ul>";
    }

    private static String emptyCell() {
        return "<td class=\"qz-weeklyTable-td\"></td>";
    }

    private static String cell(String inner, int rowspan) {
        return "<td class=\"qz-weeklyTable-td qz-hasCourse\" rowspan=\"" + rowspan + "\">"
                + inner + "</td>";
    }

    /**
     * 第一二节：周一跨两节，周五有课；
     * 第三四节：周一被上一行占住，只有二三四五六日六个格子，其中周六有课。
     */
    private static String html() {
        StringBuilder head = new StringBuilder();
        head.append("<table class=\"qz-weeklyTable\" id=\"timetable\">")
                .append("<thead class=\"qz-weeklyTable-thead\"><tr class=\"qz-weeklyTable-tr\">")
                .append("<th class=\"qz-weeklyTable-th qz-weeklyTable-label qz-ellipse\">节次</th>");
        for (int i = 0; i < DAY_NAMES.length; i++) {
            head.append("<th class=\"qz-weeklyTable-th qz-ellipse\"><span>")
                    .append(DAY_NAMES[i]).append("<br/></span><span>")
                    .append(DAY_DATES[i]).append("</span></th>");
        }
        head.append("</tr></thead><tbody>");

        // 第一二节
        head.append("<tr>")
                .append("<td class=\"qz-weeklyTable-td qz-weeklyTable-label\">第一二节</td>")
                .append(cell(course("周一长课", "01~02小节", "[1-16周] 星期一"), 2))
                .append(emptyCell()).append(emptyCell()).append(emptyCell())
                .append(cell(course("周五下午课", "01~02小节", "[1-16周] 星期五"), 1))
                .append(emptyCell()).append(emptyCell())
                .append("</tr>");

        // 第三四节：周一被上一行的 rowspan 占住，所以只有 6 个数据格
        head.append("<tr>")
                .append("<td class=\"qz-weeklyTable-td qz-weeklyTable-label\">第三四节</td>")
                .append(emptyCell())   // 周二
                .append(emptyCell())   // 周三
                .append(emptyCell())   // 周四
                .append(emptyCell())   // 周五
                .append(cell(course("周六课", "03~04小节", "[1-16周] 星期六"), 1))  // 周六
                .append(emptyCell())   // 周日
                .append("</tr>");

        head.append("</tbody></table>");
        return head.toString();
    }

    private static TimetableParser.Course find(List<TimetableParser.Course> list, String name) {
        for (TimetableParser.Course c : list) {
            if (name.equals(c.name)) {
                return c;
            }
        }
        return null;
    }

    @Test
    public void 同一行里跨节课的右边不会被右移() {
        TimetableParser.Result r = TimetableParser.parse(html());
        TimetableParser.Course fri = find(r.courses, "周五下午课");
        assertNotNull("周五那门课应该被解析出来", fri);
        assertEquals("列号必须是 5（周五）", 5, fri.dayIndex);
        assertEquals("周五", fri.dayOfWeek);
    }

    @Test
    public void 被跨节课占掉的下一行列号不会左移() {
        TimetableParser.Result r = TimetableParser.parse(html());
        TimetableParser.Course sat = find(r.courses, "周六课");
        assertNotNull("周六那门课应该被解析出来", sat);
        assertEquals("列号必须是 6（周六）", 6, sat.dayIndex);
        assertEquals("周六", sat.dayOfWeek);
    }

    @Test
    public void 跨节课自己仍然是周一() {
        TimetableParser.Result r = TimetableParser.parse(html());
        TimetableParser.Course mon = find(r.courses, "周一长课");
        assertNotNull(mon);
        assertEquals(1, mon.dayIndex);
        assertEquals("周一", mon.dayOfWeek);
        assertEquals("rowspan 仍记在 span 上，用于画高度", 2, mon.span);
    }

    @Test
    public void 列位置与文本一致时不应有告警() {
        TimetableParser.Result r = TimetableParser.parse(html());
        assertTrue("不该有列位置与文本不一致的告警：" + r.warnings, r.warnings.isEmpty());
    }
}

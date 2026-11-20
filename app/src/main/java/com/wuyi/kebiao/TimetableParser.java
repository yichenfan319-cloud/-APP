package com.wuyi.kebiao;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教务系统课表片段解析器。
 *
 * <p>输入是 {@code /jsxsd/framework/mainV_index_loadkb.jsp} 返回的 HTML 片段
 * （也就是页面上 {@code #kbLoading} 的 innerHTML），输出结构化的星期表头 + 课程列表。
 *
 * <p><b>为什么按表格几何位置解析，而不是按文本里的"星期一"</b>
 * <ul>
 *   <li>列位置就是星期几，这是表格本身的语义，不会因为文案变化而失效；</li>
 *   <li>表头 {@code <span>03-16</span>} 给出了该周的<b>真实日期</b> —— 服务端已经
 *       把"哪一周的哪一天是几月几号"算好了，客户端不需要再做任何跨周推算；</li>
 *   <li>旧实现用 {@code tds[dayIdx]} 直接按下标取列，遇到 {@code rowspan} 会把
 *       后面所有列整体左移，导致"周五的课显示成周四"。本类用列指针
 *       {@code col += rowspan} 跟踪，从根上避免这个问题。</li>
 * </ul>
 *
 * <p><b>节次以文本为准，不以 rowspan 为准</b>：真实数据里存在
 * {@code rowspan="2"} 但 {@code 05~07小节}（三节）的情况，两者并不等价。
 * {@code rowspan} 只用于跳过被占用的单元格。
 *
 * <p>纯 Java + jsoup，不依赖 Android，可直接在 JVM 单元测试里跑。
 */
public final class TimetableParser {

    /** 表头里的一天。date 形如 "03-16"，由服务端给出。 */
    public static final class Day {
        public final String name;   // "周一"
        public final String date;   // "03-16"，可能为 null

        Day(String name, String date) {
            this.name = name;
            this.date = date;
        }
    }

    /** 周次形态。教务系统真实出现过这四种写法。 */
    public enum WeekKind {
        /** 没有周次信息，视为每周都上。 */
        ALL,
        /** "[2-17周]" */
        RANGE,
        /** "[1,3,5,7,9,11,13,15周]" */
        LIST,
        /** "[1-16双周]" */
        EVEN,
        /** "[1-16单周]" */
        ODD
    }

    /**
     * 解析后的周次区间。能回答"第 N 周上不上这门课"。
     *
     * <p>四种真实形态都要支持：
     * <ul>
     *   <li>{@code [2-17周]}      区间</li>
     *   <li>{@code [1,3,5,7周]}  枚举</li>
     *   <li>{@code [1-16双周]}   仅双周</li>
     *   <li>{@code [1-16单周]}   仅单周</li>
     * </ul>
     */
    public static final class WeekRange {
        public final WeekKind kind;
        public final int from;
        public final int to;
        /** LIST 时有值，其他形态为空数组。 */
        public final int[] weeks;

        WeekRange(WeekKind kind, int from, int to, int[] weeks) {
            this.kind = kind;
            this.from = from;
            this.to = to;
            this.weeks = weeks;
        }

        /** 第 {@code week} 周（1 起）是否要上这门课。 */
        public boolean contains(int week) {
            switch (kind) {
                case RANGE:
                    return week >= from && week <= to;
                case LIST:
                    for (int w : weeks) {
                        if (w == week) {
                            return true;
                        }
                    }
                    return false;
                case EVEN:
                    return week >= from && week <= to && week % 2 == 0;
                case ODD:
                    return week >= from && week <= to && week % 2 == 1;
                case ALL:
                default:
                    return true;
            }
        }

        /** 给人看的短文本，例如 "2-17" / "1,3,5" / "1-16双" / "1-16单"。 */
        public String display() {
            switch (kind) {
                case RANGE:
                    return from + "-" + to;
                case EVEN:
                    return from + "-" + to + "双";
                case ODD:
                    return from + "-" + to + "单";
                case LIST: {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < weeks.length; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append(weeks[i]);
                    }
                    return sb.toString();
                }
                case ALL:
                default:
                    return "";
            }
        }
    }

    /** 一门课。 */
    public static final class Course {
        public String name = "";
        public String teacher = "";
        public String location = "";
        /** 形如 "1-2"、"5-7"，由 "05~07小节" 解析而来。 */
        public String sections = "";
        public int sectionFrom;
        public int sectionTo;
        /** "2-17" 或 "1,3,5,7,9,11,13,15"（已去掉结尾的"周"字）。 */
        public String weekRange = "";
        /** 结构化周次，能直接判断"第 N 周上不上"。 */
        public WeekRange weeks = new WeekRange(WeekKind.ALL, 1, 30, new int[0]);
        /** 原始周次文本 "[2-17周] 星期一"，仅供内部自校验，不要直接展示。 */
        String weekText = "";
        /** 1..7，1 = 周一。由表格列位置得出。 */
        public int dayIndex;
        public String dayOfWeek = "";
        /** 该周真实日期，形如 "03-16"。 */
        public String date = "";
        /** 单元格跨行数。仅表示表格占位，不等于节数。 */
        public int span = 1;

        @Override
        public String toString() {
            return dayOfWeek + "(" + date + ") " + sections + " " + name
                    + " / " + teacher + " / " + location + " / [" + weekRange + "周]";
        }
    }

    /** 解析结果。 */
    public static final class Result {
        public final List<Day> days = new ArrayList<>();
        public final List<Course> courses = new ArrayList<>();
        /** 非致命异常：位置推出的星期与文本里的星期不一致等。 */
        public final List<String> warnings = new ArrayList<>();

        public boolean hasDays() {
            return !days.isEmpty();
        }
    }

    private static final Pattern P_DAY = Pattern.compile("^(?:星期|周)([一二三四五六日天])$");
    private static final Pattern P_DATE = Pattern.compile("^\\d{1,2}-\\d{1,2}$");
    private static final Pattern P_SECTION_RANGE = Pattern.compile("(\\d+)\\s*[~\\-－—]\\s*(\\d+)");
    private static final Pattern P_SECTION_ONE = Pattern.compile("(\\d+)");
    private static final Pattern P_BRACKET = Pattern.compile("\\[([^\\]]+)\\]");
    private static final Pattern P_WEEK_DAY = Pattern.compile("星期([一二三四五六日天])");
    private static final Pattern P_TRAILING_ZHOU = Pattern.compile("周$");
    private static final Pattern P_WEEK_RANGE = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");
    private static final Pattern P_WEEK_LIST = Pattern.compile("\\d+(\\s*,\\s*\\d+)*");
    private static final Pattern P_WS = Pattern.compile("\\s+");
    private static final Pattern P_OPEN_PAREN_SPACE = Pattern.compile("\\(\\s+");
    private static final Pattern P_CLOSE_PAREN_SPACE = Pattern.compile("\\s+\\)");

    private static final String TEACHER_PREFIX = "教师：";
    private static final String TOTAL_PREFIX = "总人数";
    private static final String SECTION_SUFFIX = "小节";

    private TimetableParser() {
    }

    /**
     * 解析课表片段。
     *
     * @param html {@code #kbLoading} 的 innerHTML，或整页 HTML 也可以（内部按 table#timetable 查找）
     * @return 解析结果；找不到表格时返回空结果并带一条 warning
     */
    public static Result parse(String html) {
        Result result = new Result();
        if (html == null || html.trim().isEmpty()) {
            result.warnings.add("课表内容为空");
            return result;
        }

        Document doc = Jsoup.parse(html);
        Element table = doc.selectFirst("table#timetable");
        if (table == null) {
            // 兜底：有些页面结构里 id 在别的层级上，按 class 再找一次
            table = doc.selectFirst("table.qz-weeklyTable");
        }
        if (table == null) {
            result.warnings.add("未找到课表表格 table#timetable");
            return result;
        }

        parseDays(table, result);
        parseCourses(table, result);

        if (result.days.size() != 7) {
            result.warnings.add("表头天数应为 7，实际 " + result.days.size());
        }
        return result;
    }

    // ---------------------------------------------------------------- 表头

    private static void parseDays(Element table, Result result) {
        Elements ths = table.select("thead th");
        for (Element th : ths) {
            String name = null;
            String date = null;
            for (Element span : th.select("span")) {
                String t = clean(span.text());
                if (t.isEmpty()) {
                    continue;
                }
                if (name == null && P_DAY.matcher(t).matches()) {
                    name = t;
                } else if (date == null && P_DATE.matcher(t).matches()) {
                    date = t;
                }
            }
            if (name != null) {
                result.days.add(new Day(name, date));
            }
        }
    }

    // ---------------------------------------------------------------- 表体

    private static void parseCourses(Element table, Result result) {
        Elements rows = table.select("tr");
        for (Element tr : rows) {
            // 跳过表头行：表头里的 td 不存在，但保险起见按父节点判断
            Element parent = tr.parent();
            if (parent != null && "thead".equals(parent.tagName())) {
                continue;
            }

            List<Element> cells = new ArrayList<>();
            for (Element child : tr.children()) {
                if ("td".equals(child.tagName())) {
                    cells.add(child);
                }
            }
            if (cells.isEmpty()) {
                continue;
            }

            String rowLabel = clean(cells.get(0).text());
            int col = 0;

            for (Element td : cells) {
                if (hasClass(td, "qz-weeklyTable-label")) {
                    col++;
                    continue;
                }

                int rowspan = parseSpan(td.attr("rowspan"));
                int dayIndex = col; // 第 0 列是节次标签列，所以 dayIndex 从 1 开始对齐 days[dayIndex-1]

                for (Element li : td.select("li.courselists-item")) {
                    Course course = parseCourse(li, rowLabel);
                    if (course == null) {
                        continue;
                    }
                    course.span = rowspan;
                    course.dayIndex = dayIndex;
                    if (dayIndex >= 1 && dayIndex <= result.days.size()) {
                        Day day = result.days.get(dayIndex - 1);
                        course.dayOfWeek = day.name;
                        course.date = day.date == null ? "" : day.date;
                    }

                    // 自校验：文本里的"星期X"应当与列位置一致
                    int textDay = weekdayFromText(course);
                    if (textDay > 0 && dayIndex >= 1 && textDay != dayIndex) {
                        result.warnings.add(course.name + " 列位置=" + dayIndex
                                + " 但文本写星期" + textDay);
                    }

                    result.courses.add(course);
                }
                col += rowspan;
            }
        }
    }

    private static Course parseCourse(Element li, String rowLabel) {
        Element titleEl = li.selectFirst(".qz-hasCourse-title");
        if (titleEl == null) {
            return null;
        }
        String name = clean(titleEl.text());
        if (name.isEmpty()) {
            return null;
        }

        Course course = new Course();
        course.name = name;

        String sectionsText = "";
        String weekText = "";
        String location = "";

        for (Element div : li.select("div.qz-hasCourse-detailitem")) {
            String t = clean(div.text());
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith(TEACHER_PREFIX)) {
                if (course.teacher.isEmpty()) {
                    course.teacher = t.substring(TEACHER_PREFIX.length()).trim();
                }
            } else if (t.endsWith(SECTION_SUFFIX)) {
                if (sectionsText.isEmpty()) {
                    sectionsText = t;
                }
            } else if (t.startsWith("[")) {
                if (weekText.isEmpty()) {
                    weekText = t;
                }
            } else if (t.startsWith(TOTAL_PREFIX)) {
                continue;
            } else if (!t.contains("：") && location.isEmpty()) {
                location = t;
            }
        }

        if (sectionsText.isEmpty()) {
            sectionsText = rowLabel;
        }
        int[] range = parseSections(sectionsText);
        course.sectionFrom = range[0];
        course.sectionTo = range[1];
        course.sections = range[0] > 0 ? (range[0] + "-" + range[1]) : sectionsText;

        course.weekRange = parseWeekRange(weekText);
        course.weeks = parseWeekRangeFull(weekText);
        course.location = dedupPrefix(location);
        course.weekText = weekText; // 供自校验用

        return course;
    }

    /** 文本里的星期数字，供自校验；解析不出返回 0。 */
    private static int weekdayFromText(Course course) {
        if (course.weekText == null || course.weekText.isEmpty()) {
            return 0;
        }
        Matcher m = P_WEEK_DAY.matcher(course.weekText);
        if (m.find()) {
            return cnToNumber(m.group(1));
        }
        return 0;
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 去掉地点字段里重复出现的前缀。覆盖两种真实形态：
     *
     * <pre>
     *   形态 A（整段重复）  '瑞樟11瑞樟11-108[...]'   -> '瑞樟11-108[...]'
     *   形态 B（内嵌重复）  '瑞樟体育场瑞樟[匹克球场]' -> '瑞樟体育场[匹克球场]'
     * </pre>
     *
     * <p>做法：从长到短，找 s 的最长"还会在后面再次出现"的前缀 P，删掉后面那一次 P。
     * 要求删完仍有剩余，避免把 {@code 未知地点} 这类正常短词误伤。
     */
    static String dedupPrefix(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int n = s.length();
        for (int plen = n / 2; plen >= 2; plen--) {
            String p = s.substring(0, plen);
            int k = s.indexOf(p, 1);
            if (k > 0 && n - k - plen > 0) {
                return s.substring(0, k) + s.substring(k + plen);
            }
        }
        return s;
    }

    /** "05~07小节" -> {5, 7}；解析不出返回 {0, 0}。 */
    static int[] parseSections(String text) {
        if (text == null) {
            return new int[]{0, 0};
        }
        Matcher m = P_SECTION_RANGE.matcher(text);
        if (m.find()) {
            return new int[]{toInt(m.group(1)), toInt(m.group(2))};
        }
        m = P_SECTION_ONE.matcher(text);
        if (m.find()) {
            int one = toInt(m.group(1));
            return new int[]{one, one};
        }
        return new int[]{0, 0};
    }

    /** "[2-17周] 星期一" -> "2-17"；"[1,3,5周] ..." -> "1,3,5"。 */
    static String parseWeekRange(String text) {
        return parseWeekRangeFull(text).display();
    }

    /**
     * 把周次文本解析成可判断的结构。
     *
     * <p><b>这里的顺序不能改</b>：必须先判"双周/单周"，再去掉结尾的"周"字。
     * 反例：{@code [1-16双周]} 若先 strip 掉尾字会变成 {@code "1-16双"}，
     * 之后 {@code endsWith("双周")} 永远不成立，这门课就会被当成"每周都上"。
     */
    public static WeekRange parseWeekRangeFull(String text) {
        if (text == null || text.isEmpty()) {
            return new WeekRange(WeekKind.ALL, 1, 30, new int[0]);
        }
        // 有方括号就取括号内容，没有就直接用整串（兼容 "1-16双周" 这种裸写法）
        Matcher m = P_BRACKET.matcher(text);
        String raw = m.find() ? m.group(1).trim() : text.trim();

        // ① 先识别单/双周（此时"周"字还在）
        boolean even = raw.endsWith("双周");
        boolean odd = raw.endsWith("单周");
        String body = (even || odd) ? raw.substring(0, raw.length() - 2) : raw;
        if (!even && !odd) {
            body = P_TRAILING_ZHOU.matcher(body).replaceAll("");
        }
        body = body.trim();

        // ② 区间
        Matcher range = P_WEEK_RANGE.matcher(body);
        if (range.matches()) {
            int a = toInt(range.group(1));
            int b = toInt(range.group(2));
            if (a > 0 && b >= a) {
                WeekKind kind = even ? WeekKind.EVEN : (odd ? WeekKind.ODD : WeekKind.RANGE);
                return new WeekRange(kind, a, b, new int[0]);
            }
        }

        // ③ 枚举
        if (P_WEEK_LIST.matcher(body).matches()) {
            String[] parts = body.split(",");
            int[] weeks = new int[parts.length];
            int n = 0;
            for (String p : parts) {
                int v = toInt(p.trim());
                if (v > 0) {
                    weeks[n++] = v;
                }
            }
            if (n > 0) {
                int[] trimmed = new int[n];
                System.arraycopy(weeks, 0, trimmed, 0, n);
                return new WeekRange(WeekKind.LIST, trimmed[0], trimmed[n - 1], trimmed);
            }
        }

        return new WeekRange(WeekKind.ALL, 1, 30, new int[0]);
    }

    /**
     * 这门课在第 {@code week} 周上不上。{@code weekText} 传原始文本
     * （形如 {@code "[2-17周] 星期一"}），也接受已经去掉方括号的纯周次串。
     */
    public static boolean matchesWeek(String weekText, int week) {
        return parseWeekRangeFull(weekText).contains(week);
    }

    static String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = P_WS.matcher(s).replaceAll(" ").trim();
        t = P_OPEN_PAREN_SPACE.matcher(t).replaceAll("(");
        t = P_CLOSE_PAREN_SPACE.matcher(t).replaceAll(")");
        return t;
    }

    private static int parseSpan(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return 1;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean hasClass(Element el, String cls) {
        return el.hasClass(cls);
    }

    private static int cnToNumber(String cn) {
        switch (cn) {
            case "一": return 1;
            case "二": return 2;
            case "三": return 3;
            case "四": return 4;
            case "五": return 5;
            case "六": return 6;
            case "日":
            case "天": return 7;
            default: return 0;
        }
    }

    /** 不可变空列表，避免调用方拿到 null。 */
    public static List<Course> emptyCourses() {
        return Collections.emptyList();
    }
}

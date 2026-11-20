package com.wuyi.kebiao;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 课表数据源：直接向教务系统要数据，不经过 WebView。
 *
 * <p><b>为什么能绕开 WebView</b>：{@code /jsxsd/framework/mainV_index_loadkb.jsp}
 * 只是一个普通的 GET 接口，返回一段 HTML 表格片段。页面自己切周时也不过是
 * 用 jQuery 的 {@code load()} 请求它再把结果塞进 {@code #kbLoading}。
 * 只要有登录后的 Cookie，App 用 OkHttp 就能拿到完全相同的数据 ——
 * 不需要注入 JS、不需要 MutationObserver、不需要 6/15 秒兜底超时。
 *
 * <p><b>两次请求覆盖整个学期</b>：
 * <ol>
 *   <li>{@code ?zc=1}、{@code ?zc=2} —— 读表头日期反推"第一周周一"，用于把
 *       任意日期换算成周次。第一周的表头里周一可能是空的，所以取两周做多数表决。</li>
 *   <li>{@code ?zc=}（周次留空 = 全部）—— 一次拿到全部课程，每门课都带
 *       星期列位置与周次区间。</li>
 * </ol>
 * 之后任何一天的课都在本地算：{@code week = (today - firstMonday)/7 + 1}，
 * {@code dayIndex = 星期几}，再按周次区间过滤。
 *
 * <p>纯 Java（不含 Android API），可以在 JVM 单元测试里直接构造 Snapshot 验证。
 */
public final class TimetableRepository {

    public static final String BASE = "https://jwxt.wuyiu.edu.cn";

    private static final String LOADKB_PATH = "/jsxsd/framework/mainV_index_loadkb.jsp";

    /**
     * 课表接口的完整 URL。
     *
     * <p>取 Cookie 时必须用这个带路径的地址，不能只给域名：{@code CookieManager.getCookie}
     * 会按 path 匹配，学校把 jsessionid 挂在 /jsxsd 上，只传域名（默认 path=/）就拿不到它。
     */
    public static final String LOADKB_URL = BASE + LOADKB_PATH;

    /** 课表参数：kbjcmsid=99 是"默认模式"，与页面上的 layui 选中项一致。 */
    private static final String PARAMS = "&kbjcmsid=99&xnxq01id=&xswk=false";

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final String[] WEEKDAY_CN = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 日志回调，把过程推给前端实时日志区。 */
    public interface Logger {
        void log(String message);
    }

    private TimetableRepository() {
    }

    // ================================================================ 快照

    /**
     * 学期快照：全部课程 + 第一周周一 + 当前周。
     * 拿到它之后，任何一天的课都可以本地算出来，不需要再联网。
     */
    public static final class Snapshot {
        /** 全部课程（已按"全部"视图解析）。 */
        public final List<TimetableParser.Course> courses;
        /** 服务端表头给出的当前周七天（带日期）。 */
        public final List<TimetableParser.Day> currentWeekDays;
        /** 第一周周一的 00:00 毫秒时间戳。 */
        public final long firstMonday;
        /** 服务端表头对应的周次。 */
        public final int currentWeek;

        Snapshot(List<TimetableParser.Course> courses,
                 List<TimetableParser.Day> currentWeekDays,
                 long firstMonday,
                 int currentWeek) {
            this.courses = courses;
            this.currentWeekDays = currentWeekDays;
            this.firstMonday = firstMonday;
            this.currentWeek = currentWeek;
        }

        /** 某日期属于第几周（1 起）。早于第一周返回 <= 0。 */
        public int weekOf(long dateMillis) {
            long diff = startOfDay(dateMillis) - firstMonday;
            // 注意除以"一周的毫秒数"，不是除以"一天的毫秒数"。
            // 写成 diff / DAY_MS 会得到"第几天"，这是个很容易漏的错。
            return (int) Math.floor(diff / (double) (7L * DAY_MS)) + 1;
        }

        /** 某日期是周几：1 = 周一 … 7 = 周日。 */
        public int dayIndex(long dateMillis) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(dateMillis);
            int dow = c.get(Calendar.DAY_OF_WEEK); // 周日=1, 周一=2
            return dow == Calendar.SUNDAY ? 7 : dow - 1;
        }

        /** 某一天要上的课，按节次排序。 */
        public List<TimetableParser.Course> coursesOn(long dateMillis) {
            int week = weekOf(dateMillis);
            int day = dayIndex(dateMillis);
            List<TimetableParser.Course> out = new ArrayList<>();
            if (week <= 0) {
                return out;
            }
            for (TimetableParser.Course c : courses) {
                if (c.dayIndex == day && c.weeks.contains(week)) {
                    out.add(c);
                }
            }
            sortBySection(out);
            return out;
        }

        public List<TimetableParser.Course> todayCourses() {
            return coursesOn(System.currentTimeMillis());
        }

        public List<TimetableParser.Course> tomorrowCourses() {
            return coursesOn(System.currentTimeMillis() + DAY_MS);
        }

        /** 当前周的七天日期表头，供"本周课表"用。 */
        public List<TimetableParser.Day> currentWeekDays() {
            return currentWeekDays;
        }

        /** 整周按天分组，键是 1..7。 */
        public List<List<TimetableParser.Course>> wholeWeek() {
            List<List<TimetableParser.Course>> out = new ArrayList<>();
            long monday = mondayOf(System.currentTimeMillis());
            for (int i = 0; i < 7; i++) {
                out.add(coursesOn(monday + i * DAY_MS));
            }
            return out;
        }

        /** "第 12 周" 这种中文标签。 */
        public String currentWeekLabel(int week) {
            return "第 " + week + " 周";
        }
    }

    // ================================================================ 网络

    /**
     * 拉取整个学期的快照。
     *
     * @param cookie 登录后从 WebView 的 CookieManager 取到的 jwxt Cookie 串
     * @param log    过程日志（可为 null）
     */
    public static Snapshot fetch(String cookie, Logger log) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();

        if (log != null) {
            log.log("开始直连教务系统");
        }

        // ---- 第一步：校准第一周周一 ----
        List<Long> candidates = new ArrayList<>();
        for (int zc = 1; zc <= 2; zc++) {
            String html = get(client, cookie, String.valueOf(zc));
            TimetableParser.Result r = TimetableParser.parse(html);
            if (!r.hasDays()) {
                continue;
            }
            long today = System.currentTimeMillis();
            for (int i = 0; i < r.days.size(); i++) {
                long d = resolveDate(r.days.get(i).date, today);
                if (d == Long.MIN_VALUE) {
                    continue;
                }
                long monday = mondayOf(d) - (zc - 1) * 7L * DAY_MS;
                candidates.add(monday);
            }
        }
        if (candidates.isEmpty()) {
            throw new IOException("无法确定学期起始周（表头没有可用日期）");
        }
        long firstMonday = majority(candidates);
        if (log != null) {
            log.log("学期第一周周一：" + formatDate(firstMonday));
        }

        // ---- 第二步：拿全部课程 ----
        String allHtml = get(client, cookie, "");
        TimetableParser.Result all = TimetableParser.parse(allHtml);
        if (all.courses.isEmpty()) {
            throw new IOException("课表为空，可能登录已失效");
        }

        // 表头日期 = 当前周，用它反推当前周次
        long today = System.currentTimeMillis();
        int currentWeek = -1;
        for (TimetableParser.Day d : all.days) {
            long ms = resolveDate(d.date, today);
            if (ms == Long.MIN_VALUE) {
                continue;
            }
            int w = (int) Math.floor((startOfDay(ms) - firstMonday) / (double) (7L * DAY_MS)) + 1;
            if (w > 0) {
                currentWeek = w;
                break;
            }
        }
        if (currentWeek <= 0) {
            currentWeek = (int) Math.floor((startOfDay(today) - firstMonday) / (double) (7L * DAY_MS)) + 1;
        }

        if (log != null) {
            log.log("共解析 " + all.courses.size() + " 门课程，当前第 " + currentWeek + " 周");
            for (String w : all.warnings) {
                log.log("注意：" + w);
            }
        }
        return new Snapshot(all.courses, all.days, firstMonday, currentWeek);
    }

    private static String get(OkHttpClient client, String cookie, String zc) throws IOException {
        String url = BASE + LOADKB_PATH + "?zc=" + zc + PARAMS;
        Request.Builder b = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; 23127PN0CC) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Referer", BASE + "/jsxsd/framework/xsMainV.htmlx")
                .header("X-Requested-With", "XMLHttpRequest");
        if (cookie != null && !cookie.isEmpty()) {
            b.header("Cookie", cookie);
        }
        try (Response resp = client.newCall(b.build()).execute()) {
            ResponseBody body = resp.body();
            String text = body == null ? "" : body.string();
            if (!resp.isSuccessful()) {
                throw new IOException("教务系统返回 HTTP " + resp.code());
            }
            // 被踢回登录时服务端会给一个小页面，里面带 sso.wuyiu.edu.cn
            if (text.contains("sso.wuyiu.edu.cn") && text.length() < 2000) {
                throw new IOException("登录已失效，需要重新登录（返回 " + text.length() + " 字符）");
            }
            return text;
        }
    }

    // ================================================================ 日期

    /** "11-20" -> 毫秒时间戳；年份取与 {@code today} 最接近的那一年。解析不出返回 Long.MIN_VALUE。 */
    static long resolveDate(String md, long today) {
        if (md == null) {
            return Long.MIN_VALUE;
        }
        String s = md.trim();
        int dash = s.indexOf('-');
        if (dash <= 0 || dash >= s.length() - 1) {
            return Long.MIN_VALUE;
        }
        int month;
        int day;
        try {
            month = Integer.parseInt(s.substring(0, dash).trim());
            day = Integer.parseInt(s.substring(dash + 1).trim());
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return Long.MIN_VALUE;
        }
        Calendar ref = Calendar.getInstance();
        ref.setTimeInMillis(today);
        int year = ref.get(Calendar.YEAR);

        long best = Long.MIN_VALUE;
        long bestDiff = Long.MAX_VALUE;
        for (int y = year - 1; y <= year + 1; y++) {
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(y, month - 1, day, 0, 0, 0);
            // 防止 2 月 30 日之类被自动进位
            if (c.get(Calendar.MONTH) != month - 1 || c.get(Calendar.DAY_OF_MONTH) != day) {
                continue;
            }
            long ms = c.getTimeInMillis();
            long diff = Math.abs(ms - today);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = ms;
            }
        }
        return best;
    }

    static long startOfDay(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 该日期所在周的周一 00:00。 */
    static long mondayOf(long ms) {
        long day = startOfDay(ms);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(day);
        int dow = c.get(Calendar.DAY_OF_WEEK); // 周日=1, 周一=2
        int offset = (dow == Calendar.SUNDAY) ? 6 : dow - 2;
        return day - offset * DAY_MS;
    }

    static String formatDate(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.CHINA, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static String weekdayCn(int dayIndex) {
        return dayIndex >= 1 && dayIndex <= 7 ? WEEKDAY_CN[dayIndex] : "";
    }

    /** 取出现次数最多的值。 */
    static long majority(List<Long> values) {
        long best = values.get(0);
        int bestCount = 0;
        for (long v : values) {
            int n = 0;
            for (long u : values) {
                if (u == v) {
                    n++;
                }
            }
            if (n > bestCount) {
                bestCount = n;
                best = v;
            }
        }
        return best;
    }

    /** 按起始节次排序，同一节的按名称排。 */
    static void sortBySection(List<TimetableParser.Course> list) {
        java.util.Collections.sort(list, (a, b) -> {
            if (a.sectionFrom != b.sectionFrom) {
                return Integer.compare(a.sectionFrom, b.sectionFrom);
            }
            return a.name.compareTo(b.name);
        });
    }
}

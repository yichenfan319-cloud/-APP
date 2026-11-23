package com.wuyi.kebiao;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 把诊断信息写成文本文件，供「问题反馈」当附件发出去。
 *
 * 内容分两段：一段是环境快照（版本、机型、主题、账号、课表状态），
 * 一段是 {@link DiagLog} 里的滚动日志。用户不用理解内容，只要愿意发出来就行。
 */
public final class LogExporter {

    private LogExporter() {
    }

    /** 写到 cache/logs/ 下并返回文件；调用方负责用 FileProvider 分享。 */
    public static File export(Context ctx) throws IOException {
        File dir = new File(ctx.getCacheDir(), "logs");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建日志目录");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date());
        File out = new File(dir, "kipkip-log-" + stamp + ".txt");
        try (OutputStreamWriter writer =
                     new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            writer.write(build(ctx));
        }
        return out;
    }

    private static String build(Context ctx) {
        StringBuilder sb = new StringBuilder();

        sb.append("KipKip 诊断日志\n");
        sb.append("导出时间: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()))
                .append('\n');
        sb.append("App 版本: ").append(VersionChecker.currentVersionName(ctx)).append('\n');
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");

        boolean night = (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        sb.append("主题: ").append(night ? "深色" : "浅色").append('\n');

        // 账号只留首尾各两位，日志要能发出去，不能顺手把学号泄了
        SharedPreferences prefs = Prefs.get(ctx);
        String user = prefs.getString(Prefs.KEY_USERNAME, "");
        sb.append("账号: ").append(mask(user)).append('\n');
        sb.append("账号已验证: ").append(prefs.getBoolean(Prefs.KEY_VERIFIED, false)).append('\n');

        appendTimetable(sb, ScheduleStore.get().getSnapshot());

        sb.append("\n---- 日志 ----\n");
        String log = DiagLog.dump();
        sb.append(log.isEmpty() ? "(本次运行还没有日志)\n" : log);
        return sb.toString();
    }

    private static void appendTimetable(StringBuilder sb, TimetableRepository.Snapshot snap) {
        if (snap == null) {
            sb.append("课表: (尚未加载)\n");
            return;
        }
        sb.append("当前周次: 第 ").append(snap.currentWeek).append(" 周\n");
        sb.append("第一周周一: ").append(TimetableRepository.formatDate(snap.firstMonday)).append('\n');

        List<TimetableParser.Course> week = snap.currentWeekCourses;
        sb.append("本周课表: ")
                .append(week == null ? "(取周视图失败，走了兜底)" : week.size() + " 门")
                .append('\n');

        List<TimetableParser.Course> all = snap.courses;
        sb.append("学期总表: ")
                .append(all == null ? "(未请求，正常)" : all.size() + " 门（兜底用）")
                .append('\n');

        if (week != null) {
            sb.append("本周课程清单:\n");
            for (TimetableParser.Course c : week) {
                sb.append("  - 周").append(c.dayIndex)
                        .append(' ').append(c.sections)
                        .append(' ').append(c.name)
                        .append(" / ").append(c.weekRange)
                        .append(" / ").append(c.location)
                        .append(" / ").append(c.teacher)
                        .append('\n');
            }
        }
    }

    private static String mask(String user) {
        if (user == null || user.isEmpty()) {
            return "(未设置)";
        }
        if (user.length() <= 4) {
            return "****";
        }
        return user.substring(0, 2) + "****" + user.substring(user.length() - 2);
    }
}

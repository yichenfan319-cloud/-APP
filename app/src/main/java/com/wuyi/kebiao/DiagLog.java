package com.wuyi.kebiao;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 滚动日志缓冲，只保留最近若干行，供「问题反馈」导出。
 *
 * 为什么不直接读 logcat：普通应用没有 READ_LOGS 权限，release 包读不到系统日志。
 * 之前排查「周五的课显示成周六」时，用户在手机上什么也提供不了，
 * 只能靠猜；所以这里自己留一份，出问题时让用户一键导出。
 */
public final class DiagLog {

    /** 保留行数。一次完整取课表大约产生十几行，800 行够回看很多次刷新。 */
    private static final int MAX_LINES = 800;

    private static final Deque<String> LINES = new ArrayDeque<>();

    private DiagLog() {
    }

    /** 追加一条日志；换行的多行文本会被拆成多条，方便逐行带时间戳。 */
    public static synchronized void add(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        for (String line : message.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            LINES.addLast(stamp + "  " + line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
    }

    /** 导出全部内容（带时间戳，按时间正序）。 */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized int size() {
        return LINES.size();
    }
}

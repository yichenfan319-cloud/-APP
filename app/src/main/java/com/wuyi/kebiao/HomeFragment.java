package com.wuyi.kebiao;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;

public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";
    private static final String PREFS_NAME = "WuyiKebiaoPrefs";
    private static final String KEY_COOKIES = "saved_cookies";
    private static final String KEY_USERNAME = "saved_username";
    private static final String KEY_PASSWORD = "saved_password";

    // 重试机制常量
    private static final int MAX_RETRY = 1;      // 最大重试次数（只重试1次）
    private static final int RETRY_DELAY_MS = 2000;  // 重试延迟2秒

    private WebView webView;          // 前台展示WebView
    private WebView crawlerView;      // 后台爬虫WebView
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    // 登录状态
    private String pendingUser = null;
    private String pendingPass = null;
    private boolean isCrawling = false;
    private boolean hasInjectedLogin = false;
    private Runnable timeoutRunnable = null;

    // 重试计数器
    private int retryCount = 0;

    // 是否正在等待"切换周次"后的新课表（用于区分首次加载与切周次）

    // 后台桥接对象。保留引用是因为新的 HTTP 取数线程拿到数据后，要复用
    // onCourses() 里那套（保存凭据 -> 推前端 -> 记日志）的统一收尾逻辑，
    // 否则两条数据通路会各自维护一份，迟早走岔。
    private final BackBridge backBridge = new BackBridge();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        webView = view.findViewById(R.id.webView);

        crawlerView = new WebView(requireContext());
        crawlerView.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
        crawlerView.setVisibility(View.GONE);
        ((ViewGroup) view).addView(crawlerView);

        setupWebView(webView);
        webView.loadUrl("file:///android_asset/www/index.html");

        setupCrawlerView();

        autoLoginIfCredentialsExist();

        return view;
    }

    /**
     * 把一条进度消息推到前端控制面板的实时日志区。
     *
     * 必须在主线程执行 evaluateJavascript，而本方法会被 WebView 的
     * 回调线程调用，所以统一 post 到 mainHandler。
     *
     * 换行会被保留（转成 \n 转义）：日志区是可滚动控制台，
     * 多行内容（比如启动彩蛋的猫猫头）需要按原样换行显示。
     */
    private void appendLog(String msg) {
        if (msg == null) return;
        final String safe = msg.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.receiveFromAndroid('log', '" + safe + "');", null);
            }
        });
    }

    private void setupWebView(WebView wv) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        wv.setWebChromeClient(new WebChromeClient());
        wv.addJavascriptInterface(new FrontBridge(), "Android");
    }

    private void setupCrawlerView() {
        WebSettings cs = crawlerView.getSettings();
        cs.setJavaScriptEnabled(true);
        cs.setDomStorageEnabled(true);
        cs.setCacheMode(WebSettings.LOAD_NO_CACHE);
        crawlerView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                Log.d(TAG, "WebView Console: " + consoleMessage.message());
                return true;
            }
        });
        crawlerView.setWebViewClient(new CrawlerClient());
        crawlerView.addJavascriptInterface(backBridge, "AndroidBack");
    }

    private void autoLoginIfCredentialsExist() {
        String savedUser = prefs.getString(KEY_USERNAME, null);
        String savedPass = prefs.getString(KEY_PASSWORD, null);
        if (savedUser != null && savedPass != null && !savedUser.isEmpty() && !savedPass.isEmpty()) {
            webView.postDelayed(() -> {
                pendingUser = savedUser;
                pendingPass = savedPass;
                // 让登录页收起表单、只留状态行 —— 自动登录期间不该挂着一个
                // 禁用的"登录教务系统"按钮，日志区已经说明了进度
                webView.evaluateJavascript(
                        "window.receiveFromAndroid('loginState', 'busy');", null);
                startCrawl();
            }, 1000);
        }
    }

    // ==================== 前端调用接口 ====================
    public class FrontBridge {
        @JavascriptInterface
        public void login(String username, String password) {
            appendLog("收到登录请求，账号 " + username);
            pendingUser = username;
            pendingPass = password;
            mainHandler.post(() -> startCrawl());
        }

        @JavascriptInterface
        public void refresh() {
            pendingUser = prefs.getString(KEY_USERNAME, null);
            pendingPass = prefs.getString(KEY_PASSWORD, null);
            if (pendingUser == null) {
                notifyFrontError("请先输入账号密码登录");
                return;
            }
            appendLog("刷新课表");
            mainHandler.post(() -> startCrawl());
        }

        @JavascriptInterface
        public void relogin() {
            appendLog("退出登录，清除 Cookie 与账号");
            mainHandler.post(() -> {
                clearAllData();
                webView.loadUrl("file:///android_asset/www/index.html");
            });
        }

        @JavascriptInterface
        public void clearSavedCredentials() {
            prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply();
            Log.d(TAG, "已清除保存的账号密码");
        }

        @JavascriptInterface
        public void getSavedCredentials() {
            String username = prefs.getString(KEY_USERNAME, null);
            String password = prefs.getString(KEY_PASSWORD, null);
            if (username != null && !username.isEmpty()) {
                String json = "{\"username\":\"" + username + "\",\"password\":\"" + (password != null ? password : "") + "\"}";
                mainHandler.post(() -> webView.evaluateJavascript("window.receiveFromAndroid('saved_credentials', '" + json + "');", null));
            } else {
                mainHandler.post(() -> webView.evaluateJavascript("window.receiveFromAndroid('saved_credentials', 'null');", null));
            }
        }

        /**
         * 打开设置页（原底部导航「我的」）。
         * 首页顶栏右上角的齿轮图标调用这里。
         */
        @JavascriptInterface
        public void openSettings() {
            if (isAdded()) {
                startActivity(new Intent(requireContext(), SettingsActivity.class));
            }
        }

        /**
         * 切换课表周次。weekValue 为空字符串表示「全部」。
         * 首页的周次选择控件调用这里。
         */
        @JavascriptInterface
        public void selectWeek(String weekValue) {
            // 新架构下"切周次"完全在前端本地完成（一次拿全学期），
            // 这里保留 JS 接口只为兼容旧页面，不再触发任何网络请求。
            // 保留而不删除，是因为旧版 index.html 缓存里可能仍会调用它。
            Log.d(TAG, "selectWeek 被调用（已改为本地过滤，忽略）: " + weekValue);
        }

        /**
         * 弹出原生周次选择面板。
         * 周次列表由前端从教务系统页面拿到后回传，不在客户端硬编码。
         */
        @JavascriptInterface
        public void requestWeekPicker(final String weeksJson, final String currentLabel) {
            mainHandler.post(() -> {
                if (!isAdded()) return;
                WeekPickerDialog.show(requireContext(), weeksJson, currentLabel,
                        (value, label) -> mainHandler.post(() -> {
                            if (webView == null) return;
                            // 回调前端，由前端统一走"请求切换 -> 等解析"的流程
                            webView.evaluateJavascript(
                                    "window.onWeekChosen(" + quoteJs(value) + "," + quoteJs(label) + ");",
                                    null);
                        }));
            });
        }

        @JavascriptInterface
        public void openWeekView(String coursesJson) {
            // 首页现已内嵌周视图（本周课表按钮），此入口保留兼容旧页面。
            Intent intent = new Intent(requireContext(), WeekViewActivity.class);
            intent.putExtra("courses_json", coursesJson);
            startActivity(intent);
        }

        @JavascriptInterface
        public void forceRetry() {
            appendLog("手动强制重试");
            retryCount = 0;
            startCrawl();
        }
    }

    // ==================== 爬虫主流程 ====================
    private void startCrawl() {
        if (isCrawling) return;
        isCrawling = true;
        hasInjectedLogin = false;

        // 注意：原先这里会去操作 #login-page / #timetable-page / #tip / .btn .txt，
        // 但新首页已把这些元素换成 login-page / main-page / 日志区，
        // 那段 JS 属于死代码，已移除。界面状态由前端自己维护。

        // 设置超时
        if (timeoutRunnable != null) mainHandler.removeCallbacks(timeoutRunnable);
        timeoutRunnable = () -> {
            if (isCrawling) {
                Log.e(TAG, "爬取超时");
                isCrawling = false;
                hasInjectedLogin = false;

                // 超时重试
                if (retryCount < MAX_RETRY) {
                    retryCount++;
                    appendLog("连接超时，第 " + retryCount + " 次重试…");
                    notifyFrontError("连接超时，正在重试...");
                    mainHandler.postDelayed(() -> retryCrawl(), RETRY_DELAY_MS);
                } else {
                    appendLog("连接超时，请检查网络或账号密码");
                    notifyFrontError("连接超时，请检查网络或账号密码");
                }
            }
        };
        mainHandler.postDelayed(timeoutRunnable, 25000);

        restoreCookies();
        appendLog("开始连接教务系统…");
        crawlerView.stopLoading();
        crawlerView.clearHistory();
        crawlerView.loadUrl("https://jwxt.wuyiu.edu.cn/jsxsd/framework/xsMainV_new.htmlx?t1=1");
    }

    /**
     * 重试爬取（保留Cookie，不重置计数）
     */
    private void retryCrawl() {
        appendLog("执行重试，当前重试次数: " + retryCount);
        isCrawling = true;
        hasInjectedLogin = false;
        restoreCookies();
        crawlerView.loadUrl("https://jwxt.wuyiu.edu.cn/jsxsd/framework/xsMainV_new.htmlx?t1=1");
    }

    // ==================== 接口取数（替代注入脚本） ====================

    /**
     * 用登录后的 Cookie 直接请求课表接口。
     *
     * <p>为什么换掉原来的注入方案：
     * <ul>
     *   <li>注入方案要赌"页面 DOM 什么时候变成我们要的样子"，因此不得不靠
     *       MutationObserver + 6 秒 + 15 秒两级兜底；任何一次时序错位都会
     *       把上一周的数据当成这一周渲染出来，而界面看不出异常。</li>
     *   <li>接口返回的是同一张表的 HTML 原文，解析结果只取决于内容本身。</li>
     *   <li>切周次不再需要发起新的页面请求 —— 一次拿全学期，本地过滤。</li>
     * </ul>
     */
    /**
     * 收集登录所需的全部 Cookie。
     *
     * <p>必须同时取两个域：单点登录会在 sso 域下发 SESSION / SOURCEID_TGC，
     * 而 jsessionid 落在 jwxt 域。只取 jwxt 域会拿到"看起来有 Cookie、
     * 服务器却认为未登录"的半套凭据 —— 实测就是这么失败的。
     *
     * <p>按 cookie 名去重：两域可能有同名 key，重复发送会让服务端取到错的那个。
     */
    private String collectCookies() {
        CookieManager cm = CookieManager.getInstance();
        String[] sources = {
                // 用带路径的完整地址，否则拿不到挂在 /jsxsd 上的 jsessionid
                cm.getCookie(TimetableRepository.LOADKB_URL),
                cm.getCookie("https://sso.wuyiu.edu.cn/login")
        };
        java.util.LinkedHashMap<String, String> byName = new java.util.LinkedHashMap<>();
        for (String raw : sources) {
            if (raw == null || raw.isEmpty()) continue;
            for (String part : raw.split(";")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                String name = eq > 0 ? p.substring(0, eq).trim() : p;
                if (!byName.containsKey(name)) {
                    byName.put(name, p);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String v : byName.values()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(v);
        }
        if (byName.isEmpty()) {
            appendLog("没有可用 Cookie");
        } else {
            appendLog("已取得 " + byName.size() + " 个 Cookie（" + byName.keySet() + "）");
        }
        return sb.toString();
    }

    private void fetchFromServer() {
        if (!isCrawling) return;

        final String cookie = collectCookies();
        if (cookie.isEmpty()) {
            appendLog("还没有拿到登录 Cookie，稍后重试");
            return;
        }

        appendLog("通过接口直接获取课表…");
        new Thread(() -> {
            try {
                TimetableRepository.Snapshot snap =
                        TimetableRepository.fetch(cookie, this::appendLog);
                final String json = buildTimetableJson(snap);
                mainHandler.post(() -> backBridge.onCourses(json));
            } catch (Exception e) {
                Log.e(TAG, "接口获取课表失败", e);
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                appendLog("接口获取失败：" + msg);
                mainHandler.post(() -> {
                    if (!isCrawling) return;
                    isCrawling = false;
                    hasInjectedLogin = false;
                    if (retryCount < MAX_RETRY) {
                        retryCount++;
                        appendLog("课表获取失败，第 " + retryCount + " 次重试…");
                        notifyFrontError("课表获取失败，正在重试...");
                        mainHandler.postDelayed(() -> retryCrawl(), RETRY_DELAY_MS);
                    } else {
                        appendLog("课表获取失败，请检查网络或重新登录");
                        notifyFrontError("课表获取失败，请检查网络或重新登录");
                    }
                });
            }
        }, "timetable-http").start();
    }

    /**
     * 把一次抓取的快照序列化成前端载荷。
     *
     * <p>载荷里给的是<b>整个学期</b>的课程，而不是某一周。前端据此按日期
     * 自行过滤"今天/明天/本周"以及用户点选的周次 —— 这样切周次、跨周
     * （周日点"明天"）都只是纯本地计算，不再有异步等待与超时。
     *
     * <p>每门课带上结构化的周次（kind/from/to/list），是因为前端要判断
     * "第 12 周有没有这门课"，用 display() 出来的字符串再去反解很容易出错，
     * 双周/单周那种形态尤其容易被当成"每周都上"。
     */
    private static String buildTimetableJson(TimetableRepository.Snapshot snap) {
        try {
            JSONObject root = new JSONObject();
            root.put("mode", "offline");
            root.put("currentWeek", snap.currentWeek);
            root.put("currentWeekLabel", snap.currentWeekLabel(snap.currentWeek));
            root.put("firstMonday", TimetableRepository.formatDate(snap.firstMonday));

            // 可选周次：1..20（服务端 zc 的取值范围，探测得知 21 及以上为空）
            JSONArray weeks = new JSONArray();
            for (int w = 1; w <= 20; w++) {
                JSONObject o = new JSONObject();
                o.put("value", String.valueOf(w));
                o.put("label", snap.currentWeekLabel(w));
                weeks.put(o);
            }
            root.put("weeks", weeks);

            // 当前周的表头（周一..周日 + 服务端给的日期），用于周视图标题
            JSONArray days = new JSONArray();
            for (TimetableParser.Day d : snap.currentWeekDays) {
                JSONObject o = new JSONObject();
                o.put("name", d.name);
                o.put("date", d.date == null ? "" : d.date);
                days.put(o);
            }
            root.put("days", days);

            JSONArray arr = new JSONArray();
            for (TimetableParser.Course c : snap.courses) {
                JSONObject o = new JSONObject();
                o.put("name", c.name);
                o.put("teacher", c.teacher);
                o.put("location", c.location);
                o.put("sections", c.sections);
                o.put("sectionFrom", c.sectionFrom);
                o.put("sectionTo", c.sectionTo);
                o.put("weekRange", c.weekRange);
                o.put("dayIndex", c.dayIndex);
                o.put("dayOfWeek", c.dayOfWeek);
                o.put("date", c.date == null ? "" : c.date);

                TimetableParser.WeekRange wr = c.weeks;
                o.put("weekKind", wr == null ? "ALL" : wr.kind.name());
                o.put("weekFrom", wr == null ? 1 : wr.from);
                o.put("weekTo", wr == null ? 30 : wr.to);
                JSONArray list = new JSONArray();
                if (wr != null && wr.weeks != null) {
                    for (int w : wr.weeks) list.put(w);
                }
                o.put("weekList", list);

                arr.put(o);
            }
            root.put("courses", arr);
            return root.toString();
        } catch (Exception e) {
            Log.e(TAG, "序列化课表失败", e);
            return "{\"courses\":[]}";
        }
    }

    // ==================== WebView 客户端 ====================
    private class CrawlerClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Log.d(TAG, "后台页面完成: " + url);
            if (!isCrawling) return;

            if (isLoginPage(url)) {
                if (hasInjectedLogin) {
                    Log.d(TAG, "已在登录页处理过，跳过");
                    return;
                }
                appendLog("检测到统一认证登录页，准备自动填入账号");
                hasInjectedLogin = true;
                view.postDelayed(() -> injectAutoLogin(view), 1000);
            } else if (url.contains("xsMainV_new.htmlx") || url.contains("xsMainV.htmlx")) {
                // 登录已成功，Cookie 已经写进 CookieManager。
                // 从这里开始不再往页面里注入解析脚本，而是直接持有 Cookie
                // 去请求课表接口 —— 服务端返回的就是那张表格的 HTML 原文，
                // 解析它的确定性和"页面里 DOM 变成什么样"完全无关。
                appendLog("已进入教务系统，改用接口直接获取课表");
                view.postDelayed(() -> fetchFromServer(), 300);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Log.d(TAG, "shouldOverrideUrlLoading: " + url);
            return false;
        }
    }

    private boolean isLoginPage(String url) {
        return url.contains("sso.wuyiu.edu.cn") &&
                (url.contains("/linkid/") || url.contains("/login") || url.contains("统一认证"));
    }

    // ==================== 自动登录 JS 注入 ====================
    private void injectAutoLogin(WebView view) {
        if (pendingUser == null || pendingPass == null) return;
        String safeUser = pendingUser.replace("'", "\\'");
        String safePass = pendingPass.replace("'", "\\'");
        String js =
                "(function() {" +
                        "    var username = '" + safeUser + "';" +
                        "    var password = '" + safePass + "';" +
                        "    var maxWait = 10000;" +
                        "    var startTime = Date.now();" +
                        "    var log = function(msg) { AndroidBack.onLog(msg); };" +
                        "    var error = function(msg) { AndroidBack.onError(msg); };" +
                        "    var fillInput = function(el, val) {" +
                        "        el.value = val;" +
                        "        el.dispatchEvent(new Event('input', { bubbles: true }));" +
                        "        el.dispatchEvent(new Event('change', { bubbles: true }));" +
                        "        if (el._valueTracker) el._valueTracker.setValue(val);" +
                        "    };" +
                        "    var tryClickLogin = function() {" +
                        "        var btn = document.getElementById('submitBtn');" +
                        "        if (!btn) {" +
                        "            error('未找到登录按钮 submitBtn');" +
                        "            return false;" +
                        "        }" +
                        "        log('找到登录按钮，当前 disabled: ' + btn.disabled);" +
                        "        btn.disabled = false;" +
                        "        btn.classList.remove('disabled');" +
                        "        log('已移除禁用属性');" +
                        "        btn.click();" +
                        "        log('已点击登录按钮');" +
                        "        return true;" +
                        "    };" +
                        "    var loop = function() {" +
                        "        var usernameInput = document.getElementById('nameInput');" +
                        "        var passwordInput = document.querySelector('input[type=\"password\"]');" +
                        "        if (usernameInput && passwordInput) {" +
                        "            log('找到输入框，开始填充');" +
                        "            fillInput(usernameInput, username);" +
                        "            fillInput(passwordInput, password);" +
                        "            log('账号密码已填充');" +
                        "            setTimeout(function() {" +
                        "                if (!tryClickLogin()) {" +
                        "                    var form = document.getElementById('normalLoginForm');" +
                        "                    if (form) {" +
                        "                        log('尝试触发表单 submit 事件');" +
                        "                        var ev = new Event('submit', { bubbles: true, cancelable: true });" +
                        "                        form.dispatchEvent(ev);" +
                        "                        if (!ev.defaultPrevented) form.submit();" +
                        "                    }" +
                        "                }" +
                        "            }, 500);" +
                        "        } else {" +
                        "            var elapsed = Date.now() - startTime;" +
                        "            if (elapsed < maxWait) {" +
                        "                log('等待输入框出现... 已过 ' + elapsed + 'ms');" +
                        "                setTimeout(loop, 500);" +
                        "            } else {" +
                        "                error('超时：未找到用户名或密码输入框');" +
                        "            }" +
                        "        }" +
                        "    };" +
                        "    loop();" +
                        "})();";
        view.evaluateJavascript(js, null);
    }

    // ==================== Java 与 JS 桥接 ====================
    public class BackBridge {
        @JavascriptInterface
        public void onCourses(String json) {
            Log.d(TAG, "后台提取成功，JSON长度: " + json.length());

            // 解析结果结构：
            //   {"courses":[...], "currentWeek":"第七周", "weeks":[{"value":"7","label":"第七周"}...]}
            // currentWeek 与可选周次均来自教务系统页面，不自行推算。
            boolean hasValidData = false;
            int courseCount = 0;
            String currentWeek = "";
            try {
                JSONObject root = new JSONObject(json);
                JSONArray arr = root.optJSONArray("courses");
                courseCount = arr == null ? 0 : arr.length();
                currentWeek = root.optString("currentWeek", "");
                hasValidData = courseCount > 0;
            } catch (Exception e) {
                hasValidData = false;
            }

            final boolean isValid = hasValidData;
            appendLog("解析得到 " + courseCount + " 门课程"
                    + (currentWeek.isEmpty() ? "" : "，当前" + currentWeek));

            // 没有有效数据才重试
            if (!isValid && retryCount < MAX_RETRY) {
                retryCount++;
                appendLog("课表为空，第 " + retryCount + " 次重试…");
                notifyFrontError("获取课表为空，正在重试...");
                isCrawling = false;
                hasInjectedLogin = false;
                mainHandler.postDelayed(() -> retryCrawl(), RETRY_DELAY_MS);
                return;
            }

            // 正常处理
            if (timeoutRunnable != null) mainHandler.removeCallbacks(timeoutRunnable);
            isCrawling = false;
            hasInjectedLogin = false;

            saveCookies();
            if (pendingUser != null && pendingPass != null) {
                prefs.edit().putString(KEY_USERNAME, pendingUser)
                        .putString(KEY_PASSWORD, pendingPass).apply();
                Log.d(TAG, "已保存账号密码");
            }

            mainHandler.post(() -> {
                String escapedJson = json.replace("'", "\\'");
                webView.evaluateJavascript("window.receiveFromAndroid('courses', '" + escapedJson + "');", null);
                // 注意：「拿到数据」不等于「拿到有效课表」，两种情况要分开报
                if (isValid) {
                    appendLog("课表已更新");
                } else {
                    appendLog("未获取到有效课表，请重试");
                }
            });
        }

        @JavascriptInterface
        public void onError(String msg) {
            Log.e(TAG, "后台错误: " + msg);
            appendLog("解析失败：" + msg);

            // 错误时重试
            if (retryCount < MAX_RETRY) {
                retryCount++;
                Log.w(TAG, "解析错误，第 " + retryCount + " 次重试...");
                notifyFrontError("获取失败，正在重试...");
                isCrawling = false;
                hasInjectedLogin = false;
                mainHandler.postDelayed(() -> retryCrawl(), RETRY_DELAY_MS);
                return;
            }

            // 重试失败
            if (timeoutRunnable != null) mainHandler.removeCallbacks(timeoutRunnable);
            isCrawling = false;
            hasInjectedLogin = false;

            mainHandler.post(() -> {
                String escapedMsg = msg.replace("'", "\\'");
                webView.evaluateJavascript("window.receiveFromAndroid('error', '" + escapedMsg + "');", null);
                webView.evaluateJavascript("window.receiveFromAndroid('status', '登录失败: " + escapedMsg + "');", null);
            });
        }

        @JavascriptInterface
        public void onLog(String msg) {
            Log.d(TAG, "后台JS: " + msg);
        }
    }

    // ==================== 辅助方法 ====================
    /**
     * 把任意字符串安全地嵌进 JS 表达式。
     *
     * 用 JSONObject.quote 而不是手写 replace("'", "\\'"):
     * 后者只处理单引号，一旦内容里出现反斜杠、换行等字符就会破坏 JS 字符串字面量。
     * JSON 的字符串转义规则是 JS 字符串转义的子集，因此可以安全复用。
     */
    private static String quoteJs(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    /*
     * 启动彩蛋（猫猫头）已移到前端 index.html 里的 CAT 常量。
     *
     * 原因：在 Java 里 appendLog() 会在 onCreateView 阶段执行，
     * 而随后 showMainPage() -> clearLogs() 会把它冲掉（日志区实测为空）。
     * 放在前端由 receiveFromAndroid('courses') 触发，时序才是对的。
     *
     * 这里保留原实现作为参考，不要在 Java 侧再调用它。
     */
    private static String catArt() {
        String[] lines = {
                "  /\\_/\\   ",
                " ( o.o )  ",
                "  > ^ <   ",
                " /|   |\\  ",
                "(_|   |_) "
        };
        return String.join("\n", lines);
    }

    /**
     * 提示前端出错。
     *
     * 注意：新首页没有 #tip 元素，这里只通过日志通道回传。
     *
     * 另外：只有"终态失败"才让登录页恢复表单，重试过程中的中间提示
     * （如"正在重试…"）不能让表单闪回来 —— 那正是本次要消除的突兀感。
     */
    private void notifyFrontError(String msg) {
        appendLog(msg);
        final boolean transientRetry = msg != null && msg.contains("重试");
        final boolean terminal = !transientRetry
                && msg != null && (msg.contains("失败") || msg.contains("超时"));
        if (terminal) {
            mainHandler.post(() -> {
                if (webView != null) {
                    webView.evaluateJavascript(
                            "window.receiveFromAndroid('loginState', 'form');", null);
                }
            });
        }
    }

    private void saveCookies() {
        CookieManager cm = CookieManager.getInstance();
        String jwxt = cm.getCookie("https://jwxt.wuyiu.edu.cn");
        String sso = cm.getCookie("https://sso.wuyiu.edu.cn");
        if (jwxt != null || sso != null) {
            String all = (jwxt != null ? jwxt : "") + ";;" + (sso != null ? sso : "");
            prefs.edit().putString(KEY_COOKIES, all).apply();
        }
    }

    private void restoreCookies() {
        String saved = prefs.getString(KEY_COOKIES, null);
        if (saved == null || saved.isEmpty()) return;

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        String[] parts = saved.split(";;");
        if (parts.length >= 1 && parts[0] != null && !parts[0].isEmpty()) {
            for (String c : parts[0].split(";")) {
                String t = c.trim();
                if (!t.isEmpty()) cm.setCookie("https://jwxt.wuyiu.edu.cn", t);
            }
        }
        if (parts.length >= 2 && parts[1] != null && !parts[1].isEmpty()) {
            for (String c : parts[1].split(";")) {
                String t = c.trim();
                if (!t.isEmpty()) cm.setCookie("https://sso.wuyiu.edu.cn", t);
            }
        }
        cm.flush();
    }

    private void clearAllData() {
        prefs.edit().remove(KEY_COOKIES).remove(KEY_USERNAME).remove(KEY_PASSWORD).apply();
        CookieManager.getInstance().removeAllCookies(null);
        pendingUser = null;
        pendingPass = null;
        retryCount = 0;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (crawlerView != null) {
            crawlerView.destroy();
        }
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
        }
    }
}

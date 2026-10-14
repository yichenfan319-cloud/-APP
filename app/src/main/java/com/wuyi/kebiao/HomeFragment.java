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
    private boolean changeWeekPending = false;

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
        crawlerView.addJavascriptInterface(new BackBridge(), "AndroidBack");
    }

    private void autoLoginIfCredentialsExist() {
        String savedUser = prefs.getString(KEY_USERNAME, null);
        String savedPass = prefs.getString(KEY_PASSWORD, null);
        if (savedUser != null && savedPass != null && !savedUser.isEmpty() && !savedPass.isEmpty()) {
            webView.postDelayed(() -> {
                pendingUser = savedUser;
                pendingPass = savedPass;
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
            final String v = (weekValue == null) ? "" : weekValue.trim();
            mainHandler.post(() -> changeWeek(v));
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
                appendLog("已进入教务系统主页，准备提取课表");
                view.postDelayed(() -> injectParser(view), 1500);
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

    // ==================== 课表解析 JS 注入 ====================
    private void injectParser(WebView view) {
        // 设置解析超时（10秒）
        mainHandler.postDelayed(() -> {
            if (isCrawling) {
                Log.e(TAG, "解析超时");
                if (retryCount < MAX_RETRY) {
                    retryCount++;
                    Log.w(TAG, "解析超时，第 " + retryCount + " 次重试...");
                    isCrawling = false;
                    hasInjectedLogin = false;
                    mainHandler.postDelayed(() -> retryCrawl(), RETRY_DELAY_MS);
                } else {
                    notifyFrontError("解析超时，请重试");
                    isCrawling = false;
                    hasInjectedLogin = false;
                }
            }
        }, 10000);

        String js = "window.__parseTimetable = function() { try {" +
                // 教务系统返回的地点文本存在前缀重复，例如：
                //   瑞樟4瑞樟4-101   体育馆体育馆[篮球场]   屏山7屏山7-403[...]
                // 判据：存在 k（2 <= k <= len/2）使 s 以「同一前缀连写两遍」开头，
                // 且重复之后仍有剩余内容（k < len-2k 的约束见下面 break）。
                // 满足则折叠为  p + s.substring(2k)。
                // 之所以要求“还有剩余”，是为了不把「A101A101」这种
                // 本身就是完整房间号的字符串截断成「A101」。
                "var dedupPrefix = function(s) {" +
                "    if (!s) return s;" +
                "    var len = s.length, max = Math.floor(len / 2);" +
                "    for (var k = 2; k <= max; k++) {" +
                "        if (len - 2 * k < 1) break;" +
                "        var p = s.substring(0, k);" +
                "        if (s.startsWith(p + p)) return p + s.substring(2 * k);" +
                "    }" +
                "    return s;" +
                "};" +
                // 从 item 自身的 detailitem 里取字段。
                // 注意真实 DOM 结构：<div class="qz-tooltip"> 是 <ul class="courselists">
                // 的兄弟，不是 <li> 的兄弟，所以 item.nextElementSibling() 恒为 null，
                // 原来那段 tooltip 读取逻辑从未生效，weekRange / sections 一直是空的。
                // 这里改为读取 item 下的 qz-hasCourse-detailitem，与 tooltip 里同源。
                //   '教师：方政蕊'            -> teacher
                //   '01~02小节'              -> sections（无前缀，靠后缀识别）
                //   '[1-16周] 星期二'        -> weekRange + dayDate
                //   '瑞樟4瑞樟4-101'         -> location（前缀重复，走 dedupPrefix）
                "var parseItemDetails = function(item, course) {" +
                "    var dets = item.querySelectorAll('div.qz-hasCourse-detailitem');" +
                "    for (var i = 0; i < dets.length; i++) {" +
                "        var t = dets[i].textContent.trim();" +
                "        if (!t) continue;" +
                "        if (t.indexOf('教师：') === 0) {" +
                "            if (!course.teacher) course.teacher = t.substring(3);" +
                "        } else if (/小节$/.test(t)) {" +
                "            if (!course.sections) course.sections = t;" +
                "        } else if (t.charAt(0) === '[') {" +
                "            if (!course.weekRange) course.weekRange = t;" +
                "        } else if (t.indexOf('：') < 0 && !course.location) {" +
                "            course.location = t;" +
                "        }" +
                "    }" +
                "};" +
                "var table = document.querySelector('table.qz-weeklyTable');" +
                "if (!table) { AndroidBack.onError('未找到课表表格'); return; }" +
                "var courses = [];" +
                "var headers = [];" +
                "var ths = table.querySelectorAll('thead tr th.qz-weeklyTable-th');" +
                "for (var i = 1; i < ths.length; i++) {" +
                "    var spans = ths[i].querySelectorAll('span');" +
                "    if (spans.length >= 2) {" +
                "        headers.push({day: spans[0].textContent.trim(), date: spans[1].textContent.trim()});" +
                "    }" +
                "}" +
                "var rows = table.querySelectorAll('tbody tr.qz-weeklyTable-tr');" +
                "rows.forEach(function(row) {" +
                "    var tds = row.querySelectorAll('td.qz-weeklyTable-td');" +
                "    if (tds.length === 0) return;" +
                "    var timeSlot = '';" +
                "    var titleDiv = tds[0].querySelector('div.index-title');" +
                "    if (titleDiv) timeSlot = titleDiv.textContent.trim();" +
                "    for (var dayIdx = 1; dayIdx < tds.length && dayIdx <= headers.length; dayIdx++) {" +
                "        var td = tds[dayIdx];" +
                "        var ul = td.querySelector('ul.courselists');" +
                "        if (!ul) continue;" +
                "        var items = ul.querySelectorAll('li.courselists-item');" +
                "        items.forEach(function(item) {" +
                "            var nameDiv = item.querySelector('div.qz-hasCourse-title');" +
                "            var name = nameDiv ? nameDiv.textContent.trim() : '未知课程';" +
                // 字段一次性从 item 自身的 detailitem 解析（tooltip 取不到，见上面说明）
                "            var course = {teacher:'', location:'', weekRange:'', sections:''};" +
                "            parseItemDetails(item, course);" +
                "            courses.push({" +
                "                name: name," +
                "                teacher: course.teacher || '未知教师'," +
                "                location: dedupPrefix(course.location) || '未知地点'," +
                "                weekRange: course.weekRange," +
                "                dayOfWeek: headers[dayIdx - 1].day," +
                // dayDate 用表头里的日期（如 10-13）。
                // 注意不要改用 weekRange 里的「星期二」：那只是"星期几"，
                // 语义上与 dayOfWeek 重复，且显示时会与"第"字拼出「第10-13」这种怪结果。
                "                dayDate: headers[dayIdx - 1].date," +
                "                timeSlot: timeSlot," +
                "                sections: course.sections || timeSlot" +
                "            });" +
                "        });" +
                "    }" +
                "});" +
                // 当前周次：教务系统已在页面里算好并选中，直接读，不要自己按开学日期推算。
                // 同时把可选周次一起回传，让界面上的周次选择由页面数据驱动，
                // 而不是在前端硬编码「第 1..20 周」。
                "var weekOptions = [];" +
                "var weekSel = document.getElementById('week');" +
                "if (weekSel) {" +
                "    for (var wi = 0; wi < weekSel.options.length; wi++) {" +
                "        var op = weekSel.options[wi];" +
                "        if (op.value) weekOptions.push({value: op.value, label: op.textContent.trim()});" +
                "    }" +
                "}" +
                "var currentWeek = '';" +
                "var selOpt = document.querySelector('select option[selected]');" +
                "if (selOpt) currentWeek = selOpt.textContent.trim();" +
                "if (!currentWeek) {" +
                "    var sel = document.querySelector('select');" +
                "    if (sel && sel.options && sel.selectedIndex >= 0) currentWeek = sel.options[sel.selectedIndex].text;" +
                "}" +
                "if (!currentWeek) {" +
                "    var inputs = document.querySelectorAll('input.layui-unselect');" +
                "    for (var k = 0; k < inputs.length; k++) {" +
                "        var v = inputs[k].value || '';" +
                "        if (/第.+周/.test(v)) { currentWeek = v; break; }" +
                "    }" +
                "}" +
                "AndroidBack.onCourses(JSON.stringify({courses: courses, currentWeek: currentWeek, weeks: weekOptions}));" +
                "} catch(e) { AndroidBack.onError(e.message); }" +
                "};" +
                "window.__parseTimetable();";
        view.evaluateJavascript(js, null);
    }

    /**
     * 切换课表周次。
     *
     * 复用学校页面自己的逻辑：把 #week 的值改掉，再调用它页面里的 se(null)，
     * 它会用 jQuery 的 $(...).load() 把新课表注入 #kbLoading 容器。
     *
     * 为什么不直接拼那个 URL：URL 里带 kbjcmsid / xnxq01id 等参数，
     * 前者随课表模式变化、后者每学期都不同。复用页面逻辑就不必写死它们。
     *
     * 等待策略：观察 #kbLoading 子树变化，而不是固定 sleep。
     * 一旦容器内容真的更新了就立刻解析；超时则报错，避免静默卡住。
     */
    private void changeWeek(String weekValue) {
        if (isCrawling) {
            appendLog("正在忙，请稍后再切换周次");
            return;
        }
        isCrawling = true;
        changeWeekPending = true;
        appendLog("切换周次：" + (weekValue.isEmpty() ? "全部" : "第 " + weekValue + " 周"));

        String js =
                "(function() {" +
                "  var wk = document.getElementById('week');" +
                "  if (!wk) { AndroidBack.onError('未找到周次选择控件 #week'); return; }" +
                "  wk.value = '" + weekValue + "';" +
                "  var box = document.getElementById('kbLoading');" +
                "  if (!box) { AndroidBack.onError('未找到课表容器 #kbLoading'); return; }" +
                "  if (typeof se !== 'function') { AndroidBack.onError('页面未定义 se()'); return; }" +
                "  var done = false;" +
                "  var snap = function() {" +
                "    return box.innerHTML.length + ':' + box.querySelectorAll('li.courselists-item').length;" +
                "  };" +
                "  var before = snap();" +
                "  var fire = function(tag) {" +
                "    if (done) return; done = true;" +
                "    if (obs) obs.disconnect();" +
                "    if (timer) clearTimeout(timer);" +
                "    AndroidBack.onLog('周次加载' + tag + '，准备重新解析');" +
                "    __parseTimetable();" +
                "  };" +
                "  var obs = new MutationObserver(function() {" +
                "    if (snap() !== before) fire('完成');" +
                "  });" +
                "  obs.observe(box, {childList: true, subtree: true, characterData: true});" +
                // 兜底：容器结构没变（例如该周本就没课）也要收尾，不能让状态卡住
                "  var timer = setTimeout(function() { fire('超时'); }, 6000);" +
                "  try { se(null); } catch (e) { AndroidBack.onError('调用 se() 失败: ' + e.message); }" +
                "})();";
        crawlerView.evaluateJavascript(js, null);

        // 二次兜底：若上面整段都没能回调（页面异常等），恢复状态
        mainHandler.postDelayed(() -> {
            if (changeWeekPending) {
                changeWeekPending = false;
                isCrawling = false;
                appendLog("周次切换超时，请重试");
            }
        }, 15000);
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

            // 如果是"切换周次"触发的解析，无论结果如何都不要再走重试逻辑，
            // 否则用户选了一个没课的周，会被当成抓取失败反复重试。
            final boolean fromWeekChange = changeWeekPending;
            changeWeekPending = false;

            final boolean isValid = hasValidData;
            appendLog("解析得到 " + courseCount + " 门课程"
                    + (currentWeek.isEmpty() ? "" : "，当前" + currentWeek));

            // 没有有效数据且不是切周次导致的，才重试
            if (!isValid && !fromWeekChange && retryCount < MAX_RETRY) {
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
     * 注意：新首页没有 #tip 元素，这里只通过日志通道回传，
     * 同时把登录按钮恢复为可点击状态，避免界面卡在"登录中…"。
     */
    private void notifyFrontError(String msg) {
        appendLog(msg);
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
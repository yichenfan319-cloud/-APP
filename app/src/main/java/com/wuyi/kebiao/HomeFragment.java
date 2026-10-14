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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;

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

    // UI组件
    private TextView tvGreeting;

    // 登录状态
    private String pendingUser = null;
    private String pendingPass = null;
    private boolean isCrawling = false;
    private boolean hasInjectedLogin = false;
    private Runnable timeoutRunnable = null;

    // 重试计数器
    private int retryCount = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        webView = view.findViewById(R.id.webView);

        initHomeUI(view);

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

    private void initHomeUI(View view) {
        tvGreeting = view.findViewById(R.id.tv_greeting);

        updateGreeting();
    }

    private void updateGreeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting;
        if (hour >= 5 && hour < 11) greeting = "开启新的一天";
        else if (hour >= 11 && hour < 13) greeting = "午睡啦";
        else if (hour >= 13 && hour < 18) greeting = "精力充沛";
        else if (hour >= 18 && hour < 22) greeting = "可以放松啦";
        else greeting = "难道你是夜猫子";
        tvGreeting.setText(greeting);
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
            Log.d(TAG, "前台请求登录: " + username);
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
            mainHandler.post(() -> startCrawl());
        }

        @JavascriptInterface
        public void relogin() {
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

        @JavascriptInterface
        public void openWeekView(String coursesJson) {
            Intent intent = new Intent(requireContext(), WeekViewActivity.class);
            intent.putExtra("courses_json", coursesJson);
            startActivity(intent);
        }

        @JavascriptInterface
        public void forceRetry() {
            Log.d(TAG, "手动强制重试");
            retryCount = 0;
            startCrawl();
        }
    }

    // ==================== 爬虫主流程 ====================
    private void startCrawl() {
        if (isCrawling) return;
        isCrawling = true;
        hasInjectedLogin = false;

        webView.evaluateJavascript(
                "document.getElementById('login-page').classList.add('active');" +
                        "document.getElementById('timetable-page').classList.remove('active');" +
                        "var tip=document.getElementById('tip');if(tip){tip.className='tip ok';tip.textContent='正在连接教务系统...';}" +
                        "var btn=document.querySelector('.btn .txt');if(btn)btn.textContent='登录中...';" +
                        "var spin=document.querySelector('.btn .loading');if(spin)spin.classList.remove('hidden');", null);

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
                    Log.w(TAG, "超时，第 " + retryCount + " 次重试...");
                    notifyFrontError("连接超时，正在重试...");
                    mainHandler.postDelayed(() -> retryCrawl(), RETRY_DELAY_MS);
                } else {
                    notifyFrontError("连接超时，请检查网络或账号密码");
                }
            }
        };
        mainHandler.postDelayed(timeoutRunnable, 25000);

        restoreCookies();
        Log.d(TAG, "后台开始加载课表页...");
        crawlerView.stopLoading();
        crawlerView.clearHistory();
        crawlerView.loadUrl("https://jwxt.wuyiu.edu.cn/jsxsd/framework/xsMainV_new.htmlx?t1=1");
    }

    /**
     * 重试爬取（保留Cookie，不重置计数）
     */
    private void retryCrawl() {
        Log.d(TAG, "执行重试，当前重试次数: " + retryCount);
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
                Log.d(TAG, "后台检测到登录页，启动自动登录...");
                hasInjectedLogin = true;
                view.postDelayed(() -> injectAutoLogin(view), 1000);
            } else if (url.contains("xsMainV_new.htmlx") || url.contains("xsMainV.htmlx")) {
                Log.d(TAG, "后台到达课表页，准备提取...");
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

        String js = "(function() { try {" +
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
                "            var tooltip = item.nextElementSibling;" +
                "            var teacher = '', location = '', weekRange = '', sections = '';" +
                "            if (tooltip && tooltip.classList.contains('qz-tooltip')) {" +
                "                var details = tooltip.querySelectorAll('div.qz-tooltipContent-detailitem');" +
                "                details.forEach(function(div) {" +
                "                    var text = div.textContent.trim();" +
                "                    if (text.startsWith('教师：')) teacher = text.replace('教师：', '');" +
                "                    else if (text.startsWith('上课地点：')) location = text.replace('上课地点：', '');" +
                "                    else if (text.startsWith('周次：')) weekRange = text.replace('周次：', '');" +
                "                    else if (text.startsWith('节次：')) sections = text.replace('节次：', '');" +
                "                });" +
                "            }" +
                "            if (!teacher || !location) {" +
                "                var details2 = item.querySelectorAll('div.qz-hasCourse-detailitem');" +
                "                details2.forEach(function(div) {" +
                "                    var text = div.textContent.trim();" +
                "                    if (text.startsWith('教师：') && !teacher) teacher = text.replace('教师：', '');" +
                "                    else if (text.startsWith('节次：') && !sections) sections = text.replace('节次：', '');" +
                "                    else if (text.startsWith('周次：') && !weekRange) weekRange = text.replace('周次：', '');" +
                "                    else if (!text.endsWith('小节') && !text.startsWith('[') && !location) location = text;" +
                "                });" +
                "            }" +
                "            courses.push({" +
                "                name: name," +
                "                teacher: teacher || '未知教师'," +
                "                location: location || '未知地点'," +
                "                weekRange: weekRange," +
                "                dayOfWeek: headers[dayIdx - 1].day," +
                "                dayDate: headers[dayIdx - 1].date," +
                "                timeSlot: timeSlot," +
                "                sections: sections || timeSlot" +
                "            });" +
                "        });" +
                "    }" +
                "});" +
                "AndroidBack.onCourses(JSON.stringify(courses));" +
                "} catch(e) { AndroidBack.onError(e.message); }" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    // ==================== Java 与 JS 桥接 ====================
    public class BackBridge {
        @JavascriptInterface
        public void onCourses(String json) {
            Log.d(TAG, "后台提取成功，JSON长度: " + json.length());

            // 检查是否解析到有效数据
            boolean hasValidData = false;
            try {
                List<?> courses = gson.fromJson(json, List.class);
                hasValidData = courses != null && !courses.isEmpty();
            } catch (Exception e) {
                hasValidData = false;
            }

            // 🔥 解决方案：将 hasValidData 赋值给 final 变量
            final boolean isValid = hasValidData;

            // 如果没有有效数据，且未超过重试次数，则重试
            if (!isValid && retryCount < MAX_RETRY) {
                retryCount++;
                Log.w(TAG, "解析到空课表，第 " + retryCount + " 次重试...");
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

            // 🔥 使用 final 变量 isValid
            mainHandler.post(() -> {
                String escapedJson = json.replace("'", "\\'");
                webView.evaluateJavascript("window.receiveFromAndroid('courses', '" + escapedJson + "');", null);
                if (isValid) {
                    webView.evaluateJavascript("window.receiveFromAndroid('status', '登录成功');", null);
                } else {
                    webView.evaluateJavascript("window.receiveFromAndroid('status', '获取课表失败，请重试');", null);
                }
            });
        }

        @JavascriptInterface
        public void onError(String msg) {
            Log.e(TAG, "后台错误: " + msg);

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
    private void notifyFrontError(String msg) {
        mainHandler.post(() -> {
            String escapedMsg = msg.replace("'", "\\'");
            webView.evaluateJavascript(
                    "var tip=document.getElementById('tip');if(tip){tip.className='tip err';tip.textContent='" + escapedMsg + "';}" +
                            "var btn=document.querySelector('.btn .txt');if(btn)btn.textContent='登录教务系统';" +
                            "var spin=document.querySelector('.btn .loading');if(spin)spin.classList.add('hidden');", null);
        });
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
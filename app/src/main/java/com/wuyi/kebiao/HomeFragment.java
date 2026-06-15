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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";
    private static final String PREFS_NAME = "WuyiKebiaoPrefs";
    private static final String KEY_COOKIES = "saved_cookies";
    private static final String KEY_USERNAME = "saved_username";
    private static final String KEY_PASSWORD = "saved_password";

    // 服务器地址
    private static final String WEATHER_API_URL = "http://47.105.75.20:8000/api/weather";
    private static final String MOTIVATION_API_URL = "http://47.105.75.20:8002/api/weather/tip";

    private WebView webView;      // 前台显示用的 WebView
    private WebView crawlerView;  // 后台爬虫 WebView
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private OkHttpClient client;

    // 信息栏组件
    private TextView tvGreeting;
    private TextView tvWeather;
    private ImageView ivWeatherIcon;
    private LinearLayout layoutWeather;

    // 提示栏组件
    private ImageView ivCharacter;
    private TextView tvMotivation;

    private String pendingUser = null;
    private String pendingPass = null;
    private boolean isCrawling = false;
    private boolean hasInjectedLogin = false;
    private Runnable timeoutRunnable = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        // 初始化 SharedPreferences
        prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 初始化 OkHttp
        client = new OkHttpClient();

        // 获取 WebView
        webView = view.findViewById(R.id.webView);

        // 初始化主界面 UI（问候语 + 天气 + 提示栏）
        initHomeUI(view);

        // 创建隐藏的爬虫 WebView
        crawlerView = new WebView(requireContext());
        crawlerView.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
        crawlerView.setVisibility(View.GONE);
        ((ViewGroup) view).addView(crawlerView);

        // 设置前台 WebView
        setupWebView(webView);
        webView.loadUrl("file:///android_asset/www/index.html");

        // 设置后台爬虫 WebView
        setupCrawlerView();

        // 自动登录（如果有保存的账号密码）
        autoLoginIfCredentialsExist();

        return view;
    }

    /**
     * 初始化主界面 UI 组件
     */
    private void initHomeUI(View view) {
        // 信息栏
        tvGreeting = view.findViewById(R.id.tv_greeting);
        tvWeather = view.findViewById(R.id.tv_weather);
        ivWeatherIcon = view.findViewById(R.id.iv_weather_icon);
        layoutWeather = view.findViewById(R.id.layout_weather);

        // 提示栏
        ivCharacter = view.findViewById(R.id.iv_character);
        tvMotivation = view.findViewById(R.id.tv_motivation);

        // 设置问候语
        updateGreeting();

        // 获取天气
        fetchWeather();

        // 获取提示句子
        fetchMotivation();

        // 点击天气刷新
        layoutWeather.setOnClickListener(v -> fetchWeather());
    }

    /**
     * 更新问候语（根据当前时间）
     */
    private void updateGreeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting;
        if (hour >= 5 && hour < 11) {
            greeting = "迎接新的一天";
        } else if (hour >= 11 && hour < 13) {
            greeting = "睡个午觉吧";
        } else if (hour >= 13 && hour < 18) {
            greeting = "下午好";
        } else if (hour >= 18 && hour < 22) {
            greeting = "祝你好梦";
        } else {
            greeting = "你也是夜猫子？";
        }
        tvGreeting.setText(greeting);
    }

    /**
     * 获取天气信息
     */
    private void fetchWeather() {
        Request request = new Request.Builder().url(WEATHER_API_URL).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                requireActivity().runOnUiThread(() -> {
                    tvWeather.setText("天气获取失败");
                    ivWeatherIcon.setImageResource(R.drawable.ic_weather_default);
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    String weather = json.optString("weather", "未知");
                    String temp = json.optString("temp", "");
                    String city = json.optString("city", "");

                    int iconRes = getWeatherIcon(weather);
                    String weatherText = city + " " + weather + " " + temp;

                    requireActivity().runOnUiThread(() -> {
                        tvWeather.setText(weatherText.trim());
                        ivWeatherIcon.setImageResource(iconRes);
                    });

                    // 根据天气更新二次元形象
                    updateCharacterImage(weather);

                } catch (Exception e) {
                    requireActivity().runOnUiThread(() -> {
                        tvWeather.setText("天气解析失败");
                        ivWeatherIcon.setImageResource(R.drawable.ic_weather_default);
                    });
                }
            }
        });
    }

    /**
     * 根据天气类型返回对应图标
     */
    private int getWeatherIcon(String weather) {
        if (weather.contains("晴")) {
            return R.drawable.ic_weather_sunny;
        } else if (weather.contains("雨")) {
            return R.drawable.ic_weather_rainy;
        } else if (weather.contains("阴") || weather.contains("云")) {
            return R.drawable.ic_weather_default;
        } else {
            return R.drawable.ic_weather_default;
        }
    }

    /**
     * 根据天气更新二次元形象图片
     */
    private void updateCharacterImage(String weather) {
        int resId;
        if (weather.contains("晴")) {
            resId = R.drawable.character_sunny;
        } else if (weather.contains("雨")) {
            resId = R.drawable.character_rainy;
        } else if (weather.contains("阴") || weather.contains("云")) {
            resId = R.drawable.character_cloudy;
        } else {
            resId = R.drawable.character_default;
        }
        requireActivity().runOnUiThread(() -> {
            ivCharacter.setImageResource(resId);
        });
    }

    /**
     * 获取提示句子（语言框）
     */
    private void fetchMotivation() {
        Request request = new Request.Builder().url(MOTIVATION_API_URL).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                requireActivity().runOnUiThread(() -> {
                    tvMotivation.setText("今天也要加油哦~");
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    String sentence = json.optString("sentence", "今天也要加油哦~");

                    requireActivity().runOnUiThread(() -> {
                        tvMotivation.setText(sentence);
                    });
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() -> {
                        tvMotivation.setText("今天也要加油哦~");
                    });
                }
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
            Log.d(TAG, "发现已保存的账号，自动登录: " + savedUser);
            webView.postDelayed(() -> {
                pendingUser = savedUser;
                pendingPass = savedPass;
                startCrawl();
            }, 1000);
        } else {
            Log.d(TAG, "未发现已保存的账号，需要手动登录");
        }
    }

    // 前端交互接口
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
                String safeUser = username.replace("'", "\\'").replace("\"", "\\\"");
                String safePass = password != null ? password.replace("'", "\\'").replace("\"", "\\\"") : "";
                String json = "{\"username\":\"" + safeUser + "\",\"password\":\"" + safePass + "\"}";
                mainHandler.post(() -> {
                    webView.evaluateJavascript("window.receiveFromAndroid('saved_credentials', '" + json + "');", null);
                });
            } else {
                mainHandler.post(() -> {
                    webView.evaluateJavascript("window.receiveFromAndroid('saved_credentials', 'null');", null);
                });
            }
        }

        // 🔥 周课表跳转
        @JavascriptInterface
        public void openWeekView(String coursesJson) {
            Log.d(TAG, "openWeekView 被调用，JSON长度: " + coursesJson.length());
            Intent intent = new Intent(requireContext(), WeekViewActivity.class);
            intent.putExtra("courses_json", coursesJson);
            startActivity(intent);
        }
    }

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

        if (timeoutRunnable != null) mainHandler.removeCallbacks(timeoutRunnable);
        timeoutRunnable = () -> {
            if (isCrawling) {
                Log.e(TAG, "爬取超时");
                isCrawling = false;
                hasInjectedLogin = false;
                notifyFrontError("连接超时，请检查网络或账号密码");
            }
        };
        mainHandler.postDelayed(timeoutRunnable, 25000);

        restoreCookies();
        Log.d(TAG, "后台开始加载课表页...");
        crawlerView.stopLoading();
        crawlerView.clearHistory();
        crawlerView.loadUrl("https://jwxt.wuyiu.edu.cn/jsxsd/framework/xsMainV_new.htmlx?t1=1");
    }

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
                view.postDelayed(() -> injectParser(view), 3500);
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

    private void injectParser(WebView view) {
        String js = "(function() { try {" +
                "var table = document.querySelector('table.qz-weeklyTable');" +
                "if (!table) { AndroidBack.onError('课表获取失败，请重新获取'); return; }" +
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

    public class BackBridge {
        @JavascriptInterface
        public void onCourses(String json) {
            Log.d(TAG, "后台提取成功，JSON长度: " + json.length());
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
                webView.evaluateJavascript("window.receiveFromAndroid('status', '登录成功');", null);
            });
        }

        @JavascriptInterface
        public void onError(String msg) {
            Log.e(TAG, "后台错误: " + msg);
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
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (crawlerView != null) {
            crawlerView.destroy();
        }
    }
}
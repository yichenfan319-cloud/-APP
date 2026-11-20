package com.wuyi.kebiao;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.util.LinkedHashMap;

/**
 * 取数引擎：一张隐藏 WebView 只用来过单点登录，拿到 Cookie 之后就走 HTTP 直连。
 *
 * <p>为什么必须留着这张 WebView：统一身份认证的密码是页面 JS 加密后再提交的
 * （AES + 一次性密钥），在原生侧复刻这套加密是纯负担，而且学校一改就废。
 * 让它老老实实用自己的页面登录，我们只伸手拿 Cookie。
 *
 * <p>登录之后的活全部交给 {@link TimetableRepository}：它按接口原文解析，
 * 不依赖页面变成什么样子，也就不需要超时兜底。
 */
public final class ScheduleEngine {

    public interface Callback {
        void onState(ThinkingOrbView.State state, String detail);

        void onSnapshot(TimetableRepository.Snapshot snapshot);

        void onLog(String message);

        void onError(String message);
    }

    private static final String TAG = "ScheduleEngine";
    private static final String ENTRY =
            "https://jwxt.wuyiu.edu.cn/jsxsd/framework/xsMainV_new.htmlx?t1=1";
    private static final long TIMEOUT_MS = 25_000L;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback cb;

    private WebView webView;
    private boolean running;
    private boolean injectedLogin;
    private String pendingUser;
    private String pendingPass;

    private final Runnable timeout;

    public ScheduleEngine(Context context, Callback callback) {
        appContext = context.getApplicationContext();
        prefs = Prefs.get(appContext);
        cb = callback;
        // 只能在 cb 赋值之后再建这个 Runnable：字段初始化器跑在构造体之前，
        // 那时候 cb 还没写进去，javac 会报"可能尚未初始化"。
        timeout = () -> {
            if (!running) {
                return;
            }
            running = false;
            cb.onState(ThinkingOrbView.State.ERROR, "连接超时");
            cb.onError("连接超时，请检查网络或账号密码");
        };
    }

    /** 把隐藏 WebView 挂进宿主。必须在主线程调用。 */
    public void attach(ViewGroup host) {
        if (webView != null) {
            return;
        }
        webView = new WebView(host.getContext());
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setWebViewClient(new Client());
        webView.addJavascriptInterface(new Bridge(), "AndroidBack");
        host.addView(webView, new ViewGroup.LayoutParams(0, 0));
    }

    public boolean isRunning() {
        return running;
    }

    /** 用给定的账号密码走一次完整流程（验证时用）。 */
    public void start(String user, String pass) {
        if (running) {
            return;
        }
        pendingUser = user;
        pendingPass = pass;
        beginSearching("正在连接教务系统");
        // 先试快路径：WebView 的 Cookie 是持久化的，上次的会话可能还有效
        tryDirectFetch(this::webViewLogin);
    }

    /** 用已保存的凭据刷新。 */
    public void refresh() {
        if (running) {
            return;
        }
        String u = prefs.getString(Prefs.KEY_USERNAME, null);
        String p = prefs.getString(Prefs.KEY_PASSWORD, null);
        if (u == null || u.isEmpty() || p == null || p.isEmpty()) {
            cb.onState(ThinkingOrbView.State.IDLE, "还没有填过账号密码");
            cb.onError("请先在「账号」页填好学号和密码");
            return;
        }
        pendingUser = u;
        pendingPass = p;
        beginSearching("正在连接教务系统");
        tryDirectFetch(this::webViewLogin);
    }

    private void beginSearching(String detail) {
        running = true;
        injectedLogin = false;
        cb.onState(ThinkingOrbView.State.SEARCHING, detail);
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, TIMEOUT_MS);
    }

    // ------------------------------------------------------------ 快路径

    private interface Fallback {
        void run();
    }

    private void tryDirectFetch(Fallback fallback) {
        final String cookie = collectCookies();
        if (cookie.isEmpty()) {
            if (fallback != null) {
                fallback.run();
            }
            return;
        }
        cb.onLog("尝试用现有会话直接获取课表…");
        new Thread(() -> {
            try {
                TimetableRepository.Snapshot snap =
                        TimetableRepository.fetch(cookie, msg -> main.post(() -> cb.onLog(msg)));
                finishWith(snap);
            } catch (IOException e) {
                main.post(() -> {
                    cb.onLog("现有会话不可用：" + e.getMessage());
                    if (fallback != null) {
                        fallback.run();
                    }
                });
            }
        }, "timetable-fast").start();
    }

    private void finishWith(TimetableRepository.Snapshot snap) {
        main.post(() -> {
            running = false;
            main.removeCallbacks(timeout);
            cb.onSnapshot(snap);
            cb.onState(ThinkingOrbView.State.DONE,
                    "第 " + snap.currentWeek + " 周 · 共 " + snap.courses.size() + " 门课");
        });
    }

    // ------------------------------------------------------------ 登录

    private void webViewLogin() {
        if (webView == null) {
            return;
        }
        cb.onLog("需要重新登录，正在打开统一身份认证…");
        cb.onState(ThinkingOrbView.State.THINKING, "正在登录统一身份认证");
        webView.stopLoading();
        webView.clearHistory();
        webView.loadUrl(ENTRY);
    }

    private final class Client extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            if (!running) {
                return;
            }
            if (isLoginPage(url)) {
                if (!injectedLogin) {
                    injectedLogin = true;
                    injectAutoLogin(view);
                }
                return;
            }
            if (url != null && (url.contains("xsMainV_new.htmlx") || url.contains("xsMainV.htmlx"))) {
                cb.onLog("登录成功，改用接口直接获取课表");
                cb.onState(ThinkingOrbView.State.SEARCHING, "正在读取课表");
                fetchAfterLogin();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }
    }

    private void fetchAfterLogin() {
        final String cookie = collectCookies();
        new Thread(() -> {
            try {
                TimetableRepository.Snapshot snap =
                        TimetableRepository.fetch(cookie, msg -> main.post(() -> cb.onLog(msg)));
                saveCookies();
                finishWith(snap);
            } catch (IOException e) {
                main.post(() -> {
                    running = false;
                    main.removeCallbacks(timeout);
                    cb.onState(ThinkingOrbView.State.ERROR, "取课表失败");
                    cb.onError("取课表失败：" + e.getMessage());
                });
            }
        }, "timetable-after-login").start();
    }

    private static boolean isLoginPage(String url) {
        return url != null && url.contains("sso.wuyiu.edu.cn")
                && (url.contains("/linkid/") || url.contains("/login") || url.contains("统一认证"));
    }

    // ------------------------------------------------------------ 注入登录

    private void injectAutoLogin(WebView view) {
        if (pendingUser == null || pendingPass == null) {
            return;
        }
        final String u = jsString(pendingUser);
        final String p = jsString(pendingPass);
        final String js =
                "(function(){" +
                        "var username=" + u + ",password=" + p + ";" +
                        "var maxWait=12000,start=Date.now();" +
                        "var fill=function(el,v){el.value=v;" +
                        "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                        "el.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "if(el._valueTracker)el._valueTracker.setValue(v);};" +
                        "var click=function(){" +
                        "var b=document.getElementById('submitBtn');" +
                        "if(!b){AndroidBack.onError('未找到登录按钮 submitBtn');return false;}" +
                        "b.disabled=false;b.classList.remove('disabled');b.click();" +
                        "AndroidBack.onLog('已提交登录');return true;};" +
                        "var loop=function(){" +
                        "var un=document.getElementById('nameInput');" +
                        "var pw=document.querySelector('input[type=\"password\"]');" +
                        "if(un&&pw){fill(un,username);fill(pw,password);" +
                        "setTimeout(function(){if(!click()){" +
                        "var f=document.getElementById('normalLoginForm');" +
                        "if(f){var ev=new Event('submit',{bubbles:true,cancelable:true});" +
                        "f.dispatchEvent(ev);if(!ev.defaultPrevented)f.submit();}}},500);" +
                        "}else if(Date.now()-start<maxWait){setTimeout(loop,500);}" +
                        "else{AndroidBack.onError('超时：未找到用户名或密码输入框');}};" +
                        "loop();})();";
        view.evaluateJavascript(js, null);
    }

    /** 用 JSON 的引号规则转义，能覆盖引号、反斜杠、换行和中文。 */
    private static String jsString(String s) {
        return org.json.JSONObject.quote(s == null ? "" : s);
    }

    private final class Bridge {
        @JavascriptInterface
        public void onLog(String msg) {
            main.post(() -> cb.onLog(msg));
        }

        @JavascriptInterface
        public void onError(String msg) {
            main.post(() -> {
                if (!running) {
                    return;
                }
                running = false;
                main.removeCallbacks(timeout);
                cb.onState(ThinkingOrbView.State.ERROR, "登录失败");
                cb.onError(msg);
            });
        }
    }

    // ------------------------------------------------------------ Cookie

    /**
     * 同时取两个域并按名字去重。
     *
     * <p>两个坑都踩过：① 单点登录把 SESSION / SOURCEID_TGC 下在 sso 域，只取
     * jwxt 域会得到"有 Cookie 但服务端认为未登录"的半套凭据；② getCookie 按
     * path 匹配，学校把 jsessionid 挂在 /jsxsd 上，只传域名拿不到它。
     */
    private String collectCookies() {
        CookieManager cm = CookieManager.getInstance();
        String[] sources = {
                cm.getCookie(TimetableRepository.LOADKB_URL),
                cm.getCookie("https://sso.wuyiu.edu.cn/login")
        };
        LinkedHashMap<String, String> byName = new LinkedHashMap<>();
        for (String raw : sources) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            for (String part : raw.split(";")) {
                String item = part.trim();
                if (item.isEmpty()) {
                    continue;
                }
                int eq = item.indexOf('=');
                String name = eq > 0 ? item.substring(0, eq).trim() : item;
                if (!byName.containsKey(name)) {
                    byName.put(name, item);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String v : byName.values()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(v);
        }
        Log.d(TAG, "cookies=" + byName.keySet());
        return sb.toString();
    }

    private void saveCookies() {
        CookieManager cm = CookieManager.getInstance();
        String jwxt = cm.getCookie(TimetableRepository.BASE);
        String sso = cm.getCookie("https://sso.wuyiu.edu.cn");
        prefs.edit()
                .putString(Prefs.KEY_COOKIES,
                        (jwxt == null ? "" : jwxt) + ";;" + (sso == null ? "" : sso))
                .apply();
    }

    public void destroy() {
        main.removeCallbacks(timeout);
        running = false;
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
    }
}

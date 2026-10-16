package com.wuyi.kebiao;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 版本检查与更新提示。
 *
 * 原先这段逻辑埋在 ProfileFragment 里（只有手动点「检查更新」时才跑）。
 * 现在启动自动检查也要用同一套，所以抽出来共用 —— 复制两份迟早会不一致。
 *
 * 服务端契约（实测 http://47.105.75.20:3000/api/version）：
 *   {"versionCode":11,"versionName":"2.0.0-beta","updateUrl":"http://.../app-release.apk"}
 *
 * 「忽略这个版本」的实现：把被忽略的 versionCode 存进 SharedPreferences，
 * 之后该版本不再自动弹窗；有效当前版本取 max(已安装, 已忽略)，
 * 这样服务端发了更高版本时照常提醒 —— 忽略的是版本，不是更新这件事。
 */
public final class VersionChecker {

    /** 自动检查开关，默认开启 */
    public static final String KEY_AUTO_CHECK = "auto_check_update";
    /** 用户选择忽略的版本号 */
    private static final String KEY_IGNORED = "ignored_version_code";
    private static final String PREFS = "WuyiKebiaoPrefs";

    private VersionChecker() {
    }

    private interface OnResult {
        void onResult(Remote r);
    }

    private static final class Remote {
        int versionCode;
        String versionName = "";
        String updateUrl = "";
    }

    /* ==================== 偏好读写 ==================== */

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isAutoCheckEnabled(Context c) {
        return prefs(c).getBoolean(KEY_AUTO_CHECK, true);
    }

    public static void setAutoCheckEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply();
    }

    public static int getIgnoredVersion(Context c) {
        return prefs(c).getInt(KEY_IGNORED, 0);
    }

    private static void setIgnoredVersion(Context c, int code) {
        prefs(c).edit().putInt(KEY_IGNORED, code).apply();
    }

    /* ==================== 已安装版本 ==================== */

    public static int currentVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    public static String currentVersionName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "1.0" : pi.versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    /**
     * 有效当前版本 = max(已安装, 已忽略)。
     * 用它去和服务端比，等价于「低于或等于已忽略版本的都不再提示」。
     */
    private static int effectiveVersionCode(Context c) {
        return Math.max(currentVersionCode(c), getIgnoredVersion(c));
    }

    /* ==================== 检查入口 ==================== */

    /**
     * 启动时的自动检查。
     *
     * 三道闸门，缺一个都会造成打扰：
     *   1. 设置里开着自动检查
     *   2. 该版本没被忽略过
     *   3. 网络请求成功（失败就静默，绝不能在启动时弹错误打扰用户）
     */
    public static void autoCheck(Context c) {
        if (!isAutoCheckEnabled(c)) return;
        fetch(c, remote -> {
            if (remote == null) return;                       // 静默失败
            if (remote.versionCode <= effectiveVersionCode(c)) return;
            if (remote.versionCode <= getIgnoredVersion(c)) return;
            showDialog(c, remote, true);
        });
    }

    /** 设置里手动点「检查更新」。失败时要明确告知，不能像自动检查那样静默。 */
    public static void manualCheck(Context c) {
        fetch(c, remote -> {
            if (remote == null) {
                toast(c, "版本检查失败，请检查网络");
                return;
            }
            if (remote.versionCode > currentVersionCode(c)) {
                showDialog(c, remote, false);
            } else {
                toast(c, "当前已是最新版本（" + currentVersionName(c) + "）");
            }
        });
    }

    /* ==================== 网络 ==================== */

    private static void fetch(Context c, OnResult callback) {
        Request request = new Request.Builder()
                .url(ProfileFragment.SERVER_BASE_URL + "/api/version")
                .get()
                .build();
        new OkHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onResult(null);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                Remote r = null;
                try {
                    String body = response.body() == null ? "" : response.body().string();
                    JSONObject json = new JSONObject(body);
                    Remote parsed = new Remote();
                    parsed.versionCode = json.getInt("versionCode");
                    parsed.versionName = json.optString("versionName", "");
                    parsed.updateUrl = json.optString("updateUrl", "");
                    r = parsed;
                } catch (Exception ignored) {
                    // 解析失败等同于检查失败
                }
                callback.onResult(r);
            }
        });
    }

    /* ==================== 弹窗 ==================== */

    private static void showDialog(Context c, Remote r, boolean allowIgnore) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(c)
                .setTitle("发现新版本 " + r.versionName)
                .setMessage("当前版本：" + currentVersionName(c)
                        + "\n最新版本：" + r.versionName);

        // 顺序：neutral（忽略） / negative（稍后） / positive（立即更新）
        if (allowIgnore) {
            builder.setNeutralButton("忽略此版本", (d, w) -> {
                setIgnoredVersion(c, r.versionCode);
                toast(c, "已忽略 " + r.versionName + "，有新版本时会再提醒");
            });
        }
        builder.setNegativeButton("稍后", null);
        builder.setPositiveButton("立即更新", (d, w) -> {
            if (r.updateUrl == null || r.updateUrl.isEmpty()) {
                toast(c, "暂无下载链接，请稍后再试");
                return;
            }
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.updateUrl)));
            } catch (Exception e) {
                toast(c, "无法打开下载链接");
            }
        });
        builder.show();
    }

    private static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }
}

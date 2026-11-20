package com.wuyi.kebiao;

import android.content.Context;

/** 账号凭据的读写。明文存 SharedPreferences，与旧版行为一致（本地化、不上传）。 */
public final class Persist {

    private Persist() {
    }

    public static String username(Context c) {
        String v = Prefs.get(c).getString(Prefs.KEY_USERNAME, null);
        return v == null ? "" : v;
    }

    public static String password(Context c) {
        String v = Prefs.get(c).getString(Prefs.KEY_PASSWORD, null);
        return v == null ? "" : v;
    }

    /** 只有"存过账号"且"上次真的登录成功过"才算已验证。 */
    public static boolean isVerified(Context c) {
        return Prefs.get(c).getBoolean(Prefs.KEY_VERIFIED, false) && !username(c).isEmpty();
    }

    public static void verifyOk(Context c, String user, String pass) {
        Prefs.get(c).edit()
                .putString(Prefs.KEY_USERNAME, user)
                .putString(Prefs.KEY_PASSWORD, pass)
                .putBoolean(Prefs.KEY_VERIFIED, true)
                .apply();
    }

    public static void clear(Context c) {
        Prefs.get(c).edit()
                .remove(Prefs.KEY_USERNAME)
                .remove(Prefs.KEY_PASSWORD)
                .remove(Prefs.KEY_COOKIES)
                .putBoolean(Prefs.KEY_VERIFIED, false)
                .apply();
    }
}

package com.wuyi.kebiao;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地存储的键，集中一处。
 *
 * <p>这些键名是历史包袱：老版本的 HomeFragment 用它们存过账号密码和 Cookie，
 * 用户的手机上已经有数据了，所以名字不能改，否则升级后要重新填一次。
 */
public final class Prefs {

    public static final String NAME = "WuyiKebiaoPrefs";

    public static final String KEY_COOKIES = "saved_cookies";
    public static final String KEY_USERNAME = "saved_username";
    public static final String KEY_PASSWORD = "saved_password";
    /** 上一次登录是否成功过。新界面用它决定账号页显示"已验证"还是表单。 */
    public static final String KEY_VERIFIED = "account_verified";
    /** 底栏是否使用液态玻璃效果。关掉后是实心胶囊，低端机更省电。 */
    public static final String KEY_GLASS = "nav_glass";

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}

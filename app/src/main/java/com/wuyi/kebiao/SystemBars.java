package com.wuyi.kebiao;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;
import android.view.Window;

import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;

/**
 * 状态栏跟随主题：亮色模式白底黑字、夜间模式黑底白字。
 *
 * <p>三个 Activity（主界面、设置、开源许可）共用这一份，免得各写一遍还写歪。
 * 之前设置页和许可页顶栏是品牌蓝、状态栏也是蓝，跟新界面对不上 —— 改这里统一收口。
 */
final class SystemBars {

    private SystemBars() {
    }

    static void apply(Activity activity) {
        Window window = activity.getWindow();
        int surface = MaterialColors.getColor(window.getDecorView(),
                R.attr.colorSurfaceScreen, 0xFFF0F2F5);
        window.setStatusBarColor(surface);
        window.setNavigationBarColor(ContextCompat.getColor(activity, android.R.color.transparent));

        boolean night = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (night) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }
}

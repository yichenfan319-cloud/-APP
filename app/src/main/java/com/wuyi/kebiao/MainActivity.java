package com.wuyi.kebiao;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.fragment.NavHostFragment;

/**
 * 主容器。
 *
 * 底部导航栏已移除：整个首页（顶栏、控制面板、课表展示区）由
 * assets/www/index.html 渲染，个人页改由 SettingsActivity 承载。
 * 因此这里只需要托管一个 NavHostFragment。
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 导航图由布局里的 app:navGraph 自动加载，这里只确保它可用。
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            navHostFragment.getNavController();
        }

        // 启动时自动检查更新。三道闸门都在 VersionChecker.autoCheck 里：
        // 设置开关、是否已忽略该版本、请求是否成功。失败静默，不打扰用户。
        // 放在首帧之后再查，避免和启动动画抢时间。
        getWindow().getDecorView().postDelayed(
                () -> VersionChecker.autoCheck(MainActivity.this), 1500);
    }
}

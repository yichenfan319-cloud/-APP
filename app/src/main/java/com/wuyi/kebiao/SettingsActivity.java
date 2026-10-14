package com.wuyi.kebiao;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

/**
 * 设置页。
 *
 * 承接原先底部导航「我的」的内容（ProfileFragment），
 * 由首页顶栏右上角的齿轮图标通过 FrontBridge.openSettings() 打开。
 *
 * 之所以用独立 Activity 而不是在同一页切换 Fragment：ProfileFragment 里有
 * 版本检查、邮件反馈、外链跳转等逻辑，独立成页改动最小，返回键行为也自然。
 *
 * 注意：本应用主题继承 Theme.Material3.DayNight.NoActionBar，
 * 所以不能用 setSupportActionBar，直接用 MaterialToolbar 的 navigationIcon。
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new ProfileFragment())
                    .commit();
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }
}

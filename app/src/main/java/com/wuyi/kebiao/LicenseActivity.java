package com.wuyi.kebiao;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 开源许可页。
 *
 * 由设置页「开源许可」进入。清单只列 gradle/libs.versions.toml 里
 * 实际引入的依赖 —— 把没用上的库也列出来是误导，不是"更完整"。
 *
 * 版本号与 pom 一致：
 *   androidx.appcompat:appcompat:1.6.1
 *   com.google.android.material:material:1.12.0
 *   androidx.constraintlayout:constraintlayout:2.1.4
 *   androidx.navigation:navigation-{fragment,ui}:2.7.7
 *   androidx.recyclerview:recyclerview:1.3.2
 *   com.squareup.okhttp3:okhttp:4.12.0
 *   com.google.code.gson:gson:2.10.1
 *
 * 这些都是 Apache License 2.0。严格合规还应随包附上许可证全文与 NOTICE，
 * 这里只列出名称与协议 —— 如需更完整，可再加一个「查看全文」的二级页。
 */
public class LicenseActivity extends AppCompatActivity {

    private static final String THIRD_PARTY = "Apache License 2.0";
    private static final String MIT = "MIT License";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_license);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView list = findViewById(R.id.lic_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        // 外层是 ScrollView，让 RecyclerView 不自己滚动，整体一起滚
        list.setNestedScrollingEnabled(false);
        list.setAdapter(new LicenseAdapter(buildEntries()));

        findViewById(R.id.layout_full_license).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle("MIT License")
                        .setMessage(MIT_TEXT)
                        .setPositiveButton("关闭", null)
                        .show());
    }

    private List<LicenseEntry> buildEntries() {
        List<LicenseEntry> items = new ArrayList<>();

        items.add(LicenseEntry.header("本项目"));
        items.add(new LicenseEntry(
                "KipKip",
                VersionChecker.currentVersionName(this),
                MIT,
                "Copyright (c) 2026 坏比小蜘蛛"));

        items.add(LicenseEntry.header("第三方组件"));

        items.add(new LicenseEntry("AndroidX AppCompat", "1.6.1", THIRD_PARTY,
                "Copyright (C) The Android Open Source Project"));
        items.add(new LicenseEntry("AndroidX ConstraintLayout", "2.1.4", THIRD_PARTY,
                "Copyright (C) The Android Open Source Project"));
        items.add(new LicenseEntry("AndroidX Navigation", "2.7.7", THIRD_PARTY,
                "Copyright (C) The Android Open Source Project"));
        items.add(new LicenseEntry("AndroidX RecyclerView", "1.3.2", THIRD_PARTY,
                "Copyright (C) The Android Open Source Project"));
        items.add(new LicenseEntry("Material Components for Android", "1.12.0", THIRD_PARTY,
                "Copyright (C) The Android Open Source Project"));
        items.add(new LicenseEntry("OkHttp", "4.12.0", THIRD_PARTY,
                "Copyright (C) Square, Inc."));
        items.add(new LicenseEntry("Gson", "2.10.1", THIRD_PARTY,
                "Copyright (C) Google Inc."));

        return items;
    }

    /**
     * 项目自身使用的 MIT 许可全文。
     *
     * 与仓库根目录的 LICENSE 保持一致。版权署名以仓库里的为准：
     * Copyright (c) 2026 坏比小蜘蛛
     */
    private static final String MIT_TEXT =
            "Copyright (c) 2026 坏比小蜘蛛\n\n"
                    + "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
                    + "of this software and associated documentation files (the \"Software\"), to deal\n"
                    + "in the Software without restriction, including without limitation the rights\n"
                    + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
                    + "copies of the Software, and to permit persons to whom the Software is\n"
                    + "furnished to do so, subject to the following conditions:\n\n"
                    + "The above copyright notice and this permission notice shall be included in all\n"
                    + "copies or substantial portions of the Software.\n\n"
                    + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
                    + "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
                    + "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
                    + "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
                    + "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
                    + "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n"
                    + "SOFTWARE.";
}

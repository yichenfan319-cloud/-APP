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
 * 清单口径：只列**真的进了安装包**的库。
 * 核对方式是 {@code :app:dependencies --configuration releaseRuntimeClasspath}
 * （注意看箭头右侧的最终版本，左边是被 Gradle 抬掉的旧请求版本）。
 *
 * 2026-11-21 核对结果（release 包共 160 个 artifact，归并成下面这些条目）：
 *   - 直接依赖：appcompat 1.6.1 / material 1.12.0 / constraintlayout 2.1.4 /
 *     recyclerview 1.3.2 / navigation-{fragment,ui} 2.7.7 / okhttp 4.12.0 /
 *     gson 2.10.1 / jsoup 1.17.2 / backdrop-android 2.0.1 / shapes 1.2.1 /
 *     compose ui & foundation 1.12.0
 *   - 传递：androidx.core 1.16.0、activity 1.8.0、fragment 1.6.2、lifecycle 2.9.4、
 *     savedstate 1.4.0、collection 1.5.0、annotation 1.9.1、window 1.5.0、
 *     emoji2 1.4.0、profileinstaller 1.4.0、transition 1.5.0、tracing 1.2.0、
 *     startup 1.1.1、arch.core 2.2.0、okio 3.6.0、kotlin-stdlib 2.4.21、
 *     kotlinx-coroutines 1.9.0、kotlinx-serialization-core 1.7.3、
 *     guava listenablefuture 1.0、error_prone_annotations 2.15.0、
 *     jetbrains annotations 26.1.0、jspecify 1.0.0
 *   - 其余是 AndroidX 内部的零碎模块（cardview、coordinatorlayout、drawerlayout、
 *     vectordrawable、viewpager 等），协议全部是 Apache-2.0，归并为一条"其他 AndroidX 模块"。
 *
 * 协议分布：**只有 jsoup 是 MIT（BSD 风格），本项目自己是 MIT，其余全部 Apache License 2.0**。
 * 严格合规还应随包附上 Apache-2.0 的 NOTICE 文本，这里给出名称/版本/协议/版权行，
 * 并在页面底部提供本项目 MIT 全文。
 */
public class LicenseActivity extends AppCompatActivity {

    private static final String APACHE = "Apache License 2.0";
    private static final String MIT = "MIT License";
    private static final String AOSP = "Copyright (C) The Android Open Source Project";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBars.apply(this);
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

        items.add(LicenseEntry.header("界面与组件"));
        items.add(new LicenseEntry("AndroidX AppCompat", "1.6.1", APACHE, AOSP));
        items.add(new LicenseEntry("Material Components for Android", "1.12.0", APACHE, AOSP));
        items.add(new LicenseEntry("AndroidX ConstraintLayout", "2.1.4", APACHE, AOSP));
        items.add(new LicenseEntry("AndroidX RecyclerView", "1.3.2", APACHE, AOSP));
        items.add(new LicenseEntry("AndroidX Navigation", "2.7.7", APACHE, AOSP));
        items.add(new LicenseEntry("AndroidX Core / Activity / Fragment", "1.16.0 / 1.8.0 / 1.6.2",
                APACHE, AOSP));
        items.add(new LicenseEntry("AndroidX Lifecycle", "2.9.4", APACHE, AOSP));
        items.add(new LicenseEntry("AndroidX SavedState", "1.4.0", APACHE, AOSP));
        items.add(new LicenseEntry("AndroidX Collection", "1.5.0", APACHE, AOSP));
        items.add(new LicenseEntry("AndroidX Annotation", "1.9.1", APACHE, AOSP));
        items.add(new LicenseEntry("AndroidX Window", "1.5.0", APACHE, AOSP));
        items.add(new LicenseEntry("AndroidX Emoji2", "1.4.0", APACHE, AOSP));
        items.add(new LicenseEntry("AndroidX ProfileInstaller", "1.4.0", APACHE, AOSP));
        items.add(new LicenseEntry("AndroidX Transition / Tracing / Startup",
                "1.5.0 / 1.2.0 / 1.1.1", APACHE, AOSP));
        items.add(new LicenseEntry("其他 AndroidX 模块",
                "cardview、coordinatorlayout、drawerlayout 等", APACHE, AOSP));

        items.add(LicenseEntry.header("Compose 与液态玻璃"));
        items.add(new LicenseEntry("Compose UI / Foundation", "1.12.0", APACHE, AOSP));
        items.add(new LicenseEntry("Compose Runtime / Animation", "1.12.0", APACHE, AOSP));
        items.add(new LicenseEntry("Kyant0 Backdrop（液态玻璃）", "2.0.1", APACHE,
                "Copyright 2025-2026 kyant0"));
        items.add(new LicenseEntry("Kyant0 Shapes", "1.2.1", APACHE,
                "Copyright 2025-2026 kyant0"));

        items.add(LicenseEntry.header("语言运行时"));
        items.add(new LicenseEntry("Kotlin Standard Library", "2.4.21", APACHE,
                "Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors"));
        items.add(new LicenseEntry("kotlinx.coroutines", "1.9.0", APACHE,
                "Copyright 2016-2026 JetBrains s.r.o."));
        items.add(new LicenseEntry("kotlinx.serialization", "1.7.3", APACHE,
                "Copyright 2017-2026 JetBrains s.r.o."));
        items.add(new LicenseEntry("JetBrains Annotations", "26.1.0", APACHE,
                "Copyright 2000-2026 JetBrains s.r.o."));

        items.add(LicenseEntry.header("网络与解析"));
        items.add(new LicenseEntry("OkHttp", "4.12.0", APACHE, "Copyright (C) Square, Inc."));
        items.add(new LicenseEntry("Okio", "3.6.0", APACHE, "Copyright (C) Square, Inc."));
        items.add(new LicenseEntry("Gson", "2.10.1", APACHE, "Copyright (C) Google Inc."));
        items.add(new LicenseEntry("jsoup", "1.17.2", MIT,
                "Copyright (c) Jonathan Hedley (jsoup.org)"));

        items.add(LicenseEntry.header("其他传递依赖"));
        items.add(new LicenseEntry("Guava ListenableFuture", "1.0", APACHE,
                "Copyright (C) The Guava Authors"));
        items.add(new LicenseEntry("Error Prone Annotations", "2.15.0", APACHE,
                "Copyright 2014 The Error Prone Authors"));
        items.add(new LicenseEntry("JSpecify", "1.0.0", APACHE,
                "Copyright 2024 The JSpecify Authors"));

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

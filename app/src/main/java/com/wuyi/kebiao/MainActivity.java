package com.wuyi.kebiao;

import android.graphics.Color;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.color.MaterialColors;

/**
 * 主容器：三个页面 + 一条浮在内容上的玻璃导航。
 *
 * <p>没有用 Navigation 组件 —— 三页之间没有返回栈，也不需要深链接，
 * 用 FragmentTransaction 的 show/hide 更直接：页面状态（滚动位置、选中周次）
 * 天然保留，不会像 replace 那样每次切回来都重建。
 *
 * <p>导航栏整条交给 Compose（{@link GlassNavKt}）：胶囊底色、三个标签、
 * 以及那颗会停靠、能跟手拖动的液态玻璃珠都在那边画。这里只做两件事：
 * 把"当前第几页"喂给状态桥，以及接收"用户选了第几页"的回调。
 */
public class MainActivity extends AppCompatActivity {

    private static final int PAGE_TODAY = 0;
    private static final int PAGE_WEEK = 1;
    private static final int PAGE_ACCOUNT = 2;

    /** Fragment 的 tag 前缀，重建时靠它把页面捡回来（不要改，改了会让热更新后错位）。 */
    private static final String TAG_PAGE = "page";

    private static final String KEY_PAGE = "kip_current_page";

    private final Fragment[] pages = new Fragment[3];
    private int currentPage = PAGE_TODAY;

    private GlassNavController navController;

    private ScheduleEngine engine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SystemBars.apply(this);

        // 切白天/黑夜会让 Activity 重建（uiMode 变化）。重建时 FragmentManager 会把
        // 之前 add() 过的三个页面原样恢复，如果这里再 new 一遍就会叠出两套 Fragment：
        // 新的盖在上面，旧的那套仍可见 —— 表现就是"切页之后上一页的内容还留在下面"。
        // 所以有 savedInstanceState 时按 tag 把旧的捡回来，别再新建。
        if (savedInstanceState != null) {
            currentPage = savedInstanceState.getInt(KEY_PAGE, PAGE_TODAY);
            for (int i = 0; i < pages.length; i++) {
                pages[i] = getSupportFragmentManager().findFragmentByTag(TAG_PAGE + i);
            }
        }

        setupNav();
        showPage(currentPage);

        engine = new ScheduleEngine(this, new ScheduleEngine.Callback() {
            @Override
            public void onState(ThinkingOrbView.State state, String detail) {
                ScheduleStore.get().setState(state, detail);
            }

            @Override
            public void onSnapshot(TimetableRepository.Snapshot snapshot) {
                ScheduleStore.get().setSnapshot(snapshot);
            }

            @Override
            public void onLog(String message) {
                // 新界面没有日志区了，日志留给 logcat。
                // 用 Log.i 而不是 Log.d：release 包上 Log.d 常被裁掉/不输出，
                // 之前排查"课表显示错天"时就因为这条日志看不见而多绕了一圈。
                android.util.Log.i("KipKip", message);
                // 同时留一份在内存里，设置页「问题反馈」能一键导出带走
                DiagLog.add(message);
            }

            @Override
            public void onError(String message) {
                android.util.Log.w("KipKip", message);
                DiagLog.add("错误: " + message);
            }
        });
        engine.attach(findViewById(R.id.engine_host));

        // 有存过的账号就自动跑一次，省得每次打开都手动点
        String savedUser = Prefs.get(this).getString(Prefs.KEY_USERNAME, null);
        if (savedUser != null && !savedUser.isEmpty()) {
            engine.refresh();
        } else {
            ScheduleStore.get().setState(ThinkingOrbView.State.IDLE,
                    getString(R.string.status_detail_idle));
        }

        getWindow().getDecorView().postDelayed(
                () -> VersionChecker.autoCheck(MainActivity.this), 1500);

        boolean nightNow = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        DiagLog.add("主界面已创建（" + VersionChecker.currentVersionName(this)
                + (nightNow ? "，深色" : "，浅色") + "）");
    }

    // ------------------------------------------------------------ 导航

    private void setupNav() {
        ComposeView host = findViewById(R.id.nav_compose);

        String[] labels = {
                getString(R.string.nav_today),
                getString(R.string.nav_week),
                getString(R.string.nav_account)
        };

        // colorPrimary 是 AppCompat 声明的属性，不在本工程的 R 里
        int selected = MaterialColors.getColor(host, androidx.appcompat.R.attr.colorPrimary, Color.BLACK);
        int normal = MaterialColors.getColor(host, R.attr.colorTextHint, Color.GRAY);
        int bar = MaterialColors.getColor(host,
                com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF1E1E1E);
        // 90% 不透明：既能透出一点底下内容，又不会让图标文字糊掉
        int barColor = (bar & 0x00FFFFFF) | 0xE6000000;

        // 描边高光要跟底色反着来：深色底用白边才显得"受光"，
        // 浅色底用白边等于没画（白压白），换成一道深色细线才有轮廓。
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int rimColor = night ? 0x4DFFFFFF : 0x33000000;

        NavPalette palette = new NavPalette(selected, normal, barColor, rimColor);

        navController = new GlassNavController(labels, page -> {
            showPage(page);
            return kotlin.Unit.INSTANCE;
        });
        navController.setSelected(currentPage);
        navController.setGlassEnabled(glassOn());

        GlassNavKt.installGlassNav(host, navController, palette);
    }

    private void showPage(int page) {
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        for (int i = 0; i < 3; i++) {
            Fragment f = pages[i];
            if (i == page) {
                if (f == null) {
                    f = createPage(i);
                    pages[i] = f;
                    tx.add(R.id.page_container, f, TAG_PAGE + i);
                } else {
                    tx.show(f);
                }
            } else if (f != null) {
                tx.hide(f);
            }
        }
        tx.commitAllowingStateLoss();
        currentPage = page;
        if (navController != null) {
            navController.setSelected(page);
        }
    }

    private Fragment createPage(int page) {
        switch (page) {
            case PAGE_WEEK:
                return new WeekFragment();
            case PAGE_ACCOUNT:
                return new AccountFragment();
            case PAGE_TODAY:
            default:
                return new TodayFragment();
        }
    }

    private boolean glassOn() {
        return Prefs.get(this).getBoolean(Prefs.KEY_GLASS, true);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_PAGE, currentPage);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页改完开关回到这里，按最新偏好重刷一次观感
        if (navController != null) {
            navController.setGlassEnabled(glassOn());
        }
    }

    /** 有取数任务时把球点亮成 searching，返回键之类的场景还没接，先只暴露给子页面。 */
    public void refreshSchedule() {
        if (engine != null && !engine.isRunning()) {
            engine.refresh();
        }
    }

    public void verifyAccount(@NonNull String user, @NonNull String pass) {
        if (engine != null) {
            engine.start(user, pass);
        }
    }

    /** 周次页切到没取过的那一周时，让引擎去要那一周的真实课表。 */
    public void loadWeek(int week) {
        if (engine != null) {
            engine.loadWeek(week);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (engine != null) {
            engine.destroy();
            engine = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (currentPage != PAGE_TODAY) {
            showPage(PAGE_TODAY);
        } else {
            super.onBackPressed();
        }
    }
}

package com.wuyi.kebiao;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.example.liquidglass.DisplacementMode;
import com.example.liquidglass.GlassMaterial;
import com.example.liquidglass.LiquidGlassView;
import com.google.android.material.color.MaterialColors;

/**
 * 主容器：三个页面 + 一条浮在内容上的玻璃导航。
 *
 * <p>没有用 Navigation 组件 —— 三页之间没有返回栈，也不需要深链接，
 * 用 FragmentTransaction 的 show/hide 更直接：页面状态（滚动位置、选中周次）
 * 天然保留，不会像 replace 那样每次切回来都重建。
 */
public class MainActivity extends AppCompatActivity {

    private static final int PAGE_TODAY = 0;
    private static final int PAGE_WEEK = 1;
    private static final int PAGE_ACCOUNT = 2;

    private static final int[] ICONS = {
            R.drawable.ic_nav_today, R.drawable.ic_nav_week, R.drawable.ic_nav_account
    };
    private static final int[] LABELS = {
            R.string.nav_today, R.string.nav_week, R.string.nav_account
    };

    private final Fragment[] pages = new Fragment[3];
    private View[] navItems;
    private int currentPage = PAGE_TODAY;

    private FrameLayout glassNav;
    private LiquidGlassView glassLens;
    private View navScrubber;
    private LinearLayout navItemsHost;
    private int lensW;
    private int lensH;
    private boolean lensDragging;

    private ScheduleEngine engine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        applySystemBars();
        buildNav();
        showPage(PAGE_TODAY);
        setupLens();

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
                // 新界面没有日志区了，这里只把最近一步写进状态栏，日志留给 logcat
                android.util.Log.d("KipKip", message);
            }

            @Override
            public void onError(String message) {
                android.util.Log.w("KipKip", message);
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
    }

    // ------------------------------------------------------------ 导航

    private void buildNav() {
        LinearLayout host = findViewById(R.id.nav_items);
        navItems = new View[3];
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < 3; i++) {
            View item = inflater.inflate(R.layout.view_nav_item, host, false);
            ((ImageView) item.findViewById(R.id.nav_item_icon)).setImageResource(ICONS[i]);
            ((TextView) item.findViewById(R.id.nav_item_label)).setText(LABELS[i]);
            final int page = i;
            item.setOnClickListener(v -> showPage(page));
            host.addView(item);
            navItems[i] = item;
        }
    }

    private void showPage(int page) {
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        for (int i = 0; i < 3; i++) {
            Fragment f = pages[i];
            if (i == page) {
                if (f == null) {
                    f = createPage(i);
                    pages[i] = f;
                    tx.add(R.id.page_container, f, "page" + i);
                } else {
                    tx.show(f);
                }
            } else if (f != null) {
                tx.hide(f);
            }
        }
        tx.commitAllowingStateLoss();
        currentPage = page;
        updateNavTint();
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

    private void updateNavTint() {
        // colorPrimary 是 AppCompat 声明的属性，不在本工程的 R 里
        int primaryAttr = androidx.appcompat.R.attr.colorPrimary;
        int on = MaterialColors.getColor(navItems[currentPage], primaryAttr, Color.BLACK);
        int off = MaterialColors.getColor(navItems[currentPage], R.attr.colorTextHint, Color.GRAY);
        for (int i = 0; i < navItems.length; i++) {
            View item = navItems[i];
            ImageView icon = item.findViewById(R.id.nav_item_icon);
            TextView label = item.findViewById(R.id.nav_item_label);
            int color = i == currentPage ? on : off;
            Drawable d = AppCompatResources.getDrawable(this, ICONS[i]);
            if (d != null) {
                d = DrawableCompat.wrap(d.mutate());
                DrawableCompat.setTint(d, color);
                icon.setImageDrawable(d);
            }
            label.setTextColor(color);
            item.setSelected(i == currentPage);
        }
    }

    // ------------------------------------------------------------ 液态玻璃

    /**
     * 装好"跟随手指的透镜"。
     *
     * <p>关键是把 {@code backdropSource} 指向导航栏自己的内容层：这个库的玻璃
     * 本质是"采样一块指定区域 + 按形状算折射位移"，所以透镜滑到哪里，就把
     * 那里背后的图标文字折射放大，效果和 iOS 26 那种放大镜一致。
     *
     * <p>手势层是盖在导航栏上的透明 View，不抢原有点击：轻点（位移小于
     * touchSlop）时手动 performClick 到对应项，拖动时只移动透镜。
     */
    private void setupLens() {
        glassNav = findViewById(R.id.glass_nav);
        glassLens = findViewById(R.id.glass_lens);
        navScrubber = findViewById(R.id.nav_scrubber);
        navItemsHost = findViewById(R.id.nav_items);
        lensW = Math.round(dp(108));
        lensH = Math.round(dp(84));

        // 底栏退成半透明实心胶囊（背景色由代码给，避免 shape 里写 ?attr 在 API21 上不稳）
        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setCornerRadius(dp(32));
        int base = MaterialColors.getColor(navItemsHost,
                com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF1E1E1E);
        pill.setColor((base & 0x00FFFFFF) | 0xE6000000);   // 90% 不透明
        pill.setStroke(Math.round(dp(1)),
                (MaterialColors.getColor(navItemsHost, com.google.android.material.R.attr.colorOutlineVariant,
                        0xFF3A3A3A) & 0x00FFFFFF) | 0x66000000);
        glassNav.setBackground(pill);

        // 这颗珠子是全工程唯一的液态玻璃实例：采样真实窗口像素，
        // 所以手指滑到哪儿，哪儿底栏的图标文字就被折射弯掉。
        glassLens.setMaterial(GlassMaterial.CLEAR);
        glassLens.setDisplacementMode(DisplacementMode.STANDARD);
        glassLens.setAdaptiveLensScale(true);
        glassLens.setDisplacementScale(1.0f);
        glassLens.setRefractionHeight(dp(12));
        glassLens.setBevelWidth(dp(20));
        glassLens.setEdgeSoftness(dp(1.5f));
        glassLens.setEnableBackdropBlur(false);
        glassLens.setEnableChromaticDispersion(true);
        glassLens.setDispersionStrength(0.12f);
        glassLens.setEnableEdgeHighlight(true);
        glassLens.setEdgeHighlightOpacity(0.7f);
        glassLens.setEnableShadow(false);
        // 之前像"果冻一弹一弹"就是这两项：弹性形变 + 按压缩放。全关。
        glassLens.setElasticity(0f);
        glassLens.setEnablePressEffect(false);
        glassLens.setEnableSensorHighlight(false);
        glassLens.setCornerRadius(lensH / 2f);
        glassLens.setBackdropSource(null);
        // 珠子要跟手移动，必须每帧重采，否则背景冻在第一帧
        glassLens.setEnableDynamicBackground(true);

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        navScrubber.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            boolean moved;
            final java.util.ArrayList<Integer> touched = new java.util.ArrayList<>();

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        moved = false;
                        touched.clear();
                        if (glassOn()) {
                            focusLens(v, downX);
                            glassLens.setVisibility(View.VISIBLE);
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!moved && Math.abs(e.getX() - downX) > slop) {
                            moved = true;
                        }
                        if (moved && glassOn()) {
                            focusLens(v, e.getX());
                            // 手指扫过的每一项都记下来，抬手时切成最后经过的那一项
                            int idx = navIndexAt(v, e.getX());
                            if (idx >= 0 && (touched.isEmpty() || touched.get(touched.size() - 1) != idx)) {
                                touched.add(idx);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        // 拖到哪里，就消失在哪里，并跳到手指最后停的那一项
                        if (glassOn()) {
                            focusLens(v, e.getX());
                        }
                        glassLens.setVisibility(View.INVISIBLE);
                        if (moved) {
                            if (!touched.isEmpty()) {
                                selectNav(touched.get(touched.size() - 1));
                            }
                        } else {
                            clickNavAt(v, e.getX());
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        glassLens.setVisibility(View.INVISIBLE);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 珠子跟着手指走，边界处只让露出的部分收一点。 */
    private void focusLens(View scrubber, float xInScrubber) {
        float half = lensW / 2f;
        float cx = xInScrubber;
        float min = half * 0.62f;
        float max = scrubber.getWidth() - half * 0.62f;
        if (cx < min) {
            cx = min;
        }
        if (cx > max) {
            cx = max;
        }
        glassLens.setX(scrubber.getLeft() + cx - half);
    }

    /** x 落在第几项上。超出范围按最近的一端算。 */
    private int navIndexAt(View scrubber, float x) {
        if (navItems == null || navItems.length == 0 || scrubber.getWidth() <= 0) {
            return -1;
        }
        int idx = (int) (x * navItems.length / scrubber.getWidth());
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= navItems.length) {
            idx = navItems.length - 1;
        }
        return idx;
    }

    private void selectNav(int idx) {
        if (idx < 0 || idx >= 3 || idx == currentPage) {
            return;
        }
        showPage(idx);
    }

    private void clickNavAt(View scrubber, float x) {
        if (navItems == null || navItems.length == 0) {
            return;
        }
        int w = scrubber.getWidth();
        if (w <= 0) {
            return;
        }
        int idx = (int) (x * navItems.length / w);
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= navItems.length) {
            idx = navItems.length - 1;
        }
        navItems[idx].performClick();
    }

    private boolean glassOn() {
        return Prefs.get(this).getBoolean(Prefs.KEY_GLASS, true);
    }

    private void applyGlassPref() {
        if (glassNav == null) {
            return;
        }
        // 底栏现在是普通胶囊，开关只影响"拖动时跟手那颗玻璃珠"显不显示。
        // 关掉后仍然能拖动切页，只是没有珠子。
        if (!glassOn()) {
            glassLens.setVisibility(View.INVISIBLE);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页改完开关回到这里，按最新偏好重刷一次观感
        applyGlassPref();
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

    // ------------------------------------------------------------ 系统栏

    /**
     * 状态栏跟着页面底色走，图标在浅色底上用深色。
     *
     * <p>只在主界面改，不动 themes.xml —— 设置页和开源许可页还是品牌蓝的
     * 状态栏，那边有蓝色 AppBar，两边需求不一样。
     */
    private void applySystemBars() {
        SystemBars.apply(this);
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

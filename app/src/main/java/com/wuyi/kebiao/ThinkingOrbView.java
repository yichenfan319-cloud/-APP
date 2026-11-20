package com.wuyi.kebiao;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 会思考的点阵球。
 *
 * 参考 thinking-orbs（libraries.dev）与 dissolve 的 thinking-orb：那颗球是
 * <b>180 个点铺在球面上</b>，靠位移、明暗和一道扫描经线表达"我正在干什么"。
 * 原实现是 React 组件，但它的 README 明确写了 —— 纯 2D canvas、没有 WebGL、
 * 没有滤镜。也就是说它本质上是"球面点 → 投影到平面 → 画圆"，这套数学在
 * Android 的 Canvas 上原样成立，所以这里用自定义 View 重写，而不是嵌入网页。
 *
 * 状态切换的行为也照原样：<b>每个点各自用 600ms 走完，并带上错峰</b>，
 * 所以看起来是"点阵被重新编排"，而不是整颗球淡出再淡入。
 *
 * 五个状态：
 *   IDLE      呼吸，球面轻微起伏
 *   THINKING  点沿倾斜轨道绕着转
 *   SEARCHING 一道经线扫过球面，扫到的点提亮并外推
 *   DONE      收拢成紧实的球，成功色
 *   ERROR     抖动并轻微炸开，危险色
 */
public class ThinkingOrbView extends View {

    public enum State {IDLE, THINKING, SEARCHING, DONE, ERROR}

    /** 点数，对齐原实现的默认值。 */
    private static final int DOT_COUNT = 180;

    /** 状态切换的总时长（含错峰）。原实现是 600ms。 */
    private static final long MORPH_MS = 600L;

    /** 错峰占用的时间：最后一个点比第一个点晚这么多开始。 */
    private static final long STAGGER_MS = 200L;

    private static final TimeInterpolator EASE = new DecelerateInterpolator(1.6f);

    /** 手指推开点的半径，原实现是 40pt。 */
    private static final float FLEE_RADIUS_DP = 40f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    // ---- 球面基点：每个状态一套，切换时在这两套之间插值 ----
    private final float[][] sphereX = new float[State.values().length][DOT_COUNT];
    private final float[][] sphereY = new float[State.values().length][DOT_COUNT];
    private final float[][] sphereZ = new float[State.values().length][DOT_COUNT];

    private State state = State.IDLE;
    private State fromState = State.IDLE;
    private long morphStart = 0L;          // 0 表示没有正在进行的切换

    // ---- 每帧的临时数组，避免在 onDraw 里反复分配 ----
    private final float[] px = new float[DOT_COUNT];
    private final float[] py = new float[DOT_COUNT];
    private final float[] depth = new float[DOT_COUNT];

    // ---- 交互：指尖推开 + 回弹 ----
    private boolean interactive = false;
    private float touchX = Float.NaN, touchY = Float.NaN;
    private final float[] fleeX = new float[DOT_COUNT];
    private final float[] fleeY = new float[DOT_COUNT];
    private final float[] fleeVX = new float[DOT_COUNT];
    private final float[] fleeVY = new float[DOT_COUNT];

    // ---- 主题色 ----
    private int inkColor = Color.WHITE;
    private int doneColor = 0xFF2E9E5B;
    private int errorColor = 0xFFE5484D;

    private long startAt = SystemClock.uptimeMillis();
    private float dotRadiusPx;
    private float fleeRadiusPx;

    public ThinkingOrbView(Context context) {
        this(context, null);
    }

    public ThinkingOrbView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ThinkingOrbView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float d = getResources().getDisplayMetrics().density;
        dotRadiusPx = 2.5f * d * 0.5f;   // 2.5pt 直径 → 半径
        fleeRadiusPx = FLEE_RADIUS_DP * d;

        buildSpheres();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(inkColor);
    }

    /**
     * 用斐波那契球（黄金角）铺点：这是把 N 个点均匀铺到球面上最稳的办法，
     * 没有极点堆积，也不需要随机数。
     *
     * 每个状态给一个不同的黄金角起点和倾角 —— 这样切换时每个点都有"新位置"
     * 可走，视觉上就是点阵被重新编排，而不是原地闪烁。
     */
    private void buildSpheres() {
        final float goldenAngle = (float) (Math.PI * (3 - Math.sqrt(5)));
        State[] all = State.values();
        for (int s = 0; s < all.length; s++) {
            float phase = s * 1.13f;          // 每个状态的起始角不同
            float tilt = (s % 3) * 0.22f;     // 每个状态的倾角不同
            float ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);
            for (int i = 0; i < DOT_COUNT; i++) {
                float y = 1f - (i / (float) (DOT_COUNT - 1)) * 2f;
                float r = (float) Math.sqrt(Math.max(0f, 1f - y * y));
                float theta = i * goldenAngle + phase;
                float x = (float) Math.cos(theta) * r;
                float z = (float) Math.sin(theta) * r;
                // 绕 X 轴倾斜一下，让球有不同的"看面"
                float ty = y * ct - z * st;
                float tz = y * st + z * ct;
                sphereX[s][i] = x;
                sphereY[s][i] = ty;
                sphereZ[s][i] = tz;
            }
        }
    }

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    public void setState(State next) {
        if (next == null || next == state) return;
        fromState = state;
        state = next;
        morphStart = SystemClock.uptimeMillis();
        // 切到终态时给一次触感：完成用确认，出错用警告
        if (next == State.DONE) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        } else if (next == State.ERROR) {
            performHapticFeedback(HapticFeedbackConstants.REJECT);
        }
        invalidate();
    }

    public State getState() {
        return state;
    }

    public void setInteractive(boolean enabled) {
        this.interactive = enabled;
        if (!enabled) {
            touchX = touchY = Float.NaN;
        }
    }

    /** 正常状态的点色，默认跟随主题文字色。 */
    public void setInkColor(int color) {
        this.inkColor = color;
        invalidate();
    }

    public void setDoneColor(int color) {
        this.doneColor = color;
        invalidate();
    }

    public void setErrorColor(int color) {
        this.errorColor = color;
        invalidate();
    }

    public void setDotRadiusDp(float dp) {
        this.dotRadiusPx = dp * getResources().getDisplayMetrics().density;
        invalidate();
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!interactive) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                touchX = event.getX();
                touchY = event.getY();
                postInvalidateOnAnimation();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchX = touchY = Float.NaN;
                postInvalidateOnAnimation();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        bounds.set(0, 0, getWidth(), getHeight());
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float radius = Math.min(bounds.width(), bounds.height()) * 0.5f * 0.86f;

        long now = SystemClock.uptimeMillis();
        float t = (now - startAt) / 1000f;                 // 秒

        float morph = 1f;
        if (morphStart > 0L) {
            morph = Math.min(1f, (now - morphStart) / (float) MORPH_MS);
            if (morph >= 1f) {
                morphStart = 0L;
                fromState = state;
            }
        }

        // 状态自身的持续运动
        boolean animating = state != State.IDLE || morphStart != 0L;
        boolean fleeActive = stepFlee(now);

        // 整球的旋转：每个状态速度不同
        float spin = t * spinSpeed(state);
        float sinS = (float) Math.sin(spin), cosS = (float) Math.cos(spin);

        float breathe = 1f;
        if (state == State.IDLE) {
            breathe = 1f + 0.035f * (float) Math.sin(t * 1.6f);
        } else if (state == State.DONE) {
            // done 收拢得更紧
            breathe = 0.93f;
        } else if (state == State.ERROR) {
            breathe = 1.06f;
        }

        float scanAngle = (t * 2.1f) % ((float) Math.PI * 2f);

        int ink = inkColor;
        if (state == State.DONE) ink = doneColor;
        else if (state == State.ERROR) ink = errorColor;

        for (int i = 0; i < DOT_COUNT; i++) {
            // 1) 球面位置：从 fromState 插值到 state，带错峰
            float delay = (i / (float) DOT_COUNT) * (STAGGER_MS / (float) MORPH_MS);
            float local = clamp01((morph - delay) / (1f - delay));
            float e = EASE.getInterpolation(local);

            float x = lerp(sphereX[fromState.ordinal()][i], sphereX[state.ordinal()][i], e);
            float y = lerp(sphereY[fromState.ordinal()][i], sphereY[state.ordinal()][i], e);
            float z = lerp(sphereZ[fromState.ordinal()][i], sphereZ[state.ordinal()][i], e);

            // 2) 状态自身的形变
            float extraAlpha = 1f;
            float extraScale = 1f;

            switch (state) {
                case THINKING: {
                    // 沿倾斜轨道绕行：在 XZ 平面上再转一圈，速度更快
                    float a = t * 1.9f + i * 0.37f;
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    float nx = x * ca - z * sa;
                    float nz = x * sa + z * ca;
                    x = nx;
                    z = nz;
                    break;
                }
                case SEARCHING: {
                    // 一道经线扫过：经度接近扫描角的点被推出并提亮
                    float lon = (float) Math.atan2(z, x);
                    float d = Math.abs(angleDelta(lon, scanAngle));
                    if (d < 0.42f) {
                        float w = 1f - d / 0.42f;
                        float push = 1f + 0.16f * w * w;
                        x *= push;
                        y *= push;
                        z *= push;
                        extraAlpha = 0.45f + 0.55f * w;
                        extraScale = 1f + 0.5f * w;
                    } else {
                        extraAlpha = 0.55f;
                    }
                    break;
                }
                case ERROR: {
                    // 高频抖动 + 轻微炸开
                    float j = 0.055f;
                    x += (float) Math.sin(t * 31f + i * 2.1f) * j;
                    y += (float) Math.cos(t * 27f + i * 1.7f) * j;
                    break;
                }
                default:
                    break;
            }

            x *= breathe;
            y *= breathe;
            z *= breathe;

            // 3) 整球旋转（绕 Y 轴）
            float rx = x * cosS - z * sinS;
            float rz = x * sinS + z * cosS;

            // 4) 透视投影：靠前的点更大更实
            float f = 1f / (1f - rz * 0.30f);
            float sx = cx + rx * radius * f;
            float sy = cy + y * radius * f;

            px[i] = sx;
            py[i] = sy;
            depth[i] = (rz + 1f) * 0.5f;    // 0 = 最远, 1 = 最近
            dotExtra[i] = extraAlpha * extraScale;

            if (!Float.isNaN(touchX)) {
                sx += fleeX[i];
                sy += fleeY[i];
                px[i] = sx;
                py[i] = sy;
            }
        }

        // 远的先画，近的后画 —— 保证遮挡关系正确
        for (int i = 0; i < DOT_COUNT; i++) {
            order[i] = i;
            sortKey[i] = depth[i];
        }
        sortByDepth();

        for (int k = 0; k < DOT_COUNT; k++) {
            int i = order[k];
            float d = depth[i];
            float alpha = (0.20f + 0.80f * d) * dotExtra[i];
            float r = dotRadiusPx * (0.62f + 0.62f * d) * Math.min(1.8f, dotExtra[i]);
            paint.setColor(ink);
            paint.setAlpha((int) (255 * clamp01(alpha)));
            canvas.drawCircle(px[i], py[i], r, paint);
        }

        if (animating || fleeActive || morphStart != 0L) {
            postInvalidateOnAnimation();
        }
    }

    // ---- onDraw 用的临时缓冲，避免每帧 new ----
    private final float[] dotExtra = new float[DOT_COUNT];
    private final int[] order = new int[DOT_COUNT];
    private final float[] sortKey = new float[DOT_COUNT];

    /** 180 个元素，用插入排序足够（远点的数组本来就接近有序）。 */
    private void sortByDepth() {
        for (int i = 1; i < DOT_COUNT; i++) {
            int idx = order[i];
            float key = sortKey[idx];
            int j = i - 1;
            while (j >= 0 && sortKey[order[j]] > key) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = idx;
        }
    }

    /**
     * 指尖推开 + 弹簧回位。
     *
     * @return 是否还有点在动（有的话要继续请求下一帧）
     */
    private boolean stepFlee(long now) {
        if (!interactive) return false;
        boolean moving = false;
        float dt = 1f / 60f;
        for (int i = 0; i < DOT_COUNT; i++) {
            float dx = 0f, dy = 0f;
            if (!Float.isNaN(touchX)) {
                dx = px[i] - touchX;
                dy = py[i] - touchY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < fleeRadiusPx && dist > 0.01f) {
                    float force = (1f - dist / fleeRadiusPx);
                    fleeVX[i] += (dx / dist) * force * 900f * dt;
                    fleeVY[i] += (dy / dist) * force * 900f * dt;
                }
            }
            // 弹簧回位（阻尼振荡）
            fleeVX[i] += -fleeX[i] * 60f * dt;
            fleeVY[i] += -fleeY[i] * 60f * dt;
            fleeVX[i] *= 0.88f;
            fleeVY[i] *= 0.88f;
            fleeX[i] += fleeVX[i] * dt * 60f;
            fleeY[i] += fleeVY[i] * dt * 60f;

            if (Math.abs(fleeX[i]) > 0.05f || Math.abs(fleeY[i]) > 0.05f
                    || Math.abs(fleeVX[i]) > 0.05f || Math.abs(fleeVY[i]) > 0.05f) {
                moving = true;
            } else {
                fleeX[i] = fleeY[i] = fleeVX[i] = fleeVY[i] = 0f;
            }
        }
        return moving;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAt = SystemClock.uptimeMillis();
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static float spinSpeed(State s) {
        switch (s) {
            case THINKING:
                return 0.55f;
            case SEARCHING:
                return 0.32f;
            case DONE:
                return 0.08f;
            case ERROR:
                return 0.75f;
            case IDLE:
            default:
                return 0.18f;
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 角度差，结果落在 [-π, π]。 */
    private static float angleDelta(float a, float b) {
        float d = (a - b) % ((float) Math.PI * 2f);
        if (d > Math.PI) d -= (float) Math.PI * 2f;
        if (d < -Math.PI) d += (float) Math.PI * 2f;
        return d;
    }
}

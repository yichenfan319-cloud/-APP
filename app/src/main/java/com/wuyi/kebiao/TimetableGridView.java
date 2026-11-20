package com.wuyi.kebiao;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Scroller;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 周课表网格。
 *
 * <p>自己画、自己滚，而不是拼一堆 TextView：课程要跨行（一节 5-7 是三个格子高）、
 * 表头与节次列要固定不动、点击要能精确命中某一门课。这三件事在 ViewGroup 里做
 * 都要写测量和布局代码，在 Canvas 上只是一次坐标换算。
 *
 * <p>两块固定区：左边节次列、顶部星期行。滚动的只有中间的内容区，靠裁剪 + 平移实现。
 */
public class TimetableGridView extends View {

    public interface OnCourseClickListener {
        void onCourseClick(TimetableParser.Course course);
    }

    /** 一行算一节。教务系统的节次文本是 "NN~NN小节"，最大到 12 就够用。 */
    private static final int ROWS = 12;
    private static final int DAYS = 7;

    private static final String[] DAY_LABEL = {"一", "二", "三", "四", "五", "六", "日"};

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slotText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final List<TimetableParser.Course> courses = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();

    private final int softColors;
    private final int textColors;

    private final float density;
    private float headerH;
    private float timeW;
    private float colW;
    private float rowH;

    private int scrollX;
    private int scrollY;

    private final Scroller scroller;
    private VelocityTracker tracker;
    private final int touchSlop;
    private float lastX;
    private float lastY;
    private boolean dragging;

    private OnCourseClickListener listener;

    public TimetableGridView(Context c) {
        this(c, null);
    }

    public TimetableGridView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        headerH = dp(38);
        timeW = dp(30);
        colW = dp(58);
        rowH = dp(50);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.max(1f, density * 0.7f));
        linePaint.setColor(attrColor(android.R.attr.colorControlHighlight, 0x22808080));

        headerText.setTextSize(sp(11));
        headerText.setTextAlign(Paint.Align.CENTER);
        headerText.setTypeface(Typeface.DEFAULT_BOLD);
        headerText.setColor(attrColor(R.attr.colorTextPrimary, 0xFF1A2C3E));

        slotText.setTextSize(sp(10));
        slotText.setTextAlign(Paint.Align.CENTER);
        slotText.setColor(attrColor(R.attr.colorTextHint, 0xFF999999));

        blockText.setTextSize(sp(10.5f));
        blockText.setTypeface(Typeface.DEFAULT_BOLD);

        surfacePaint.setColor(attrColor(R.attr.colorSurfaceCard, 0xFFFFFFFF));

        softColors = R.color.course_soft_0;   // 用 id 起点 + 取模，避免建数组
        textColors = R.color.course_text_0;

        scroller = new Scroller(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClickable(true);
    }

    public void setOnCourseClickListener(OnCourseClickListener l) {
        listener = l;
    }

    /** 换一批课（已经是"这一周该上的"），重建命中表并回到左上角。 */
    public void setCourses(List<TimetableParser.Course> list) {
        courses.clear();
        if (list != null) {
            for (TimetableParser.Course c : list) {
                if (c != null && c.dayIndex >= 1 && c.dayIndex <= DAYS) {
                    courses.add(c);
                }
            }
        }
        scrollX = 0;
        scrollY = 0;
        rebuildHits();
        invalidate();
    }

    private void rebuildHits() {
        hits.clear();
        for (TimetableParser.Course c : courses) {
            int from = Math.max(1, Math.min(ROWS, c.sectionFrom));
            int to = Math.max(from, Math.min(ROWS, c.sectionTo));
            float left = timeW + (c.dayIndex - 1) * colW + dp(2);
            float top = headerH + (from - 1) * rowH + dp(2);
            float right = left + colW - dp(4);
            float bottom = top + (to - from + 1) * rowH - dp(4);
            hits.add(new Hit(new RectF(left, top, right, bottom), c));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clampScroll();
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // 内容区：左、上各让出一条固定带
        canvas.save();
        canvas.clipRect(timeW, headerH, w, h);
        canvas.translate(-scrollX, -scrollY);
        drawGrid(canvas);
        drawBlocks(canvas);
        canvas.restore();

        // 顶部星期行（横向跟着滚，纵向固定）
        canvas.save();
        canvas.clipRect(timeW, 0, w, headerH);
        canvas.drawRect(timeW, 0, w, headerH, surfacePaint);
        for (int d = 0; d < DAYS; d++) {
            float cx = timeW + d * colW + colW / 2f - scrollX;
            if (cx < timeW - colW || cx > w + colW) {
                continue;
            }
            canvas.drawText(DAY_LABEL[d], cx, headerH / 2f + sp(4), headerText);
        }
        canvas.restore();

        // 左边节次列（纵向跟着滚，横向固定）
        canvas.save();
        canvas.clipRect(0, headerH, timeW, h);
        canvas.drawRect(0, headerH, timeW, h, surfacePaint);
        for (int r = 1; r <= ROWS; r++) {
            float cy = headerH + (r - 1) * rowH + rowH / 2f - scrollY;
            if (cy < headerH - rowH || cy > h + rowH) {
                continue;
            }
            canvas.drawText(String.valueOf(r), timeW / 2f, cy + sp(3.5f), slotText);
        }
        canvas.restore();

        // 左上角空格
        canvas.drawRect(0, 0, timeW, headerH, surfacePaint);
    }

    private void drawGrid(Canvas canvas) {
        float contentW = DAYS * colW;
        float contentH = ROWS * rowH;
        for (int d = 0; d <= DAYS; d++) {
            float x = timeW + d * colW;
            canvas.drawLine(x, headerH, x, headerH + contentH, linePaint);
        }
        for (int r = 0; r <= ROWS; r++) {
            float y = headerH + r * rowH;
            canvas.drawLine(timeW, y, timeW + contentW, y, linePaint);
        }
    }

    private void drawBlocks(Canvas canvas) {
        for (Hit hit : hits) {
            int idx = Math.abs(hit.course.name == null ? 0 : hit.course.name.hashCode()) % 7;
            int soft = ContextCompat.getColor(getContext(), softColors + idx);
            int ink = ContextCompat.getColor(getContext(), textColors + idx);

            blockFill.setColor(soft);
            rect.set(hit.rect);
            canvas.drawRoundRect(rect, dp(8), dp(8), blockFill);

            blockText.setColor(ink);
            String name = hit.course.name == null ? "" : hit.course.name;
            // 只显示课程名（用户要求），超宽就分两行、再超就省略
            float maxW = hit.rect.width() - dp(8);
            List<String> lines = wrap(name, blockText, maxW, 2);
            float lineH = blockText.getTextSize() + dp(2);
            float startY = hit.rect.centerY() - (lines.size() - 1) * lineH / 2f + sp(3.5f);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                float tw = blockText.measureText(line);
                float lx = hit.rect.centerX() - tw / 2f;
                if (lx < hit.rect.left + dp(4)) {
                    lx = hit.rect.left + dp(4);
                }
                canvas.drawText(line, lx, startY + i * lineH, blockText);
            }
        }
    }

    /** 按字符贪心折行，最多 maxLines 行，超出用省略号。中文没有词边界，按字折最稳。 */
    private static List<String> wrap(String s, Paint p, float maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (p.measureText(line.toString() + ch) > maxW && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                if (out.size() == maxLines) {
                    String last = out.get(maxLines - 1);
                    out.set(maxLines - 1, last.substring(0, Math.max(0, last.length() - 1)) + "…");
                    return out;
                }
            }
            line.append(ch);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    // ---------------------------------------------------------------- 触摸

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                if (tracker == null) {
                    tracker = VelocityTracker.obtain();
                } else {
                    tracker.clear();
                }
                tracker.addMovement(e);
                lastX = e.getX();
                lastY = e.getY();
                dragging = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (tracker != null) {
                    tracker.addMovement(e);
                }
                float dx = lastX - e.getX();
                float dy = lastY - e.getY();
                if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragging = true;
                }
                if (dragging) {
                    scrollX += (int) dx;
                    scrollY += (int) dy;
                    clampScroll();
                    invalidate();
                }
                lastX = e.getX();
                lastY = e.getY();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) {
                    handleTap(e.getX(), e.getY());
                } else if (tracker != null) {
                    tracker.computeCurrentVelocity(1000);
                    scroller.fling(scrollX, scrollY,
                            -(int) tracker.getXVelocity(), -(int) tracker.getYVelocity(),
                            0, maxScrollX(), 0, maxScrollY());
                    invalidate();
                }
                if (tracker != null) {
                    tracker.recycle();
                    tracker = null;
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void handleTap(float x, float y) {
        if (x < timeW || y < headerH) {
            return;
        }
        float cx = x - timeW + scrollX;
        float cy = y - headerH + scrollY;
        for (Hit hit : hits) {
            if (cx >= hit.rect.left - dp(2) && cx <= hit.rect.right + dp(2)
                    && cy >= hit.rect.top && cy <= hit.rect.bottom) {
                if (listener != null) {
                    listener.onCourseClick(hit.course);
                }
                return;
            }
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.getCurrX();
            scrollY = scroller.getCurrY();
            clampScroll();
            invalidate();
        }
    }

    private int maxScrollX() {
        return Math.max(0, Math.round(DAYS * colW - (getWidth() - timeW)));
    }

    private int maxScrollY() {
        return Math.max(0, Math.round(ROWS * rowH - (getHeight() - headerH)));
    }

    private void clampScroll() {
        int mx = maxScrollX();
        int my = maxScrollY();
        if (scrollX < 0) {
            scrollX = 0;
        }
        if (scrollX > mx) {
            scrollX = mx;
        }
        if (scrollY < 0) {
            scrollY = 0;
        }
        if (scrollY > my) {
            scrollY = my;
        }
    }

    // ---------------------------------------------------------------- 工具

    private float dp(float v) {
        return v * density;
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    private int attrColor(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            if (tv.resourceId != 0) {
                return ContextCompat.getColor(getContext(), tv.resourceId);
            }
        }
        return fallback;
    }

    private static final class Hit {
        final RectF rect;
        final TimetableParser.Course course;

        Hit(RectF r, TimetableParser.Course c) {
            rect = r;
            course = c;
        }
    }
}

package local.overtime;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.NavigableMap;
import java.util.function.Consumer;

final class AttendanceHeatmap extends LinearLayout {
    private static final int INK = 0xff17312b;
    private static final int MUTED = 0xff526862;
    private static final int NO_RECORD = 0xffe5e8e6;
    private static final int NO_OVERTIME = 0xffcee6d4;
    private static final int OVERTIME = 0xff8ab59c;
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private final HorizontalScrollView scroll;
    private final int contentWidth;
    private TextView selectedCell;
    private PopupWindow popup;
    private final Runnable fadeDuration = this::fadePopup;

    AttendanceHeatmap(Context context, NavigableMap<LocalDate, Records.Punch> punches, LocalDate today,
            LocalDate selected, int distanceFromEnd, Consumer<LocalDate> onSelected) {
        super(context);
        setOrientation(VERTICAL);
        int cellSize = dp(31);
        int step = cellSize + dp(3);
        int labelHeight = dp(22);
        LocalDate earliest = punches.isEmpty() || selected.isBefore(punches.firstKey()) ? selected : punches.firstKey();
        LocalDate first = firstMonday(today, earliest);
        int weeks = (int) ChronoUnit.WEEKS.between(first, today) + 1;
        contentWidth = weeks * step;

        LinearLayout fields = new LinearLayout(context);
        scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setOnScrollChangeListener((view, left, top, oldLeft, oldTop) -> dismissPopup());
        fields.addView(scroll, new LayoutParams(0, -2, 1));

        FrameLayout grid = new FrameLayout(context);
        grid.setMinimumWidth(contentWidth);
        scroll.addView(grid, new FrameLayout.LayoutParams(contentWidth, labelHeight + 7 * step));
        for (int week = 0; week < weeks; week++) {
            LocalDate monday = first.plusWeeks(week);
            if (week == 0 || monday.getMonth() != monday.minusWeeks(1).getMonth()) {
                String month = monday.getMonthValue() + "月";
                if (monday.getMonthValue() == 1 || week == 0) {
                    month = monday.getYear() + "年" + month;
                }
                place(grid, label(month, 11, MUTED), -2, labelHeight, week * step, 0);
            }
            for (int weekday = 0; weekday < 7; weekday++) {
                LocalDate date = monday.plusDays(weekday);
                if (date.isAfter(today)) {
                    break;
                }
                Records.Punch punch = punches.get(date);
                int minutes = punch == null ? Overtime.MISSING : Overtime.minutes(date, punch.arrival, punch.departure);
                int color = punch == null ? NO_RECORD : minutes > 0 ? OVERTIME : NO_OVERTIME;
                String overtime = overtimeLabel(punch, minutes);
                TextView cell = label(Integer.toString(date.getDayOfMonth()), 12, INK);
                cell.setGravity(Gravity.CENTER);
                cell.setTag(color);
                cell.setContentDescription(date + " 加班时长 " + overtime);
                cell.setFocusable(true);
                boolean chosen = date.equals(selected);
                style(cell, chosen);
                if (chosen) {
                    selectedCell = cell;
                }
                cell.setOnClickListener(view -> {
                    if (selectedCell != null) {
                        style(selectedCell, false);
                    }
                    selectedCell = cell;
                    style(cell, true);
                    onSelected.accept(date);
                    showPopup(cell, overtime);
                });
                place(grid, cell, cellSize, cellSize, week * step, labelHeight + weekday * step);
            }
        }

        LinearLayout weekdayLabels = new LinearLayout(context);
        weekdayLabels.setOrientation(VERTICAL);
        weekdayLabels.setPadding(dp(5), labelHeight, 0, 0);
        for (String weekday : WEEKDAYS) {
            weekdayLabels.addView(label(weekday, 10, MUTED), new LayoutParams(-2, step));
        }
        fields.addView(weekdayLabels);
        addView(fields);

        LinearLayout legend = new LinearLayout(context);
        legend.setPadding(0, dp(8), 0, 0);
        addLegend(legend, NO_RECORD, "无记录");
        addLegend(legend, NO_OVERTIME, "无有效加班");
        addLegend(legend, OVERTIME, "有加班");
        addView(legend);
        scroll.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right > left) {
                    scroll.removeOnLayoutChangeListener(this);
                    scroll.scrollTo(Math.max(0, maxScroll() - distanceFromEnd), 0);
                }
            }
        });
    }

    static LocalDate firstMonday(LocalDate today, LocalDate firstRecord) {
        LocalDate recent = today.minusWeeks(15);
        LocalDate start = firstRecord != null && firstRecord.isBefore(recent) ? firstRecord : recent;
        return start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    int distanceFromEnd() {
        return Math.max(0, maxScroll() - scroll.getScrollX());
    }

    private int maxScroll() {
        return Math.max(0, contentWidth - scroll.getWidth());
    }

    static String overtimeLabel(Records.Punch punch, int minutes) {
        if (punch == null) {
            return "无";
        }
        if (minutes >= 0) {
            return MainActivity.duration(minutes);
        }
        return punch.departure >= 0 && punch.departure < punch.arrival ? "时间待核对" : "待补录";
    }

    private void showPopup(TextView cell, String label) {
        dismissPopup();
        TextView message = label(label, 15, Color.WHITE);
        message.setGravity(Gravity.CENTER);
        message.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        message.setPadding(dp(10), 0, dp(10), 0);
        int height = dp(30);
        message.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        int width = message.getMeasuredWidth();
        PopupWindow window = new PopupWindow(message, width, height, false);
        GradientDrawable background = shape(0xff1a322b);
        background.setCornerRadius(dp(9));
        window.setBackgroundDrawable(background);
        window.setElevation(dp(3));
        window.setTouchable(false);
        popup = window;
        window.showAsDropDown(cell, (cell.getWidth() - width) / 2,
                -(cell.getHeight() + height + dp(4)), Gravity.START);
        postDelayed(fadeDuration, 1500);
    }

    private void fadePopup() {
        popup.getContentView().getRootView().animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction(this::dismissPopup)
                .start();
    }

    private void dismissPopup() {
        removeCallbacks(fadeDuration);
        if (popup != null) {
            popup.getContentView().getRootView().animate().cancel();
            popup.dismiss();
            popup = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        dismissPopup();
        super.onDetachedFromWindow();
    }

    private void style(TextView cell, boolean chosen) {
        GradientDrawable background = shape((Integer) cell.getTag());
        if (chosen) {
            background.setStroke(dp(2), INK);
        }
        cell.setBackground(background);
        cell.setSelected(chosen);
        cell.setTypeface(Typeface.DEFAULT, chosen ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void addLegend(LinearLayout legend, int color, String caption) {
        LinearLayout item = new LinearLayout(getContext());
        item.setGravity(Gravity.CENTER_VERTICAL);
        View swatch = new View(getContext());
        swatch.setBackground(shape(color));
        item.addView(swatch, new LayoutParams(dp(11), dp(11)));
        TextView text = label(caption, 11, MUTED);
        text.setPadding(dp(4), 0, dp(10), 0);
        item.addView(text);
        legend.addView(item);
    }

    private void place(FrameLayout grid, View view, int width, int height, int left, int top) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        grid.addView(view, params);
    }

    private TextView label(String text, int size, int color) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(5));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

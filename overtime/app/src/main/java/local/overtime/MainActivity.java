package local.overtime;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.CookieManager;

import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity {
    private static final int NOTIFICATIONS = 12;
    private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("M.d");
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE);
    private static final int BG = 0xfff8f8f2;
    private static final int WHITE = Color.WHITE;
    private static final int INK = 0xff182721;
    private static final int MUTED = 0xff52625a;
    private static final int GREEN = 0xff236749;
    private static final int PALE = 0xffe7eee5;
    private static final int LINE = 0xffdbe2d8;
    private static final YearMonth FIRST_PICKABLE_MONTH = YearMonth.of(1900, 1);
    private static final int MONTH_REFERENCE_MINUTES = 20 * 60;
    private static final String DATE_KEY = "selectedDay";
    private static final String DETAIL_DATE_KEY = "detailDate";
    private static final String HEATMAP_OFFSET_KEY = "heatmapOffset";

    private static final class MonthStats {
        int minutes;
        int days;
    }

    private Records records;
    private OaCredentials credentials;
    private OaSession oaSession;
    private String oaProgress;
    private LinearLayout root;
    private ScrollView mainScroll;
    private AttendanceHeatmap heatmap;
    private int heatmapOffset;
    private boolean revealDetail;
    private LocalDate selected;
    private LocalDate detailDate;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        records = new Records(this);
        credentials = new OaCredentials(this);
        selected = saved == null ? Overtime.today() : LocalDate.parse(saved.getString(DATE_KEY));
        detailDate = saved == null ? Overtime.today() : LocalDate.parse(saved.getString(DETAIL_DATE_KEY, selected.toString()));
        if (saved != null) {
            heatmapOffset = saved.getInt(HEATMAP_OFFSET_KEY);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateReminder();
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(DATE_KEY, selected.toString());
        out.putString(DETAIL_DATE_KEY, detailDate.toString());
        out.putInt(HEATMAP_OFFSET_KEY, heatmap == null || !heatmap.isLaidOut()
                ? heatmapOffset : heatmap.distanceFromEnd());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        if (oaSession != null) {
            oaSession.close();
        }
        super.onDestroy();
    }

    private void handleIntent(Intent intent) {
        if (ReminderWorker.OPEN_TODAY.equals(intent.getAction())) {
            selected = LocalDate.ofEpochDay(intent.getIntExtra(ReminderWorker.OPEN_DATE,
                    (int) Overtime.today().toEpochDay()));
            detailDate = selected;
            heatmap = null;
            heatmapOffset = 0;
            revealDetail = true;
        }
    }

    private void render() {
        LocalDate today = Overtime.today();
        YearMonth month = Overtime.period(selected);
        YearMonth current = Overtime.period(today);
        NavigableMap<LocalDate, Records.Punch> punches = records.all();
        int verticalPosition = mainScroll == null ? 0 : mainScroll.getScrollY();
        if (heatmap != null && heatmap.isLaidOut()) {
            heatmapOffset = heatmap.distanceFromEnd();
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setBackgroundColor(BG);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 35) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            }
            return insets;
        });
        root = column();
        root.setPadding(dp(18), dp(4), dp(18), dp(12));
        scroll.addView(root);
        setContentView(scroll);
        mainScroll = scroll;
        boolean showDetail = revealDetail;
        revealDetail = false;
        scroll.post(() -> scroll.scrollTo(0, showDetail ? scroll.getChildAt(0).getHeight() : verticalPosition));

        LinearLayout header = row();
        addIcon(header, R.drawable.ic_overview_menu, "打开设置侧边栏", this::showSettings);
        header.addView(text("考勤统计", 20, INK, true), weight());
        root.addView(header);
        if (oaSession != null) {
            LinearLayout progress = row();
            ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
            progress.addView(spinner, new LinearLayout.LayoutParams(dp(17), dp(17)));
            TextView caption = text(oaProgress, 13, MUTED, false);
            caption.setPadding(dp(8), 0, 0, 0);
            caption.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            progress.addView(caption, weight());
            add(root, progress, 8);
        }

        add(root, periodPager(punches, today, month, current), 8);
        add(root, text("打卡明细", 17, INK, true), 16);
        addAttendanceDetails(punches, today);
    }

    private ViewPager periodPager(NavigableMap<LocalDate, Records.Punch> punches, LocalDate today,
            YearMonth shown, YearMonth current) {
        YearMonth earliest = shown.isBefore(FIRST_PICKABLE_MONTH) ? shown : FIRST_PICKABLE_MONTH;
        YearMonth firstRecord = punches.isEmpty() ? shown : Overtime.period(punches.firstKey());
        YearMonth first = firstRecord.isBefore(earliest) ? firstRecord : earliest;
        int count = (int) ChronoUnit.MONTHS.between(first, current) + 1;
        ViewPager pager = new PeriodPager();
        pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pager.setPageMargin(dp(8));
        pager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return count;
            }

            @Override
            public Object instantiateItem(ViewGroup container, int position) {
                LinearLayout card = periodCard(punches, today, first.plusMonths(position), current,
                        position > 0, direction -> pager.setCurrentItem(
                                position + direction, ValueAnimator.areAnimatorsEnabled()));
                container.addView(card);
                return card;
            }

            @Override
            public void destroyItem(ViewGroup container, int position, Object card) {
                container.removeView((View) card);
            }

            @Override
            public boolean isViewFromObject(View view, Object card) {
                return view == card;
            }
        });
        pager.setCurrentItem((int) ChronoUnit.MONTHS.between(first, shown), false);
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                YearMonth month = first.plusMonths(position);
                selected = month.equals(current) ? today : Overtime.end(month);
            }
        });
        return pager;
    }

    private LinearLayout periodCard(NavigableMap<LocalDate, Records.Punch> punches, LocalDate today,
            YearMonth month, YearMonth current, boolean hasPrevious, IntConsumer step) {
        LinearLayout card = column();
        card.setBackground(rounded(WHITE, 16));
        card.setClipToOutline(true);

        boolean latest = month.equals(current);
        LinearLayout navigation = row();
        addPeriodArrow(navigation, "上个考勤月", false, hasPrevious, () -> step.accept(-1));
        String periodName = month.getYear() + "年" + month.getMonthValue() + "月";
        TextView period = action(periodName, INK, Color.TRANSPARENT, () -> pickDate(selected, date -> {
            selected = date;
            render();
        }));
        period.setTextSize(16);
        navigation.addView(period, weight());
        addPeriodArrow(navigation, "下个考勤月", true, !latest, () -> step.accept(1));
        card.addView(navigation);

        YearMonth first = Overtime.quarterStart(month);
        MonthStats shown = monthStats(punches, month);
        int quarterMinutes = 0;
        int quarterDays = 0;
        for (int index = 0; index < 3; index++) {
            YearMonth periodMonth = first.plusMonths(index);
            MonthStats stats = periodMonth.equals(month) ? shown : monthStats(punches, periodMonth);
            quarterMinutes += stats.minutes;
            quarterDays += stats.days;
        }

        LinearLayout body = column();
        body.setPadding(dp(16), 0, dp(16), dp(10));
        int remaining = Overtime.remaining(today);
        boolean showCountdown = latest && remaining > 0;
        body.addView(text("本月加班", 13, MUTED, false));
        LinearLayout metrics = row();
        metrics.setBaselineAligned(false);
        String hours = Integer.toString(shown.minutes / 60) + (shown.minutes % 60 == 0 ? "" : ".5");
        TextView amount = overviewValue(shown.days == 0 ? "—" : hours, shown.days == 0 ? "" : "h", 68, GREEN);
        metrics.addView(amount, weight());

        if (showCountdown) {
            LinearLayout settlement = column();
            settlement.setGravity(Gravity.END);
            settlement.addView(text("距本期结算", 13, MUTED, false));
            settlement.addView(overviewValue(Integer.toString(remaining), "天", 42, INK));
            metrics.addView(settlement);
        } else {
            metrics.addView(text(latest ? "今天结算" : "已结算", 14, MUTED, false));
        }
        body.addView(metrics);

        addMonthProgress(body, shown.minutes);
        LinearLayout periodDetail = row();
        String coverage = shown.days == 0 ? "暂无有效打卡" : "已统计 " + shown.days + " 天";
        String dates = dateSpan(Overtime.start(month), Overtime.end(month));
        periodDetail.addView(text(dates + " · " + coverage, 12, MUTED, false), weight());
        String reference = compactDuration(MONTH_REFERENCE_MINUTES);
        String progressLabel = reference + " 月度参照";
        if (shown.days > 0) {
            int gap = MONTH_REFERENCE_MINUTES - shown.minutes;
            progressLabel = gap > 0 ? "距 " + reference + " 参照还差 " + compactDuration(gap)
                    : "已达 " + reference + " 参照";
        }
        periodDetail.addView(text(progressLabel, 12, MUTED, false));
        add(body, periodDetail, 5);
        card.addView(body);

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(dp(16), 0, dp(16), 0);
        card.addView(divider, dividerParams);

        LinearLayout quarter = row();
        quarter.setPadding(dp(16), dp(11), dp(16), dp(11));
        int quarterNumber = (first.getMonthValue() - 1) / 3 + 1;
        String prefix = "第 " + quarterNumber + " 季度　月均 ";
        SpannableString average = new SpannableString(prefix
                + (quarterDays == 0 ? "—" : compactDuration(quarterMinutes / 3)));
        average.setSpan(new RelativeSizeSpan(20f / 13), prefix.length(), average.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        average.setSpan(new StyleSpan(Typeface.BOLD), prefix.length(), average.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView quarterAverage = text("", 13, INK, false);
        quarterAverage.setText(average);
        quarter.addView(quarterAverage, weight());
        String total = "累计 " + (quarterDays == 0 ? "—" : compactDuration(quarterMinutes));
        quarter.addView(text(total, 13, MUTED, false));
        card.addView(quarter);
        return card;
    }

    private TextView overviewValue(String number, String unit, int size, int color) {
        TextView value = text(number, size, color, true);
        value.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        value.setLetterSpacing(-0.025f);
        value.setIncludeFontPadding(false);
        if (!unit.isEmpty()) {
            SpannableString label = new SpannableString(number + "\u00a0" + unit);
            label.setSpan(new RelativeSizeSpan(16f / size), number.length(), label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new ForegroundColorSpan(MUTED), number.length(), label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            value.setText(label);
        }
        return value;
    }

    private MonthStats monthStats(NavigableMap<LocalDate, Records.Punch> punches, YearMonth month) {
        MonthStats stats = new MonthStats();
        for (Map.Entry<LocalDate, Records.Punch> entry : punches
                .subMap(Overtime.start(month), true, Overtime.end(month), true).entrySet()) {
            int minutes = minutes(entry.getKey(), entry.getValue());
            if (minutes >= 0) {
                stats.minutes += minutes;
                stats.days++;
            }
        }
        return stats;
    }

    private void addMonthProgress(LinearLayout summary, int minutes) {
        View track = new View(this);
        ClipDrawable progress = new ClipDrawable(rounded(GREEN, 3), Gravity.START, ClipDrawable.HORIZONTAL);
        track.setBackground(new LayerDrawable(new Drawable[]{rounded(LINE, 3), progress}));
        progress.setLevel(Math.min(minutes, MONTH_REFERENCE_MINUTES) * 10000 / MONTH_REFERENCE_MINUTES);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(5));
        params.topMargin = dp(7);
        summary.addView(track, params);
    }

    private void showSettings() {
        Dialog drawer = new Dialog(this);
        drawer.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = column();
        sheet.setBackgroundColor(BG);
        LinearLayout header = row();
        header.setPadding(dp(24), dp(12), dp(12), dp(4));
        header.addView(text("设置", 24, INK, true), weight());
        addIcon(header, R.drawable.ic_overview_close, "关闭设置侧边栏", drawer::dismiss);
        sheet.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout settings = column();
        settings.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(settings);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout data = settingsSection(settings, "考勤数据");
        if (oaSession == null) {
            data.addView(settingLink("刷新本月考勤", () -> {
                drawer.dismiss();
                refreshOa();
            }));
            data.addView(settingLink("导入考勤记录", () -> {
                drawer.dismiss();
                showOaImport();
            }));
        }
        boolean loggedIn = credentials.remembered();
        data.addView(settingLink(loggedIn ? "OA 账号设置" : "登录 OA", () -> {
            drawer.dismiss();
            if (loggedIn) {
                showOaSettings();
            } else {
                refreshOa();
            }
        }));
        String lastSync = ReminderWorker.state(this).getString(ReminderWorker.LAST_SYNC, "");
        if (!lastSync.isEmpty()) {
            TextView status = text(lastSync, 12, MUTED, false);
            status.setPadding(dp(8), 0, dp(8), 0);
            add(settings, status, 6);
        }

        LinearLayout notifications = settingsSection(settings, "通知与提醒");
        addScheduleSetting(notifications, false, "昨日考勤更新通知", "自动刷新时间");
        addSettingsDivider(notifications, 0);
        addScheduleSetting(notifications, true, "工作日打卡提醒", "提醒时间");
        addSettingsDivider(notifications, 0);
        notifications.addView(settingLink("系统通知设置", () -> {
            drawer.dismiss();
            notificationSettings();
        }));
        add(settings, settingLink("统计规则", () -> {
            drawer.dismiss();
            showRules();
        }), 12);

        drawer.setContentView(sheet);
        drawer.setCanceledOnTouchOutside(true);
        Window window = drawer.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setWindowAnimations(R.style.SettingsDrawerAnimation);
        window.setGravity(Gravity.START | Gravity.TOP);
        int width = Math.min(312, getResources().getConfiguration().screenWidthDp - 64);
        window.setLayout(dp(width), LinearLayout.LayoutParams.MATCH_PARENT);
        drawer.show();
    }

    private void addScheduleSetting(LinearLayout settings, boolean punch, String notice, String schedule) {
        Switch toggle = new Switch(this);
        toggle.setText(notice);
        toggle.setTextColor(INK);
        toggle.setTextSize(14);
        toggle.setMinHeight(dp(48));
        toggle.setPadding(dp(12), 0, dp(12), 0);
        toggle.setChecked(ReminderWorker.noticeEnabled(this, punch));
        TextView status = text("系统通知设置已关闭此通知", 12, MUTED, false);
        status.setPadding(dp(8), 0, dp(8), dp(5));
        status.setVisibility(toggle.isChecked() && !ReminderWorker.allowed(this, punch) ? View.VISIBLE : View.GONE);
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            ReminderWorker.setNoticeEnabled(this, punch, enabled);
            boolean blocked = enabled && !ReminderWorker.allowed(this, punch);
            status.setVisibility(blocked ? View.VISIBLE : View.GONE);
            if (blocked) {
                notificationSettings();
            }
        });
        settings.addView(toggle);
        add(settings, status, 0);
        int time = punch ? ReminderWorker.punchMinutes(this) : ReminderWorker.scheduleMinutes(this);
        TextView value = text(PunchTime.display(time), 14, GREEN, true);
        add(settings, settingRow(schedule, value, MUTED, () -> {
            int current = punch ? ReminderWorker.punchMinutes(this) : ReminderWorker.scheduleMinutes(this);
            pickTime(punch ? "打卡提醒时间" : "每日自动刷新时间", current, current, minutes -> {
                if (punch) {
                    ReminderWorker.changePunchTime(this, minutes);
                } else {
                    ReminderWorker.changeTime(this, minutes);
                }
                value.setText(PunchTime.display(minutes));
            });
        }), 0);
    }

    private LinearLayout settingsSection(LinearLayout parent, String title) {
        TextView heading = text(title, 15, INK, true);
        heading.setPadding(dp(8), 0, 0, 0);
        add(parent, heading, 16);
        LinearLayout group = column();
        group.setPadding(dp(4), 0, dp(4), 0);
        group.setBackground(rounded(WHITE, 16));
        group.setClipToOutline(true);
        add(parent, group, 8);
        return group;
    }

    private LinearLayout settingLink(String label, Runnable click) {
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_overview_chevron);
        arrow.setScaleX(-1);
        arrow.setColorFilter(MUTED);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(16), dp(16)));
        return settingRow(label, arrow, INK, click);
    }

    private LinearLayout settingRow(String label, View value, int color, Runnable click) {
        LinearLayout item = row();
        item.setPadding(dp(12), 0, dp(12), 0);
        item.setMinimumHeight(dp(48));
        item.setBackground(clickable(Color.TRANSPARENT, 10));
        item.addView(text(label, 14, color, false), weight());
        item.addView(value);
        item.setOnClickListener(view -> click.run());
        return item;
    }

    private void addSettingsDivider(LinearLayout settings, int gap) {
        addDivider(settings, gap, LINE);
    }

    private void addDivider(LinearLayout parent, int gap, int color) {
        View divider = new View(this);
        divider.setBackgroundColor(color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(gap);
        parent.addView(divider, params);
    }

    private void addAttendanceDetails(NavigableMap<LocalDate, Records.Punch> punches, LocalDate today) {
        LinearLayout card = panel(WHITE, 16);
        LinearLayout line = row();
        LinearLayout detail = column();
        TextView dateLabel = text("", 14, INK, true);
        TextView times = text("", 14, MUTED, false);
        TextView overtime = text("", 14, GREEN, true);
        detail.addView(dateLabel);
        add(detail, times, 4);
        add(detail, overtime, 4);
        line.addView(detail, weight());
        TextView editButton = action("编辑", GREEN, PALE, () -> edit(detailDate, date -> detailDate = date));
        line.addView(editButton);
        Consumer<LocalDate> showDate = date -> {
            detailDate = date;
            Records.Punch punch = punches.get(date);
            dateLabel.setText(date + " · " + dayDescription(date));
            times.setText(punch == null ? "上班 无　下班 无"
                    : "上班 " + clockOrDash(punch.arrival) + "　下班 " + clockOrDash(punch.departure));
            overtime.setText(punch == null ? "无打卡记录"
                    : "当日加班  " + AttendanceHeatmap.overtimeLabel(punch, minutes(date, punch)));
            editButton.setText(punch == null ? "补录" : "编辑");
        };
        showDate.accept(detailDate);
        heatmap = new AttendanceHeatmap(this, punches, today, detailDate, heatmapOffset, showDate);
        card.addView(heatmap);
        addSettingsDivider(card, 14);
        add(card, line, 12);
        add(root, card, 8);
    }

    private static String dayDescription(LocalDate date) {
        return date.format(WEEKDAY) + " | " + ChinaWorkCalendar.day(date).label;
    }

    private void showRules() {
        new AlertDialog.Builder(this).setTitle("统计规则")
                .setMessage("每月 26 日至次月 25 日，以结束月份归入季度。剩余天数按自然日计算，今天不计入剩余。\n\n"
                        + "工作日和调休补班日从 18:30 起计。周末和节假日休息日按上下班时间差计算，扣除与 12:00–13:00 和 18:00–18:30 重合的时段。\n\n"
                        + "每天满 1 小时才计入，随后的时长向下取整到半小时。内置 2025–2027 年官方放假和调休安排，其他年份按自然星期计算。\n\n"
                        + "季度月均加班按季度累计时长除以 3 计算。")
                .setPositiveButton("知道了", null).show();
    }

    private void pickDate(LocalDate date, Consumer<LocalDate> picked) {
        pickDate("选择日期", date, null, picked);
    }

    private void pickDate(String title, LocalDate date, LocalDate earliest, Consumer<LocalDate> picked) {
        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, day) ->
                picked.accept(LocalDate.of(year, month + 1, day)), date.getYear(), date.getMonthValue() - 1,
                date.getDayOfMonth());
        dialog.setTitle(title);
        if (earliest != null) {
            dialog.getDatePicker().setMinDate(earliest.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        dialog.getDatePicker().setMaxDate(Overtime.today().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1);
        dialog.show();
    }

    private void edit(LocalDate date, Consumer<LocalDate> afterSave) {
        Records.Punch saved = records.all().get(date);
        int in = saved == null ? Overtime.MISSING : saved.arrival;
        int out = saved == null ? Overtime.MISSING : saved.departure;
        LinearLayout form = column();
        form.setPadding(dp(22), dp(6), dp(22), dp(3));
        TextView dateButton = action(date.toString() + "  ·  更换日期", GREEN, PALE, null);
        add(form, dateButton, 4);
        int[] times = {in, out};
        TextView arrival = timeField(form, "上班时间", in);
        TextView departure = timeField(form, "下班时间", out);
        Runnable pickDeparture = () -> pickTime("下班时间", times[1], 18 * 60, minutes -> {
            times[1] = Overtime.departureOnWorkday(minutes);
            departure.setText(clockOrDash(times[1]));
        });
        departure.setOnClickListener(view -> pickDeparture.run());
        arrival.setOnClickListener(view -> pickTime("上班时间", times[0], 9 * 60, minutes -> {
            times[0] = minutes;
            arrival.setText(clockOrDash(minutes));
            pickDeparture.run();
        }));
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("记录打卡")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null);
        if (saved != null) {
            builder.setNeutralButton("删除", (dialog, which) -> new AlertDialog.Builder(this)
                    .setMessage("删除 " + date + " 的打卡记录？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (confirm, button) -> {
                        records.delete(date);
                        render();
                    }).show());
        }
        AlertDialog dialog = builder.create();
        dateButton.setOnClickListener(view -> pickDate(date, newDate -> {
            if (!newDate.equals(date)) {
                dialog.dismiss();
                edit(newDate, afterSave);
            }
        }));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int firstMinutes = times[0];
            int lastMinutes = times[1];
            if (firstMinutes == Overtime.MISSING && lastMinutes == Overtime.MISSING) {
                toast("请选择至少一个打卡时间");
            } else if (lastMinutes != Overtime.MISSING && lastMinutes < firstMinutes) {
                toast("下班时间应在上班时间之后");
            } else {
                records.save(date, new Records.Punch(firstMinutes, lastMinutes));
                afterSave.accept(date);
                dialog.dismiss();
                render();
            }
        });
    }

    private TextView timeField(LinearLayout form, String label, int value) {
        add(form, text(label, 13, MUTED, true), 10);
        TextView field = action(value == Overtime.MISSING ? "选择时间" : clockOrDash(value), INK, PALE, null);
        field.setContentDescription(label);
        add(form, field, 4);
        return field;
    }

    private void pickTime(String title, int saved, int defaultTime, Consumer<Integer> picked) {
        int minutes = saved == Overtime.MISSING ? defaultTime : saved % Overtime.MINUTES_PER_DAY;
        LinearLayout wheels = row();
        wheels.setPadding(dp(20), dp(10), dp(20), dp(8));
        NumberPicker hour = clockWheel(24, minutes / 60);
        NumberPicker minute = clockWheel(60, minutes % 60);
        for (String unit : new String[]{"时", "分"}) {
            LinearLayout wheel = column();
            TextView label = text(unit, 14, MUTED, false);
            label.setGravity(Gravity.CENTER);
            wheel.addView(label);
            wheel.addView("时".equals(unit) ? hour : minute, new LinearLayout.LayoutParams(-1, dp(154)));
            wheels.addView(wheel, weight());
        }
        new AlertDialog.Builder(this).setTitle(title).setView(wheels)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> picked.accept(hour.getValue() * 60 + minute.getValue()))
                .show();
    }

    private NumberPicker clockWheel(int count, int value) {
        NumberPicker wheel = new NumberPicker(this);
        wheel.setMinValue(0);
        wheel.setMaxValue(count - 1);
        String[] values = new String[count];
        for (int i = 0; i < count; i++) {
            values[i] = String.format(Locale.ROOT, "%02d", i);
        }
        wheel.setDisplayedValues(values);
        wheel.setValue(value);
        wheel.setWrapSelectorWheel(true);
        wheel.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        return wheel;
    }

    private void showOaLogin(Consumer<OaCredentials.Account> loggedIn) {
        LinearLayout form = column();
        form.setPadding(dp(22), dp(4), dp(22), dp(5));
        EditText username = new EditText(this);
        username.setSingleLine();
        username.setHint("OA 用户名");
        username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        form.addView(username);
        EditText password = new EditText(this);
        password.setSingleLine();
        password.setHint("OA 密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(password);
        add(form, text("登录成功后会在此手机加密保存，用于刷新和每日同步。", 13, MUTED, false), 8);
        AlertDialog login = new AlertDialog.Builder(this).setTitle("登录 OA").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("登录并保存", null).create();
        login.show();
        login.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        login.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = username.getText().toString().trim();
            String secret = password.getText().toString();
            if (name.isEmpty()) {
                username.setError("请输入 OA 用户名");
            } else if (secret.isEmpty()) {
                password.setError("请输入 OA 密码");
            } else {
                password.getText().clear();
                login.dismiss();
                loggedIn.accept(new OaCredentials.Account(name, secret));
            }
        });
    }

    private void showOaSettings() {
        new AlertDialog.Builder(this).setTitle("OA 账号设置")
                .setItems(new String[]{"重新登录", "忘记已保存的账号密码"}, (dialog, option) -> {
                    if (option == 0) {
                        LocalDate today = Overtime.today();
                        showOaLogin(account -> startOa(account, true, Overtime.start(Overtime.period(today)), today, true));
                    } else {
                        credentials.forget();
                        ReminderWorker.state(this).edit().clear().apply();
                        CookieManager.getInstance().removeAllCookies(removed -> CookieManager.getInstance().flush());
                        android.webkit.WebStorage.getInstance().deleteAllData();
                        render();
                    }
                }).show();
    }

    private void refreshOa() {
        LocalDate today = Overtime.today();
        syncOa(Overtime.start(Overtime.period(today)), today, true);
    }

    private void showOaImport() {
        LocalDate today = Overtime.today();
        YearMonth current = Overtime.period(today);
        new AlertDialog.Builder(this).setTitle("OA 导入")
                .setItems(new String[]{"导入本月", "导入上月", "手动选择起始和结束"}, (dialog, option) -> {
                    if (option == 0) {
                        syncOa(Overtime.start(current), today, false);
                    } else if (option == 1) {
                        YearMonth previous = current.minusMonths(1);
                        syncOa(Overtime.start(previous), Overtime.end(previous), false);
                    } else {
                        pickDate("选择起始日期", Overtime.start(current), null, from ->
                                pickDate("选择结束日期", today, from, through -> syncOa(from, through, false)));
                    }
                }).show();
    }

    private void syncOa(LocalDate from, LocalDate through, boolean refresh) {
        if (oaSession != null) {
            return;
        }
        Consumer<OaCredentials.Account> login = account -> startOa(account, true, from, through, refresh);
        try {
            OaCredentials.Account account = credentials.read();
            if (account == null) {
                showOaLogin(login);
            } else {
                startOa(account, false, from, through, refresh);
            }
        } catch (IOException | GeneralSecurityException | JSONException error) {
            new AlertDialog.Builder(this).setTitle("无法读取已保存的 OA 登录信息")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("重新登录", (dialog, button) -> showOaLogin(login)).show();
        }
    }

    private void startOa(OaCredentials.Account account, boolean remember, LocalDate from, LocalDate through,
            boolean refresh) {
        try {
            LocalDate firstQuery = records.needsBoundaryRepair(from) ? from.minusDays(1) : from;
            OaSession session = new OaSession(getApplicationContext(), account, firstQuery, through, new OaSession.Listener() {
                @Override
                public void status(String message) {}

                @Override
                public void authenticated() {
                    if (remember) {
                        try {
                            credentials.save(account);
                            updateReminder();
                        } catch (IOException | GeneralSecurityException error) {
                            toast("OA 已登录，但手机未能保存登录信息");
                        }
                    }
                }

                @Override
                public void success(OaResult result) {
                    oaSession = null;
                    int days = result.apply(records, from, through);
                    String message = result.describeImport(from, through, days, refresh);
                    ReminderWorker.state(MainActivity.this).edit().putString(ReminderWorker.LAST_SYNC, message).apply();
                    selected = through;
                    render();
                }

                @Override
                public void failure(String error) {
                    oaSession = null;
                    render();
                    showOaError(error);
                }
            });
            oaProgress = refresh ? "正在刷新 OA…" : "正在导入 OA…";
            oaSession = session;
            render();
            session.layoutForBackground();
            session.start(remember);
        } catch (IOException error) {
            showOaError("无法加载 OA 同步组件。");
        }
    }

    private void showOaError(String message) {
        new AlertDialog.Builder(this).setTitle("OA 同步未完成").setMessage(message)
                .setPositiveButton("知道了", null).show();
    }

    private void updateReminder() {
        if (credentials.remembered() || ReminderWorker.allowed(this)) {
            ReminderWorker.ensureScheduled(this);
        }
    }

    private void notificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationsAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationsAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        } else {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATIONS) {
            updateReminder();
            render();
        }
    }

    private int minutes(LocalDate date, Records.Punch punch) {
        return Overtime.minutes(date, punch.arrival, punch.departure);
    }

    private static String clockOrDash(int minutes) {
        return PunchTime.display(minutes);
    }

    static String duration(int minutes) {
        return minutes / 60 + " 小时" + (minutes % 60 == 0 ? "" : " 30 分");
    }

    private static String compactDuration(int minutes) {
        int remainder = minutes % 60;
        String hours = Integer.toString(minutes / 60);
        if (remainder == 30) {
            return hours + ".5h";
        }
        return hours + "h" + (remainder == 0 ? "" : " " + remainder + "m");
    }

    private static String dateSpan(LocalDate from, LocalDate to) {
        return from.format(DATE_SHORT) + "–" + to.format(DATE_SHORT);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout panel(int color, int radius) {
        LinearLayout panel = column();
        panel.setPadding(dp(15), dp(13), dp(15), dp(13));
        panel.setBackground(rounded(color, radius));
        return panel;
    }

    private TextView action(String label, int foreground, int background, Runnable click) {
        TextView button = text(label, 14, foreground, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(46));
        button.setMinWidth(dp(46));
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setBackground(clickable(background, 12));
        if (click != null) {
            button.setOnClickListener(view -> click.run());
        }
        return button;
    }

    private ImageButton addIcon(LinearLayout parent, int icon, String label, Runnable click) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(INK);
        button.setContentDescription(label);
        button.setBackground(clickable(Color.TRANSPARENT, 10));
        button.setOnClickListener(view -> click.run());
        parent.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    private void addPeriodArrow(LinearLayout parent, String label, boolean next, boolean enabled, Runnable click) {
        ImageButton arrow = addIcon(parent, R.drawable.ic_overview_chevron, label, click);
        if (next) {
            arrow.setScaleX(-1);
        }
        arrow.setEnabled(enabled);
        arrow.setAlpha(enabled ? 1f : 0.38f);
    }

    private final class PeriodPager extends ViewPager {
        PeriodPager() {
            super(MainActivity.this);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            // Populate adjacent pages first, then give them enough height for the largest card.
            super.onMeasure(widthSpec, heightSpec);
            int pageWidth = View.MeasureSpec.makeMeasureSpec(getMeasuredWidth(), View.MeasureSpec.EXACTLY);
            int naturalHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            int height = 0;
            for (int index = 0; index < getChildCount(); index++) {
                View page = getChildAt(index);
                page.measure(pageWidth, naturalHeight);
                height = Math.max(height, page.getMeasuredHeight());
            }
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        }
    }

    private RippleDrawable clickable(int background, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x260f5141),
                rounded(background, radius), rounded(WHITE, radius));
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView text(String label, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private void add(LinearLayout parent, View view, int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(marginTop);
        parent.addView(view, params);
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

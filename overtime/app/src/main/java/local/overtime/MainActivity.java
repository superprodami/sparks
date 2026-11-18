package local.overtime;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.CookieManager;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int NOTIFICATIONS = 12;
    private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("M月d日");
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE);
    private static final int BG = 0xfff3f6f4;
    private static final int WHITE = Color.WHITE;
    private static final int INK = 0xff17312b;
    private static final int MUTED = 0xff526862;
    private static final int GREEN = 0xff166a55;
    private static final int DARK_GREEN = 0xff153e34;
    private static final int PALE = 0xffe7f1eb;
    private static final int MINT = 0xffc7e8d6;
    private static final String DATE_KEY = "selectedDay";
    private static final String RANGE_KEY = "range";
    private static final String DETAIL_DATE_KEY = "detailDate";
    private static final String HEATMAP_OFFSET_KEY = "heatmapOffset";

    private enum Range { DAY, MONTH, QUARTER }

    private Records records;
    private OaCredentials credentials;
    private OaSession oaSession;
    private String oaProgress;
    private LinearLayout root;
    private ScrollView mainScroll;
    private AttendanceHeatmap heatmap;
    private int heatmapOffset;
    private LocalDate selected;
    private LocalDate detailDate;
    private Range range = Range.MONTH;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        records = new Records(this);
        credentials = new OaCredentials(this);
        selected = saved == null ? Overtime.today() : LocalDate.parse(saved.getString(DATE_KEY));
        detailDate = saved == null ? Overtime.today() : LocalDate.parse(saved.getString(DETAIL_DATE_KEY, selected.toString()));
        if (saved != null) {
            range = Range.valueOf(saved.getString(RANGE_KEY));
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
        out.putString(RANGE_KEY, range.name());
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
            selected = Overtime.today();
            detailDate = selected;
            heatmap = null;
            heatmapOffset = 0;
            range = Range.DAY;
            render();
        }
    }

    private void render() {
        LocalDate today = Overtime.today();
        YearMonth month = Overtime.period(selected);
        YearMonth quarter = Overtime.quarterStart(month);
        NavigableMap<LocalDate, Records.Punch> punches = records.all();
        int verticalPosition = mainScroll == null ? 0 : mainScroll.getScrollY();
        if (heatmap != null && heatmap.isLaidOut()) {
            heatmapOffset = heatmap.distanceFromEnd();
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
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
        root.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(root);
        setContentView(scroll);
        mainScroll = scroll;
        scroll.post(() -> scroll.scrollTo(0, verticalPosition));

        LinearLayout header = row();
        TextView title = text("考勤记录", 29, INK, true);
        header.addView(title, weight());
        TextView refresh = action("刷新 OA", GREEN, PALE, this::refreshOa);
        refresh.setEnabled(oaSession == null);
        header.addView(refresh);
        root.addView(header);
        TextView todayLabel = text(today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "  ·  " + today.format(WEEKDAY), 13, MUTED, false);
        add(root, todayLabel, 4);

        YearMonth current = Overtime.period(today);
        LinearLayout hero = panel(DARK_GREEN, 22);
        hero.addView(text("当前考勤月", 14, MINT, true));
        hero.addView(text("还剩 " + Overtime.remaining(today) + " 天", 36, WHITE, true));
        add(hero, text(dateSpan(Overtime.start(current), Overtime.end(current)), 14, MINT, false), 8);
        add(root, hero, 18);

        LinearLayout controls = row();
        TextView importOa = action("OA 导入", WHITE, GREEN, this::showOaImport);
        importOa.setEnabled(oaSession == null);
        controls.addView(importOa, weight());
        TextView addPunch = action("手动记录", GREEN, PALE, () -> edit(selected));
        LinearLayout.LayoutParams addParams = weight();
        addParams.leftMargin = dp(8);
        controls.addView(addPunch, addParams);
        add(root, controls, 12);
        String lastSync = ReminderWorker.state(this).getString(ReminderWorker.LAST_SYNC, "");
        if (oaSession != null) {
            LinearLayout progress = row();
            ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
            progress.addView(spinner, new LinearLayout.LayoutParams(dp(17), dp(17)));
            TextView caption = text(oaProgress, 13, MUTED, false);
            caption.setPadding(dp(8), 0, 0, 0);
            caption.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            progress.addView(caption, weight());
            add(root, progress, 8);
        } else if (!lastSync.isEmpty()) {
            add(root, text(lastSync, 13, MUTED, false), 6);
        }

        add(root, text("加班统计", 20, INK, true), 22);
        LinearLayout tabs = row();
        for (Range option : Range.values()) {
            String label = option == Range.DAY ? "日" : option == Range.MONTH ? "月" : "季度";
            TextView tab = action(label, option == range ? WHITE : MUTED,
                    option == range ? DARK_GREEN : PALE, () -> {
                        range = option;
                        render();
                    });
            tab.setSelected(option == range);
            LinearLayout.LayoutParams params = weight();
            params.rightMargin = option == Range.QUARTER ? 0 : dp(6);
            tabs.addView(tab, params);
        }
        add(root, tabs, 9);

        LinearLayout summary = panel(WHITE, 18);
        LinearLayout navigation = row();
        TextView previous = action("‹", GREEN, PALE, () -> move(-1));
        previous.setContentDescription("上一时段");
        navigation.addView(previous);
        String periodName = range == Range.DAY ? selected.format(DATE_SHORT)
                : range == Range.MONTH ? month.getYear() + "年" + month.getMonthValue() + "月"
                : month.getYear() + "年 第" + ((month.getMonthValue() - 1) / 3 + 1) + "季度";
        navigation.addView(action(periodName, INK, WHITE, () -> pickDate(selected, date -> {
            selected = date;
            render();
        })), weight());
        TextView next = action("›", GREEN, PALE, () -> move(1));
        next.setContentDescription("下一时段");
        boolean future = range == Range.DAY ? !selected.isBefore(today)
                : range == Range.MONTH ? !month.isBefore(current) : !quarter.isBefore(Overtime.quarterStart(current));
        next.setEnabled(!future);
        next.setAlpha(future ? 0.35f : 1f);
        navigation.addView(next);
        summary.addView(navigation);

        LocalDate from = range == Range.DAY ? selected
                : Overtime.start(range == Range.MONTH ? month : quarter);
        LocalDate through = range == Range.DAY ? selected
                : Overtime.end(range == Range.MONTH ? month : quarter.plusMonths(2));
        NavigableMap<LocalDate, Records.Punch> shown = punches.subMap(from, true, through, true);
        int total = 0;
        int calculated = 0;
        for (Map.Entry<LocalDate, Records.Punch> entry : shown.entrySet()) {
            int minutes = minutes(entry.getKey(), entry.getValue());
            if (minutes >= 0) {
                total += minutes;
                calculated++;
            }
        }
        if (range == Range.DAY) {
            Records.Punch punch = punches.get(selected);
            String daySummary = punch == null ? "无"
                    : clockOrDash(punch.arrival) + " - " + clockOrDash(punch.departure) + "："
                    + AttendanceHeatmap.overtimeLabel(punch, minutes(selected, punch));
            add(summary, text(daySummary, 20, DARK_GREEN, true), 10);
            add(summary, text(dayDescription(selected), 13, MUTED, false), 4);
        } else {
            add(summary, text(calculated == 0 ? "—" : duration(total), 32, DARK_GREEN, true), 10);
            add(summary, text(dateSpan(from, through) + "  ·  已统计 " + calculated + " 天", 13, MUTED, false), 4);
        }
        add(root, summary, 9);

        add(root, text("打卡明细", 19, INK, true), 21);
        addAttendanceDetails(punches, today);
        boolean reminder = ReminderWorker.allowed(this);
        int syncTime = ReminderWorker.scheduleMinutes(this);
        add(root, action("每日自动刷新  " + PunchTime.display(syncTime), GREEN, PALE,
                () -> pickTime("每日自动刷新时间", syncTime, syncTime, minutes -> {
                    ReminderWorker.changeTime(this, minutes);
                    render();
                })), 18);
        add(root, action(reminder ? "通知设置" : "开启通知", GREEN, BG, this::notificationSettings), 3);
        if (credentials.remembered()) {
            add(root, action("OA 账号设置", GREEN, BG, this::showOaSettings), 3);
        }
        add(root, action("查看统计规则", GREEN, BG, this::showRules), 3);
    }

    private void move(int direction) {
        if (range == Range.DAY) {
            selected = selected.plusDays(direction);
        } else {
            YearMonth next = Overtime.period(selected).plusMonths(range == Range.MONTH ? direction : 3L * direction);
            selected = Overtime.end(next);
            if (selected.isAfter(Overtime.today())) {
                selected = Overtime.today();
            }
        }
        render();
    }

    private void addAttendanceDetails(NavigableMap<LocalDate, Records.Punch> punches, LocalDate today) {
        LinearLayout card = panel(WHITE, 18);
        LinearLayout line = row();
        LinearLayout detail = column();
        TextView dateLabel = text("", 14, INK, true);
        TextView times = text("", 14, MUTED, false);
        detail.addView(dateLabel);
        add(detail, times, 5);
        line.addView(detail, weight());
        line.addView(action("编辑", GREEN, PALE, () -> edit(detailDate, date -> detailDate = date)));
        Consumer<LocalDate> showDate = date -> {
            detailDate = date;
            Records.Punch punch = punches.get(date);
            dateLabel.setText(date + " | " + dayDescription(date));
            times.setText(punch == null ? "上班 无　下班 无"
                    : "上班 " + clockOrDash(punch.arrival) + "　下班 " + clockOrDash(punch.departure));
        };
        showDate.accept(detailDate);
        heatmap = new AttendanceHeatmap(this, punches, today, detailDate, heatmapOffset, showDate);
        card.addView(heatmap);
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
                        + "每天满 1 小时才计入，随后的时长向下取整到半小时。内置 2025–2027 年官方放假和调休安排，其他年份按自然星期计算。")
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

    private void edit(LocalDate date) {
        edit(date, updated -> selected = updated);
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
                    range = Range.MONTH;
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

    private static String dateSpan(LocalDate from, LocalDate to) {
        return from.format(DATE_SHORT) + " – " + to.format(DATE_SHORT);
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

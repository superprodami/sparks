package local.overtime;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class ReminderWorker extends Worker {
    private static final String REFRESH_WORK = "daily-attendance-reminder";
    private static final String PUNCH_WORK = "workday-punch-reminder";
    private static final String REFRESH_CHANNEL = "attendance";
    private static final String PUNCH_CHANNEL = "punch_reminder";
    private static final String SCHEDULE_MINUTES = "schedule_minutes";
    private static final String REFRESH_MINUTES = "refresh_minutes";
    private static final String PUNCH_MINUTES = "punch_minutes";
    private static final String PUNCH_DATE = "punch_date";
    private static final int DEFAULT_MINUTES = Overtime.REFRESH_TIME.toSecondOfDay() / 60;
    private static final int DEFAULT_PUNCH_MINUTES = Overtime.PUNCH_REMINDER_TIME.toSecondOfDay() / 60;
    static final String OPEN_TODAY = "local.overtime.OPEN_TODAY";
    static final String OPEN_DATE = "attendance_date";
    static final String LAST_SYNC = "last_sync";
    private static final DateTimeFormatter SYNC_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    static NotificationManager manager(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                REFRESH_CHANNEL, "昨日考勤更新", NotificationManager.IMPORTANCE_DEFAULT));
        manager.createNotificationChannel(new NotificationChannel(
                PUNCH_CHANNEL, "工作日打卡提醒", NotificationManager.IMPORTANCE_DEFAULT));
        return manager;
    }

    static boolean allowed(Context context) {
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                && manager(context).areNotificationsEnabled();
    }

    static boolean allowed(Context context, boolean punch) {
        String channel = punch ? PUNCH_CHANNEL : REFRESH_CHANNEL;
        return allowed(context) && manager(context).getNotificationChannel(channel).getImportance()
                != NotificationManager.IMPORTANCE_NONE;
    }

    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("daily_attendance", Context.MODE_PRIVATE);
    }

    static int scheduleMinutes(Context context) {
        SharedPreferences prefs = settings(context);
        int previous = prefs.getInt(SCHEDULE_MINUTES, DEFAULT_PUNCH_MINUTES);
        return prefs.getInt(REFRESH_MINUTES, previous == DEFAULT_PUNCH_MINUTES ? DEFAULT_MINUTES : previous);
    }

    static int punchMinutes(Context context) {
        return settings(context).getInt(PUNCH_MINUTES, DEFAULT_PUNCH_MINUTES);
    }

    static void ensureScheduled(Context context) {
        boolean migrated = settings(context).contains(REFRESH_MINUTES);
        if (!migrated) {
            settings(context).edit().putInt(REFRESH_MINUTES, scheduleMinutes(context)).apply();
        }
        scheduleRefresh(context, migrated ? ExistingPeriodicWorkPolicy.KEEP : ExistingPeriodicWorkPolicy.UPDATE);
        if (noticeEnabled(context, true)) {
            schedulePunch(context, ExistingPeriodicWorkPolicy.KEEP);
        }
    }

    static void changeTime(Context context, int minutes) {
        if (minutes == scheduleMinutes(context)) {
            return;
        }
        settings(context).edit().putInt(REFRESH_MINUTES, minutes).apply();
        scheduleRefresh(context, ExistingPeriodicWorkPolicy.UPDATE);
    }

    static void changePunchTime(Context context, int minutes) {
        if (minutes == punchMinutes(context)) {
            return;
        }
        settings(context).edit().putInt(PUNCH_MINUTES, minutes).apply();
        if (noticeEnabled(context, true)) {
            schedulePunch(context, ExistingPeriodicWorkPolicy.UPDATE);
        }
    }

    static boolean noticeEnabled(Context context, boolean punch) {
        return settings(context).getBoolean(punch ? PUNCH_CHANNEL : REFRESH_CHANNEL, true);
    }

    static void setNoticeEnabled(Context context, boolean punch, boolean enabled) {
        settings(context).edit().putBoolean(punch ? PUNCH_CHANNEL : REFRESH_CHANNEL, enabled).apply();
        if (punch) {
            if (enabled) {
                schedulePunch(context, ExistingPeriodicWorkPolicy.KEEP);
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(PUNCH_WORK);
                manager(context).cancel(2);
            }
        } else if (!enabled) {
            manager(context).cancel(1);
        }
    }

    private static androidx.work.Operation scheduleRefresh(Context context, ExistingPeriodicWorkPolicy policy) {
        int minutes = scheduleMinutes(context);
        LocalTime time = LocalTime.of(minutes / 60, minutes % 60);
        ZonedDateTime next = Overtime.nextReminder(ZonedDateTime.now(Overtime.ZONE), time);
        return schedule(context, REFRESH_WORK, policy, next,
                new Data.Builder().putInt(SCHEDULE_MINUTES, minutes).build());
    }

    private static androidx.work.Operation schedulePunch(Context context, ExistingPeriodicWorkPolicy policy) {
        int minutes = punchMinutes(context);
        LocalTime time = LocalTime.of(minutes / 60, minutes % 60);
        ZonedDateTime next = Overtime.nextPunchReminder(ZonedDateTime.now(Overtime.ZONE), time);
        return schedule(context, PUNCH_WORK, policy, next,
                new Data.Builder().putInt(SCHEDULE_MINUTES, minutes)
                        .putString(PUNCH_DATE, next.toLocalDate().toString()).build());
    }

    private static androidx.work.Operation schedule(Context context, String work, ExistingPeriodicWorkPolicy policy,
            ZonedDateTime next, Data input) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(ReminderWorker.class, 1, TimeUnit.DAYS)
                .setInputData(input)
                .setNextScheduleTimeOverride(next.toInstant().toEpochMilli())
                .build();
        return WorkManager.getInstance(context).enqueueUniquePeriodicWork(work, policy, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String punchDate = getInputData().getString(PUNCH_DATE);
        boolean punch = punchDate != null;
        int scheduledMinutes = getInputData().getInt(SCHEDULE_MINUTES, -1);
        if (punch && (!noticeEnabled(context, true) || scheduledMinutes != punchMinutes(context))) {
            return Result.success();
        }
        try {
            (punch ? schedulePunch(context, ExistingPeriodicWorkPolicy.UPDATE)
                    : scheduleRefresh(context, ExistingPeriodicWorkPolicy.UPDATE)).getResult().get();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (ExecutionException error) {
            Log.e("AttendanceReminder", "Unable to schedule the next reminder", error);
            return Result.retry();
        }
        LocalDate today = Overtime.today();
        if (punch) {
            if (scheduledMinutes == punchMinutes(context)
                    && today.toString().equals(punchDate) && !ChinaWorkCalendar.day(today).rest) {
                notify(context, true, today, "记得检查今日是否打卡~", null);
            }
            return Result.success();
        }
        if (scheduledMinutes != scheduleMinutes(context)) {
            return Result.success();
        }
        LocalDate yesterday = today.minusDays(1);
        Records records = new Records(context);
        String title;
        try {
            synchronizeOa(context, records, yesterday);
            title = "昨日考勤更新成功";
        } catch (IOException error) {
            saveStatus(context, error.getMessage());
            title = "昨日考勤更新失败";
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }
        notify(context, false, yesterday, title, monthSummary(records, today));
        return Result.success();
    }

    private static String monthSummary(Records records, LocalDate today) {
        YearMonth month = Overtime.period(today);
        int total = 0;
        for (Map.Entry<LocalDate, Records.Punch> entry : records.all()
                .subMap(Overtime.start(month), true, Overtime.end(month), true).entrySet()) {
            Records.Punch punch = entry.getValue();
            int minutes = Overtime.minutes(entry.getKey(), punch.arrival, punch.departure);
            if (minutes >= 0) {
                total += minutes;
            }
        }
        return "本月时长 " + total / 60 + (total % 60 == 0 ? "" : ".5") + "h/20h";
    }

    private void notify(Context context, boolean punch, LocalDate date, String title, String message) {
        if (!noticeEnabled(context, punch) || !allowed(context, punch)) {
            return;
        }
        NotificationManager manager = manager(context);
        String channel = punch ? PUNCH_CHANNEL : REFRESH_CHANNEL;
        int id = punch ? 2 : 1;
        Intent open = new Intent(context, MainActivity.class).setAction(OPEN_TODAY)
                .putExtra(OPEN_DATE, (int) date.toEpochDay());
        PendingIntent action = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notice = new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(action)
                .setAutoCancel(true)
                .build();
        manager.notify(id, notice);
    }

    static SharedPreferences state(Context context) {
        return context.getSharedPreferences("oa_sync", Context.MODE_PRIVATE);
    }

    static void saveStatus(Context context, String message) {
        String time = ZonedDateTime.now(Overtime.ZONE).format(SYNC_TIME);
        state(context).edit().putString(LAST_SYNC, time + "  " + message).apply();
    }

    private void synchronizeOa(Context context, Records records, LocalDate yesterday)
            throws IOException, InterruptedException {
        OaCredentials credentials = new OaCredentials(context);
        OaCredentials.Account account;
        try {
            account = credentials.read();
        } catch (IOException | GeneralSecurityException | JSONException error) {
            throw new IOException("无法读取 OA 登录信息，请重新登录", error);
        }
        if (account == null) {
            throw new IOException("请先登录 OA");
        }
        Handler main = new Handler(Looper.getMainLooper());
        CompletableFuture<OaResult> result = new CompletableFuture<>();
        AtomicReference<OaSession> running = new AtomicReference<>();
        LocalDate firstQuery = records.needsBoundaryRepair(yesterday) ? yesterday.minusDays(1) : yesterday;
        main.post(() -> {
            if (result.isDone()) {
                return;
            }
            try {
                OaSession session = new OaSession(context, account, firstQuery, yesterday, new OaSession.Listener() {
                    @Override
                    public void status(String message) {}

                    @Override
                    public void authenticated() {}

                    @Override
                    public void success(OaResult data) {
                        result.complete(data);
                    }

                    @Override
                    public void failure(String message) {
                        result.completeExceptionally(new IOException(message));
                    }
                });
                running.set(session);
                session.layoutForBackground();
                session.start(false);
            } catch (IOException error) {
                result.completeExceptionally(new IOException("无法加载 OA 同步组件", error));
            }
        });
        try {
            OaResult data = result.get(55, TimeUnit.SECONDS);
            if (!credentials.remembered()) {
                throw new IOException("OA 已退出登录");
            }
            if (!ChinaWorkCalendar.day(yesterday).rest && !data.punches.containsKey(yesterday)) {
                throw new IOException("OA 尚未返回昨日考勤");
            }
            int days = data.apply(records, yesterday, yesterday);
            saveStatus(context, data.describeImport(yesterday, yesterday, days, true));
        } catch (ExecutionException error) {
            throw new IOException(error.getCause().getMessage(), error);
        } catch (TimeoutException error) {
            throw new IOException("OA 同步超时", error);
        } finally {
            result.cancel(false);
            main.post(() -> {
                if (running.get() != null) {
                    running.get().close();
                }
            });
        }
    }
}

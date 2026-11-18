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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class ReminderWorker extends Worker {
    private static final String WORK = "daily-attendance-reminder";
    private static final String CHANNEL = "attendance";
    private static final String SCHEDULE_MINUTES = "schedule_minutes";
    private static final int DEFAULT_MINUTES = Overtime.REMINDER.toSecondOfDay() / 60;
    static final String OPEN_TODAY = "local.overtime.OPEN_TODAY";
    static final String LAST_SYNC = "last_sync";
    private static final DateTimeFormatter SYNC_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    static NotificationManager manager(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "每日考勤更新", NotificationManager.IMPORTANCE_DEFAULT));
        return manager;
    }

    static boolean allowed(Context context) {
        NotificationManager manager = manager(context);
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                && manager.areNotificationsEnabled()
                && manager.getNotificationChannel(CHANNEL).getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("daily_attendance", Context.MODE_PRIVATE);
    }

    static int scheduleMinutes(Context context) {
        return settings(context).getInt(SCHEDULE_MINUTES, DEFAULT_MINUTES);
    }

    static void ensureScheduled(Context context) {
        boolean migrated = settings(context).contains(SCHEDULE_MINUTES);
        schedule(context, migrated ? ExistingPeriodicWorkPolicy.KEEP : ExistingPeriodicWorkPolicy.UPDATE);
        if (!migrated) {
            settings(context).edit().putInt(SCHEDULE_MINUTES, DEFAULT_MINUTES).apply();
        }
    }

    static void changeTime(Context context, int minutes) {
        if (settings(context).contains(SCHEDULE_MINUTES) && minutes == scheduleMinutes(context)) {
            return;
        }
        settings(context).edit().putInt(SCHEDULE_MINUTES, minutes).apply();
        schedule(context, ExistingPeriodicWorkPolicy.UPDATE);
    }

    private static androidx.work.Operation schedule(Context context, ExistingPeriodicWorkPolicy policy) {
        int minutes = scheduleMinutes(context);
        LocalTime time = LocalTime.of(minutes / 60, minutes % 60);
        long next = Overtime.nextReminder(ZonedDateTime.now(Overtime.ZONE), time).toInstant().toEpochMilli();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(ReminderWorker.class, 1, TimeUnit.DAYS)
                .setInputData(new Data.Builder().putInt(SCHEDULE_MINUTES, minutes).build())
                .setNextScheduleTimeOverride(next)
                .build();
        return WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, policy, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            schedule(context, ExistingPeriodicWorkPolicy.UPDATE).getResult().get();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (ExecutionException error) {
            Log.e("AttendanceReminder", "Unable to schedule the next reminder", error);
            return Result.retry();
        }
        if (getInputData().getInt(SCHEDULE_MINUTES, -1) != scheduleMinutes(context)) {
            return Result.success();
        }
        try {
            LocalDate today = Overtime.today();
            OaResult data = synchronizeOa(context, today);
            notifySuccess(context, data.punches.get(today));
        } catch (IOException error) {
            saveStatus(context, error.getMessage());
            notify(context, "今日考勤更新失败", error.getMessage());
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }
        return Result.success();
    }

    static String punchSummary(Records.Punch punch) {
        if (punch == null || (punch.arrival == Overtime.MISSING && punch.departure == Overtime.MISSING)) {
            return null;
        }
        return PunchTime.display(punch.arrival) + " -> "
                + PunchTime.display(Overtime.departureOnWorkday(punch.departure));
    }

    private void notifySuccess(Context context, Records.Punch punch) {
        String times = punchSummary(punch);
        if (times == null) {
            notify(context, "今日考勤已更新，暂未获取数据", null);
        } else {
            notify(context, "今日考勤已更新", times);
        }
    }

    private void notify(Context context, String title, String message) {
        if (!allowed(context)) {
            return;
        }
        Intent open = new Intent(context, MainActivity.class).setAction(OPEN_TODAY);
        PendingIntent action = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notice = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(action)
                .setAutoCancel(true)
                .build();
        manager(context).notify(1, notice);
    }

    static SharedPreferences state(Context context) {
        return context.getSharedPreferences("oa_sync", Context.MODE_PRIVATE);
    }

    static void saveStatus(Context context, String message) {
        String time = ZonedDateTime.now(Overtime.ZONE).format(SYNC_TIME);
        state(context).edit().putString(LAST_SYNC, time + "  " + message).apply();
    }

    static String describeSync(OaResult result, int days) {
        LocalDate today = Overtime.today();
        if (!result.covers(today) || !result.covers(today.minusDays(1))) {
            return "OA 已读取 " + days + " 天，日期范围未覆盖今天和昨天";
        }
        if (days == 0 && !result.punches.containsKey(today) && !result.punches.containsKey(today.minusDays(1))) {
            return "OA 尚未返回今天或昨天的日结果";
        }
        return "OA 已同步 " + days + " 天打卡";
    }

    private OaResult synchronizeOa(Context context, LocalDate today) throws IOException, InterruptedException {
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
        LocalDate from = today.minusDays(1);
        Records records = new Records(context);
        LocalDate firstQuery = records.needsBoundaryRepair(from) ? from.minusDays(1) : from;
        main.post(() -> {
            if (result.isDone()) {
                return;
            }
            try {
                OaSession session = new OaSession(context, account, firstQuery, today, new OaSession.Listener() {
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
            if (credentials.remembered()) {
                int days = data.apply(records, from, today);
                saveStatus(context, describeSync(data, days));
                return data;
            }
            throw new IOException("OA 已退出登录");
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

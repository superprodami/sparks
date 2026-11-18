# Overtime 考勤统计

Android 8.1 及以上。导入公司 OA 打卡、手动补录、查看月度和季度加班时长。打卡记录和加密保存的 OA 账号信息留在手机上。

## 使用

- 首次点「刷新 OA」或「OA 导入」时输入 OA 账号。可导入本月、上月，或指定日期范围。
- 点击热力表上的日期查看或补录上下班时间。默认每天北京时间 20:30 自动刷新今天和昨天，可在首页修改时间。
- 考勤月为每月 26 日至次月 25 日。工作日从 18:30 算加班。休息日按上下班时间算，扣除与 12:00–13:00、18:00–18:30 重合的部分。单日不足 1 小时计 0，满 1 小时后向下取整到半小时。

## 自己构建

准备 JDK 17 或 21 和 Android SDK 35，设置 JAVA_HOME 和 ANDROID_HOME，然后在项目根目录运行：

~~~powershell
.\gradlew.bat :app:assembleDebug
~~~

生成的安装包在 app/build/outputs/apk/debug/。

## Releases

从 [Overtime 1.4.3](https://github.com/superprodami/sparks/releases/tag/overtime-v1.4.3) 下载 APK。自动导入适用于当前配置的公司 OA。

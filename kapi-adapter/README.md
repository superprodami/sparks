# 咔皮记账 · 京东淘宝适配

在京东或淘宝打开已支付订单详情时，用咔皮原生浮窗确认记账。备注取商品名，订单号用于重复提醒。

## 安装

1. 从 [Release](https://github.com/superprodami/sparks/releases/tag/kapi-adapter-v1.0.4) 下载并安装 Kapi-Adapter-1.0.4.apk。需要咔皮记账 1.30.01、SukiSU Root 和 Vector。淘宝 10.65.0 已验证。
2. 给模块 Root 权限。打开模块点「启用并应用」，Vector 作用域只选淘宝。系统设置里允许模块自启动，电池设为「无限制」。
3. 保持咔皮的自动记账和无障碍服务开启。打开已支付订单详情，展开订单信息，在浮窗里核对并确认。卸载前先点「停用并恢复」。

## 从源码构建（可选）

在 Windows 上准备 Python 3.10+（命令为 python）、JDK 17、Android SDK 35 和 Build Tools 35.0.0，设置 JAVA_HOME 和 ANDROID_HOME。

把自己设备上的咔皮 1.30.01 原版 APK 放到 reference/installed-kapi-1.30.01.apk。脚本会校验 APK，再构建适配模块。在项目根目录依次运行：

~~~powershell
.\native\build.ps1
.\taobao-bridge\build.ps1
~~~

生成的安装包在 taobao-bridge/咔皮京东淘宝适配.apk。重新生成签名后，无法直接覆盖用不同密钥签名的版本。

## 安装包

当前源码版本为 1.0.4。模块安装包内含修改后的咔皮 APK，供本地构建使用。

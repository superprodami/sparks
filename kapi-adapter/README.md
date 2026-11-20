# 咔皮记账 · 京东淘宝适配

在京东或淘宝打开已支付订单详情时，用咔皮原生浮窗确认记账。备注取商品名，订单号用于重复提醒。

## 安装与使用

1. 下载并安装 [咔皮京东淘宝适配 1.0.5](https://github.com/superprodami/sparks/releases/tag/kapi-adapter-v1.0.5)。
2. 在 LSPosed 中启用模块，将咔皮和淘宝加入作用域，并保留咔皮的无障碍服务。
3. 重新打开相关应用。在京东或淘宝打开已支付订单详情，在咔皮浮窗中确认记账。

已在 Android 15、LSPosed 2.2.1（7942，API 102）和原版咔皮 1.30.01 上验证可用。适配模块由框架加载，无需单独授予 Root 权限，也无需挂载替换咔皮的 APK。

Vector 2.2（3080）曾在咔皮加入作用域后导致启动闪退。使用该版本时，请保持模块停用。

## 旧版恢复

[1.0.4](https://github.com/superprodami/sparks/releases/tag/kapi-adapter-v1.0.4) 使用 Xposed API 82 处理淘宝，并通过 Root 把修改过的 APK 挂载到咔皮的安装路径。软重启可能保留此挂载，导致 PackageManager 重新扫描到无法通过签名校验的 APK。覆盖安装、卸载或软重启前，先在 1.0.4 里点「停用并恢复」，确认咔皮能正常打开。

## 构建

在 Windows 上准备 JDK 17、Android SDK 35 和 Build Tools 35.0.0，设置 `JAVA_HOME` 和 `ANDROID_HOME`。构建时从 Maven Central 获取 libxposed API 102.0.0。在本目录运行：

~~~powershell
.\taobao-bridge\build.ps1
~~~

安装包生成在 `taobao-bridge/Kapi-Adapter-1.0.5.apk`。需要覆盖安装旧版时，用 `-Keystore` 指定旧版的签名密钥。

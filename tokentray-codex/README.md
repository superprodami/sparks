# TokenTray Codex 定制版

Windows 托盘额度工具，基于 [messy-snail/tokentray](https://github.com/messy-snail/tokentray) 0.1.1 修改。

## 下载与运行

1. 打开 [TokenTray 的 Release](https://github.com/superprodami/sparks/releases/tag/tokentray-v0.1.1-codex.1)，下载 `tokentray-windows-x64.zip`。
2. 解压到固定目录，例如 `D:\Apps\TokenTray`。
3. 双击解压目录中的 **tokentray-gui.exe**。

压缩包自带 Python 和运行依赖，支持 Windows x64。请保留整个解压目录，EXE 依赖旁边的 `_internal` 文件夹。当前构建没有代码签名。

先安装 Codex 并通过 ChatGPT 账号登录。工具使用本机已有登录凭据查询额度，API Key 登录方式不提供 ChatGPT 套餐额度。

```text
tokentray/
  tokentray-gui.exe   # 双击启动托盘程序
  tokentray.exe       # 命令行查询与控制
  _internal/         # Python、Qt 和运行依赖
  README.md
  LICENSE
```

## 使用

- 点击右下角托盘图标打开面板。图标隐藏时点击时钟旁的 `^`。
- 外圈显示 **5 小时剩余额度**，内圈显示 **7 天剩余额度**。
- 面板中「显示：开／关」独立控制对应圆环，选择会自动保存。
- 两圈均关闭时保留小圆点，仍可点击打开面板。
- 悬浮提示为 `5h 92% - 7d 62%`。
- 右键勾选 **Start at login** 可登录自启，点击 **Quit** 可退出。

启用自启动后请保留程序目录。移动目录后，在新位置启动程序以更新登记路径。

## 刷新与网络

程序定期查询额度。点击 **Refresh now** 会重建网络客户端并重新读取当前代理设置。

- `live` 表示成功获取实时数据。
- `cached` 表示使用缓存数据。
- `stale` 表示刷新失败，当前显示旧数据。
- `Window reset — awaiting fresh data` 表示窗口已重置，正在等待新数据。此时 `~0%` 无法代表实际余量。

请求至少间隔 30 秒，连续点击可能返回缓存。连接失败时，确认本机网络和系统代理能访问 ChatGPT 后再刷新。

额度查询使用登录 Token 访问用量接口，不调用模型生成内容。

## 从源码运行

需要 Git 和 [uv](https://docs.astral.sh/uv/)。下载或克隆本仓库后，在仓库目录执行以下命令。

```powershell
uv sync --python 3.13
uv run tokentray
```

启动前先退出旧版 TokenTray，避免单实例机制转到已有进程。

## 打包与发布

在 Windows x64 上执行以下脚本，生成 `dist/tokentray-windows-x64.zip` 和 SHA-256 校验文件。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1
```

构建完成后，将 ZIP 和 SHA-256 校验文件作为 GitHub Release 附件上传。

## 配置与定制

正常启动时，配置位于 `%LOCALAPPDATA%\tokentray\config.toml`。Codex 桌面应用内执行命令可能读写其应用包内的 AppData 副本，排查时需确认实际配置文件。

本版修改包括 Codex 双环、显示开关、悬浮提示、自启动绝对路径、配置重新加载和手动刷新连接。重新安装上游版本会覆盖这些代码。

本项目沿用 MIT 许可证，随包字体和图标的许可证保留在 `src/tokentray/resources/`。

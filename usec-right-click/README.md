# USEC 右键菜单

适用于在 USEC 环境中用 RabbitVCS 管理 SVN 工作副本的场景。如果 SVN 更新或提交要求使用明文模式，而日常读写文件需要密文模式，可以在工作副本的空白处右键完成切换。

1. 更新或提交前，选择 **切换为明文**，看到结果通知后再执行 SVN 操作。
2. SVN 操作结束后，选择 **切换为密文**，再继续读写文件。

两项操作和 RabbitVCS 的「更新」「提交」同级。它们切换的是 USEC 的工作模式，当前目录只是操作入口。如果已经处于目标模式，菜单会提示当前模式并保持原样。

## 环境

- Ubuntu 20.04、Nautilus 3.36。
- `/usr/bin/usec` 可由运行 Nautilus 的账户执行。工具沿用 `usec` 的交互流程，选项 `9` 显示当前模式并请求确认，选项 `8` 退出。

## 安装

从 GitHub 下载仓库或运行 `git clone https://github.com/superprodami/sparks.git`，在 Ubuntu 终端进入仓库的 `usec-right-click` 目录。运行 `install.py` 时使用运行 Nautilus 的同一个账户。

```bash
sudo apt install python3-nautilus python3-pexpect gir1.2-notify-0.7
python3 install.py
```

如果终端已是 `root`，第一行可去掉 `sudo`。等待进行中的文件操作完成，执行 `nautilus -q`，再重新打开「文件」。

## 全局快捷键

安装工具后，打开 Ubuntu 的「设置 → 键盘快捷键」，滚到最下方点「＋」。分别添加下面两项，把对应的一整行粘贴到「命令」栏，再点击「设置快捷键」并按下要绑定的组合键。

**切换为明文**（例如 `Ctrl+Alt+P`）

```bash
sh -c 'if msg=$(/usr/bin/python3 "${XDG_DATA_HOME:-$HOME/.local/share}/usec-right-click/usec_mode_switch.py" "$1" 2>&1); then notify-send "USEC" "$msg"; else notify-send -u critical "USEC 切换失败" "$msg"; fi' usec plaintext
```

**切换为密文**（例如 `Ctrl+Alt+M`）

```bash
sh -c 'if msg=$(/usr/bin/python3 "${XDG_DATA_HOME:-$HOME/.local/share}/usec-right-click/usec_mode_switch.py" "$1" 2>&1); then notify-send "USEC" "$msg"; else notify-send -u critical "USEC 切换失败" "$msg"; fi' usec ciphertext
```

如果系统缺少 `notify-send`，执行 `sudo apt install libnotify-bin`。

## 卸载

在同一账户下，进入源码目录执行以下命令，再重新打开「文件」。

```bash
python3 install.py --uninstall
nautilus -q
```

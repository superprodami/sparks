import argparse
import os
from pathlib import Path
import shutil


source = Path(__file__).resolve().parent
data = Path(os.environ.get('XDG_DATA_HOME') or Path.home() / '.local' / 'share')
extension = data / 'nautilus-python' / 'extensions' / 'usec_mode_menu.py'
helper = data / 'usec-right-click' / 'usec_mode_switch.py'

parser = argparse.ArgumentParser(description='安装或卸载 USEC 右键菜单')
parser.add_argument('--uninstall', action='store_true')
if parser.parse_args().uninstall:
    extension.unlink(missing_ok=True)
    for cache in (extension.parent / '__pycache__').glob('usec_mode_menu.*.pyc'):
        cache.unlink()
    helper.unlink(missing_ok=True)
    helper.with_name('switch.lock').unlink(missing_ok=True)
    if helper.parent.is_dir() and not any(helper.parent.iterdir()):
        helper.parent.rmdir()
    print('已卸载。执行 nautilus -q 后重新打开文件管理器。')
else:
    for destination in (helper, extension):
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / destination.name, destination)
    print('已安装。执行 nautilus -q 后重新打开文件管理器。')

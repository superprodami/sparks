from pathlib import Path
import hashlib
import shutil
import sys

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / 'native'))
from package_standard import STOCK_SHA256

source = ROOT.parent / 'native' / 'build' / 'kapi-jd-taobao.apk'
patched_hash = hashlib.sha256(source.read_bytes()).hexdigest()
shutil.copy2(source, ROOT / 'assets' / 'kapi.apk')
template = (ROOT / 'assets' / 'bootstrap.sh.in').read_text(encoding='utf-8')
(ROOT / 'assets' / 'bootstrap.sh').write_text(template.replace('@STOCK_HASH@', STOCK_SHA256).replace('@PATCH_HASH@', patched_hash), encoding='utf-8', newline='\n')
(ROOT / 'src/cn/local/kapitb/BuildConfig.java').write_text(
    'package cn.local.kapitb;\npublic final class BuildConfig {\n'
    f'    public static final String PATCH_HASH = "{patched_hash}";\n'
    '}\n', encoding='utf-8')
print('Embedded Kapi SHA256:', patched_hash)

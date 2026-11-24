import argparse
import fcntl
from pathlib import Path
import sys

import pexpect


USEC = '/usr/bin/usec'
MENU = r'Please choose:'
CONFIRM = (r'Current [^\r\n]*?mode is (plaintext|ciphertext) mode,\s*'
           r'to switch to (plaintext|ciphertext) mode\?\s*\[y/n\]:')
RESULT = r'Current [^\r\n]*?mode is (plaintext|ciphertext) mode\.'
NAMES = {'plaintext': '明文', 'ciphertext': '密文'}
LOCK = Path(__file__).with_name('switch.lock')


def switch(target):
    child = pexpect.spawn(USEC, [], timeout=20, encoding='utf-8', codec_errors='replace')
    try:
        child.expect(MENU)
        child.send('9')
        child.expect(CONFIRM)
        current, offered = child.match.groups()

        changed = current != target
        if changed:
            if offered != target:
                raise RuntimeError('usec 提示的切换目标与菜单选择不一致。')
            child.send('y\r')
            child.expect(RESULT)
            actual = child.match.group(1)
            if actual != target:
                raise RuntimeError('usec 报告当前为{}模式，请核实。'.format(NAMES[actual]))
        else:
            child.send('n\r')

        child.expect(MENU)
        child.send('8')
        child.expect(pexpect.EOF)
        child.close()
        if child.exitstatus != 0:
            raise RuntimeError('usec 退出异常，请打开原软件核实当前模式。')
        return changed
    except (pexpect.TIMEOUT, pexpect.EOF) as error:
        raise RuntimeError('usec 提示与预期不符，或等待响应失败。请打开原软件核实当前模式。') from error
    finally:
        if not child.closed:
            child.close(force=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('target', choices=NAMES)
    target = parser.parse_args().target
    try:
        with LOCK.open('a') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise RuntimeError('已有切换操作正在进行。') from None
            changed = switch(target)
    except (RuntimeError, OSError, pexpect.ExceptionPexpect) as error:
        print(str(error), file=sys.stderr)
        return 1

    print(('已切换为' if changed else '当前已是') + NAMES[target] + '模式')
    return 0


if __name__ == '__main__':
    sys.exit(main())

"""Build a standard multidex variant from the retained embedded application DEXes."""
from pathlib import Path
import hashlib
import struct
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'native' / 'build'
STOCK_APK = ROOT / 'reference' / 'installed-kapi-1.30.01.apk'
STOCK_SHA256 = '85a39277816e84e9c0d755144354a4479ab73e84b91fa823d56b3542efb9b39f'
UNPACKED = BUILD / 'unpacked-1.30.01'
PAYMENT_OFFSET = 53035008
BUSINESS_OFFSETS = (49152, 10027008, 10043392, 18743296, 27279360, 35061760,
                    43991040, PAYMENT_OFFSET, 61374464, 66600960)


def extract():
    if hashlib.sha256(STOCK_APK.read_bytes()).hexdigest() != STOCK_SHA256:
        raise ValueError('Unexpected stock APK; this build requires the verified Kapi 1.30.01')
    UNPACKED.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(STOCK_APK) as source:
        container = source.read('classes.dex')
    for offset in BUSINESS_OFFSETS:
        size = struct.unpack_from('<I', container, offset + 32)[0]
        (UNPACKED / f'{offset}.dex').write_bytes(container[offset:offset + size])


def length(data, pos):
    first = data[pos]
    return (((first & 127) << 8) | data[pos + 1], pos + 2) if first & 128 else (first, pos + 1)


def manifest(data):
    # The shipped XML has a legal extended 12-byte root header; normalize to eight.
    data = bytearray(data[:8] + data[12:])
    struct.pack_into('<H', data, 2, 8)
    struct.pack_into('<I', data, 4, len(data))
    pool = 8
    count, styles, flags, start = struct.unpack_from('<IIII', data, pool + 8)
    if styles or not flags & 256:
        raise ValueError('Unexpected manifest string pool')
    strings = []
    for i in range(count):
        pos = pool + start + struct.unpack_from('<I', data, pool + 28 + 4 * i)[0]
        _, pos = length(data, pos)
        size, pos = length(data, pos)
        strings.append(data[pos:pos + size].decode())
    actual = strings.index('com.kapinote.ai.MainApp')
    changed = 0
    pos = pool
    while pos < len(data):
        kind, _, size = struct.unpack_from('<HHI', data, pos)
        if kind == 0x102 and strings[struct.unpack_from('<I', data, pos + 20)[0]] == 'application':
            attr_start, attr_size, attr_count = struct.unpack_from('<HHH', data, pos + 24)
            for i in range(attr_count):
                attr = pos + 16 + attr_start + i * attr_size
                name = strings[struct.unpack_from('<I', data, attr + 4)[0]]
                if name == 'name':
                    if strings[struct.unpack_from('<I', data, attr + 16)[0]] != 'com.Proxy.ShellApplication':
                        raise ValueError('Unexpected Application name')
                    struct.pack_into('<I', data, attr + 8, actual)
                    struct.pack_into('<I', data, attr + 16, actual)
                    changed += 1
        pos += size
    if changed != 1:
        raise ValueError('Expected one application name replacement')
    return bytes(data)


def package():
    with zipfile.ZipFile(STOCK_APK) as source, zipfile.ZipFile(BUILD / 'kapi-jd-standard-unaligned.apk', 'w') as output:
        for info in source.infolist():
            signature = info.filename.startswith('META-INF/') and info.filename.upper().endswith(('.RSA', '.SF', '.DSA', '.EC', '.MF'))
            if info.filename == 'classes.dex' or signature:
                continue
            if 'libxloader.so' in info.filename:
                continue
            value = source.read(info)
            if info.filename == 'AndroidManifest.xml':
                value = manifest(value)
            output.writestr(info, value)
        for index, offset in enumerate(BUSINESS_OFFSETS, 1):
            path = UNPACKED / f'{offset}.dex'
            name = 'classes.dex' if index == 1 else f'classes{index}.dex'
            value = (BUILD / 'payment-patched.dex').read_bytes() if offset == PAYMENT_OFFSET else path.read_bytes()
            if path.with_suffix('.dex.factory').exists():
                value = path.with_suffix('.dex.factory').read_bytes()
            output.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    print('Standard multidex variant built; business DEXes:', len(BUSINESS_OFFSETS))


if __name__ == '__main__':
    if sys.argv[1:] == ['--extract']:
        extract()
    else:
        package()

"""Rename LevelName inside a gzipped level.dat (NBT): usage rename_level.py <level.dat> <new name>."""
import gzip, struct, sys
path, new = sys.argv[1], sys.argv[2]
raw = gzip.open(path, 'rb').read()
key = b'\x08' + struct.pack('>H', len(b'LevelName')) + b'LevelName'
i = raw.find(key)
assert i >= 0, 'LevelName not found'
j = i + len(key)
old_len = struct.unpack('>H', raw[j:j + 2])[0]
old = raw[j + 2:j + 2 + old_len].decode('utf-8')
value = new.encode('utf-8')
out = raw[:j] + struct.pack('>H', len(value)) + value + raw[j + 2 + old_len:]
with gzip.open(path, 'wb') as f:
    f.write(out)
# python -I ignores PYTHONIOENCODING: a Cyrillic name on a cp1252 console would fail after the write.
sys.stdout.reconfigure(encoding='utf-8', errors='backslashreplace')
print(f'LevelName: {old!r} -> {new!r}')

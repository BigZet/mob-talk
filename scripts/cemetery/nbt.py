"""Minimal NBT (gzip, big-endian) reader and writer for structure templates. Values are tagged explicitly on write:
Byte/Short/Int/Long/Float/Double/String wrap scalars, python lists need TagList(type, items), dicts are compounds."""
import gzip
import struct

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class Tag:
    def __init__(self, kind, value):
        self.kind, self.value = kind, value

    def __repr__(self):
        return f"Tag({self.kind}, {self.value!r})"


def Byte(v): return Tag(BYTE, v)
def Int(v): return Tag(INT, v)
def String(v): return Tag(STRING, v)
def List(kind, items): return Tag(LIST, (kind, items))
def Compound(d): return Tag(COMPOUND, d)


def _kind(v):
    if isinstance(v, Tag): return v.kind
    if isinstance(v, dict): return COMPOUND
    if isinstance(v, str): return STRING
    if isinstance(v, bool): return BYTE
    if isinstance(v, int): return INT
    if isinstance(v, float): return DOUBLE
    raise TypeError(v)


def _payload(out, kind, v):
    if isinstance(v, Tag): v = v.value
    if kind == BYTE: out += struct.pack(">b", int(v))
    elif kind == SHORT: out += struct.pack(">h", v)
    elif kind == INT: out += struct.pack(">i", v)
    elif kind == LONG: out += struct.pack(">q", v)
    elif kind == FLOAT: out += struct.pack(">f", v)
    elif kind == DOUBLE: out += struct.pack(">d", v)
    elif kind == STRING:
        b = v.encode("utf-8"); out += struct.pack(">H", len(b)) + b
    elif kind == LIST:
        sub, items = v
        out += struct.pack(">bi", sub if items else END, len(items))
        for it in items: _payload(out, sub, it)
    elif kind == COMPOUND:
        for name, item in v.items():
            k = _kind(item)
            out += struct.pack(">b", k); _payload(out, STRING, name); _payload(out, k, item)
        out += b"\x00"
    elif kind == INT_ARRAY:
        out += struct.pack(">i", len(v)) + b"".join(struct.pack(">i", x) for x in v)
    else: raise TypeError(kind)


def write(path, root):
    out = bytearray(b"\x0a"); _payload(out, STRING, ""); _payload(out, COMPOUND, root)
    with open(path, "wb") as f:
        f.write(gzip.compress(bytes(out), mtime=0))


def read(path):
    data = gzip.decompress(open(path, "rb").read()); pos = [0]

    def take(fmt):
        size = struct.calcsize(fmt); r = struct.unpack_from(fmt, data, pos[0]); pos[0] += size; return r[0]

    def payload(kind):
        if kind == BYTE: return take(">b")
        if kind == SHORT: return take(">h")
        if kind == INT: return take(">i")
        if kind == LONG: return take(">q")
        if kind == FLOAT: return take(">f")
        if kind == DOUBLE: return take(">d")
        if kind == STRING:
            n = take(">H"); s = data[pos[0]:pos[0] + n].decode("utf-8"); pos[0] += n; return s
        if kind == LIST:
            sub = take(">b"); n = take(">i"); return [payload(sub) for _ in range(n)]
        if kind == COMPOUND:
            d = {}
            while True:
                k = take(">b")
                if k == END: return d
                name = payload(STRING); d[name] = payload(k)
        if kind == BYTE_ARRAY:
            n = take(">i"); r = data[pos[0]:pos[0] + n]; pos[0] += n; return r
        if kind == INT_ARRAY: return [take(">i") for _ in range(take(">i"))]
        if kind == LONG_ARRAY: return [take(">q") for _ in range(take(">i"))]
        raise TypeError(kind)

    take(">b"); payload(STRING); return payload(COMPOUND)

"""Minimal NBT reader/writer for structure templates (gzip, big-endian Java edition)."""
import gzip
import io
import struct

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class Tag:
    __slots__ = ("type", "value", "elem")

    def __init__(self, type_, value, elem=None):
        self.type = type_
        self.value = value
        self.elem = elem  # element type for lists

    def __repr__(self):
        return f"Tag({self.type}, {self.value!r})"


def _read_str(f):
    (n,) = struct.unpack(">H", f.read(2))
    return f.read(n).decode("utf-8", "surrogatepass")


def _read_payload(f, t):
    if t == BYTE:
        return Tag(t, struct.unpack(">b", f.read(1))[0])
    if t == SHORT:
        return Tag(t, struct.unpack(">h", f.read(2))[0])
    if t == INT:
        return Tag(t, struct.unpack(">i", f.read(4))[0])
    if t == LONG:
        return Tag(t, struct.unpack(">q", f.read(8))[0])
    if t == FLOAT:
        return Tag(t, struct.unpack(">f", f.read(4))[0])
    if t == DOUBLE:
        return Tag(t, struct.unpack(">d", f.read(8))[0])
    if t == BYTE_ARRAY:
        (n,) = struct.unpack(">i", f.read(4))
        return Tag(t, f.read(n))
    if t == STRING:
        return Tag(t, _read_str(f))
    if t == LIST:
        et, n = struct.unpack(">bi", f.read(5))
        return Tag(t, [_read_payload(f, et) for _ in range(n)], et)
    if t == COMPOUND:
        d = {}
        while True:
            (ct,) = struct.unpack(">b", f.read(1))
            if ct == END:
                return Tag(t, d)
            name = _read_str(f)
            d[name] = _read_payload(f, ct)
    if t == INT_ARRAY:
        (n,) = struct.unpack(">i", f.read(4))
        return Tag(t, list(struct.unpack(f">{n}i", f.read(4 * n))))
    if t == LONG_ARRAY:
        (n,) = struct.unpack(">i", f.read(4))
        return Tag(t, list(struct.unpack(f">{n}q", f.read(8 * n))))
    raise ValueError(f"bad tag {t}")


def _write_str(out, s):
    b = s.encode("utf-8", "surrogatepass")
    out.write(struct.pack(">H", len(b)))
    out.write(b)


def _write_payload(out, tag):
    t, v = tag.type, tag.value
    if t == BYTE:
        out.write(struct.pack(">b", v))
    elif t == SHORT:
        out.write(struct.pack(">h", v))
    elif t == INT:
        out.write(struct.pack(">i", v))
    elif t == LONG:
        out.write(struct.pack(">q", v))
    elif t == FLOAT:
        out.write(struct.pack(">f", v))
    elif t == DOUBLE:
        out.write(struct.pack(">d", v))
    elif t == BYTE_ARRAY:
        out.write(struct.pack(">i", len(v)))
        out.write(v)
    elif t == STRING:
        _write_str(out, v)
    elif t == LIST:
        et = tag.elem if v == [] else v[0].type
        out.write(struct.pack(">bi", et if et is not None else END, len(v)))
        for x in v:
            _write_payload(out, x)
    elif t == COMPOUND:
        for k, x in v.items():
            out.write(struct.pack(">b", x.type))
            _write_str(out, k)
            _write_payload(out, x)
        out.write(b"\x00")
    elif t == INT_ARRAY:
        out.write(struct.pack(">i", len(v)))
        out.write(struct.pack(f">{len(v)}i", *v))
    elif t == LONG_ARRAY:
        out.write(struct.pack(">i", len(v)))
        out.write(struct.pack(f">{len(v)}q", *v))
    else:
        raise ValueError(t)


def load(data):
    """Parses gzip'd (or raw) NBT bytes; returns (root name, compound tag)."""
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    f = io.BytesIO(data)
    (t,) = struct.unpack(">b", f.read(1))
    name = _read_str(f)
    return name, _read_payload(f, t)


def dump(name, tag):
    out = io.BytesIO()
    out.write(struct.pack(">b", tag.type))
    _write_str(out, name)
    _write_payload(out, tag)
    return gzip.compress(out.getvalue(), mtime=0)


def walk_strings(tag, fn):
    """Calls fn(str) on every string tag and compound key path; replaces strings by fn's return."""
    if tag.type == STRING:
        tag.value = fn(tag.value)
    elif tag.type == LIST:
        for x in tag.value:
            walk_strings(x, fn)
    elif tag.type == COMPOUND:
        for x in tag.value.values():
            walk_strings(x, fn)


def c(**kw):
    return Tag(COMPOUND, kw)

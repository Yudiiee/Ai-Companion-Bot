"""Tiny NBT reader/writer for structure files (keeps tag types so files round-trip)."""
import gzip, struct, io

class Tag:
    __slots__ = ('t', 'v', 'et')
    def __init__(self, t, v, et=None): self.t, self.v, self.et = t, v, et
    def __getitem__(self, k): return self.v[k]
    def get(self, k, d=None): return self.v.get(k, d) if isinstance(self.v, dict) else d
    def __repr__(self): return f'Tag({self.t},{self.v!r})'

def _rs(f):
    n = struct.unpack('>H', f.read(2))[0]; return f.read(n).decode('utf-8')

def _rp(f, t):
    if t == 1: return Tag(1, struct.unpack('>b', f.read(1))[0])
    if t == 2: return Tag(2, struct.unpack('>h', f.read(2))[0])
    if t == 3: return Tag(3, struct.unpack('>i', f.read(4))[0])
    if t == 4: return Tag(4, struct.unpack('>q', f.read(8))[0])
    if t == 5: return Tag(5, struct.unpack('>f', f.read(4))[0])
    if t == 6: return Tag(6, struct.unpack('>d', f.read(8))[0])
    if t == 7: n = struct.unpack('>i', f.read(4))[0]; return Tag(7, f.read(n))
    if t == 8: return Tag(8, _rs(f))
    if t == 9:
        et = f.read(1)[0]; n = struct.unpack('>i', f.read(4))[0]
        return Tag(9, [_rp(f, et) for _ in range(n)], et)
    if t == 10:
        m = {}
        while True:
            tt = f.read(1)[0]
            if tt == 0: return Tag(10, m)
            k = _rs(f); m[k] = _rp(f, tt)
    if t == 11:
        n = struct.unpack('>i', f.read(4))[0]; return Tag(11, list(struct.unpack('>%di' % n, f.read(4 * n))))
    if t == 12:
        n = struct.unpack('>i', f.read(4))[0]; return Tag(12, list(struct.unpack('>%dq' % n, f.read(8 * n))))
    raise ValueError(t)

def load(path):
    d = open(path, 'rb').read()
    if d[:2] == b'\x1f\x8b': d = gzip.decompress(d)
    f = io.BytesIO(d); assert f.read(1)[0] == 10; _rs(f)
    return _rp(f, 10)

def _ws(s):
    b = s.encode('utf-8'); return struct.pack('>H', len(b)) + b

def _wp(tag):
    t, v = tag.t, tag.v
    if t == 1: return struct.pack('>b', v)
    if t == 2: return struct.pack('>h', v)
    if t == 3: return struct.pack('>i', v)
    if t == 4: return struct.pack('>q', v)
    if t == 5: return struct.pack('>f', v)
    if t == 6: return struct.pack('>d', v)
    if t == 7: return struct.pack('>i', len(v)) + v
    if t == 8: return _ws(v)
    if t == 9:
        et = tag.et if tag.et is not None else (v[0].t if v else 0)
        return bytes([et]) + struct.pack('>i', len(v)) + b''.join(_wp(x) for x in v)
    if t == 10: return b''.join(bytes([x.t]) + _ws(k) + _wp(x) for k, x in v.items()) + b'\x00'
    if t == 11: return struct.pack('>i', len(v)) + struct.pack('>%di' % len(v), *v)
    if t == 12: return struct.pack('>i', len(v)) + struct.pack('>%dq' % len(v), *v)
    raise ValueError(t)

def save(root, path):
    open(path, 'wb').write(gzip.compress(bytes([10]) + _ws('') + _wp(root), mtime=0))

def ints(*xs): return Tag(9, [Tag(3, x) for x in xs], 3)

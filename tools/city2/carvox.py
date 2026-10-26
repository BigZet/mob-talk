"""The voxel engine of the city's cars (tools/city2/cars.py): a car is drawn as voxels of 1/16 block (a model unit, 6.25 cm),
its moving parts (doors, wheels, the steering wheel, the beacon) as their own voxels, and every visible face is painted
afterwards from where its texels lie in the car. The result is a mesh of greedy-merged quads with one texture atlas per
colour, read by io.github.mobtalk.client.game.CarClient.

Model space: x across the car (+x is the car's left, the driver's side), y up from the ground, z along the car (+z is the
front). Units are 1/16 block. Textures carry D = 2 texels a unit (32 a block), like the flat furniture.

Painting is vectorised: every texel of the atlas knows its point in the car, the face it lies on, the material of the voxel
behind it and whether it sits on the edge of a surface, so a car's look is a handful of numpy rules (see cars.py).
"""
import json
import math

import numpy as np
from PIL import Image

D = 2  # texels a model unit

# ---------------------------------------------------------------- materials
AIR, PAINT, GLASS, CHROME, BLACK, TIRE, RIM, HEAD, TAIL, TURN, SEAT, DASH, FLOOR, PLATE, STEER, UNDER, GRILLE, \
    BLUE, RED, TRIM, PAINT2, ALU, LAMP, REVERSE, MIRROR, CANVAS, RUST = range(27)
NAMES = {v: k for k, v in dict(AIR=AIR, PAINT=PAINT, GLASS=GLASS, CHROME=CHROME, BLACK=BLACK, TIRE=TIRE, RIM=RIM, HEAD=HEAD,
                                 TAIL=TAIL, TURN=TURN, SEAT=SEAT, DASH=DASH, FLOOR=FLOOR, PLATE=PLATE, STEER=STEER, UNDER=UNDER,
                                 GRILLE=GRILLE, BLUE=BLUE, RED=RED, TRIM=TRIM, PAINT2=PAINT2, ALU=ALU, LAMP=LAMP,
                                 REVERSE=REVERSE, MIRROR=MIRROR, CANVAS=CANVAS, RUST=RUST).items()}
TRANSLUCENT = (GLASS,)

# glow channels (bit masks in a quad's glow field): what lights up when
G_HEAD, G_TAIL, G_BLUE, G_RED, G_TURN = 1, 2, 4, 8, 16
GLOW_OF = {HEAD: G_HEAD, TAIL: G_TAIL, BLUE: G_BLUE, RED: G_RED, TURN: G_TURN, LAMP: G_HEAD}

# directions: index -> (axis, sign)
DIRS = [(0, 1), (0, -1), (1, 1), (1, -1), (2, 1), (2, -1)]


class Vox:
    """A block of voxels: material and part per cell. `origin` is the model point of cell (0,0,0)'s low corner, `s` the
    size of a cell in units."""

    def __init__(self, nx, ny, nz, origin, s=1.0, part=0):
        self.mat = np.zeros((nx, ny, nz), np.uint8)
        self.part = np.full((nx, ny, nz), part, np.uint8)
        self.origin = np.array(origin, float)
        self.s = float(s)
        self.local = None   # for a part's own voxels: the pivot they are drawn around (their coordinates are relative to it)

    @property
    def shape(self):
        return self.mat.shape

    def grids(self):
        """Model coordinates of every cell's centre, as three broadcastable arrays X, Y, Z."""
        nx, ny, nz = self.shape
        o, s = self.origin, self.s
        X = o[0] + (np.arange(nx) + 0.5) * s
        Y = o[1] + (np.arange(ny) + 0.5) * s
        Z = o[2] + (np.arange(nz) + 0.5) * s
        return X[:, None, None], Y[None, :, None], Z[None, None, :]

    def set(self, mask, mat, part=None):
        mask = np.broadcast_to(mask, self.shape)
        self.mat[mask] = mat
        if part is not None:
            self.part[mask] = part

    def where(self, fn, mat, part=None, only=None):
        """Sets cells whose centre satisfies fn(X, Y, Z) (and, if given, whose material is in `only`)."""
        X, Y, Z = self.grids()
        m = np.broadcast_to(fn(X, Y, Z), self.shape)
        if only is not None:
            m = m & np.isin(self.mat, only)
        self.set(m, mat, part)
        return m

    def box(self, x0, y0, z0, x1, y1, z1, mat, part=None, only=None):
        lo, hi = (min(x0, x1), min(y0, y1), min(z0, z1)), (max(x0, x1), max(y0, y1), max(z0, z1))
        return self.where(lambda X, Y, Z: (X > lo[0]) & (X < hi[0]) & (Y > lo[1]) & (Y < hi[1]) & (Z > lo[2]) & (Z < hi[2]), mat, part, only)

    def mbox(self, x0, x1, y0, y1, z0, z1, mat, part=None, only=None):
        """A box mirrored across x = 0 (x0..x1 given for the +x side)."""
        return self.where(lambda X, Y, Z: (abs(X) > x0) & (abs(X) < x1) & (Y > y0) & (Y < y1) & (Z > z0) & (Z < z1), mat, part, only)

    def solid(self):
        return self.mat > 0

    def lookup(self, P):
        """Cell indices for model points P (n,3); -1 where outside."""
        idx = np.floor((P - self.origin) / self.s).astype(int)
        ok = np.all((idx >= 0) & (idx < np.array(self.shape)), axis=1)
        return idx, ok


def pl(z, pts):
    """A piecewise-linear profile through (z, value) points (z ascending)."""
    zs = [p[0] for p in pts]
    vs = [p[1] for p in pts]
    return np.interp(z, zs, vs)


def erode(m):
    """Cells of m whose six neighbours are all in m."""
    out = m.copy()
    for a in range(3):
        for s in (1, -1):
            sh = np.zeros_like(m)
            src = [slice(None)] * 3
            dst = [slice(None)] * 3
            if s > 0:
                src[a], dst[a] = slice(1, None), slice(None, -1)
            else:
                src[a], dst[a] = slice(None, -1), slice(1, None)
            sh[tuple(dst)] = m[tuple(src)]
            out &= sh
    return out


def neighbour(arr, a, s, fill=0):
    """arr shifted so that out[i] = arr[i + s along axis a] (fill outside)."""
    out = np.full_like(arr, fill)
    src = [slice(None)] * 3
    dst = [slice(None)] * 3
    if s > 0:
        src[a], dst[a] = slice(1, None), slice(None, -1)
    else:
        src[a], dst[a] = slice(None, -1), slice(1, None)
    out[tuple(dst)] = arr[tuple(src)]
    return out


def exposure(v):
    """Per direction, which cells show a face that way."""
    mat, part = v.mat, v.part
    solid = mat > 0
    glass = np.isin(mat, TRANSLUCENT)
    out = []
    for a, s in DIRS:
        nm = neighbour(mat, a, s, 0)
        npart = neighbour(part, a, s, 255)
        nsolid = nm > 0
        nglass = np.isin(nm, TRANSLUCENT)
        e = solid & (~nsolid | (~glass & nglass) | (nsolid & (npart != part) & ~glass & ~nglass))
        out.append(e)
    return out


class Quad:
    __slots__ = ("vox", "dir", "plane", "b0", "b1", "c0", "c1", "part", "layer", "tw", "th", "u", "v", "glow")


def greedy(v, vid, exp):
    """Merges exposed faces into rectangles per slice, part and layer."""
    quads = []
    glass = np.isin(v.mat, TRANSLUCENT)
    for d, (a, s) in enumerate(DIRS):
        b, c = [x for x in range(3) if x != a]
        key = np.where(exp[d], v.part.astype(np.int32) * 2 + glass + 1, 0)
        n = v.shape[a]
        for i in range(n):
            sl = [slice(None)] * 3
            sl[a] = i
            k = key[tuple(sl)]  # 2D array over (b, c)
            if not k.any():
                continue
            k = k.copy()
            nb, nc = k.shape
            for bi in range(nb):
                row = k[bi]
                if not row.any():
                    continue
                ci = 0
                while ci < nc:
                    val = row[ci]
                    if val == 0:
                        ci += 1
                        continue
                    ce = ci
                    while ce < nc and k[bi, ce] == val:
                        ce += 1
                    be = bi + 1
                    while be < nb and np.all(k[be, ci:ce] == val):
                        be += 1
                    k[bi:be, ci:ce] = 0
                    q = Quad()
                    q.vox, q.dir = vid, d
                    q.plane = v.origin[a] + (i + (1 if s > 0 else 0)) * v.s
                    q.b0, q.b1 = v.origin[b] + bi * v.s, v.origin[b] + be * v.s
                    q.c0, q.c1 = v.origin[c] + ci * v.s, v.origin[c] + ce * v.s
                    q.part = int((val - 1) // 2)
                    q.layer = int((val - 1) % 2)
                    quads.append(q)
                    ci = ce
    return quads


class Atlas:
    """Shelf packing of the quads' texel rectangles."""

    def __init__(self, width):
        self.w = width
        self.x = self.y = self.row = 0

    def place(self, tw, th):
        if self.x + tw > self.w:
            self.x, self.y, self.row = 0, self.y + self.row, 0
        u, v = self.x, self.y
        self.x += tw
        self.row = max(self.row, th)
        return u, v

    def height(self):
        h = self.y + self.row
        p = 16
        while p < h:
            p *= 2
        return p


class Mesh:
    """All the faces of a car: the quads, their texels and, after paint(), the atlas images."""

    def __init__(self, voxes, parts):
        self.voxes = voxes            # list of Vox
        self.parts = parts            # list of dicts: name, role, pivot, ...
        self.exp = [exposure(v) for v in voxes]
        self.quads = []
        for vid, v in enumerate(voxes):
            self.quads += greedy(v, vid, self.exp[vid])
        # atlas layout: tall quads first
        order = sorted(range(len(self.quads)), key=lambda i: -self._size(self.quads[i])[1])
        width = 256
        area = sum(self._size(q)[0] * self._size(q)[1] for q in self.quads)
        while width * width < area * 1.3:
            width *= 2
        atlas = Atlas(width)
        for i in order:
            q = self.quads[i]
            q.tw, q.th = self._size(q)
            q.u, q.v = atlas.place(q.tw, q.th)
        self.W, self.H = width, atlas.height()
        self._texels()

    @staticmethod
    def _size(q):
        return max(1, round((q.b1 - q.b0) * D)), max(1, round((q.c1 - q.c0) * D))

    def _texels(self):
        """Per texel: atlas (u, v), model point, direction, vox id, cell index, material, part."""
        us, vs, P, dirs, vids = [], [], [], [], []
        for qi, q in enumerate(self.quads):
            a, s = DIRS[q.dir]
            b, c = [x for x in range(3) if x != a]
            ii, jj = np.meshgrid(np.arange(q.tw), np.arange(q.th), indexing="ij")
            ii, jj = ii.ravel(), jj.ravel()
            p = np.zeros((ii.size, 3))
            p[:, a] = q.plane
            p[:, b] = q.b0 + (ii + 0.5) * (q.b1 - q.b0) / q.tw
            p[:, c] = q.c0 + (jj + 0.5) * (q.c1 - q.c0) / q.th
            us.append(q.u + ii)
            vs.append(q.v + jj)
            P.append(p)
            dirs.append(np.full(ii.size, q.dir))
            vids.append(np.full(ii.size, q.vox))
        self.tu = np.concatenate(us)
        self.tv = np.concatenate(vs)
        self.Pv = np.concatenate(P)
        self.dir = np.concatenate(dirs)
        self.vid = np.concatenate(vids)
        n = self.Pv.shape[0]
        self.mat = np.zeros(n, np.uint8)
        self.tpart = np.zeros(n, np.uint8)
        self.cell = np.zeros((n, 3), int)
        # the cell behind each texel, and edge flags toward each in-plane neighbour
        self.edge = np.zeros((n, 6), bool)     # per direction index: the surface ends that way
        self.seam = np.zeros(n, bool)          # next to another part (a door's or a lid's edge)
        for vid, v in enumerate(self.voxes):
            sel = np.nonzero(self.vid == vid)[0]
            if sel.size == 0:
                continue
            d = self.dir[sel]
            nrm = np.zeros((sel.size, 3))
            for k, (a, s) in enumerate(DIRS):
                nrm[d == k, a] = s
            inside = self.Pv[sel] - nrm * v.s * 0.5
            idx, ok = v.lookup(inside)
            idx = np.clip(idx, 0, np.array(v.shape) - 1)
            self.cell[sel] = idx
            self.mat[sel] = v.mat[idx[:, 0], idx[:, 1], idx[:, 2]]
            self.tpart[sel] = v.part[idx[:, 0], idx[:, 1], idx[:, 2]]
            step = 1.0 / D
            for k, (a, s) in enumerate(DIRS):
                inplane = d // 2 != a
                if not inplane.any():
                    continue
                off = np.zeros(3)
                off[a] = s * step
                q = inside + off
                nidx, nok = v.lookup(q)
                nidx = np.clip(nidx, 0, np.array(v.shape) - 1)
                same_cell = np.all(nidx == idx, axis=1)
                nm = v.mat[nidx[:, 0], nidx[:, 1], nidx[:, 2]]
                npart = v.part[nidx[:, 0], nidx[:, 1], nidx[:, 2]]
                # the neighbour shows a face the same way and is of the same material: the surface goes on
                cont = np.zeros(sel.size, bool)
                for dd in range(6):
                    m = d == dd
                    if m.any():
                        e = self.exp[vid][dd]
                        cont[m] = e[nidx[m, 0], nidx[m, 1], nidx[m, 2]]
                cont &= nok & (nm == self.mat[sel])
                edge = inplane & ~same_cell & ~cont
                self.edge[sel, k] = edge
                self.seam[sel] |= inplane & ~same_cell & nok & (nm > 0) & (npart != self.tpart[sel])

        # model points for the painters (a part's own voxels sit at its pivot)
        self.P = self.Pv.copy()
        for vid, v in enumerate(self.voxes):
            if v.local is not None:
                self.P[self.vid == vid] += np.array(v.local, float)

    # ------------------------------------------------------------ painting helpers
    def normal(self):
        n = np.zeros((self.dir.size, 3))
        for k, (a, s) in enumerate(DIRS):
            n[self.dir == k, a] = s
        return n

    def paint(self, colour_fn):
        """colour_fn(mesh) -> (n,4) uint8 colours for every texel; returns the atlas image."""
        cols = colour_fn(self)
        img = np.zeros((self.H, self.W, 4), np.uint8)
        img[self.tv, self.tu] = cols
        return Image.fromarray(img, "RGBA")

    def glow_mask(self, channel):
        mats = [m for m, g in GLOW_OF.items() if g & channel]
        return np.isin(self.mat, mats)

    def quad_glow(self):
        """Per quad: the glow channels some texel of it carries."""
        g = np.zeros(len(self.quads), int)
        offs = np.cumsum([0] + [q.tw * q.th for q in self.quads])
        gl = np.zeros(self.mat.size, int)
        for m, ch in GLOW_OF.items():
            gl[self.mat == m] |= ch
        for i in range(len(self.quads)):
            g[i] = np.bitwise_or.reduce(gl[offs[i]:offs[i + 1]]) if offs[i + 1] > offs[i] else 0
        return g

    # ------------------------------------------------------------ export
    def corners(self, q):
        """The quad's four corners (model units) and their atlas (u, v), counter-clockwise seen from outside."""
        a, s = DIRS[q.dir]
        b, c = [x for x in range(3) if x != a]

        def pt(bb, cc):
            p = [0.0, 0.0, 0.0]
            p[a], p[b], p[c] = q.plane, bb, cc
            return p
        uv = lambda i, j: (q.u + i * q.tw, q.v + j * q.th)
        cs = [(q.b0, q.c0, *uv(0, 0)), (q.b1, q.c0, *uv(1, 0)), (q.b1, q.c1, *uv(1, 1)), (q.b0, q.c1, *uv(0, 1))]
        # (b, c, a) is right-handed for a = x (y, z), y (x, z)? fix winding by the sign of the cross product
        p0, p1, p3 = np.array(pt(*cs[0][:2])), np.array(pt(*cs[1][:2])), np.array(pt(*cs[3][:2]))
        nrm = np.zeros(3)
        nrm[a] = s
        if np.dot(np.cross(p1 - p0, p3 - p0), nrm) < 0:
            cs = [cs[0], cs[3], cs[2], cs[1]]
        return [(pt(bb, cc), (u, v)) for bb, cc, u, v in cs]

    def export(self, extra=None):
        glow = self.quad_glow()
        parts = []
        for pi, p in enumerate(self.parts):
            piv = np.array(p.get("pivot", (0, 0, 0)), float)
            qs = []
            for qi, q in enumerate(self.quads):
                if q.part != pi:
                    continue
                off = np.zeros(3) if self.voxes[q.vox].local is not None else piv
                row = []
                for pos, (u, v) in self.corners(q):
                    row += [round(pos[0] - off[0], 4), round(pos[1] - off[1], 4), round(pos[2] - off[2], 4), u, v]
                row += [q.dir, q.layer, int(glow[qi])]
                qs.append(row)
            d = {k: v for k, v in p.items() if k != "pivot"}
            d["pivot"] = [round(x, 4) for x in piv]
            d["quads"] = qs
            parts.append(d)
        out = {"texture": [self.W, self.H], "parts": parts}
        if extra:
            out.update(extra)
        return out


# ---------------------------------------------------------------- preview (orthographic software render)

SHADE = [0.8, 0.8, 1.0, 0.5, 0.6, 0.6]  # +x -x +y -y +z -z, like Minecraft's entity light


def rot_y(deg):
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_x(deg):
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_z(deg):
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def preview(mesh, atlas, yaw, pitch, size=(640, 400), scale=None, pose=None, bg=(150, 170, 190), glow=None):
    """Renders the mesh orthographically: yaw turns the car (0: we look at its left side), pitch tilts the view down.
    pose(part) -> 3x3 rotation (about the part's pivot) for doors, wheels etc."""
    W, H = size
    tex = np.asarray(atlas.convert("RGBA")).astype(float)
    gtex = np.asarray(glow.convert("RGBA")).astype(float) if glow is not None else None
    view = rot_x(pitch) @ rot_y(yaw)
    tris = []
    for q in mesh.quads:
        part = mesh.parts[q.part]
        piv = np.array(part.get("pivot", (0, 0, 0)), float)
        R = pose(part) if pose else None
        if part.get("hidden"):
            continue
        cs = mesh.corners(q)
        pts = np.array([c[0] for c in cs], float)
        local = mesh.voxes[q.vox].local is not None
        rel = pts if local else pts - piv
        pts = (rel @ R.T if R is not None else rel) + piv
        uvs = np.array([c[1] for c in cs], float)
        nrm = np.zeros(3)
        a, s = DIRS[q.dir]
        nrm[a] = s
        if R is not None:
            nrm = R @ nrm
        shade = 0.55 + 0.45 * max(0, np.dot(nrm, np.array([0.3, 0.9, 0.35]) / np.linalg.norm([0.3, 0.9, 0.35])))
        tris.append((pts @ view.T, uvs, shade, q.layer))
    allp = np.concatenate([t[0] for t in tris])
    lo, hi = allp.min(0), allp.max(0)
    if scale is None:
        scale = min((W - 40) / (hi[0] - lo[0]), (H - 40) / (hi[1] - lo[1]))
    cx, cy = (lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2
    img = np.zeros((H, W, 3))
    img[:] = bg
    zbuf = np.full((H, W), -1e9)
    glass = []

    def raster(p, uv, shade, layer, blend):
        sx = (p[:, 0] - cx) * scale + W / 2
        sy = H / 2 - (p[:, 1] - cy) * scale
        for tri in ((0, 1, 2), (0, 2, 3)):
            x, y, z = sx[list(tri)], sy[list(tri)], p[list(tri), 2]
            t = uv[list(tri)]
            x0, x1 = int(max(0, math.floor(x.min()))), int(min(W - 1, math.ceil(x.max())))
            y0, y1 = int(max(0, math.floor(y.min()))), int(min(H - 1, math.ceil(y.max())))
            if x1 < x0 or y1 < y0:
                continue
            den = (y[1] - y[2]) * (x[0] - x[2]) + (x[2] - x[1]) * (y[0] - y[2])
            if abs(den) < 1e-9:
                continue
            gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            l0 = ((y[1] - y[2]) * (gx - x[2]) + (x[2] - x[1]) * (gy - y[2])) / den
            l1 = ((y[2] - y[0]) * (gx - x[2]) + (x[0] - x[2]) * (gy - y[2])) / den
            l2 = 1 - l0 - l1
            m = (l0 >= -1e-6) & (l1 >= -1e-6) & (l2 >= -1e-6)
            if not m.any():
                continue
            zz = l0 * z[0] + l1 * z[1] + l2 * z[2]
            uu = l0 * t[0, 0] + l1 * t[1, 0] + l2 * t[2, 0]
            vv = l0 * t[0, 1] + l1 * t[1, 1] + l2 * t[2, 1]
            ui = np.clip(np.floor(uu).astype(int), 0, tex.shape[1] - 1)
            vi = np.clip(np.floor(vv).astype(int), 0, tex.shape[0] - 1)
            col = tex[vi, ui]
            sub = zbuf[y0:y1 + 1, x0:x1 + 1]
            m &= zz > sub + 1e-4
            m &= col[..., 3] > 8
            if not m.any():
                continue
            rgb = col[..., :3] * shade
            if gtex is not None:
                g = gtex[vi, ui]
                rgb = np.where(g[..., 3:4] > 8, np.minimum(255, col[..., :3] * 0.3 + g[..., :3]), rgb)
            tgt = img[y0:y1 + 1, x0:x1 + 1]
            if blend:
                a = col[..., 3:4] / 255
                tgt[m] = (tgt * (1 - a) + rgb * a)[m]
            else:
                tgt[m] = rgb[m]
                sub[m] = zz[m]

    for p, uv, shade, layer in tris:
        if layer == 0:
            raster(p, uv, shade, layer, False)
    gl = [t for t in tris if t[3] == 1]
    gl.sort(key=lambda t: t[0][:, 2].mean())
    for p, uv, shade, layer in gl:
        raster(p, uv, shade, layer, True)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))

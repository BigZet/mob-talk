"""Houses of several blocks (spec §3.2): an L-shaped house of two wings and a corner block, a courtyard block (дом-колодец)
of four corner blocks round a small yard, with wings between them when it is bigger, and an arch from the yard to the
street through one corner's ground floor.

    t, flats = compose.l_house("1-447", sections=2, floors=5, colour="brick")
    t, flats = compose.yard_house("ii-03", sections=0, floors=4, colour="brick")

Each block is a House of its own (house.py), built in its own template and turned into place here: wing A runs west of
the corner block along x, wing B south of it along z (turned a quarter clockwise, its entrances on the east street).
The blocks abut with their own walls (a deformation joint); where a block's template reaches into another block's
footprint (eaves, the apron) it is cut off, and the apron is laid anew under the whole house.
"""
import house as H
import scale
import series as S

CW = {"north": "east", "east": "south", "south": "west", "west": "north"}


def turn_props(props, rot):
    """A block's state turned rot quarter turns clockwise (what a structure's rotation does to it in the game)."""
    if not rot or not props:
        return props
    out = dict(props)
    for _ in range(rot):
        prev = dict(out)
        if prev.get("facing") in CW:
            out["facing"] = CW[prev["facing"]]
        sides = [d for d in CW if d in prev]
        if len(sides) == 4:
            for d in CW:
                out[CW[d]] = prev[d]
        if prev.get("axis") in ("x", "z"):
            out["axis"] = "z" if prev["axis"] == "x" else "x"
        if "rotation" in prev and prev["rotation"].isdigit():
            out["rotation"] = str((int(prev["rotation"]) + 4) % 16)
    return out


def turn(bx, bz, L, D, rot):
    """A block-local cell (x along the block's length L, z across its depth D) turned rot quarters clockwise."""
    for _ in range(rot % 4):
        bx, bz, L, D = D - 1 - bz, bx, D, L
    return bx, bz


class Part:
    def __init__(self, block, rot, x, z):
        """block: a built House; rot: quarter turns clockwise; (x, z): where its turned footprint's corner goes."""
        self.h, self.rot, self.x, self.z = block, rot, x, z
        self.foot = {self.place(bx, bz) for bx, bz in block.base_grid}
        self.ground = {self.place(bx, bz) for bx, bz in block.grids.get(1, block.base_grid)}

    def place(self, bx, bz):
        tx, tz = turn(bx, bz, self.h.length, self.h.depth, self.rot)
        return self.x + tx, self.z + tz

    def cells(self):
        h = self.h
        for (x, y, z), (name, props) in h.tpl.cells.items():
            if y == 0:
                continue
            wx, wz = self.place(x - h.ox, z - h.oz)
            yield (wx, y, wz), name, turn_props(props, self.rot)


def compose(parts):
    m = scale.MARGIN
    every = {}
    for p in parts:
        others = set().union(*(q.foot for q in parts if q is not p)) if len(parts) > 1 else set()
        for (x, y, z), name, props in p.cells():
            if (x, z) in others:
                continue
            every[(x, y, z)] = (name, props)
    foot = set().union(*(p.foot for p in parts))
    ground = set().union(*(p.ground for p in parts))
    x0 = min(x for x, _ in foot) - m
    z0 = min(z for _, z in foot) - m
    x1 = max(x for x, _ in foot) + m
    z1 = max(z for _, z in foot) + m
    height = max(p.h.tpl.size[1] for p in parts)
    t = H.Tpl((x1 - x0 + 1, height, z1 - z0 + 1))
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            t.set(x - x0, 0, z - z0, "plinth" if (x, z) in ground else "asphalt")
    for (x, y, z), (name, props) in every.items():
        t.set(x - x0, y, z - z0, name, props)
    flats = []
    for p in parts:
        h = p.h
        for f in h.flats:
            g = H.Flat(f.section, f.floor, f.side)
            for kind, cells in f.rooms.items():
                g.rooms[kind] = [tuple(v - o for v, o in zip(p.place(cx - h.ox, cz - h.oz), (x0, z0))) for cx, cz in cells]
            for attr in ("door", "bed"):
                v = getattr(f, attr)
                if v:
                    wx, wz = p.place(v[0] - h.ox, v[2] - h.oz)
                    setattr(g, attr, (wx - x0, v[1], wz - z0))
            flats.append(g)
    return t, flats


def block(series, sections, floors, colour, seed, **opts):
    h = H.House(series, sections, floors, colour, seed=seed, opts=opts)
    h.build()
    return h


def l_house(series, sections, floors, colour):
    """Wing A (sections along x, its east end closed) - the corner block - wing B (sections along z, its north end
    closed): an L whose street sides are north and east, the yard in the inner corner."""
    j = S.CORNER_JOIN
    a = block(series, sections, floors, colour, 1, closed_end=True)
    c = block(series + "-c", 1, floors, colour, 2)
    b = block(series, sections, floors, colour, 3, closed_start=True)
    return compose([Part(a, 0, 0, 0), Part(c, 0, a.length, 0), Part(b, 1, a.length + j["b"], j["size"])])


def yard_house(series, sections, floors, colour):
    """Four corner blocks round the yard, turned to face their two streets each, with wings of `sections` sections
    between them (none: the corners abut, a yard 16 x 16 - a real well); the north-east corner has the arch."""
    j = S.CORNER_JOIN
    C, Dj = j["size"], j["a"]
    wings = [block(series, sections, floors, colour, 10 + i, closed_start=True, closed_end=True) for i in range(4)] if sections else []
    Lw = wings[0].length if wings else 0
    Sd = 2 * C + Lw
    corners = [block(series + ("-ca" if i == 0 else "-c"), 1, floors, colour, 20 + i) for i in range(4)]
    parts = [Part(corners[0], 0, C + Lw, 0), Part(corners[1], 1, C + Lw, C + Lw), Part(corners[2], 2, 0, C + Lw),
             Part(corners[3], 3, 0, 0)]
    if wings:
        parts += [Part(wings[0], 0, C, 0), Part(wings[1], 1, Sd - Dj, C), Part(wings[2], 2, C, Sd - Dj), Part(wings[3], 3, 0, C)]
    return compose(parts)

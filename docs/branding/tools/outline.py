"""Traces the tower (legs, neck, braces) from the logo as polygons: marching squares on luminance, then Douglas-Peucker."""
import math

def lum(c): return min(c)  # whiteness: only white is high in every channel (light-blue arcs have R = 70)

def trace(grid, x0, y0, x1, y1, level, tol):
    f = [[lum(grid[y][x]) - level for x in range(x0, x1 + 1)] for y in range(y0, y1 + 1)]
    # Pixels outside the box count as background, so loops close at the box border.
    H, W = len(f), len(f[0])
    val = lambda i, j: f[j][i] if 0 <= i < W and 0 <= j < H else -1.0
    segs = {}
    def interp(a, b, pa, pb):
        t = a / (a - b); return (pa[0] + t * (pb[0] - pa[0]), pa[1] + t * (pb[1] - pa[1]))
    for j in range(-1, H):
        for i in range(-1, W):
            c = [(i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1)]
            v = [val(*p) for p in c]
            idx = sum(1 << k for k in range(4) if v[k] > 0)
            if idx in (0, 15): continue
            e = {}
            for k in range(4):
                a, b = v[k], v[(k + 1) % 4]
                if (a > 0) != (b > 0): e[k] = interp(a, b, c[k], c[(k + 1) % 4])
            ks = sorted(e)
            if len(ks) == 2: pairs = [(ks[0], ks[1])]
            else:  # saddle: decide by the cell centre
                centre = sum(v) / 4
                pairs = [(0, 1), (2, 3)] if (centre > 0) == (v[1] > 0) else [(0, 3), (1, 2)]
            for a, b in pairs:
                pa, pb = e[a], e[b]
                segs.setdefault(round_key(pa), []).append(pb); segs.setdefault(round_key(pb), []).append(pa)
    loops, used = [], set()
    for start in list(segs):
        if start in used: continue
        loop = [start]; used.add(start); prev = None; cur = start
        while True:
            nxt = [p for p in segs[cur] if round_key(p) != prev and round_key(p) not in used]
            if not nxt: break
            prev, cur = cur, round_key(nxt[0]); used.add(cur); loop.append(cur)
        if len(loop) > 8: loops.append([(x + x0, y + y0) for x, y in loop])
    return [simplify_closed(l, tol) for l in loops]

def simplify_closed(l, tol):
    """Douglas-Peucker on a closed loop: split at the point farthest from the start, simplify both halves."""
    far = max(range(len(l)), key=lambda i: math.hypot(l[i][0] - l[0][0], l[i][1] - l[0][1]))
    first = dp(l[:far + 1], tol)
    second = dp(l[far:] + [l[0]], tol)
    return first[:-1] + second[:-1]

def round_key(p): return (round(p[0], 4), round(p[1], 4))

def dp(pts, tol):
    if len(pts) < 3: return pts
    a, b = pts[0], pts[-1]; dx, dy = b[0] - a[0], b[1] - a[1]; L = math.hypot(dx, dy) or 1e-9
    d = [abs(dy * (p[0] - a[0]) - dx * (p[1] - a[1])) / L for p in pts[1:-1]]
    i = max(range(len(d)), key=d.__getitem__) if d else None
    if i is None or d[i] <= tol: return [a, b]
    return dp(pts[:i + 2], tol)[:-1] + dp(pts[i + 1:], tol)

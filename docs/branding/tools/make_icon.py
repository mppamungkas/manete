"""Generates NetLogger's adaptive launcher icon (VectorDrawables) from docs/branding/netlogger-logo.png.

    python3 docs/branding/tools/make_icon.py        (from the repository root; no dependencies)

Writes app/src/main/res/: drawable/ic_launcher_foreground.xml, drawable/ic_launcher_monochrome.xml,
values/ic_launcher_colors.xml, mipmap-anydpi-v26/ic_launcher(_round).xml. Measurements are in logo pixels
(1254 x 1254); see docs/branding/README.md.
"""
import math, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import png
import outline

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..'))
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'res')
w, h, px = png.read(os.path.join(ROOT, 'docs', 'branding', 'netlogger-logo.png'))
grid = [[px(x, y) for x in range(w)] for y in range(h)]
# The tower (legs, neck, braces) is traced: straight edges, so a few polygon points reproduce it exactly. The rest is
# geometry measured below. The phone (x >= 642) is not part of the trace.
tower = outline.trace(grid, 380, 395, 642, 870, (outline.lum((253, 253, 254)) + outline.lum((13, 77, 167))) / 2, 0.8)

# Colors sampled from the logo (most common pixel per region).
BG, WHITE, ARC, SCREEN, BAR = '#FF0D4DA7', '#FFFDFDFE', '#FF46CCFE', '#FF0F3D7F', '#FF44E494'

# Geometry measured in the logo (image px).
BALL = (567.5, 351.0, 65.0)
ARC_C, ARC_W = (568.0, 351.0), 38.0
ARCS = [  # radius, from angle, to angle (degrees, 0 = right, counterclockwise = up)
    (143.5, 140, 220), (218.0, 138.5, 221.5),   # left inner, left outer
    (143.5, 40, -40), (218.0, 41.5, -35),       # right inner, right outer (stops short of the phone)
]
PHONE = (653, 497, 919, 869, 50)          # outer frame: left, top, right, bottom, corner radius
SCREEN_R = (677, 553, 895, 848, 10)
SLOT = (757.5, 521, 815.5, 535, 7)        # speaker slot (cut out of the frame)
BARS = [(706, 724), (750, 695), (794, 663), (838, 632)]  # left, top; width 29, bottom 771, radius 5
BAR_W, BAR_BOTTOM, BAR_R = 29, 771, 5

# Placement: symbol centred by its bounding box (as in the logo), scaled so its farthest pixel from that centre
# lies on a 32.5 dp circle: inside the 66 dp safe zone (radius 33) with 0.5 dp margin for anti-aliasing.
bgc = (13, 77, 167)
pts = [(x, y) for y in range(170, 890) for x in range(300, 950) if sum(abs(a - b) for a, b in zip(grid[y][x], bgc)) > 90]
bx0, by0 = min(p[0] for p in pts), min(p[1] for p in pts); bx1, by1 = max(p[0] for p in pts), max(p[1] for p in pts)
CX, CY = (bx0 + bx1 + 1) / 2, (by0 + by1 + 1) / 2
R = max(math.hypot(x + 0.5 - CX, y + 0.5 - CY) for x, y in pts)
K = 32.5 / R
print(f'bbox ({bx0},{by0})-({bx1},{by1}), centre ({CX},{CY}), farthest {R:.1f} px, scale {K:.5f} dp/px, '
      f'symbol {(bx1 - bx0 + 1) * K:.1f} x {(by1 - by0 + 1) * K:.1f} dp', file=sys.stderr)

f = lambda v: f'{v:.2f}'.rstrip('0').rstrip('.')
X = lambda x: f(54 + (x - CX) * K)
Y = lambda y: f(54 + (y - CY) * K)
S = lambda v: f(v * K)

def rrect(l, t, r, b, rad):
    return (f'M{X(l + rad)},{Y(t)} H{X(r - rad)} A{S(rad)},{S(rad)} 0 0 1 {X(r)},{Y(t + rad)} V{Y(b - rad)} '
            f'A{S(rad)},{S(rad)} 0 0 1 {X(r - rad)},{Y(b)} H{X(l + rad)} A{S(rad)},{S(rad)} 0 0 1 {X(l)},{Y(b - rad)} '
            f'V{Y(t + rad)} A{S(rad)},{S(rad)} 0 0 1 {X(l + rad)},{Y(t)} Z')

def circle(cx, cy, r):
    return f'M{X(cx - r)},{Y(cy)} A{S(r)},{S(r)} 0 1 0 {X(cx + r)},{Y(cy)} A{S(r)},{S(r)} 0 1 0 {X(cx - r)},{Y(cy)} Z'

def arc(r, a0, a1):
    p = lambda a: (ARC_C[0] + r * math.cos(math.radians(a)), ARC_C[1] - r * math.sin(math.radians(a)))
    (x0, y0), (x1, y1) = p(a0), p(a1)
    sweep = 1 if a1 < a0 else 0  # decreasing angle = clockwise on screen (y down)
    return f'M{X(x0)},{Y(y0)} A{S(r)},{S(r)} 0 0 {sweep} {X(x1)},{Y(y1)}'

def poly(loops):
    return ' '.join('M' + ' L'.join(f'{X(x)},{Y(y)}' for x, y in l) + ' Z' for l in loops)

def path(data, fill=None, stroke=None, width=None, evenodd=False, name=None):
    a = [f'android:pathData="{data}"']
    if name: a.insert(0, f'android:name="{name}"')
    if fill: a.append(f'android:fillColor="{fill}"')
    if evenodd: a.append('android:fillType="evenOdd"')
    if stroke: a += [f'android:strokeColor="{stroke}"', f'android:strokeWidth="{S(width)}"', 'android:strokeLineCap="round"']
    return '    <path\n        ' + '\n        '.join(a) + ' />\n'

def vector(comment, body):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!--\n    {comment}\n    Generated from docs/branding/netlogger-logo.png (see docs/branding/README.md); 108 dp adaptive icon\n'
            '    canvas, symbol within the 66 dp safe zone.\n-->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n' + body + '</vector>\n')

def symbol(mono):
    c = lambda col: '#FFFFFFFF' if mono else col
    arcs = ''.join(path(arc(*a), stroke=c(ARC), width=ARC_W, name='arc') for a in ARCS)
    frame = rrect(*PHONE) + ' ' + rrect(*SCREEN_R) + ' ' + rrect(*SLOT)
    body = arcs
    body += path(circle(*BALL), fill=c(WHITE), name='tower_top')
    body += path(poly(tower), fill=c(WHITE), evenodd=True, name='tower')
    body += path(frame, fill=c(WHITE), evenodd=True, name='phone_frame')
    if not mono: body += path(rrect(*SCREEN_R), fill=SCREEN, name='phone_screen')
    body += path(' '.join(rrect(l, t, l + BAR_W, BAR_BOTTOM, BAR_R) for l, t in BARS), fill=c(BAR), name='signal_bars')
    return body

open(f'{OUT}/drawable/ic_launcher_foreground.xml', 'w').write(vector(
    'Launcher icon foreground: cell tower with signal arcs, phone with signal bars.', symbol(False)))
open(f'{OUT}/drawable/ic_launcher_monochrome.xml', 'w').write(vector(
    'Launcher icon, monochrome layer (Android 13+ themed icons): the symbol in one color, screen cut out.', symbol(True)))
open(f'{OUT}/values/ic_launcher_colors.xml', 'w').write(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    '    <!-- Launcher icon background: the logo\'s blue (sampled from docs/branding/netlogger-logo.png). -->\n'
    f'    <color name="ic_launcher_background">{BG}</color>\n</resources>\n')
adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
            '</adaptive-icon>\n')
for name in ('ic_launcher', 'ic_launcher_round'):
    open(f'{OUT}/mipmap-anydpi-v26/{name}.xml', 'w').write(adaptive)

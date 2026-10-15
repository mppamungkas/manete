# Branding

`netlogger-logo.png` is NetLogger's own logo (our design, not third-party material): a cell tower with signal arcs
and a phone with signal bars on a blue rounded square, with the "NetLogger" wordmark below.

## Launcher icon

An adaptive icon (minSdk 31: no legacy PNG mipmaps), generated from the logo as VectorDrawables, so it is sharp at
every size. Only the symbol is used, no wordmark.

| Layer | Resource | Content |
|---|---|---|
| Background | `@color/ic_launcher_background` (`values/ic_launcher_colors.xml`) | Solid logo blue |
| Foreground | `drawable/ic_launcher_foreground.xml` | The symbol in its logo colors |
| Monochrome | `drawable/ic_launcher_monochrome.xml` | The symbol in one color, phone screen cut out (Android 13+ themed icons) |

`mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` combine them; the manifest uses both.

**Colors**, sampled from the logo (most common pixel in each region):

| Element | RGB | Hex |
|---|---|---|
| Background | 13, 77, 167 | `#0D4DA7` |
| Tower and phone | 253, 253, 254 | `#FDFDFE` |
| Signal arcs | 70, 204, 254 | `#46CCFE` |
| Phone screen | 15, 61, 127 | `#0F3D7F` |
| Signal bars | 68, 228, 148 | `#44E494` |

**Geometry** (logo pixels, 1254 × 1254): tower ball circle at (567.5, 351), r 65; arcs centered at (568, 351),
radii 143.5 and 218, stroke 38, round caps, ±40° and ±41.5° (the right outer arc stops at −35°, where it meets the
phone); phone frame 653–919 × 497–869, corner radius 50, screen 677–895 × 553–848, radius 10, speaker slot
757.5–815.5 × 521–535; bars 29 wide at x 706 / 750 / 794 / 838, tops 724 / 695 / 663 / 632, bottom 771, radius 5. The
tower's legs, neck and braces are traced from the logo (marching squares, Douglas–Peucker at 0.8 px): a 20-point
outline with three triangular holes.

**Placement:** the symbol is centered on its bounding box, as in the logo, and scaled so its farthest pixel lies
32.5 dp from the center: inside the 66 dp safe zone (radius 33 dp) of the 108 dp canvas, so no launcher mask cuts the
tower top or the arcs. The symbol is 44.7 × 51.3 dp.

**Regenerate** after a logo or geometry change (from the repository root; Python 3, no dependencies):

```
python3 docs/branding/tools/make_icon.py
```

## Previews

`previews/` holds the icon as Android renders it (moto g45, 2026-10-15), written by the debug-only
`IconPreviewActivity`, which also checks the safe zone (logcat: farthest pixel 32.31 dp from the center for both
layers):

```
adb shell am start -n id.my.pamslab.netlogger/.IconPreviewActivity
adb pull /sdcard/Android/data/id.my.pamslab.netlogger/files/icon-previews/. docs/branding/previews/
```

| File | Shows |
|---|---|
| `full_square_432.png` | The whole 108 dp canvas, no mask |
| `circle_mask_432.png`, `rounded_square_mask_432.png` | The 72 dp a launcher shows, circle / rounded-square mask |
| `circle_mask_48px.png`, `circle_mask_24px.png` | Small sizes (48 px is a typical launcher icon at mdpi) |
| `monochrome_432.png`, `monochrome_48px.png` | Themed icon: the monochrome layer tinted, here dark on light |
| `launcher_moto_g45.png` | The moto g45's app drawer (crop of a screenshot) |
| `launcher_samsung_a56.png` | The Samsung A56's home screen, One UI rounded-square mask (crop of a screenshot) |

Not an instrumented test of `:app` on purpose: `connectedDebugAndroidTest` of the app module uninstalls the app
afterwards and deletes its data.

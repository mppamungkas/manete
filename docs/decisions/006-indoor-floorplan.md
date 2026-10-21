# 006: Floor plans for indoor tests: plan

- **Status:** Accepted (D1–D8 as recommended), 2026-10-21. Step 1 done (schema v5, `PlanInterpolator`); step 2 done (Settings › Floor plans, import, scale; not yet on a phone).
- **Users:** teachers and students measuring inside buildings (campus, mall, station), and the research that needs
  radio values per room or corridor instead of a GPS track.
- **Language:** English only, texts in `strings.xml`.

## Problem

Indoors, GPS is weak or wrong. The A56 session of 2026-10-20 (indoors, still) had 21 GPS lost/restored pairs and 127
of 322 ticks with a fix older than 2 s. The indoor scenario exists (`Scenario.INDOOR`), but a session can only be
placed on the OSM map, where an indoor walk is a cloud of points around the building. Indoor drive-test tools (TEMS,
G-NetTrack Pro's indoor mode) let the user load a floor plan and tap where they are while walking; the samples
between taps are placed along the path.

## What Android provides (public APIs, no new permission)

- **Storage Access Framework** (`ACTION_OPEN_DOCUMENT`): the user picks an image or PDF; NetLogger copies it into its
  own storage. No storage permission.
- **`BitmapFactory`** with `inSampleSize` for PNG/JPEG/WebP, **`ImageDecoder`** for HEIC; **`PdfRenderer`** renders a
  PDF page to a bitmap (architects often hand out PDFs).
- **Compose** gestures (`transformable`, `detectTapGestures`) and `Canvas` for zoom, pan, tap and the coloured dots;
  the session charts already draw with Compose Canvas, no chart or image library.
- Sensors (step detector, compass) would allow dead reckoning between taps, but drift and OEM differences make it
  unreliable; out of scope for the first version (see D1).

## Plan

### How a position is recorded

1. Before or during an indoor session the user opens **Plan** on Live and picks a floor plan.
2. While walking, the user taps **where they are now** on the plan: at the start, at each turn, door or landmark, and
   at the end. Each tap is a **waypoint**: plan, x/y on the plan, and the time (both `wallTimeMs` and
   `elapsedRealtimeNs`).
3. Ticks between two waypoints on the same plan are placed on the straight line between them, in proportion to time
   (constant walking speed between taps). Ticks before the first waypoint, after the last one, or between waypoints on
   different plans have no plan position (never guessed).
4. Standing still: tap the same spot again ("I'm still here"), so the time in between stays at that spot.
5. **Undo** removes the last waypoint (a wrong tap); it is stored as removed, not deleted, so the export can show it.
6. Changing floor: pick another plan; a `FLOOR_CHANGE` event is written, and the next tap starts the new floor.

The waypoints are the raw truth and are stored; the per-tick positions are **derived** (interpolation, pure Kotlin,
unit-tested) when showing or exporting, so a better method later (e.g. snapping to corridors) can be applied to old
sessions. Each derived position carries `waypointGapS`: seconds to the nearest waypoint, so the analysis can drop ticks
that were far from any tap.

### Floor plans

- **Import:** Settings › Floor plans (new page) or from the Plan view: pick a PNG/JPEG/WebP/HEIC image or a PDF
  (first page, or a chosen page). Stored downscaled to at most 4096 px on the long side in `files/plans/<uuid>.png`.
- **Name** and **floor label** (e.g. "Building C, 2nd floor"), editable.
- **Scale (optional):** tap two points and enter the distance between them in metres; positions are then also exported
  in metres from the plan's top-left corner, and walking speeds can be checked.
- **Georeference (optional, later step):** place two or three plan points on the OSM map; indoor ticks then get
  lat/lon and appear on the normal map and in `track.geojson`.
- A plan is deleted only when no session uses it (the session keeps its waypoints referring to it).

### Where the user sees it

- **Live** (any scenario, suggested for Indoor): a **Plan** button opens the full-screen plan view: zoom and pan, the
  current position (last tap), the derived path of this session's ticks coloured by the Map's metric (LTE RSRP, NR
  SS-RSRP, SINR, network type), and **Here** (tap mode), **Still here**, **Undo**, **Floor**. The status bar (REC,
  ticks, Marker, Stop) stays visible. A small line on Live shows "Plan: Building C 2F · last tap 0:42 ago".
- **Map:** a recorded session with waypoints shows "Floor plan" as a view next to the OSM map: the plan with the
  coloured ticks and the waypoints; tap a dot for the same values card as on the map; a floor selector when the
  session used several plans. A legend with tick counts per colour, as on the map.
- **Sessions:** the detail shows the plans used and the waypoint count; the size estimate includes the plan images.
- **How to use:** a new section "Indoor tests with a floor plan" (tap at every turn, stand-still taps, accuracy limits).

### Export (ZIP)

- `waypoints.csv`: every waypoint (time both clocks, plan, x, y, removed flag).
- `indoor.csv`: one row per tick with a plan position: `seq`, both timestamps, plan, floor, `planX`, `planY` (pixels of
  the stored image), `planXM`, `planYM` (metres, with a scale), `lat`/`lon` (with a georeference), `waypointGapS`.
- `plans/<uuid>.png` and `plans.json` (name, floor, size, scale, georeference) for every plan the session used.
- `flat.csv` unchanged (indoor columns would be empty for most sessions); `track.geojson` uses georeferenced indoor
  positions when there are any, GPS otherwise, with a `positionSource` property.
- Optional, later: `heatmap_<floor>.png`, the plan with the coloured ticks, for reports.

### Code changes

- **Schema v5** (migration 4→5, tested): table `floor_plan` (`id, uuid, name, floor, imageFile, widthPx, heightPx,
  metresPerPx, georefJson, createdAt`) and table `plan_waypoint` (`id, sessionId, planId, wallTimeMs,
  elapsedRealtimeNs, x, y, removed`), x/y in pixels of the stored image. `EventType.FLOOR_CHANGE` (no schema change:
  events store the type as text). No change to `measurement` or `cell`.
- `core:model`: `FloorPlan`, `Waypoint`, `PlanPosition`; `PlanInterpolator` (pure, unit-tested: gaps, plan changes,
  undo, stand-still, ticks outside the waypoints).
- `core:data`: DAOs, plan files (`PlanStore`, like `CaptureFileStore`), import and downscaling.
- New module **`feature:indoor`**: plan library page, import and scale screens, the plan view (zoom, pan, taps,
  coloured ticks). Live and Map get it through slots from `:app`, like the capture row (features stay independent).
- `core:export`: the new files; `docs/columns.md`; `SizeEstimate` weights.
- `LoggingEngine`: `addWaypoint`, `undoWaypoint`, `setPlan` (writes `FLOOR_CHANGE`), the current plan in
  `LoggingState.Running`.
- No new dependency.

## Steps (one commit each; build, unit tests and lint green before each)

1. Schema v5 + migration test, models, `PlanInterpolator` with unit tests.
2. Plan import (image + PDF), plan store, Settings › Floor plans (list, rename, floor, delete), scale calibration.
3. Live plan view: Here, Still here, Undo, Floor; derived path coloured live; engine and events.
4. Map: Floor plan view for recorded sessions, values card, legend, floor selector.
5. Export: `waypoints.csv`, `indoor.csv`, plan images, `plans.json`; size estimate; `docs/columns.md`.
6. Device checks on both phones (a walk through one floor and a stairway to another), How to use, checklist,
   device quirks, CLAUDE.md.
7. Optional: georeference (2–3 points on the map), lat/lon in `indoor.csv` and `track.geojson`; heatmap image.

## Decisions needed

- **D1** Position input: **taps at waypoints, positions in between by time** (recommended; simple, honest, what
  TEMS/G-NetTrack do). Alternative: step counter + compass dead reckoning between taps (more points, but drift and OEM
  differences; could come later on top of the taps).
- **D2** Store **waypoints only** and derive per-tick positions when showing/exporting (recommended; raw input kept,
  method can improve). Alternative: also store a position per tick in `measurement` (faster queries, but a schema
  change to the biggest table and a frozen method).
- **D3** Formats: PNG/JPEG/WebP/HEIC images and **PDF pages via PdfRenderer** (recommended). CAD files (DWG/DXF) are
  out of scope: export them to PDF or PNG first.
- **D4** Scale (two points + metres) optional in step 2: recommended yes. Georeference: **optional and last** (step 7),
  or leave it out.
- **D5** Several floors per session (one plan per floor, `FLOOR_CHANGE` event): recommended yes.
- **D6** New module `feature:indoor`, Live and Map through slots from `:app`; plans managed in Settings › Floor plans.
  Recommended yes.
- **D7** Export as separate `indoor.csv` + `waypoints.csv` + plan images, `flat.csv` unchanged: recommended yes.
  Alternative: add `planX`/`planY`/`floor` columns to `flat.csv`.
- **D8** Privacy: floor plans can be confidential (security layouts). Plans stay on the phone, and the export asks
  "Include floor plan images?" (default yes) so a ZIP can be shared without them. Recommended yes.

## Out of scope

Automatic indoor positioning (Wi-Fi RTT, BLE beacons, UWB: needs infrastructure or permissions the phones may not
have), CAD import, 3D/multi-floor rendering in one view, sharing plans between phones (export/import of a plan file
could come later), the G-NetTrack indoor log format.

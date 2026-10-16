# 005: Crash and problem reports without the Play Store: plan

- **Status:** Proposed, 2026-10-16. Waiting for approval of D1–D8.
- **Date:** 2026-10-16
- **Users:** teachers and students who install the APK directly, and the developer who has to fix what they hit.
- **Language:** English only, texts in `strings.xml`.

## Problem

NetLogger is installed from an APK, not from the Play Store, so the Play Console's crash reports (Android vitals) never
see it. Today a crash leaves nothing behind but Android's own crash log, which only `adb` can read. Example: on
2026-10-16 the moto g45 had a NetLogger crash at 05:51 that nobody noticed; it was found by chance in
`logcat -b crash` and turned out to be a reproducible Map crash (fixed in PR #7). On a student's phone it would have
gone unreported.

For a logger, the crash is not the only problem worth reporting: the logging service shares the main process, so a
crash, a freeze (ANR) or Android ending the process in the background (low memory, battery restrictions) also ends a
recording. Today such a session is closed on the next start without saying why.

## What Android provides (public APIs, no permission)

- **`ActivityManager.getHistoricalProcessExitReasons()`** (`ApplicationExitInfo`, Android 11+; NetLogger's minSdk is
  31): why each of the app's own processes ended (the main process and `:vpn`), with time, importance and PSS.
  Reasons include `REASON_CRASH` (Java), `REASON_CRASH_NATIVE`, `REASON_ANR`, `REASON_LOW_MEMORY`, `REASON_SIGNALED`,
  `REASON_EXCESSIVE_RESOURCE_USAGE`, `REASON_FREEZER`, `REASON_USER_REQUESTED` (force stop), `REASON_EXIT_SELF`.
  - `getTraceInputStream()`: for an ANR the thread dump (text); for a native crash on Android 12+ the **tombstone**
    (protobuf: signal, abort message, the crashing thread's backtrace with library and function names). This is how
    the MapLibre `MapRenderer::render` crash would have been reported.
  - For a Java crash it has no stack trace, so NetLogger also needs its own uncaught-exception handler.
- **`ActivityManager.setProcessStateSummary()`** (≤ 128 bytes, kept with the next exit record): NetLogger stores what
  was running (session id, capture on/off, the open screen), so a report can say "a recording was running".
- **Thread.setDefaultUncaughtExceptionHandler**: writes the Java stack trace to a file before the process dies, then
  hands over to Android's handler (the system crash dialog and exit info stay as they are).
- **Own log lines**: an app can read its own processes' log lines with `logcat -d` (no permission, own UID only).
  Whether the previous process' lines are still in the buffer after a restart differs by phone: checked in step 1;
  fallback, an in-memory ring buffer of NetLogger's own log lines, written to the report file at a crash.

## Plan

### What is recorded

At every start of the main process, NetLogger reads the exit records it has not seen yet (it remembers the newest
timestamp it has read) and keeps a **problem report** for each one that was not normal:

| Exit reason | Report | Live card |
|---|---|---|
| Crash (Java or native), in the main process or `:vpn` | yes, with the stack trace / tombstone summary | yes |
| ANR (frozen) | yes, with the main thread of the dump | yes |
| Ended by Android (low memory, signaled, excessive resource use, freezer) **while a session was recording** | yes, no trace | yes: "Android ended NetLogger during a recording" |
| Ended by Android while not recording, force stop, normal exit, app update | no | no |

### The report

Plain text, readable by a teacher, useful to the developer:

```
NetLogger problem report
App        0.1.0 (build 511cf00), installed from APK
Phone      motorola moto g45 5G, Android 15 (API 35), baseband …
Time       2026-10-16 05:51:08 +07:00
Process    main · foreground
What       Crash (native): SIGSEGV in libmaplibre.so MapRenderer::render
Running    Map screen; no recording; no capture
Trace      #00 libmaplibre.so mln::android::MapRenderer::render(_JNIEnv&)+144
           …
Log        (NetLogger's own lines before the end, last 200)
Your note  (Report a problem: what the user typed)
```

**Never in a report:** location, cell identities (cell ID, TAC, PCI), the Wi-Fi SSID, phone numbers or IMEI, capture
contents. NetLogger's own log lines contain none of these today (checked 2026-10-16); a filter drops any line with a
coordinate pattern anyway, and a rule in `CLAUDE.md` keeps them out of log lines. The user always sees the whole report
before it leaves the phone.

### Where the user sees it

- **Live**, after an unexpected end: a card at the top, "NetLogger stopped unexpectedly at 05:51 (crash on the Map)",
  with **View report**, **Send** and **Dismiss**. Shown once per report.
- **Settings › Report a problem** (Info section):
  - the list of kept reports (time, what happened), each with View, Send and Delete;
  - **Report a problem**: a text field ("what happened, what did you expect?") and Send, which builds the same report
    with the last log lines and no crash.

### How it is sent (the user chooses, nothing is automatic)

- **Share**: Android's share sheet (email, WhatsApp, Drive …) with the report as text and, for a native crash, the
  raw tombstone as an attachment (`FileProvider`, as the session export already uses).
- **GitHub issue**: opens `github.com/mppamungkas/netlogger/issues/new` in the browser with title and body filled in
  (the body cut to fit a URL; the full report is copied to the clipboard to paste). The page warns that issues are
  public. Needs a GitHub account.

Reports are kept in the app's own storage (`files/problems/`), at most 20 (oldest removed); they are deleted with the
app. The About › Privacy text gets one line: "Problem reports stay on the phone until you send one."

### Code changes

- New module **`core:diagnostics`** (Android library, Hilt; no new dependency):
  `CrashRecorder` (uncaught-exception handler, process state summary), `ExitReader` (`ApplicationExitInfo`, seen
  marker), `ProblemReports` (store, list, delete, `StateFlow` of unseen reports), `ReportBuilder` (pure, unit-tested),
  `TombstoneSummary` (pure, unit-tested; a minimal protobuf reader for the few fields needed, about 100 lines).
- `NetLoggerApplication`: installs `CrashRecorder` in **both** processes (main and `:vpn`); reads exit records in the
  main process only. Nothing in the capture itself changes.
- `feature:logger`: the Live card. `feature:settings`: the Report a problem pages. Both read `core:diagnostics`
  (features still do not depend on each other).
- `LoggingEngine` / `CaptureController` update the process state summary when a session or capture starts and ends.
- Debug builds only: a hidden test screen (like `IconPreviewActivity`) that causes a Java crash, an ANR and a native
  crash (`Process.sendSignal(myPid(), SIGSEGV)`), so every path can be checked on both phones. Not in release builds.
- No schema change. The recovered-session closing on start stays as it is; the report adds the reason.

## Steps (one commit each; build, unit tests and lint green before each)

1. `core:diagnostics`: crash handler, process state summary, exit reader, report store; check on both phones which
   exit reasons and traces each phone gives, and whether the previous process' log lines can still be read.
2. Report builder and tombstone summary, with unit tests (fixtures from step 1, anonymised).
3. Live card and Settings › Report a problem (list, view, share, GitHub issue, delete, "Report a problem" with a note).
4. Debug test screen; check every path on both phones (Java crash, native crash, ANR, kill during a recording,
   `:vpn` crash), update `docs/field-test-checklist.md`, `docs/device-quirks.md`, How to use, About › Privacy,
   CLAUDE.md.

## Decisions needed

- **D1** Built in, no library, nothing sent automatically. Alternatives: **ACRA** (Apache 2.0 library; can e-mail or
  post to a server you run; a new dependency, and no tombstone or exit-reason reading of its own), **Firebase
  Crashlytics** (works without the Play Store, but uploads automatically to Google, closed source, needs a Firebase
  project; contradicts "no analytics, no upload"), **Sentry** (needs a server or an account).
  *Recommendation: built in.*
- **D2** Also report Android ending NetLogger **during a recording** (low memory, battery restrictions), not only
  crashes and ANRs. *Recommendation: yes: for a logger these lose data just like a crash.*
- **D3** Sending: share sheet **and** a pre-filled GitHub issue (public, with a warning). *Recommendation: both.*
  Alternative: share only, if issues should stay private (reports would then reach you by e-mail or chat).
- **D4** Include NetLogger's own log lines (last 200, filtered, shown before sending). *Recommendation: yes.*
- **D5** Summarise native tombstones with a small protobuf reader of our own (no protobuf library), and attach the raw
  tombstone. *Recommendation: yes.*
- **D6** A debug-only test screen that crashes the app on purpose. *Recommendation: yes; never in release builds.*
- **D7** Keep at most 20 reports on the phone. *Recommendation: yes.*
- **D8** New module `core:diagnostics`; the crash handler also runs in the `:vpn` process (app-level code; the capture
  itself is not touched). *Recommendation: yes.*

## Out of scope

Automatic upload or a report server, symbolising native frames that have no names (stripped libraries keep only
offsets), crash statistics across users, a release APK and signing (parked 2026-10-16), the Play Store.

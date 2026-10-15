# Third-party code

Code copied or adapted from other projects. Gradle dependencies are listed in `gradle/libs.versions.toml`.

The app credits both (and the libraries in the APK) in Settings › About, with the full licence texts
(`feature/settings/.../Licences.kt`, `feature/settings/src/main/res/raw/`; GPL and LGPL are copied from `LICENSE`
and zdtun's `COPYING` at build time). Update that list when a dependency or copied code changes.

## NetMonster Core

- **Source:** https://github.com/mroczis/netmonster-core (studied at commit `33063e0`)
- **License:** Apache License 2.0. Copyright 2019 Michal Mroček.
- **Adapted into:** `core/telephony/src/main/kotlin/id/my/pamslab/netlogger/core/telephony/parser/SamsungQuirks.kt`
- **From:**
  - `feature/postprocess/SamsungEndiannessPostprocessor.kt`: Samsung (Android 12+) serving TAC/LAC byte swap,
    detected by comparing with the deprecated `getCellLocation()`.
  - `feature/postprocess/SamsungInvalidValuesPostprocessor.kt`: Samsung neighbor placeholders
    (RSSI -51, TA 0, Ec/No 0).
  - `telephony/mapper/cell/CellMapperLte.kt`: LTE RSSNR 0 = "not available" on Samsung.
- **Changes:** reimplemented on our `CellSample` model; every correction emits a `ParseWarning` that keeps the
  raw value; the byte-swap latch lasts one session instead of being persisted per Android build; the RSSNR 0
  rule is limited to Samsung (NetMonster applies it to all devices).
- See `docs/decisions/001-cell-parsing.md`.

## zdtun

- **Source:** https://github.com/emanuele-f/zdtun, commit `c2a2674` (2025-02-20), as pinned by PCAPdroid.
- **License:** GNU LGPL v3 or later (compatible with NetLogger's GPL v3). Copyright 2018-22 Emanuele Faranda.
  Includes `uthash.h` (BSD 1-clause, Copyright 2003-2020 Troy D. Hanson).
- **Copied into:** `feature/capture/src/main/cpp/third_party/zdtun/` (license file `COPYING` and headers kept,
  see the `README.md` there).
- **Changes:** none; NetLogger links it into `libnetcapture` as the user-space TCP/UDP/ICMP forwarder of the
  native capture (Phase 3b). See `docs/decisions/002-native-capture.md` (D1, D2).

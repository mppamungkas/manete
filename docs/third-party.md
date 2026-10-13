# Third-party code

Code copied or adapted from other projects. Gradle dependencies are listed in `gradle/libs.versions.toml`.

## NetMonster Core

- **Source:** https://github.com/mroczis/netmonster-core (studied at commit `33063e0`)
- **License:** Apache License 2.0. Copyright 2019 Michal Mroček.
- **Adapted into:** `core/telephony/src/main/kotlin/id/ac/pnp/netlogger/core/telephony/parser/SamsungQuirks.kt`
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

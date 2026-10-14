# zdtun (third-party, unmodified)

- **Source:** https://github.com/emanuele-f/zdtun, commit `c2a2674a411efdd71f7595e5395d8b49c52ab347` (2025-02-20),
  the version PCAPdroid pins as a submodule.
- **License:** GNU Lesser General Public License v3 or later (`COPYING`, file headers). Copyright 2018-22
  Emanuele Faranda. `third_party/uthash.h`: BSD 1-clause, Copyright 2003-2020 Troy D. Hanson.
  `third_party/net_headers.h` carries no header of its own and is covered by `COPYING`.
- **Files:** `zdtun.c/h`, `utils.c/h` (zdtun.c uses `calc_checksum`), `socks5.c/h` (zdtun.c includes
  `socks5.c`; NetLogger never enables the SOCKS5 proxy), `third_party/`. Not copied: zdtun's own client, gateway
  and pivot tools and its CMakeLists.
- **Changes:** none. Do not edit these files; NetLogger's code lives one level up (`../../`).
- Used by NetLogger's native capture (milestone 11, `docs/decisions/002-native-capture.md`, D1/D2).

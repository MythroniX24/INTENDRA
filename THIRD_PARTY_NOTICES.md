# INTENDRA — Third-Party Notices

This file documents the third-party components that ship inside or are
downloaded by INTENDRA (the **Embedded Linux Environment**, the **offline AI
backend**, and the app's core dependencies). It is maintained for
redistribution compliance — "open source" does not mean unrestricted
commercial redistribution, so every component is listed with its exact
license and project URL.

> ⚠️ **Do not copy binaries or root filesystems from Termux or any other
> project without checking their redistribution terms.** INTENDRA downloads
> the official Termux **bootstrap archive** (the same one Termux itself
> extracts on first launch) from the project's own GitHub Releases. Each
> package inside the archive carries its own license; the list below covers
> the ones INTENDRA relies on.

---

## 0. Offline AI backend

### llama.cpp
- **Project:** https://github.com/ggml-org/llama.cpp
- **License:** MIT
- **Used as:** Native offline AI inference backend (GGUF models). INTENDRA
  builds against a pinned revision:
  `000547513f1530346ecd163db8b3e13962949961`.
- **Notice:** MIT License — Copyright (c) 2023-2026 The ggml authors.

---

## 1. Embedded runtime components

### Termux bootstrap archive (`termux-packages`)
- **Project:** https://github.com/termux/termux-packages
- **License:** GPL-3.0-or-later (the bootstrap build system and Termux
  tooling). The archive is a collection of independent packages, each with
  its own license (see below).
- **Used as:** The root filesystem for INTENDRA's embedded Linux environment
  (downloaded at first-run setup, extracted into app-controlled storage).
- **NOTICE:** The Termux *application* additionally ships under GPLv3 with a
  linking exception; INTENDRA does **not** redistribute the Termux app, only
  the bootstrap archive downloaded from the project's official releases.

### proot
- **Project:** https://github.com/proot-me/proot
- **License:** GPL-2.0-or-later
- **Used as:** User-space chroot — lets the embedded Linux rootfs run inside
  the Android app sandbox without root.

### proot-distro
- **Project:** https://github.com/termux/proot-distro
- **License:** GPL-3.0-or-later
- **Used as:** Optional full-Linux-distro installer (Ubuntu, Debian, …).

### BusyBox
- **Project:** https://busybox.net
- **License:** GPL-2.0-only (with applet exception allowing redistribution of
  the *unmodified* binary under the GPL).
- **Used as:** Core utilities inside the bootstrap.

### bash
- **Project:** https://www.gnu.org/software/bash/
- **License:** GPL-3.0-or-later
- **Used as:** Default interactive shell.

### coreutils, grep, sed, awk (gawk), findutils, tar, gzip, unzip
- **Project:** GNU (https://www.gnu.org/software/)
- **License:** GPL-3.0-or-later (coreutils, findutils, gzip, tar);
  GPL-3.0-or-later (grep, sed); GPL-3.0-or-later (gawk);
  GPL-2.0-or-later (unzip).
- **Used as:** Standard command-line tools in the embedded environment.

### Python
- **Project:** https://www.python.org/
- **License:** PSF License Agreement (Python Software Foundation) — permissive,
  allows commercial use and redistribution.
- **Used as:** Optional runtime (`python`, `pip`), installed via `pkg` when
  requested.

### Git
- **Project:** https://git-scm.com/
- **License:** GPL-2.0-only
- **Used as:** Version control inside the environment.

### Node.js / npm
- **Project:** https://nodejs.org/
- **License:** MIT
- **Used as:** Optional runtime (installed via `pkg install nodejs` when the
  user requests it; not bundled in the initial APK).

### curl
- **Project:** https://curl.se/
- **License:** curl License (MIT-style, permissive)
- **Used as:** Network transfers inside the environment.

### OpenSSL / LibreSSL
- **Project:** https://www.openssl.org/ / https://www.libressl.org/
- **License:** Apache-2.0 (OpenSSL) / ISC-style (LibreSSL)
- **Used as:** TLS/HTTPS support for curl/wget/git.

---

## 2. Android-side libraries used by the terminal layer

| Component | License |
|-----------|---------|
| OkHttp (bootstrap download, web search) | Apache-2.0 |
| Kotlin coroutines | Apache-2.0 |
| AndroidX / Jetpack Compose | Apache-2.0 |

---

## 3. Full license texts

INTENDRA redistributes the full text of the licenses above (GPL-2.0,
GPL-3.0, Apache-2.0, MIT, PSF) in the `licenses/` directory alongside this
file, or — where the component is downloaded at runtime rather than shipped
in the APK — the license is available at the component's own project page
listed above.

---

## 4. Compliance notes

1. INTENDRA does **not** bundle the bootstrap archive inside the APK; it is
   downloaded at first-run setup from the official Termux GitHub Releases
   (`termux/termux-packages`), so APK size and redistribution obligations are
   minimal.
2. GPL-2.0/GPL-3.0 components are used in *userspace, out-of-process* (never
   linked into INTENDRA's proprietary code): they run as separate processes
   inside the sandbox. INTENDRA's own code does not link against them.
3. Users can remove the entire embedded environment at any time via
   Settings → Linux Environment → Remove.
4. Source code for all GPL components is available from the projects listed
   above; INTENDRA does not modify these components.

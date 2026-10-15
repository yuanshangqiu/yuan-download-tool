# libhydra 1.0.0 — x86_64-pc-windows-msvc

An embeddable, multi-source download engine with a stable C ABI.

## Licence

libhydra is **MIT OR Apache-2.0**, at your option. See `LICENSE-MIT` and
`LICENSE-APACHE`.

This is deliberately *not* the licence of the `hydra` command-line tool, which
is GPL-3.0-or-later. This package contains no GPL code: it is built from
`hya-ffi`, `hya-core` and `hya-net` only, and those three crates are
permissively licensed precisely so that this archive can be linked into your
application. Third-party dependency terms are in `THIRD-PARTY-NOTICES.md`.

## Contents

- `include/hydra.h` — the published ABI. Generated from the Rust definitions
  and carrying static assertions that check the layout against your compiler.
- `docs/ABI.md` — the specification: design principles, the ABI stability
  policy, ownership, the event queue's guarantees. Read it before writing a
  binding.
- `lib/` — the libraries. See `native-static-libs.txt` for the system
  libraries the static archive must be linked against.
- `docs/` — the ABI specification, the per-platform integration guides, and
  the language binding guide.
- `examples/` — a complete C client, the ABI conformance program, and the
  forward-compatibility probe CI runs against every published header.

## ABI version

#define HYDRA_FFI_ABI_VERSION 1

Compare it against `hydra_ffi_abi_version()` at startup and refuse to run on a
mismatch: a header from one ABI and a library from another disagree about the
layout of every struct.

# jolt-libkrun — libkrun 2.0 bindings for jolt

[libkrun](https://github.com/containers/libkrun) 2.0 bindings for
[jolt](https://github.com/jolt-lang/jolt): the builder/device C API
(`krun_vmm_builder_*`, `krun_payload_*`, `krun_*_device_*`). The old
`krun_create_ctx`/`krun_set_*` API is removed on libkrun `main`, so this
library tracks libkrun's version (2.0, matching libkrun 2.0).

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+ (the library declares
  `:jolt/min-version 0.8.28`)
- libkrun 2.0 (`libkrun.so.2`) + libkrunfw (`libkrunfw.so.5`)
- Linux: a writable `/dev/kvm` to boot; macOS: the hypervisor entitlement

The shared library is declared `:optional`, so an app can load the namespace
without libkrun installed. `jolt.libkrun.probe` answers whether it is there,
and `boot!` fails at its first foreign call if it is not.

## Build

Build libkrun 2.0 from its `main` branch when you need the new API:

```bash
rustup target add x86_64-unknown-linux-musl
cd libkrun && make NET=1   # -> target/release/libkrun.so.2.0.0
```

libkrun 2.0 reuses the installed `libkrunfw.so.5`, so no libkrunfw kernel
build is required. Run jolt with `LD_LIBRARY_PATH=<libkrun>/target/release`.

## Usage

```clojure
{:deps {racklr-dev/jolt-libkrun
        {:git/url "https://github.com/racklr-dev/jolt-libkrun"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[jolt.libkrun :as krun])

;; one-shot: boot a rootfs and run a command as PID1 (kernel cmdline --)
(krun/boot! {:rootfs "/path/to/rootfs"
             :cpus 2 :memory-mib 512
             :exec ["/bin/sh" "-c" "echo hi"]})

;; mounts + networking
(krun/boot! {:rootfs "/path/to/rootfs"
             :mounts [["workspace" "/host/ws"]]
             :net-taps [{:id "eth0" :tap "jolt0" :mac (byte-array [2 0 0 0 1])}]})
```

## API

| Function | Args | Returns | Errors |
|---|---|---|---|
| `boot!` | `opts` | the guest's exit (vmm_run exits this process) | fails at the first foreign call when libkrun is absent |
| `libkrun-leaf` | — | resolved libkrun library path, or nil | — |
| `libkrunfw-leaf` | — | resolved libkrunfw library path, or nil | — |
| `find-libkrun` | — | resolved libkrun library path, or nil | — |
| `find-libkrunfw` | — | resolved libkrunfw library path, or nil | — |
| `kvm-available?` | — | boolean | — |
| `kvm-present?` | — | boolean (device exists, regardless of permission) | — |

`boot!` `opts`: `:rootfs`, `:cpus`, `:memory-mib`, `:exec`, `:env`, `:cwd`,
`:mounts`, `:net-taps`. Rootfs and mounts are virtio-fs devices (root is tag
`/dev/root`); networking is a TAP net device the guest auto-DHCPs on. The
guest command/env/cwd come from `/.krun_config.json` (OCI `args`/`env`/`cwd`)
or a kernel cmdline `-- cmd`. `KrunStr`/`KrunBytes` are `{ptr,len}` structs
passed by value — the reason for the 0.8.28 floor.

## Status

beta — the FFI surface loads and the probe distinguishes present/absent
libkrun (`test/jolt/libkrun_probe_test.clj`); boot tests (`boot2_test.clj`,
`boot3_test.clj`, `nettap_test.clj`) boot alpine and exercise mounts/network
where libkrun + KVM are present.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [libkrun](https://github.com/containers/libkrun) — the runtime this binds
- [jolt.ffi](https://github.com/jolt-lang/jolt) — the stdlib FFI layer used here
- [jolt-microvm](https://github.com/racklr-dev/jolt-microvm) — the high-level consumer

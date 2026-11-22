# jolt-libkrun

libkrun 2.0 bindings for [jolt](https://github.com/jolt-lang/jolt) — the **new**
builder/device C API (`krun_vmm_builder_*`, `krun_payload_*`, `krun_*_device_*`).

The old `krun_create_ctx`/`krun_set_*` API is removed on libkrun `main`, so this
library tracks libkrun's version (this is **2.0.0**, matching libkrun 2.0.0).

## Build libkrun 2.0 from main

```bash
rustup target add x86_64-unknown-linux-musl
cd libkrun && make NET=1   # -> target/release/libkrun.so.2.0.0
```

libkrun 2.0 reuses the installed `libkrunfw.so.5` (it only needs
`krunfw_get_kernel`), so no libkrunfw kernel build is required. Run jolt with
`LD_LIBRARY_PATH=<libkrun>/target/release` to load the 2.0 library.

## Use

```clojure
(require '[jolt.libkrun :as krun])

;; one-shot: boot a rootfs and run a command as PID1 (via kernel cmdline --)
(krun/boot! {:rootfs "/path/to/rootfs"
             :cpus 2 :memory-mib 512
             :exec ["/bin/sh" "-c" "echo hi"]})

;; mounts + networking
(krun/boot! {:rootfs "/path/to/rootfs"
             :mounts [["workspace" "/host/ws"]]
             :net-taps [{:id "eth0" :tap "jolt0" :mac (byte-array [2 0 0 0 0 1])}]})
```

## The new API shape

```
vmm_builder_new -> vcpus/ram_mib -> payload_load_krunfw -> [append_cmdline]
-> builder_payload -> fs_device_new (/dev/root + mounts) -> net_device_new_tap
-> device_manager_new + add -> builder_devices -> builder_build -> vmm_run
```

- Rootfs and mounts are all virtio-fs devices (`krun_fs_device_new`); root is
  tag `/dev/root`.
- Networking is a net device (`krun_net_device_new_tap`); the guest init
  auto-DHCPs on it. There is no `krun_set_port_map`/TSI anymore.
- The guest command/env/cwd come from `/.krun_config.json` (OCI `args`/`env`/
  `cwd`, with `Cmd`/`Env`/`WorkingDir` aliases) or a kernel cmdline `-- cmd`.
- `KrunStr`/`KrunBytes` are `{ptr,len}` structs passed by value.

## Modules

- `jolt.libkrun` — the 2.0 bindings + `boot!`.
- `jolt.libkrun.probe` — `find-libkrun`/`find-libkrunfw`/`kvm-available?`.

## Smoke test

```bash
LD_LIBRARY_PATH=/path/to/libkrun/target/release \
  jolt run test/boot2_test.clj   # boots alpine + /bin/sh, exits 0
```

## License

EPL-2.0

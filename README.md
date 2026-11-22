# jolt-libkrun

libkrun microVM bindings for [Jolt](https://github.com/jolt-lang/jolt): the
`krun_*` C API, exposed as Clojure over `jolt.ffi`. Requires
[libkrun](https://github.com/containers/libkrun).

## Use

```clojure
{:deps {racklr-dev/jolt-libkrun
        {:git/url "https://github.com/racklr-dev/jolt-libkrun"
         :git/sha "69eac3d17c2f696a6e6887165edfe7158a833218"}}}
```

```clojure
(require '[jolt.libkrun :as krun])

;; single virtio-fs mount
(krun/boot! {:rootfs "/path/to/rootfs"
             :exec ["/bin/sh" "-c" "..."]
             :virtiofs ["workspace" "/host/workspace"]})

;; multiple virtio-fs mounts
(krun/boot! {:rootfs "/path/to/rootfs"
             :virtiofs [["workspace" "/host/ws"] ["data" "/host/data"]]})
```

## API

- `boot!` — create a context, apply opts, block in the guest until exit.
  opts: `:rootfs` (required), `:workdir`, `:cpus`, `:memory-mib`, `:port-map`
  `"HOST:22"`, `:virtiofs` (`[tag path]` or `[[tag path] ...]`),
  `:exec [cmd arg...]`, `:rlimits [str...]`.
- `jolt.libkrun.probe` — `find-libkrun`, `find-libkrunfw`, `kvm-present?`,
  `kvm-available?` for pre-flight checks.

## Caveats

- `boot!` skips the Linux `RLIMIT_NOFILE` cap the runner applies; virtio-fs with
  many fds may need it.
- Smoke-tested on Debian 13 x86_64 with libkrun 1.19.6.

## License

EPL-2.0

# jolt-libkrun

libkrun microVM bindings for [Jolt](https://github.com/jolt-lang/jolt): the
`krun_*` C API, exposed as Clojure over `jolt.ffi`. Requires
[libkrun](https://github.com/containers/libkrun).

## Use

```clojure
{:deps {racklr-dev/jolt-libkrun
        {:git/url "https://github.com/racklr-dev/jolt-libkrun"
         :git/sha "TODO"}}}
```

```clojure
(require '[jolt.libkrun :as krun])
(krun/boot! {:rootfs "/path/to/rootfs" :exec ["/bin/sh"]})
```

## Caveats

- `boot!` skips the Linux `RLIMIT_NOFILE` cap the runner applies; virtio-fs with
  many fds may need it (shortcut: add `set-rlimits` callers later).
- Untested against real libkrun here; ABI types taken from libkrun's `krun.h`.

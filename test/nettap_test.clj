;; Net-tap smoke: boot with a TAP-backed net device + dhcp, dump the guest's
;; network state. Host must have jolt0 up with dnsmasq serving DHCP on it.
(ns jolt.test.nettap
  (:require [jolt.libkrun :as krun]))

(krun/boot! {:rootfs "/tmp/nettap-cache/alpine_3.20/base"
             :cpus 1 :memory-mib 256
             :net-taps [{:id "eth0" :tap "jolt0" :mac (byte-array [2 0 0 0 0 1])}]
             :dhcp true
             :exec ["/bin/sh" "-c" "ip addr show eth0; ip route; cat /etc/resolv.conf"]})

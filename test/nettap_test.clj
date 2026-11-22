;; Net-tap smoke: DHCP, reach the host TAP, and confirm default-deny egress
;; (no NAT on the host, so the internet must be unreachable).
(ns jolt.test.nettap
  (:require [jolt.libkrun :as krun]))

(krun/boot! {:rootfs "/tmp/nettap-cache/alpine_3.20/base"
             :cpus 1 :memory-mib 256
             :net-taps [{:id "eth0" :tap "jolt0" :mac (byte-array [2 0 0 0 0 1])}]
             :dhcp true
             :exec ["/bin/sh" "-c" "echo ---net---; ip addr show eth0 | grep inet; ip route; cat /etc/resolv.conf; echo ---ping-host---; ping -c 1 -W 2 192.168.44.1; echo ---internet---; wget -qO- -T 3 http://8.8.8.8 && echo REACHED-INTERNET || echo BLOCKED-INTERNET"]})

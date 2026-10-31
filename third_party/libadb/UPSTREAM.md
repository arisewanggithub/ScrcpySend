# Vendored libadb-android

Upstream: https://github.com/MuntashirAkon/libadb-android
Tag 3.1.1, commit c849886ebc6d48e7b46d967e78a6bb65c90c3b74.
Selected after inspection of AbsAdbConnectionManager.pair and
PairingConnectionCtx: implements Android SPAKE2 wireless-debugging pairing
and TLS 1.3, unlike legacy-only adb clients. Apache-2.0 alternative elected;
preserve COPYING, LICENSES, and individual BSD notices.

Local changes: cap each incoming ADB stream at 1 MiB and 64 packets; overflow
closes the ADB connection explicitly. The upstream ConcurrentLinkedQueue was
unbounded and acknowledged WRTE immediately. ScreenCopy drains streams on
dedicated readers and bounds its separate fair media multiplexer.
Connection/handshake/pairing/open deadlines are 10s; stream-open wait checks
its acknowledgement predicate (preventing lost notification). Expose TLS state
so wrapper refuses legacy plaintext ADB. Pairing close is null-safe after early
failure and does not print peer identity.
InputStream single-byte reads are unsigned. Remote-close state, the current
partial buffer and queued bytes share one read lock; EOF is exposed only after
both buffers drain. Local cancellation discards buffers and wakes readers.
Shell/sync reads and all writes have 10s deadlines; capture-video/log reads
explicitly opt out because a static screen may produce no packets. Audio retains
its startup deadline until valid codec initialization, then opts out so hard
silence does not end a healthy session. The session
watchdog still bounds startup and owns socket closure on cancellation.

ADB is restricted to 127.0.0.1 by our wrapper. Upstream adbd TLS certificate
acceptance follows Android ADB's local pairing protocol. Never use this client
for arbitrary remote ADB hosts. ScreenCopy v3 peer streaming separately uses
plain TCP with name/source-IP endpoint selection on a trusted family LAN; that
does not relax this client's Android ADB TLS/authentication requirements.
Conscrypt is bundled so TLS exporter needed by pairing uses public APIs,
without hidden-API bypass. Native SPAKE2 code is supplied by the pinned
spake2-android 2.2.1 dependency. Upstream library is not security-audited.

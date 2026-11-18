# Security policy

JUring runs Linux `io_uring` syscalls through the Java Foreign Function & Memory API. It's a
low-level I/O layer: it doesn't parse untrusted protocol input, but it does share memory with
the kernel and takes caller-provided native addresses and buffer registrations.

## Reporting a vulnerability

Please don't open a public issue. Use GitHub's private vulnerability reporting ("Security" →
"Report a vulnerability" on https://github.com/davidtos/JUring); that's the fastest way to
reach me.

Include:

- the affected version or commit,
- the kernel version and distro, your JDK version, and whether the process is sandboxed,
- a minimal reproducer and the impact you observed: crash, memory corruption, hang, data loss,
  privilege boundary.

I'll acknowledge within a few days. To be clear, there is no bug bounty.

## Scope and threat model

**Trusted callers.** JUring assumes the Java code calling it is trusted application code. It is
not a sandbox for untrusted Java. Passing foreign addresses, closing `Arena`s that back
registered buffers, or double-checking-in pooled buffers is misuse. The library rejects the
detectable forms: double/foreign buffer check-ins, use-after-close, owner-thread violations.
But it can't make arbitrary native addresses memory-safe.

**Kernel attack surface.** `io_uring` has had a history of kernel vulnerabilities, and several
environments restrict it: Docker's default seccomp profile, AppArmor on Ubuntu 23.10+,
`kernel.io_uring_disabled`, gVisor, ChromeOS and Android. Libraries and services should call
`JUring.isSupported()` and fall back to `java.nio` when io_uring is unavailable. Follow your
platform's guidance on whether unprivileged io_uring is allowed.

**Sandboxing.** Don't expose JUring-based I/O to untrusted tenants without an OS-level sandbox
(container, seccomp, VM). The library can't enforce quality-of-service isolation or fair
queuing.

**Native access.** JUring needs `--enable-native-access` (JEP 472). Grant it to the JUring
module only (`--enable-native-access=JUring`) rather than `ALL-UNNAMED` where you can.

**Registered memory.** Registered fixed buffers stay pinned until they're unregistered or
closed, and they count against `RLIMIT_MEMLOCK`. A process that registers attacker-controlled
sizes can exhaust its own locked-memory budget, so validate sizes at the application boundary.

## Supported versions

Security fixes go to the latest released minor version. Before 1.0, releases are 0.x and may
contain breaking changes; see [docs/api-stability.md](docs/api-stability.md).

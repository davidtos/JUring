# Security policy

JUring executes Linux `io_uring` syscalls through the Java Foreign Function & Memory API. It is
a low-level I/O layer: it does not parse untrusted protocol input, but it does share memory with
the kernel and accepts caller-provided native addresses and buffer registrations.

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's private vulnerability reporting
("Security" → "Report a vulnerability" on https://github.com/davidtos/JUring), or contact the
maintainer listed in `pom.xml`/`NOTICE`. Include:

- the affected version/commit,
- the kernel version and distro, JDK version, and whether the process is sandboxed,
- a minimal reproducer and the observed impact (crash, memory corruption, hang, data loss,
  privilege boundary).

You can expect an acknowledgement within a few days. There is no formal bounty programme.

## Scope and threat model

- **Trusted callers.** JUring assumes the Java code calling it is trusted application code, not
  a sandbox for untrusted Java. Passing foreign addresses, closing `Arena`s that back registered
  buffers, or double-checking-in pooled buffers is misuse. The library now rejects the
  detectable forms (double/foreign buffer check-ins, use-after-close, owner-thread violations),
  but it is not a memory-safety sandbox for arbitrary native addresses.
- **Kernel attack surface.** `io_uring` has had a history of kernel vulnerabilities and is
  restricted in several environments (Docker's default seccomp profile, AppArmor on Ubuntu
  23.10+, `kernel.io_uring_disabled`, gVisor/ChromeOS/Android). Libraries and services should
  call `JUring.isSupported()` and fall back to `java.nio` when io_uring is unavailable, and
  should follow their platform's guidance on whether unprivileged io_uring is permitted.
- **Sandboxing.** Do not expose JUring-based I/O to untrusted tenants without an OS-level
  sandbox (container/seccomp/VM). The library cannot enforce quality-of-service isolation or
  fair queuing; see `docs/m4-decision.md`.
- **Native access.** JUring requires `--enable-native-access` (JEP 472). Grant it to the JUring
  module only (`--enable-native-access=JUring`) rather than `ALL-UNNAMED` where possible.
- **Registered memory.** Registered fixed buffers remain pinned until unregistered/closed and
  count against `RLIMIT_MEMLOCK`; a process that registers attacker-controlled sizes can exhaust
  its own locked-memory budget. Validate sizes at the application boundary.

## Supported versions

Security fixes are provided for the latest released minor version. Before 1.0, releases are
0.x and may contain breaking changes; see `docs/api-stability.md`.

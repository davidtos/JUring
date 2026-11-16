# Supported platforms and environments

JUring drives Linux `io_uring` directly through the Java Foreign Function & Memory API. There is
no JNI and no `liburing`, so the requirements are runtime requirements rather than build
requirements.

## Short version

| Item | Requirement |
|---|---|
| OS | Linux only (the syscall numbers and mmap constants are Linux-specific) |
| CPU architecture | x86-64 (`amd64`) or aarch64 only |
| JDK | 25 or newer (the module is compiled for Java 25) |
| Kernel | 5.6 minimum for the required opcode probe; practical floor **6.1** when using `SINGLE_ISSUER` + `DEFER_TASKRUN` (the recommended fast path) |
| libc | glibc or musl; optional symbols (`sched_getcpu`, `posix_fadvise`) degrade gracefully when absent |
| Native access | `--enable-native-access=JUring` on the module path, or `--enable-native-access=ALL-UNNAMED` on the classpath (JEP 472) |
| Storage | `O_DIRECT` requires a filesystem and alignment that support it; registered buffers need `RLIMIT_MEMLOCK` headroom |
| Containers | io_uring is denied by Docker's default seccomp profile and can be disabled by AppArmor or `kernel.io_uring_disabled`; see below |

Check at runtime:

```java
if (JUring.isSupported()) {
    try (JUring ring = new JUring(64, IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN)) {
        // ...
    }
} else {
    // fall back to java.nio FileChannel (or AsynchronousFileChannel with a bounded pool)
}
```

`JUring.isSupported()` performs a minimal `io_uring_setup` probe once and caches the result. A
`new JUring(...)` on an unsupported platform throws `UnsupportedOperationException` with the
OS/arch, and a denied syscall fails during setup with an exception carrying the errno.

## Kernel feature gates

The implementation probes the kernel's opcode table (`IORING_REGISTER_PROBE`, Linux 5.6+) at
ring creation and fails early with an actionable message if a required opcode is missing.
Optional operations are gated individually:

- `IORING_SETUP_SINGLE_ISSUER` — Linux 6.0+
- `IORING_SETUP_COOP_TASKRUN` — Linux 5.19+
- `IORING_SETUP_DEFER_TASKRUN` — Linux 6.1+ and requires `SINGLE_ISSUER`
- `IORING_OP_LINK_TIMEOUT`, `IORING_OP_STATX`, `IORING_OP_FALLOCATE`, `IORING_OP_FTRUNCATE`
  and other optional opcodes vary by kernel version; `JuringFile`/`JUring` check support and
  throw `UnsupportedOperationException` where an operation is unavailable.

The recommended fast path (`SINGLE_ISSUER | COOP_TASKRUN | DEFER_TASKRUN`, registered files,
fixed buffers) therefore targets Linux 6.1+.

## Setup flags that JUring rejects

`IoUringOptions` mirrors the kernel flag names for discoverability, but JUring 0.1.0 rejects
flags it cannot honour instead of passing a broken configuration to the kernel. These throw
`IllegalArgumentException`:

| Flag | Why rejected |
|---|---|
| `IORING_SETUP_SQ_AFF` | No `sq_thread_cpu` setter exists yet |
| `IORING_SETUP_CQSIZE` | No explicit `cq_entries` setter exists yet |
| `IORING_SETUP_NO_MMAP` | Application-provided ring memory is not supported |
| `IORING_SETUP_REGISTERED_FD_ONLY` | A registered-fd-only ring has no usable mapping |
| `IORING_SETUP_HYBRID_IOPOLL` | Not implemented |
| `IORING_SETUP_IOPOLL` | Not supported/tested in 0.1.0 |
| `IORING_SETUP_R_DISABLED` | No enable API exists in this library |
| `IORING_SETUP_ATTACH_WQ` (user-supplied) | Use `getSharedWorkerRing()`; ATTACH_WQ is set internally there |
| `IORING_SETUP_DEFER_TASKRUN` alone | Requires `IORING_SETUP_SINGLE_ISSUER` (kernel rule) |
| `IORING_SETUP_SQPOLL` on `getSharedWorkerRing` | `ATTACH_WQ` and `SQPOLL` cannot be combined |

Supported: `SINGLE_ISSUER`, `COOP_TASKRUN`, `DEFER_TASKRUN`, `SQPOLL` (normal rings, with the
`sqThreadIdleMs` constructor parameter), `SQE128`, `CQE32`, `NO_SQARRAY`.

## Containers and sandboxes

Unprivileged `io_uring` is a security-sensitive interface and many environments restrict it:

- **Docker**: the default seccomp profile has `defaultAction: SCMP_ACT_ERRNO` and does not allow
  the `io_uring_setup`/`io_uring_enter`/`io_uring_register` syscalls, so ring creation fails with
  `EPERM`. A custom seccomp profile that allows those syscalls is required.
- **Kubernetes / container runtimes**: seccomp profiles (RuntimeDefault or stricter) often deny
  io_uring; check your runtime's profile.
- **Ubuntu 23.10+/24.04**: AppArmor restricts unprivileged io_uring by default
  (`kernel.apparmor_restrict_unprivileged_io_uring`); a privileged profile or
  `sysctl -w kernel.apparmor_restrict_unprivileged_io_uring=0` is needed.
- **`kernel.io_uring_disabled`** (Linux 6.6+): `0` = enabled, `1` = disabled for unprivileged
  users, `2` = disabled for everyone.
- Some hardened/gVisor/Android/ChromeOS environments disable io_uring entirely.

Libraries should call `JUring.isSupported()` and fall back to `java.nio` when it returns
`false`; do not assume io_uring availability in a portable server binary. (`isSupported()` and
the `JUring` constructor are gated before the native layer loads; other public types are
Linux-only and must only be reached after that check.)

## O_DIRECT, alignment and memory locking

- `O_DIRECT` opens (`LinuxOpenOptions.READ_DIRECT`/`WRITE_DIRECT`/`CREATE`/…) require
  buffer-size, offset and length alignment (typically 512 or 4096 bytes). The filesystem must
  support direct I/O (ext4/xfs do; tmpfs and some network filesystems do not).
- Registered fixed buffers are pinned by the kernel and count against `RLIMIT_MEMLOCK`
  (`ulimit -l`). Registering many large buffers may require raising the limit (or
  `CAP_IPC_LOCK`).
- `JUring` does not convert unaligned access into aligned access; the caller owns alignment
  and offset arithmetic.

## Java native-access configuration

JUring uses restricted FFM downcalls. On JDK 24+ (JEP 472) the JVM warns unless native access is
enabled, and future releases deny it by default:

```sh
# module path
java --enable-native-access=JUring ...
# classpath
java --enable-native-access=ALL-UNNAMED ...
```

The Maven surefire configuration in this repository already passes
`--enable-native-access=ALL-UNNAMED` for the test suite.

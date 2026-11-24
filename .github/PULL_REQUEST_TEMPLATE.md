## Summary

What does this change, and why?

## Tests run

Paste the evidence, not just a tick:

- `mvn clean test` — result and test count
- `mvn javadoc:javadoc` — clean or not
- Platform used (kernel, distro, arch, JDK) and any tests that only run on certain kernels or filesystems

## Checklist

- [ ] The change is focused: one logical change per PR.
- [ ] Tests cover the change; a bug fix has a regression test that fails without the fix.
- [ ] Javadoc is complete for every public or protected member (`doclint=all` with warnings as errors).
- [ ] Docs are updated if public API or behaviour changed: `docs/UPGRADING.md` and `docs/api-stability.md`.
- [ ] Benchmark changes: the hot path is still allocation-free, the relevant allocation test is extended, and `python3 bench/analysis/selftest.py --unit` plus `uvx ruff check bench` pass. No new CI job pretends to measure performance.
- [ ] No JNI, no `liburing`, and no new native build dependency.

## Notes

Anything reviewers should know: related issues, follow-ups, or parts you are unsure about.

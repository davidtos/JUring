package com.davidvlijmincx.lio.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Test helper exposing the logical CPUs this process is actually allowed to run on, read
 * from {@code /proc/self/status} ({@code Cpus_allowed_list}).
 *
 * <p>Tests that pin shard threads must not hard-code CPU ids: developer machines usually
 * expose many CPUs, while CI runners typically expose four vCPUs (0-3). Pinning to a CPU
 * that does not exist is a best-effort, non-fatal failure ({@code affinityApplied=false}),
 * so a hard-coded id turns an environment difference into a test failure.</p>
 */
final class CpuTopology {

    private static final String STATUS = "/proc/self/status";
    private static final String KEY = "Cpus_allowed_list:";

    private CpuTopology() {
    }

    /**
     * Returns the sorted ids of the CPUs this process may run on.
     *
     * @return allowed CPU ids, or an empty list when the platform does not expose them
     */
    static List<Integer> allowedCpus() {
        try {
            for (String line : Files.readAllLines(Path.of(STATUS))) {
                if (line.startsWith(KEY)) {
                    return parseList(line.substring(KEY.length()).trim());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Fall through: callers treat an empty list as "no affinity test possible".
        }
        return List.of();
    }

    /**
     * Returns the first allowed CPU id.
     *
     * @return the first allowed CPU, or {@code -1} when none could be determined
     */
    static int firstCpu() {
        List<Integer> cpus = allowedCpus();
        return cpus.isEmpty() ? -1 : cpus.get(0);
    }

    /**
     * Returns the first pair of adjacent allowed CPUs, as required by the group base-CPU
     * mapping (shard {@code i} is pinned to {@code base + i}).
     *
     * @return {@code {base, base + 1}}, or {@code null} when the host does not expose two
     *         adjacent CPUs
     */
    static int[] adjacentPair() {
        List<Integer> cpus = allowedCpus();
        for (int i = 0; i + 1 < cpus.size(); i++) {
            int base = cpus.get(i);
            if (cpus.get(i + 1) == base + 1) {
                return new int[] {base, base + 1};
            }
        }
        return null;
    }

    private static List<Integer> parseList(String value) {
        List<Integer> cpus = new ArrayList<>();
        if (value.isEmpty()) {
            return cpus;
        }
        for (String part : value.split(",")) {
            String[] range = part.trim().split("-");
            int start = Integer.parseInt(range[0].trim());
            int end = range.length > 1 ? Integer.parseInt(range[1].trim()) : start;
            for (int cpu = start; cpu <= end; cpu++) {
                cpus.add(cpu);
            }
        }
        return cpus;
    }
}

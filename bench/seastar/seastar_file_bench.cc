/*
 * seastar_file_bench - seastar-side counterpart of bench/juring/JUringFileBench.java.
 *
 * Purpose
 * -------
 * Run exactly the same file-I/O workload against the same existing file as the
 * JUring harness, so the comparison is apples-to-apples:
 *
 *   - same file, same O_DIRECT-capable fs (seastar's open_file_dma always uses
 *     O_DIRECT unless the fs does not support it),
 *   - same block size / queue depth (one fiber in flight per outstanding I/O),
 *   - same workload semantics (randread / randwrite / seqread / seqwrite),
 *   - same per-op latency definition (timestamp before submission, timestamp at
 *     completion),
 *   - same latency sampling semantics: a bounded uniform reservoir per fiber
 *     (Algorithm R), so a 120 s run's percentiles describe the whole window,
 *     not just its first seconds,
 *   - same JSON schema as JUringFileBench (schema_version 1.1), so
 *     bench/parse_results.py can consume both.
 *
 * Multi-shard (M3) mode
 * ---------------------
 * With seastar's own `--smp N` argv flag this app runs N seastar reactors, one
 * per shard. Each shard opens its *own* file handle on the same file and runs
 * its own `--qd`-in-flight fiber pipeline, so the total in-flight count is
 * qd * N -- matching fio `numjobs=N --iodepth=64` and JUring
 * `--runtime=shard --shards N`. Every shard does the same warmup + measured
 * protocol as the single-shard case.
 *
 * Cross-shard aggregation deliberately avoids moving C++ containers across
 * shards (allocator/serializer risk): each shard writes a self-describing binary
 * record (its per-shard counters + raw uint64-ns latency reservoir) plus a
 * human-readable JSON mirror under a per-process tmpfs directory
 * (/dev/shm/seastar_file_bench.<pid>/shard-<id>.{lat,json}); after the
 * `smp::invoke_on_all` barrier shard 0 reads them, computes per-shard
 * percentiles and the merged percentile set, writes ONE combined JSON object to
 * `--json` and stdout, and removes the directory. The binary file is the
 * authoritative transport; the JSON file is the inspection artifact.
 *
 * JSON is additive to the historical 1.0 shape (all old fields keep their
 * names) and adds the JUring schema 1.1 fields M3 needs: config.runtime="smp",
 * config.shards=N, runs[].elapsed_sec/ops/bytes/errors, runs[].threads[] and
 * runs[].shards[] (per-shard iops/mibs/latency_us), run-level merged latency_us,
 * plus `recorded`/`min` in every latency block. Ring metrics are JUring-only
 * (B5) and are not emitted here.
 *
 * Why not io_tester? io_tester creates and prefills its own files under
 * --storage, which is impossible on a read-only dataset mount and perturbs the
 * device state. This app opens an existing file read-only for the read path and
 * is the L0/L1 counterpart of the JUring harness.
 *
 * Build: see bench/seastar/build-file-bench.sh (uses the in-repo SeaStarRef build
 * and the workspace dependency sysroot in the sandbox; on a host, plain
 * `g++ ... $(pkg-config --libs --cflags --static seastar)` works).
 *
 * Run (single shard):
 *   taskset -c 2 seastar_file_bench \
 *     --file /mnt/TestData/read_files/single_file_bench.bin \
 *     --workload randread --bs 4k --qd 64 --seconds 10 --warmup-sec 2 \
 *     --readonly --seed 20261113 --label rr4k_qd64 --json out.json \
 *     --smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend io_uring
 *
 * Run (M3 shard mode, N=8):
 *   LD_LIBRARY_PATH=$PWD/.sysroot/root/usr/lib64 bench/seastar/seastar_file_bench \
 *     --file /mnt/TestData/read_files/single_file_bench.bin \
 *     --workload randread --bs 4k --qd 64 --seconds 30 --warmup-sec 10 \
 *     --readonly --seed 20261114 --label rr4k_qd64_n8 --json out.json \
 *     --smp 8 --cpuset 1,2,4,6,8,10,12,14 \
 *     --memory 1G --lock-memory 0 --reactor-backend io_uring
 */

#include <seastar/core/app-template.hh>
#include <seastar/core/coroutine.hh>
#include <seastar/core/file.hh>
#include <seastar/core/future.hh>
#include <seastar/core/aligned_buffer.hh>
#include <seastar/core/reactor.hh>
#include <seastar/core/seastar.hh>
#include <seastar/core/smp.hh>

#include <boost/program_options.hpp>
#include <boost/range/irange.hpp>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include <sys/stat.h>
#include <sys/utsname.h>
#include <unistd.h>

namespace bpo = boost::program_options;
using clock_type = std::chrono::steady_clock;

static constexpr const char* SCHEMA_VERSION = "1.1";
static constexpr const char* ENGINE = "seastar";

// ---------------------------------------------------------------------------
// config
// ---------------------------------------------------------------------------

struct config {
    std::string workload = "randread";
    std::string file;
    std::string label;
    std::string json_path;
    uint64_t bs = 4096;
    unsigned qd = 64;
    unsigned seconds = 10;
    unsigned warmup_sec = 2;
    unsigned runs = 1;
    uint64_t seed = 20261113;
    uint64_t dataset = 0;      // 0 => whole file
    bool readonly = false;
    bool verbose = false;

    bool is_write() const { return workload == "randwrite" || workload == "seqwrite"; }
    bool is_random() const { return workload == "randread" || workload == "randwrite"; }
    bool is_nop() const { return workload == "nop"; }
};

static uint64_t parse_size(const std::string& s) {
    if (s.empty()) {
        throw std::runtime_error("empty size");
    }
    char* end = nullptr;
    double v = std::strtod(s.c_str(), &end);
    if (end == s.c_str()) {
        throw std::runtime_error("bad size: " + s);
    }
    std::string suffix(end);
    for (auto& c : suffix) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    if (suffix.empty() || suffix == "b") {
        return static_cast<uint64_t>(v);
    }
    if (suffix == "k" || suffix == "kb" || suffix == "kib") {
        return static_cast<uint64_t>(v * 1024.0);
    }
    if (suffix == "m" || suffix == "mb" || suffix == "mib") {
        return static_cast<uint64_t>(v * 1024.0 * 1024.0);
    }
    if (suffix == "g" || suffix == "gb" || suffix == "gib") {
        return static_cast<uint64_t>(v * 1024.0 * 1024.0 * 1024.0);
    }
    throw std::runtime_error("bad size suffix: " + s);
}

// ---------------------------------------------------------------------------
// per-fiber state
// ---------------------------------------------------------------------------

struct fiber_stat {
    uint64_t ops = 0;
    uint64_t bytes = 0;
    uint64_t errors = 0;
    uint64_t seq_step = 0;
    uint64_t rng = 1;
    uint64_t lat_seen = 0;          // latency samples offered (== ops that recorded one)
    uint64_t lat_rng = 0x9E3779B97F4A7C15ULL;
    std::vector<uint64_t> lat_ns;   // uniform reservoir sample of per-op latencies (ns)
};

static inline uint64_t xorshift64(uint64_t& x) {
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    return x * 0x2545F4914F6CDD1DULL;
}

static uint64_t now_ns() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            clock_type::now().time_since_epoch()).count());
}

// Uniform reservoir sample (Algorithm R). Keeping only the *first* `cap`
// samples would describe just the first seconds of a long measured window
// (e.g. 2M of ~39M ops in a 120 s QD64 run); replacing a random element with
// probability cap/seen makes the retained set a uniform sample of the whole
// window, so tail percentiles cover steady state (matches JUringFileBench's
// reservoir semantics).
static void record_latency(fiber_stat& st, uint64_t ns, uint64_t cap) {
    st.lat_seen++;
    if (cap == 0) {
        return;
    }
    if (st.lat_ns.size() < cap) {
        st.lat_ns.push_back(ns);
        return;
    }
    const uint64_t j = xorshift64(st.lat_rng) % st.lat_seen;
    if (j < cap) {
        st.lat_ns[static_cast<size_t>(j)] = ns;
    }
}

// One fiber: issue one I/O at a time until the deadline.
static seastar::future<> run_fiber(seastar::file& f, const config& cfg,
                                   uint64_t deadline_ns, uint64_t region_end,
                                   uint64_t nblocks, unsigned fiber_index,
                                   fiber_stat& st, uint64_t lat_cap) {
    auto buf = seastar::allocate_aligned_buffer<char>(cfg.bs, 4096);
    char* p = buf.get();
    st.rng = cfg.seed ^ (0x9E3779B97F4A7C15ULL * (fiber_index + 1));
    if (st.rng == 0) {
        st.rng = 1;
    }
    // Independent PRNG stream for the latency reservoir (do not disturb offsets).
    st.lat_rng = cfg.seed ^ (0xD1B54A32D192ED03ULL * (fiber_index + 1));
    if (st.lat_rng == 0) {
        st.lat_rng = 1;
    }
    const uint64_t bs = cfg.bs;

    while (now_ns() < deadline_ns) {
        uint64_t pos;
        if (cfg.is_random()) {
            pos = (xorshift64(st.rng) % nblocks) * bs;
        } else {
            // contiguously interleaved sequential stream across fibers:
            // fiber i takes block (i + step*qd)
            pos = (fiber_index + st.seq_step * cfg.qd) * bs;
            pos %= ((region_end / bs) * bs);
            st.seq_step++;
        }
        uint64_t t0 = now_ns();
        try {
            size_t n;
            if (cfg.is_write()) {
                n = co_await f.dma_write(pos, p, bs);
            } else {
                n = co_await f.dma_read(pos, p, bs);
            }
            uint64_t t1 = now_ns();
            st.ops++;
            st.bytes += n;
            record_latency(st, t1 - t0, lat_cap);
        } catch (...) {
            st.errors++;
        }
    }
    co_return;
}

// ---------------------------------------------------------------------------
// phase / run
// ---------------------------------------------------------------------------

struct phase_result {
    uint64_t ops = 0;
    uint64_t bytes = 0;
    uint64_t errors = 0;
    uint64_t recorded = 0;          // latency samples offered (== ops)
    std::vector<uint64_t> lat_ns;   // sorted uniform reservoir (ns)
    double elapsed_s = 0.0;
};

static phase_result aggregate(const std::vector<fiber_stat>& fibers, double elapsed_s) {
    phase_result r;
    r.elapsed_s = elapsed_s;
    size_t total_lat = 0;
    for (const auto& f : fibers) {
        r.ops += f.ops;
        r.bytes += f.bytes;
        r.errors += f.errors;
        r.recorded += f.lat_seen;
        total_lat += f.lat_ns.size();
    }
    r.lat_ns.reserve(total_lat);
    for (const auto& f : fibers) {
        r.lat_ns.insert(r.lat_ns.end(), f.lat_ns.begin(), f.lat_ns.end());
    }
    std::sort(r.lat_ns.begin(), r.lat_ns.end());
    return r;
}

static seastar::future<phase_result> run_phase(seastar::file& f, const config& cfg,
                                               unsigned seconds, bool collect,
                                               uint64_t lat_cap) {
    (void)collect;
    std::vector<fiber_stat> fibers(cfg.qd);
    uint64_t start = now_ns();
    uint64_t deadline = start + static_cast<uint64_t>(seconds) * 1000000000ULL;
    const uint64_t region_end = cfg.dataset;
    const uint64_t nblocks = std::max<uint64_t>(1, (region_end / cfg.bs));
    if (cfg.is_random() && (region_end < cfg.bs)) {
        throw std::runtime_error("dataset smaller than block size");
    }
    co_await seastar::parallel_for_each(boost::irange(0u, cfg.qd),
        [&](unsigned i) {
            return run_fiber(f, cfg, deadline, region_end, nblocks, i, fibers[i], lat_cap);
        });
    uint64_t end = now_ns();
    double elapsed = static_cast<double>(end - start) / 1e9;
    co_return aggregate(fibers, elapsed);
}

// ---------------------------------------------------------------------------
// multi-shard transport: one self-describing file pair per shard
// ---------------------------------------------------------------------------
//
// /dev/shm/seastar_file_bench.<pid>/shard-<id>.json  human-readable mirror
// /dev/shm/seastar_file_bench.<pid>/shard-<id>.lat   authoritative binary:
//     u64 magic, u64 nruns, u64 file_size, u64 dataset_size,
//     per run: f64 elapsed_sec, u64 ops, u64 bytes, u64 errors,
//              u64 n, n x u64 latency_ns (sorted ascending)
// /dev/shm/seastar_file_bench.<pid>/shard-<id>.err   present only on failure

static constexpr uint64_t SHARD_LAT_MAGIC = 0x534541535441524CULL; // "SEASTARL"

static std::string tmp_root() {
    return "/dev/shm/seastar_file_bench." + std::to_string(static_cast<long>(::getpid()));
}

static std::string shard_json_path(unsigned id, const std::string& root) {
    return root + "/shard-" + std::to_string(id) + ".json";
}

static std::string shard_lat_path(unsigned id, const std::string& root) {
    return root + "/shard-" + std::to_string(id) + ".lat";
}

static std::string shard_err_path(unsigned id, const std::string& root) {
    return root + "/shard-" + std::to_string(id) + ".err";
}

static void write_shard_error(unsigned id, const std::string& root, const std::string& msg) {
    std::ofstream ef(shard_err_path(id, root), std::ios::trunc);
    if (ef) {
        ef << msg;
    }
}

static void cleanup_tmp(const std::string& root, unsigned smp) {
    for (unsigned id = 0; id < smp; ++id) {
        ::remove(shard_json_path(id, root).c_str());
        ::remove(shard_lat_path(id, root).c_str());
        ::remove(shard_err_path(id, root).c_str());
    }
    ::rmdir(root.c_str());
}

static void write_shard_files(unsigned shard_id, const std::string& root,
                              uint64_t file_size, uint64_t dataset_size,
                              const std::vector<phase_result>& runs) {
    // Human-readable mirror (never read back; the .lat file is authoritative).
    std::ostringstream js;
    js << std::fixed << std::setprecision(6);
    js << "{\"shard\":" << shard_id
       << ",\"file_size_bytes\":" << file_size
       << ",\"dataset_bytes\":" << dataset_size
       << ",\"runs\":[";
    for (size_t r = 0; r < runs.size(); ++r) {
        if (r) js << ",";
        js << "{\"elapsed_sec\":" << runs[r].elapsed_s
           << ",\"ops\":" << runs[r].ops
           << ",\"bytes\":" << runs[r].bytes
           << ",\"errors\":" << runs[r].errors
           << ",\"lat_samples\":" << runs[r].lat_ns.size() << "}";
    }
    js << "]}";
    {
        std::ofstream jf(shard_json_path(shard_id, root), std::ios::trunc);
        if (!jf) {
            throw std::runtime_error("cannot write shard json mirror for shard " +
                                     std::to_string(shard_id));
        }
        jf << js.str() << "\n";
        jf.close();
        if (!jf) {
            throw std::runtime_error("short write of shard json mirror for shard " +
                                     std::to_string(shard_id));
        }
    }

    std::ofstream lf(shard_lat_path(shard_id, root), std::ios::binary | std::ios::trunc);
    if (!lf) {
        throw std::runtime_error("cannot write shard latency file for shard " +
                                 std::to_string(shard_id));
    }
    auto put_u64 = [&lf](uint64_t v) {
        lf.write(reinterpret_cast<const char*>(&v), static_cast<std::streamsize>(sizeof(v)));
    };
    put_u64(SHARD_LAT_MAGIC);
    put_u64(runs.size());
    put_u64(file_size);
    put_u64(dataset_size);
    for (const auto& r : runs) {
        const double elapsed = r.elapsed_s;
        lf.write(reinterpret_cast<const char*>(&elapsed), static_cast<std::streamsize>(sizeof(elapsed)));
        put_u64(r.ops);
        put_u64(r.bytes);
        put_u64(r.errors);
        put_u64(r.lat_ns.size());
        if (!r.lat_ns.empty()) {
            lf.write(reinterpret_cast<const char*>(r.lat_ns.data()),
                     static_cast<std::streamsize>(r.lat_ns.size() * sizeof(uint64_t)));
        }
    }
    lf.close();
    if (!lf) {
        throw std::runtime_error("short write of shard latency file for shard " +
                                 std::to_string(shard_id));
    }
}

struct shard_payload {
    uint64_t file_size = 0;
    uint64_t dataset_size = 0;
    std::vector<phase_result> runs;
};

static uint64_t read_u64(std::ifstream& in) {
    uint64_t v = 0;
    in.read(reinterpret_cast<char*>(&v), static_cast<std::streamsize>(sizeof(v)));
    if (!in) {
        throw std::runtime_error("truncated shard latency file");
    }
    return v;
}

static double read_f64(std::ifstream& in) {
    double v = 0.0;
    in.read(reinterpret_cast<char*>(&v), static_cast<std::streamsize>(sizeof(v)));
    if (!in) {
        throw std::runtime_error("truncated shard latency file");
    }
    return v;
}

static shard_payload read_shard_files(unsigned shard_id, const std::string& root,
                                      unsigned expected_runs) {
    std::ifstream lf(shard_lat_path(shard_id, root), std::ios::binary);
    if (!lf) {
        throw std::runtime_error("missing latency file for shard " + std::to_string(shard_id));
    }
    shard_payload p;
    if (read_u64(lf) != SHARD_LAT_MAGIC) {
        throw std::runtime_error("bad magic in latency file for shard " + std::to_string(shard_id));
    }
    const uint64_t nruns = read_u64(lf);
    if (nruns != expected_runs) {
        throw std::runtime_error("shard " + std::to_string(shard_id) + " reported " +
                                 std::to_string(nruns) + " runs, expected " +
                                 std::to_string(expected_runs));
    }
    p.file_size = read_u64(lf);
    p.dataset_size = read_u64(lf);
    p.runs.reserve(static_cast<size_t>(nruns));
    for (uint64_t r = 0; r < nruns; ++r) {
        phase_result pr;
        pr.elapsed_s = read_f64(lf);
        pr.ops = read_u64(lf);
        pr.bytes = read_u64(lf);
        pr.errors = read_u64(lf);
        const uint64_t n = read_u64(lf);
        if (n > 400000000ULL) {
            throw std::runtime_error("implausible latency sample count in shard " +
                                     std::to_string(shard_id));
        }
        pr.recorded = pr.ops;
        pr.lat_ns.resize(static_cast<size_t>(n));
        if (n > 0) {
            lf.read(reinterpret_cast<char*>(pr.lat_ns.data()),
                    static_cast<std::streamsize>(n * sizeof(uint64_t)));
            if (!lf) {
                throw std::runtime_error("truncated latency samples in shard " +
                                         std::to_string(shard_id));
            }
        }
        std::sort(pr.lat_ns.begin(), pr.lat_ns.end());
        p.runs.push_back(std::move(pr));
    }
    return p;
}

// ---------------------------------------------------------------------------
// per-shard work (runs on every seastar reactor via smp::invoke_on_all)
// ---------------------------------------------------------------------------

static seastar::future<> run_shard(config cfg, std::string root, uint64_t lat_cap,
                                   unsigned shard_id) {
    std::vector<phase_result> phase_runs;
    uint64_t file_size = 0;
    uint64_t dataset_size = 0;
    try {
        if (cfg.is_nop()) {
            // No file: measure reactor/fiber overhead with an immediately-ready future.
            for (unsigned r = 0; r < cfg.runs; ++r) {
                uint64_t start = now_ns();
                uint64_t deadline = start + static_cast<uint64_t>(cfg.seconds) * 1000000000ULL;
                uint64_t n = 0;
                while (now_ns() < deadline) {
                    co_await seastar::make_ready_future<>();
                    ++n;
                }
                phase_result pr;
                pr.elapsed_s = static_cast<double>(now_ns() - start) / 1e9;
                pr.ops = n;
                pr.recorded = n;
                phase_runs.push_back(std::move(pr));
            }
        } else {
            auto f = co_await seastar::open_file_dma(
                cfg.file.c_str(),
                cfg.is_write() ? (seastar::open_flags::rw | seastar::open_flags::create)
                               : seastar::open_flags::ro);
            file_size = co_await f.size();
            dataset_size = cfg.dataset == 0 ? file_size : std::min(cfg.dataset, file_size);
            dataset_size = (dataset_size / cfg.bs) * cfg.bs;
            if (dataset_size < cfg.bs) {
                co_await f.close();
                throw std::runtime_error("dataset smaller than block size");
            }
            cfg.dataset = dataset_size;
            for (unsigned r = 0; r < cfg.runs; ++r) {
                if (cfg.warmup_sec > 0) {
                    co_await run_phase(f, cfg, cfg.warmup_sec, false, lat_cap);
                }
                phase_runs.push_back(co_await run_phase(f, cfg, cfg.seconds, true, lat_cap));
            }
            co_await f.close();
        }
        write_shard_files(shard_id, root, file_size, dataset_size, phase_runs);
    } catch (const std::exception& e) {
        write_shard_error(shard_id, root, e.what());
    } catch (...) {
        write_shard_error(shard_id, root, "unknown exception");
    }
    co_return;
}

// Copyable/nothrow-movable functor wrapper for smp::invoke_on_all.
struct shard_work {
    config cfg;
    std::string root;
    uint64_t lat_cap = 0;

    seastar::future<> operator()() const {
        return run_shard(cfg, root, lat_cap,
                         static_cast<unsigned>(seastar::this_shard_id()));
    }
};

// ---------------------------------------------------------------------------
// json (self-contained, additive to the historical seastar shape, JUring 1.1)
// ---------------------------------------------------------------------------

struct manifest {
    std::string kernel;
    std::string cpu_model;
    std::string hostname;
    std::string backend;
    unsigned smp = 1;
    std::string command_line;
};

static std::string json_escape(const std::string& s) {
    std::ostringstream o;
    for (char c : s) {
        switch (c) {
            case '"': o << "\\\""; break;
            case '\\': o << "\\\\"; break;
            case '\n': o << "\\n"; break;
            case '\r': o << "\\r"; break;
            case '\t': o << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    o << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                      << static_cast<int>(c) << std::dec;
                } else {
                    o << c;
                }
        }
    }
    return o.str();
}

static std::string hostname_str() {
    char buf[256] = {};
    if (gethostname(buf, sizeof(buf) - 1) != 0) {
        return "unknown";
    }
    return buf;
}

static std::string cpu_model_str() {
    std::ifstream in("/proc/cpuinfo");
    std::string line;
    while (std::getline(in, line)) {
        if (line.compare(0, 10, "model name") == 0) {
            auto colon = line.find(':');
            if (colon != std::string::npos) {
                std::string v = line.substr(colon + 1);
                while (!v.empty() && (v.front() == ' ' || v.front() == '\t')) {
                    v.erase(v.begin());
                }
                return v;
            }
        }
    }
    return "unknown";
}

static std::string kernel_str() {
    struct utsname u;
    if (uname(&u) != 0) {
        return "unknown";
    }
    return std::string(u.sysname) + " " + u.release;
}

// Latency block: seastar's historical fields (samples, sampled, avg, p50..max)
// plus JUring 1.1's `recorded`/`min`. All values in microseconds.
static std::string percentile_json(const phase_result& r) {
    std::ostringstream o;
    auto pct = [&](double p) -> double {
        if (r.lat_ns.empty()) {
            return 0.0;
        }
        size_t idx = static_cast<size_t>(std::llround(p * (r.lat_ns.size() - 1)));
        idx = std::min(idx, r.lat_ns.size() - 1);
        return static_cast<double>(r.lat_ns[idx]) / 1000.0; // us
    };
    double sum = 0;
    for (auto v : r.lat_ns) {
        sum += static_cast<double>(v);
    }
    double avg = r.lat_ns.empty() ? 0.0 : sum / r.lat_ns.size() / 1000.0;
    // `samples` is how many per-op samples are in the reservoir; `sampled` says
    // whether that is the whole population (false: old first-N truncation) or a
    // uniform sample of it (true: population > cap). Same field names as JUring.
    o << "\"samples\":" << r.lat_ns.size()
      << ",\"recorded\":" << r.recorded
      << ",\"sampled\":" << ((!r.lat_ns.empty() && r.lat_ns.size() < r.ops) ? "true" : "false");
    o << std::fixed << std::setprecision(3);
    o << ",\"min\":" << (r.lat_ns.empty() ? 0.0 : static_cast<double>(r.lat_ns.front()) / 1000.0)
      << ",\"avg\":" << avg
      << ",\"p50\":" << pct(0.50)
      << ",\"p90\":" << pct(0.90)
      << ",\"p95\":" << pct(0.95)
      << ",\"p99\":" << pct(0.99)
      << ",\"p999\":" << pct(0.999)
      << ",\"max\":" << (r.lat_ns.empty() ? 0.0 : static_cast<double>(r.lat_ns.back()) / 1000.0);
    return o.str();
}

struct run_result {
    phase_result measured;              // merged over shards: ops/bytes/latency
    std::vector<phase_result> shards;   // per-shard phase results
    double iops = 0.0;
    double mibs = 0.0;
};

static double phase_iops(const phase_result& p) {
    return p.elapsed_s > 0 ? static_cast<double>(p.ops) / p.elapsed_s : 0.0;
}

static double phase_mibs(const phase_result& p) {
    return p.elapsed_s > 0
        ? static_cast<double>(p.bytes) / (1024.0 * 1024.0) / p.elapsed_s : 0.0;
}

// One runs[].threads[] / runs[].shards[] entry (JUring 1.x worker shape minus the
// JUring-only ring_metrics/ring_series/dispatch_latency_us/shard_stats).
static void render_shard_entry(std::ostringstream& o, const char* id_key, unsigned id,
                               const phase_result& p) {
    o << "{\"" << id_key << "\":" << id;
    o << ",\"elapsed_sec\":" << std::fixed << std::setprecision(6) << p.elapsed_s
      << std::setprecision(3);
    o << ",\"ops\":" << p.ops;
    o << ",\"bytes\":" << p.bytes;
    o << ",\"errors\":" << p.errors;
    o << ",\"iops\":" << phase_iops(p);
    o << ",\"mibs\":" << phase_mibs(p);
    o << ",\"latency_us\":{" << percentile_json(p) << "}}";
}

static std::string render_json(const config& cfg, const manifest& m, unsigned smp,
                               const std::vector<std::string>& args,
                               uint64_t file_size, uint64_t dataset_size,
                               const std::vector<run_result>& runs) {
    std::ostringstream o;
    o << std::fixed;
    o << "{";
    o << "\"schema_version\":\"" << SCHEMA_VERSION << "\"";
    o << ",\"engine\":\"" << ENGINE << "\"";
    o << ",\"engine_detail\":\"seastar_file_bench\"";
    o << ",\"status\":\"ok\"";
    o << ",\"error\":null";
    o << ",\"label\":\"" << json_escape(cfg.label.empty() ? cfg.workload : cfg.label) << "\"";
    o << ",\"timestamp_utc\":\"" << json_escape([] {
            std::time_t t = std::time(nullptr);
            char b[32];
            std::strftime(b, sizeof(b), "%Y-%m-%dT%H:%M:%SZ", std::gmtime(&t));
            return std::string(b);
        }()) << "\"";
    o << ",\"argv\":[";
    for (size_t i = 0; i < args.size(); ++i) {
        if (i) o << ",";
        o << "\"" << json_escape(args[i]) << "\"";
    }
    o << "]";

    o << ",\"manifest\":{";
    o << "\"kernel\":\"" << json_escape(m.kernel) << "\"";
    o << ",\"cpu_model\":\"" << json_escape(m.cpu_model) << "\"";
    o << ",\"hostname\":\"" << json_escape(m.hostname) << "\"";
    o << ",\"reactor_backend\":\"" << json_escape(m.backend) << "\"";
    o << ",\"smp\":" << m.smp;   // numeric N (M3 driver validates manifest.smp == N)
    o << ",\"shards\":" << smp;
    o << ",\"command_line\":\"" << json_escape(m.command_line) << "\"";
    o << ",\"seastar_version\":\"seastar-25.05.0-1392-g04b1c04d\"";
    o << ",\"compiler\":\"g++ " << __GNUC__ << "." << __GNUC_MINOR__ << "." << __GNUC_PATCHLEVEL__ << "\"";
    o << "}";

    o << ",\"config\":{";
    o << "\"workload\":\"" << json_escape(cfg.workload) << "\"";
    o << ",\"file\":\"" << json_escape(cfg.file) << "\"";
    o << ",\"file_size_bytes\":" << file_size;
    o << ",\"dataset_bytes\":" << dataset_size;
    o << ",\"bs\":" << cfg.bs;
    o << ",\"qd\":" << cfg.qd;
    o << ",\"threads\":" << smp;
    o << ",\"runtime\":\"smp\"";
    o << ",\"shards\":" << smp;
    o << ",\"seconds\":" << cfg.seconds;
    o << ",\"warmup_sec\":" << cfg.warmup_sec;
    o << ",\"runs\":" << cfg.runs;
    o << ",\"seed\":" << cfg.seed;
    o << ",\"readonly\":" << (cfg.is_write() ? "false" : "true");
    o << ",\"direct_io\":true";
    o << ",\"ring_entries\":200";
    o << ",\"ring_flags\":[\"seastar io_uring backend default (no SINGLE_ISSUER/COOP_TASKRUN/DEFER_TASKRUN)\"]";
    o << ",\"io_buffer_mode\":\"aligned-unregistered\"";
    o << ",\"fixed_buffers\":false";
    o << ",\"registered_files\":false";
    o << "}";

    o << ",\"runs\":[";
    for (size_t i = 0; i < runs.size(); ++i) {
        const auto& r = runs[i];
        if (i) o << ",";
        o << "{";
        o << "\"run\":" << (i + 1);
        o << ",\"seconds\":" << std::setprecision(6) << r.measured.elapsed_s << std::setprecision(3);
        o << ",\"elapsed_sec\":" << std::setprecision(6) << r.measured.elapsed_s
          << std::setprecision(3);
        o << ",\"ops_total\":" << r.measured.ops;
        o << ",\"bytes_total\":" << r.measured.bytes;
        o << ",\"errors_total\":" << r.measured.errors;
        o << ",\"ops\":" << r.measured.ops;
        o << ",\"bytes\":" << r.measured.bytes;
        o << ",\"errors\":" << r.measured.errors;
        o << ",\"iops\":" << std::setprecision(3) << r.iops;
        o << ",\"mibs\":" << std::setprecision(3) << r.mibs;
        o << ",\"latency_us\":{" << percentile_json(r.measured) << "}";
        o << ",\"threads\":[";
        for (size_t k = 0; k < r.shards.size(); ++k) {
            if (k) o << ",";
            render_shard_entry(o, "thread", static_cast<unsigned>(k), r.shards[k]);
        }
        o << "],\"shards\":[";
        for (size_t k = 0; k < r.shards.size(); ++k) {
            if (k) o << ",";
            render_shard_entry(o, "shard", static_cast<unsigned>(k), r.shards[k]);
        }
        o << "]}";
    }
    o << "]";

    // aggregate: keep the historical seastar shape/values (sum over runs).
    uint64_t ops = 0, bytes = 0, errs = 0, recorded = 0;
    double elapsed = 0.0;
    std::vector<uint64_t> all_lat;
    for (const auto& r : runs) {
        ops += r.measured.ops;
        bytes += r.measured.bytes;
        errs += r.measured.errors;
        recorded += r.measured.recorded;
        elapsed += r.measured.elapsed_s;
        all_lat.insert(all_lat.end(), r.measured.lat_ns.begin(), r.measured.lat_ns.end());
    }
    std::sort(all_lat.begin(), all_lat.end());
    double iops = elapsed > 0 ? static_cast<double>(ops) / elapsed : 0.0;
    double mibs = elapsed > 0 ? static_cast<double>(bytes) / (1024.0 * 1024.0) / elapsed : 0.0;
    phase_result agg;
    agg.lat_ns = std::move(all_lat);
    agg.elapsed_s = elapsed;
    agg.ops = ops;
    agg.bytes = bytes;
    agg.errors = errs;
    agg.recorded = recorded;
    double iops_sd = 0.0;
    if (runs.size() > 1) {
        double mean = 0;
        for (auto& r : runs) mean += r.iops;
        mean /= runs.size();
        for (auto& r : runs) iops_sd += (r.iops - mean) * (r.iops - mean);
        iops_sd = std::sqrt(iops_sd / (runs.size() - 1));
    }
    o << ",\"aggregate\":{";
    o << "\"runs\":" << runs.size();
    o << ",\"elapsed_sec\":" << std::setprecision(6) << elapsed << std::setprecision(3);
    o << ",\"ops_total\":" << ops;
    o << ",\"bytes_total\":" << bytes;
    o << ",\"errors_total\":" << errs;
    o << ",\"iops\":{\"mean\":" << iops << ",\"stdev\":" << iops_sd << "}";
    o << ",\"mibs\":{\"mean\":" << mibs << "}";
    o << ",\"latency_us\":{" << percentile_json(agg) << "}";
    o << "}";
    o << "}";
    return o.str();
}

// ---------------------------------------------------------------------------
// collect (needs run_result/phase_result definitions from above)
// ---------------------------------------------------------------------------

static std::vector<run_result> collect_runs(const config& cfg, const std::string& root,
                                            unsigned smp, uint64_t& file_size,
                                            uint64_t& dataset_size) {
    std::vector<shard_payload> payloads(smp);
    for (unsigned id = 0; id < smp; ++id) {
        std::ifstream ef(shard_err_path(id, root));
        if (ef) {
            std::ostringstream msg;
            msg << ef.rdbuf();
            throw std::runtime_error("shard " + std::to_string(id) + " failed: " + msg.str());
        }
        payloads[id] = read_shard_files(id, root, cfg.runs);
    }
    file_size = payloads[0].file_size;
    dataset_size = payloads[0].dataset_size;

    std::vector<run_result> out(cfg.runs);
    for (unsigned r = 0; r < cfg.runs; ++r) {
        run_result& rr = out[r];
        rr.shards.resize(smp);
        double max_elapsed = 0.0;
        std::vector<uint64_t> merged;
        for (unsigned id = 0; id < smp; ++id) {
            phase_result p = std::move(payloads[id].runs[r]);
            if (p.elapsed_s > max_elapsed) {
                max_elapsed = p.elapsed_s;
            }
            rr.measured.ops += p.ops;
            rr.measured.bytes += p.bytes;
            rr.measured.errors += p.errors;
            rr.measured.recorded += p.recorded;
            merged.insert(merged.end(), p.lat_ns.begin(), p.lat_ns.end());
            rr.shards[id] = std::move(p);
        }
        std::sort(merged.begin(), merged.end());
        rr.measured.lat_ns = std::move(merged);
        rr.measured.elapsed_s = max_elapsed;
        rr.iops = phase_iops(rr.measured);
        rr.mibs = phase_mibs(rr.measured);
    }
    return out;
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

int main(int argc, char** argv) {
    seastar::app_template app;
    app.add_options()
        ("file", bpo::value<std::string>(), "existing file to benchmark (required for I/O workloads)")
        ("workload", bpo::value<std::string>()->default_value("randread"),
            "randread|randwrite|seqread|seqwrite|nop")
        ("bs", bpo::value<std::string>()->default_value("4k"), "block size (e.g. 4k, 1M)")
        ("qd", bpo::value<unsigned>()->default_value(64), "in-flight I/O per shard (fibers)")
        ("seconds", bpo::value<unsigned>()->default_value(10), "measured duration per run")
        ("warmup-sec", bpo::value<unsigned>()->default_value(2), "warmup before each measured phase")
        ("runs", bpo::value<unsigned>()->default_value(1), "repetitions")
        ("seed", bpo::value<uint64_t>()->default_value(20261113), "PRNG seed")
        ("size", bpo::value<std::string>(), "dataset bytes to use (default: whole file)")
        ("readonly", bpo::bool_switch(), "assert read-only operation")
        ("json", bpo::value<std::string>(), "also write the JSON report to this path")
        ("label", bpo::value<std::string>(), "case label for reports");

    return app.run(argc, argv, [&app, argc, argv]() -> seastar::future<> {
        auto& vm = app.configuration();
        config cfg;
        cfg.workload = vm["workload"].as<std::string>();
        cfg.bs = parse_size(vm["bs"].as<std::string>());
        cfg.qd = vm["qd"].as<unsigned>();
        cfg.seconds = vm["seconds"].as<unsigned>();
        cfg.warmup_sec = vm["warmup-sec"].as<unsigned>();
        cfg.runs = vm["runs"].as<unsigned>();
        cfg.seed = vm["seed"].as<uint64_t>();
        cfg.readonly = vm["readonly"].as<bool>();
        if (vm.count("file")) cfg.file = vm["file"].as<std::string>();
        if (vm.count("json")) cfg.json_path = vm["json"].as<std::string>();
        if (vm.count("label")) cfg.label = vm["label"].as<std::string>();
        if (vm.count("size")) cfg.dataset = parse_size(vm["size"].as<std::string>());

        if (!cfg.is_nop() && cfg.file.empty()) {
            throw std::runtime_error("--file is required for I/O workloads");
        }
        if (cfg.is_write() && cfg.readonly) {
            throw std::runtime_error("write workload requested with --readonly");
        }

        const unsigned smp = static_cast<unsigned>(seastar::this_smp_shard_count());
        if (smp < 1 || smp > 64) {
            throw std::runtime_error("--smp must be in [1, 64] (got " + std::to_string(smp) + ")");
        }
        if (cfg.qd < 1) {
            throw std::runtime_error("--qd must be >= 1");
        }
        if (cfg.runs < 1) {
            throw std::runtime_error("--runs must be >= 1");
        }
        if (cfg.bs < 512 || (cfg.bs & (cfg.bs - 1)) != 0) {
            throw std::runtime_error("--bs must be a power of two >= 512");
        }

        std::vector<std::string> args;
        args.reserve(static_cast<size_t>(argc));
        for (int i = 0; i < argc; ++i) {
            args.emplace_back(argv[i]);
        }
        std::ostringstream cmd;
        for (int i = 0; i < argc; ++i) {
            if (i) cmd << ' ';
            cmd << argv[i];
        }
        manifest m;
        m.kernel = kernel_str();
        m.cpu_model = cpu_model_str();
        m.hostname = hostname_str();
        m.backend = std::string(seastar::engine().get_backend_name());
        m.smp = smp;
        m.command_line = cmd.str();

        // Per-fiber uniform-reservoir cap. Single-shard keeps the historical
        // formula exactly; multi-shard scales it down so that the *merged*
        // reservoir stays informative without moving >q*d*N*8 B per shard across
        // the tmpfs barrier (N=8/QD64 => 5000/fiber => 320k samples/shard).
        const uint64_t qd64 = std::max(1u, cfg.qd);
        const uint64_t lat_cap = smp == 1
            ? std::max<uint64_t>(20000, 2000000 / qd64)
            : std::max<uint64_t>(5000, 2000000 / (qd64 * smp));

        const std::string root = tmp_root();
        if (::mkdir(root.c_str(), 0700) != 0 && errno != EEXIST) {
            throw std::runtime_error("cannot create temp dir " + root + ": " + std::strerror(errno));
        }
        // Clear stale shard files (pid reuse after a crash) before the barrier.
        for (unsigned id = 0; id < smp; ++id) {
            ::remove(shard_json_path(id, root).c_str());
            ::remove(shard_lat_path(id, root).c_str());
            ::remove(shard_err_path(id, root).c_str());
        }

        std::vector<run_result> runs;
        uint64_t file_size = 0;
        uint64_t dataset_size = 0;
        try {
            // A named functor (instead of a lambda) keeps the closure trivially
            // nothrow-move-constructible, which seastar::smp::invoke_on_all
            // requires; std::is_nothrow_move_constructible_v<shard_work> holds
            // because config contains only strings/integers.
            co_await seastar::smp::invoke_on_all(shard_work{cfg, root, lat_cap});
            runs = collect_runs(cfg, root, smp, file_size, dataset_size);
        } catch (...) {
            cleanup_tmp(root, smp);
            throw;
        }
        cleanup_tmp(root, smp);

        std::string json = render_json(cfg, m, smp, args, file_size, dataset_size, runs);
        std::cout << json << std::endl;
        if (!cfg.json_path.empty()) {
            std::ofstream out(cfg.json_path);
            if (!out) {
                throw std::runtime_error("cannot open --json path: " + cfg.json_path);
            }
            out << json << "\n";
        }
        co_return;
    });
}

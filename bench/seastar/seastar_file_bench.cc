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
 *   - same JSON schema as JUringFileBench (schema_version 1.0), so
 *     bench/parse_results.py can consume both.
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
 * Run (single shard; the reactor/shard flags are seastar's own):
 *   taskset -c 2 seastar_file_bench \
 *     --file /mnt/TestData/read_files/single_file_bench.bin \
 *     --workload randread --bs 4k --qd 64 --seconds 10 --warmup-sec 2 \
 *     --readonly --seed 20261113 --label rr4k_qd64 --json out.json
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
#include <chrono>
#include <cmath>
#include <cstdint>
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

#include <sys/utsname.h>
#include <unistd.h>

namespace bpo = boost::program_options;
using clock_type = std::chrono::steady_clock;

static constexpr const char* SCHEMA_VERSION = "1.0";
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
    std::vector<uint64_t> lat_ns;
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
                                               unsigned seconds, bool collect) {
    std::vector<fiber_stat> fibers(cfg.qd);
    uint64_t lat_cap = std::max<uint64_t>(20000, 2000000 / std::max(1u, cfg.qd));
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
// json (self-contained, same schema as JUringFileBench)
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
      << ",\"sampled\":" << ((!r.lat_ns.empty() && r.lat_ns.size() < r.ops) ? "true" : "false");
    o << std::fixed << std::setprecision(3);
    o << ",\"avg\":" << avg
      << ",\"p50\":" << pct(0.50)
      << ",\"p90\":" << pct(0.90)
      << ",\"p95\":" << pct(0.95)
      << ",\"p99\":" << pct(0.99)
      << ",\"p999\":" << pct(0.999)
      << ",\"max\":" << (r.lat_ns.empty() ? 0.0 : static_cast<double>(r.lat_ns.back()) / 1000.0);
    return o.str();
}

struct run_result {
    phase_result measured;
    double iops = 0.0;
    double mibs = 0.0;
};

static std::string render_json(const config& cfg, const manifest& m,
                               uint64_t file_size, uint64_t dataset_size,
                               const std::vector<run_result>& runs, int status) {
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

    o << ",\"manifest\":{";
    o << "\"kernel\":\"" << json_escape(m.kernel) << "\"";
    o << ",\"cpu_model\":\"" << json_escape(m.cpu_model) << "\"";
    o << ",\"hostname\":\"" << json_escape(m.hostname) << "\"";
    o << ",\"reactor_backend\":\"" << json_escape(m.backend) << "\"";
    o << ",\"smp\":\"" << m.smp << "\"";
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
    o << ",\"threads\":1";
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
        o << ",\"ops_total\":" << r.measured.ops;
        o << ",\"bytes_total\":" << r.measured.bytes;
        o << ",\"errors_total\":" << r.measured.errors;
        o << ",\"iops\":" << std::setprecision(3) << r.iops;
        o << ",\"mibs\":" << std::setprecision(3) << r.mibs;
        o << ",\"latency_us\":{" << percentile_json(r.measured) << "}";
        o << "}";
    }
    o << "]";

    // aggregate
    uint64_t ops = 0, bytes = 0, errs = 0;
    double elapsed = 0.0;
    std::vector<uint64_t> all_lat;
    for (const auto& r : runs) {
        ops += r.measured.ops;
        bytes += r.measured.bytes;
        errs += r.measured.errors;
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
        if (seastar::this_smp_shard_count() != 1) {
            throw std::runtime_error("run with --smp 1 for now (multi-shard mode is a later addition)");
        }
        if (cfg.bs < 512 || (cfg.bs & (cfg.bs - 1)) != 0) {
            throw std::runtime_error("--bs must be a power of two >= 512");
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
        m.smp = seastar::this_smp_shard_count();
        m.command_line = cmd.str();

        std::vector<run_result> runs;
        uint64_t file_size = 0;
        uint64_t dataset_size = 0;

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
                run_result rr;
                rr.measured.elapsed_s = static_cast<double>(now_ns() - start) / 1e9;
                rr.measured.ops = n;
                rr.measured.bytes = 0;
                rr.iops = n / rr.measured.elapsed_s;
                rr.mibs = 0;
                runs.push_back(std::move(rr));
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
                throw std::runtime_error("dataset smaller than block size");
            }
            cfg.dataset = dataset_size;
            for (unsigned r = 0; r < cfg.runs; ++r) {
                if (cfg.warmup_sec > 0) {
                    co_await run_phase(f, cfg, cfg.warmup_sec, false);
                }
                phase_result measured = co_await run_phase(f, cfg, cfg.seconds, true);
                run_result rr;
                rr.measured = std::move(measured);
                rr.iops = rr.measured.elapsed_s > 0
                    ? static_cast<double>(rr.measured.ops) / rr.measured.elapsed_s : 0.0;
                rr.mibs = rr.measured.elapsed_s > 0
                    ? static_cast<double>(rr.measured.bytes) / (1024.0 * 1024.0) / rr.measured.elapsed_s : 0.0;
                runs.push_back(std::move(rr));
            }
            co_await f.close();
        }

        std::string json = render_json(cfg, m, file_size, dataset_size, runs, 0);
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

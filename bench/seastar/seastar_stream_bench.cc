/*
 * seastar_stream_bench - seastar fstream (stream) counterpart of
 * bench/juring/JUringStreamBench.java (B2 / W21-W22).
 *
 * Purpose
 * -------
 * Measure seastar's *stream* API (fstream) on an existing file with the same
 * CLI, JSON schema, latency definition and dataset conventions as the JUring
 * stream runner, so the two engines can be compared cell by cell:
 *
 *   - same file, same O_DIRECT path (seastar open_file_dma is always DMA here),
 *   - same stream buffer size (--bs) and concurrency (--qd),
 *   - same per-chunk latency definition (timestamp before the stream call,
 *     timestamp when the chunk's future resolves, including any wait),
 *   - same bounded uniform reservoir (Algorithm R) for percentiles, so a long
 *     measured window's tails are sampled uniformly instead of being truncated
 *     to the first N chunks,
 *   - same JSON schema (schema_version 1.0) as JUringFileBench / JUringStreamBench,
 *     so bench/stream/analyze_stream.py can consume both engines.
 *
 * Workloads
 * ---------
 *   stream_read : open_file_dma(ro), make_file_input_stream(f, 0, dataset,
 *                 {buffer_size=bs, read_ahead=qd-1, dynamic_adjustments=history}),
 *                 loop read() until an empty buffer (EOF), close the stream and
 *                 start a new pass from offset 0 until the phase deadline.
 *   stream_write: open_file_dma(rw|create), make_file_output_stream(f,
 *                 {buffer_size=bs, preallocation_size=0, write_behind=qd}),
 *                 write exactly dataset bytes in bs-sized chunks from one reused
 *                 aligned buffer (deterministic per-pass pattern), flush(), close(),
 *                 reopen the file and start a new pass until the phase deadline;
 *                 the chunk loop stops cleanly at chunk granularity.
 *
 * Read-ahead asymmetry (disclosed in the JSON as read_ahead_mode)
 * ---------------------------------------------------------------
 * seastar only honours `read_ahead > 1` when dynamic adjustments are enabled:
 * file_data_source_impl::get_initial_read_ahead() returns `!!read_ahead` (i.e.
 * 1) when `dynamic_adjustments` is null. To actually get ~qd requests in
 * flight we therefore pass `read_ahead = qd - 1` *and* a shared
 * file_input_stream_history, reused across all passes of the cell so seastar's
 * slow start has already ramped up when the measured phase starts. This is an
 * engine asymmetry vs JUring's fixed `readAhead = qd - 1` (JUring has no
 * adaptive mode) and is recorded as `"read_ahead_mode":"seastar-adaptive"`.
 * As a side effect seastar's adaptive path also slow-starts the *buffer size*
 * from min(bs/4, bs) up to bs - `stream_buffer_size` in the JSON is the
 * requested (maximum) value, not the instantaneous one.
 *
 * Write-behind is exact: file_output_stream_options::write_behind = qd maps
 * directly to qd concurrent dma_write()s, so stream_concurrency == qd there.
 *
 * EOF / padding semantics (documented deviations)
 * -----------------------------------------------
 *   - Reads stop at EOF (an empty buffer from input_stream::read(); the design
 *     doc's `s.get()` is the data_source method below it - input_stream only
 *     exposes read(), which forwards to data_source::get()).
 *   - Writes: fstream's sink pads an unaligned tail with zeros and then
 *     truncate()s the file to the logical position (seastar
 *     file_data_sink_impl::do_put), i.e. the same rule as the JUring output
 *     stream. To avoid resizing the existing benchmark file we require
 *     `--size` to be a multiple of `--bs` for stream_write. A pass stopped at
 *     the deadline only ever ends on a chunk boundary, so a partial pass never
 *     truncates anything.
 *
 * Build: bench/seastar/build-stream-bench.sh (SeaStarRef/build/release +
 *        .sysroot in the sandbox; on a host, `pkg-config --libs --cflags
 *        --static seastar` works).
 *
 * Run (single shard; the reactor/shard flags are seastar's own):
 *   LD_LIBRARY_PATH=$PWD/.sysroot/root/usr/lib64 taskset -c 2 \
 *     bench/seastar/seastar_stream_bench \
 *       --file /mnt/TestData/read_files/single_file_bench.bin \
 *       --workload stream_read --bs 128k --qd 8 --seconds 10 --warmup-sec 2 \
 *       --readonly --label W22-128k --json /tmp/w22-128k.json \
 *       --smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend io_uring
 */

#include <seastar/core/app-template.hh>
#include <seastar/core/coroutine.hh>
#include <seastar/core/file.hh>
#include <seastar/core/fstream.hh>
#include <seastar/core/future.hh>
#include <seastar/core/aligned_buffer.hh>
#include <seastar/core/reactor.hh>
#include <seastar/core/seastar.hh>
#include <seastar/core/shared_ptr.hh>
#include <seastar/core/smp.hh>

#include <boost/program_options.hpp>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
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
static constexpr const char* ENGINE_DETAIL = "seastar_stream_bench";

// ---------------------------------------------------------------------------
// config
// ---------------------------------------------------------------------------

struct config {
    std::string workload = "stream_read";
    std::string file;
    std::string label;
    std::string json_path;
    uint64_t bs = 128 * 1024;   // stream buffer size
    unsigned qd = 8;            // stream concurrency (in-flight depth)
    unsigned seconds = 10;
    unsigned warmup_sec = 2;
    unsigned runs = 1;
    uint64_t seed = 20261113;
    uint64_t dataset = 0;       // 0 => file size
    bool readonly = false;
    bool no_register_file = false;  // accepted no-op disclosure flag
    // Shared adaptive read-ahead history: reused across every pass of this
    // process/cell so seastar's slow start is only paid once (see file header).
    seastar::lw_shared_ptr<seastar::file_input_stream_history> history;

    bool is_write() const { return workload == "stream_write"; }
    bool is_read() const { return workload == "stream_read"; }
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

static std::string current_exception_what() {
    try {
        throw;
    } catch (const std::exception& e) {
        return e.what();
    } catch (...) {
        return "unknown exception";
    }
}

// ---------------------------------------------------------------------------
// timing / latency reservoir (identical semantics to seastar_file_bench.cc)
// ---------------------------------------------------------------------------

static uint64_t now_ns() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            clock_type::now().time_since_epoch()).count());
}

static inline uint64_t xorshift64(uint64_t& x) {
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    return x * 0x2545F4914F6CDD1DULL;
}

// Uniform reservoir sample (Algorithm R), same as seastar_file_bench.cc. A
// stream workload has a single sequential series of chunks per phase, so one
// reservoir per phase is enough; the capacity follows the same bounded policy
// as the file bench (max(20000, 2000000/qd) samples => at most a few MB).
struct reservoir {
    uint64_t seen = 0;
    uint64_t rng = 0x9E3779B97F4A7C15ULL;
    std::vector<uint64_t> samples;

    void add(uint64_t ns, uint64_t cap) {
        seen++;
        if (cap == 0) {
            return;
        }
        if (samples.size() < cap) {
            samples.push_back(ns);
            return;
        }
        const uint64_t j = xorshift64(rng) % seen;
        if (j < cap) {
            samples[static_cast<size_t>(j)] = ns;
        }
    }
};

// ---------------------------------------------------------------------------
// per-pass / per-phase results
// ---------------------------------------------------------------------------

struct pass_result {
    uint64_t ops = 0;
    uint64_t bytes = 0;
    uint64_t errors = 0;
};

struct phase_result {
    uint64_t ops = 0;
    uint64_t bytes = 0;
    uint64_t errors = 0;
    uint64_t passes = 0;
    double elapsed_s = 0.0;
    std::vector<uint64_t> lat_ns;   // uniform reservoir of per-chunk latencies (ns)
};

// Fill a reused aligned buffer with a deterministic (xorshift64) pattern.
static void fill_pattern(char* p, uint64_t n, uint64_t seed) {
    uint64_t rng = seed ? seed : 1;
    uint64_t i = 0;
    for (; i + 8 <= n; i += 8) {
        uint64_t v = xorshift64(rng);
        std::memcpy(p + i, &v, 8);
    }
    if (i < n) {
        uint64_t v = xorshift64(rng);
        std::memcpy(p + i, &v, static_cast<size_t>(n - i));
    }
}

// ---------------------------------------------------------------------------
// one read pass: open file + input stream at offset 0, read until EOF
// or until the phase deadline, then close the stream and the file.
// ---------------------------------------------------------------------------

static seastar::future<pass_result>
read_pass(const config& cfg, uint64_t dataset, uint64_t deadline,
          reservoir* res, uint64_t lat_cap) {
    pass_result pr;
    auto f = co_await seastar::open_file_dma(cfg.file.c_str(), seastar::open_flags::ro);

    seastar::file_input_stream_options opts;
    opts.buffer_size = static_cast<size_t>(cfg.bs);   // requested maximum
    opts.read_ahead = cfg.qd - 1;                     // effective only with history (adaptive)
    opts.dynamic_adjustments = cfg.history;

    // len == dataset: the stream stops at EOF anyway, and this keeps --size
    // meaningful for reads on a larger file (same contract as the JUring runner).
    auto s = seastar::make_file_input_stream(f, 0, dataset, opts);

    while (now_ns() < deadline) {
        uint64_t t0 = now_ns();
        try {
            // input_stream::read() forwards to data_source::get(); an empty
            // buffer is EOF.
            auto buf = co_await s.read();
            uint64_t t1 = now_ns();
            if (buf.empty()) {
                break;   // EOF: end of this pass, reopen at offset 0
            }
            pr.ops++;
            pr.bytes += buf.size();
            if (res) {
                res->add(t1 - t0, lat_cap);
            }
        } catch (...) {
            pr.errors++;
            std::cerr << "seastar_stream_bench: stream_read chunk error: "
                      << current_exception_what() << "\n";
            break;
        }
    }

    try {
        co_await s.close();
    } catch (...) {
        pr.errors++;
        std::cerr << "seastar_stream_bench: stream_read close error: "
                  << current_exception_what() << "\n";
    }
    try {
        co_await f.close();
    } catch (...) {
        pr.errors++;
        std::cerr << "seastar_stream_bench: stream_read file close error: "
                  << current_exception_what() << "\n";
    }
    co_return pr;
}

// ---------------------------------------------------------------------------
// one write pass: open file (rw|create) + output stream, write exactly
// `dataset` bytes as bs-sized chunks, flush(), close(), then the caller
// reopens for the next pass. The chunk loop stops cleanly at the deadline.
// ---------------------------------------------------------------------------

static seastar::future<pass_result>
write_pass(const config& cfg, uint64_t dataset, uint64_t deadline,
           uint64_t pass_index, reservoir* res, uint64_t lat_cap) {
    pass_result pr;
    auto f = co_await seastar::open_file_dma(
        cfg.file.c_str(),
        seastar::open_flags::rw | seastar::open_flags::create);

    seastar::file_output_stream_options opts;
    opts.buffer_size = static_cast<unsigned>(cfg.bs);
    opts.preallocation_size = 0;
    opts.write_behind = cfg.qd;

    // NOTE: output_stream::close() closes the underlying file (file_data_sink_impl),
    // so the file must be reopened for every pass - and must not be closed twice here.
    auto s = co_await seastar::make_file_output_stream(std::move(f), opts);

    auto buf = seastar::allocate_aligned_buffer<char>(static_cast<size_t>(cfg.bs), 4096);
    char* p = buf.get();
    fill_pattern(p, cfg.bs,
                 cfg.seed ^ (0x9E3779B97F4A7C15ULL * (pass_index + 1)));

    uint64_t remaining = dataset;
    while (remaining > 0 && now_ns() < deadline) {
        const size_t n = static_cast<size_t>(std::min<uint64_t>(cfg.bs, remaining));
        uint64_t t0 = now_ns();
        try {
            co_await s.write(p, n);
            uint64_t t1 = now_ns();
            pr.ops++;
            pr.bytes += n;
            remaining -= n;
            if (res) {
                res->add(t1 - t0, lat_cap);
            }
        } catch (...) {
            pr.errors++;
            std::cerr << "seastar_stream_bench: stream_write chunk error: "
                      << current_exception_what() << "\n";
            break;
        }
    }

    // Always drain + close cleanly (also when the deadline interrupted the pass;
    // all chunks are whole bs-sized chunks, so an interrupted pass leaves the
    // file length untouched - no unaligned tail => no truncate()).
    try {
        co_await s.flush();
    } catch (...) {
        pr.errors++;
        std::cerr << "seastar_stream_bench: stream_write flush error: "
                  << current_exception_what() << "\n";
    }
    try {
        co_await s.close();   // closes the file too
    } catch (...) {
        pr.errors++;
        std::cerr << "seastar_stream_bench: stream_write close error: "
                  << current_exception_what() << "\n";
    }
    co_return pr;
}

// ---------------------------------------------------------------------------
// phase: repeat passes until the deadline
// ---------------------------------------------------------------------------

static seastar::future<phase_result>
run_phase(const config& cfg, uint64_t dataset, unsigned seconds, bool collect) {
    phase_result ph;
    const uint64_t lat_cap =
        std::max<uint64_t>(20000, 2000000 / std::max(1u, cfg.qd));
    reservoir res;
    res.rng = cfg.seed ^ 0xA5A5A5A5DEADBEEFULL;
    if (res.rng == 0) {
        res.rng = 1;
    }

    const uint64_t start = now_ns();
    const uint64_t deadline = start + static_cast<uint64_t>(seconds) * 1000000000ULL;
    uint64_t pass_index = 0;

    while (now_ns() < deadline) {
        pass_result pr;
        if (cfg.is_read()) {
            pr = co_await read_pass(cfg, dataset, deadline,
                                    collect ? &res : nullptr, lat_cap);
        } else {
            pr = co_await write_pass(cfg, dataset, deadline, pass_index,
                                     collect ? &res : nullptr, lat_cap);
        }
        ph.ops += pr.ops;
        ph.bytes += pr.bytes;
        ph.errors += pr.errors;
        ph.passes++;
        if (pr.errors > 0) {
            // Do not hammer a failing device for the rest of the window.
            break;
        }
        pass_index++;
    }

    ph.elapsed_s = static_cast<double>(now_ns() - start) / 1e9;
    if (collect) {
        ph.lat_ns = std::move(res.samples);
        // percentile_json() indexes a sorted array (same contract as
        // seastar_file_bench.cc, which sorts the merged per-fiber reservoirs).
        std::sort(ph.lat_ns.begin(), ph.lat_ns.end());
    }
    co_return std::move(ph);
}

// ---------------------------------------------------------------------------
// JSON (same schema as seastar_file_bench.cc / JUringStreamBench)
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
    // `samples` = reservoir size; `sampled` = true when the population is larger
    // than the reservoir (uniform sample of the whole window), as in JUring.
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
                               const std::vector<run_result>& runs,
                               const std::string& status, const std::string& error) {
    std::ostringstream o;
    o << std::fixed;
    o << "{";
    o << "\"schema_version\":\"" << SCHEMA_VERSION << "\"";
    o << ",\"engine\":\"" << ENGINE << "\"";
    o << ",\"engine_detail\":\"" << ENGINE_DETAIL << "\"";
    o << ",\"status\":\"" << json_escape(status) << "\"";
    o << ",\"error\":" << (error.empty() ? "null" : ("\"" + json_escape(error) + "\""));
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
    // String, for exact schema compatibility with seastar_file_bench.cc.
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
    o << ",\"io_buffer_mode\":\"fstream-aligned-unregistered\"";
    o << ",\"fixed_buffers\":false";
    o << ",\"registered_files\":false";
    o << ",\"stream_buffer_size\":" << cfg.bs;
    o << ",\"stream_concurrency\":" << cfg.qd;
    o << ",\"read_ahead_mode\":\"" << (cfg.is_read() ? "seastar-adaptive" : "n/a") << "\"";
    if (cfg.is_write()) {
        o << ",\"write_behind\":" << cfg.qd;
    } else {
        o << ",\"read_ahead\":" << (cfg.qd - 1);
    }
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

    // aggregate (same conventions as seastar_file_bench.cc: throughput mean is
    // total bytes/ops over total elapsed; stdev/sd/ci95 are across runs when
    // runs > 1, 0 otherwise)
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
    const double iops = elapsed > 0 ? static_cast<double>(ops) / elapsed : 0.0;
    const double mibs = elapsed > 0
        ? static_cast<double>(bytes) / (1024.0 * 1024.0) / elapsed : 0.0;
    phase_result agg;
    agg.lat_ns = std::move(all_lat);
    agg.elapsed_s = elapsed;
    agg.ops = ops;
    agg.bytes = bytes;
    agg.errors = errs;

    double iops_sd = 0.0;
    double mibs_sd = 0.0;
    double mibs_ci95 = 0.0;
    if (runs.size() > 1) {
        const double n = static_cast<double>(runs.size());
        double iops_mean = 0.0, mibs_mean = 0.0;
        for (const auto& r : runs) {
            iops_mean += r.iops;
            mibs_mean += r.mibs;
        }
        iops_mean /= n;
        mibs_mean /= n;
        for (const auto& r : runs) {
            iops_sd += (r.iops - iops_mean) * (r.iops - iops_mean);
            mibs_sd += (r.mibs - mibs_mean) * (r.mibs - mibs_mean);
        }
        iops_sd = std::sqrt(iops_sd / (n - 1));
        mibs_sd = std::sqrt(mibs_sd / (n - 1));
        mibs_ci95 = 1.96 * mibs_sd / std::sqrt(n);
    }
    o << ",\"aggregate\":{";
    o << "\"runs\":" << runs.size();
    o << ",\"elapsed_sec\":" << std::setprecision(6) << elapsed << std::setprecision(3);
    o << ",\"ops_total\":" << ops;
    o << ",\"bytes_total\":" << bytes;
    o << ",\"errors_total\":" << errs;
    o << ",\"iops\":{\"mean\":" << iops << ",\"stdev\":" << iops_sd << "}";
    o << ",\"mibs\":{\"mean\":" << mibs << ",\"sd\":" << mibs_sd
      << ",\"ci95\":" << mibs_ci95 << "}";
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
        ("file", bpo::value<std::string>(),
            "existing file to benchmark (required)")
        ("workload", bpo::value<std::string>()->default_value("stream_read"),
            "stream_read|stream_write")
        ("bs", bpo::value<std::string>()->default_value("128k"),
            "stream buffer size (e.g. 128k, 1M)")
        ("qd", bpo::value<unsigned>()->default_value(8),
            "stream concurrency = in-flight depth (read: read_ahead = qd-1, write: write_behind = qd)")
        ("size", bpo::value<std::string>(),
            "dataset bytes per pass (default: whole file)")
        ("seconds", bpo::value<unsigned>()->default_value(10),
            "measured duration per run")
        ("warmup-sec", bpo::value<unsigned>()->default_value(2),
            "warmup before each measured phase")
        ("runs", bpo::value<unsigned>()->default_value(1), "repetitions")
        ("seed", bpo::value<uint64_t>()->default_value(20261113), "PRNG seed")
        ("readonly", bpo::bool_switch(), "assert read-only operation")
        ("no-register-file", bpo::bool_switch(),
            "accepted no-op: seastar has no registered-file API (JSON records registered_files:false)")
        ("buffered", bpo::bool_switch(),
            "rejected: seastar file I/O on this path is always O_DIRECT/DMA")
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
        cfg.no_register_file = vm["no-register-file"].as<bool>();
        if (vm.count("file")) cfg.file = vm["file"].as<std::string>();
        if (vm.count("json")) cfg.json_path = vm["json"].as<std::string>();
        if (vm.count("label")) cfg.label = vm["label"].as<std::string>();
        if (vm.count("size")) cfg.dataset = parse_size(vm["size"].as<std::string>());

        // --buffered is intentionally unsupported: this runner exercises seastar's
        // DMA/O_DIRECT file path (open_file_dma + fstream always issue O_DIRECT on
        // this filesystem), so a "buffered" switch would silently lie. The JUring
        // runner keeps a disclosed buffered exploratory row instead.
        if (vm["buffered"].as<bool>()) {
            throw std::runtime_error(
                "--buffered is not supported by seastar_stream_bench: seastar file I/O "
                "in this benchmark path is always O_DIRECT/DMA (open_file_dma + fstream), "
                "so the JSON always records direct_io:true. Use the JUring runner for a "
                "disclosed buffered exploratory row.");
        }
        if (cfg.workload != "stream_read" && cfg.workload != "stream_write") {
            throw std::runtime_error(
                "--workload must be stream_read or stream_write (got '" + cfg.workload + "')");
        }
        if (cfg.file.empty()) {
            throw std::runtime_error("--file is required");
        }
        if (cfg.is_write() && cfg.readonly) {
            throw std::runtime_error("stream_write requested with --readonly");
        }
        if (cfg.seconds < 1) {
            throw std::runtime_error("--seconds must be >= 1");
        }
        if (cfg.runs < 1) {
            throw std::runtime_error("--runs must be >= 1");
        }
        if (cfg.bs < 512 || (cfg.bs & (cfg.bs - 1)) != 0) {
            throw std::runtime_error("--bs must be a power of two >= 512");
        }
        if (cfg.bs > (16ULL << 20)) {
            throw std::runtime_error("--bs must be <= 16 MiB (fstream caps request size)");
        }
        if (cfg.qd < 1 || cfg.qd > 1024) {
            throw std::runtime_error("--qd must be in [1, 1024]");
        }
        if (cfg.is_read() && cfg.qd < 2) {
            throw std::runtime_error(
                "stream_read requires --qd >= 2 (seastar fstream needs read_ahead >= 1 "
                "to have more than one request in flight; the runner uses read_ahead = qd-1 "
                "with adaptive adjustment)");
        }
        if (seastar::this_smp_shard_count() != 1) {
            throw std::runtime_error(
                "run with --smp 1 for now (the runner measures one shard)");
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

        // Shared adaptive read-ahead history: one per process/cell, reused by
        // every pass of every phase (warmup + measured), so the measured window
        // does not re-pay seastar's slow start.
        cfg.history = seastar::make_lw_shared<seastar::file_input_stream_history>();

        // Stat the file once to resolve the dataset; passes reopen per pass.
        uint64_t file_size = 0;
        {
            auto f = co_await seastar::open_file_dma(
                cfg.file.c_str(),
                cfg.is_write() ? (seastar::open_flags::rw | seastar::open_flags::create)
                               : seastar::open_flags::ro);
            file_size = co_await f.size();
            std::cerr << "seastar_stream_bench: file=" << cfg.file
                      << " size=" << file_size
                      << " memory_dma_alignment=" << f.memory_dma_alignment();
            if (cfg.is_write()) {
                std::cerr << " disk_write_max_length=" << f.disk_write_max_length()
                          << " write_behind=" << cfg.qd;
            } else {
                std::cerr << " disk_read_max_length=" << f.disk_read_max_length()
                          << " read_ahead=" << (cfg.qd - 1)
                          << " (adaptive, effective depth ~qd=" << cfg.qd << ")";
            }
            std::cerr << "\n";
            co_await f.close();
        }

        uint64_t dataset_size = 0;
        if (cfg.dataset != 0) {
            dataset_size = cfg.dataset;
            if (file_size != 0) {
                // Benchmark an existing file: never read past its end and never
                // grow the write target beyond its current size.
                dataset_size = std::min(dataset_size, file_size);
            }
        } else {
            dataset_size = file_size;   // read and write default
        }
        if (dataset_size == 0) {
            throw std::runtime_error(
                "file is empty: pass --size for stream_write (or use a non-empty file)");
        }
        if (dataset_size < cfg.bs) {
            throw std::runtime_error(
                "dataset (" + std::to_string(dataset_size) +
                " bytes) is smaller than --bs (" + std::to_string(cfg.bs) + " bytes)");
        }
        if (cfg.is_write() && (dataset_size % cfg.bs) != 0) {
            // fstream pads an unaligned tail and truncate()s the file back to the
            // logical position, which would resize the existing benchmark file.
            // Require an exact multiple instead (datasets in the campaign are).
            throw std::runtime_error(
                "stream_write requires --size to be a multiple of --bs: fstream pads "
                "an unaligned tail and truncate()s the file to the logical position, "
                "which would resize the existing file");
        }

        std::cerr << "seastar_stream_bench: workload=" << cfg.workload
                  << " dataset=" << dataset_size
                  << " bs=" << cfg.bs
                  << " qd=" << cfg.qd
                  << " seconds=" << cfg.seconds
                  << " warmup=" << cfg.warmup_sec
                  << " runs=" << cfg.runs
                  << " readonly=" << (cfg.is_write() ? "false" : "true")
                  << " seed=" << cfg.seed << "\n";

        std::vector<run_result> runs;
        for (unsigned r = 0; r < cfg.runs; ++r) {
            if (cfg.warmup_sec > 0) {
                auto warm = co_await run_phase(cfg, dataset_size, cfg.warmup_sec, false);
                std::cerr << "seastar_stream_bench: warmup run " << (r + 1)
                          << ": passes=" << warm.passes
                          << " ops=" << warm.ops
                          << " bytes=" << warm.bytes
                          << " errors=" << warm.errors
                          << " elapsed=" << std::setprecision(3) << warm.elapsed_s
                          << "s\n";
            }
            phase_result measured =
                co_await run_phase(cfg, dataset_size, cfg.seconds, true);
            run_result rr;
            rr.measured = std::move(measured);
            rr.iops = rr.measured.elapsed_s > 0
                ? static_cast<double>(rr.measured.ops) / rr.measured.elapsed_s : 0.0;
            rr.mibs = rr.measured.elapsed_s > 0
                ? static_cast<double>(rr.measured.bytes) / (1024.0 * 1024.0)
                      / rr.measured.elapsed_s : 0.0;
            std::cerr << "seastar_stream_bench: measured run " << (r + 1)
                      << ": passes=" << rr.measured.passes
                      << " ops=" << rr.measured.ops
                      << " bytes=" << rr.measured.bytes
                      << " errors=" << rr.measured.errors
                      << " elapsed=" << std::setprecision(3) << rr.measured.elapsed_s
                      << "s iops=" << rr.iops << " mibs=" << rr.mibs << "\n";
            runs.push_back(std::move(rr));
        }

        uint64_t errs = 0;
        for (const auto& r : runs) {
            errs += r.measured.errors;
        }
        std::string status = "ok";
        std::string error;
        if (errs > 0) {
            status = "error";
            error = std::to_string(errs) +
                    " chunk-level I/O error(s) during measurement (see stderr)";
        }

        std::string json = render_json(cfg, m, file_size, dataset_size, runs,
                                       status, error);
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

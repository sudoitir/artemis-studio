#!/usr/bin/env python3
"""Summarise a load run: Gatling's statistics, Studio's own metrics and the published figures.

usage: load-summary.py --profile NAME --version V --gatling DIR --scrape DIR --counts FILE --sizing FILE --out DIR

Writes DIR/summary.json and DIR/summary.md and prints the markdown. Exits 1 when a figure is worse than the target
docs/sizing.md publishes for it, after printing REGRESSION for each. Targets are only checked for the `reference`
profile: a smaller profile measures the load test, not the product.
"""
import argparse
import glob
import json
import os
import re
import sys

ap = argparse.ArgumentParser()
for name in ("profile", "version", "gatling", "scrape", "counts", "sizing", "out"):
    ap.add_argument("--" + name, required=True)
args = ap.parse_args()

# Prometheus series worth reporting, summed over their labels per replica and scrape.
WANTED = [
    ("heap_used_bytes", re.compile(r'^jvm_memory_used_bytes\{[^}]*area="heap"')),
    ("hikari_active", re.compile(r"^hikaricp_connections_active\b")),
    ("hikari_pending", re.compile(r"^hikaricp_connections_pending\b")),
    ("hikari_acquire_seconds_max", re.compile(r"^hikaricp_connections_acquire_seconds_max\b")),
    ("cpu_usage", re.compile(r"^process_cpu_usage\b")),
    ("sse_dropped", re.compile(r"^studio_sse_dropped")),
    ("stream_clients", re.compile(r"^studio_stream_clients")),
    ("job_lag_seconds_max", re.compile(r"^studio_job_lag_seconds(_max)?\b")),
    ("job_overruns", re.compile(r"^studio_job_overruns")),
    ("job_timeouts", re.compile(r"^studio_job_timeouts")),
    ("broker_calls", re.compile(r"^studio_broker_calls_total\b")),
    ("broker_requests", re.compile(r"^studio_broker_requests_seconds_count\b")),
    ("permit_timeouts", re.compile(r"^studio_broker_permit_timeouts")),
]
LINE = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*(?:\{[^}]*\})?)\s+([-+0-9.eE]+|NaN|[+-]Inf)\s*$")


def scrape(path):
    out = {}
    with open(path) as f:
        for line in f:
            m = LINE.match(line)
            if not m:
                continue
            for key, pattern in WANTED:
                if pattern.match(m.group(1)):
                    try:
                        value = float(m.group(2))
                    except ValueError:
                        continue
                    if value == value:  # not NaN
                        # Max-style series do not add up across labels; counters and gauges do.
                        out[key] = max(out.get(key, 0.0), value) if key.endswith("_max") else out.get(key, 0.0) + value
    return out


def replica_series(prefix):
    """{replica: [(order, metrics)]} for the scrape files `prefix-<replica>.prom` or `sample-<n>-<replica>.prom`."""
    series = {}
    for path in sorted(glob.glob(os.path.join(args.scrape, prefix))):
        base = os.path.basename(path)[: -len(".prom")]
        replica = base.rsplit("-", 2)[-2] + "-" + base.rsplit("-", 1)[-1] if base.count("-") >= 2 else base
        series.setdefault(replica, []).append((base, scrape(path)))
    return series


# --- Gatling ---
# Gatling 3.16 writes its statistics only into the HTML report: one table row per request, columns 2 to 14 being
# total, ok, ko, ko %, requests/s, min, p50, p75, p95, p99, max, mean and standard deviation.
runs = sorted(glob.glob(os.path.join(args.gatling, "*", "index.html")), key=os.path.getmtime)
requests = {}
if runs:
    html = open(runs[-1], encoding="utf-8").read()
    for row in re.findall(r'<tr id="req_[^"]*"[^>]*>(.*?)</tr>', html, re.S):
        name = re.search(r'class="ellipsed-name">([^<]*)<', row).group(1)
        cells = {int(c): v for c, v in re.findall(r'class="value[^"]*col-(\d+)">([^<]*)<', row)}

        def num(col):
            try:
                return float(cells[col])
            except (KeyError, ValueError):
                return 0.0  # "-" when a request has no sample

        requests[name] = {
            "count": int(num(2)),
            "failed": int(num(4)),
            "mean_ms": round(num(13)),
            "p50_ms": round(num(8)),
            "p95_ms": round(num(10)),
            "p99_ms": round(num(11)),
            "max_ms": round(num(12)),
        }


def family(prefix):
    rows = [r for n, r in requests.items() if n.startswith(prefix)]
    if not rows:
        return None
    count = sum(r["count"] for r in rows)
    failed = sum(r["failed"] for r in rows)
    return {
        "requests": count,
        "failed": failed,
        "failed_percent": round(100.0 * failed / count, 3) if count else 0.0,
        "p95_ms": max(r["p95_ms"] for r in rows),
        "p99_ms": max(r["p99_ms"] for r in rows),
    }


families = {name: family(prefix) for name, prefix in [("ui", "ui."), ("stream", "stream."), ("export", "heavy.audit."), ("sql", "heavy.sql."), ("auth", "auth.")]}

counts = json.load(open(args.counts)) if os.path.exists(args.counts) else {}

# --- Studio's metrics: before, after, and the peak over the run's samples, per replica ---
before, after, samples = replica_series("before-*.prom"), replica_series("after-*.prom"), replica_series("sample-*.prom")
peaks, growth = {}, {}
for replica, rows in samples.items():
    for _, m in rows:
        for k, v in m.items():
            peaks[k] = max(peaks.get(k, 0.0), v)
    heap = [m.get("heap_used_bytes", 0.0) for _, m in rows]
    if len(heap) >= 6:
        growth[replica] = (sum(heap[-3:]) - sum(heap[:3])) / 3 / 1048576
delta = {}
for replica in after:
    b = dict(before.get(replica, [("", {})])[0][1]) if replica in before else {}
    a = after[replica][0][1]
    for k in set(a) | set(b):
        delta[k] = delta.get(k, 0.0) + a.get(k, 0.0) - b.get(k, 0.0)

figures = {}
if families["ui"]:
    figures["ui_read_p95_ms"] = families["ui"]["p95_ms"]
    figures["ui_read_failed_percent"] = families["ui"]["failed_percent"]
if families["stream"]:
    figures["stream_failed_percent"] = families["stream"]["failed_percent"]
if families["sql"]:
    figures["sql_p95_ms"] = families["sql"]["p95_ms"]
if families["export"]:
    figures["export_p95_ms"] = families["export"]["p95_ms"]
if "hikari_pending" in peaks:
    figures["pool_pending_max"] = peaks["hikari_pending"]
if growth:
    figures["heap_growth_mb"] = round(max(growth.values()), 1)
if "job_lag_seconds_max" in peaks:
    figures["job_lag_max_s"] = peaks["job_lag_seconds_max"]
if "heap_used_bytes" in peaks:
    figures["heap_peak_mb"] = round(peaks["heap_used_bytes"] / 1048576, 1)
if "cpu_usage" in peaks:
    figures["studio_cpu_peak_percent"] = round(peaks["cpu_usage"] * 100, 1)
if counts.get("stalledOpened"):
    figures["stalled_closed_percent"] = round(100.0 * counts.get("stalledClosedByServer", 0) / counts["stalledOpened"], 1)

# --- the published targets ---
targets = {}
if os.path.exists(args.sizing):
    inside = False
    for line in open(args.sizing):
        if "<!-- targets:begin -->" in line:
            inside = True
        elif "<!-- targets:end -->" in line:
            inside = False
        elif inside:
            m = re.match(r"^\|\s*`([a-z0-9_]+)`\s*\|\s*(<=|>=)\s*\|\s*([0-9.]+)\s*\|", line)
            if m:
                targets[m.group(1)] = (m.group(2), float(m.group(3)))

checked = args.profile == "reference"
regressions = []
if checked:
    for key, (op, limit) in targets.items():
        if key in figures and ((op == "<=" and figures[key] > limit) or (op == ">=" and figures[key] < limit)):
            regressions.append({"figure": key, "value": figures[key], "target": f"{op} {limit:g}"})

summary = {
    "profile": args.profile,
    "version": args.version,
    "requests": requests,
    "families": families,
    "simulation": counts,
    "figures": figures,
    "studio_metrics": {"delta": delta, "peak": peaks, "heap_growth_mb_by_replica": growth},
    "targets": {k: f"{op} {v:g}" for k, (op, v) in targets.items()},
    "targets_checked": checked,
    "regressions": regressions,
}
os.makedirs(args.out, exist_ok=True)
json.dump(summary, open(os.path.join(args.out, "summary.json"), "w"), indent=2, sort_keys=True)

md = [f"# Load run: profile `{args.profile}`, Studio `{args.version}`", "", "## Requests", "",
      "| Request | Count | Failed | p50 ms | p95 ms | p99 ms | Max ms |", "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
for name, r in sorted(requests.items()):
    md.append(f"| `{name}` | {r['count']} | {r['failed']} | {r['p50_ms']} | {r['p95_ms']} | {r['p99_ms']} | {r['max_ms']} |")
md += ["", "## Simulation counts", "", "```json", json.dumps(counts, indent=1), "```", "", "## Figures", "",
       "| Figure | Value | Target |", "| --- | ---: | --- |"]
for key, value in sorted(figures.items()):
    t = summary["targets"].get(key, "none published")
    md.append(f"| `{key}` | {value:g} | {t if checked or key not in targets else t + ' (not checked: profile ' + args.profile + ')'} |")
md += ["", "## Studio metrics over the run (sum of replicas)", "", "| Series | Change | Peak |", "| --- | ---: | ---: |"]
for key in sorted(set(delta) | set(peaks)):
    md.append(f"| `{key}` | {delta.get(key, 0.0):g} | {peaks.get(key, 0.0):g} |")
md.append("")
if not targets:
    md.append("No published targets yet: docs/sizing.md has no `targets` table.")
elif not checked:
    md.append(f"Targets are checked for the `reference` profile only; this was `{args.profile}`.")
for r in regressions:
    md.append(f"REGRESSION: {r['figure']} = {r['value']:g}, target {r['target']}")
text = "\n".join(md) + "\n"
open(os.path.join(args.out, "summary.md"), "w").write(text)
print(text)
sys.exit(1 if regressions else 0)

#!/usr/bin/env python3
"""측정 결과 디렉터리(loadtest/results/<RUN_ID>)의 파일만 읽어 레벨 요약과 Outbox 주기 분석을 출력한다.

DB에 접속하지 않으므로, 환경을 정리한 뒤에도 저장된 결과로 다시 실행할 수 있다.
사용법: [CYCLE_GAP_S=0.05] python3 analyze.py loadtest/results/<RUN_ID>

입력 파일: load-start-utc.txt, load-duration-s.txt(없으면 120초), k6-summary.json, server-e2e-result.txt,
outbox-throughput-<service>.txt, outbox-watch.csv, consumer-lag.csv, jvm-metrics.csv, docker-stats.csv,
outbox-rows-<service>.csv, db-stats-before.txt / db-stats-after.txt(있을 때)
CSV의 HH:MM:SS 시각은 측정한 머신의 로컬 시간대로 해석한다.

주기 분석에서 각 서비스의 첫 발행 주기에 실린 이벤트는 비교할 직전 주기가 없어 분류에서 제외한다.
그래서 이벤트 종류별 n이 주문 수보다 조금 작을 수 있다. 발행 대기 평균은 outbox-delay-result.txt(SQL)가 기준이다.

order-api·product-api 주기는 sent_at 간격이 CYCLE_GAP_S(기본 0.2초) 이하인 이벤트를 한 주기로 묶어 추정한다.
Publisher 실행 간격(fixed-delay)이 이 값보다 짧으면 여러 주기가 하나로 합쳐지므로, 간격의 절반 정도로 낮춰 실행한다.
"""
import csv, json, sys, statistics as st, datetime as dt, os
from collections import defaultdict

D = sys.argv[1].rstrip("/")
start = dt.datetime.fromisoformat(open(f"{D}/load-start-utc.txt").read().strip().rstrip('Z')).replace(tzinfo=dt.timezone.utc)
T0 = start.timestamp()
DURATION_S = float(open(f"{D}/load-duration-s.txt").read()) if os.path.exists(f"{D}/load-duration-s.txt") else 120.0
LOAD_END = T0 + DURATION_S
LOCAL_TZ = dt.datetime.now().astimezone().tzinfo
CYCLE_GAP_S = float(os.environ.get("CYCLE_GAP_S", "0.2"))
if CYCLE_GAP_S <= 0:
    sys.exit("CYCLE_GAP_S must be > 0")
CORES = os.cpu_count() or 1  # actuator CPU 비율을 코어 수로 환산한다. 측정한 머신에서 실행해야 맞다


def hms_to_epoch(hms):
    d = start.astimezone(LOCAL_TZ).date()
    t = dt.datetime.combine(d, dt.time.fromisoformat(hms), LOCAL_TZ).timestamp()
    if t < T0 - 3600:
        t += 86400
    return t


def slope(points):
    if len(points) < 3:
        return float('nan')
    xs, ys = zip(*points)
    mx, my = st.mean(xs), st.mean(ys)
    den = sum((x - mx) ** 2 for x in xs)
    return sum((x - mx) * (y - my) for x, y in points) / den if den else float('nan')


def pct(a, p):
    a = sorted(a)
    if not a:
        return float('nan')
    k = (len(a) - 1) * p
    f = int(k)
    c = min(f + 1, len(a) - 1)
    return a[f] + (a[c] - a[f]) * (k - f)


out = []
P = out.append

# ---------- k6
k = json.load(open(f"{D}/k6-summary.json"))["metrics"]
def kc(name):
    m = k.get(name, {})
    return int(m.get("count", 0))
e2e = k.get("order_e2e_duration", {})
hd = k.get("http_req_duration", {})
P("[k6]")
P(f"  accepted={kc('orders_accepted')} succeeded={kc('orders_succeeded')} failed={kc('orders_failed')} "
  f"timeout={kc('orders_timeout')} rejected={kc('orders_rejected')} poll_errors={kc('status_poll_errors')} "
  f"dropped_iterations={kc('dropped_iterations')} http_reqs={kc('http_reqs')} "
  f"http_req_failed={k.get('http_req_failed', {}).get('value')} vus_max={k.get('vus_max', {}).get('max')}")
P(f"  e2e p50={e2e.get('p(50)', 0):.0f} p95={e2e.get('p(95)', 0):.0f} p99={e2e.get('p(99)', 0):.0f} "
  f"min={e2e.get('min', 0):.0f} avg={e2e.get('avg', 0):.0f} max={e2e.get('max', 0):.0f} ms")
P(f"  http_req_duration avg={hd.get('avg', 0):.1f} p95={hd.get('p(95)', 0):.1f} p99={hd.get('p(99)', 0):.1f} max={hd.get('max', 0):.1f} ms")

# ---------- server e2e
P("[server e2e]")
for line in open(f"{D}/server-e2e-result.txt"):
    if line.strip():
        P("  " + line.rstrip())

# ---------- outbox throughput
P(f"[outbox throughput, events/s]  steady=부하 시작+10s~+{DURATION_S:.0f}s, all=발행이 있던 전체 초")
for s in ("order", "inventory", "product"):
    rows = []
    for line in open(f"{D}/outbox-throughput-{s}.txt"):
        f = line.strip().split("|")
        if len(f) == 4 and f[0][:2] == "20":
            ts = dt.datetime.fromisoformat(f[0].replace("+00", "+00:00")).timestamp()
            rows.append((ts, int(f[1])))
    if not rows:
        P(f"  {s}: no rows"); continue
    steady = [n for ts, n in rows if T0 + 10 <= ts < LOAD_END]
    first, last = rows[0][0], rows[-1][0]
    total = sum(n for _, n in rows)
    P(f"  {s}: total={total} all_avg={total / (last - first + 1):.1f} steady_avg={st.mean(steady) if steady else 0:.1f} "
      f"peak={max(n for _, n in rows)} last_send_offset={last - T0:.0f}s")

# ---------- outbox backlog
P(f"[outbox backlog (outbox-watch.csv)]  slope=부하 구간(0~{DURATION_S:.0f}s) 선형회귀 기울기, 건/초")
by = defaultdict(list)
with open(f"{D}/outbox-watch.csv") as fh:
    for r in csv.DictReader(fh):
        try:
            t = hms_to_epoch(r["time"])
            by[r["service"]].append((t, int(r["pending"] or 0), int(r["processing"] or 0), int(r["failed"] or 0), int(r["consumer_lag"] or 0)))
        except (ValueError, TypeError):
            pass
for svc, rows in by.items():
    load = [x for x in rows if T0 <= x[0] <= LOAD_END]
    gaps = [b[0] - a[0] for a, b in zip(rows, rows[1:])]
    P(f"  {svc}: samples={len(rows)} interval≈{st.mean(gaps) if gaps else 0:.1f}s max_pending={max(x[1] for x in rows)} "
      f"max_processing={max(x[2] for x in rows)} max_unsent={max(x[1] + x[2] for x in rows)} "
      f"pending_slope={slope([(x[0] - T0, x[1] + x[2]) for x in load]):.3f}/s "
      f"unsent_at_load_end≈{(load[-1][1] + load[-1][2]) if load else 'NA'} final_failed={rows[-1][3]} "
      f"max_group_lag={max(x[4] for x in rows)} group_lag_slope={slope([(x[0] - T0, x[4]) for x in load]):.3f}/s")

# ---------- consumer lag per topic
P("[consumer lag per topic (consumer-lag.csv)]")
lag = defaultdict(list)
with open(f"{D}/consumer-lag.csv") as fh:
    for r in csv.DictReader(fh):
        try:
            lag[(r["group"], r["topic"])].append((hms_to_epoch(r["time"]), int(r["lag"])))
        except (ValueError, TypeError):
            pass
samples = len({t for v in lag.values() for t, _ in v})
P(f"  samples={samples}")
for (g, t), v in sorted(lag.items(), key=lambda kv: -max(x[1] for x in kv[1])):
    load = [(x - T0, y) for x, y in v if T0 <= x <= LOAD_END]
    mx = max(y for _, y in v)
    if mx == 0 and not t.endswith("-event"):
        continue
    P(f"  {g:14s} {t:34s} max={mx:5d} slope={slope(load):7.3f}/s at_load_end≈{load[-1][1] if load else 'NA'}")

# ---------- JVM metrics
P(f"[JVM / Hikari (actuator, 부하 구간 0~{DURATION_S:.0f}s 기준, CPU는 {CORES}코어 전체 대비 %)]")
jm = defaultdict(list)
with open(f"{D}/jvm-metrics.csv") as fh:
    for r in csv.DictReader(fh):
        jm[r["service"]].append(r)
for svc, rows in jm.items():
    load = [r for r in rows if T0 <= float(r["time"]) <= LOAD_END + 2]
    allr = rows
    if not load:
        continue
    f = lambda rs, key: [float(r[key]) for r in rs]
    gcs = f(load, "gc_pause_sum_s"); gcc = f(load, "gc_pause_count")
    pc = f(load, "process_cpu"); sc = f(load, "system_cpu")
    P(f"  {svc}: proc_cpu avg={st.mean(pc) * 100:.1f}% max={max(pc) * 100:.1f}% (≈{max(pc) * CORES:.2f} cores) "
      f"sys_cpu avg={st.mean(sc) * 100:.1f}% max={max(sc) * 100:.1f}% heap_used max={max(f(allr, 'heap_used_mb')):.0f}MB/{load[0]['heap_max_mb']}MB "
      f"gc_pause in_load={gcs[-1] - gcs[0]:.3f}s ({gcc[-1] - gcc[0]:.0f} pauses) "
      f"hikari active max={max(f(load, 'hikari_active')):.0f}/{load[0]['hikari_max']} avg={st.mean(f(load, 'hikari_active')):.2f} "
      f"pending max={max(f(allr, 'hikari_pending')):.0f} acquire_max={max(f(allr, 'hikari_acquire_max_ms')):.1f}ms "
      f"timeouts={max(f(allr, 'hikari_timeouts')):.0f} threads max={max(f(allr, 'threads_live')):.0f} samples={len(load)}")

# ---------- docker stats
P("[docker stats / ps (부하 구간 최대)]")
ds = defaultdict(list)
if os.path.exists(f"{D}/docker-stats.csv"):
    with open(f"{D}/docker-stats.csv") as fh:
        for r in csv.reader(fh):
            if r[0] == "time":
                continue
            try:
                t = hms_to_epoch(r[0])
            except ValueError:
                continue
            if T0 <= t <= LOAD_END + 5:
                ds[(r[1], r[2])].append((float(r[3].rstrip('%')), r[4].split(" /")[0]))
for (kind, name), v in sorted(ds.items()):
    m = max(v, key=lambda x: x[0])
    P(f"  {kind:9s} {name:32s} cpu max={m[0]:.1f}% avg={st.mean(x[0] for x in v):.1f}% mem@max={m[1]} samples={len(v)}")


# ---------- Outbox cycle analysis
def load_rows(s):
    rows = []
    with open(f"{D}/outbox-rows-{s}.csv") as fh:
        for r in csv.reader(fh):
            if not r[4]:
                continue
            rows.append(dict(id=int(r[0]), type=r[1], agg=r[2], c=float(r[3]), s=float(r[4]),
                             claim=float(r[5]) if len(r) > 5 and r[5] else None))
    return rows


def cycles_from_sent(rows, gap=CYCLE_GAP_S):
    ss = sorted(r["s"] for r in rows)
    cyc = []
    for x in ss:
        if cyc and x - cyc[-1][1] <= gap:
            cyc[-1][1] = x; cyc[-1][2] += 1
        else:
            cyc.append([x, x, 1])
    return cyc  # [start(first sent), end(last sent), size]


def cycles_from_claim(rows):
    g = defaultdict(list)
    for r in rows:
        g[r["claim"]].append(r["s"])
    return sorted([[c, max(v), len(v)] for c, v in g.items()])


import bisect
P(f"[Outbox 주기 분석]  cycle start: order/product=배치 첫 sent_at(추정, select 시간만큼 늦음, 주기 구분 sent_at 간격>{CYCLE_GAP_S * 1000:.0f}ms), inventory=claimed_at(실측)")
P("  분류: during=직전 발행 주기가 진행 중일 때 생성(그 주기에 못 실림) / idle=주기 사이 대기 구간에 생성 / skipped=직전 주기 시작 전에 생성됐는데도 직전 주기에 안 실림(적체·배치 한도·커밋 지연)")
all_rows = {}
for s in ("order", "inventory", "product"):
    rows = load_rows(s)
    all_rows[s] = rows
    if not rows:
        continue
    cyc = cycles_from_claim(rows) if s == "inventory" else cycles_from_sent(rows)
    starts = [c[0] for c in cyc]
    sizes = [c[2] for c in cyc]
    durs = [c[1] - c[0] for c in cyc]
    gaps = [b[0] - a[1] for a, b in zip(cyc, cyc[1:]) if b[0] - a[1] < 2.0]
    P(f"  {s}-api: cycles={len(cyc)} batch size median={st.median(sizes)} p95={pct(sizes, .95):.0f} max={max(sizes)} "
      f"(=50 hits {sum(1 for x in sizes if x >= 50)}) send span(first→last sent) median={st.median(durs) * 1000:.0f}ms p95={pct(durs, .95) * 1000:.0f}ms max={max(durs) * 1000:.0f}ms "
      f"gap(end→next start) median={st.median(gaps) * 1000 if gaps else 0:.0f}ms")
    stats = defaultdict(lambda: defaultdict(list))
    for r in rows:
        ki = bisect.bisect_right(starts, r["s"] + 1e-6) - 1  # 이 이벤트를 실은 주기
        if s == "inventory":
            ki = starts.index(r["claim"])
        if ki <= 0:
            continue
        ps, pe, _ = cyc[ki - 1]
        cat = "during" if ps <= r["c"] <= pe else ("idle" if r["c"] > pe else "skipped")
        st_ = stats[r["type"]]
        st_[cat].append(r["s"] - r["c"])
        st_["all"].append(r["s"] - r["c"])
        if cat == "during":
            st_["pos"].append(r["c"] - ps)
    for t, v in sorted(stats.items()):
        n = len(v["all"])
        def cs(cat):
            a = v[cat]
            return f"{cat}={len(a) / n * 100:4.0f}% (wait avg {st.mean(a) * 1000:4.0f}ms)" if a else f"{cat}=   0%"
        P(f"    {t:34s} n={n:5d} wait avg={st.mean(v['all']) * 1000:4.0f}ms p95={pct(v['all'], .95) * 1000:4.0f}ms | "
          f"{cs('during')} {cs('idle')} {cs('skipped')}")

# 인과 반응 시간: 부모 이벤트 발행(sent_at) → 자식 이벤트 생성(created_at), aggregate_id 일치
P("  [인과 반응 시간] 부모 sent_at → 자식 created_at (aggregate_id로 연결, 같은 JVM 시계 또는 같은 호스트 시계)")
idx = {}
for s, rows in all_rows.items():
    for r in rows:
        idx[(s, r["type"], r["agg"])] = r
pairs = [
    (("order", "order-create-event"), ("inventory", "inventory-reserved-event")),
    (("inventory", "inventory-reserved-event"), ("order", "payment-request-event")),
    (("order", "payment-request-event"), ("order", "payment-succeeded-event")),
    (("order", "payment-succeeded-event"), ("order", "order-confirm-event")),
    (("order", "order-confirm-event"), ("inventory", "inventory-confirm-event")),
]
for (ps_, pt), (cs_, ct) in pairs:
    d = []
    for (s, t, a), r in idx.items():
        if s == cs_ and t == ct:
            p = idx.get((ps_, pt, a))
            if p:
                d.append(r["c"] - p["s"])
    if d:
        P(f"    {pt:26s} → {ct:26s} matched={len(d):5d} reaction p50={pct(d, .5) * 1000:5.0f}ms p95={pct(d, .95) * 1000:5.0f}ms")
    else:
        P(f"    {pt:26s} → {ct:26s} matched=0 (aggregate_id 불일치, 연결 불가)")

# ---------- DB 통계 (측정 전후 차이)
if os.path.exists(f"{D}/db-stats-before.txt") and os.path.exists(f"{D}/db-stats-after.txt"):
    def read_stats(path):
        r = {}
        for line in open(path):
            f = line.strip().split("|")
            if len(f) == 7:
                r[(f[0], f[1], f[2])] = [int(x) for x in f[3:]]
        return r
    b, a = read_stats(f"{D}/db-stats-before.txt"), read_stats(f"{D}/db-stats-after.txt")
    span = os.path.getmtime(f"{D}/db-stats-after.txt") - T0
    P(f"[DB 통계 차이 (측정 전 → 측정 후, 부하 시작부터 약 {span:.0f}초)]  index: idx_scan, tup_read / database: xact_commit, rollback")
    for key in sorted(a):
        if key not in b:
            continue
        d = [x - y for x, y in zip(a[key], b[key])]
        svc, kind, name = key
        if kind == "table":
            P(f"  {svc:9s} table    {name:28s} seq_scan={d[0]} idx_scan={d[1]} ins={d[2]} upd={d[3]}")
        elif kind == "index":
            P(f"  {svc:9s} index    {name:28s} idx_scan={d[1]} ({d[1] / span:.1f}/s) tup_read={d[2]}")
        else:
            P(f"  {svc:9s} database {name:28s} xact_commit={d[1]} ({d[1] / span:.1f}/s) rollback={d[2]}")

print("\n".join(out))

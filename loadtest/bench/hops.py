#!/usr/bin/env python3
"""주문 하나의 서버 E2E를 8개 Outbox 대기와 9개 처리 구간으로 나눈다.
입력: <run>/orders-chain.csv(chain-export.sh), <run>/outbox-rows-{order,inventory,product}.csv(analyze.sh)
처리 구간 = 부모 이벤트 sent_at(또는 접수) → 자식 이벤트 created_at. Kafka 전달 + Consumer 대기 + 처리 + 커밋을 포함한다.
마지막 구간은 inventory-confirm sent_at → orders.updated_at(COMPLETED)이다.
사용법: python3 loadtest/bench/hops.py loadtest/results/<RUN_ID>
"""
import csv, sys, statistics as st

D = sys.argv[1].rstrip("/")


def pct(a, p):
    a = sorted(a)
    if not a:
        return float("nan")
    k = (len(a) - 1) * p
    f = int(k)
    return a[f] + (a[min(f + 1, len(a) - 1)] - a[f]) * (k - f)


rows = {}
for s in ("order", "inventory", "product"):
    with open(f"{D}/outbox-rows-{s}.csv") as fh:
        for r in csv.reader(fh):
            if not r[4]:
                continue
            rows[(r[1], r[2])] = (float(r[3]), float(r[4]))

CHAIN = [  # (이벤트, aggregate_id 키)
    ("productSnapshot-requested-event", None),
    ("productSnapshot-reply-event", "req"),
    ("order-create-event", "oid"),
    ("inventory-reserved-event", "oid"),
    ("payment-request-event", "oid"),
    ("payment-succeeded-event", "oid"),
    ("order-confirm-event", "oid"),
    ("inventory-confirm-event", "oid"),
]
OWNER = {"productSnapshot-reply-event": "product", "inventory-reserved-event": "inventory",
         "inventory-confirm-event": "inventory"}

seg = {}
order_names = []
missing = 0
n = 0
for r in csv.reader(open(f"{D}/orders-chain.csv")):
    idem, acc, oid, done, status, rq_c, rq_s, req = r
    if status != "COMPLETED" or not rq_s:
        missing += 1
        continue
    acc, done = float(acc), float(done)
    ev = [(float(rq_c), float(rq_s))]
    ok = True
    for name, key in CHAIN[1:]:
        v = rows.get((name, req if key == "req" else oid))
        if v is None:
            ok = False
            break
        ev.append(v)
    if not ok:
        missing += 1
        continue
    n += 1
    parts = [("proc", "접수 → requested 생성", ev[0][0] - acc)]
    for i, (name, _) in enumerate(CHAIN):
        parts.append(("wait", f"wait {name}", ev[i][1] - ev[i][0]))
        nxt = CHAIN[i + 1][0] if i + 1 < len(CHAIN) else "COMPLETED"
        end = ev[i + 1][0] if i + 1 < len(CHAIN) else done
        parts.append(("proc", f"{name.replace('-event', '')} → {nxt.replace('-event', '')}", end - ev[i][1]))
    parts.append(("e2e", "서버 E2E", done - acc))
    for kind, label, v in parts:
        if label not in seg:
            seg[label] = (kind, [])
            order_names.append(label)
        seg[label][1].append(v * 1000)

print(f"[주문 단위 구간 분해] 연결된 주문={n} 연결 실패={missing}")
print(f"  {'구간':58s} {'p50':>7s} {'p95':>7s} {'avg':>7s}  (ms)")
tw = tp = 0
for label in order_names:
    kind, v = seg[label]
    tag = {"wait": "[대기]", "proc": "[처리]", "e2e": "[합계]"}[kind]
    print(f"  {tag} {label:52s} {pct(v, .5):7.1f} {pct(v, .95):7.1f} {st.mean(v):7.1f}")
    if kind == "wait":
        tw += st.mean(v)
    elif kind == "proc":
        tp += st.mean(v)
e2e = st.mean(seg["서버 E2E"][1])
print(f"  평균 합: Outbox 대기={tw:.1f}ms ({tw / e2e * 100:.1f}%) 처리={tp:.1f}ms ({tp / e2e * 100:.1f}%) E2E 평균={e2e:.1f}ms")
by = {"order": 0.0, "inventory": 0.0, "product": 0.0}
for label in order_names:
    kind, v = seg[label]
    if kind == "wait":
        ev = label[5:]
        by[OWNER.get(ev, "order")] += st.mean(v)
print("  서비스별 Outbox 대기 평균 합: " + " ".join(f"{k}={v:.1f}ms" for k, v in by.items()))

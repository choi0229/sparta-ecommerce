import psycopg2
import requests
import json
import time

DB_CONFIG = {
    "host": "localhost",
    "port": 5434,
    "database": "productdb",
    "user": "postgres",
    "password": "postgres"
}

ES_URL = "http://localhost:9200"
INDEX = "products"
BATCH_SIZE = 1000

def sync():
    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()

    # 전체 건수 확인
    cur.execute("SELECT COUNT(*) FROM product")
    total = cur.fetchone()[0]
    print(f"총 {total:,}건 동기화 시작...")

    cur.execute("""
        SELECT id, name, brand_name, category_id, status, description, created_at
        FROM product
        ORDER BY id
    """)

    batch = []
    success = 0
    start = time.time()

    while True:
        rows = cur.fetchmany(BATCH_SIZE)
        if not rows:
            break

        for row in rows:
            id_, name, brand_name, category_id, status, description, created_at = row
            batch.append(json.dumps({"index": {"_id": str(id_)}}))
            batch.append(json.dumps({
                "id": id_,
                "name": name,
                "brandName": brand_name,
                "categoryId": category_id,
                "status": status,
                "description": description or "",
                "createdAt": created_at.isoformat() if created_at else None
            }))

        # bulk insert
        body = "\n".join(batch) + "\n"
        res = requests.post(
            f"{ES_URL}/{INDEX}/_bulk",
            headers={"Content-Type": "application/x-ndjson"},
            data=body.encode("utf-8")
        )

        result = res.json()
        if result.get("errors"):
            print(f"  ⚠️ 일부 오류 발생")

        success += len(batch) // 2
        batch = []

        elapsed = time.time() - start
        rps = success / elapsed if elapsed > 0 else 0
        print(f"  [{success:,}/{total:,}] {rps:.0f} docs/s")

    cur.close()
    conn.close()

    # 최종 확인
    res = requests.get(f"{ES_URL}/{INDEX}/_count")
    count = res.json().get("count", 0)
    elapsed = time.time() - start
    print(f"\n✅ 완료! ES 문서 수: {count:,}건 ({elapsed:.1f}초)")

if __name__ == "__main__":
    sync()
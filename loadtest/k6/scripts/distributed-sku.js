import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 분산 SKU 테스트
 * - CSV에 있는 SKU 풀에서 랜덤으로 2개 선택
 * - 락 경합을 낮추고 "순수 처리량"을 보는 용도
 *
 * 사용:
 *  k6 run -e BASE_URL=http://localhost:8080 -e SKU_CSV=../data/skus.csv distributed-sku.js
 */

export const options = {
    scenarios: {
        dist: {
            executor: 'ramping-arrival-rate',
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: 400,
            stages: [
                { duration: '30s', target: 20 },
                { duration: '2m',  target: 150 },
                { duration: '1m',  target: 250 },
                { duration: '30s', target: 0 },
            ],
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.02'],
        http_req_duration: ['p(95)<1200', 'p(99)<2500'],
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ORDER_PATH = __ENV.ORDER_PATH || '/orders';
const SKU_CSV = __ENV.SKU_CSV || '../data/skus.csv';
const ITEMS_PER_ORDER = Number(__ENV.ITEMS_PER_ORDER || 2);

function parseSkus(csvText) {
    // 매우 단순 CSV 파서: 첫 줄 header, 이후 sku 컬럼만 읽음
    // 형식: sku
    //       SKU-0001
    //       SKU-0002
    const lines = csvText.split('\n').map(l => l.trim()).filter(Boolean);
    if (lines.length <= 1) return [];
    const header = lines[0].split(',').map(s => s.trim());
    const skuIdx = header.indexOf('sku');
    if (skuIdx === -1) throw new Error('CSV must have "sku" header');
    return lines.slice(1).map(line => line.split(',')[skuIdx].trim()).filter(Boolean);
}

const skuPool = new SharedArray('skuPool', function () {
    const csv = open(SKU_CSV);
    const skus = parseSkus(csv);
    if (skus.length < 2) throw new Error(`Not enough SKUs in CSV: ${SKU_CSV}`);
    return skus;
});

function pickSku() {
    const idx = Math.floor(Math.random() * skuPool.length);
    return skuPool[idx];
}

export default function () {
    const idemKey = uuidv4();

    const items = [];
    const used = new Set();
    while (items.length < ITEMS_PER_ORDER) {
        const sku = pickSku();
        if (used.has(sku)) continue;
        used.add(sku);
        items.push({ sku, quantity: 1 });
    }

    const payload = JSON.stringify({
        userId: Number(__ENV.USER_ID || 1),
        items,
    });

    const res = http.post(`${BASE_URL}${ORDER_PATH}`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': idemKey,
        },
    });

    check(res, {
        'status is 2xx': (r) => r.status >= 200 && r.status < 300,
    });

    sleep(0.03);
}
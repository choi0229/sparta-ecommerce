import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 기본 주문 부하 테스트
 * - 2개 SKU 고정 (SKU-001, SKU-002)
 * - Idempotency-Key 매 요청마다 새로 생성
 */

export const options = {
    discardResponseBodies: true,
    noConnectionReuse: true,
    scenarios: {
        ramp: {
            executor: 'ramping-arrival-rate',
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: 300,
            stages: [
                { duration: '30s', target: 2 },   // warmup
                { duration: '1m',  target: 5 },
                { duration: '1m',  target: 7 },
                { duration: '30s', target: 0 },
            ],
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.02'],
        http_req_duration: ['p(95)<1500', 'p(99)<3000'], // request-reply 포함이므로 넉넉히
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
    const idemKey = uuidv4();

    const payload = JSON.stringify({
        userId: 1,
        items: [
            { sku: 'MBA-M3-08GB', quantity: 1 },
            { sku: 'MBA-M3-16GB', quantity: 1 },
        ],
    });

    const res = http.post(`${BASE_URL}/orders`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': idemKey,
        },
    });

    check(res, {
        'status is 2xx': (r) => r.status >= 200 && r.status < 300,
        'not 0 status': (r) => r.status !== 0,
    });

    sleep(0.05);
}
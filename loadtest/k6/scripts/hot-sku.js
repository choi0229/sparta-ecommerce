import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 핫 SKU 경합 테스트
 * - 모든 요청이 동일 SKU를 주문
 * - 재고 락 경합/oversell 방지/지연(p99) 확인용
 */

export const options = {
    scenarios: {
        hot: {
            executor: 'ramping-arrival-rate',
            timeUnit: '1s',
            preAllocatedVUs: 80,
            maxVUs: 500,
            stages: [
                { duration: '30s', target: 20 },
                { duration: '1m',  target: 100 },
                { duration: '2m',  target: 200 },
                { duration: '30s', target: 0 },
            ],
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.05'],
        http_req_duration: ['p(95)<2500', 'p(99)<5000'],
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ORDER_PATH = __ENV.ORDER_PATH || '/orders';

export default function () {
    const idemKey = uuidv4();
    const sku = __ENV.HOT_SKU || 'MBA-M3-08GB';
    const qty = Number(__ENV.QTY || 1);

    const payload = JSON.stringify({
        userId: Number(__ENV.USER_ID || 1),
        items: [{ sku, quantity: qty }],
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

    sleep(0.02);
}
import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
    discardResponseBodies: false, // 폴링 응답 읽어야 해서 false로 변경
    noConnectionReuse: false,
    scenarios: {
        ramp: {
            executor: 'ramping-arrival-rate',
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: 1000,
            stages: [
                { duration: '30s', target: 5 },
                { duration: '1m',  target: 10 },
                { duration: '1m',  target: 30 },
                { duration: '1m',  target: 50 },
                { duration: '30s', target: 0 },
            ],
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.02'],
        http_req_duration: ['p(95)<15000', 'p(99)<25000'], // 폴링 포함이라 여유있게
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083/api';

export default function () {
    const payload = JSON.stringify({
        userId: 1,
        items: [
            { sku: 'MBA-M5-08GB', quantity: 1 },
            { sku: 'MBA-M5-16GB', quantity: 1 },
        ],
    });

    // 1. 주문 접수
    const res = http.post(`${BASE_URL}/orders`, payload, {
        headers: { 'Content-Type': 'application/json' },
    });

    check(res, {
        'accepted 202': (r) => r.status === 200 || r.status === 202,
    });

    if (res.status !== 200 && res.status !== 202) return;

    const idemKey = res.json('data.idemKey');
    if (!idemKey) return;

    // 2. 폴링 (최대 3초, 100ms 간격 = 최대 50회)
    let orderStatus = 'PENDING';
    for (let i = 0; i < 100; i++) {
        sleep(0.3);
        const statusRes = http.get(`${BASE_URL}/orders/status/${idemKey}`);

        if (statusRes.status !== 200) break;

        orderStatus = statusRes.json('data.status');
        if (orderStatus !== 'PENDING') break;
    }

    check(orderStatus, {
        'order completed': (s) => s === 'COMPLETED',
    });

    sleep(0.05);
}
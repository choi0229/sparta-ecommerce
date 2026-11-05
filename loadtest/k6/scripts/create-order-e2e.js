// 주문 접수부터 최종 상태(SUCCEEDED/FAILED)까지의 E2E 부하 테스트.
// 실행 방법과 결과 해석은 loadtest/README.md를 참고한다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { randomBytes } from 'k6/crypto';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083/api';
const RUN_ID = __ENV.RUN_ID || `run${Date.now()}`;
const PROFILE = __ENV.PROFILE || 'ramp'; // ramp | constant
const RATE = Number(__ENV.RATE || 10); // constant 프로필의 목표 RPS (10, 20, 30, 50)
const DURATION = __ENV.DURATION || '2m'; // constant 프로필의 지속 시간
const SKUS = (__ENV.SKUS || 'LOADTEST-SKU-A,LOADTEST-SKU-B').split(',');
const QUANTITY = Number(__ENV.QUANTITY || 1);
const DUPLICATE_SEND = __ENV.DUPLICATE_SEND === 'true'; // 같은 키로 동시에 2건 전송

const POLL_INTERVAL_SECONDS = 1;
const POLL_MAX_SECONDS = 15;
const VU_MARGIN_SECONDS = 5; // 접수 응답 시간과 폴링 지연을 고려한 여유

const MAX_RPS = PROFILE === 'constant' ? RATE : 50;
const maxVUs = MAX_RPS * (POLL_MAX_SECONDS + VU_MARGIN_SECONDS);

const scenarios = {
    ramp: {
        executor: 'ramping-arrival-rate',
        timeUnit: '1s',
        startRate: 0,
        preAllocatedVUs: 50,
        maxVUs,
        // create-order2.js와 같은 부하 단계
        stages: [
            { duration: '30s', target: 5 },
            { duration: '1m', target: 10 },
            { duration: '1m', target: 30 },
            { duration: '1m', target: 50 },
            { duration: '30s', target: 0 },
        ],
    },
    constant: {
        executor: 'constant-arrival-rate',
        timeUnit: '1s',
        rate: RATE,
        duration: DURATION,
        preAllocatedVUs: Math.max(RATE * 2, 10),
        maxVUs,
    },
};

if (!scenarios[PROFILE]) {
    throw new Error(`PROFILE must be ramp or constant: ${PROFILE}`);
}

export const options = {
    discardResponseBodies: false,
    scenarios: { order_e2e: scenarios[PROFILE] },
    summaryTrendStats: ['avg', 'min', 'p(50)', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        // 부하를 목표대로 넣지 못하면 지연 수치를 비교할 수 없으므로 실패로 본다
        dropped_iterations: ['count==0'],
    },
};

const ordersAccepted = new Counter('orders_accepted');
const ordersRejected = new Counter('orders_rejected');
const ordersSucceeded = new Counter('orders_succeeded');
const ordersFailed = new Counter('orders_failed');
const ordersTimeout = new Counter('orders_timeout'); // 주문 실패가 아니라 관측 타임아웃
const statusPollErrors = new Counter('status_poll_errors');
const duplicateMismatches = new Counter('duplicate_response_mismatches');
const orderE2eDuration = new Trend('order_e2e_duration', true);

function newIdempotencyKey() {
    const random = Array.from(new Uint8Array(randomBytes(8)), (b) => b.toString(16).padStart(2, '0')).join('');
    return `${RUN_ID}-${exec.scenario.iterationInTest}-${random}`;
}

function orderBody() {
    return JSON.stringify({
        userId: (exec.scenario.iterationInTest % 1000) + 1,
        items: SKUS.map((sku) => ({ sku, quantity: QUANTITY })),
        shippingAddress: { recipientName: '부하테스트', recipientAddress: '서울시 강남구 테헤란로 1' },
    });
}

function submit(key) {
    const params = { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key } };
    const body = orderBody();
    if (!DUPLICATE_SEND) {
        return http.post(`${BASE_URL}/orders`, body, params);
    }
    const [first, second] = http.batch([
        ['POST', `${BASE_URL}/orders`, body, params],
        ['POST', `${BASE_URL}/orders`, body, params],
    ]);
    if (first.status !== second.status || first.body !== second.body) {
        duplicateMismatches.add(1);
    }
    return first;
}

export default function () {
    const key = newIdempotencyKey();
    const startedAt = Date.now();

    const accepted = submit(key);
    if (!check(accepted, { 'accepted 202': (r) => r.status === 202 })) {
        ordersRejected.add(1);
        return;
    }
    ordersAccepted.add(1);

    // 폴링 간격만큼 관측 지연이 포함된다. 실제 완료 시각과 최대 약 1초 차이가 날 수 있다.
    for (let elapsed = 0; elapsed < POLL_MAX_SECONDS; elapsed += POLL_INTERVAL_SECONDS) {
        sleep(POLL_INTERVAL_SECONDS);

        const response = http.get(`${BASE_URL}/orders/status/${encodeURIComponent(key)}`, {
            tags: { name: 'GET /orders/status/{idemKey}' },
        });
        if (response.status !== 200) {
            statusPollErrors.add(1);
            continue;
        }

        const status = response.json('data.status');
        if (status === 'SUCCEEDED') {
            ordersSucceeded.add(1);
            orderE2eDuration.add(Date.now() - startedAt, { outcome: 'succeeded' });
            return;
        }
        if (status === 'FAILED') {
            ordersFailed.add(1);
            orderE2eDuration.add(Date.now() - startedAt, { outcome: 'failed' });
            return;
        }
    }
    ordersTimeout.add(1);
}

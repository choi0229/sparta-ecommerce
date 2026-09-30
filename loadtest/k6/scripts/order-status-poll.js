import http from "k6/http";
import { check } from "k6";

/**
 * 주문 상태 폴링 부하 테스트
 * - seed 데이터(seed-000001 ~ seed-{SEED_COUNT})에서 무작위 키로 조회
 * - constant-arrival-rate로 응답 속도와 무관하게 초당 요청 수 고정
 */

const BASE_URL = __ENV.BASE_URL || "http://localhost:8083";
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || "5m";
const SEED_COUNT = Number(__ENV.SEED_COUNT || 10000);

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
  scenarios: {
    poll: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    checks: ["rate>0.99"],
  },
};

function randomSeedKey() {
  const n = Math.floor(Math.random() * SEED_COUNT) + 1;
  return `seed-${String(n).padStart(6, "0")}`;
}

export default function () {
  const res = http.get(`${BASE_URL}/api/orders/status/${randomSeedKey()}`, {
    responseType: "text",
  });

  check(res, {
    "status is 200": (r) => r.status === 200,
    "orderStatus exists": (r) =>
      r.status === 200 && r.json("data.orderStatus") != null,
  });
}

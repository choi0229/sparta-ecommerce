import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// FTS 검색 저부하 테스트 → /api/products/fts/search

export const options = {
    discardResponseBodies: false,
    scenarios: {
        keyword_search: {
            executor: 'ramping-arrival-rate', timeUnit: '1s',
            preAllocatedVUs: 20, maxVUs: 50,
            stages: [{ duration: '30s', target: 3 }, { duration: '1m', target: 10 }, { duration: '2m', target: 20 }, { duration: '30s', target: 0 }],
            exec: 'keywordSearch',
        },
        brand_search: {
            executor: 'ramping-arrival-rate', timeUnit: '1s',
            preAllocatedVUs: 10, maxVUs: 30,
            stages: [{ duration: '30s', target: 2 }, { duration: '1m', target: 5 }, { duration: '2m', target: 10 }, { duration: '30s', target: 0 }],
            exec: 'brandSearch',
        },
        complex_search: {
            executor: 'ramping-arrival-rate', timeUnit: '1s',
            preAllocatedVUs: 10, maxVUs: 30,
            stages: [{ duration: '30s', target: 2 }, { duration: '1m', target: 5 }, { duration: '2m', target: 10 }, { duration: '30s', target: 0 }],
            exec: 'complexSearch',
        },
        product_detail: {
            executor: 'ramping-arrival-rate', timeUnit: '1s',
            preAllocatedVUs: 5, maxVUs: 20,
            stages: [{ duration: '30s', target: 1 }, { duration: '1m', target: 3 }, { duration: '2m', target: 5 }, { duration: '30s', target: 0 }],
            exec: 'productDetail',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        'http_req_duration{scenario:keyword_search}': ['p(95)<3000'],
        'http_req_duration{scenario:brand_search}':   ['p(95)<3000'],
        'http_req_duration{scenario:complex_search}': ['p(95)<3000'],
        'http_req_duration{scenario:product_detail}': ['p(95)<500'],
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:51007/api';
const KEYWORDS = ['노트북', '맥북', '갤럭시', '아이폰', '운동화', '티셔츠', '청바지', '패딩', '에어팟', '덤벨', 'MacBook', 'Samsung', 'Nike', 'Apple', 'LG', '그램', 'ThinkPad', '후드티', '요가매트', '의자', 'Pro', 'Air', '무선', '블랙', '화이트'];
const BRANDS = ['Samsung', 'Apple', 'LG', 'Nike', 'Adidas', 'Uniqlo', 'Sony', 'Dell', 'Lenovo', 'ASUS', 'New Balance', 'Puma', 'Zara', 'Bose', 'MSI'];
const CATEGORY_IDS = [6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18];
const PAGE_SIZES = [10, 20, 30];
const MAX_PRODUCT_ID = 101135;

export function keywordSearch() {
    const keyword = KEYWORDS[randomIntBetween(0, KEYWORDS.length - 1)];
    const res = http.get(`${BASE_URL}/products/fts/search?keyword=${encodeURIComponent(keyword)}&page=${randomIntBetween(0, 10)}&size=${PAGE_SIZES[randomIntBetween(0, 2)]}`, { tags: { name: 'keyword_search' }, timeout: '10s' });
    check(res, { 'keyword 200': (r) => r.status === 200 });
    sleep(0.1);
}

export function brandSearch() {
    const brand = BRANDS[randomIntBetween(0, BRANDS.length - 1)];
    const res = http.get(`${BASE_URL}/products/fts/search?brandName=${encodeURIComponent(brand)}&page=${randomIntBetween(0, 10)}&size=${PAGE_SIZES[randomIntBetween(0, 2)]}`, { tags: { name: 'brand_search' }, timeout: '10s' });
    check(res, { 'brand 200': (r) => r.status === 200 });
    sleep(0.1);
}

export function complexSearch() {
    const keyword = KEYWORDS[randomIntBetween(0, KEYWORDS.length - 1)];
    const brand = BRANDS[randomIntBetween(0, BRANDS.length - 1)];
    const category = CATEGORY_IDS[randomIntBetween(0, CATEGORY_IDS.length - 1)];
    const res = http.get(`${BASE_URL}/products/fts/search?keyword=${encodeURIComponent(keyword)}&brandName=${encodeURIComponent(brand)}&categoryId=${category}&page=${randomIntBetween(0, 5)}&size=${PAGE_SIZES[randomIntBetween(0, 2)]}`, { tags: { name: 'complex_search' }, timeout: '10s' });
    check(res, { 'complex 200': (r) => r.status === 200 });
    sleep(0.1);
}

export function productDetail() {
    const res = http.get(`${BASE_URL}/products/${randomIntBetween(1, MAX_PRODUCT_ID)}`, { tags: { name: 'product_detail' }, timeout: '10s' });
    check(res, { 'detail 2xx or 404': (r) => r.status === 200 || r.status === 404 });
    sleep(0.1);
}
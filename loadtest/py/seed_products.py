import requests
import random
import time
import time as time_module

BASE_URL = "http://127.0.0.1:59154"  # minikube service 새 포트로 변경!

CATEGORIES = [
    {"name": "전자제품", "parentId": None, "sortOrder": 1, "status": "ACTIVE"},
    {"name": "의류",     "parentId": None, "sortOrder": 2, "status": "ACTIVE"},
    {"name": "스포츠",   "parentId": None, "sortOrder": 3, "status": "ACTIVE"},
    {"name": "가구",     "parentId": None, "sortOrder": 4, "status": "ACTIVE"},
    {"name": "식품",     "parentId": None, "sortOrder": 5, "status": "ACTIVE"},
]

SUB_CATEGORIES = [
    {"name": "노트북",   "parentKey": "전자제품", "sortOrder": 1},
    {"name": "스마트폰", "parentKey": "전자제품", "sortOrder": 2},
    {"name": "태블릿",   "parentKey": "전자제품", "sortOrder": 3},
    {"name": "이어폰",   "parentKey": "전자제품", "sortOrder": 4},
    {"name": "상의",     "parentKey": "의류",     "sortOrder": 1},
    {"name": "하의",     "parentKey": "의류",     "sortOrder": 2},
    {"name": "아우터",   "parentKey": "의류",     "sortOrder": 3},
    {"name": "운동화",   "parentKey": "스포츠",   "sortOrder": 1},
    {"name": "헬스용품", "parentKey": "스포츠",   "sortOrder": 2},
    {"name": "의자",     "parentKey": "가구",     "sortOrder": 1},
    {"name": "책상",     "parentKey": "가구",     "sortOrder": 2},
    {"name": "음료",     "parentKey": "식품",     "sortOrder": 1},
    {"name": "과자",     "parentKey": "식품",     "sortOrder": 2},
]

PRODUCT_TEMPLATES = {
    "노트북": {
        "brands": ["Samsung", "LG", "Apple", "Dell", "Lenovo", "ASUS", "HP", "MSI"],
        "names": ["갤럭시북", "그램", "MacBook Pro", "XPS", "ThinkPad", "ZenBook", "Pavilion", "게이밍 노트북"],
        "price_range": (500000, 3000000),
        "options": [
            {"color": "스페이스그레이", "storage": "256GB", "ram": "8GB"},
            {"color": "실버", "storage": "512GB", "ram": "16GB"},
            {"color": "골드", "storage": "1TB", "ram": "32GB"},
        ]
    },
    "스마트폰": {
        "brands": ["Samsung", "Apple", "LG", "Xiaomi", "Google", "OnePlus"],
        "names": ["갤럭시 S", "아이폰", "픽셀", "원플러스", "미 시리즈", "벨벳"],
        "price_range": (300000, 1500000),
        "options": [
            {"color": "블랙", "storage": "128GB"},
            {"color": "화이트", "storage": "256GB"},
            {"color": "블루", "storage": "512GB"},
        ]
    },
    "태블릿": {
        "brands": ["Apple", "Samsung", "Lenovo", "Microsoft"],
        "names": ["iPad Pro", "갤럭시탭", "레노버탭", "서피스"],
        "price_range": (300000, 1200000),
        "options": [
            {"color": "스페이스그레이", "storage": "64GB"},
            {"color": "실버", "storage": "128GB"},
        ]
    },
    "이어폰": {
        "brands": ["Sony", "Apple", "Samsung", "Bose", "JBL"],
        "names": ["WH-1000XM", "AirPods Pro", "갤럭시버즈", "QuietComfort", "Tune"],
        "price_range": (30000, 400000),
        "options": [
            {"color": "블랙", "type": "무선"},
            {"color": "화이트", "type": "유선"},
        ]
    },
    "상의": {
        "brands": ["Nike", "Adidas", "Uniqlo", "H&M", "Zara"],
        "names": ["기본 티셔츠", "후드티", "맨투맨", "셔츠", "니트"],
        "price_range": (10000, 100000),
        "options": [
            {"color": "화이트", "size": "S"},
            {"color": "블랙", "size": "M"},
            {"color": "그레이", "size": "L"},
            {"color": "네이비", "size": "XL"},
        ]
    },
    "하의": {
        "brands": ["Levi's", "Nike", "Adidas", "Uniqlo", "H&M"],
        "names": ["슬림핏 청바지", "조거팬츠", "트레이닝팬츠", "슬랙스"],
        "price_range": (15000, 120000),
        "options": [
            {"color": "블루", "size": "28"},
            {"color": "블랙", "size": "30"},
            {"color": "그레이", "size": "32"},
        ]
    },
    "아우터": {
        "brands": ["The North Face", "Patagonia", "Columbia", "Nike"],
        "names": ["패딩 점퍼", "바람막이", "코트", "플리스 자켓"],
        "price_range": (50000, 500000),
        "options": [
            {"color": "블랙", "size": "M"},
            {"color": "네이비", "size": "L"},
        ]
    },
    "운동화": {
        "brands": ["Nike", "Adidas", "New Balance", "Puma", "ASICS"],
        "names": ["에어맥스", "울트라부스트", "574", "RS-X", "젤-카야노"],
        "price_range": (50000, 300000),
        "options": [
            {"color": "화이트", "size": "255"},
            {"color": "블랙", "size": "260"},
            {"color": "그레이", "size": "265"},
        ]
    },
    "헬스용품": {
        "brands": ["Everlast", "Bowflex", "Reebok"],
        "names": ["덤벨 세트", "요가매트", "폼롤러", "저항밴드"],
        "price_range": (5000, 200000),
        "options": [
            {"color": "블랙", "weight": "5kg"},
            {"color": "블루", "weight": "10kg"},
        ]
    },
    "의자": {
        "brands": ["Herman Miller", "한샘", "이케아", "시디즈"],
        "names": ["에어론", "팝체어", "MARKUS", "T50"],
        "price_range": (50000, 1500000),
        "options": [
            {"color": "블랙", "material": "메쉬"},
            {"color": "그레이", "material": "패브릭"},
        ]
    },
    "책상": {
        "brands": ["이케아", "한샘", "일룸", "퍼시스"],
        "names": ["LINNMON", "모던 책상", "높이조절 책상", "L형 책상"],
        "price_range": (30000, 800000),
        "options": [
            {"color": "화이트", "size": "120cm"},
            {"color": "오크", "size": "140cm"},
        ]
    },
    "음료": {
        "brands": ["동서식품", "롯데", "코카콜라", "빙그레"],
        "names": ["아메리카노", "녹차", "탄산수", "에너지드링크"],
        "price_range": (500, 5000),
        "options": [
            {"size": "250ml", "type": "무가당"},
            {"size": "500ml", "type": "가당"},
        ]
    },
    "과자": {
        "brands": ["오리온", "롯데", "해태", "농심"],
        "names": ["초코파이", "빼빼로", "오징어땅콩", "새우깡"],
        "price_range": (500, 5000),
        "options": [
            {"size": "소", "flavor": "오리지널"},
            {"size": "중", "flavor": "초콜릿"},
        ]
    },
}


def safe_get(url, retries=5):
    for attempt in range(retries):
        try:
            return requests.get(url, timeout=10)
        except Exception as e:
            print(f"  ⚠️ GET 재시도 {attempt+1}/{retries}: {e}")
            time.sleep(2)
    return None


def safe_post(url, json, retries=5):
    for attempt in range(retries):
        try:
            return requests.post(url, json=json, timeout=10)
        except Exception as e:
            print(f"  ⚠️ POST 재시도 {attempt+1}/{retries}: {e}")
            time.sleep(2)
    return None


def get_all_categories():
    res = safe_get(f"{BASE_URL}/api/categories")
    if not res:
        return []
    data = res.json().get("data", [])
    flat = []
    for parent in data:
        flat.append({"id": parent["id"], "name": parent["name"]})
        for child in parent.get("childCategories", []):
            flat.append({"id": child["id"], "name": child["name"]})
    return flat


def create_categories():
    category_map = {}
    print("카테고리 생성 중...")

    for cat in CATEGORIES:
        all_cats = get_all_categories()
        existing = next((c for c in all_cats if c["name"] == cat["name"]), None)
        if existing:
            category_map[cat["name"]] = existing["id"]
            print(f"  ✅ 대분류 기존: {cat['name']} (id={existing['id']})")
            continue
        payload = {"name": cat["name"], "parentId": None, "sortOrder": cat["sortOrder"], "status": cat["status"]}
        safe_post(f"{BASE_URL}/api/categories", json=payload)
        all_cats = get_all_categories()
        found = next((c for c in all_cats if c["name"] == cat["name"]), None)
        if found:
            category_map[cat["name"]] = found["id"]
            print(f"  ✅ 대분류: {cat['name']} (id={found['id']})")

    for sub in SUB_CATEGORIES:
        parent_id = category_map.get(sub["parentKey"])
        if not parent_id:
            continue
        all_cats = get_all_categories()
        existing = next((c for c in all_cats if c["name"] == sub["name"]), None)
        if existing:
            category_map[sub["name"]] = existing["id"]
            print(f"  ✅ 소분류 기존: {sub['name']} (id={existing['id']})")
            continue
        payload = {"name": sub["name"], "parentId": parent_id, "sortOrder": sub["sortOrder"], "status": "ACTIVE"}
        safe_post(f"{BASE_URL}/api/categories", json=payload)
        all_cats = get_all_categories()
        found = next((c for c in all_cats if c["name"] == sub["name"]), None)
        if found:
            category_map[sub["name"]] = found["id"]
            print(f"  ✅ 소분류: {sub['name']} (id={found['id']})")

    return category_map


def generate_sku(cat_name, brand, idx, variant_idx):
    prefix = cat_name[:2].upper()
    brand_prefix = brand[:3].upper().replace("&", "").replace(" ", "")
    ts = str(int(time_module.time() * 1000))[-6:]
    return f"{prefix}-{brand_prefix}-{ts}-{idx:06d}-{variant_idx}"


def create_products(category_map, start_from=1, total=100000, batch_log=500):
    valid_cats = [c for c in PRODUCT_TEMPLATES.keys() if c in category_map]
    if not valid_cats:
        print("❌ 유효한 카테고리 없음!")
        return

    success = 0
    fail = 0
    print(f"\n유효 카테고리: {valid_cats}")
    print(f"상품 생성 시작: {start_from:,} ~ {total:,}\n")
    elapsed_start = time.time()

    for i in range(start_from, total + 1):
        cat_name = random.choice(valid_cats)
        template = PRODUCT_TEMPLATES[cat_name]
        category_id = category_map[cat_name]
        brand = random.choice(template["brands"])
        product_name = random.choice(template["names"])
        price_min, price_max = template["price_range"]
        options = template["options"]

        num_variants = random.randint(1, min(3, len(options)))
        selected_options = random.sample(options, num_variants)

        variants = []
        for j, opt in enumerate(selected_options):
            sku = generate_sku(cat_name, brand, i, j)
            variants.append({
                "sku": sku,
                "price": random.randint(price_min // 1000, price_max // 1000) * 1000,
                "stockQuantity": random.randint(0, 500),
                "optionJson": opt
            })

        payload = {
            "name": f"{brand} {product_name} {i}",
            "brandName": brand,
            "categoryId": category_id,
            "description": f"{brand}의 {product_name} 상품입니다.",
            "variants": variants,
            "images": []
        }

        res = safe_post(f"{BASE_URL}/api/admin/products", json=payload)
        if res and res.status_code == 200:
            success += 1
        else:
            fail += 1
            if fail <= 3 and res:
                print(f"  ❌ 실패 #{fail}: {res.status_code} {res.text[:100]}")

        if i % batch_log == 0:
            elapsed = time.time() - elapsed_start
            done = i - start_from + 1
            rps = done / elapsed if elapsed > 0 else 0
            remaining = (total - i) / rps if rps > 0 else 0
            print(f"  [{i:,}/{total:,}] 성공:{success:,} 실패:{fail} | {rps:.1f} req/s | 남은시간:{remaining:.0f}초")

    elapsed = time.time() - elapsed_start
    print(f"\n✅ 완료! 성공:{success:,}건 / 실패:{fail}건 ({elapsed:.1f}초)")


if __name__ == "__main__":
    category_map = create_categories()
    print(f"\n최종 카테고리 맵: {category_map}\n")

    if not category_map:
        print("❌ 카테고리 생성 실패")
        exit(1)

    # start_from: 이어서 넣을 때 마지막 로그 번호 + 1로 변경
    # 현재까지 4,500건 성공했으니 4501부터 시작
    create_products(category_map, start_from=47501, total=100000, batch_log=500)
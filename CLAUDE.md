# CLAUDE.md

## 프로젝트 개요

이 프로젝트는 MSA 기반 이커머스 시스템입니다.

- `product-api`: 상품, SKU/Variant, 검색, 약식 결제 흐름
- `order-api`: 주문 생성, 주문 상태, 주문 스냅샷, Saga, 멱등성
- `inventory-api`: 재고 예약, TTL 만료, 재고 해제, 보상 처리
- `logistics-api`: 배송 요청 생성, 배송 상태 관리, 배송 상태 이력, Transactional Outbox 기반 배송 이벤트 발행, 주문 이벤트 수신

핵심 관심사는 Saga, Transactional Outbox, Idempotency, 주문 스냅샷, 재고 예약, Elasticsearch 검색 성능, 관측성입니다.

---

## 반드시 지킬 규칙

- 각 서비스는 자신의 도메인 데이터만 소유합니다.
- 다른 서비스의 DB에 직접 접근하지 않습니다.
- 서비스 간 통신은 API 또는 Kafka 이벤트를 사용합니다.
- `@Transactional` 내부에서 오래 걸리는 외부 호출을 수행하지 않습니다.
- Kafka request-reply, HTTP 호출, Elasticsearch 호출을 DB 트랜잭션 내부에서 대기하지 않습니다.
- Kafka 이벤트는 중복 전달될 수 있으므로 Consumer는 멱등해야 합니다.
- 주문 항목은 주문 시점의 상품 정보를 스냅샷으로 저장해야 합니다.
- 재고는 직접 차감보다 예약과 보상 흐름을 통해 변경합니다.
- Elasticsearch는 검색용 projection이며 Source of Truth가 아닙니다.
- 상품 데이터의 Source of Truth는 PostgreSQL입니다.

---

## 작업 방식

- 코드를 수정하기 전에 관련 서비스 구조를 먼저 확인합니다.
- 요청 범위를 벗어난 대규모 리팩토링은 하지 않습니다.
- 기존 프로젝트 패턴을 우선 따릅니다.
- Controller에서 JPA Entity를 직접 노출하지 않습니다.
- 생성자 주입을 사용하고 필드 주입은 피합니다.
- 테스트나 빌드를 실제로 실행하지 않았다면 통과했다고 말하지 않습니다.
- 검증되지 않은 성능 개선을 주장하지 않습니다.
- secret, token, password, private key를 커밋하지 않습니다.

---

## 테스트 / 배포

테스트는 수정한 서비스 디렉터리에서 실행합니다.

```bash
./gradlew test
```

실제 Minikube/Kubernetes 반영은 테스트 또는 빌드만으로 되지 않습니다.

사용자가 배포 반영을 요청한 경우에만 `deploy-api` Skill과 `scripts/redeploy-api.sh`를 사용합니다.

---

## 응답 형식

작업 완료 시 다음 순서로 답변합니다.

1. 변경한 내용
2. 변경한 이유
3. 수정한 파일
4. 수행한 테스트 또는 검증
5. 남은 리스크 또는 후속 작업
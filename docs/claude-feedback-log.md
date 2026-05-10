# Claude Code 피드백 로그

이 문서는 Claude Code를 활용한 logistics-api 생성 및 하네스 구축 과정에서 실제로 발견되고 보강된 내용을 기록합니다.
재발 방지 수단이 코드·설정에 반영되었는지 추적하는 용도로 유지합니다.

---

## 2026-04-21 — logistics-api 생성 및 하네스 구축

### 1. OutboxPublisherJob self-invocation 문제

**발견 경로**
코드 리뷰 중 `OutboxPublisherJob.publish()` 내부에서 `markSent()` / `markFailed()`를 같은 클래스의 메서드로 직접 호출하고 있음을 확인.

**영향**
Spring AOP는 프록시 기반이므로 같은 Bean 내 자기 호출(self-invocation)은 프록시를 우회합니다.
`markSent` / `markFailed`에 선언된 `@Transactional(propagation = REQUIRES_NEW)`가 무시되어,
outbox 상태 갱신이 `publish()` 트랜잭션에 묶이거나 전혀 트랜잭션 보호를 받지 못하는 상태였습니다.
발행 실패 시 outbox 레코드가 올바르게 FAILED로 전환되지 않아 무한 재시도 또는 유실 위험이 있었습니다.

**수정**
`OutboxEventTransactionalService`를 별도 Spring Bean으로 분리하여 `markSent()` / `markFailed()`를 이관.
`OutboxPublisherJob`은 외부 Bean을 주입받아 호출하므로 AOP 프록시가 정상 적용됩니다.

**재발 방지**
- `.claude/rules/kafka-outbox-saga.md`에 "Outbox 상태 갱신은 self-invocation 주의" 항목 포함
- 코드 리뷰 체크리스트: `REQUIRES_NEW`를 사용하는 메서드가 같은 클래스 내에 있는지 확인

---

### 2. PESSIMISTIC_WRITE 사용 중 @Transactional 누락 문제

**발견 경로**
`OutboxEventTransactionalService` 분리 리팩토링 과정에서 `OutboxPublisherJob.publish()`의 `@Transactional`이 실수로 제거됨.

**영향**
`OutboxQueryRepository.findBatchForPublish()`는 `PESSIMISTIC_WRITE` 잠금을 사용합니다.
활성 트랜잭션이 없으면 JPA가 잠금을 획득하지 못하거나 `TransactionRequiredException`이 발생합니다.
잠금 없이 여러 인스턴스가 동일 outbox 레코드를 중복 발행할 수 있었습니다.

**수정**
`OutboxPublisherJob.publish()`에 `@Transactional`을 복원.
내부에서 호출하는 `markSent` / `markFailed`는 별도 Bean의 `REQUIRES_NEW`로 동작하므로 충돌 없음.

**재발 방지**
- 코드 리뷰 시 `PESSIMISTIC_WRITE`를 사용하는 Repository 메서드의 호출 경로에 `@Transactional`이 있는지 확인
- 향후 OutboxPublisherJob 수정 시 트랜잭션 어노테이션을 제거하지 않도록 주석으로 의도 명시

---

### 3. IdempotencyRecord PENDING stuck 가능성

**발견 경로**
`OrderEventConsumer`가 `IdempotencyRecord`를 PENDING으로 저장한 뒤, 이후 단계에서 예외가 발생하면 PENDING 상태로 영구히 남는 시나리오를 리뷰에서 식별.

**영향**
Consumer 재시작 후 동일 이벤트가 재전달될 때, PENDING 레코드가 존재하면 "이미 처리 중"으로 판단해 처리를 건너뜀.
실제로는 배송 생성이 완료되지 않았으므로 배송 누락이 발생합니다.

**수정**
`LogisticsTransactionalService.createShipmentForOrderEvent()`를 단일 `@Transactional` 메서드로 작성.
4가지 상태를 하나의 트랜잭션 안에서 처리합니다.

| 상태 | 처리 |
|---|---|
| COMPLETED 레코드 존재 | 중복 — 즉시 반환 |
| PENDING + Shipment 존재 | 레코드만 COMPLETED로 전환 (회복) |
| PENDING + Shipment 없음 | 기존 레코드 재사용 후 생성 |
| 레코드 없음 | PENDING 생성 → Shipment 생성 → COMPLETED |

**재발 방지**
- `.claude/rules/logistics-api.md` 멱등성 항목에 PENDING 회복 케이스 명시
- 단위 테스트 5개로 4가지 상태 + 중복 orderId 케이스 커버

---

### 4. createShipmentForOrderEvent 테스트 부족

**발견 경로**
초기 생성 코드에 단위 테스트가 없었음. 리뷰 과정에서 식별.

**영향**
PENDING stuck / 중복 생성 / 회복 경로가 검증되지 않은 채 CI를 통과할 수 있었습니다.

**수정**
`LogisticsTransactionalServiceTest` 작성 (`@ExtendWith(MockitoExtension.class)`, DB 불필요).

| 테스트 | 검증 내용 |
|---|---|
| `alreadyCompleted_returnsNull` | COMPLETED 레코드 → null 반환 |
| `pendingRecord_shipmentExists_recoversAndReturnsExisting` | PENDING + Shipment 존재 → 회복 |
| `pendingRecord_noShipment_reusesRecordAndCreatesShipment` | PENDING + Shipment 없음 → 생성 |
| `noRecord_createsPendingThenShipmentThenCompletes` | 정상 최초 생성 흐름 |
| `pendingRecord_duplicateOrderId_doesNotCreateNewShipment` | 중복 orderId → 새 Shipment 생성 안 함 |

Shipment.id private 필드는 `ReflectionTestUtils.setField()`로 주입 (`Map.of()` NPE 방지).

**재발 방지**
- `.claude/rules/logistics-api.md` 테스트 항목에 4가지 멱등성 케이스 명시
- GitHub Actions `logistics-api-test` job이 PR마다 실행

---

### 5. README의 위험 명령 설명 문구로 인한 guardrails 오탐

**발견 경로**
`scripts/claude-guardrails.sh`를 GitHub Actions에서 실행했을 때 README.md 때문에 job 실패.

**영향**
CI가 항상 실패하므로 guardrails job이 실질적인 보호 역할을 하지 못했습니다.

**원인**
guardrails의 위험 패턴 스캔이 `scripts/claude-guardrails.sh`와 `.github/` 만 제외하고 있었고,
README.md · `.claude/settings.json` 같은 문서·설정 파일은 스캔 대상에 포함되어 있었습니다.

**수정**
스캔 제외 패턴을 다음으로 확장:

```
^(README\.md|scripts/claude-guardrails\.sh|\.claude/settings\.json|\.github/)
```

**재발 방지**
- 새로운 문서·설정 파일에 위험 명령 예시를 추가할 경우 위 제외 패턴에 경로를 함께 추가
- guardrails 오탐 발생 시 제외 패턴 확인을 첫 번째 디버깅 단계로 삼음

---

### 6. Guardrails Markdown 문서 오탐 개선

README와 하네스 평가 문서에 위험 명령어를 설명 목적으로 작성했으나, guardrails가 이를 실제 위험 명령으로 오탐지했다.  
이에 따라 위험 명령 문자열 검사 대상에서 Markdown 문서를 제외하고, Java/YAML/SQL/Shell 등 실제 코드 및 설정 파일 중심으로 검사하도록 개선했다.

---

### 7. Minikube 배포 검증 — NodePort 직접 접근 불가 (Docker Desktop 환경)

**발견 경로**
Minikube 배포 후 `http://$(minikube ip):30084` 로 접근 시 응답 없음.

**영향**
NodePort로 접근이 안 되면 배포 검증 자체를 진행할 수 없을 것으로 오해할 수 있습니다.

**원인**
Docker Desktop 기반 Minikube는 VM 네트워크와 호스트 네트워크가 격리되어 있어 NodePort IP가 호스트에서 직접 라우팅되지 않습니다. 이는 서비스나 Pod의 문제가 아닙니다.

**수정**
`kubectl port-forward svc/logistics-api-svc 8084:8084 -n ecommerce` 로 우회하여 `localhost:8084` 접근.
Service와 Endpoint는 정상적으로 구성되어 있었고 모든 API 검증을 완료했습니다.

**재발 방지**
- Docker Desktop 기반 Minikube 환경에서는 NodePort 직접 접근 대신 `port-forward` 또는 `minikube service` 사용
- 배포 검증 체크리스트에 환경별 접근 방법 분기 추가 (위 내용은 `docs/` 배포 가이드에 반영)

---

## 2026-05-10 — Karpathy 스타일 CLAUDE.md 정비 및 address service 연동

### 8. CLAUDE.md가 .gitignore에 포함되어 있었던 문제

**발견 경로**
Phase 3 작업을 시작하면서 `.gitignore` 내용을 확인하던 중 `CLAUDE.md`가 포함되어 있음을 발견.

**영향**
CLAUDE.md 변경이 커밋에 반영되지 않았습니다.
하네스의 핵심 진입 파일이 버전 관리 밖에 있었으므로, 다른 환경에서 클론하거나 새 세션에서 시작하면
기대하던 지침이 없는 상태로 진행될 수 있었습니다.
하네스를 구성했어도 공유되지 않으면 실질적으로 없는 것과 같습니다.

**수정**
`.gitignore`에서 `CLAUDE.md` 항목 제거.
CLAUDE.md를 커밋 대상으로 전환하여 변경 이력이 git에 남도록 수정.

**재발 방지**
- 하네스 파일(.claude/ 하위 전체, CLAUDE.md)이 .gitignore에 포함되어 있지 않은지 초기 점검 항목에 추가
- 새 프로젝트에 하네스를 구성할 때 CLAUDE.md를 가장 먼저 커밋하는 순서로 진행

---

### 9. Karpathy 스타일 CLAUDE.md 도입 배경

**발견 경로**
Phase 2 이후 CLAUDE.md에 세부 내용이 누적되어 역할 경계가 흐려지는 경향을 관찰.
rules 파일에 있어야 할 내용과 CLAUDE.md에 있는 내용이 부분적으로 중복되었습니다.

**영향**
어느 파일이 권위 있는 규칙인지 불명확해지면, Claude가 rules보다 CLAUDE.md의 암묵적 내용에 의존하거나
두 파일의 지침이 충돌할 때 임의로 선택하는 상황이 생길 수 있습니다.

**수정**
CLAUDE.md를 다음 원칙으로 정비했습니다.
- 고수준 원칙 5개만 유지: Think Before Coding / Simplicity First / Surgical Changes / Goal-Driven Execution / Project Context
- 서비스별·도메인별 세부 규칙은 `.claude/rules/*.md`로 완전히 위임
- CLAUDE.md 첫 문단에 "도메인 규칙은 rules, 반복 작업은 skills 참조"를 명시

**재발 방지**
- CLAUDE.md에 새 내용을 추가하기 전에 "이 내용이 rules 파일에 더 적합하지 않은가"를 먼저 판단
- 특정 서비스에만 적용되는 규칙은 CLAUDE.md가 아닌 해당 서비스 rule 파일에 추가

---

### 10. AddressServiceClient stub/http 분리 작업에서 드러난 하네스 효과

**발견 경로**
order-api에 AddressServiceClient를 추가하는 작업 중 구조 선택 과정에서 관찰.

**관찰 내용**
`.claude/rules/order-api.md`의 트랜잭션 경계 규칙("@Transactional 내부에서 외부 서비스 호출 금지")이
구현 단계에서 실제로 적용되었습니다.
HTTP 구현체(`HttpAddressServiceClient`)를 호출하는 `resolveShippingAddress()`가 `@Transactional` 밖에서
실행되도록 구조가 유지되었고, 재작업 없이 완료되었습니다.

rules 파일이 없었다면 "HTTP 호출을 트랜잭션 밖에 두어야 한다"는 판단을 매 작업마다 설명해야 했을 것입니다.

**재발 방지**
- 향후 외부 서비스 클라이언트를 추가할 때 동일 원칙(`resolveX()` 호출은 트랜잭션 밖에서)이 rules 파일로 유지되도록 함

---

### 11. mock address-api + http mode smoke 자동화 과정에서 얻은 교훈

**발견 경로**
`e2e-order-address-http-smoke.sh` 작성 과정에서 기존 smoke script 구조와의 정합성을 맞추는 중 식별.

**관찰 내용**
기존 smoke script(`e2e-order-shipment-smoke.sh`, `e2e-order-invalid-sku-smoke.sh`)가 일관된 구조를 갖추고 있었기 때문에
새 smoke script를 작성할 때 동일한 패턴을 재사용할 수 있었습니다.
- `extract()` 함수 (`.data.*` 추출), `extract_error()` 함수 (`.error.errorCode` 추출)
- jq 유무 감지 후 grep/sed fallback
- `trap cleanup EXIT`로 stub 모드 복원 보장

smoke script를 별도 파일로 분리하지 않고 smoke-tests.yml에 인라인으로 작성했다면,
이 패턴을 재사용하기 어려웠을 것입니다.

**재발 방지**
- 새 시나리오 smoke script는 기존 구조(`extract()`, jq fallback, trap cleanup)를 그대로 따름
- 공통 helper 함수가 늘어나면 별도 `.sh` 라이브러리 파일로 분리 검토

---

### 12. 최신 이미지 미반영으로 503이 404처럼 보였던 검증 이슈

**발견 경로**
mock address-api를 배포한 후 addressId=503 검증에서 예상했던 HTTP 503 대신 404가 반환됨.

**원인**
`docker build` 후 `minikube image load`를 수행했지만,
기존에 실행 중이던 address-api Pod가 이전 이미지(503 stub 매핑이 없는 버전)를 그대로 사용하고 있었습니다.
WireMock은 매핑이 없는 요청에 기본값 404를 반환하므로,
addressId=503에 대한 매핑이 없는 이전 컨테이너가 404를 반환했습니다.

**해결**
`kubectl rollout restart deployment/address-api -n ecommerce`로 Pod를 재시작하여 새 이미지를 적용.
이후 addressId=503 → HTTP 503 정상 반환 확인.

**재발 방지**
- `minikube image load` 후 반드시 `kubectl rollout restart` 또는 `kubectl rollout status` 확인
- smoke script의 `[2/7]` 단계에서 `kubectl rollout status`가 완료된 후 검증을 진행하도록 구조화
- 이미지 로드 후 "예상과 다른 응답"이 나오면 Pod 이미지 버전을 먼저 확인

---

### 13. Kafka 문제처럼 보였지만 실제 배포 반영 문제였던 사례

**발견 경로**
order-api를 http 모드로 전환한 후 addressId=1 주문에서 Kafka 이벤트 발행이 되지 않는 것처럼 보였음.
`GET /api/orders/status/{idemKey}` 폴링에서 상태가 변하지 않아 Kafka consumer 문제로 오판.

**원인**
`kubectl set env`로 환경변수를 변경했지만, rollout 완료를 기다리지 않은 상태에서 주문 요청을 보냈습니다.
이전 Pod(stub 모드)가 여전히 요청을 처리하고 있었고, stub 모드에서의 주소 조회는 정상이지만
이전 이미지의 다른 설정 차이로 인해 처리가 지연된 것이었습니다.
Kafka consumer는 정상 동작 중이었으나 요청이 의도한 Pod에 도달하지 못한 것이었습니다.

**해결**
`kubectl rollout status deployment/order-api -n ecommerce --timeout=120s`가 완료된 이후에 주문 요청 실행.
이후 addressId=1 → status=CREATED, shipmentStatus=READY 정상 확인.

**재발 방지**
- `kubectl set env` 또는 deployment 변경 후에는 반드시 rollout status 확인 후 검증 진행
- 비동기 처리 이슈처럼 보일 때 "배포가 실제로 반영되었는가"를 먼저 확인
- smoke script 구조에서 env 변경 직후 `kubectl rollout status`를 필수 단계로 포함 (현재 `[3/7]` 단계에서 처리 중)
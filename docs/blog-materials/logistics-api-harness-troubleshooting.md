# logistics-api + Claude Code 하네스 트러블슈팅 기록

작업 중 실제로 마주친 문제와 해결 방법을 정리했습니다.

---

## 1. Xcode Command Line Tools 미설치 — git diff 실행 오류

**현상**
`git diff` 명령 실행 시 다음과 같은 오류 발생:

```
xcrun: error: invalid active developer path (/Library/Developer/CommandLineTools),
missing xcrun at: /Library/Developer/CommandLineTools/usr/bin/xcrun
```

**원인**
macOS에서 git을 포함한 개발 도구는 Xcode Command Line Tools에 의존합니다.
macOS 업데이트 후 Command Line Tools가 무효화되는 경우가 있습니다.

**해결**
```bash
xcode-select --install
```
설치 완료 후 git 정상 동작 확인.

---

## 2. Java 17 미설치

**현상**
`./gradlew test` 실행 시 Java를 찾지 못하는 오류 발생.

**원인**
macOS 기본 환경에 Java가 설치되어 있지 않음.

**해결**
```bash
brew install openjdk@17
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
java -version
```

`java -version` 으로 `openjdk 17.x.x` 출력 확인 후 진행.

---

## 3. Homebrew 미설치

**현상**
`brew install openjdk@17` 실행 시 `command not found: brew` 오류.

**원인**
Homebrew가 설치되어 있지 않은 초기 macOS 환경.

**해결**
Homebrew 공식 설치 스크립트를 실행한 뒤 Java 설치 재시도.
설치 후 터미널을 새로 열거나 PATH를 갱신해야 `brew` 명령이 인식됨.

---

## 4. Docker Desktop 무한 로딩 / Minikube 시작 실패

**현상**
Docker Desktop이 시작 후 무한 로딩 상태로 정상화되지 않음.
`minikube start` 실행 시 타임아웃 또는 연결 실패.

**원인**
Docker Desktop의 내부 상태가 이전 세션과 충돌하거나, 리소스 부족 상태에서 기동 시도.

**해결**
1. Docker Desktop 완전 종료 (트레이 아이콘 → Quit Docker Desktop)
2. 재시작 후 고래 아이콘이 완전히 안정될 때까지 대기
3. `minikube start` 재시도

Docker Desktop이 완전히 기동된 상태에서만 Minikube 시작이 정상 동작합니다.

---

## 5. openjdk:17-jdk-slim 이미지 Minikube 내 not found

**현상**
`kubectl get pod -n ecommerce -l app=logistics-api` 실행 시 Pod 상태가 `ErrImagePull` 또는 `ImagePullBackOff`.

```
Failed to pull image "openjdk:17-jdk-slim": ... manifest unknown
```

**원인**
`openjdk` 공식 이미지는 Docker Hub에서 deprecated 처리되어 일부 태그가 더 이상 제공되지 않거나,
Minikube 내부 환경에서 외부 이미지 pull이 제한된 상황.

**해결**
`logistics-api/Dockerfile`의 base image를 적극 유지되는 이미지로 교체:

```dockerfile
# 수정 전
FROM openjdk:17-jdk-slim

# 수정 후
FROM eclipse-temurin:17-jdk-jammy
```

또는 동일하게 유지 관리되는 `amazoncorretto:17-alpine` 도 사용 가능.
수정 후 `./scripts/redeploy-api.sh logistics-api` 재실행.

---

## 6. NodePort 30084 직접 접근 실패

**현상**
```bash
curl http://$(minikube ip):30084/actuator/health
# 응답 없음 또는 Connection refused
```

**원인**
Docker Desktop 기반 Minikube는 Linux VM과 호스트(macOS) 사이에 네트워크 격리가 존재합니다.
`minikube ip`로 반환되는 IP는 VM 내부 IP이며, macOS 호스트에서 직접 라우팅되지 않습니다.
이는 서비스나 Pod의 문제가 아닙니다.

**확인**
Service와 Endpoint가 정상적으로 구성되어 있는지 먼저 확인:
```bash
kubectl get svc logistics-api-svc -n ecommerce
kubectl get endpoints logistics-api-svc -n ecommerce
```
Endpoints에 Pod IP가 잡혀 있으면 서비스 구성은 정상입니다.

**해결**
`kubectl port-forward`로 로컬 포트를 클러스터 Service에 포워딩:

```bash
kubectl port-forward svc/logistics-api-svc 8084:8084 -n ecommerce
```

이후 `localhost:8084`로 접근 가능합니다.

**참고**
`minikube tunnel` 또는 `minikube service <서비스명> --url` 명령으로도 접근 가능하지만,
`port-forward`가 가장 빠르고 안정적입니다.

---

## 7. Guardrails가 Markdown 문서의 위험 명령 설명 문구를 오탐

**현상**
GitHub Actions `Claude Guardrails` job 실패.

```
[FAIL] 위험한 명령어 패턴이 감지되었습니다:
       README.md
       docs/claude-harness-evaluation.md
```

**원인**
`scripts/claude-guardrails.sh`의 위험 패턴 스캔이 모든 변경 파일을 대상으로 동작했습니다.
README.md와 하네스 평가 문서에는 `rm -rf`, `DROP TABLE`, `TRUNCATE` 등의 문자열이
설명 목적으로 포함되어 있어 실제 위험 명령과 구분되지 않았습니다.

**1차 수정**
README.md와 설정 파일을 명시적으로 제외:
```bash
SCAN_FILES=$(echo "$CHANGED_FILES" | grep -vE '^(README\.md|scripts/claude-guardrails\.sh|\.claude/settings\.json|\.github/)' || true)
```

**2차 수정**
`docs/claude-harness-evaluation.md` 추가 오탐 발생 후,
특정 파일 열거 방식의 한계를 인식하고 모든 Markdown 파일을 제외하는 방식으로 전환:
```bash
SCAN_FILES=$(echo "$CHANGED_FILES" | grep -vE '(\.md$|scripts/claude-guardrails\.sh|\.claude/settings\.json|\.github/)' || true)
```

**교훈**
- 위험 패턴 스캔은 실제 코드 파일(.java, .sh, .sql, .yaml)에만 적용하는 것이 적절합니다.
- 문서 파일은 설명 목적의 예시 코드를 포함할 수 있으므로 처음부터 제외 대상으로 설계하는 것이 좋습니다.
- 새로운 문서 형식이 추가될 때마다 제외 패턴을 업데이트하는 것보다, 확장자 기반 포함/제외가 유지보수 면에서 유리합니다.

---

## 8. Hibernate 6에서 javax.persistence 힌트가 silently 무시됨

**현상**
`OutboxQueryRepository`에서 PESSIMISTIC_WRITE 잠금 시 `"javax.persistence.lock.timeout"` 힌트를 설정했지만,
실제로 lock timeout이 적용되지 않아 잠금 대기가 무제한으로 발생할 수 있는 상태였습니다.
별도 오류 메시지 없이 힌트가 무시되기 때문에 코드만 보고는 정상 동작 중이라고 오인하기 쉽습니다.

**원인**
Spring Boot 3.x는 Hibernate 6을 사용하며, Hibernate 6부터 JPA 네임스페이스가 `javax.*`에서 `jakarta.*`로 변경되었습니다.
`javax.persistence.lock.timeout` 힌트는 Hibernate 6에서 인식되지 않아 적용 없이 통과됩니다.

**해결**
```java
// 수정 전
.setHint("javax.persistence.lock.timeout", 3000)

// 수정 후
.setHint("jakarta.persistence.lock.timeout", 3000)
```

**교훈**
- Spring Boot 2.x → 3.x 마이그레이션 또는 신규 프로젝트에서 `javax.*` 힌트 키를 그대로 사용하면 silently 무시됩니다.
- 힌트가 실제로 적용되는지 확인하려면 slow query 로그나 lock wait 모니터링이 필요합니다.
- Hibernate 6 기반 프로젝트에서는 JPA 힌트 키 전체를 `jakarta.*`로 통일하는 것이 안전합니다.

---

## 9. Outbox 폴링으로 인한 SQL 로그 과다 출력

**현상**
`OutboxPublisherJob`이 500ms 주기로 Outbox 이벤트를 폴링하면서
`show-sql: true` 설정으로 인해 분당 ~120줄 이상의 SQL 로그가 출력되었습니다.
실제 비즈니스 로그가 SQL 로그에 묻혀 가독성이 크게 떨어졌습니다.

**원인**
- `application.yml`에 `spring.jpa.show-sql: true` 설정
- Hibernate `format_sql` 미설정으로 멀티라인 SQL 출력
- 폴링 주기가 짧아 로그 볼륨이 빠르게 증가

**해결**
```yaml
spring:
  jpa:
    show-sql: false
    properties:
      hibernate:
        format_sql: false

logging:
  level:
    org.hibernate.SQL: INFO
    org.hibernate.orm.jdbc.bind: INFO
```

Hibernate SQL 로그를 `show-sql`이 아닌 Logger 레벨로 제어하면
필요 시 특정 패키지 로그 레벨만 올려서 디버깅할 수 있습니다.

**교훈**
- `show-sql: true`는 개발 초기에만 사용하고, 운영/통합 환경에서는 반드시 꺼야 합니다.
- Outbox 패턴처럼 짧은 폴링 주기가 있는 경우 SQL 로그 설정을 더욱 신중하게 관리해야 합니다.
- Hibernate Logger 레벨 기반 설정이 `show-sql`보다 환경별 제어에 유리합니다.

---

## 10. KafkaTemplate.send() 반환 타입 mock — CompletableFuture 처리

**현상**
`OutboxPublisherJobTest` 작성 시 `kafkaTemplate.send()`의 반환 타입을 mock하는 과정에서
`ListenableFuture`(Spring Kafka 2.x)와 `CompletableFuture`(Spring Kafka 3.x)의 혼동이 발생할 수 있습니다.

**원인**
Spring Kafka 3.x(Spring Boot 3.x)에서 `KafkaTemplate.send()`의 반환 타입이
`ListenableFuture<SendResult<K, V>>`에서 `CompletableFuture<SendResult<K, V>>`로 변경되었습니다.

**해결**
```java
// 성공 케이스 — 즉시 완료되는 Future
@SuppressWarnings("unchecked")
CompletableFuture<SendResult<String, String>> successFuture =
        CompletableFuture.completedFuture(mock(SendResult.class));
given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(successFuture);

// 실패 케이스 — .get() 호출 시 ExecutionException 발생
CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
failedFuture.completeExceptionally(new RuntimeException("kafka send timeout"));
given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(failedFuture);
```

`CompletableFuture.completedFuture(...)`는 `.get()`이 즉시 반환됩니다.
`completeExceptionally(...)`를 사용한 Future는 `.get()` 호출 시 `ExecutionException`을 발생시켜
`catch (Exception e)` 블록으로 진입하도록 유도합니다.

`mock(SendResult.class)`는 제네릭 타입 추론 경고가 발생하므로 `@SuppressWarnings("unchecked")`를 테스트 메서드에 추가합니다.

**교훈**
- Spring Boot 버전 업그레이드 시 `KafkaTemplate.send()` 반환 타입 변경 여부를 반드시 확인해야 합니다.
- mock할 때 Future 타입이 맞지 않으면 `stubbing argument mismatch` 오류가 발생합니다.

---

## 11. DomainException.getCode()가 String을 반환 — enum 직접 비교 불가

**현상**
`OutboxEventTransactionalServiceTest`와 `OrderEventConsumerTest`에서 `DomainException` 검증 시
`.isEqualTo(DomainExceptionCode.EVENT_NOT_FOUND)` 비교가 실패했습니다.

**원인**
`DomainException.getCode()`는 `String` 타입을 반환합니다 (`DomainExceptionCode.name()` 결과값).
`DomainExceptionCode` enum 인스턴스와 직접 비교하면 타입이 달라 항상 불일치합니다.

**해결**
```java
// 잘못된 비교
assertThat(((DomainException) ex).getCode())
        .isEqualTo(DomainExceptionCode.EVENT_NOT_FOUND);  // String vs Enum → 실패

// 올바른 비교
assertThat(((DomainException) ex).getCode())
        .isEqualTo(DomainExceptionCode.EVENT_NOT_FOUND.name());  // String vs String → 성공
```

**교훈**
- 예외 코드를 문자열로 저장하는 패턴에서는 테스트 비교 시 `.name()`을 사용해야 합니다.
- IDE 타입 추론이 없는 `assertThat` 체인에서는 실제 반환 타입을 소스에서 직접 확인하는 것이 안전합니다.

---

## 12. JPA 락 기반 Outbox 조회 경합 → native claim 전환

**현상**
멀티 Pod 환경에서 `OutboxPublisherJob` 인스턴스 여러 개가 동일한 PENDING Outbox 이벤트를 중복으로 클레임할 수 있는 구조였습니다.
`PESSIMISTIC_WRITE` 락 방식은 이미 잠긴 행을 기다리므로, 동시 처리량이 낮고 락 타임아웃 설정이 실제로 적용되지 않을 때는 무한 대기가 발생할 수 있었습니다.

**원인**
`SELECT ... FOR UPDATE`는 행을 잠그지만, 다른 인스턴스가 같은 행을 기다리는 직렬화 구조가 됩니다.
`PESSIMISTIC_WRITE`로 클레임하더라도 클레임된 상태로 상태 변경이 이루어지기 전 다른 인스턴스가 동일 이벤트를 가져가는 경쟁 조건이 잠재적으로 존재합니다.

**해결**
`FOR UPDATE SKIP LOCKED + UPDATE ... RETURNING` 네이티브 쿼리 방식으로 전환했습니다.

```sql
UPDATE outbox_event
SET status = 'PROCESSING', next_retry_at = :expiry
WHERE id IN (
    SELECT id FROM outbox_event
    WHERE status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= :now)
    ORDER BY created_at
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
)
RETURNING id
```

이미 처리 중인 행은 `SKIP LOCKED`로 건너뛰어 대기 없이 진행하므로, 멀티 인스턴스 환경에서 중복 없이 원자적으로 클레임됩니다.

**교훈**
- Outbox multi-instance 환경에서는 `FOR UPDATE SKIP LOCKED` 방식이 안전합니다.
- `PESSIMISTIC_WRITE` 락은 단일 인스턴스에서는 문제없지만, 스케일아웃 시 경합 위험이 있습니다.
- 네이티브 쿼리로 전환하면 Hibernate 버전별 힌트 호환성 문제에서도 벗어납니다.

---

## 13. PROCESSING 상태 stuck — stale recovery 없는 경우

**현상**
`OutboxPublisherJob`이 이벤트를 `PROCESSING`으로 전환한 뒤 Kafka 발행 전에 Pod가 비정상 종료(OOM Kill, 강제 재시작 등)되면,
해당 이벤트는 `PROCESSING` 상태로 무기한 남습니다.
Outbox 발행 스케줄러는 `PENDING` 상태만 처리하므로 해당 이벤트를 영구적으로 건너뜁니다.

**원인**
claim 단계에서 `next_retry_at = now + 2분`으로 만료 시각을 기록하지만,
만료된 PROCESSING 이벤트를 자동으로 회복하는 로직이 없으면 시간이 지나도 상태가 유지됩니다.

**해결**
`StaleOutboxRecoveryJob`을 추가했습니다.
30초 주기로 `status = PROCESSING AND next_retry_at < now`인 이벤트를 감지해
`status = PENDING`, `next_retry_at = now + 30초`로 초기화합니다.
이후 Outbox 발행 스케줄러가 해당 이벤트를 정상 재처리합니다.

**교훈**
- claim 만료 시각만 기록하고 recovery 로직이 없으면 Pod 장애 시 이벤트가 영구 stuck됩니다.
- recovery job 주기는 claim 만료 시간(2분)보다 짧게 설정하면 복구 지연을 줄일 수 있습니다.
- `logistics.outbox.stale.recovered` Counter 메트릭으로 회복 빈도를 모니터링하면 인프라 불안정 징후를 조기 포착할 수 있습니다.

---

## 14. CLAUDE.md가 .gitignore에 포함되어 있었던 문제

**현상**
새 환경에서 클론 후 Claude Code 세션을 시작했을 때, CLAUDE.md 내용이 반영되지 않거나
커밋 이력에서 CLAUDE.md 변경 사항이 보이지 않는 상황.

**원인**
`.gitignore`에 `CLAUDE.md`가 포함되어 있어 버전 관리 대상에서 제외된 상태였습니다.
하네스 파일 중 가장 중요한 진입점 파일이 git 추적 밖에 있었습니다.

**해결**
```bash
# .gitignore에서 CLAUDE.md 항목 제거 후
git add CLAUDE.md
git commit -m "chore: CLAUDE.md git 추적 대상으로 전환"
```

**교훈**
- 하네스 파일(`CLAUDE.md`, `.claude/` 전체)을 구성한 뒤 `.gitignore`에 포함되어 있지 않은지 반드시 확인합니다.
- 하네스를 구성했어도 파일이 공유되지 않으면 효과가 없습니다.
- 새 프로젝트 하네스 구성 체크리스트에 "CLAUDE.md git 추적 확인" 항목을 포함합니다.

---

## 15. minikube image load 후 최신 이미지가 반영되지 않는 문제

**현상**
`docker build`와 `minikube image load`를 완료했는데 새 이미지가 Pod에 반영되지 않았습니다.
예: addressId=503 요청 시 HTTP 503이 아닌 HTTP 404가 반환됨.

**원인**
`minikube image load`는 이미지를 클러스터에 로드하지만,
이미 실행 중인 Pod는 자동으로 재시작되지 않습니다.
기존 Pod가 이전 이미지(`imagePullPolicy: Never`)를 그대로 사용하고 있었습니다.

**확인**
```bash
kubectl get pod -n ecommerce -l app=address-api -o jsonpath='{.items[*].status.containerStatuses[*].imageID}'
```
출력된 imageID가 새 이미지와 다르면 미반영 상태입니다.

**해결**
```bash
kubectl rollout restart deployment/address-api -n ecommerce
kubectl rollout status deployment/address-api -n ecommerce
```

**교훈**
- `imagePullPolicy: Never`(Minikube 로컬 이미지) 환경에서는 `minikube image load` 후 반드시 rollout restart가 필요합니다.
- smoke script의 `[2/7]` 단계에서 `kubectl apply` 후 `kubectl rollout status`를 포함하면 이 문제를 자동으로 방지합니다.
- "예상과 다른 응답"이 나올 때 Pod 이미지 버전을 첫 번째 확인 항목으로 점검합니다.

---

## 16. addressId=503이 404처럼 보였던 오판 사례

**현상**
mock address-api에 addressId=503 → HTTP 503 응답 stub을 구성했는데,
실제 테스트에서 HTTP 404가 반환되었습니다.
order-api에서 ADDRESS_NOT_FOUND가 아닌 ADDRESS_LOOKUP_FAILED가 발생해야 하는 시나리오였습니다.

**원인**
WireMock은 일치하는 stub 매핑이 없을 때 기본값으로 HTTP 404를 반환합니다.
이미지 미반영(#15 참조)으로 인해 503 stub 매핑이 없는 이전 버전 WireMock이 실행 중이었고,
addressId=503 요청에 매핑이 없어 WireMock 기본 404가 반환되었습니다.

**해결**
`kubectl rollout restart deployment/address-api -n ecommerce`로 최신 이미지를 적용한 뒤 재검증.
addressId=503 → HTTP 503 → order-api ADDRESS_LOOKUP_FAILED 정상 확인.

**교훈**
- WireMock의 기본 동작(매핑 없음 → 404)을 숙지해야 합니다.
- "404가 나왔는데 404 stub을 설정한 것 같지 않다"면 이미지 미반영을 먼저 의심합니다.
- 검증 실패 시 "stub 설정이 잘못됐는가" vs "이미지가 반영되지 않았는가" 두 경우를 구분하여 진단합니다.

---

## 17. address-api Service NodePort → ClusterIP 전환 이슈

**현상**
`deployment/address-api/service.yaml` 초기 작성 시 NodePort로 구성했으나,
smoke script와 워크플로에서 주소 서비스에 클러스터 외부에서 직접 접근할 필요가 없었습니다.
NodePort 포트 번호가 기존 서비스와 충돌하거나 불필요한 외부 노출이 생겼습니다.

**원인**
address-api는 클러스터 내부에서만 order-api가 접근하는 서비스입니다.
외부 노출이 필요 없는 서비스에 NodePort를 설정하는 것은 과도한 설정입니다.

**해결**
`service.yaml`을 ClusterIP 타입으로 수정 (`type`, `nodePort` 필드 제거):
```yaml
spec:
  selector:
    app: address-api
  ports:
    - protocol: TCP
      port: 8090
      targetPort: 8080
```

order-api는 `ADDRESS_CLIENT_BASE_URL=http://address-api-svc:8090`으로 클러스터 내부 DNS를 통해 접근합니다.

**교훈**
- 서비스 타입은 "외부에서 접근이 필요한가"를 먼저 결정합니다.
  - 클러스터 내부만: ClusterIP
  - 개발/테스트 직접 접근: NodePort + port-forward 조합
  - 프로덕션 외부 노출: LoadBalancer / Ingress
- mock address-api처럼 클러스터 내부 용도로만 쓰이는 서비스는 ClusterIP가 적합합니다.

---

## 18. 배포 변경이 Kafka 문제처럼 보였던 사례

**현상**
`kubectl set env`로 order-api를 http 모드로 전환한 후 주문 생성 요청을 보냈는데,
상태 조회(`GET /api/orders/status/{idemKey}`)에서 상태가 변하지 않았습니다.
처음에는 Kafka consumer가 동작하지 않는 것으로 오판했습니다.

**원인**
`kubectl set env` 직후 rollout이 완료되기 전에 주문 요청을 보냈습니다.
이전 Pod(stub 모드)가 여전히 요청 일부를 처리하고 있었고,
해당 Pod의 설정 상태가 기대와 달라 정상 처리가 이루어지지 않았습니다.
Kafka consumer 자체는 정상이었습니다.

**확인**
```bash
kubectl rollout status deployment/order-api -n ecommerce
```
rollout이 완료되지 않은 상태(`Waiting for rollout to finish`)라면 전환 중인 상태입니다.

**해결**
`kubectl rollout status ... --timeout=120s`가 완료된 이후 주문 요청 재실행.
이후 addressId=1 → status=CREATED, shipmentStatus=READY 정상 확인.

**교훈**
- 비동기 처리 이슈(Kafka, Outbox)처럼 보일 때 "배포가 실제로 반영되었는가"를 먼저 확인합니다.
- `kubectl set env` 또는 deployment 변경 후에는 반드시 `kubectl rollout status`로 완료를 확인한 뒤 검증합니다.
- smoke script 구조에서 env 변경 직후 rollout status 확인 단계를 필수로 포함하면 이 오판을 방지합니다.

---

## 19. 배송지 필수 정책 변경으로 기존 happy/negative smoke 실패

**현상**
GitHub Actions Smoke Tests(all) 실행 시 기존 happy smoke와 negative smoke 모두 `SHIPPING_ADDRESS_REQUIRED`로 주문 생성 실패.

**원인**
order-api에 `addressId` 또는 `shippingAddress` 중 하나를 반드시 포함해야 하는 정책이 추가된 이후,
기존 smoke script CREATE_BODY에는 배송지 정보가 없었습니다.
smoke script 자체는 문법상 정상이지만, API 정책 변경에 의해 요청이 거부됩니다.

**해결**
두 smoke script CREATE_BODY에 `"shippingAddress":{"recipientName":"홍길동","recipientAddress":"서울시 강남구 테헤란로 1"}` 추가.
negative smoke는 배송지는 정상값으로 두고 SKU를 `SKU-INVALID`로 유지.

**교훈**
- smoke script가 "최근 바꾼 것 없음" 상태에서 실패하면 API 정책 변경을 먼저 의심합니다.
- 신규 정책 추가 시 기존 smoke script를 함께 검토하는 습관이 필요합니다.

---

## 20. port-forward가 종료 중인 이전 Pod에 연결되는 문제

**현상**
address-http smoke [4/7] port-forward 시작 후 [5/7] curl에서 exit code 7(connection refused) 발생.
`PF_READY=true`가 되었음에도 이후 요청이 거부됨.

**원인**
rollout 이후 잠깐 동안 이전 Pod(종료 중)와 새 Pod가 동시에 존재합니다.
`kubectl port-forward svc/<name>` 방식은 서비스 레이블 기반으로 Pod를 선택하므로, 종료 중인 이전 Pod에 연결될 수 있습니다.
health check(`/actuator/health`) 타이밍에 따라 이전 Pod에서 응답이 돌아올 수도 있어 PF_READY가 true가 되더라도 이후 요청이 실패합니다.

**해결**
```bash
ORDER_API_POD=$(kubectl get pods -n "${NAMESPACE}" -l app=order-api \
  --sort-by=.metadata.creationTimestamp \
  -o custom-columns=NAME:.metadata.name --no-headers | tail -n 1)
kubectl wait --for=condition=ready pod/"${ORDER_API_POD}" -n "${NAMESPACE}" --timeout=120s
kubectl port-forward pod/"${ORDER_API_POD}" 8083:8083 -n "${NAMESPACE}" &
```

**교훈**
- rollout 이후 port-forward 대상은 Service 경유보다 최신 Pod에 직접 지정하는 방식이 안정적입니다.
- `--sort-by=.metadata.creationTimestamp | tail -n 1` 패턴으로 최신 Pod 이름을 추출합니다.

---

## 21. rollout 완료 후에도 port-forward health check 타임아웃 발생

**현상**
`kubectl wait --for=condition=ready pod/<name>` 이 완료됐음에도 health check(`/actuator/health`) 루프가 응답을 받지 못해 timeout.

**원인**
`kubectl wait --for=condition=ready`는 kubelet 관점의 probe 통과 기준입니다.
JVM 애플리케이션의 경우 probe 통과 이후에도 HTTP 서버가 완전히 초기화되는 데 추가 시간이 걸릴 수 있습니다.
기존 구현은 port-forward 직후 15초(seq 1 15)만 대기하고 health check를 시도했습니다.

**해결**
health check 루프를 `seq 1 30`으로 연장하고, curl 옵션을 `curl -sS --max-time 2 >/dev/null 2>&1`로 강화.

**교훈**
- `kubectl wait --for=condition=ready`는 HTTP 레이어 준비를 보장하지 않습니다.
- JVM 기반 서비스는 probe 통과 이후에도 몇 초 더 대기가 필요할 수 있습니다.

---

## 22. integrationTest task가 없는 서비스에서 GitHub Actions 실패

**현상**
integration-tests.yml 전체 실행(all) 시 `product-api`에서 `Task 'integrationTest' not found in root project` 오류.

**원인**
`order-api`와 `logistics-api`는 별도의 `integrationTest` Gradle task가 정의되어 있지만,
`product-api`와 `inventory-api`는 표준 `test` task만 존재합니다.
모든 서비스에 `./gradlew integrationTest`를 적용하면 task가 없는 서비스에서 빌드 실패합니다.

**해결**
workflow에 서비스별 분기 추가:
```yaml
if [[ "${{ matrix.service }}" == "order-api" || "${{ matrix.service }}" == "logistics-api" ]]; then
  ./gradlew integrationTest
else
  ./gradlew test
fi
```

**교훈**
- 멀티 서비스 workflow에서 모든 서비스가 동일한 Gradle task를 갖는다고 가정하면 안 됩니다.
- 새 서비스 추가 시 integrationTest task 유무를 workflow 분기 조건에 함께 반영합니다.

---

## 23. self-hosted runner 없이 smoke workflow 실행 시 무한 대기

**현상**
smoke-tests.yml을 push했지만 workflow가 "Waiting for a runner to pick up this job" 상태에서 진행되지 않음.

**원인**
smoke-tests.yml이 `runs-on: self-hosted`로 설정되어 있습니다.
GitHub-hosted runner에서는 `localhost` 클러스터에 접근할 수 없으므로 의도적으로 self-hosted 전용으로 제한한 것입니다.
self-hosted runner가 등록되어 있지 않으면 workflow는 runner를 기다리며 멈춥니다.

**해결**
로컬 머신에서 runner를 GitHub Actions Settings → Runners 화면을 통해 등록.
runner가 online 상태가 된 이후 smoke workflow가 정상 실행되어 모든 시나리오 통과 확인.

**교훈**
- `runs-on: self-hosted` workflow는 runner를 별도로 등록하지 않으면 실행되지 않습니다.
- workflow_dispatch 전용 트리거로 제한하면 외부 fork PR 실행 위험을 줄일 수 있습니다.

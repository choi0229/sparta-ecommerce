# Phase 3 하네스 고도화 — Karpathy 스타일 CLAUDE.md 도입

작성 기준: 2026-05-10
대상: sparta-ecommerce MSA 프로젝트

---

## 왜 Phase 3가 필요했는가

Phase 2에서 revfactory/harness를 참조해 agents, rules, skills, CI gate, guardrails를 갖춘 구조를 완성했습니다.
그런데 실제 작업을 진행하다 보니 두 가지 문제가 드러났습니다.

**문제 1 — CLAUDE.md가 .gitignore에 포함되어 있었다**

CLAUDE.md 변경이 커밋에 반영되지 않았습니다.
하네스의 핵심 진입 파일이 버전 관리 밖에 있었기 때문에,
새 세션이나 다른 환경에서 기대하던 지침이 없는 상태로 시작될 수 있었습니다.
하네스를 구성했어도 파일이 공유되지 않으면 실질적으로 없는 것과 같습니다.

**문제 2 — CLAUDE.md와 rules 파일 간 경계가 흐릿했다**

Phase 2 초기에는 CLAUDE.md에 프로젝트 전반 원칙과 서비스별 세부 규칙이 섞이는 경향이 있었습니다.
세부 내용은 `.claude/rules/*.md`에 있는데 비슷한 내용이 CLAUDE.md에도 일부 남아 있어,
어느 파일이 권위 있는 규칙인지 불명확했습니다.

---

## 기존 하네스의 한계

Phase 2 하네스 항목별 평가(2026-04-21 기준)에서 다음 세 항목이 3점으로 평가되었습니다.

| 항목 | 점수 | 주요 원인 |
|---|---|---|
| permissions boundary | 3 / 5 | 실제 차단 여부 edge case 미검증 |
| guardrails script | 3 / 5 | macOS `grep -P` 비호환, 오탐 2회 발생 |
| 피드백 루프 | 3 / 5 | feedback-log 작성이 수동, CI 결과와 미연결 |

세 항목 모두 "구성은 되어 있지만 실제 효과가 일관되지 않는다"는 특성이 있었습니다.
CLAUDE.md 구조 자체보다 버전 관리 누락이 더 근본적인 원인이었습니다.

---

## Karpathy 스타일 CLAUDE.md 도입 내용

Andrej Karpathy의 "LLM 지시는 얇고 정밀하게, 두껍고 포괄적이지 않게"라는 원칙을 참고했습니다.
긴 지시 문서보다 명확한 역할 경계와 구체적인 참조 구조가 더 효과적이라는 관점입니다.

구체적으로 반영한 내용:

**1. CLAUDE.md는 고수준 원칙 5개만 유지**
- Think Before Coding / Simplicity First / Surgical Changes / Goal-Driven Execution / Project Context
- 각 섹션은 3~5줄을 넘지 않도록 제한
- "무엇을 하지 않을지"가 "무엇을 해야 하는지"만큼 명시됨

**2. 세부 규칙은 `.claude/rules/*.md`로 완전히 위임**
- 서비스별 경계, 이벤트 계약, Saga/Outbox 원칙은 CLAUDE.md에서 언급하지 않음
- rules 파일이 path-scoped로 자동 로드되어 해당 서비스 작업 시에만 적용됨
- 중복 정의 없음

**3. `.gitignore`에서 CLAUDE.md 제거**
- CLAUDE.md를 커밋 대상으로 전환
- 변경 이력이 git에 남고, 팀/환경 간 동일 지침 보장

**4. 참조 구조 명시**
- CLAUDE.md 첫 문단에 "도메인 규칙은 `.claude/rules`, 반복 작업은 `.claude/skills` 참조"를 명시
- Claude가 작업 시작 전 어디를 먼저 확인해야 하는지 안내

---

## 무엇이 달라졌는가

| 항목 | Phase 2 | Phase 3 |
|---|---|---|
| CLAUDE.md 길이 | 성장에 따라 늘어나는 경향 | 5개 섹션으로 고정 |
| 세부 규칙 위치 | CLAUDE.md + rules 일부 중복 | rules 파일에만 존재 |
| 버전 관리 | .gitignore 포함 (변경 이력 없음) | git 추적 (커밋 이력 존재) |
| 새 세션 일관성 | 환경에 따라 지침 누락 가능 | 항상 동일 CLAUDE.md 로드 |
| rules/skills 참조 | 암묵적 | CLAUDE.md에서 명시적으로 안내 |
| 배포 검증 범위 | 수동 curl + Minikube 확인 | smoke script로 자동화 |

이 변화는 코드 생성 품질 자체보다 **지침 안정성**에 더 큰 영향을 주었습니다.
다음은 정성적 관찰입니다 (수치 측정 없음).

- 세션 시작 후 프로젝트 맥락을 반복 설명하는 빈도가 줄었습니다.
- Claude가 작업 범위를 벗어나려 할 때 "CLAUDE.md의 Surgical Changes 원칙"을 명시적으로 참조하며 범위를 제한하는 경우가 늘었습니다.
- 외부 호출을 `@Transactional` 안에 넣으려는 시도가 rules 파일 기반으로 일관되게 차단되었습니다.

---

## 실제 작업 사례 — AddressServiceClient stub/http 분리

Phase 3 구조에서 진행된 첫 번째 주요 작업입니다.

**요청 내용**
`order-api`에 주소 서비스 클라이언트를 추가하되, 실제 HTTP 구현체와 stub 구현체를 설정 기반으로 선택 가능하게 구성.

**하네스가 작동한 부분**

- CLAUDE.md Simplicity First 원칙으로 "인터페이스 + stub 먼저, HTTP 구현체는 별도 단계"로 자연스럽게 분리되었습니다.
- `.claude/rules/order-api.md` 트랜잭션 경계 규칙이 적용되어 외부 HTTP 호출이 `@Transactional` 밖에서 이루어졌습니다.
- `@Component`를 `StubAddressServiceClient`에서 제거하고 `AddressClientConfig`가 빈을 단독 관리하도록 한 판단은 CLAUDE.md Simplicity First가 기여했습니다.

**하네스 없이도 가능했을 부분**
인터페이스 + 구현체 분리, `@ConfigurationProperties` 사용은 일반적인 Spring Boot 패턴입니다.

**하네스가 없었다면 달라졌을 부분 (정성적 관찰)**
트랜잭션 경계 규칙이 없었다면 HTTP 호출이 `@Transactional` 안에 들어갔을 가능성이 있었습니다.
이는 DB 커넥션 점유 시간 증가로 이어지는 구조적 문제입니다.

---

## 얻은 교훈

**1. 하네스는 버전 관리되어야 한다**

CLAUDE.md가 .gitignore에 있었던 기간 동안, 하네스를 구성했어도 없는 것과 같은 상황이 간헐적으로 발생할 수 있었습니다.
하네스 구성 = 파일 작성 + git 추적 + 팀 공유까지 포함해야 완성됩니다.

**2. CLAUDE.md는 얇게, 규칙은 rules 파일에**

CLAUDE.md에 세부 내용을 계속 추가하면 파일이 무거워지고 세션마다 전체 내용을 처리해야 합니다.
Karpathy 스타일의 "얇은 진입점 + 구조화된 세부 파일" 방식이 장기 유지보수에 유리합니다.
규칙이 중복되면 어느 파일이 권위 있는지 불명확해져 적용이 불일관해집니다.

**3. 규칙의 효과는 참조 빈도로 확인된다**

rules 파일이 실제로 작동하는지 확인하는 방법은 "Claude가 작업 중 규칙 파일을 명시적으로 참조하는가"를 관찰하는 것입니다.
참조 없이 코드가 생성된다면, 규칙이 로드되지 않았거나 path-scoped 범위에 해당하지 않는다는 신호입니다.

**4. 검증 자동화는 하네스 효과를 확인하는 도구다**

smoke script가 있으면 "하네스가 있을 때 코드가 올바르게 동작하는가"를 반복 가능한 방식으로 확인할 수 있습니다.
수동 curl 기반 검증은 재현하기 어렵고, 이전 상태와 비교하기도 어렵습니다.

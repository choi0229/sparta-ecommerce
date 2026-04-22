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

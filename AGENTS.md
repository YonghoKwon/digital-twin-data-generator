# AGENTS.md

이 파일은 `digital-twin-data-generator` 저장소의 개발 작업 가이드입니다.

## 프로젝트 요약

이 프로젝트는 Spring Boot 기반 ActiveMQ Artemis 데이터 생성/전송 시뮬레이터입니다.

기술 버전: Java 21, Spring Boot 4.1.2, springdoc-openapi 3.2.0, Apache Artemis Jakarta client 2.57.0 (`org.apache.artemis`).
JSON 처리는 Jackson 3 (`tools.jackson.*`)와 Spring이 주입하는 `JsonMapper`를 사용합니다.
Jackson annotation 패키지는 `com.fasterxml.jackson.annotation`을 유지하며, `spring.jackson.use-jackson2-defaults=true`로 기존 JSON 기본 동작을 유지합니다.
JPA/H2/Postgres와 H2 콘솔은 유지하며, Boot 4에서는 콘솔 자동설정을 위해 `spring-boot-h2console` 모듈이 필요합니다.

현재 주요 기능:

- 포맷 기반 데이터 생성
- 랜덤 데이터 대량 생성
- `format.txt` + `data.txt` 기반 메시지 생성
- header 기반 data 매핑
- quoted CSV 처리
- ActiveMQ Topic 전송
- 목표 TPS 기반 pacing 전송(`targetTps`)
- dry-run 생성 결과 확인
- dry-run messages object 응답
- JmsTemplate 기반 부하 테스트
- JmsTemplate 부하 테스트 결과 집계(`elapsedMillis`, `actualTps`, `successCount`, `failureCount`)
- profile별 request/response logging 정책
- Swagger UI 제공

제외한 기능:

- task 상태 이력 DB 저장 기능은 구현하지 않습니다.
- `/activemq/task/statuses` 계열 task 상태 이력 API는 사용하지 않습니다.

## 실행

기본 실행:

```bash
mvn spring-boot:run
```

local profile 실행:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

prod profile 실행:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

Swagger:

```text
http://localhost:8080/swagger
http://localhost:8080/swagger-ui/index.html
```

## 테스트

```bash
./mvnw -B test
```

Windows PowerShell에서는 `.\mvnw.cmd -B test`를 사용하며, 설치된 Maven의 `mvn test`도 가능합니다.
외부 Artemis 브로커 없이 기존 테스트와 dry-run JSON object 계약 테스트를 실행합니다.
CI도 Java 21 기준으로 `mvn -B test`를 실행합니다.

## 주요 API

전송 API:

```text
POST /activemq/request/{taskId}
POST /activemq/request/file/{taskId}
POST /activemq/request/file-data/{taskId}
```

Dry-run API:

```text
POST /activemq/request/dry-run?limit=10
POST /activemq/request/file/dry-run?limit=10
POST /activemq/request/file-data/dry-run?limit=10
```

JmsTemplate 부하 테스트 API:

```text
POST /activemq/request/jms-template-load/{taskId}
```

Task API:

```text
GET /activemq/task/running-tasks
POST /activemq/task/cancel-tasks
POST /activemq/task/cancel-task/{taskId}
```

## 요청 필드 메모

일반/file/file-data/JmsTemplate 부하 테스트 요청에서 `targetTps`를 사용할 수 있습니다.

```json
{
  "targetTps": 100
}
```

동작:

```text
targetTps = 0   : TPS 제한 없음
targetTps > 0   : 초당 targetTps에 가깝게 pacing
```

`delayTime`과 `targetTps`를 함께 주면 둘 다 적용됩니다. 일반적으로 TPS 제어 목적이면 `delayTime=0`을 권장합니다.

## Dry-run 응답 규칙

Dry-run 응답의 `messages`는 escaped JSON string이 아니라 object 형태입니다.

```json
{
  "requestedLimit": 5,
  "returnedCount": 1,
  "estimatedRepeatCount": 1,
  "estimatedSourceRowCount": 1,
  "estimatedTotalMessagesPerTask": 1,
  "generationMode": "FILE_DATA",
  "messages": [
    {
      "CREATE_TIMESTAMP": "20260504192700000",
      "MESSAGE_ID": "KE2D1Z11",
      "DATA_MAP": {}
    }
  ]
}
```

## JmsTemplate 부하 테스트 응답 규칙

`/activemq/request/jms-template-load/{taskId}`는 문자열이 아니라 결과 DTO를 반환합니다.

```json
{
  "taskId": "jms-load-1",
  "requestedCount": 10000,
  "successCount": 10000,
  "failureCount": 0,
  "elapsedMillis": 100235,
  "actualTps": 99.766,
  "workerCount": 4,
  "targetTps": 100,
  "cancelled": false,
  "message": "completed"
}
```

## 파일 기반 데이터 규칙

format 예시:

```json
"temperature": "{{temperature}}",
"pressure": "{{pressure}}",
"status": "{{status}}"
```

순서 기반 data:

```csv
25.1,1001,OK
25.2,1002,WARN
```

header 기반 data:

```csv
status,temperature,pressure
OK,25.1,1001
WARN,25.2,1002
```

quoted CSV:

```csv
status,message,temperature
OK,"hello, world",25.1
WARN,"quoted ""text"" sample",25.2
```

## 부하 테스트 메모

기존 `concurrentTasks` 방식은 동시 task/producer 부하 테스트에 가깝습니다.
JmsTemplate 방식은 제한된 worker와 session cache를 사용하므로 메시지 처리량 비교에 더 적합합니다.

총 메시지 수:

```text
일반 랜덤: messageCount
value row: messageCount × value row 수
file repeat: messageCount
file-data: messageCount × data row 수
concurrentTasks 사용 시: 위 계산 × concurrentTasks
```

`messageCount`가 0이고 `repeatBoolean=true`이면 다음 규칙을 따릅니다.

```text
repeatCount = ceil(repeatTime / delayTime)
```

단, `delayTime=0`이면 내부 기준값 1000ms로 계산됩니다.

## 로그 정책

profile별 request/response logging 정책:

```text
local: request body / response body 포함
prod : request body / response body 제외
```

관련 설정:

```properties
simulator.http-logging.enabled=true
simulator.http-logging.include-request-body=true
simulator.http-logging.include-response-body=true
simulator.http-logging.max-payload-length=8000
```

운영 로그에 민감 정보가 그대로 남지 않도록 합니다.
메시지나 요청 내용을 로그로 남길 때는 `SensitiveLogMasker.mask(...)` 적용 여부를 확인합니다.

## 작업 완료 후 문서 갱신 규칙

기능, API, 설정, 테스트 방식, 사용법을 변경했다면 작업 완료 전에 아래 세 파일을 반드시 확인하고 최신 상태로 맞춥니다.

1. `AGENTS.md`
2. `README.md`
3. `src/main/부하테스트.http`

다음 항목이 바뀌면 세 파일을 특히 주의해서 갱신합니다.

- REST API path
- request body / response body
- application properties
- `targetTps` / `messageCount` / `repeatTime` / `concurrentTasks` / `workerCount` 동작
- dry-run 응답 구조
- JmsTemplate 부하 테스트 결과 구조
- task 상태 관리 방식
- CSV 파싱 규칙
- placeholder 매핑 규칙
- Swagger 접근 경로
- 테스트 실행 방법

변경이 필요 없다고 판단한 경우에도 최종 응답에 세 파일 확인 여부를 명시합니다.

# AGENTS.md

이 파일은 `digital-twin-data-generator` 저장소의 개발 작업 가이드입니다.

## 프로젝트 요약

이 프로젝트는 Spring Boot 기반 ActiveMQ Artemis 데이터 생성/전송 시뮬레이터입니다.

기술 버전: Java 21, Spring Boot 4.1.2, springdoc-openapi 3.2.0, Apache Artemis Jakarta client 2.44.0 (`org.apache.activemq`, 운영 브로커 2.44.0과 동일 버전으로 고정).
JSON 처리는 Jackson 3 (`tools.jackson.*`)와 Spring이 주입하는 `JsonMapper`를 사용합니다.
Jackson annotation 패키지는 `com.fasterxml.jackson.annotation`을 유지하며, `spring.jackson.use-jackson2-defaults=true`로 기존 JSON 기본 동작을 유지합니다.
JPA/H2/Postgres, H2 콘솔과 개발용 `/test` API는 제외했습니다. 설정은 application.properties로 통합하며 virtual threads와 포트 8080을 유지합니다.

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

일반 format/value 요청의 `typedValues`는 기본 false입니다. true일 때 Integer/Double/Boolean 랜덤값과 value 값을 JSON number/boolean으로 출력합니다.
String/Date는 문자열이며 잘못된 타입 값은 오류입니다. false이면 기존 문자열 출력을 유지합니다.
JmsTemplate 요청도 `activeMQIp`, `id`, `pw`, `topic` override를 지원하며 생략/공백이면 서버 기본값을 사용합니다.

## S1 공통 브로커·파일·오류 규칙

- 공통 계층: `broker.BrokerSettings`, `BrokerConnectionService`, `MessagePublisher`, `JmsMessagePublisher`.
- 발행 scope는 task별 connection/session cache를 닫습니다. headers는 JMS string property로 설정합니다.
- 비동기 전송 3종은 요청 스레드에서 입력 검증·파일 읽기·dry-run 1건 JSON 검증 후 connect/start/close를 확인합니다.
- 입력/파일/JSON 오류는 400, 브로커 연결 실패는 502, 실행 중인 JmsTemplate taskId 중복은 409입니다.
- 오류 JSON은 `{"error","message"}`이며(400 message는 검증 실패 원인, controller 패키지 전체에 적용) error 값은 `INVALID_REQUEST`, `BROKER_CONNECTION_FAILED`, `DUPLICATE_TASK_ID`입니다.
- 성공 문자열, API path, dry-run 및 LoadTestResultDto 구조는 유지합니다. 이후 행에서 생성이 실패하면 task를 FAILED로 처리합니다.

```properties
simulator.files.base-dir=./data
# local profile: c:/Project
simulator.activemq.connect-timeout-ms=3000
simulator.message-log.sample-every=0
# EAI 필드별 override 예시 (underscore 보존)
simulator.message.file-data-extra-fields[works_code]=K
simulator.message.file-data-extra-fields[interface_data_t_len]=760
```

`filePath="."`는 base-dir 자체, 상대 경로는 base-dir 기준입니다. 절대 경로는 base-dir 내부만 허용합니다.
정규화 경로와 실제 경로를 검사해 `..`, 외부 절대 경로/다른 드라이브, 외부 symlink 탈출을 차단합니다.
timeout은 `callTimeout`, `callFailoverTimeout`, `connect-timeout-millis`에 적용하고 initialConnectAttempts/reconnectAttempts는 0입니다.
file-data EAI 기본 필드의 순서·값(빈 문자열 및 공백 포함)은 기존과 같으며 LinkedHashMap 설정으로 관리합니다.
신규 설정 키는 뒤에 추가됩니다. 기본 바이트 계약은 회귀 테스트로 고정합니다.
placeholder는 JSON 따옴표/escape 상태를 추적해 문자열 내부이면 JSON escape, 외부이면 원문을 삽입합니다.
전송 시에도 모든 생성 메시지를 JSON 검증합니다.

테스트는 브로커 mock과 닫힌 포트로 실행하고 서버는 RANDOM_PORT를 사용합니다.
Windows symlink 권한이 없으면 해당 테스트만 Assumptions로 skip하고 결과에 사유를 적습니다.

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
메시지 본문은 DEBUG, sample-every=0이면 INFO 샘플 끔, 양수 N이면 성공 전송 N건마다 INFO 1건입니다.
task 시작/종료는 INFO로 taskId, 전송 수, 경과 ms, 결과를 남깁니다.

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

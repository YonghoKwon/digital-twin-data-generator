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

실시간 API (Swagger `Realtime`):

```text
POST /dt/publish
POST /dt/publish/preview
POST /dt/streams
GET /dt/streams
GET /dt/streams/{id}
PATCH /dt/streams/{id}
DELETE /dt/streams/{id}
DELETE /dt/streams
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

## 실시간 전송

`realtime` 패키지와 `controller/RealtimeController`에서 공통 브로커 계층을 재사용합니다. 스트림 상태는 메모리에만 보관하며 앱 종료 시 모두 정리합니다.
`POST /dt/publish`는 동기 전송이며 `count=1`, `intervalMs=0`이 기본입니다. 결과는 `{sent,failed,elapsedMillis,topic,firstMessage}`이며 브로커 오류는 502입니다.
`POST /dt/publish/preview`는 브로커에 연결하지 않고 `{count,topic,messages:[object...]}`를 반환합니다. count는 1~10입니다.

기본 `envelope=DTCORE`는 object payload와 messageId를 요구하며 `CREATE_TIMESTAMP`와 `DATA_MAP` 키에 같은 `yyyyMMddHHmmssSSS` 시각을 넣습니다.
`RAW`는 임의 JSON을 그대로 직렬화하며 문자열 payload는 따옴표 없는 문자열 본문으로 전송합니다.
`standardHeaders=true`이면 UUID·UTC ISO-8601·schema version을 JMS string property에 넣습니다.
Artemis 2.44.0 클라이언트가 하이픈 property를 거부하므로 예비안을 적용합니다:
`dx-schema-version → dx_schema_version`, `dx-message-id → dx_message_id`, `dx-sent-at → dx_sent_at`.
사용자 headers에서도 이 세 이름만 변환하며 나머지는 JMS Java 식별자 규칙(예약어/JMS 접두사 제외)을 검증합니다. 표준 헤더 덮어쓰기와 변환 후 충돌은 400입니다.

```properties
simulator.realtime.schema-version=1
simulator.realtime.max-publish-count=1000
simulator.realtime.max-rate-tps=5000
simulator.realtime.max-streams=50
```

문자열 값 전체가 토큰이면 타입을 유지하며 객체·배열에 재귀 적용합니다. 일부 토큰은 문자열 치환입니다.
`{{seq}}`, `{{now}}`, `{{now:yyyy-MM-dd'T'HH:mm:ss}}`, `{{uuid}}`, `{{random:Integer:1..10}}`,
`{{random:Double:1..2}}`, `{{random:Boolean:}}`, `{{walk:20:10..30:0.5}}`, `{{cycle:OK|WARN}}`, `{{pick:A|B}}`를 지원합니다.
random 조건은 기존 `RandomValueGenerator` 문법이며 잘못된/알 수 없는 토큰은 요청 시 400입니다.
seq는 성공 전송 기준 1부터, cycle은 seq 기준, walk는 각 필드별 초기값에서 시작하고 지정 범위와 최대 변화량을 지킵니다.

단발 예시 (`POST /dt/publish`):

```json
{"topic":"topic.sensor","messageId":"SENSOR","payload":{"temperature":23.5},"standardHeaders":true}
```

센서 스트림 (`POST /dt/streams`, 기본 durationSec/maxMessages=0은 무한):

```json
{"streamId":"sensor-1","topic":"topic.sensor","messageId":"SENSOR","rateTps":1,"payload":{"seq":"{{seq}}","temperature":"{{walk:20:10..30:0.5}}"}}
```

`PATCH /dt/streams/sensor-1`에 `{"rateTps":10}`, `{"paused":true}`, `{"paused":false}`를 보내 속도 변경·일시정지·재개합니다.
payload 교체도 가능하며 다음 메시지부터 적용하고 walk 상태를 초기화합니다. 진행 중인 발행 한 건은 완료될 수 있습니다.
FILE_DATA 스트림은 payload 대신 다음 source를 사용합니다:

```json
{"streamId":"file-1","messageId":"SENSOR","rateTps":1,"source":{"type":"FILE_DATA","filePath":".","formatFileName":"format.txt","dataFileName":"data.csv","loop":true}}
```

기존 header/순서/quoted CSV·날짜 정규화·JSON escape·base-dir/실제 경로 제한을 따릅니다. 행을 순서대로 보내고 loop=false이면 마지막 행에서 COMPLETED입니다.
기존 private 파일 로직은 변경하지 않고 realtime 어댑터와 S1 비교 테스트로 계약을 유지합니다. 파일은 생성 요청 시 읽고 검증하며 PATCH payload는 파일 소스를 단일 payload 반복으로 교체합니다.
파일 행의 값은 리터럴로 유지하여 값 생성 토큰을 확장하지 않으며 기존 EAI 부가 필드 대신 실시간 envelope를 사용합니다.

스트림별 가상 스레드 한 개와 독립 발행 scope를 사용합니다. 첫 발행은 즉시이며 절대 시각 pacing으로 지연 누적을 피하고 밀림 시 따라잡기 없이 기준을 재설정해 lagResets를 증가시킵니다.
rateTps는 양의 실수(예: 0.2), durationSec는 일시정지/재연결을 포함한 경과 초, maxMessages는 성공 전송 수 기준입니다.
상태는 RUNNING/PAUSED/RECONNECTING/COMPLETED/STOPPED/FAILED이며 중복 실행 id는 409, 최대 동시 수 초과는 429, 없는 id는 404입니다.
브로커 오류는 1·2·4·8·16·30초 백오프로 재연결합니다. failed는 실제 실패 시도 수, skippedWhileDisconnected는 연결 단절 동안 생략한 예약 수(일시정지 제외)입니다.
actualTps는 최근 10초 성공 수/관측 시간(시작 후 10초 미만이면 경과 시간)이며 lastError는 자격 정보와 공급자 상세 문자열을 노출하지 않습니다.
종료 상태는 최근 100개만 남기며 DELETE는 STOPPED 상태를 반환합니다. 네트워크 작업이 이미 진행 중이면 scope 정리는 공통 브로커 timeout 이내에 이어집니다.
테스트는 fake publisher로 시간·순서·재연결을 검증하며 100 TPS × 2초의 합격 범위는 180~220건입니다. 실제 STOMP 헤더 수신은 S4 검증 범위입니다.

## REST Mock

`com.dt.digitaltwinsimulator.mock` 패키지는 `/mock/**` 서빙과 `/mock-admin` 관리 API를 제공합니다.
`simulator.mock.base-path`, `routes-dir`, `journal-size`, `max-delay-ms`로 설정하며 route/호출 기록은 메모리에만 저장합니다.
route 파일은 `mock-routes/*.json`, 가이드는 [docs/rest-mock.md](docs/rest-mock.md), 요청 예시는 [src/main/rest-mock.http](src/main/rest-mock.http)입니다.
관리 API는 Swagger의 `REST Mock Admin` 태그에 노출하고 실제 서빙 경로는 숨깁니다.
신규 테스트는 `src/test/java/com/dt/digitaltwinsimulator/mock`에서 RANDOM_PORT/MockMvc로 실행하며 전체 게이트는 기존 `.\mvnw.cmd -B test`입니다.
시작 route 로드 실패는 ERROR 로그와 `GET /mock-admin/status`에 기록하고 빈 route로 기동하며, 수동 reload 실패는 400과 기존 route/순번 유지를 보장합니다.
DTCore import는 ApiUrl 앞 `/` 추가·query 제거 후 행별 검증하고 `imported`/`skipped`/`invalid`(id·reason)를 반환합니다.
매칭은 정확 경로 > `**` 없는 패턴(변수·`*` 적은 순) > `**` 패턴(변수 적은 순) > 등록 순입니다.
TRACE route 등록은 400, TRACE 요청은 405이며 Tomcat TRACE 활성화 설정을 두지 않습니다.
등록 후 bodyFile 읽기 실패는 500 `MOCK_BODY_FILE_UNREADABLE`과 routeId를 반환합니다. 렌더/파일 오류 WARN에는 route id·method·path·예외 스택만 남기고 본문·헤더 원문을 넣지 않습니다.

# AGENTS.md

이 파일은 `digital-twin-data-generator` 저장소의 개발 작업 가이드입니다.

## 프로젝트 요약

이 프로젝트는 Spring Boot 기반 Digital Twin(UE DTCore) 테스트용 데이터 생성/전송·구독·REST Mock 시뮬레이터입니다.

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
- 실시간 단발 전송(`/dt/publish`)과 실행 중 제어 가능한 지속 스트림(`/dt/streams`)
- 내장 Artemis 2.44.0 브로커(STOMP TCP+WebSocket, 기본 off)와 토픽 구독·SSE(`/dt/broker`, `/dt/subscriptions`)
- UE DTCore REST API Mock(`/mock/**`, `/mock-admin/**`)

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

내장 브로커·구독 API (Swagger `Embedded Broker`, `Subscriptions`):

```text
GET /dt/broker
POST /dt/subscriptions
GET /dt/subscriptions
GET /dt/subscriptions/{id}/messages?limit=&afterSeq=&messageId=
GET /dt/subscriptions/{id}/stream
DELETE /dt/subscriptions/{id}
DELETE /dt/subscriptions/{id}/messages
```

REST Mock API (Swagger `REST Mock Admin`, 서빙 경로 `/mock/**`는 Swagger에서 숨김):

```text
ANY  /mock/**
GET/POST /mock-admin/routes, GET/PUT/DELETE /mock-admin/routes/{id}
PATCH /mock-admin/routes/{id}/enabled, POST /mock-admin/routes/{id}/toggle
POST /mock-admin/routes/reload, GET /mock-admin/status
GET/DELETE /mock-admin/requests
POST /mock-admin/import/dtcore-api-table?overwrite=false
```

시나리오 API (Swagger `Scenarios`):

```text
GET /dt/scenarios
GET/PUT/DELETE /dt/scenarios/{file}
POST /dt/scenarios/validate
POST/GET/DELETE /dt/scenario-runs
GET/PATCH/DELETE /dt/scenario-runs/{runId}
GET /dt/scenario-runs/{runId}/events?limit=50
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
simulator.http-logging.exclude-paths=/dt/subscriptions/*/stream
```

운영 로그에 민감 정보가 그대로 남지 않도록 합니다.
메시지나 요청 내용을 로그로 남길 때는 `SensitiveLogMasker.mask(...)` 적용 여부를 확인합니다.
메시지 본문은 DEBUG, sample-every=0이면 INFO 샘플 끔, 양수 N이면 성공 전송 N건마다 INFO 1건입니다.
task 시작/종료는 INFO로 taskId, 전송 수, 경과 ms, 결과를 남깁니다.
`Accept`에 `text/event-stream`이 있거나 `exclude-paths`의 Ant 패턴 목록(쉼표 구분)에 맞으면
공통 RequestResponseLoggingFilter에서 요청/응답 로깅과 본문 캐싱을 건너뜁니다.

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

## 내장 브로커와 구독

- 내장 Artemis는 운영과 동일한 **2.44.0**, BOM 관리 서버/STOMP 모듈과 직접 구성한 EmbeddedActiveMQ를 사용합니다.
  Boot Artemis starter를 추가하지 않습니다. 기본/local `simulator.embedded-broker.enabled=false`.
- enabled 및 `use-as-default=true`이고 실제 running일 때만 주소 생략 요청의 core/계정 기본값을
  `broker.BrokerDefaultsProvider`로 일반/file/file-data/JmsTemplate/구독에 공유합니다.
  명시 URL은 기존 외부 기본값입니다. 요청 id/pw override를 지원합니다.
  기동 실패/중지 시 외부 기본값으로 fallback하며 `/dt/broker`의 `defaultTarget`은 EMBEDDED 또는 EXTERNAL입니다.
- host=0.0.0.0, core-port=61626, stomp-port=61623 (STOMP TCP+WebSocket 공용).
  username/password 기본 artemis/artemis, 단일 계정만 허용, 상태/로그에 비밀번호를 노출하지 않습니다.
- UE DTCoreSettings: `WebSocketUrl=ws://<PC IP>:61623`, `Login=<설정값>`, `Passcode=<설정값>`.
  다른 PC에는 방화벽 TCP 61623 허용 (HTTP/SSE 8080, 외부 JMS 61626).
- max-frame-bytes=2097152: 기본 65536이 1 MiB 단일 프레임을 거부하여 2 MiB 예비안 적용.
  stomp-min-large-message-size=102400, max-size-bytes=104857600, address-full-policy=PAGE (DROP 선택 가능).
  journal 비영속, PAGE/large 임시 파일 정상 종료 삭제, 실행 중 Artemis audit logger 비활성화.
- JMS topic=STOMP destination 문자열, MULTICAST 기본, prefix 불필요.
  `dx_schema_version`은 동일 STOMP 헤더, `dx-schema-version`은 JMS client가 AMQ139012로 전송 전 거부합니다.
  S2 헤더는 underscore 사용. STOMP SEND content-length 없으면 TEXT, 있으면 BYTES (WS 프레임 종류와 별개).
- POST /dt/subscriptions: topic 필수, subscriptionId 생략 시 UUID, activeMQIp/id/pw override,
  bufferSize 기본 1000/최대 `simulator.subscribe.max-buffer=10000`, maxBodyBytes 기본 65536/양수.
  동시 구독 최대 `simulator.subscribe.max-subscriptions=20`, non-durable.
  wildcard는 점 구분, * 한 단어, # 0개 이상 단어 (`dt.sensor.#`).
- 기록은 seq/receivedAt/topic/type(TEXT|BYTES|OTHER)/encoding(UTF8_TEXT|BINARY)/sizeBytes/body/bodyJson/bodyBase64Head/messageId/properties/truncated.
  TEXT UTF-8 바이트 제한, 잘리지 않은 JSON만 bodyJson 제공, 최상위 MESSAGE_ID 추출.
  BYTES도 maxBodyBytes 미리보기가 유효한 UTF-8이면 encoding=UTF8_TEXT/body 제공,
  잘리지 않은 JSON은 bodyJson/최상위 MESSAGE_ID를 제공합니다. 잘린 UTF-8 끝의 불완전 문자는 제외합니다.
  유효하지 않은 UTF-8은 encoding=BINARY/bodyBase64Head(앞 256바이트)만 제공합니다.
  민감 property 마스킹, 버퍼 오래된 순서로 제거, 비워도 seq 유지.
- CONNECTED/RECONNECTING/STOPPED, 누적 수/마지막 수신/오류 표시, 재연결 1~30초.
  앱 종료 구독→브로커. 브로커 기동 실패에도 앱 유지, 상태 running=false/lastError.
- API 목록:
  `GET /dt/broker`, `POST /dt/subscriptions`, `GET /dt/subscriptions`,
  `GET /dt/subscriptions/{id}/messages?limit=100&messageId=UE-1`,
  `GET /dt/subscriptions/{id}/stream`, `DELETE /dt/subscriptions/{id}`,
  `DELETE /dt/subscriptions/{id}/messages`.
  생성 201, 삭제/비움 204, 중복 409, 한도 429, 없는 ID 404, 입력 400, 브로커 502, 오류 {"error","message"}.
  afterSeq 생략 시 필터에 맞는 최근 limit개를 seq 오름차순 반환, 지정 시 그 seq 이후부터 limit개입니다.
- SSE: `curl -N http://localhost:8080/dt/subscriptions/ue-sensors/stream`.
  ready 후 신규 message 이벤트/seq ID, 15초 keepalive, 재생 없음.
  느린 대기열 min(bufferSize,256) 초과 시 overflow 이벤트를 best-effort로 보내고 닫습니다.
  data={"reason":"SLOW_CONSUMER","dropped":n}, n은 버린 대기 기록과 거부된 1건(전송 중 제외)입니다.
  WARN은 구독 id만 남깁니다. 느린 SSE는 끊기므로 마지막 수신 seq의 afterSeq로 이어서 조회합니다.
  공통 로깅 필터의 Accept/설정 경로 제외로 SSE 응답 버퍼링을 방지합니다.
- `.\mvnw.cmd -B test`: 임의 포트 내장 브로커/JDK WebSocket STOMP로 본문/헤더 양방향,
  1 MiB, CRUD/버퍼/마스킹/SSE/재연결/기동 실패/기존 API 기본값 검증.
  상세 설정과 예시는 README의 같은 섹션과 src/main/부하테스트.http에 있습니다.

## 실시간 전송

`realtime` 패키지와 `controller/RealtimeController`에서 공통 브로커 계층을 재사용합니다. 스트림 상태는 메모리에만 보관하며 앱 종료 시 모두 정리합니다.
`POST /dt/publish`는 동기 전송이며 `count=1`, `intervalMs=0`이 기본입니다. 성공 결과는 `{sent,failed,elapsedMillis,topic,firstMessage}`입니다.
count만큼 먼저 생성하므로 생성/토큰 오류는 전송 없이 400 `INVALID_REQUEST`와 원인 메시지를 반환합니다. 시각/UUID/walk 값도 이 사전 생성 시점에 결정됩니다.
첫 전송 전 브로커 실패는 502, 1건 이상 성공 후 브로커 실패는 누적 sent와 `failed:1,stoppedReason:"BROKER_ERROR"`를 포함한 207입니다. 일부 전송 완료를 HTTP 상태와 결과로 함께 알리기 위해 207을 사용하며 이미 보낸 메시지는 취소하지 않습니다.
`POST /dt/publish/preview`는 브로커에 연결하지 않고 `{count,topic,messages:[object...]}`를 반환합니다. count는 1~10입니다.

기본 `envelope=DTCORE`는 object payload와 messageId를 요구하며 `CREATE_TIMESTAMP`와 `DATA_MAP` 키에 같은 `yyyyMMddHHmmssSSS` 시각을 넣습니다.
`RAW`는 임의 JSON을 그대로 직렬화하며 문자열 payload는 따옴표 없는 문자열 본문으로 전송합니다.
`standardHeaders=true`이면 UUID·UTC ISO-8601·schema version을 JMS string property에 넣습니다.
DTCore 규약 `dx-*`는 Artemis 2.44.0 JMS 제약(AMQ139012)으로 `dx_*`로 전달됩니다. S4에서 `_` 이름이 STOMP 헤더로 그대로 전달됨을 확인했습니다:
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
rate 변경 시 다음 전송은 마지막 성공 전송 시각 + 새 주기(이미 지났으면 now)입니다. pause는 추가 전송을 만들지 않으며 실제 pause→resume 때만 now부터 한 건을 허용합니다. 이미 실행 중인 스트림에 paused=false를 반복해도 즉시 발행하지 않습니다.
payload만 교체하면 기존 예약 시각을 유지합니다. 발행 도중 rate가 바뀌면 그 발행 완료 시각부터 새 주기를 적용합니다.
payload 교체도 가능하며 다음 메시지부터 적용하고 walk 상태를 초기화합니다. 진행 중인 발행 한 건은 완료될 수 있습니다.
FILE_DATA 스트림은 payload 대신 다음 source를 사용합니다:

```json
{"streamId":"file-1","messageId":"SENSOR","rateTps":1,"source":{"type":"FILE_DATA","filePath":".","formatFileName":"format.txt","dataFileName":"data.csv","loop":true}}
```

기존 header/순서/quoted CSV·날짜 정규화·JSON escape·base-dir/실제 경로 제한을 따릅니다. 행을 순서대로 보내고 loop=false이면 마지막 행에서 COMPLETED입니다.
`logic.FileDataTemplate`에 S1의 파일 읽기·placeholder·CSV·헤더 매핑·날짜 정규화·경로 제한·JSON escape 규칙을 모아 `ActiveMQRequestLogic`과 `RealtimeFileSource`가 공유합니다. 기존 전송/dry-run 바이트와 S1 오류 메시지를 유지합니다.
파일은 생성 요청 시 읽고 검증하며 PATCH payload는 파일 소스를 단일 payload 반복으로 교체합니다.
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

## 시나리오 재생

- 구현은 `scenario` 패키지와 `controller/ScenarioController`, Swagger 태그 `Scenarios`입니다.
- 라이브러리 GET `/dt/scenarios`, GET/PUT/DELETE `/dt/scenarios/{file}`, POST `/dt/scenarios/validate`.
  원문 object를 저장/조회하며 PUT 전 검증합니다. validate는 200 `{valid,eventCount,durationMs,topics,errors}`로 오류를 수집합니다.
- 실행 POST/GET/DELETE `/dt/scenario-runs`, GET/PATCH/DELETE `/dt/scenario-runs/{runId}`,
  GET `/dt/scenario-runs/{runId}/events?limit=50`. POST는 file 또는 inline scenario 중 하나, runId/speed/loop/startAtMs/startPaused 선택, 201입니다.
  PATCH는 speed(0.1~100)/paused/seekMs(0..durationMs), DELETE 단건은 종료 run/반복 호출에도 STOPPED, 전체는 상태 배열입니다.
  중복/종료 PATCH 409, 동시 한도 429, 없는 파일/run 404, 입력 400, 오류 `{error,message}`입니다.
- `simulator.scenario.dir=./scenarios`(local `c:/Project/scenarios`), max-runs=10, max-track-rows=1000000.
  라이브러리 파일 경로/외부 symlink 탈출 금지. CSV filePath는 `simulator.files.base-dir` 기준입니다.
- at는 정수 ms 또는 HH:mm:ss(.SSS), type 기본 PUBLISH, repeat={count>=1,everyMs>=0}.
  PUBLISH는 S2 defaults/envelope/header/token 의미를 재사용합니다. MARK는 lastMark를 갱신합니다.
  MOCK_UPDATE는 전체 MockRoute 등록/교체, MOCK_ENABLE/DISABLE은 토글이며 실패 이벤트만 집계하고 계속합니다.
  `mock.MockRouteControl`의 고립 검증과 얇은 facade로 기존 store 동작을 유지합니다.
- CSV_TIMELINE은 필수 header/timeColumn, OFFSET_MS/OFFSET_SEC/날짜 패턴(첫 행 기준), startAt,
  topicColumn/messageIdColumn과 선택 formatFileName을 지원합니다. S1 파서/JSON escape를 공유하고 셀은 리터럴입니다.
  포맷이 없으면 전체 열의 문자열 object를 사용합니다. 시간/파일 위치 인덱스만 저장하고 전송 시 payload를 읽습니다.
  정렬된 인라인/repeat/트랙을 지연 병합하며 같은 at는 정의 순서입니다. 트랙 행 한도 초과는 400, 실행 중 파일 변경은 FAILED입니다.
- run당 가상 스레드/publication scope 한 개. 절대 시각 pacing, 늦으면 생략 없이 따라잡고 최대 lagMs를 노출합니다.
  speed/pause/seek는 기준 시각 재설정, seek로 건너간 이벤트는 skipped에 포함하지 않습니다. 이미 발행 중 한 건은 완료될 수 있습니다.
  뒤로 seek는 Mock 변경을 되돌리지 않으며, seq(성공 전송 순번)/walk는 run 전체 상태를 유지합니다.
  loop 끝에서 loopCount 증가 후 처음부터 재생(0ms loop는 1ms 양보).
- 브로커 연결은 공통 defaults/factory/publisher와 1·2·4·8·16·30초 백오프를 사용합니다.
  SKIP은 단절 중 PUBLISH만 skipped, MARK/MOCK 계속. WAIT는 위치/실패 이벤트 유지 후 이어 재생합니다.
  실패한 시도는 failed, 성공 Mock은 mockOps, 처리 이벤트는 eventsDone에 누적합니다. 일시정지는 재연결도 멈춥니다.
- 상태 RUNNING/PAUSED/RECONNECTING/COMPLETED/STOPPED/FAILED, progress는 현재 루프 위치/길이,
  누적 eventsDone/sent/failed/skipped/mockOps/byTopic과 lastMark/lastError/시각을 제공합니다.
  본문 없는 이벤트 로그는 seq/atMs/type/topic/messageId/result/error, 최근 500개(limit 1~500), 종료 run 최근 50개.
  시작/변경/종료 INFO, 발행 본문은 MessageBodyLogger, 공급자 상세/자격 정보는 상태에서 제외합니다.
- 예시 `scenarios/coil-line-demo.json`과 `csv-track-demo.json`(line1.csv/line1_format.txt).
  저장소 루트에서 CSV 예시는 `--simulator.files.base-dir=.` 필요. local dir/base-dir는 예시 파일 위치에 맞춰 override합니다.
  README와 src/main/부하테스트.http의 시나리오 블록을 함께 유지합니다.
- 전체 `.\mvnw.cmd -B test`: 기존 테스트 + at/병합/CSV/2x(400±100ms)/pause/seek/loop/SKIP/WAIT/실제 MOCK HTTP/CRUD/오류/한도/로그/예시 검증.

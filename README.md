# Digital Twin Data Generator

REST API 요청으로 특정 포맷의 데이터를 대량 생성한 뒤 ActiveMQ Artemis Topic으로 전송하는 시뮬레이터입니다.

| 주요 API | 기능 |
| --- | --- |
| `POST /activemq/request/{taskId}` | 기존 format/value 대량 전송 |
| `POST /activemq/request/file/{taskId}`, `POST /activemq/request/file-data/{taskId}` | 기존 파일 전송 |
| `POST /activemq/request/jms-template-load/{taskId}` | JmsTemplate 부하 테스트 |
| `POST /dt/publish`, `POST /dt/publish/preview` | 실시간 단발 전송 / 생성 미리보기 |
| `POST/GET/DELETE /dt/streams`, `GET/PATCH/DELETE /dt/streams/{id}` | 실시간 스트림 생성·조회·변경·중지 |

## 실행 환경

- Java 21
- Spring Boot 4.1.2
- springdoc-openapi 3.2.0
- Apache Artemis Jakarta client 2.44.0 (`org.apache.activemq`, 운영 브로커 2.44.0과 동일 버전으로 고정)
- Jackson 3 (`tools.jackson.*`, Spring이 제공하는 `JsonMapper` 사용)

JSON 요청/응답의 기존 기본 동작은 `spring.jackson.use-jackson2-defaults=true`로 유지합니다.
이 설정은 Jackson 3에 적용되며, Jackson annotation 패키지는 `com.fasterxml.jackson.annotation`을 유지합니다.
DB 기능은 제외하며 JPA/H2/Postgres 의존성과 H2 콘솔, 개발용 `/test` API는 제거했습니다.
설정은 `application.properties`에 통합되어 있으며 Java 21 virtual threads와 기본 포트 8080을 유지합니다.

## Swagger UI

- http://localhost:8080/swagger
- http://localhost:8080/swagger-ui/index.html

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

## ActiveMQ 기본 설정

요청 body에 `activeMQIp`, `id`, `pw`, `topic`을 넣으면 해당 값이 우선 사용됩니다.
요청 body에서 생략하면 아래 서버 기본값을 사용합니다.

```properties
simulator.activemq.broker-url=tcp://localhost:61616
simulator.activemq.username=artemis
simulator.activemq.password=artemis
simulator.activemq.topic=topic.cep.output.0
simulator.activemq.session-cache-size=100
simulator.activemq.connect-timeout-ms=3000
```

JmsTemplate 부하 테스트도 `activeMQIp`, `id`, `pw`, `topic` override를 동일하게 지원합니다.
공통 `broker` 패키지의 `BrokerSettings`, `BrokerConnectionService`, `MessagePublisher`를 사용하며,
JMS 구현은 task별 connection/session cache를 닫습니다. 발행 계층의 headers는 JMS string property로 설정됩니다.
연결 확인과 전송 factory에 `callTimeout`, `callFailoverTimeout`, `connect-timeout-millis`를 설정하고,
`initialConnectAttempts=0`, `reconnectAttempts=0`으로 재시도를 끕니다.

## 파일 접근 제한과 사전 검증

```properties
simulator.files.base-dir=./data
# application-local.properties에서는 c:/Project
```

`filePath`는 서버의 base-dir 하위만 허용합니다. `filePath="."`는 base-dir 자체이고,
`"samples"`는 base-dir의 samples 디렉터리입니다. 절대 경로는 base-dir 내부일 때만 허용합니다.
`..`로 외부에 접근하거나 다른 드라이브·외부 절대 경로·symlink로 외부 파일을 읽으면 400입니다.
파일 예시는 서버의 base-dir에 파일을 준비한 뒤 실행합니다. local profile에서는 같은 `"."`가 `c:/Project`를 가리킵니다.

비동기 전송 3종은 요청 스레드에서 입력 검증, 파일 읽기, dry-run 1건 생성·JSON 검증 후
브로커 connect/start/close 확인을 수행합니다. 실패하면 task를 시작하지 않습니다.
첫 메시지 이후의 생성·전송 실패는 해당 task를 FAILED로 종료하며 잘못된 JSON은 발행하지 않습니다.
dry-run은 브로커에 연결하지 않습니다.

| HTTP | error | 조건 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 입력, 파일 경로/내용, 생성 JSON 오류 |
| 502 | `BROKER_CONNECTION_FAILED` | 사전 브로커 연결·인증 실패 |
| 409 | `DUPLICATE_TASK_ID` | 실행 중인 ID로 JmsTemplate 부하 테스트 요청 |

오류 응답은 `{"error":"INVALID_REQUEST","message":"<원인>"}` 형식입니다. 400의 `message`에는 고쳐야 할 원인(예: `File path is outside the allowed base directory.`, `데이터 row의 컬럼 수가 ...`)이 들어갑니다(민감값은 마스킹).
성공 응답 문자열 `success : Started 1 tasks with base ID sample-random-1`은 유지합니다.
일반 전송 ID에는 기존 UUID suffix가 붙으며, 실행 중인 JmsTemplate taskId는 원자적으로 중복을 차단합니다.
dry-run과 `LoadTestResultDto` 응답 구조는 유지합니다.

전송 계약은 다음과 같습니다. 아래 사용 예의 `sample-...-1`은 모두 `{taskId}` 값입니다.

| 용도 | 경로 | 예시 taskId |
| --- | --- | --- |
| 일반 랜덤/value | `/activemq/request/{taskId}` | `sample-random-1`, `sample-value-1` |
| 파일 | `/activemq/request/file/{taskId}` | `sample-file-1` |
| 파일+데이터 | `/activemq/request/file-data/{taskId}` | `sample-file-data-1` |

## Profile별 request/response logging

local profile:

```properties
simulator.http-logging.enabled=true
simulator.http-logging.include-request-body=true
simulator.http-logging.include-response-body=true
simulator.http-logging.max-payload-length=8000
logging.level.com.dt.digitaltwinsimulator=DEBUG
```

prod profile:

```properties
simulator.http-logging.enabled=true
simulator.http-logging.include-request-body=false
simulator.http-logging.include-response-body=false
simulator.http-logging.max-payload-length=1000
logging.level.com.dt.digitaltwinsimulator=INFO
```

로그에는 `id`, `username`, `user`, `pw`, `password` 계열 민감값이 마스킹됩니다.
메시지 본문은 DEBUG로 기록하고 `simulator.message-log.sample-every=0`이면 INFO 본문 샘플은 끕니다.
양수 N이면 task별 성공 전송 N건마다 INFO 샘플을 남깁니다. 모든 본문 로그는 `SensitiveLogMasker.mask`를 적용합니다.
task 시작/종료는 INFO로 taskId, 전송 수, 경과 ms, 결과를 요약합니다.

## 공통 요청 필드

| field | 설명 | 예시 |
| --- | --- | --- |
| activeMQIp | ActiveMQ broker URL. 생략 시 서버 기본값 사용 | `tcp://localhost:61616` |
| id | ActiveMQ 계정. 생략 시 서버 기본값 사용 | `artemis` |
| pw | ActiveMQ 비밀번호. 생략 시 서버 기본값 사용 | `artemis` |
| topic | 전송할 topic. 생략 시 서버 기본값 사용 | `topic.cep.output.0` |
| tcName | transaction/message id | `KE2D1Z11` |
| delayTime | 메시지 간 대기 시간(ms) | `2000` |
| targetTps | 목표 TPS. 0이면 제한 없음 | `100` |
| repeatBoolean | 반복 전송 여부 | `true` |
| repeatTime | `messageCount`가 없을 때 전송 지속 시간(ms) | `10000` |
| messageCount | 명시적 생성 횟수. 지정 시 `repeatTime`보다 우선 | `1000` |
| concurrentTasks | 동시 실행 task 수. 1~2000 | `10` |
| typedValues | 일반 format/value 요청 전용. 기본 false: 문자열, true: Integer/Double/Boolean을 JSON number/boolean으로 출력 | `false` |

`targetTps`와 `delayTime`을 함께 주면 둘 다 적용됩니다. TPS 기준 전송을 테스트할 때는 보통 `delayTime=0`을 권장합니다.
`typedValues=true`는 랜덤값과 value row 모두에 적용됩니다. String/Date는 문자열을 유지하고,
타입과 맞지 않는 숫자·boolean 값은 오류입니다. 예: Integer/Double/Boolean 값 `12,2.5,true`는
기본값에서 `"12","2.5","true"`, opt-in에서 `12,2.5,true`로 출력됩니다.

## 1. Dry-run

Dry-run은 ActiveMQ로 전송하지 않고 생성될 메시지 샘플만 반환합니다. `limit`은 최대 100개입니다.
`messages`는 escaped JSON string이 아니라 object 형태로 반환됩니다.

```http
POST http://localhost:8080/activemq/request/file-data/dry-run?limit=5
Content-Type: application/json
```

```json
{
  "tcName": "KE2D1Z11",
  "delayTime": 0,
  "targetTps": 0,
  "repeatBoolean": true,
  "repeatTime": 0,
  "messageCount": 1,
  "filePath": ".",
  "formatFileName": "KE2D1Z11_format.txt",
  "dataFileName": "KE2D1Z11_data.txt",
  "concurrentTasks": 1
}
```

응답 예시:

```json
{
  "requestedLimit": 5,
  "returnedCount": 3,
  "estimatedRepeatCount": 1,
  "estimatedSourceRowCount": 3,
  "estimatedTotalMessagesPerTask": 3,
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

지원 API:

| method | path | 설명 |
| --- | --- | --- |
| POST | `/activemq/request/dry-run?limit=10` | 일반 format/value 기반 생성 결과 확인 |
| POST | `/activemq/request/file/dry-run?limit=10` | 파일 메시지 생성 결과 확인 |
| POST | `/activemq/request/file-data/dry-run?limit=10` | format/data 파일 기반 생성 결과 확인 |

## 2. 포맷 기반 랜덤 대량 데이터 생성

`value`를 비우거나 생략하면 `format` 정의에 맞춰 랜덤 데이터를 생성합니다.

```http
POST http://localhost:8080/activemq/request/sample-random-1
Content-Type: application/json
```

```json
{
  "tcName": "KE2D1Z11",
  "delayTime": 0,
  "targetTps": 100,
  "repeatBoolean": true,
  "repeatTime": 0,
  "messageCount": 1000,
  "concurrentTasks": 1,
  "format": [
    {
      "dataId": "temperature,pressure,status,createdAt,enabled",
      "dataType": "Double,Integer,String,Date,Boolean",
      "randomBoolean": "1,1,1,1,1",
      "randomCondition": "20.0..35.0,900..1100,8,yyyyMMddHHmmssSSS,"
    }
  ],
  "value": []
}
```

### randomCondition 규칙

| type | randomCondition 예시 | 설명 |
| --- | --- | --- |
| String | `10` | 길이 10 랜덤 문자열 |
| String | `5..20` | 길이 5~20 랜덤 문자열 |
| Integer | `100` | 0~100 정수 |
| Integer | `10..20` | 10~20 정수 |
| Double | `100.5` | 0~100.5 실수, 소수점 3자리 반올림 |
| Double | `10.5..20.5` | 10.5~20.5 실수 |
| Boolean | 빈 값 | `true` 또는 `false` |
| Date | 빈 값 | `yyyyMMddHHmmssSSS` |
| Date | `yyyyMMddHHmmss` | 지정한 DateTimeFormatter 패턴 |

## 3. 포맷 + 값 목록 기반 대량 데이터 전송

`value`가 있으면 value row를 format의 `dataId` 순서에 맞춰 메시지로 만듭니다.
`messageCount`는 value row 전체를 몇 번 반복할지 의미합니다.

```http
POST http://localhost:8080/activemq/request/sample-value-1
Content-Type: application/json
```

```json
{
  "tcName": "KE2D1Z11",
  "delayTime": 0,
  "targetTps": 50,
  "repeatBoolean": true,
  "repeatTime": 0,
  "messageCount": 3,
  "concurrentTasks": 1,
  "format": [
    {
      "dataId": "temperature,pressure,status",
      "dataType": "Double,Integer,String",
      "randomBoolean": "0,0,0",
      "randomCondition": "20.0..35.0,900..1100,8"
    }
  ],
  "value": [
    {
      "0": "25.1,1001,OK",
      "1": "25.2,1002,OK",
      "2": "25.3,1003,WARN"
    }
  ]
}
```

## 4. 파일 메시지 반복 전송

서버 로컬 파일의 JSON fragment를 반복 전송합니다.
`repeatBoolean=false`여도 1회 전송합니다.

```http
POST http://localhost:8080/activemq/request/file/sample-file-1
Content-Type: application/json
```

```json
{
  "tcName": "KE2D1Z11",
  "delayTime": 0,
  "targetTps": 100,
  "repeatBoolean": true,
  "repeatTime": 0,
  "messageCount": 100,
  "filePath": ".",
  "fileName": "KE2D1Z11_format.txt",
  "concurrentTasks": 1
}
```

## 5. 포맷 파일 + 데이터 파일 전송

핵심 사용 방식입니다. `KE2D1Z11_format.txt`와 `KE2D1Z11_data.txt`를 읽어서 format 파일의 placeholder를 data 파일 값으로 치환한 뒤 ActiveMQ로 전송합니다.

```http
POST http://localhost:8080/activemq/request/file-data/sample-file-data-1
Content-Type: application/json
```

```json
{
  "tcName": "KE2D1Z11",
  "delayTime": 0,
  "targetTps": 200,
  "repeatBoolean": true,
  "repeatTime": 0,
  "messageCount": 100,
  "filePath": ".",
  "formatFileName": "KE2D1Z11_format.txt",
  "dataFileName": "KE2D1Z11_data.txt",
  "concurrentTasks": 1
}
```

위 예시는 data 파일 전체를 100번 반복해서 전송합니다. data row가 3개이면 총 300개 메시지가 전송됩니다.

### 5-1. 순서 기반 data 파일

format file 예시:

```json
"temperature": "{{temperature}}",
"pressure": "{{pressure}}",
"status": "{{status}}"
```

data file 예시:

```csv
25.1,1001,OK
25.2,1002,OK
25.3,1003,WARN
```

### 5-2. 헤더 기반 data 파일

```csv
status,temperature,pressure
OK,25.1,1001
OK,25.2,1002
WARN,25.3,1003
```

컬럼 순서와 관계없이 placeholder 이름 기준으로 매핑됩니다.

### 5-3. quoted CSV

쉼표가 포함된 값은 큰따옴표로 감싸면 됩니다.

```csv
status,message,temperature
OK,"hello, world",25.1
WARN,"quoted ""text"" sample",25.2
```

placeholder가 JSON 문자열 안에 있으면 값의 따옴표·역슬래시·제어문자를 JSON escape합니다.
문자열 여부는 템플릿의 따옴표와 escape 상태로 판별하며 `"prefix {{x}} suffix"`도 지원합니다.
따옴표 밖의 `{{n}}`은 원문을 삽입하므로 숫자·boolean·JSON object 등을 넣을 수 있으며, 생성 후 JSON을 검증합니다.
예: `"message":"{{message}}","temperature":{{temperature}}`에 `quoted "text",25.1`을 매핑하면
문자열의 따옴표는 escape되고 temperature는 숫자가 됩니다.

### 5-4. file-data 추가 필드 설정

`transaction_code`는 기존처럼 tcName을 사용합니다. 나머지 EAI 고정 필드는
`simulator.message.file-data-extra-fields`의 `LinkedHashMap`으로 관리합니다.
기본값의 순서, 빈 문자열, 공백 한 글자는 기존과 동일하며 출력 바이트를 회귀 테스트합니다.

| 기본 키 (출력 순서) | 기본 문자열 값 |
| --- | --- |
| works_code | `K` |
| sndr_inform_edit_pgm_id | 빈 문자열 |
| eai_interface_id | 빈 문자열 |
| interface_data_dir_actual_type | 빈 문자열 |
| interface_data_ocr_res_flag | 빈 문자열 |
| interface_data_send_seq | `0` |
| interface_data_upd_tp | 빈 문자열 |
| interface_data_t_len | `760` |
| attribute | 공백 한 글자 |
| bsc_gw_data_attr | 공백 한 글자 |
| it_com_eai_ifc_var_item_usg_f | 공백 한 글자 |

키의 underscore를 보존하려면 properties에서 대괄호로 설정합니다. 지정한 키만 override하며 새 키는 뒤에 추가됩니다.

```properties
simulator.message.file-data-extra-fields[works_code]=K
simulator.message.file-data-extra-fields[interface_data_t_len]=760
```

## 6. JmsTemplate 기반 부하 테스트 모드

JmsTemplate 모드는 Spring `CachingConnectionFactory`를 사용해 session을 캐시하고, `workerCount` 작업자가 `messageCount`를 나눠 전송합니다.
응답에는 elapsed time, TPS, 성공 건수, 실패 건수가 포함됩니다.

```http
POST http://localhost:8080/activemq/request/jms-template-load/jms-load-1
Content-Type: application/json
```

```json
{
  "tcName": "KP1D0012",
  "messageCount": 10000,
  "delayTime": 0,
  "targetTps": 100,
  "workerCount": 20,
  "topic": "topic.cep.output.0",
  "payload": "{\"source\":\"jms-template-load\",\"type\":\"LOAD_TEST\"}"
}
```

응답 예시:

```json
{
  "taskId": "jms-load-1",
  "requestedCount": 10000,
  "successCount": 10000,
  "failureCount": 0,
  "elapsedMillis": 100235,
  "actualTps": 99.766,
  "workerCount": 20,
  "targetTps": 100,
  "cancelled": false,
  "message": "completed"
}
```

## 7. Task API

| method | path | 설명 |
| --- | --- | --- |
| GET | `/activemq/task/running-tasks` | 실행 중인 task 목록 조회 |
| POST | `/activemq/task/cancel-tasks` | 전체 task 취소 요청 |
| POST | `/activemq/task/cancel-task/{taskId}` | 특정 task 취소 요청 |

Task 상태 이력 DB 저장 기능과 `/activemq/task/statuses` 계열 API는 제공하지 않습니다.

## 테스트

```bash
./mvnw -B test
```

Windows PowerShell에서는 `.\mvnw.cmd -B test`를 사용합니다. 설치된 Maven으로 `mvn test`도 가능합니다.
테스트는 외부 Artemis 브로커 없이 실행되며, dry-run의 `messages` object 응답도 검증합니다.
브로커 서비스 mock과 닫힌 포트 `tcp://127.0.0.1:1`로 연결 실패를 검증하고 서버 테스트는 RANDOM_PORT를 사용합니다.
경로 제한, JSON escape/전송 전 검증, 400/502/409, EAI 기본 출력 바이트, typedValues를 포함합니다.
Windows에서 symlink 생성 권한이 없으면 해당 테스트만 Assumptions로 skip하며 사유를 결과에 기록합니다.
CI는 GitHub Actions에서 Java 21 기준으로 `mvn -B test`를 수행합니다.

## 실시간 전송

Swagger의 `Realtime` 태그에서 JSON 단발 발행과 여러 지속 스트림을 제어합니다. `topic`, `activeMQIp`, `id`, `pw`는 생략/공백이면 서버 브로커 기본값을 사용합니다.

| method | path | 동작 |
| --- | --- | --- |
| POST | `/dt/publish` | 동기 발행, `count=1`, `intervalMs=0` 기본 |
| POST | `/dt/publish/preview` | 브로커 연결 없이 생성, count 1~10 |
| POST | `/dt/streams` | 생성, 201 + 상태 |
| GET | `/dt/streams`, `/dt/streams/{id}` | 전체/개별 상태 조회 |
| PATCH | `/dt/streams/{id}` | `rateTps`, `paused`, `payload` 변경 |
| DELETE | `/dt/streams/{id}`, `/dt/streams` | 개별/전체 중지, 상태 보존 |

단발 한 건 (`POST /dt/publish`):

```json
{
  "topic": "topic.sensor",
  "messageId": "SENSOR",
  "payload": {"temperature": 23.5, "status": "OK"},
  "standardHeaders": true
}
```

`count`를 주면 여러 건을 순서대로 보내며 `intervalMs`는 각 발행 사이 대기 시간입니다.
성공 응답은 `{"sent":1,"failed":0,"elapsedMillis":12,"topic":"topic.sensor","firstMessage":{...}}`입니다.
count만큼 메시지를 먼저 생성하므로 입력/토큰 오류는 전송 없이 400 `INVALID_REQUEST`와 원인 메시지를 반환합니다. 시각/UUID/walk 값도 이 사전 생성 시점에 결정됩니다.
첫 전송 전 브로커 연결·발행 오류는 기존 `{"error","message"}` JSON과 502를 반환합니다.
1건 이상 성공한 뒤 브로커 오류가 나면 `{"sent":2,"failed":1,"stoppedReason":"BROKER_ERROR","elapsedMillis":12,"topic":"topic.sensor","firstMessage":{...}}`와 **207**을 반환하고 남은 발행을 중단합니다.
207은 일부 발행 완료를 HTTP 상태와 누적 결과로 함께 알리기 위한 선택입니다. 이미 보낸 메시지는 취소되지 않으며, 요청 전체를 다시 보내면 중복될 수 있습니다. 완전 성공은 기존 200과 결과 구조를 유지합니다.
preview는 같은 요청으로 `{"count":1,"topic":"topic.sensor","messages":[{...}]}`를 반환하며 intervalMs 대기를 하지 않습니다.

기본 `envelope="DTCORE"`는 object payload와 messageId를 요구하며 다음 envelope를 만듭니다. 두 시각 문자열은 같습니다.

```json
{"CREATE_TIMESTAMP":"20261108120000000","MESSAGE_ID":"SENSOR","DATA_MAP":{"20261108120000000":{"temperature":23.5,"status":"OK"}}}
```

`envelope="RAW"`는 임의 JSON을 그대로 직렬화하며, 문자열 payload는 JSON 따옴표 없는 문자열 본문으로 보냅니다. messageId는 RAW에서 선택입니다.
`headers`는 문자열 map입니다. `standardHeaders=true`는 schema version(기본 `1`), 메시지별 UUID, UTC ISO-8601 발행 시각을 추가합니다.
**DTCore 규약 `dx-*`는 Artemis 2.44.0 JMS 제약(AMQ139012)으로 `dx_*`로 전달됩니다.** S4에서 `_` 이름이 STOMP 헤더로 그대로 전달됨을 확인했습니다.

| 채널 규약 이름 / API headers 입력 | 실제 JMS string property |
| --- | --- |
| `dx-schema-version` | `dx_schema_version` |
| `dx-message-id` | `dx_message_id` |
| `dx-sent-at` | `dx_sent_at` |

사용자 headers의 위 세 이름도 같은 규칙으로 변환합니다. 나머지 이름은 JMS Java 식별자(예약어와 JMS 접두사 제외)여야 합니다.
표준 헤더를 사용자 값으로 덮어쓰거나 변환 후 이름이 충돌하면 400입니다. DTCore 수신 측도 위 `_` 이름을 사용해야 합니다. 이 작업의 테스트는 fake publisher를 사용합니다.

센서처럼 매초 부드럽게 바뀌는 값 (`POST /dt/streams`):

```json
{
  "streamId": "sensor-1",
  "topic": "topic.sensor",
  "messageId": "SENSOR",
  "standardHeaders": true,
  "rateTps": 1,
  "durationSec": 0,
  "maxMessages": 0,
  "startPaused": false,
  "payload": {
    "seq": "{{seq}}",
    "temperature": "{{walk:20:10..30:0.5}}",
    "status": "{{cycle:OK|OK|WARN}}"
  }
}
```

`streamId` 생략 시 UUID를 생성합니다. id는 영문/숫자/`_.-`로 1~128자입니다.
첫 메시지는 즉시 보냅니다. rateTps는 양의 실수이며 `0.2`는 5초 간격입니다. durationSec/maxMessages의 0은 무한입니다.
durationSec는 일시정지와 재연결 시간을 포함한 경과 초, maxMessages는 성공 전송 수입니다.
같은 id 실행 중이면 409 `DUPLICATE_TASK_ID`, 최대 동시 수 초과는 429 `STREAM_LIMIT_EXCEEDED`, 없는 id는 404 `STREAM_NOT_FOUND`입니다.

`PATCH /dt/streams/sensor-1` 예시:

```json
{"rateTps":10}
```

```json
{"paused":true}
```

```json
{"paused":false,"payload":{"temperature":"{{walk:25:15..35:0.2}}","seq":"{{seq}}"}}
```

변경은 다음 메시지부터 적용됩니다. 이미 진행 중인 발행 한 건은 완료될 수 있습니다. payload 교체는 walk 상태를 초기화하며 seq는 계속 증가합니다.
rate만 변경하면 다음 전송 시각은 **마지막 성공 전송 시각 + 새 주기**이며 이미 지났으면 now입니다. 예를 들어 0.2 → 0.1 TPS로 낮추면 직전 전송에서 10초가 지난 시점까지 기다립니다.
pause는 추가 발행을 만들지 않으며 실제 pause→resume 시 now부터 한 건을 허용합니다. 이미 실행 중일 때 paused=false를 반복해도 예약을 앞당기지 않습니다.
payload만 교체하면 예약 시각을 유지합니다. 발행 도중 rate가 바뀌면 그 발행 완료 시각부터 새 주기를 적용합니다.
종료된 스트림을 PATCH하면 409 `STREAM_NOT_ACTIVE`입니다. `DELETE /dt/streams/sensor-1`로 중지합니다.

| payload 문자열 값 토큰 | 결과 |
| --- | --- |
| `{{seq}}` | 성공 전송 기준 1부터 정수 |
| `{{now}}`, `{{now:yyyy-MM-dd'T'HH:mm:ss}}` | 기본 yyyyMMddHHmmssSSS / 지정 DateTimeFormatter 패턴 문자열 |
| `{{uuid}}` | UUID 문자열 |
| `{{random:Integer:1..10}}`, `{{random:Double:1.5..2.5}}`, `{{random:Boolean:}}` | JSON number/boolean; 조건은 기존 RandomValueGenerator 문법 |
| `{{random:String:8}}`, `{{random:Date:yyyyMMdd}}` | 문자열 |
| `{{walk:20:10..30:0.5}}` | 첫 값 20, 범위 10~30, 직전 성공 값에서 최대 ±0.5 변화하는 실수 |
| `{{cycle:A|B|C}}`, `{{pick:A|B|C}}` | seq 순서 반복 / 무작위 선택 문자열 |

객체·배열에 재귀 적용합니다. 값 전체가 토큰이면 타입을 유지하고 `"sensor-{{seq}}"` 같은 일부 토큰은 문자열로 치환합니다.
알 수 없거나 잘못된 토큰은 요청 시 400입니다. 시각 토큰과 envelope 시각은 서버 로컬 시간이며 dx_sent_at만 UTC입니다.

FILE_DATA 스트림 (`POST /dt/streams`, payload 대신 source):

```json
{
  "streamId": "file-1",
  "topic": "topic.sensor",
  "messageId": "SENSOR",
  "rateTps": 1,
  "source": {"type":"FILE_DATA","filePath":".","formatFileName":"format.txt","dataFileName":"data.csv","loop":true}
}
```

`format.txt` 예시: `"temperature":{{temperature}},"status":"{{status}}"`

```csv
status,temperature
OK,20.1
"WARN, check",20.2
```

헤더/순서 매핑, quoted CSV, 날짜 정규화, JSON 문자열 escape, base-dir/실제 경로 제한은 기존 파일 규칙과 같습니다.
`logic.FileDataTemplate`을 기존 전송/dry-run과 실시간 소스가 공유하며 S1 출력 바이트와 검증 오류 메시지를 유지합니다.
행을 순서대로 보내며 loop는 기본 true, false이면 마지막 행 후 COMPLETED입니다. 파일은 생성 시 한 번 읽고 전체 행을 검증합니다.
파일 행은 기존과 같이 리터럴 데이터이며 값 생성 토큰을 확장하지 않습니다. 기존 EAI 부가 필드는 넣지 않고 실시간 envelope를 사용합니다.
PATCH payload는 파일 소스를 단일 payload 반복으로 교체합니다.

스트림 상태는 다음 필드를 반환합니다:
`streamId,state,topic,messageId,rateTps,sent,failed,actualTps,startedAt,lastSentAt,lastError,elapsedMillis,lagResets,skippedWhileDisconnected`.
state는 RUNNING/PAUSED/RECONNECTING/COMPLETED/STOPPED/FAILED입니다. actualTps는 최근 10초 성공 수를 관측 시간으로 나눈 이동 평균이며 시작 후 10초 미만이면 경과 시간을 사용합니다.
브로커 오류는 스트림을 RECONNECTING으로 전환하고 1·2·4·8·16·30초 백오프로 재연결합니다. 성공하면 RUNNING(일시정지 중이면 PAUSED)으로 돌아옵니다.
failed는 실제 연결/발행 실패 시도 수, skippedWhileDisconnected는 단절 동안 생략한 예약 수이며 일시정지 시간은 제외합니다.
lastError에는 자격 정보나 공급자 오류 상세를 넣지 않습니다. 단절 중 예약을 쌓지 않으며 브로커 발행 결과가 불명확하면 중복 수신 가능성은 있습니다.

스트림별 가상 스레드와 독립 JMS scope를 사용합니다. 절대 시각 pacing으로 드리프트를 피하고 속도 변경 시 마지막 성공 전송에 새 주기를 적용합니다.
느린 발행으로 다음 예약을 놓치면 lagResets를 증가시키고 다음 주기로 기준을 재설정하므로 밀린 메시지를 한꺼번에 보내지 않습니다.
종료된 스트림은 최근 100개만 메모리에 남습니다. DELETE 후 진행 중인 네트워크 정리는 공통 브로커 timeout에 따라 이어지며 앱 종료 시 모든 스트림 scope를 정리합니다.

```properties
simulator.realtime.schema-version=1
simulator.realtime.max-publish-count=1000
simulator.realtime.max-rate-tps=5000
simulator.realtime.max-streams=50
```

신규 테스트는 fake publisher와 닫힌 포트로 실행합니다. 100 TPS × 2초는 180~220건(±10%)을 기준으로 검증합니다.
실제 브로커·UE 수신을 필요로 하는 검증은 별도 통합 범위입니다.

## REST Mock

UE DTCore API 테스트를 위한 `/mock/**` 서빙과 `/mock-admin` 관리 API를 제공합니다.
`DTCoreSettings.TestApiUrl` 또는 `LocalApiUrl`을 `http://<host>:8080/mock`으로 설정합니다.
`mock-routes/*.json`에 경로별 응답·템플릿·지연·순차 응답·오류를 정의하며 호출 기록은 메모리에 보관합니다.
Swagger의 `REST Mock Admin` 태그에서 route CRUD/reload와 DataTable import를 실행할 수 있습니다.
설정·JSON 스키마·UE 연결·테스트 방법은 [REST Mock 가이드](docs/rest-mock.md), 요청 예시는 [rest-mock.http](src/main/rest-mock.http)를 참고하세요.
시작 파일이 잘못되어도 빈 route로 기동하며 `GET /mock-admin/status`에서 route 수와 마지막 로드 결과를 확인합니다. 수동 reload 실패는 400으로 반환하고 기존 route/순번을 유지합니다.
DTCore import는 ApiUrl 앞 `/`를 보완하고 query를 제거하며 잘못된 행을 `invalid`(id·reason)로 보고합니다. 정확 경로, `**` 없는 패턴, `**` 패턴 순으로 우선 매칭합니다.
TRACE route 등록은 400, TRACE 요청은 405입니다. 등록 후 bodyFile 읽기 실패는 500 `MOCK_BODY_FILE_UNREADABLE`과 routeId를 반환합니다.

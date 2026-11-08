# Digital Twin Data Generator

REST API 요청으로 특정 포맷의 데이터를 대량 생성한 뒤 ActiveMQ Artemis Topic으로 전송하는 시뮬레이터입니다.

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
`Accept`에 `text/event-stream`이 있거나 `simulator.http-logging.exclude-paths`에 맞으면
공통 로깅 필터가 요청/응답 로깅과 본문 캐싱을 건너뛰어 실시간 flush를 유지합니다.
`exclude-paths`는 쉼표로 구분한 Ant 패턴 목록이며 기본값은 `/dt/subscriptions/*/stream`입니다.
예: `simulator.http-logging.exclude-paths=/dt/subscriptions/*/stream,/dt/live/**`.
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

## 내장 브로커와 구독

외부 브로커 없이 UE DTCore와 테스트할 때 Artemis **2.44.0** 내장 브로커를 켭니다.
기본/local 모두 `simulator.embedded-broker.enabled=false`이며 Boot Artemis starter 없이
`EmbeddedActiveMQ`를 직접 구성합니다.

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--simulator.embedded-broker.enabled=true"
# 또는
java -jar target/digital-twin-simulator-0.0.1-SNAPSHOT.jar --simulator.embedded-broker.enabled=true
```

| 설정 (`simulator.embedded-broker.` 아래) | 기본값 | 의미 |
| --- | --- | --- |
| `enabled` | `false` | 명시적으로 켬 |
| `use-as-default` | `true` | enabled이며 실제 running일 때 주소 생략 요청의 기본값 |
| `host` | `0.0.0.0` | bind 주소 |
| `core-port` / `stomp-port` | `61626` / `61623` | JMS core / STOMP TCP+WebSocket 공용 |
| `username` / `password` | `artemis` / `artemis` | 허용할 단일 계정 |
| `max-frame-bytes` | `2097152` (2 MiB) | WebSocket 프레임 한도 (STOMP 헤더 포함) |
| `stomp-min-large-message-size` | `102400` | large message 전환 임계 바이트 |
| `max-size-bytes` | `104857600` | 주소별 메모리 한도 |
| `address-full-policy` | `PAGE` | `PAGE` 또는 `DROP` 선택 가능 |

Artemis 기본 65536바이트 WebSocket 프레임 한도로는 1 MiB 단일 프레임을 받을 수 없어 2 MiB로 올렸습니다.
더 큰 PCD에는 STOMP 헤더 여유를 포함해 올리세요.
journal 영속성은 끄며 PAGE/large message용 파일은 OS 임시 디렉터리에 만들고 정상 종료 시 삭제합니다.
JMS property 원문 노출을 막기 위해 내장 브로커 실행 중 Artemis audit logger를 끕니다.
앱 종료 순서는 구독→브로커입니다. 브로커 기동 실패에도 HTTP 앱은 유지되고
`GET /dt/broker`에 `running=false, lastError`가 표시됩니다.

enabled 및 use-as-default가 true이고 실제 running이며 요청의 `activeMQIp`가 생략/공백이면 일반/file/file-data 전송,
JmsTemplate 부하 테스트, 구독 및 `BrokerConnectionService.resolve`를 사용하는 API가
내장 core 주소와 계정을 기본값으로 사용합니다. 요청 `id/pw`는 각각 override할 수 있습니다.
명시 URL은 기존 외부 기본 계정을 사용하므로 필요한 `id/pw`도 함께 지정하세요.
내장 브로커 기동 실패/중지 시 외부 기본 주소와 계정으로 fallback합니다.
`GET /dt/broker`의 `defaultTarget`(`EMBEDDED` 또는 `EXTERNAL`)으로 현재 기본 전송 대상을 확인합니다.

UE DTCoreSettings 예시 (위에서 설정한 계정 입력):

```ini
WebSocketUrl=ws://<PC IP>:61623
Login=<설정값>
Passcode=<설정값>
```

다른 PC의 UE에는 시뮬레이터 PC 방화벽 TCP 61623을 허용합니다.
HTTP API/SSE는 기본 8080, 외부 JMS 클라이언트는 61626입니다.
0.0.0.0은 bind용이며 UE에는 실제 PC IP를 넣습니다.
상태 API는 요청 host 기준 URL, dtcoreSettingsHint, 주소별 routingTypes/messageCount/consumerCount와
대용량 settings를 반환하며 비밀번호는 포함하지 않습니다.

**2.44.0 검증 계약:** JMS topic `topic.cep.output.0`과 STOMP `destination:topic.cep.output.0`은
같은 주소이며 MULTICAST 기본, prefix 불필요입니다. `/topic/`을 임의로 붙이지 마세요.
JMS `dx_schema_version` string property는 동일 STOMP 헤더로 전달됩니다.
`dx-schema-version`은 Jakarta JMS client가 `AMQ139012` (유효한 Java identifier 아님)로
전송 전 거부하므로 공통 전송 헤더는 underscore 형식을 사용합니다.
STOMP SEND에 `content-length`가 없으면 TEXT, 있으면 BYTES로 수신됩니다.
UTF-8 텍스트도 content-length가 있으면 BYTES입니다. WebSocket text/binary 프레임 종류와 별개이므로
DTCore의 실제 STOMP 헤더에 맞춰 확인하세요.

| HTTP | 경로 | 결과 |
| --- | --- | --- |
| GET | `/dt/broker` | disabled일 때도 상태 제공 |
| POST | `/dt/subscriptions` | 201 및 Location |
| GET | `/dt/subscriptions` | 목록, 상태, 누적 수/마지막 수신/오류 |
| GET | `/dt/subscriptions/{id}/messages?limit=100&messageId=UE-1` | 일치하는 최근 limit개, seq 오름차순 |
| GET | `/dt/subscriptions/{id}/messages?limit=100&afterSeq=42&messageId=UE-1` | seq>42부터 일치하는 limit개, seq 오름차순 |
| GET | `/dt/subscriptions/{id}/stream` | SSE 신규 기록 |
| DELETE | `/dt/subscriptions/{id}/messages` | 버퍼 비움, 204 |
| DELETE | `/dt/subscriptions/{id}` | 구독/연결 제거, 204 |

```http
POST http://localhost:8080/dt/subscriptions
Content-Type: application/json

{
  "subscriptionId": "ue-sensors",
  "topic": "dt.sensor.#",
  "bufferSize": 1000,
  "maxBodyBytes": 65536
}
```

topic은 필수입니다. subscriptionId 생략 시 UUID, 지정 시 영문자/숫자/`_.-` 1~128자입니다.
`activeMQIp/id/pw`는 전송과 같은 override입니다.
내장 브로커 wildcard는 점 구분: `*`는 한 단어, `#`는 0개 이상의 단어
(예: `dt.sensor.*`, `dt.sensor.#`). 외부 브로커에서는 해당 브로커 설정을 따릅니다.
non-durable topic consumer이므로 연결 전/재연결 중 메시지를 복원하지 않습니다.
연결 손실 시 RECONNECTING/마지막 오류를 표시하고 1→2→4→8→16→30초 간격으로 재연결합니다.
연결 시 CONNECTED, 삭제/종료 시 STOPPED로 닫은 뒤 목록에서 제거합니다.

bufferSize 기본 1000, 허용 1~`simulator.subscribe.max-buffer`(기본 10000),
동시 구독은 `simulator.subscribe.max-subscriptions`(기본 20)까지입니다.
오래된 기록부터 버리며 버퍼를 비워도 receivedCount/seq는 계속 증가합니다.
조회 limit 기본 100, 허용 1~max-buffer입니다. afterSeq를 생략하면 최근 limit개를 seq 오름차순으로 반환합니다.
afterSeq를 지정하면 초과(>)하는 seq부터 limit개를 반환합니다(`afterSeq=0`이면 보관된 처음부터).
messageId 필터는 두 경우 모두 limit 적용 전에 적용합니다.
중복 ID 409 `DUPLICATE_SUBSCRIPTION_ID`, 한도 초과 429 `SUBSCRIPTION_LIMIT_EXCEEDED`,
없는 ID 404 `SUBSCRIPTION_NOT_FOUND`, 입력 오류 400, 연결 실패 502입니다.
오류 JSON은 `{"error","message"}` 형식입니다.

기록: `seq, receivedAt, topic, type, encoding, sizeBytes, body, bodyJson, bodyBase64Head, messageId, properties, truncated`.
TEXT는 UTF-8 바이트 기준 maxBodyBytes(기본 65536, 양수)까지 미리보기를 보관합니다.
완전한 JSON이면 bodyJson을 JSON 값으로 제공하며 최상위 MESSAGE_ID를 messageId로 추출합니다.
잘린 TEXT는 bodyJson=null로 두어 제한을 우회하지 않습니다.
TEXT의 encoding은 UTF8_TEXT입니다. content-length가 있는 STOMP JSON SEND도 BYTES로 유지하되,
maxBodyBytes까지의 미리보기가 유효한 UTF-8이면 encoding=UTF8_TEXT와 body를 제공합니다.
잘리지 않은 JSON은 bodyJson과 최상위 MESSAGE_ID의 messageId도 제공하여 필터로 조회할 수 있습니다.
잘린 BYTES는 bodyJson/messageId=null이며 끝의 불완전 UTF-8 문자는 제외합니다.
UTF-8이 유효하지 않은 BYTES(PCD 등)는 encoding=BINARY와 앞 256바이트 bodyBase64Head만 제공합니다.
바이너리 head는 maxBodyBytes가 256 미만이어도 256바이트까지 유지합니다. 미리보기가 짧으면 truncated=true입니다.
다른 JMS 메시지는 OTHER/encoding=BINARY입니다. password/passcode/token/secret/authorization/API key 등 민감 property는
마스킹하며 메시지 본문은 UE 디버깅용 원문 미리보기입니다.

```bash
curl -N http://localhost:8080/dt/subscriptions/ue-sensors/stream
```

SSE는 ready 이벤트 뒤 새 기록을 `event: message`, `id: <seq>`로 보내고 15초마다 keepalive를 보냅니다.
기존 기록 재생/Last-Event-ID 복원은 하지 않으며 누락분은 messages?afterSeq=로 조회합니다.
느린 SSE 대기열이 min(bufferSize,256)을 넘으면 닫기 전에 아래 이벤트를 best-effort로 보내고,
본문 없이 구독 id만 포함한 WARN 한 줄을 남깁니다. 소켓이 막혔거나 끊겼으면 이벤트 전달을 보장하지 않습니다.

```text
event: overflow
data: {"reason":"SLOW_CONSUMER","dropped":3}
```

dropped는 버린 대기 기록과 거부된 1건의 합이며 이미 전송 중인 기록은 제외합니다.
느린 SSE는 끊기므로 마지막으로 받은 seq를 `messages?afterSeq=<seq>&limit=100`에 지정해 이어서 조회합니다.
복구는 메모리에 남은 구독 버퍼 범위까지입니다.
공통 RequestResponseLoggingFilter의 Accept/설정 경로 제외로 SSE 응답 버퍼링을 방지합니다.

`.\mvnw.cmd -B test`는 외부 브로커 없이 임의 포트 내장 브로커와 JDK 원시 WebSocket STOMP로
양방향 본문/헤더, 1 MiB 단일 바이너리 프레임, CRUD/버퍼/마스킹/SSE/재연결/포트 충돌을 검증합니다.
근거: [Artemis 2.44.0 STOMP](https://github.com/apache/artemis/blob/2.44.0/docs/user-manual/stomp.adoc),
[2.44.0 wildcard](https://github.com/apache/artemis/blob/2.44.0/docs/user-manual/wildcard-syntax.adoc).

## REST Mock

UE DTCore API 테스트를 위한 `/mock/**` 서빙과 `/mock-admin` 관리 API를 제공합니다.
`DTCoreSettings.TestApiUrl` 또는 `LocalApiUrl`을 `http://<host>:8080/mock`으로 설정합니다.
`mock-routes/*.json`에 경로별 응답·템플릿·지연·순차 응답·오류를 정의하며 호출 기록은 메모리에 보관합니다.
Swagger의 `REST Mock Admin` 태그에서 route CRUD/reload와 DataTable import를 실행할 수 있습니다.
설정·JSON 스키마·UE 연결·테스트 방법은 [REST Mock 가이드](docs/rest-mock.md), 요청 예시는 [rest-mock.http](src/main/rest-mock.http)를 참고하세요.
시작 파일이 잘못되어도 빈 route로 기동하며 `GET /mock-admin/status`에서 route 수와 마지막 로드 결과를 확인합니다. 수동 reload 실패는 400으로 반환하고 기존 route/순번을 유지합니다.
DTCore import는 ApiUrl 앞 `/`를 보완하고 query를 제거하며 잘못된 행을 `invalid`(id·reason)로 보고합니다. 정확 경로, `**` 없는 패턴, `**` 패턴 순으로 우선 매칭합니다.
TRACE route 등록은 400, TRACE 요청은 405입니다. 등록 후 bodyFile 읽기 실패는 500 `MOCK_BODY_FILE_UNREADABLE`과 routeId를 반환합니다.

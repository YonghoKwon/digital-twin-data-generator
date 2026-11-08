# Digital Twin Data Generator

REST API 요청으로 특정 포맷의 데이터를 대량 생성한 뒤 ActiveMQ Artemis Topic으로 전송하는 시뮬레이터입니다.

## 실행 환경

- Java 21
- Spring Boot 4.1.2
- springdoc-openapi 3.2.0
- Apache Artemis Jakarta client 2.57.0 (`org.apache.artemis`)
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

오류 응답은 `{"error":"INVALID_REQUEST","message":"Invalid input or file content."}` 형식입니다.
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

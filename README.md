# Digital Twin Data Generator

Digital Twin(UE DTCore) 테스트용 데이터 시뮬레이터입니다. 특정 포맷의 데이터를 생성해 ActiveMQ Artemis Topic으로 단발·지속·대량 전송하고, UE가 보내는 메시지를 구독해 확인하며, UE가 호출할 REST API를 Mock으로 제공합니다. 외부 브로커 없이 쓸 수 있도록 Artemis 2.44.0 내장 브로커(STOMP-WebSocket)도 켤 수 있습니다.

| 주요 API | 기능 |
| --- | --- |
| `POST /activemq/request/{taskId}` | 기존 format/value 대량 전송 |
| `POST /activemq/request/file/{taskId}`, `POST /activemq/request/file-data/{taskId}` | 기존 파일 전송 |
| `POST /activemq/request/jms-template-load/{taskId}` | JmsTemplate 부하 테스트 |
| `POST /dt/publish`, `POST /dt/publish/preview` | 실시간 단발 전송 / 생성 미리보기 |
| `POST/GET/DELETE /dt/streams`, `GET/PATCH/DELETE /dt/streams/{id}` | 실시간 스트림 생성·조회·변경·중지 |
| `GET /dt/broker` | 내장 Artemis 2.44.0 상태, UE `WebSocketUrl` 안내 |
| `POST/GET /dt/subscriptions`, `GET /dt/subscriptions/{id}/messages`, `GET /dt/subscriptions/{id}/stream` | 토픽 구독(UE가 보낸 메시지 확인), SSE 실시간 보기 |
| `/mock/**`, `/mock-admin/**` | UE DTCore REST API Mock 서빙과 route·호출 기록 관리 |
| `GET /dt/scenarios`, `GET/PUT/DELETE /dt/scenarios/{file}`, `POST /dt/scenarios/validate` | 시나리오 파일 라이브러리·검증 |
| `POST/GET/DELETE /dt/scenario-runs`, `GET/PATCH/DELETE /dt/scenario-runs/{runId}`, `GET /dt/scenario-runs/{runId}/events` | 시나리오 재생·제어·진행 상태·이벤트 로그 |

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

## 웹 UI

서버 실행 후 **http://localhost:8080/ui/** 를 엽니다. `/`는 `/ui/`로 이동하며 `/swagger`를 제공합니다.

- HMI 회색 패널, 그룹 박스, 30px 표 행과 상태 램프를 사용합니다. Bahnschrift/DIN과 맑은 고딕을 사용하며 주간·야간·시스템 테마를 지원합니다. 1024px 미만에서는 1단 화면과 가로 메뉴로 전환합니다.
- 상태 띠는 브로커 대상·WebSocket 주소 복사, 스트림·구독 수신률·시나리오 진행률·Mock 수를 3초마다 갱신합니다. 경보를 선택하면 해당 화면으로 이동합니다. 전체 정지는 확인 후 스트림, 시나리오, 기존 task 순으로 요청하고 결과를 함께 알립니다.
- `Ctrl+K` 또는 `/`로 명령 검색, 화살표와 Enter로 실행, `1`~`7`로 화면 이동, `Ctrl+Enter`로 현재 폼 주 동작, `?`로 단축키 목록을 엽니다. 숫자와 `/` 단축키는 입력 중에 화면을 이동하지 않습니다. Esc는 대화상자를 닫습니다.
- 개요에서 브로커와 address별 구독자 수를 확인하고 `SIM_PING` 왕복 시간을 측정합니다. 구독자 수에는 시뮬레이터 구독이 포함됩니다. 기본 Topic은 `dt.ping`이며 지정 Topic에 임시 구독을 만든 뒤 테스트 후 삭제합니다. UE 수신 여부는 UE에서 별도로 확인합니다. DTCoreSettings의 WebSocketUrl과 TestApiUrl을 복사할 수 있습니다.
- 보내기와 스트림은 Topic·Message ID 자동 완성, JSON 정렬·오류 줄/열·토큰 삽입, 기본 Payload 프리셋 3종과 사용자 프리셋 저장·불러오기·삭제를 지원합니다. 브로커 addresses, 스트림·구독, 시나리오 정의와 최근 입력을 제안합니다.
- 스트림은 JSON/FILE_DATA 생성, 면/표 전환, 실제 TPS와 최근 60초 스파크라인, 속도 입력·슬라이더·반감/배증, 일시정지·재개·중지·Payload 교체를 지원합니다. 복제는 이 브라우저에서 시작해 원본 설정이 보관된 스트림에 제공됩니다. 조회 API는 원본 payload/source를 제공하지 않습니다.
- 수신 화면은 구독 목록과 시각·Topic·MESSAGE_ID·크기·형식 표, 본문 접기와 JSON 정렬·복사, properties 표, Topic 부분 검색과 MESSAGE_ID 완전 일치 필터를 제공합니다. 최신 따라가기와 화면 일시정지를 별도로 제어합니다. 화면 보관은 최대 500건이며 Topic별 보관 수를 표시합니다. SSE overflow/재연결은 `afterSeq`로 이어받고 제거된 구간을 경고합니다. 화면 일시정지 중에도 수신은 계속합니다.
- REST는 route 편집·사용 토글·파일 reload·DataTable import와 호출 기록을 제공합니다. 선택 route의 경로 변수 입력 후 실제 `/mock/...` 요청을 실행해 상태·지연·응답을 확인합니다. 호출 기록 자동 새로고침을 끌 수 있고 4xx/5xx 행은 경고/경보로 표시합니다. 메모리 수정은 reload 시 파일 내용으로 대체됩니다. route 실행 seq는 조회 API에서 제공하지 않습니다.
- 시나리오는 라이브러리 바로 실행, JSON 검증 오류 목록, 저장·삭제, 배속·탐색·반복 누적값과 이벤트 로그를 제공합니다. 타임라인은 Topic/트랙별 눈금, Mock 마름모, MARK 라벨과 재생 위치를 표시하며 클릭하면 seek합니다. CSV 미래 행의 위치는 API에서 제공하지 않아 트랙 구간과 처리 로그를 표시합니다. 반복 눈금은 정의별 최대 2000개입니다. 원본 정의가 없는 run은 처리 로그를 사용합니다. 오류 행 이동은 검증 메시지의 필드와 현재 편집기의 줄을 대응하는 보조 기능입니다. S5 API가 404이면 기능 미탑재 안내를 표시합니다.
- 마지막 화면, 폼 초안, 선택 구독·스트림과 사용자 프리셋은 localStorage에 저장합니다. 브로커 연결 입력은 저장 대상에서 제외하고 `pw`, password/token/authorization 등 민감 필드는 중첩 JSON 문자열 안에서도 제거합니다. JSON 초안은 문법이 정상인 값만 저장합니다. 최근 단발 요청은 최대 10개입니다. 제거된 민감 값은 복원 후 다시 입력합니다.
- 삭제·비우기·정지는 대상 이름을 표시하는 dialog로 확인합니다. 서버에서 시작한 전송 작업은 메뉴 이동 후에도 실행되므로 상태 띠나 관리 화면에서 정지합니다. 화면 전환 시 해당 화면의 요청·polling·SSE를 정리하고 숨겨진 브라우저에서는 polling을 멈춥니다. 스트림/run은 1초, 구독 목록·Mock 기록·기존 task는 3초 주기입니다.

외부 CDN·npm·추가 빌드 없이 같은 서버의 ES modules를 사용합니다. 정적 파일은 `spring.web.resources.cache.cachecontrol.no-cache=true`로 매번 재검증합니다. UI 서빙 회귀 테스트는 `./mvnw -B test`에 포함됩니다. Node 22에서는 `node --test src/test/ui/*.test.mjs`와 모든 UI JS의 `node --check`를 실행합니다.

## 실행

기본 실행:

```bash
mvn spring-boot:run
```

실행 후 웹 운영 화면: http://localhost:8080/ui/

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

## 시나리오 재생

Swagger `Scenarios`에서 센서·알람·REST Mock 변경을 한 파일로 재생합니다. 예시는
`scenarios/coil-line-demo.json`(외부 파일 없음)과 `scenarios/csv-track-demo.json`입니다.

```properties
simulator.scenario.dir=./scenarios
# local profile: c:/Project/scenarios
simulator.scenario.max-runs=10
simulator.scenario.max-track-rows=1000000
```

라이브러리는 dir 바로 아래 `*.json`만 읽고 저장하며 경로/외부 symlink 탈출을 차단합니다.
GET 목록은 `{file,scenarioId,name,description,eventCount,trackCount,durationMs,valid,error}` 배열,
GET 단건은 JSON 원문 object, PUT은 검증 후 원자적 저장과 요약(200), DELETE는 204입니다.
`POST /dt/scenarios/validate`는 `{valid,eventCount,durationMs,topics,errors}`를 반환합니다.
유효하지 않은 정의도 HTTP 200으로 여러 오류를 수집하며, 저장과 실행 시에는 400으로 거부합니다.
JSON 문법 자체가 잘못된 요청은 400입니다. 검증은 발행/Mock route 변경을 하지 않습니다.

```json
{
  "scenarioId":"small-demo", "speed":1, "loop":false, "onDisconnect":"SKIP",
  "defaults":{"topic":"dt.sensor","messageId":"SENSOR","envelope":"DTCORE"},
  "events":[
    {"at":0,"payload":{"temperature":"{{walk:25:20..30:0.5}}","seq":"{{seq}}"},"repeat":{"count":3,"everyMs":1000}},
    {"at":"00:00:02.000","type":"MARK","label":"End"}
  ]
}
```

`at`는 0 이상의 정수 ms 또는 `HH:mm:ss`/`HH:mm:ss.SSS`입니다.
`repeat={count,everyMs}`는 at부터 count번(1 이상), everyMs 간격(0 이상)으로 반복합니다.
마지막 at가 `durationMs`이며 eventCount/eventsTotal은 repeat와 트랙 행을 포함합니다.
PUBLISH의 topic/messageId/envelope/headers/standardHeaders는 이벤트 값, defaults 순으로 적용하고
나머지는 S2 기본값(DTCORE, standardHeaders=false, 서버 topic)을 사용합니다.
DTCORE는 messageId와 object payload가 필요하고 RAW는 임의 JSON을 지원합니다.
S2 토큰, 표준 헤더의 underscore 변환과 JMS header 검증을 그대로 사용합니다.
`seq`는 run 전체의 성공 전송 순번이며 loop·seek 때에도 계속 증가하고 walk 상태도 유지합니다.
`MOCK_UPDATE`는 routeId와 일치하는 전체 MockRoute를 등록/교체하고,
`MOCK_ENABLE`/`MOCK_DISABLE`은 기존 route를 켜고 끕니다. 없는 route의 토글은 해당 이벤트만 실패합니다.
`MARK`는 lastMark를 변경하며 발행하지 않습니다. MOCK 변경은 실제 메모리 route에 남습니다.

실행은 `POST /dt/scenario-runs`에 다음을 보냅니다(file과 inline scenario 중 정확히 하나).

```json
{"runId":"demo-1","file":"coil-line-demo.json","speed":5,"loop":false,"startAtMs":0,"startPaused":false}
```

201로 상태를 반환합니다. `speed` 범위는 0.1~100이고 실행 요청의 speed/loop가 파일 값을 덮어씁니다.
시나리오 최상위 activeMQIp/id/pw는 공통 브로커 기본값/내장 브로커 선택 규칙을 따릅니다.
`PATCH /dt/scenario-runs/demo-1`은 `{"speed":2}`, `{"paused":true}`, `{"paused":false}`,
`{"seekMs":3000}` 또는 이들의 조합을 지원합니다. 위치는 0..durationMs이며 seek 지점과 같은 at는 재생하고,
건너간 이벤트는 skipped에 넣지 않습니다. 뒤로 seek하면 Mock 부작용을 되돌리지 않고 이후 이벤트를 다시 처리합니다.
배속·pause·seek는 현재 위치에 기준 시각을 재설정합니다. 이미 발행 중인 한 건은 완료될 수 있습니다.
DELETE 단건은 종료된 run에도 STOPPED 상태를 반환하며 반복 호출할 수 있습니다. DELETE 목록은 전체 중지 후 상태 배열을 반환합니다.
종료 run PATCH/실행 중 중복 ID는 409, 최대 동시 수는 429, 없는 파일/run은 404이며
입력 오류 400을 포함해 오류는 `{error,message}`입니다. 실행 중 브로커 장애는 RECONNECTING으로 처리합니다.

상태는 `runId,scenarioId,name,state,speed,loop,positionMs,durationMs,progress,loopCount,eventsTotal,eventsDone,
sent,failed,skipped,mockOps,byTopic,lastMark,lastError,startedAt,lastEventAt,lagMs`입니다.
state는 RUNNING/PAUSED/RECONNECTING/COMPLETED/STOPPED/FAILED,
progress는 현재 루프의 위치/길이(0~1), lagMs는 최대 발행 지연 ms입니다.
loopCount는 완료 후 되감은 횟수, eventsDone/sent/failed/skipped/mockOps/byTopic은 run 전체 누적입니다.
failed는 실패한 발행/연결 시도와 Mock 실패, mockOps는 성공한 Mock 이벤트 수입니다.
`GET /dt/scenario-runs/demo-1/events?limit=50`은 본문 없는
`{seq,atMs,type,topic,messageId,result,error}` 배열이며 result는 SENT/FAILED/SKIPPED/MOCK_OK/MOCK_FAILED/MARK입니다.
최근 이벤트 500개(limit 1~500), 종료 run 50개만 메모리에 보관하고 재시작 시 초기화합니다.

run별 가상 스레드와 독립 publication scope 한 개를 사용합니다. 절대 시각 pacing으로 드리프트를 피하고,
늦으면 순서대로 따라잡으며 이벤트를 생략하지 않습니다. 동시 at는 정의 순서(인라인 다음 트랙/행 순서)로 처리합니다.
repeat와 트랙을 이터레이터로 병합합니다. loop는 마지막 처리 뒤 처음부터 시작하며 길이 0인 루프는 1ms 양보합니다.
브로커 재연결은 S2와 같은 1·2·4·8·16·30초 백오프입니다.
SKIP은 실패한 발행을 failed로 집계하고 단절 중 도래한 PUBLISH만 skipped로 집계하며 MARK/MOCK는 계속합니다.
WAIT는 실패 이벤트와 위치를 유지하고 재연결 후 같은 이벤트부터 이어갑니다. 성공 전까지 seq/walk는 확정하지 않습니다.
일시정지는 재연결 시도도 멈춥니다. 공급자 오류/자격 정보는 상태에 노출하지 않으며 본문 로그는 MessageBodyLogger를 사용합니다.

CSV_TIMELINE 트랙에는 trackId/type/filePath/dataFileName/timeColumn을 지정합니다.
첫 유효 행은 고유한 열 이름의 header이고, 시간은 OFFSET_MS(기본), OFFSET_SEC(소수 초 가능), 날짜 패턴을 지원합니다.
날짜 패턴은 첫 데이터 행 기준 상대 시각이며 startAt(기본 0)을 더합니다. 값은 음수가 될 수 없습니다.
행의 topicColumn/messageIdColumn을 선택할 수 있고 없으면 트랙 값/defaults를 사용합니다.
formatFileName을 주면 S1 FILE_DATA placeholder/header/quoted CSV/날짜 정규화/JSON escape로 payload를 만들고,
생략하면 모든 CSV 열을 문자열 값으로 가진 object를 만듭니다. 파일 값의 생성 토큰은 리터럴로 유지합니다.
트랙 파일은 기존 simulator.files.base-dir 제한을 따릅니다. 전체 CSV payload를 적재하지 않고
시간/파일 위치 인덱스만 정렬하며, 행은 전송 시 읽습니다. 트랙마다 최대 max-track-rows(기본 1,000,000)를 넘으면 400입니다.
실행 중 파일 변경은 FAILED를 일으키므로 새 run으로 다시 검증합니다.

저장소 루트에서 CSV 예시를 실행하려면 `--simulator.files.base-dir=.`을 지정합니다.
기본 라이브러리 `./scenarios`와 트랙 filePath=`scenarios`가 모두 저장소의 scenarios를 가리킵니다.
local profile에서는 기본 라이브러리 `c:/Project/scenarios`로 예시를 복사하거나 dir을 override하고,
트랙이 실제 저장된 위치에 맞춰 base-dir도 지정해야 합니다.

```powershell
.\mvnw.cmd -B package
java -jar target/digital-twin-simulator-0.0.1-SNAPSHOT.jar --simulator.embedded-broker.enabled=true --simulator.files.base-dir=.
```

`src/main/부하테스트.http`의 시나리오 블록은 구독 → validate → 실행 → 상태/메시지/REST 확인 순서입니다.
전체 게이트는 기존 `.\mvnw.cmd -B test`입니다. 신규 테스트는 RANDOM_PORT/MockMvc와 fake publisher를 쓰며,
2x 타이밍은 시나리오 800ms를 실제 400ms ±100ms로 검증합니다. Windows symlink 권한이 없으면 해당 검사만 skip합니다.

# REST Mock

시뮬레이터 안에서 UE DTCore `UDxApiSubsystem`이 호출할 REST 응답을 정의합니다. 외부 REST 서버나 Artemis 브로커 없이 사용할 수 있습니다. route와 호출 기록은 메모리에서 관리합니다.

## 실행과 DTCore 연결

저장소 루트에서 실행하면 `mock-routes/example.json`의 route 3개가 시작 시 로드됩니다.

```powershell
.\mvnw.cmd spring-boot:run
# 병렬 worktree jar 검증용 포트
java -jar target/digital-twin-simulator-0.0.1-SNAPSHOT.jar --server.port=18083
```

UE Project Settings의 DTCoreSettings에 다음 값을 설정합니다.

```ini
[/Script/DTCore.DTCoreSettings]
TestApiUrl=http://127.0.0.1:8080/mock
LocalApiUrl=http://127.0.0.1:8080/mock
```

jar 검증 포트를 사용할 때는 URL의 포트를 `18083`으로 바꿉니다. UE가 다른 컴퓨터에서 실행되면 `127.0.0.1` 대신 시뮬레이터 호스트 IP를 씁니다. `FApiStruct` Row의 `ApiType`을 `Test` 또는 `Local`, `ApiMethod`를 `Get`, `ApiUrl`을 `/api/coil/C001`로 설정하면 `<BaseUrl><ApiUrl>`인 `/mock/api/coil/C001`로 요청합니다. BaseUrl 끝에는 `/`를 붙이지 않고 ApiUrl은 `/`로 시작합니다. `ApiResource`, `ApiAction`, `ApiMessageClass`는 UE의 기존 응답 핸들러 설정을 유지합니다. 아래 예시 body는 데모이므로 실제 핸들러가 기대하는 JSON 필드로 수정해야 합니다.

DTCore는 연결 실패와 5xx에서 재시도하므로 `/api/unstable`의 기본 503으로 재시도 흐름을 확인할 수 있습니다. `responses`로 첫 503 후 200을 정의하면 복구 흐름도 확인할 수 있습니다. 서버는 HTTP status 오류와 지연을 주입하며 TCP 연결 끊김은 주입하지 않습니다.

## 설정

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `simulator.mock.base-path` | `/mock` | 서빙 prefix. 루트 `/`, 패턴, 끝 `/`, `/mock-admin` 하위는 불가 |
| `simulator.mock.routes-dir` | `./mock-routes` | 실행 작업 디렉터리 기준 route 폴더 |
| `simulator.mock.journal-size` | `1000` | 메모리에 보관할 최근 호출 수, 1 이상 |
| `simulator.mock.max-delay-ms` | `60000` | 고정/랜덤 지연의 허용 상한, 0~9223372036854775806 (`max + 1` 오버플로 방지) |

base-path 자체 요청은 route path `/`와 매칭합니다. `/mock-admin`은 base-path와 별도로 고정되어 있습니다. GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS 및 사용자 정의 method를 정확히 구분합니다. HEAD는 body 없이 헤더만 전송하며 204/205/304도 body를 전송하지 않습니다. TRACE route 등록은 400으로 거부하고 TRACE 요청은 405를 반환합니다.

서빙은 OncePerRequestFilter로 처리해 MVC의 자동 OPTIONS 처리를 피합니다. Tomcat의 기본 TRACE 차단을 유지하며 connector/valve로 TRACE를 활성화하지 않습니다. mock 필터도 TRACE를 405로 거부해 요청 헤더 반사를 방지합니다.

## Route JSON

각 `*.json` 파일의 최상위는 단일 route object 또는 route 배열입니다. 폴더가 없으면 빈 상태로 기동합니다. 시작 시 파일 로드/검증에 실패해도 앱은 빈 route로 기동하며 실패 파일명·사유를 ERROR 로그와 `GET /mock-admin/status`에 남깁니다. 최상위 폴더의 `.json` 파일을 파일명 순서대로 읽고, 배열 순서를 등록 순서로 사용합니다. 응답용 JSON 파일은 `bodies/` 같은 하위 폴더에 두어 route 파일로 읽히지 않게 합니다.

```json
{
  "id": "coil-get",
  "method": "GET",
  "path": "/api/coil/{coilId}",
  "status": 200,
  "headers": {"Content-Type": "application/json", "X-Mock": "yes"},
  "body": "{\"coilId\":\"{{path.coilId}}\",\"seq\":{{seq}}}",
  "delayMinMs": 10,
  "delayMaxMs": 30,
  "responses": [
    {"status": 503, "body": "{\"error\":\"warming-up\"}"},
    {"status": 200}
  ],
  "cycle": false,
  "fault": {"errorRate": 0, "errorStatus": 500, "errorBody": "{\"error\":\"injected\"}"},
  "enabled": true
}
```

| 필드 | 규칙 / 기본값 |
| --- | --- |
| `id` | 필수, 고유. 영문/숫자/`_`/`-`/`.` 1~200자 |
| `method` | 필수 HTTP method, 대문자로 정규화. TRACE는 400으로 거부. `*`도 문자 그대로의 method이며 전체 method alias가 아님 |
| `path` | 필수, base-path를 제외한 `/` 시작 경로. `{coilId}` segment 변수와 Ant 형식 `**`(0개 이상 segment), `*` 지원. 쿼리/fragment 제외 |
| `status` | 최종 HTTP status 200~599, 기본 200 |
| `headers` | 응답 헤더 map. 헤더명/제어문자/Content-Type 검증. Content-Length, Transfer-Encoding, Connection은 서버가 관리 |
| `body` | JSON 또는 text를 담은 **문자열**. 미지정 시 `{}`. object 입력은 문자열로 바인딩되지 않아 400 |
| `bodyFile` | body 대신 쓸 UTF-8 상대 파일 경로. routes-dir 내부의 기존 파일만 허용. 절대 경로, 경로 탈출, 외부 symlink/junction 차단. 등록 시 검증 실패는 400, 등록 후 누락/읽기 실패는 500 |
| `delayMs` | 고정 지연, 기본 0. 0~max-delay-ms |
| `delayMinMs`, `delayMaxMs` | 두 값을 함께 지정. 0 ≤ min ≤ max ≤ max-delay-ms, 양 끝 포함 랜덤 지연. delayMs와 동시에 사용 불가 |
| `responses` | 응답 object 목록. 각 object에 status/headers/body 또는 bodyFile/delay 필드 지정 가능. 생략/빈 배열은 route 기본 응답 |
| `cycle` | 기본 false. false면 responses의 마지막 응답 유지, true면 처음부터 반복 |
| `fault` | errorRate 0~1(기본 0), errorStatus 400~599(기본 500), errorBody 문자열(기본 `{"error":"MOCK_FAULT"}`). 선택 응답의 status/body를 확률적으로 교체하며 delay/headers는 유지 |
| `enabled` | 기본 true. false인 route는 매칭에서 제외 |

`responses`의 빠진 필드는 route 값을 상속합니다. headers는 대소문자를 구분하지 않고 합칩니다. body/bodyFile 중 하나를 override하면 두 필드의 기존 설정을 함께 대체합니다. delay 필드 중 하나를 override하면 route delay 설정 전체를 대체하므로 랜덤 delay는 min/max를 모두 지정합니다. fault 응답에도 템플릿을 적용합니다.

매칭 우선순위는 **정확한 경로 → `**` 없는 패턴(`{name}`·`*` 합계가 적은 순) → `**` 패턴(`{name}` 변수가 적은 순) → 등록 순서**입니다. 따라서 `/api/**`를 먼저 등록해도 `/api/{line}/{coil}`이 매칭되면 후자가 이깁니다. 같은 우선순위·개수일 때는 등록 순서를 유지합니다. `**` 패턴끼리는 `*`·`**` 개수로 순서를 바꾸지 않습니다. `**`로 잡힌 부분은 이름이 없으므로 `path.*` 템플릿 변수로 제공되지 않습니다. 매칭 실패는 404입니다.

```json
{"error":"NO_MOCK_ROUTE","method":"GET","path":"/unknown"}
```

route마다 호출 순번을 `AtomicLong`으로 증가시켜 동시 요청에서도 고유한 번호와 responses 위치를 배정합니다. fault가 난 요청도 순번을 소비합니다. 순번은 1부터 시작하고 route PUT/import overwrite/reload에서 초기화됩니다. 활성화 변경은 순번을 유지합니다. POST 상태 변경 예제의 responses는 호출 순서를 표현하며 다른 GET route의 데이터를 변경하는 공유 상태 저장소는 아닙니다.

## Body 템플릿

| 문법 | 값 |
| --- | --- |
| `{{path.coilId}}` | 매칭된 path 변수 |
| `{{query.x}}` | 쿼리 파라미터의 첫 값 |
| `{{header.X-Name}}` | 요청 헤더, 헤더명 대소문자 무시 |
| `{{body.field}}`, `{{body.coil.id}}` | 요청 JSON object의 최상위/점 경로 필드 |
| `{{now}}` | 서버 로컬 시각 `yyyyMMddHHmmssSSS` |
| `{{now:yyyy-MM-dd HH:mm:ss}}` | Java DateTimeFormatter 패턴 |
| `{{uuid}}` | UUID |
| `{{seq}}` | route별 호출 순번 |
| `{{random:Integer:10..20}}` | 기존 RandomValueGenerator의 Integer 범위 문법 |
| `{{random:String:8}}`, `{{random:String:5..10}}` | 영숫자 문자열 길이 |
| `{{random:Double:1.5..3.5}}`, `{{random:Boolean}}` | 실수/불리언 |
| `{{random:Date:yyyyMMdd}}` | 날짜 형식. 기존 int/long/float/number/bool/datetime/timestamp alias도 지원 |

응답 Content-Type에 `json`이 있으면 JSON 문자열 안의 치환 값을 JSON escape합니다. Content-Type 생략 시 body의 첫 공백 제외 문자가 `{` 또는 `[`이면 JSON, 나머지는 text/plain으로 판단합니다. JSON scalar body는 Content-Type을 명시합니다. text 값은 그대로 치환하며 headers는 템플릿 처리하지 않습니다. 모르는 변수, 없는 값, JSON이 아닌 요청의 `body.*`는 빈 문자열이 됩니다. JSON object/array 값은 JSON 문자열로 변환해 치환합니다. 따옴표 밖 숫자/boolean 치환을 쓰면 요청에 해당 값이 있어야 합니다. 최종 body의 유효한 JSON 구성은 route 작성자가 결정하므로 의도적으로 잘못된 응답도 만들 수 있습니다.

```json
{"body":"{\"label\":\"{{query.label}}\",\"count\":{{seq}},\"temperature\":{{random:Integer:10..20}}}"}
```

잘못된 날짜/랜덤 문법은 등록 시 400입니다. bodyFile 내용을 변경하면 다음 호출부터 읽습니다. 변경된 파일의 템플릿 실행 오류는 500 `MOCK_RENDER_ERROR`이며 route id·method·path와 예외 스택을 WARN 로그에 남깁니다. 요청/응답 본문과 헤더 원문은 이 로그에 넣지 않습니다. 등록 후 파일 누락, 읽을 수 없는 UTF-8, 경로 탈출 등은 500 `{"error":"MOCK_BODY_FILE_UNREADABLE","routeId":"해당-id"}`와 WARN 로그를 반환합니다. 관리 API 등록 시의 파일 검증 실패는 계속 400입니다.

## 관리 API

Swagger `/swagger` 또는 `/swagger-ui/index.html`의 **REST Mock Admin** 태그에서 실행할 수 있습니다. 실제 mock 서빙 경로는 Swagger에 노출되지 않습니다.

| Method / path | 동작 |
| --- | --- |
| `GET /mock-admin/status` | 현재 route 수와 마지막 시작 로드/reload 시각·성공 여부·오류 메시지 |
| `GET /mock-admin/routes` | 전체 route 정의 |
| `GET /mock-admin/routes/{id}` | 단일 조회, 없으면 404 |
| `POST /mock-admin/routes` | 추가, 201. 중복 id는 400 |
| `PUT /mock-admin/routes/{id}` | 전체 교체. body id는 path id와 동일해야 함. 없으면 404 |
| `DELETE /mock-admin/routes/{id}` | 삭제, 204 |
| `PATCH /mock-admin/routes/{id}/enabled` | `{"enabled":true}` 또는 false |
| `POST /mock-admin/routes/{id}/toggle` | 현재 enabled 값 반전 |
| `POST /mock-admin/routes/reload` | 파일을 모두 검증한 뒤 전체 route 교체. 실패 시 400, 이전 route/순번 유지 |
| `GET /mock-admin/requests?limit=100` | 최신 완료 호출부터 조회. limit ≥ 0, 최대 journal-size개 |
| `DELETE /mock-admin/requests` | 기록 비우기, 204 |
| `POST /mock-admin/import/dtcore-api-table?overwrite=false` | UE DataTable JSON 배열로 route stub 생성 |

관리 API 변경은 파일에 저장되지 않습니다. 재시작/reload 시 파일 정의로 돌아갑니다. 시작 로드 실패는 빈 route로 기동하고, 수동 reload 실패는 400과 함께 기존 route/순번을 유지합니다. reload 중 요청은 기존 또는 새 snapshot으로 처리합니다. mock 호출은 일반 request/response body logging 필터보다 먼저 처리하며 journal에만 기록합니다. 관리 API 자체는 journal에 기록하지 않습니다.

`GET /mock-admin/status` 응답 예시입니다. `routeCount`는 비활성 route를 포함한 현재 등록 수이며, 나머지는 마지막 파일 로드 시도의 결과입니다. CRUD/import는 이 로드 결과를 바꾸지 않습니다. 파일을 수정하고 reload에 성공하면 `lastReloadSuccessful=true`, `lastReloadError=null`로 바뀝니다. `lastReloadAt`은 시도 시작 시각(UTC ISO)입니다. 폴더가 없을 때의 빈 로드는 성공입니다.

```json
{
  "routeCount": 0,
  "lastReloadAt": "2026-11-08T00:00:00Z",
  "lastReloadSuccessful": false,
  "lastReloadError": "Cannot load route file 'broken.json': invalid route JSON"
}
```

기록 필드는 `timestamp`(UTC ISO), `method`, `path`(base-path 제외), `query`, `headers`, `body`, `routeId`(미매칭이면 null), `status`, `delayMs`(선택된 지연), `elapsedMillis`입니다. 요청 헤더는 처음 32개, 값은 각 512 bytes로 제한합니다. Authorization, Cookie, API key, token/secret/password 관련 헤더는 `****`로 마스킹합니다. query와 body에도 민감 필드 마스킹과 기존 `SensitiveLogMasker.mask(...)`를 적용합니다. body와 query는 각각 UTF-8 4096 bytes까지만 보관하며 잘린 다중 바이트 문자를 남기지 않습니다. journal은 응답 생성 완료 시 기록합니다.

관리 API는 인증을 추가하지 않으므로 로컬 또는 접근을 제한한 개발 네트워크에서 사용합니다.

## DTCore DataTable import

```http
POST /mock-admin/import/dtcore-api-table
Content-Type: application/json

[
  {"Name":"GetCoil","ApiMethod":"EApiMethod::Get","ApiUrl":"api/coil/{coilId}?view=full","ApiResource":"Coil","ApiAction":"Get"},
  {"Name":"SetStatus","ApiMethod":"POST","ApiUrl":"/api/coil/{coilId}/status"},
  {"Name":"BadRoute","ApiMethod":"TRACE","ApiUrl":"/api/trace"}
]
```

필드 이름 대소문자를 무시하고 ApiMethod의 마지막 `::` 앞 enum prefix를 제거합니다. `Name`, `ApiMethod`, `ApiUrl`이 필요합니다. `ApiUrl`은 첫 `?`와 그 뒤 query를 제거하고, 앞 `/`가 없으면 붙여 path만 사용합니다. 빈 path, fragment, 잘못된 method/id/pattern 등은 정규화 후 검증합니다. 다른 필드는 허용하되 서버 매칭에 사용하지 않습니다. `id=dtcore-<Name>`, body 문자열 `{}`, status 200, enabled true로 생성합니다. id 문자 제약은 일반 route와 같습니다.

기존 id(같은 요청에서 이미 성공한 id 포함)는 기본적으로 `skipped`에 넣고, `overwrite=true`일 때 전체 교체합니다. 잘못된 행은 `invalid`의 `{id, reason}`으로 반환하며 다른 유효한 행은 계속 import합니다(HTTP 200). Name이 없거나 object가 아닌 행의 id는 `row-<1부터 시작하는 행 번호>`입니다. 실패한 overwrite는 기존 route를 유지합니다. 최상위가 배열이 아니면 전체 요청을 400으로 거부합니다.

```json
{"imported":["dtcore-GetCoil","dtcore-SetStatus"],"skipped":[],"invalid":[{"id":"dtcore-BadRoute","reason":"TRACE routes are not supported"}]}
```

## 예시와 검증

실행 가능한 요청은 [rest-mock.http](../src/main/rest-mock.http), 기본 route는 [example.json](../mock-routes/example.json)입니다.

```bash
curl -i http://localhost:18083/mock/api/coil/C001
curl -i -X POST http://localhost:18083/mock/api/coil/C001/status -H 'Content-Type: application/json' --data-raw '{"status":"STOPPED"}'
curl -i http://localhost:18083/mock/api/unstable
curl 'http://localhost:18083/mock-admin/requests?limit=10'
curl http://localhost:18083/mock-admin/status
./mvnw -B test
```

신규 테스트는 RANDOM_PORT 컨텍스트에서 MockMvc와 실제 HTTP를 사용합니다. `**` 우선순위, 템플릿 escape/렌더 실패 로그, 응답 순환/동시 순번, fault 0/1, 작은 지연과 경계값, journal 제한/마스킹, bodyFile 경로 탈출/등록 후 읽기 실패, import 정규화/invalid/overwrite, 시작 실패/status/reload 복구, Swagger 노출, TRACE 거부와 나머지 method 처리를 외부 브로커 없이 검증합니다.

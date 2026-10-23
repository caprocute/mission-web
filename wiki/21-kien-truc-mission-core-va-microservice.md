> Nguồn: baseline mục 4 (ADR), mục 5 và 5.1 (SVC, MOD); TL-01 chương "Thiết kế chi tiết từng thành phần", mục "SVC-05 mission-core: lõi Modular Monolith" (Cấu trúc gói Java theo module, Khai báo module bằng Spring Modulith, Quy tắc ArchUnit, Giao tiếp giữa module, Quản lý giao dịch, Dữ liệu sở hữu, Sự kiện phát và nhận, Co giãn, chịu lỗi, bảo mật) và các mục SVC-01 đến SVC-18; TL-01 chương "Độ tin cậy và khả năng chịu lỗi" (cấu hình Resilience4j chuẩn, Timeout mặc định, Idempotency); TL-01 Phụ lục A, ADR-21 đến ADR-24; TL-03 chương "Danh mục microservice, module và kho dữ liệu"; `docs/quy-dinh/BACKEND.md`; `docs/KE-HOACH-TONG-THE-HE-THONG.md` mục 5.

# Kiến trúc mission-core và microservice

Trang này dành cho người viết mã máy chủ. Nó trả lời bốn câu hỏi mà mọi task CORE, DV và NEN đều gặp: mission-core chia gói thế nào, module nào được gọi module nào, mười bảy service còn lại viết bằng gì và sở hữu dữ liệu gì, và giao dịch, sự kiện đi qua những đường nào. Trang không lặp lại phần tổng quan đã có ở [trang 01](01-tong-quan-he-thong.md) (quy mô, NFR, cell) và phần frontend ở [trang 02](02-kien-truc-frontend.md). Thiết kế CSDL và đa khách thuê nằm ở [trang 17](17-thiet-ke-csdl-va-da-khach-thue.md); sự kiện, outbox và đồng bộ nằm ở [trang 18](18-su-kien-va-dong-bo-du-lieu.md).

Trang này được đánh số 21 chứ không phải 16 như thẻ NEN-002 ghi, vì số 16 đã dùng cho trang nội dung giao diện; điểm lệch số trang được ghi ở [trang 14](14-quyet-dinh-va-cau-hoi-mo.md).

## Vai trò của SVC-05 mission-core

ADR-01 chọn kiến trúc lai: phần lõi nghiệp vụ là một khối nguyên khối theo module (Modular Monolith, Spring Modulith), còn những điểm nóng về tải và những vùng bảo mật được tách thành microservice. mission-core (SVC-05) là phần lõi đó. Nó chứa tám module MOD-01 `okr`, MOD-02 `task`, MOD-03 `product`, MOD-04 `appraisal`, MOD-05 `policy`, MOD-06 `dossier`, MOD-07 `oversight` và MOD-08 `config`, xử lý phần lớn request đọc và ghi nghiệp vụ, bảo đảm ACID cho các mốc pháp lý, và phát sự kiện nghiệp vụ qua outbox. Lõi chạy như một tiến trình Spring Boot duy nhất với nhiều replica không trạng thái sau cổng API.

TL-01 nhấn mạnh rằng "một tiến trình" không có nghĩa là "một khối bùn". Mỗi module có ranh giới được kiểm tra tự động, có schema và tài khoản CSDL riêng, có interface công bố và sự kiện công bố riêng. Nhờ vậy, khi một module đạt tiêu chí tách ở TL-01 chương 3, việc tách chỉ là thay cách nối chứ không phải viết lại.

Stack của lõi là Java 21 (Temurin, image distroless), Spring Boot 3.5, Spring Modulith 1.4, Spring Data JPA với Hibernate 6.6, Flyway 11, HikariCP, Resilience4j 2.3, cel-java 0.9, springdoc-openapi 2.8, OpenTelemetry Java agent 2.x, Log4j2 với JSON Template Layout theo ECS, ArchUnit 1.4 và Testcontainers. Luồng ảo của Java 21 được bật cho Tomcat (`spring.threads.virtual.enabled=true`); bulkhead và pool kết nối vẫn là thứ giới hạn số request chạm CSDL cùng lúc.

## Cây gói `vn.viettel.okrkpi`

Gói gốc là `vn.viettel.okrkpi`. Spring Modulith coi mỗi gói con trực tiếp của gói gốc là một module ứng dụng, nên tám module là tám gói con, cộng gói `shared` chứa nhân kỹ thuật dùng chung không có nghiệp vụ. Bên trong mỗi module, mã tổ chức theo kiến trúc lục giác với năm gói con có vai trò cố định: bốn lớp `api`, `application`, `domain`, `infrastructure` và gói `internal`.

```text
vn.viettel.okrkpi
├── MissionCoreApplication.java
├── shared
│   ├── kernel          TenantId, PeriodId, Money, Clock, Result
│   ├── tenant          TenantContext, TenantFilter, TenantAwareDataSource
│   ├── outbox          OutboxWriter, OutboxEvent, EventEnvelope
│   ├── crypto          CryptoProvider, FieldEncryptor, DekCache
│   ├── authz           OpaClient, @RequiresPermission
│   └── error           ProblemDetails theo RFC 9457
├── okr                 MOD-01
├── task                MOD-02
│   ├── api             @NamedInterface("api"): TaskApi, TaskSummary, api/events
│   ├── application     use case (AssignTaskUseCase, UpdateProgressUseCase), port
│   ├── domain          Task, TaskStatus, TaskStateMachine, TransitionTable
│   ├── infrastructure  web (TaskController), persistence (JPA), policy
│   └── internal        tiện ích riêng của module (TaskMapper)
├── product             MOD-03
├── appraisal           MOD-04
├── policy              MOD-05
├── dossier             MOD-06
├── oversight           MOD-07
└── config              MOD-08
```

Gói `api` là bề mặt công bố của module: interface dịch vụ, DTO bất biến dạng `record` và sự kiện công bố. Đây là gói duy nhất module khác được tham chiếu. Gói `application` chứa use case, mỗi use case là một lớp có một phương thức công khai, cùng các cổng ra (port) mà hạ tầng hiện thực; ranh giới giao dịch đặt ở đây. Gói `domain` chứa aggregate, value object, máy trạng thái và quy tắc nghiệp vụ thuần, không phụ thuộc Spring, JPA hay Jackson. Gói `infrastructure` chứa adapter vào (controller REST, listener) và adapter ra (JPA, client gọi service khác). Gói `internal` là tiện ích nội bộ mà không ai ngoài module được dùng.

Hướng phụ thuộc trong module là `infrastructure` → `application` → `domain`; `api` chỉ phụ thuộc kiểu dữ liệu thuần; `domain` không phụ thuộc gì ngoài `shared.kernel`. Giữa các module, module A chỉ được phụ thuộc `B.api`, và không có vòng phụ thuộc nào, kể cả vòng đi qua `api`.

## Khai báo module và ma trận phụ thuộc

Mỗi module khai báo phụ thuộc cho phép trong `package-info.java`, và gói `api` được đánh dấu là interface có tên. Spring Modulith dùng khai báo này để xác minh lúc kiểm thử và để sinh tài liệu kiến trúc.

Bẫy khi viết Javadoc trong mission-core (23/10/2026): `spring-modulith-apt` (kéo theo `spring-modulith-starter-core`) gom Javadoc vào `target/generated-spring-modulith/javadoc.json` của thư mục nơi chạy Maven, và `Documenter` đọc tệp này bằng `BasicJsonParser` của Spring Boot. Bộ đọc đơn giản đó đọc sai, hoặc làm mất khoá, khi Javadoc có dấu ngoặc kép ASCII chứa dấu phẩy, dấu gạch ngược hay ngoặc lệch như khoảng nửa mở `[from, to)`; khi đó `ArchitectureRulesTest` báo `JsonParseException` hoặc `NullPointerException` ở bước sinh tài liệu. Trong Javadoc, dùng ngoặc kép “ ” và diễn đạt khoảng hay ký tự thoát bằng lời. Kiểm thử `javadocDocDuocBangBoDocCuaSpringModulith` so kết quả của bộ đọc đó với Jackson và nêu tên lớp cần viết lại; khi dựng từ gốc `backend` thì tệp không nằm trong `mission-core/target` nên kiểm thử này bỏ qua.

```java
// vn/viettel/okrkpi/task/package-info.java
@org.springframework.modulith.ApplicationModule(
    displayName = "MOD-02 Giao việc và KPI",
    allowedDependencies = { "shared", "config::api", "policy::api", "okr::api" }
)
package vn.viettel.okrkpi.task;

// vn/viettel/okrkpi/task/api/package-info.java
@org.springframework.modulith.NamedInterface("api")
package vn.viettel.okrkpi.task.api;
```

Ma trận dưới đây chép đúng từng dòng bảng phụ thuộc của TL-01. Các module phía trên cung cấp dữ liệu nền, các module phía dưới tiêu thụ; khi module phía trên cần biết điều gì xảy ra ở phía dưới, nó nghe sự kiện thay vì gọi interface, nên chuỗi `config` ← `policy` ← `okr` ← `task` ← `product` ← `appraisal` không bao giờ quay vòng.

| Module | Được phụ thuộc vào `api` của | Nhận sự kiện từ (TL-01) |
|---|---|---|
| MOD-08 `config` | không module nào | EVT-22 từ MOD-07 |
| MOD-05 `policy` | `config` | không |
| MOD-01 `okr` | `config`, `policy` | không |
| MOD-02 `task` | `config`, `policy`, `okr` | EVT-01 từ MOD-01, EVT-18 từ SVC-10 |
| MOD-03 `product` | `config`, `policy`, `task` | không |
| MOD-04 `appraisal` | `config`, `policy`, `task`, `product`, `okr` | EVT-09, EVT-10, EVT-18 |
| MOD-06 `dossier` | `config`, `appraisal` | EVT-12, EVT-17, EVT-18 |
| MOD-07 `oversight` | `config`, `appraisal`, `task`, `product` | EVT-20, EVT-25 |

Cột "Nhận sự kiện" của TL-01 chỉ ghi các sự kiện chính. TL-03 chương "Danh mục microservice, module và kho dữ liệu" liệt kê đầy đủ hơn theo từng module, và đó là danh sách nên dùng khi viết consumer. Theo TL-03, `okr` nhận EVT-09, EVT-14, EVT-15, EVT-22; `task` nhận EVT-01, EVT-09, EVT-15, EVT-18, EVT-22, EVT-24; `product` nhận EVT-06, EVT-09, EVT-22; `appraisal` nhận EVT-09, EVT-10, EVT-15, EVT-18, EVT-22; `policy` không nhận sự kiện nghiệp vụ; `dossier` nhận EVT-12, EVT-17, EVT-18; `oversight` nhận EVT-20, EVT-12; `config` nhận EVT-22. Riêng schema `org_replica` nhận EVT-14, EVT-15, EVT-16. Hai nguồn khác nhau ở chỗ TL-01 cho `oversight` nhận EVT-25 (sự kiện do chính MOD-07 phát) còn TL-03 cho nhận EVT-12; điểm này được ghi ở trang 14.

Về sự kiện phát, TL-03 ghi: `okr` phát EVT-01, EVT-02; `task` phát EVT-03 đến EVT-06; `product` phát EVT-07, EVT-08; `appraisal` phát EVT-11, EVT-12; `policy` phát EVT-13; `oversight` phát EVT-22, EVT-25; `config` phát EVT-09 (theo lệnh của saga SVC-06) và EVT-23; mọi module có thể phát EVT-21 để yêu cầu thông báo; `dossier` phát thêm sự kiện nội bộ `dossier.form.requested.v1` cho SVC-11. Tổng hợp lại đúng với câu của TL-01: lõi phát EVT-01 đến EVT-09, EVT-11, EVT-12, EVT-13, EVT-21, EVT-22, EVT-23, EVT-25.

## Quy tắc kiểm tra kiến trúc

Spring Modulith (`ApplicationModules.of(...).verify()`) xác minh ranh giới module và vòng phụ thuộc. ArchUnit bổ sung các quy tắc bên trong module mà Modulith không kiểm, chạy trong mọi build và làm build thất bại khi vi phạm. TL-01 đặt tám quy tắc: `domainIsPure` (gói `domain` không phụ thuộc Spring, JPA, Jackson, `infrastructure`, `application`), `applicationDoesNotSeeInfrastructure`, `internalIsPrivateToModule` (lớp trong `X.internal` chỉ được lớp trong `X` truy cập), `controllersOnlyInWebAdapter` (mọi `@RestController` nằm trong `infrastructure.web`), `transactionsOnlyInApplication` (chỉ phương thức trong `application` được gắn `@Transactional`), và ba quy tắc chung `noStdStreams`, `noGenericExceptions`, `noJavaUtilLogging`. Giới hạn kích thước lớp 1.000 dòng, hàm 100 dòng và độ phức tạp nhận thức không quá 15 do cổng chất lượng SonarQube kiểm. TL-01 còn nêu hai kiểm tra ArchUnit khác: cấm gọi `DataSource.getConnection()` ngoài lớp hạ tầng chuẩn (chương "Đa khách thuê") và cấm `ExecutorService` không giới hạn ngoài lớp nền (chương "Độ tin cậy và khả năng chịu lỗi").

## Mười bảy service còn lại

Bảng sau tóm tắt các service ngoài mission-core. Cột ngôn ngữ, kho và co giãn lấy từ baseline mục 5; cột sự kiện lấy từ TL-03 chương "Danh mục microservice, module và kho dữ liệu", vì TL-03 ghi đủ hơn baseline. Cụm PostgreSQL ghi theo cách đặt tên năm cụm của TL-03; TL-04 dùng sáu cụm với tên khác, xem [trang 17](17-thiet-ke-csdl-va-da-khach-thue.md).

| Mã | Tên triển khai | Ngôn ngữ, công nghệ | Kho sở hữu | Sự kiện phát chính | Sự kiện nhận chính | Co giãn |
|---|---|---|---|---|---|---|
| SVC-01 | api-gateway | Apache APISIX 3.x, WAF Coraza | etcd; Valkey cho bộ đếm giới hạn | không | không | HPA theo CPU, RPS |
| SVC-02 | iam | Keycloak 26 (Organizations, VNeID) | CSDL `iam`, cụm `pg-iam` | sự kiện đăng nhập cho SOC (xem trang 14) | EVT-14, EVT-16 | HPA |
| SVC-03 | tenant-registry (toàn cục) | Go 1.23 | CSDL `registry` toàn cục | `registry.tenant.changed.v1` | không | HPA |
| SVC-04 | org-service | Java 21, Spring Boot 3.5 | schema `org` trong `td_<mã>` | EVT-14, EVT-15, EVT-16 | không (nhận lệnh SCIM qua API) | HPA |
| SVC-06 | workflow-service | Flowable 7 nhúng Spring Boot, external worker | CSDL `flowable`, cụm `pg-wf` | `workflow.task.created.v1`, `workflow.task.completed.v1` (nội bộ) | EVT-13, EVT-25, yêu cầu bắt đầu luồng | HPA (API), KEDA (async executor) |
| SVC-07 | scoring-service | Java 21, Spring Boot, cel-java, Flowable DMN | schema `scoring` trong `td_<mã>` | EVT-10 | EVT-06, EVT-08, EVT-09, EVT-13, EVT-22 | KEDA theo độ trễ consumer |
| SVC-08 | evidence-service | Java 21, Spring Boot WebFlux, Ceph RGW, ClamAV | schema `evidence` trong `td_<mã>`, bucket S3 | `evidence.object.scanned.v1` (nội bộ) | không | HPA, KEDA (quét, OCR) |
| SVC-09 | ledger-service | Go 1.23 | CSDL `ledger`, cụm `pg-ledger` riêng | EVT-19 | EVT-01..05, 07..09, 11..13, 18, 22, 25 | số replica bằng số phân vùng consumer |
| SVC-10 | esign-service | Java 21, Spring Boot, PKCS#11/HSM | `esign`, cụm `pg-sec` | EVT-18 | EVT-17, yêu cầu ký | HPA, KEDA |
| SVC-11 | document-service | Java 21, docx4j, OpenPDF, Gotenberg | schema `docgen` trong `td_<mã>`, bucket | EVT-17 | `dossier.form.requested.v1` | KEDA |
| SVC-12 | notification-service | Go 1.23 | schema `notify` trong `cell_shared`, Valkey Streams | không | EVT-02, 03, 04, 06, 07, 09, 11, 20, 21, 25 | HPA, KEDA |
| SVC-13 | integration-gateway | Java 21, Spring Boot, Apache Camel | schema `integ` trong `cell_shared` | EVT-24 | EVT-12, EVT-19 | HPA |
| SVC-14 | ai-gateway | Python 3.12, FastAPI, vLLM, pgvector | `ai`, cụm `pg-sec` | không | EVT-24 | HPA theo hàng đợi suy luận |
| SVC-15 | analytics | ClickHouse, Cube, Superset, Python | ClickHouse `analytics_<cell>` | EVT-20 (không qua outbox PostgreSQL) | CDC từ các schema | theo shard ClickHouse |
| SVC-16 | search | OpenSearch 2.x, indexer Kafka | chỉ mục OpenSearch (không phải nguồn sự thật) | không | EVT-04, 05, 06, 14, 24 | theo shard |
| SVC-17 | web-frontend | Angular 21, PrimeNG, Formly, cel-js | không có kho máy chủ | không | EVT-23 (qua CDN) | CDN |
| SVC-18 | mobile-app | Flutter 3 | SQLite mã hoá cục bộ | không | thông báo đẩy từ SVC-12 | không áp dụng |

Bốn microservice SVC-04, SVC-07, SVC-08, SVC-11 đặt schema trong cùng CSDL dữ liệu tenant `td_<mã>` với mission-core, mỗi schema một vai trò riêng; TL-03 giải thích lý do là giảm số slot sao chép logic và giữ giao dịch cục bộ với outbox. Các service vốn phục vụ nhiều tenant cùng lúc (thông báo, tích hợp) dùng CSDL `cell_shared` tách tenant bằng RLS; các service cần cô lập tải hoặc bảo mật (iam, workflow, ledger, esign, ai) có CSDL và cụm riêng.

## Quy tắc giao tiếp

Trong lõi, module giao tiếp theo đúng hai cách. Cách thứ nhất là gọi đồng bộ qua interface công bố trong gói `*.api`, dùng khi bên gọi cần câu trả lời ngay để tiếp tục cùng giao dịch. Ví dụ, khi MOD-02 giao nhiệm vụ, nó gọi `config.api.PeriodApi.assertWritable` để biết kỳ còn mở (phương thức ném `PeriodLockedException` khi kỳ ở `DANG_KHOA` hoặc `DA_KHOA`) và gọi `okr.api.OkrApi` để kiểm KR được liên kết. Đây là lời gọi phương thức Java trong cùng tiến trình, không có chi phí mạng.

Cách thứ hai là sự kiện ứng dụng, dùng khi bên phát không cần biết ai xử lý và việc xử lý có thể diễn ra sau. Listener khai báo bằng `@ApplicationModuleListener`, chạy bất đồng bộ sau khi giao dịch phát commit, trong giao dịch riêng, và được ghi vào Event Publication Registry (bảng `modulith.event_publication`) để thử lại khi thất bại, theo ADR-23. Ví dụ, khi nhiệm vụ chuyển sang `HOAN_THANH`, MOD-04 cập nhật dữ liệu đầu vào hồ sơ đánh giá qua listener nghe `TrangThaiNhiemVuDoi` thay vì để MOD-02 gọi MOD-04.

Sự kiện cần ra khỏi tiến trình, như EVT-04 hay EVT-06, luôn đi qua outbox của schema module (bảng `<schema>.outbox_event`, ghi trong cùng giao dịch, Debezium đẩy lên Kafka). TL-01 nói rõ hai cơ chế không trùng nhau: một sự kiện công bố ra ngoài luôn được ghi outbox, và nếu module nội bộ cũng cần nghe nó thì nghe qua sự kiện ứng dụng, không đọc lại từ Kafka. TL-03 lại viết rằng sự kiện ứng dụng nội bộ giữa hai module "vẫn được ghi vào outbox ... rồi đi qua Kafka"; câu này trái ADR-23 và được ghi ở trang 14. Cho tới khi người phụ trách chốt, mã theo ADR-23 vì đó là quyết định kiến trúc có mã (suy luận).

Giữa các service, đường mặc định là sự kiện qua Kafka. REST đồng bộ chỉ dùng cho truy vấn cần dữ liệu tức thời, và luôn đi qua client có Resilience4j do thư viện nền `vt-platform-starter` cấu hình (ADR-24). Cấu hình chuẩn của TL-01 là time limiter 3 giây, circuit breaker ngưỡng lỗi 50%, bulkhead 25 lời gọi đồng thời chờ tối đa 50 ms, retry 3 lần chờ 200 ms nhân đôi có ngẫu nhiên 50%, chỉ cho thao tác idempotent (GET hoặc có `Idempotency-Key`). Thứ tự áp dụng là bulkhead ngoài cùng, rồi time limiter, circuit breaker, retry trong cùng. Một số phụ thuộc có cấu hình riêng (ký số MySign 10 giây, CSDL CBCCVC 8 giây). Lõi gọi workflow-service và evidence-service qua client này; khi workflow-service không phản hồi, thao tác duyệt trả 503 kèm `Retry-After`, còn thao tác không cần duyệt vẫn chạy. Không có lời gọi đồng bộ trên đường găng sang hệ thống ngoài: vOffice, CSDL CBCCVC, MySign, TSA đều đi qua SVC-13 hoặc SVC-10 theo kiểu bất đồng bộ.

## Quản lý giao dịch

Ranh giới giao dịch đặt ở lớp `application`, mỗi use case một giao dịch, và ArchUnit cấm `@Transactional` ở nơi khác. Mức cô lập là `READ COMMITTED` mặc định của PostgreSQL. Xung đột ghi đồng thời phát hiện bằng khoá lạc quan trên cột `row_version` (`@Version` của JPA); API nhận `If-Match` và trả 409 kèm Problem Details khi lệch phiên bản. Một đoạn khác của TL-01 ghi 412, nhưng quyết định HD-01 và `docs/quy-dinh/BACKEND.md` đã chốt 409 (trang 14). Thao tác chốt có điều kiện đếm, như khoá kỳ, dùng `SELECT ... FOR UPDATE` trên dòng `config.period` để tuần tự hoá ngắn.

TL-01 đặt ba quy tắc bắt buộc. Thứ nhất, không gọi mạng ra ngoài tiến trình trong giao dịch, trừ lời gọi tới CSDL; mọi tác dụng phụ ra ngoài đi qua outbox. Thứ hai, mọi giao dịch có giới hạn 5 giây (`@Transactional(timeout = 5)`), với `statement_timeout` của tài khoản CSDL làm lưới an toàn; con số `statement_timeout` lệch giữa các đoạn (30 giây ở mục "Quản lý giao dịch", 5 giây ở bảng "Timeout mặc định" và ở TL-03), xem trang 14. Thứ ba, giao dịch đọc dài như xuất báo cáo không chạy trong lõi mà chuyển sang analytics hoặc tác vụ nền trả mã theo dõi (202 kèm `Location`).

Use case mẫu `UpdateProgressUseCase` của TL-01 cho thấy thứ tự chuẩn trong một giao dịch: tra `IdempotencyStore` và trả lại kết quả đã lưu nếu khoá đã dùng; đọc aggregate theo phiên bản mong đợi (lệch thì ném lỗi xung đột); gọi `PeriodApi.assertWritable`; chạy quy tắc domain; lưu; ghi sự kiện bằng `OutboxWriter.append(EventEnvelope.of("task.task.progressed.v1", ...))` kèm phân loại; ghi khoá idempotency; trả kết quả. Khoá idempotency được chèn trong cùng giao dịch với thao tác nghiệp vụ, nên không có trường hợp đã làm mà chưa lưu khoá. Thời hạn lưu khoá đã chốt là 48 giờ theo HD-02 (TL-01 ghi 24 giờ).

Tám module dùng tám tài khoản CSDL, nên mỗi module có một `DataSource` riêng với pool nhỏ riêng. Cách này vừa là bulkhead ở mức CSDL vừa cưỡng chế quyền. Hệ quả là một use case chạm hai module phải gọi qua interface công bố của module kia và dùng kết nối của module kia trong một giao dịch riêng chỉ đọc, hoặc dùng bộ đệm. Lõi không dùng giao dịch phân tán XA; khi cần ghi nhất quán hai module, thiết kế đặt dữ liệu chốt vào một module và module kia nhận sự kiện.

## Co giãn, chịu lỗi và bảo mật của lõi

TL-01 cho lõi co giãn ngang bằng HPA theo CPU trung bình 60% và độ trễ p95 của API ghi (ngưỡng 400 ms), tối thiểu 6 replica ở cell lớn; mỗi replica yêu cầu 2 vCPU, 4 GiB, giới hạn 4 vCPU, 4 GiB, heap `-XX:MaxRAMPercentage=70`. Số replica tối đa và kích thước pod ở TL-04 khác TL-01, xem [trang 19](19-ha-tang-production-va-trien-khai.md) và trang 14. Readiness probe gồm kiểm tra pool CSDL, liveness probe không gồm phụ thuộc ngoài để tránh khởi động lại dây chuyền. Khi Kafka không sẵn sàng, lõi vẫn ghi được vì outbox nằm trong CSDL.

Về bảo mật, mọi endpoint mặc định từ chối. Quyền khai báo bằng `@RequiresPermission` ở use case và do OPA cùng pod quyết định (ADR-21); truy vấn danh sách áp bộ lọc phạm vi cây tổ chức do OPA trả về bằng đánh giá từng phần. Trường C2 được `FieldEncryptor` mã hoá trước khi rời ứng dụng. Chi tiết ở [trang 20](20-bao-mat-phia-may-chu.md) và [trang 15](15-phan-quyen.md).

## Thư viện nền vt-kernel và vt-platform-starter (NEN-009, NEN-016)

Mọi service Java phụ thuộc vào `vt-platform-starter`, thư viện kéo theo `vt-kernel` (UUIDv7 qua `Ids`, các kiểu mã `TenantId`, `PeriodId`, `ActorId`, `Clock` có ngày nghiệp vụ theo giờ Asia/Ho_Chi_Minh, `Result`, `Money`, `Classification` C0–C3 và annotation `@Classified`). Nhân `vt-kernel` không phụ thuộc Spring, JPA hay Jackson nên mã miền dùng được ở mọi nơi.

Về lỗi, mã nghiệp vụ chỉ ném `BusinessException` với mã có trong danh mục `error-codes.json`. Danh mục này sinh từ hợp đồng bằng `node backend/libs/vt-platform-starter/tools/extract-error-codes.mjs`, và kiểm thử đồng bộ sẽ đỏ khi hợp đồng đổi mà danh mục chưa được sinh lại. Mô-đun của mã lỗi lấy theo gói `vn.viettel.okrkpi.<module>..` của controller, nếu không được thì theo `okrkpi.problem.module`, cuối cùng là `COMMON`. Yêu cầu cập nhật thiếu `If-Match` nhận 428, xung đột phiên bản nhận 409 `<MODULE>-409-VERSION-CONFLICT`.

Về nhật ký, service phải loại Logback (`spring-boot-starter-logging`) khỏi các starter khác của Spring Boot và dùng `log4j2-spring.xml` có sẵn trong starter. Plugin che dữ liệu chỉ được nạp khi jar có tệp `Log4j2Plugins.dat` do bộ xử lý annotation của log4j-core sinh; thiếu tệp này thì nhật ký không được che. Kiểm thử đọc nhật ký bất đồng bộ cần `follow="true"`, `OutputCaptureExtension` và Awaitility, và MDC không tự đi sang luồng khác.

Về chịu lỗi, mọi lời gọi ra ngoài đi qua `ResilientClientFactory.create("<instance>", baseUrl)`; instance chưa khai báo dùng cấu hình `default` chép từ TL-01, còn ghi đè thì đặt trong `application.yml` kèm lý do. Kiểm thử kiến trúc của service gắn luật dùng chung, ví dụ `@ArchTest static final ArchRule r = PlatformArchitectureRules.NO_UNBOUNDED_EXECUTORS;` (cần `archunit-junit5` ở scope test). Chi tiết về nhật ký và che dữ liệu ở [trang 20](20-bao-mat-phia-may-chu.md).

## Mẫu service Java `service-template` và lệnh `scripts/new-service.sh` (NEN-019)

Thư mục `backend/services/service-template/` cung cấp khung microservice Java chuẩn (Spring Boot 3.5, Java 21, phụ thuộc `vt-kernel` và `vt-platform-starter`) tuân thủ TL-01, TL-04 (WI-20, WI-23) và `docs/quy-dinh/BACKEND.md`:

- **Cấu trúc lục giác 5 gói**: `vn.viettel.okrkpi.<module>.{api,application,domain,infrastructure,internal}` kèm bộ kiểm tra ArchUnit (`ArchitectureRulesTest`) cưỡng chế 8 quy tắc của TL-01 và `PlatformArchitectureRules` của `vt-platform-starter`.
- **Cổng quản trị và tắt êm (TL-04 WI-20, WI-23)**: cổng HTTP chính `${SERVER_PORT:8080}`, cổng Actuator tách riêng `${MANAGEMENT_PORT:8081}` chỉ mở `health,info,prometheus`. Probe `/actuator/health/liveness` chỉ kiểm nội tại JVM (`livenessState`), còn `/actuator/health/readiness` kiểm pool kết nối CSDL và trạng thái producer Kafka (`TemplateReadinessIndicator`). Cấu hình tắt êm `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=25s`, khớp với `terminationGracePeriodSeconds: 30` và `preStop: sleep 5` trong `helm/values.yaml`.
- **Tham số môi trường động hỗ trợ SSH tunnel (`35xxx`)**: mọi địa chỉ và cổng kết nối trong `application.yaml` và `application-dev.yaml` đều tham số hoá qua biến môi trường (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_SCHEMA`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `KAFKA_BOOTSTRAP_SERVERS`, `APICURIO_REGISTRY_URL`, `KEYCLOAK_ISSUER_URI`, `OPA_URL`, `OPENBAO_URL`, `OTEL_EXPORTER_OTLP_ENDPOINT`), mặc định ở profile `dev` trỏ về `127.0.0.1` với dải cổng `35xxx` (`35432`/`35433`, `35379`, `35092`, `35081`, `35080`, `35181`, `35820`, `35318`).
- **Tạo microservice mới bằng một lệnh**: chạy `./scripts/new-service.sh <ten-dich-vu> [--java]` (ví dụ `./scripts/new-service.sh scoring-service`) sẽ đối chiếu `<ten-dich-vu>` với danh mục SVC Java của baseline mục 5 (`org-service`, `workflow-service`, `scoring-service`, `evidence-service`, `esign-service`, `document-service`, `integration-gateway`), sao chép `service-template` sang `backend/services/<ten-dich-vu>/`, đổi gói Java, tên lớp khởi chạy, mã `SVC-xx`, schema CSDL, sinh `deploy/charts/<ten-dich-vu>/values.yaml` và đăng ký `<module>` vào `backend/services/pom.xml`.

## Khung `mission-core`: 8 DataSource, định tuyến đọc/ghi và bảo vệ 9 schema (CORE-00-01)

Module `backend/mission-core/` hiện thực `SVC-05 mission-core` với các cơ chế nền tảng sau:

- **Tám `DataSource` nghiệp vụ độc lập + `orgReplicaDataSource`**: mỗi mô-đun (`okr`, `task`, `product`, `appraisal`, `policy`, `dossier`, `oversight`, `config`) có một `ReadWriteRoutingDataSource`, một `LocalContainerEntityManagerFactoryBean` và một `ModuleTenantJpaTransactionManager` riêng; `task` được đánh dấu `@Primary`. Tổng `maximumPoolSize` mặc định của 8 mô-đun nghiệp vụ là `24` (`task=8, okr=4, appraisal=4, product=3, config=2, policy=1, dossier=1, oversight=1`), được kiểm tra tự động không vượt trần `35` kết nối/pod của TL-04.
- **Chuẩn hoá PgBouncer (`PgBouncerJdbcUrlFinalizer`)**: mọi URL JDBC được tự động gắn `prepareThreshold=0` và `ApplicationName=mission-core-<module>`.
- **Định tuyến đọc/ghi (`ReadWriteRoutingDataSource` + `RecentWriteTracker`)**: giao dịch `@Transactional(readOnly = true)` được định tuyến sang pool `RO` khi bật read-replica, nhưng tự động ghim về `RW` trong vòng `2 giây` sau khi người dùng vừa thực hiện giao dịch ghi (*read-your-own-writes*) hoặc khi độ trễ nhân bản vượt `1.000 ms`.
- **Đối chiếu 9 schema khi khởi động (`MissionCoreSchemaVersionGuard`)**: kiểm tra `flyway_schema_history` của `okr`, `task`, `product`, `appraisal`, `policy`, `dossier`, `oversight`, `config` và `org_replica` trên từng `DataSource` tương ứng trước khi nhận lưu lượng.

## Vị trí mã trong repo

Repo này là repo chung toàn hệ thống. Theo `docs/KE-HOACH-TONG-THE-HE-THONG.md` mục 5 và `docs/quy-dinh/BACKEND.md`, mã máy chủ đặt như sau. Thư mục `backend/` là dự án Maven đa mô-đun, gồm `platform-bom`, `libs/` (`vt-kernel`, `vt-platform-starter`, `vt-db-migration`, `vt-api-contract`, `vt-event-contract`), `mission-core/` và `services/<tên>` cho các service Java (mẫu gốc `services/service-template` đã có từ NEN-019; các dịch vụ `org-service`, `workflow-service`, `scoring-service`, `evidence-service`, `esign-service`, `document-service`, `integration-gateway` được khởi tạo bằng `scripts/new-service.sh`). Thư mục `services-go/` chứa tenant-registry, ledger và notification viết bằng Go 1.23. Thư mục `services-py/` chứa ai-gateway và các job phân tích viết bằng Python 3.12. Thư mục `contracts/` chứa hợp đồng: `openapi/` (đã có, là nguồn sự thật cho mọi API HTTP theo ADR-32 API-first), `events/avro/` và `asyncapi/` cho sự kiện. Mã máy chủ sinh interface từ hợp đồng bằng openapi-generator rồi viết lớp hiện thực; khi mã và hợp đồng lệch nhau thì sửa mã, không sửa hợp đồng.

## Liên quan

- [Tổng quan hệ thống](01-tong-quan-he-thong.md)
- [Kiến trúc frontend](02-kien-truc-frontend.md)
- [Quy ước API](06-quy-uoc-api.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
- [Phân quyền](15-phan-quyen.md)
- [Thiết kế CSDL và đa khách thuê](17-thiet-ke-csdl-va-da-khach-thue.md)
- [Sự kiện và đồng bộ dữ liệu](18-su-kien-va-dong-bo-du-lieu.md)
- [Hạ tầng production và triển khai](19-ha-tang-production-va-trien-khai.md)
- [Bảo mật phía máy chủ](20-bao-mat-phia-may-chu.md)
- `docs/quy-dinh/BACKEND.md`

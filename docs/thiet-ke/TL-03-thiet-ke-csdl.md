---
title: "Thiết kế cơ sở dữ liệu và cơ chế đồng bộ dữ liệu"
subtitle: "Nền tảng đánh giá cán bộ theo OKR và KPI · Mã tài liệu TL-03 · Phiên bản 1.1 · 10/2026"
toc-title: "Mục lục"
---

# Thông tin tài liệu {-}

## Kiểm soát phiên bản {-}

| Phiên bản | Ngày | Người thực hiện | Nội dung thay đổi |
|---|---|---|---|
| 0.1 | 12/10/2026 | Nhóm kiến trúc dữ liệu | Khung tài liệu, quy ước đặt tên, danh mục schema theo baseline |
| 0.5 | 16/10/2026 | Nhóm kiến trúc dữ liệu | Thiết kế chi tiết 20 schema, cơ chế đồng bộ, lược đồ sự kiện |
| 1.0 | 19/10/2026 | Nhóm kiến trúc dữ liệu | Hoàn thiện mã hoá theo lớp, hiệu năng, di trú từ Oracle/vOffice, bảng truy vết checklist |
| 1.1 | 23/10/2026 | Nhóm kiến trúc dữ liệu | Cập nhật theo thực tế triển khai đợt 1: phụ lục cuối ghi điểm lệch, bổ sung và quyết định; chỉ tiêu hiệu năng chỉ ghi lại theo YC-PT-19 |

## Tài liệu liên quan {-}

| Mã | Tên tài liệu | Quan hệ với TL-03 |
|---|---|---|
| TL-01 | Thiết kế kiến trúc chi tiết | Xác định service SVC-xx, module MOD-xx, ADR; TL-03 chi tiết hoá phần dữ liệu của từng thành phần |
| TL-02 | Đặc tả quy trình nghiệp vụ và giao diện (TL-02a, TL-02b) | Quy trình QT-00 đến QT-18 và màn hình SCR tham chiếu tới bảng `schema.bảng` và sự kiện EVT trong tài liệu này |
| TL-03 | Thiết kế cơ sở dữ liệu và cơ chế đồng bộ dữ liệu | Tài liệu này |
| TL-04 | Triển khai, hiệu năng, dự phòng và ATTT | Dùng ước tính dung lượng, cấu hình PostgreSQL, PgBouncer, Kafka, Ceph trong tài liệu này để định cỡ hạ tầng và đặt kịch bản PERF, DR, SEC |
| Đầu vào 1 | Bản đánh giá kiến trúc công nghệ 10/2026 (110 tiêu chí A1 đến L12, 64 vấn đề) | Checklist phải đáp ứng; chương cuối đối chiếu các tiêu chí A2, A4, A5, A6, E2, E4, E5, I1, I2, I5, I6, I7, K1, K3, K4, K5, L4, L11 |
| Đầu vào 2 | Báo cáo Ban Giám đốc 10/2026 (74 slide, Phụ lục A1 đến A9) | 8 nhóm dữ liệu tối thiểu theo PL 4, 7 lớp bảo vệ, mô hình cell, Policy Pack |

# Giới thiệu

## Mục đích và phạm vi

Tài liệu này trả lời ba câu hỏi mà baseline giao cho TL-03: dữ liệu của nền tảng nằm ở đâu, thuộc quyền sở hữu của service hay module nào, và được đồng bộ giữa các thành phần bằng cơ chế nào. Vì nền tảng được thiết kế theo mô hình lai (ADR-01), gồm một lõi Modular Monolith là mission-core (SVC-05) với tám module MOD-01 đến MOD-08 và mười bảy thành phần còn lại triển khai thành microservice, hạ tầng hoặc frontend, nên phần lớn nội dung xoay quanh hai trục. Trục thứ nhất là thiết kế tĩnh: mỗi service và mỗi module sở hữu kho dữ liệu nào, các bảng có cấu trúc ra sao, ràng buộc, chỉ mục, phân vùng và phân loại dữ liệu đến từng cột. Trục thứ hai là thiết kế động: khi một bản ghi thay đổi ở nơi sở hữu, thay đổi đó lan tới những nơi cần đọc bằng cơ chế gì, với độ trễ và mức nhất quán nào, và làm sao phát hiện, sửa chữa khi bản sao bị lệch.

Phạm vi bao gồm toàn bộ kho dữ liệu nghiệp vụ trong một cell (PostgreSQL 17, Valkey, Ceph RGW, OpenSearch, ClickHouse, Kafka), kho dữ liệu toàn cục của vùng điều phối (danh bạ tenant và kho phân tích quốc gia), cơ chế di trú dữ liệu từ hệ thống hiện tại chạy trên Oracle trong schema `VOFFICE`, và chính sách lưu giữ, lưu trữ, xoá dữ liệu. Tài liệu không mô tả lại lý do chọn kiến trúc (đã có trong TL-01), không đặc tả màn hình (TL-02) và không mô tả cách vận hành cụm CSDL trên Kubernetes ở mức thao tác (TL-04), nhưng cung cấp số liệu đầu vào cho các tài liệu đó.

Người đọc chính là kiến trúc sư, trưởng nhóm phát triển backend, quản trị CSDL và đội an toàn thông tin. Mức chi tiết được chọn để một nhóm phát triển có thể viết tệp migration Flyway đầu tiên của mỗi schema trực tiếp từ từ điển dữ liệu, và một quản trị CSDL có thể định cỡ cụm PostgreSQL của cell lớn nhất từ chương hiệu năng mà không phải hỏi lại.

## Cách đọc tài liệu

Chương 2 nêu các nguyên tắc chi phối mọi quyết định dữ liệu: sở hữu dữ liệu theo service, quyền tối thiểu, không có quyền DDL ở ứng dụng. Chương 3 trình bày mô hình khái niệm theo tám nhóm dữ liệu của Phụ lục 4 dự thảo Quy định mới và mô hình dữ liệu chuẩn dùng để trao đổi với hệ thống ngoài. Chương 4 là danh mục đầy đủ của 18 service và 8 module với trách nhiệm dữ liệu, kho, sự kiện phát và nhận. Chương 5 mô tả cách dữ liệu được phân bổ theo cell và ba mô hình thuê bao silo, bridge, pool, trong đó có thiết kế bảo mật theo dòng (Row-Level Security). Chương 6 là phần dài nhất, thiết kế chi tiết hai mươi schema với sơ đồ thực thể liên kết và từ điển dữ liệu. Các chương 7, 8 và 9 đi sâu vào ba cơ chế có giá trị pháp lý và bảo mật cao: dữ liệu hai trục thời gian, sổ cái toàn vẹn và mã hoá theo lớp dữ liệu. Chương 10 và 11 là trọng tâm về đồng bộ dữ liệu và lược đồ sự kiện. Các chương 12 đến 17 bàn về cache, kho đối tượng, hiệu năng, migration, lưu giữ và đối soát vận hành. Chương 18 đối chiếu các tiêu chí checklist liên quan tới dữ liệu với mục cụ thể trong tài liệu.

Khi nhắc tới một bảng, tài liệu luôn viết dạng `schema.bảng`, ví dụ `task.task` hoặc `appraisal.score_snapshot`. Khi nhắc tới một service, tài liệu dùng mã SVC-xx kèm tên triển khai, ví dụ SVC-07 scoring-service. Các sự kiện được gọi bằng mã EVT-xx theo baseline mục 7.

## Quy ước đặt tên

Mọi định danh trong CSDL dùng chữ thường tiếng Anh theo kiểu `snake_case`, không dấu, không dùng từ khoá dành riêng của SQL chuẩn, và không phải đặt trong dấu ngoặc kép. Lý do là PostgreSQL tự chuyển định danh không trích dẫn về chữ thường; nếu đặt tên có chữ hoa thì mọi câu lệnh phải trích dẫn, dễ sinh lỗi khi viết tay và khó di chuyển sang công cụ khác. Bảng của Flowable (`ACT_*`, `FLW_*`) là ngoại lệ duy nhất vì do engine tự tạo.

| Đối tượng | Quy ước | Ví dụ |
|---|---|---|
| Schema | Tên module hoặc service, số ít, trùng tên trong baseline mục 9 | `task`, `appraisal`, `org_replica` |
| Bảng | Danh từ số ít mô tả một thực thể; bảng liên kết ghép hai danh từ | `task`, `task_assignee`, `okr_link` |
| Cột khoá chính | `id` kiểu `uuid` | `id` |
| Cột khoá ngoại | `<tên bảng được tham chiếu>_id` | `task_id`, `plan_version_id` |
| Cột trạng thái | `status` kiểu `varchar(32)` với ràng buộc `CHECK` liệt kê giá trị trong baseline mục 9.1 | `status IN ('NHAP','DA_GIAO',...)` |
| Cột thời điểm | Hậu tố `_at`, kiểu `timestamptz` | `submitted_at`, `locked_at` |
| Cột ngày lịch | Hậu tố `_date` hoặc `_on`, kiểu `date` | `due_date`, `effective_on` |
| Cột cờ | Tiền tố `is_` hoặc `has_`, kiểu `boolean NOT NULL DEFAULT false` | `is_overdue`, `has_e2ee` |
| Cột mã hoá C2 | Hậu tố `_enc` kiểu `bytea`, đi kèm cột `dek_id uuid` trong cùng bảng | `comment_enc`, `national_id_enc` |
| Cột chỉ mục mù | Hậu tố `_bidx` kiểu `bytea` (HMAC-SHA-256 cắt 16 byte) | `national_id_bidx` |
| Cột băm | Hậu tố `_hash` kiểu `bytea` (32 byte SHA-256) | `content_hash`, `input_hash` |
| Khoá chính | `pk_<bảng>` | `pk_task` |
| Khoá ngoại | `fk_<bảng>__<bảng đích>` | `fk_task_assignee__task` |
| Ràng buộc duy nhất | `uq_<bảng>__<cột>` | `uq_catalog_item__code` |
| Ràng buộc kiểm tra | `ck_<bảng>__<nội dung>` | `ck_task__status` |
| Ràng buộc loại trừ | `ex_<bảng>__<nội dung>` | `ex_assignment__no_overlap` |
| Chỉ mục | `ix_<bảng>__<cột>`; chỉ mục một phần thêm hậu tố `_p` | `ix_task__assignee_due_p` |
| Chính sách RLS | `rls_<bảng>__tenant` | `rls_task__tenant` |
| Trigger | `tg_<bảng>__<hành vi>` | `tg_ledger_entry__no_update` |
| Hàm, thủ tục | `fn_<động từ>_<đối tượng>` | `fn_create_period_partitions` |
| Vai trò CSDL | `svc_<schema>`, `ro_<schema>`, `mig_<schema>`, `cdc_<schema>` | `svc_task`, `mig_ledger` |

Tên bảng và tên schema đã có trong baseline mục 9 được giữ nguyên. Tài liệu này thêm một số bảng mới trong dải mã được cấp, mỗi bảng thêm đều được đánh dấu "bổ sung" trong phần giải thích của schema và được liệt kê ở tệp ghi chú `src/_ghi-chu-TL-03.md` để người tổng hợp đối chiếu.

## Kiểu dữ liệu chuẩn

Việc thống nhất kiểu dữ liệu giữa hai mươi schema giúp các bộ ánh xạ (JPA, jOOQ, pgx trong Go, asyncpg trong Python) dùng chung một bảng quy đổi, và tránh lỗi làm tròn khi cùng một con số đi qua nhiều service. Bảng sau là danh sách kiểu được phép; muốn dùng kiểu khác phải ghi lý do trong đánh giá thiết kế của migration.

| Loại giá trị | Kiểu PostgreSQL | Kiểu Java / Avro | Ghi chú |
|---|---|---|---|
| Định danh | `uuid` (UUIDv7) | `java.util.UUID` / `string` logicalType `uuid` | Sinh ở ứng dụng (thư viện uuid-creator), không sinh bằng hàm CSDL vì PostgreSQL 17 chưa có hàm UUIDv7 dựng sẵn |
| Mã nghiệp vụ | `varchar(n)` với n cụ thể | `String` | Không dùng `char(n)` vì đệm khoảng trắng |
| Văn bản dài | `text` | `String` | Giới hạn độ dài bằng `CHECK (length(x) <= n)` khi cần |
| Thời điểm | `timestamptz` | `java.time.Instant` / `long` logicalType `timestamp-micros` | Lưu UTC; hiển thị theo múi giờ Asia/Ho_Chi_Minh |
| Ngày lịch | `date` | `java.time.LocalDate` / `int` logicalType `date` | Hạn nhiệm vụ, ngày hiệu lực |
| Khoảng hiệu lực | `daterange`, `tstzrange` | Hai trường from/to | Dùng cho ràng buộc loại trừ ở schema `org` |
| Điểm số | `numeric(6,3)` | `java.math.BigDecimal` / `bytes` logicalType `decimal(6,3)` | Phạm vi 0,000 đến 100,000; không dùng `double` để tránh sai số dấu phẩy động |
| Hệ số A, B, C, tỷ lệ | `numeric(6,4)` | `BigDecimal` / `decimal(6,4)` | Phạm vi 0 đến 1 (tỷ lệ vượt có thể > 1, chặn bằng CHECK ≤ 10) |
| Giá trị đo KR, KPI | `numeric(18,4)` | `BigDecimal` / `decimal(18,4)` | Chỉ tiêu, giá trị hiện tại |
| Đếm | `int`, `bigint` | `int`, `long` | `bigint` cho số thứ tự sổ cái, offset |
| Cờ | `boolean` | `boolean` | Luôn `NOT NULL` với giá trị mặc định |
| Cấu trúc mềm | `jsonb` | `JsonNode` / `string` | Chỉ cho dữ liệu cấu hình, tham số, payload; không dùng để né thiết kế cột |
| Bản mã, băm, chữ ký | `bytea` | `byte[]` / `bytes` | Cột `_enc`, `_hash`, `_bidx`, `signature` |
| Véc-tơ nhúng | `vector(1024)` (pgvector) | `float[]` | Chỉ trong schema `ai` |

Hệ quả thực tế của việc dùng `numeric(6,3)` cho điểm là phép tính điểm được làm hoàn toàn bằng `BigDecimal` với chế độ làm tròn `HALF_UP` ở ba chữ số thập phân theo quy định làm tròn trong Policy Pack. Khi SVC-07 scoring-service tính lại một hồ sơ cũ, kết quả trùng tới từng chữ số với giá trị đã lưu, điều kiện cần để công việc đối soát hằng đêm (chương 17) không sinh cảnh báo giả.

## Cột chung của bảng nghiệp vụ

Mọi bảng nghiệp vụ có bảy cột chung theo baseline mục 9. Các bảng phân vùng theo kỳ có thêm `period_id` và cột khoá phân vùng `period_bucket`. Để từ điển dữ liệu ở chương 6 gọn, bảy cột chung được ghi gộp thành một dòng "(cột chung)" trong mỗi bảng; ý nghĩa đầy đủ của chúng như sau.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK (hoặc một phần PK ở bảng phân vùng) | C1 | UUIDv7 sinh ở ứng dụng; 48 bit đầu là thời điểm mili giây nên chèn tăng dần, giảm phân mảnh chỉ mục B-tree so với UUIDv4 |
| `tenant_id` | `uuid` | Không | Cột đầu tiên của mọi chỉ mục; khoá của chính sách RLS | C1 | Mã tenant theo `registry.tenant.id` |
| `created_at` | `timestamptz` | Không | DEFAULT `now()` | C1 | Thời điểm tạo bản ghi theo đồng hồ CSDL |
| `created_by` | `uuid` | Không | | C1 | Mã người dùng (`sub` trong JWT) hoặc mã tài khoản dịch vụ |
| `updated_at` | `timestamptz` | Không | DEFAULT `now()` | C1 | Thời điểm cập nhật gần nhất |
| `updated_by` | `uuid` | Không | | C1 | Người cập nhật gần nhất |
| `row_version` | `int` | Không | DEFAULT 0 | C1 | Khoá lạc quan; ETag của API; tăng 1 mỗi lần cập nhật; cũng là `aggregate_version` ghi vào outbox |
| `period_id` | `uuid` | Không | Tham chiếu logic `config.period.id` | C1 | Kỳ đánh giá nghiệp vụ |
| `period_bucket` | `int` | Không | Khoá phân vùng LIST | C1 | Giá trị `năm × 10 + quý` (ví dụ 20271 là quý I/2027); `năm × 10` cho dữ liệu cấp năm |

Lý do tách `period_bucket` khỏi `period_id` là PostgreSQL yêu cầu khoá phân vùng nằm trong khoá chính, và công cụ quản lý phân vùng làm việc tốt với giá trị số có thứ tự hơn với UUID. `period_id` vẫn là khoá nghiệp vụ duy nhất mà ứng dụng dùng để lọc, còn `period_bucket` được suy ra một lần khi tạo bản ghi từ ngày bắt đầu kỳ và không bao giờ thay đổi. Luồng Nhà nước có kỳ theo tháng (NĐ 335/2025): kỳ tháng được xếp vào phân vùng của quý chứa tháng đó, nên số phân vùng mỗi năm luôn là năm (bốn quý cộng một phân vùng năm).

`created_by` và `updated_by` không có khoá ngoại vật lý tới bảng người dùng vì người dùng thuộc SVC-02 iam và SVC-04 org-service, nằm ở CSDL khác. Đây là hệ quả trực tiếp của nguyên tắc Database per Service: tham chiếu giữa các service luôn là tham chiếu logic bằng mã định danh, tính toàn vẹn được bảo đảm bằng sự kiện và đối soát chứ không bằng khoá ngoại.

## Phân loại dữ liệu C0 đến C3 trên từng cột

ADR-06 chia dữ liệu thành bốn mức. Tài liệu này áp phân loại tới từng cột, vì cùng một bảng có thể chứa cột thường và cột nhạy cảm. Ví dụ bảng `appraisal.rating_explanation` có cột `rating_code` (mức xếp loại, C1 theo lập trường G3 của baseline vì cần tính toán, phân tích) và cột `explanation_enc` (lời giải trình, C2). Cách phân loại này thống nhất với TL-01: điểm số và mức xếp loại là C1, được bảo vệ bằng RLS, ABAC và sổ cái, không mã hoá trường; nhận xét đánh giá, nội dung khiếu nại, góp ý kiểm điểm, số định danh và ngày sinh là C2 và được mã hoá trường. Tài liệu không xếp "kết quả đánh giá" nói chung vào C2.

| Mức | Tên | Tiêu chí xếp mức | Biện pháp bắt buộc ở tầng dữ liệu |
|---|---|---|---|
| C0 | Công khai | Thông tin được phép công bố: danh mục, tên đơn vị, văn bản đã công khai | Mã hoá ổ đĩa và TLS như mọi dữ liệu khác |
| C1 | Nội bộ | Dữ liệu nghiệp vụ thường: nhiệm vụ, tiến độ, điểm số, mức xếp loại, mã định danh | RLS theo tenant, ABAC ở ứng dụng, mã hoá ổ đĩa (LUKS, Ceph), TLS 1.3, mTLS |
| C2 | Nhạy cảm | Dữ liệu cá nhân theo NĐ 356/2025 (số định danh, ngày sinh, số điện thoại), nhận xét đánh giá, giải trình, nội dung khiếu nại, góp ý kiểm điểm, kết luận hậu kiểm | Thêm mã hoá trường AES-256-GCM theo envelope encryption; không cache dạng rõ; giả danh hoá khi sang ClickHouse; không đưa vào OpenSearch |
| C3 | Hạn chế, mật | Nội dung nhiệm vụ mật, tài liệu mật đính kèm | Mã hoá đầu cuối tại client; máy chủ chỉ lưu bản mã và khoá bọc theo người nhận; không tìm kiếm, không phân tích, không AI |

Phân loại được ghi vào CSDL bằng chú thích cột theo cú pháp thống nhất, để công cụ kiểm tra trong pipeline CI và công cụ cấu hình Debezium đọc được tự động.

```sql
COMMENT ON COLUMN appraisal.rating_explanation.explanation_enc IS
  '{"cls":"C2","pii":false,"desc":"Lời giải trình khi mức quyết định khác mức đề xuất"}';
COMMENT ON COLUMN org.person.national_id_enc IS
  '{"cls":"C2","pii":true,"desc":"Số định danh cá nhân, mã hoá trường"}';
```

Một bài kiểm tra trong CI (chạy Testcontainers PostgreSQL sau khi áp toàn bộ migration) đọc `pg_description` và từ chối bản build nếu có cột nào thiếu chú thích phân loại, hoặc có cột ghi `"cls":"C2"` mà kiểu không phải `bytea` hoặc tên không có hậu tố `_enc`. Cùng danh sách này được dùng để sinh tham số `column.exclude.list` cho connector Debezium phân tích (chương 10), nhờ vậy việc thêm một cột C2 mới không thể vô tình làm rò dữ liệu sang ClickHouse.

# Nguyên tắc thiết kế dữ liệu

## Sở hữu dữ liệu: Database per Service và Schema per Module

Nguyên tắc nền tảng của thiết kế là mỗi đơn vị triển khai sở hữu riêng dữ liệu của mình (mẫu Database per Service). Bản đánh giá kiến trúc chỉ ra rằng hiện trạng là một Distributed Monolith: service Nhiệm vụ tách tiến trình nhưng 23 bảng mới vẫn nằm trong schema `VOFFICE` dùng chung với core, và có 17 điểm ghép nối ở mức bảng (vấn đề A01, K01, tiêu chí A2 chưa đạt). Thiết kế mới chấm dứt tình trạng này bằng ba quy tắc.

Thứ nhất, mỗi microservice có schema riêng và tài khoản CSDL riêng; các service có yêu cầu đặc biệt về bảo mật hoặc tải (SVC-02 iam, SVC-06 workflow-service, SVC-09 ledger-service, SVC-10 esign-service, SVC-14 ai-gateway) còn có CSDL và cụm PostgreSQL riêng; các kho không quan hệ (ClickHouse, OpenSearch, Ceph bucket) cũng thuộc riêng một service. Đây là hai biến thể đã được công nhận của mẫu Database per Service: "schema riêng cho mỗi service" (schema-per-service) và "máy chủ CSDL riêng cho mỗi service" (database-server-per-service). Điểm cốt lõi của mẫu là không service nào đọc, ghi được bảng của service khác, và điều đó được bảo đảm bằng quyền CSDL chứ không bằng quy ước. Không service nào có thông tin đăng nhập vào schema của service khác. Thứ hai, bên trong mission-core (SVC-05), mỗi module MOD-01 đến MOD-08 có một schema riêng và một vai trò CSDL riêng. Các module chạy chung tiến trình Java nhưng mỗi module dùng một `DataSource` riêng đăng nhập bằng vai trò của module đó, nên kể cả khi lập trình viên viết nhầm truy vấn sang bảng của module khác thì CSDL cũng từ chối. Cách làm này biến ranh giới module từ một quy ước (ArchUnit kiểm tra ở mức mã) thành một ràng buộc kỹ thuật kiểm tra ở mức dữ liệu. Thứ ba, dữ liệu cần dùng chung được trao đổi qua hai kênh duy nhất: API công bố (REST có Resilience4j cho truy vấn cần dữ liệu tức thời) và sự kiện qua Kafka. Riêng dữ liệu tổ chức, nhân sự được nhân bản vào schema `org_replica` theo mẫu Event-carried State Transfer (chương 10).

Các schema được đặt vào CSDL vật lý theo đơn vị "CSDL dữ liệu tenant" (`td_<mã tenant>`), chi tiết ở chương 5. Mỗi CSDL dữ liệu tenant chứa các schema của mission-core cùng schema của bốn microservice xử lý dữ liệu nghiệp vụ theo tenant (SVC-04 `org`, SVC-07 `scoring`, SVC-08 `evidence`, SVC-11 `docgen`), mỗi schema một vai trò riêng. Lý do là ba điều. Thứ nhất, thao tác có giá trị pháp lý của lõi cần giao dịch ACID cục bộ với outbox của chính module. Thứ hai, PostgreSQL giải mã WAL cho từng slot sao chép logic trên toàn cụm, nên số slot (tức số connector Debezium) càng ít thì chi phí giải mã càng thấp; gom theo CSDL dữ liệu tenant cho đúng một slot mỗi tenant. Thứ ba, ở mô hình bridge, mỗi tỉnh có đúng một CSDL riêng, đúng như baseline mục 6 mô tả, nên sao lưu, khôi phục, di chuyển hay xoá dữ liệu của một tỉnh là thao tác trên một CSDL. Nếu sau này một module đạt tiêu chí tách service (TL-01, tiêu chí tách thêm một module theo bản đánh giá), việc chuyển schema của nó sang CSDL hoặc cụm riêng chỉ là thao tác vận hành: schema đã độc lập, không có khoá ngoại hay truy vấn chéo, đã có outbox, inbox và tài khoản riêng.

## Không truy cập chéo và quyền tối thiểu

Mỗi schema có bốn vai trò CSDL với quyền tách bạch, đặt tên thống nhất với TL-01: `svc_<module>` là tài khoản chạy và `mig_<module>` là tài khoản Flyway; TL-03 thêm hai vai trò chỉ đọc và CDC. Vai trò `mig_<schema>` sở hữu mọi đối tượng trong schema và chỉ được Flyway dùng khi chạy migration. Vai trò `svc_<schema>` là tài khoản ứng dụng, chỉ có quyền đọc ghi dữ liệu, không có quyền tạo, sửa, xoá đối tượng. Vai trò `ro_<schema>` chỉ đọc, dùng cho bản sao đọc và công cụ hỗ trợ vận hành có kiểm soát qua PAM. Vai trò `cdc_<schema>` dùng cho Debezium, có thuộc tính `REPLICATION` và chỉ được đọc bảng `outbox_event` cùng các bảng được khai báo cho CDC phân tích.

```sql
-- Chạy một lần khi khởi tạo cụm, bằng tài khoản quản trị cụm (không phải tài khoản ứng dụng)
CREATE ROLE mig_task  LOGIN PASSWORD :'mig_task_pwd' NOINHERIT;
CREATE ROLE svc_task  LOGIN PASSWORD :'svc_task_pwd' NOINHERIT NOBYPASSRLS CONNECTION LIMIT 400;
CREATE ROLE ro_task   LOGIN PASSWORD :'ro_task_pwd' NOINHERIT NOBYPASSRLS CONNECTION LIMIT 20;
CREATE ROLE cdc_task  LOGIN REPLICATION PASSWORD :'cdc_task_pwd' NOINHERIT CONNECTION LIMIT 5;

CREATE SCHEMA task AUTHORIZATION mig_task;
REVOKE ALL ON SCHEMA task FROM PUBLIC;
GRANT USAGE ON SCHEMA task TO svc_task, ro_task, cdc_task;

-- Quyền mặc định cho mọi bảng do mig_task tạo về sau
ALTER DEFAULT PRIVILEGES FOR ROLE mig_task IN SCHEMA task
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO svc_task;
ALTER DEFAULT PRIVILEGES FOR ROLE mig_task IN SCHEMA task
  GRANT USAGE, SELECT ON SEQUENCES TO svc_task;
ALTER DEFAULT PRIVILEGES FOR ROLE mig_task IN SCHEMA task
  GRANT SELECT ON TABLES TO ro_task;

-- Ngoại lệ: bảng nhật ký chỉ ghi thêm thì ứng dụng không được sửa, xoá
REVOKE UPDATE, DELETE ON task.task_state_log FROM svc_task;
-- CDC chỉ đọc outbox
GRANT SELECT ON task.outbox_event TO cdc_task;
-- Không vai trò ứng dụng nào của module khác có USAGE trên schema task
```

Vai trò ứng dụng và vai trò chỉ đọc được tạo với `NOINHERIT` và `NOBYPASSRLS` (ADR-22 của TL-01), nên không thể vượt qua RLS kể cả khi bị cấu hình sai, và không được cấp vào bất kỳ vai trò nhóm nào có quyền trên schema khác. Mật khẩu không nằm trong Git: CloudNativePG tạo Secret cho từng vai trò, External Secrets Operator đồng bộ từ OpenBao (ADR-17), và OpenBao Database Secrets Engine xoay vòng mật khẩu `svc_<schema>` mỗi 30 ngày. Tham số `CONNECTION LIMIT` ở vai trò là hàng rào cuối cùng chống việc một module chiếm hết kết nối của cụm; con số cụ thể được tính ở chương 14.

## Ứng dụng không có quyền DDL

Tiêu chí K1 yêu cầu "ứng dụng chạy không cần quyền DDL", trong khi hiện trạng có lớp `MissionEvaluationStepSchemaGuard` tự chạy `ALTER TABLE` lúc khởi động (vấn đề K03). Thiết kế mới tách hẳn hai việc. Migration chạy bằng Flyway trong một Kubernetes Job riêng (Helm hook `pre-upgrade`) với tài khoản `mig_<schema>`; pod ứng dụng chỉ nhận thông tin đăng nhập `svc_<schema>`, và Spring Boot cấu hình `spring.flyway.enabled=false`, `spring.jpa.hibernate.ddl-auto=validate`. Khi khởi động, ứng dụng kiểm tra bảng `flyway_schema_history` của schema mình để bảo đảm phiên bản schema không thấp hơn phiên bản tối thiểu mà mã yêu cầu; nếu thấp hơn thì pod dừng với thông báo rõ thay vì chạy sai.

Các thao tác cần quyền cao hơn ở thời điểm chạy, như tạo phân vùng cho kỳ mới, được đóng gói trong hàm `SECURITY DEFINER` do vai trò `mig_<schema>` sở hữu, chỉ nhận tham số đã kiểm tra và đặt `search_path` cố định. Vai trò `svc_<schema>` chỉ có quyền `EXECUTE` trên hàm đó. Cách này cho phép mission-core tạo phân vùng khi quản trị tenant tạo kỳ đánh giá mới mà không cần cấp quyền DDL tổng quát.

## Không phụ thuộc SQL đặc thù của hãng

Tiêu chí E2 nêu hai yêu cầu: không phát sinh phí giấy phép cho mỗi bản cài và SQL không phụ thuộc đặc thù một hãng. Hiện trạng có hơn 1.000 câu SQL đặc thù Oracle (vấn đề E02). Thiết kế áp dụng ba lớp quy tắc. Ở mã nghiệp vụ, truy vấn viết bằng JPQL, Spring Data hoặc jOOQ ở chế độ SQL chuẩn; không dùng `CONNECT BY`, `ROWNUM`, `NVL`, `DECODE`, `MERGE` kiểu Oracle, cũng không dùng hàm riêng của PostgreSQL trong mã nghiệp vụ trừ các truy vấn đã được ghi tên trong danh sách ngoại lệ có review. Ở tầng lược đồ, tài liệu chủ động dùng một số tính năng của PostgreSQL vì chúng là lý do chọn PostgreSQL (ADR-02): bảo mật theo dòng, phân vùng khai báo, `jsonb`, kiểu khoảng (`tstzrange`) và ràng buộc loại trừ `EXCLUDE USING gist`, logical decoding cho Debezium. Các tính năng này đều thuộc PostgreSQL mã nguồn mở giấy phép PostgreSQL License, cài được ngoại tuyến cho bản cách ly, nên không vi phạm mục tiêu của E2. Ở tầng vận hành, mọi tiện ích mở rộng được dùng phải có trong bộ CloudNativePG chuẩn hoặc được đóng gói sẵn trong image do Viettel ký: `btree_gist`, `pg_partman`, `pgcrypto` (chỉ để kiểm tra băm trong công cụ đối soát, không dùng để mã hoá dữ liệu), `pg_stat_statements`, `pgvector` (chỉ trong schema `ai`).

Phiên bản đích là PostgreSQL 17 trên CloudNativePG, node pool CSDL riêng với NVMe cục bộ (ADR-02). PostgreSQL 17 mang lại hai điểm có ích trực tiếp cho thiết kế: logical replication slot có thể được đồng bộ sang bản sao dự phòng (`sync_replication_slots`), nên khi chuyển đổi dự phòng thì Debezium tiếp tục đọc WAL từ bản chính mới mà không mất sự kiện outbox; và cơ chế vacuum dùng ít bộ nhớ hơn cho bảng lớn, có lợi cho các bảng nhật ký hàng trăm triệu dòng như `task.task_state_log`.

## Toàn vẹn dữ liệu trong và ngoài phạm vi giao dịch

Trong một schema, toàn vẹn được bảo đảm bằng khoá chính, khoá ngoại, ràng buộc `CHECK`, ràng buộc loại trừ và giao dịch ACID. Giữa các schema của cùng mission-core và giữa các service, không có khoá ngoại vật lý; toàn vẹn tham chiếu được bảo đảm theo ba cách bổ sung nhau. Cách thứ nhất là kiểm tra tại thời điểm ghi bằng bản sao cục bộ: khi MOD-02 giao nhiệm vụ cho một người, module kiểm tra người đó có phân công hiệu lực trong `org_replica.assignment_v` mà không gọi sang SVC-04. Cách thứ hai là sự kiện bù: khi SVC-04 phát EVT-15 báo một phân công kết thúc, MOD-02 nhận và đánh dấu nhiệm vụ cần giao lại. Cách thứ ba là đối soát định kỳ (chương 17) so khớp tập mã định danh giữa nơi sở hữu và nơi tham chiếu.

Mọi bảng nghiệp vụ dùng khoá lạc quan `row_version`. Câu lệnh cập nhật luôn có dạng `UPDATE ... SET ..., row_version = row_version + 1 WHERE id = ? AND row_version = ?`; nếu số dòng bị ảnh hưởng bằng 0 thì ứng dụng trả HTTP 409 theo quy ước API baseline mục 13. Tiêu chí F9 vốn đã đạt ở hệ hiện tại (cột `VO_VERSION`) nên thiết kế giữ nguyên cách làm quen thuộc này.

# Mô hình dữ liệu khái niệm và mô hình dữ liệu chuẩn trao đổi

## Tám nhóm dữ liệu tối thiểu theo Phụ lục 4

Phụ lục 4 của dự thảo Quy định mới thực chất là một đặc tả phần mềm, trong đó yêu cầu nền tảng quản lý tám nhóm dữ liệu tối thiểu, mỗi nhóm có mã định danh, lịch sử thay đổi và truy vết hai chiều (Báo cáo BGĐ, Phụ lục A6). Báo cáo đề xuất dùng tám nhóm này làm khung cho chuẩn dữ liệu và API quốc gia (kiến nghị thể chế số 1). Vì vậy mô hình khái niệm của nền tảng được dựng đúng theo tám nhóm, sau đó mới ánh xạ xuống schema và bảng. Cách làm này có hai lợi ích. Thứ nhất, khi văn bản chính thức ban hành và có thay đổi về nội dung một nhóm, nhóm thiết kế biết ngay bảng nào bị ảnh hưởng. Thứ hai, mô hình dữ liệu chuẩn dùng để trao đổi với hệ thống ngoài (mục tiếp theo) có cùng cấu trúc, đáp ứng tiêu chí L11.

![Hình 3.1. Mô hình khái niệm tám nhóm dữ liệu PL 4 và chuỗi truy vết từ điểm số tới căn cứ](db-mo-hinh-khai-niem.png){width=14cm}

Hình 3.1 thể hiện tám nhóm dữ liệu cùng các quan hệ chính giữa chúng. Đường đậm là chuỗi truy vết mà PL 4 và slide 31 của Báo cáo BGĐ yêu cầu: từ điểm xếp loại đi ngược về cấu phần điểm, về KPI hoặc KR, về sản phẩm, về minh chứng, về kế hoạch, về mục tiêu O và cuối cùng về căn cứ (nghị quyết, kết luận, chương trình công tác). Mỗi mũi tên trong chuỗi tương ứng với một cột khoá ngoại hoặc một bảng liên kết cụ thể, nên truy vấn đi xuống từ bảng điều hành (SCR-1603) tới minh chứng luôn đi theo khoá, không phải tìm kiếm văn bản. Nhóm "Chủ thể, kỳ" là trục chung mà mọi nhóm khác gắn vào: mỗi bản ghi nghiệp vụ thuộc một tenant, một kỳ và một hoặc nhiều chủ thể (tập thể, cá nhân, vị trí).

Bảng dưới đây liệt kê nội dung tối thiểu của từng nhóm theo PL 4 và ánh xạ sang các bảng trong tài liệu này.

| Nhóm PL 4 | Nội dung tối thiểu theo PL 4 | Schema và bảng chính | Service sở hữu |
|---|---|---|---|
| 1. Chủ thể, kỳ | Tập thể, cá nhân, vị trí; lịch sử phân công, điều động | `org.org_unit`, `org.org_unit_relation`, `org.person`, `org.position`, `org.job_title`, `org.assignment`, `org.subject_group`, `config.period`; bản sao `org_replica.*` | SVC-04, MOD-08 |
| 2. Mục tiêu O, KR | Nguồn, mục tiêu, hạn, trọng số, người xác nhận; truy vết hai chiều | `okr.basis`, `okr.objective`, `okr.key_result`, `okr.okr_link`, `okr.kr_checkin` | MOD-01 |
| 3. KPI | Nội dung đo, đơn vị, nguồn dữ liệu, tỷ trọng, mức khó | `task.work_catalog`, `task.catalog_item`, `policy.policy_rule`, `policy.decision_table` | MOD-02, MOD-05 |
| 4. Chương trình | Tầng 2: chương trình, gói, đợt triển khai, mốc kiểm soát | `task.program`, `task.program_milestone` (bổ sung) | MOD-02 |
| 5. Nhiệm vụ | Tầng 3: trạng thái, liên kết tới O, KR; kiểm soát trùng lặp | `task.plan`, `task.plan_version`, `task.plan_item`, `task.task`, `task.task_assignee`, `task.task_progress`, `task.task_state_log`, `task.task_source_doc`, `task.task_change_request` | MOD-02 |
| 6. Điểm nghẽn, rủi ro | Nguyên nhân, người xử lý, hạn xử lý, mức ảnh hưởng | `task.task_blocker` | MOD-02 |
| 7. Sản phẩm, minh chứng | Phiên bản, tệp, người cập nhật, người xác nhận, thời điểm | `product.deliverable`, `product.evidence`, `product.evidence_version`, `product.acceptance`, `evidence.object_meta`, `evidence.object_version` | MOD-03, SVC-08 |
| 8. Đánh giá, xếp loại | Lưu riêng từng cấu phần; mức tính tự động không thay quyết định | `scoring.score_result`, `scoring.score_trace`, `appraisal.appraisal_profile`, `appraisal.general_criteria_result`, `appraisal.self_assessment`, `appraisal.score_snapshot`, `appraisal.rating_proposal`, `appraisal.rating_explanation`, `appraisal.annual_rating`, `appraisal.prospect_group` | SVC-07, MOD-04 |

Nhóm 4 "Chương trình" không có bảng trong baseline mục 9. Tài liệu này bổ sung hai bảng `task.program` và `task.program_milestone` trong schema `task` của MOD-02, vì chương trình là tầng 2 nằm giữa mục tiêu O (MOD-01) và nhiệm vụ tầng 3 (MOD-02), có vòng đời gắn với kế hoạch công tác quý mà MOD-02 quản lý. Đặt chương trình ở MOD-02 cũng giúp truy vấn "các nhiệm vụ thuộc chương trình X" không phải đi qua ranh giới module.

Một điểm thiết kế quan trọng của nhóm 8 là yêu cầu "lưu riêng từng cấu phần; mức tính tự động không thay quyết định". Thiết kế đáp ứng bằng cách tách bốn mức trong bảng `appraisal.rating_proposal`: mức tự đề xuất, mức thẩm định, mức đề xuất của cấp có thẩm quyền và mức quyết định là bốn dòng khác nhau, phân biệt bằng cột `stage`. Điểm do SVC-07 tính chỉ được ghi vào `scoring.score_result` và chụp vào `appraisal.score_snapshot`; nó không bao giờ tự động trở thành mức quyết định. Cột `decided_by` của dòng `stage = 'QUYET_DINH'` bắt buộc là một người dùng có vai trò `decider`, không thể là tài khoản dịch vụ.

## Hai luồng Đảng và Nhà nước trên một bộ dữ liệu

PL A5 của Báo cáo BGĐ yêu cầu cùng một cán bộ có thể được đánh giá theo hai thang khác nhau trong cùng kỳ: luồng Đảng 100 điểm kết quả nhiệm vụ, luồng Nhà nước 30 điểm tiêu chí chung cộng 70 điểm KPI. Hệ hiện tại chỉ có một hồ sơ cho mỗi người mỗi kỳ (vấn đề H04). Mô hình dữ liệu xử lý bằng cách giữ một tập dữ liệu sản phẩm, minh chứng, nhiệm vụ duy nhất và nhân hồ sơ đánh giá theo luồng. Bảng `appraisal.appraisal_profile` có cột `flow` nhận giá trị `DANG` hoặc `NHA_NUOC` và ràng buộc duy nhất `(tenant_id, period_id, person_id, flow)`. SVC-07 tính điểm hai lần trên cùng dữ liệu đầu vào, mỗi lần dùng phiên bản Policy Pack gắn cho luồng tương ứng qua `policy.policy_binding`. Nhờ vậy cán bộ nhập minh chứng một lần, còn hai hồ sơ có hai điểm, hai mức xếp loại và hai luồng duyệt độc lập.

## Mô hình dữ liệu chuẩn trao đổi

### Vai trò của mô hình dữ liệu chuẩn

Mô hình dữ liệu chuẩn (Canonical Data Model, còn gọi là ngôn ngữ công bố, Published Language) là tập định nghĩa thực thể và trường dùng chung cho mọi bộ kết nối của SVC-13 integration-gateway. Không có mô hình này, mỗi bộ kết nối (vOffice, iOffice, CSDL CBCCVC, HRM của doanh nghiệp) sẽ phải ánh xạ trực tiếp sang bảng nội bộ, và mỗi lần đổi bảng nội bộ phải sửa mọi bộ kết nối. Với mô hình chuẩn, bộ kết nối chỉ ánh xạ một chiều giữa hệ ngoài và mô hình chuẩn; lớp chống ô nhiễm (Anti-Corruption Layer) của SVC-13 ánh xạ mô hình chuẩn sang API và sự kiện nội bộ. Số ánh xạ cần bảo trì giảm từ N × M xuống N + M.

Mô hình chuẩn có phiên bản riêng (`cdm.v1`), được công bố bằng JSON Schema 2020-12 trong tài liệu OpenAPI 3.1 và AsyncAPI 3.0 của SVC-13, tuân thủ chính sách tương thích ngược giống lược đồ sự kiện (chương 11). Sự kiện ra ngoài dùng phong bì CloudEvents 1.0 định dạng JSON, phần `data` là thực thể theo mô hình chuẩn.

### Danh mục thực thể chuẩn

| Thực thể chuẩn | Nhóm PL 4 | Nguồn nội bộ | Trường bắt buộc | Mã định danh ngoài thường gặp |
|---|---|---|---|---|
| `Subject` (Chủ thể) | 1 | `org.person`, `org.org_unit`, `org.position` | `subjectId`, `subjectType` (CA_NHAN, TAP_THE, VI_TRI), `displayName`, `orgUnitId` | Mã CBCCVC, mã đảng viên, mã đơn vị trong vOffice (`VHR_ORG.ORG_ID`) |
| `Assignment` (Phân công) | 1 | `org.assignment` | `personId`, `orgUnitId`, `jobTitleCode`, `kind`, `validFrom`, `validTo` | Mã quyết định bổ nhiệm, điều động |
| `Period` (Kỳ) | 1 | `config.period` | `periodId`, `kind` (THANG, QUY, NAM), `startDate`, `endDate`, `status` | Không có |
| `Objective` (Mục tiêu O) | 2 | `okr.objective` | `objectiveId`, `title`, `basisRefs[]`, `ownerSubjectId`, `periodId`, `status` | Không có |
| `KeyResult` (KR) | 2 | `okr.key_result` | `keyResultId`, `objectiveId`, `metric`, `targetValue`, `currentValue`, `unit`, `weight`, `verifierId` | Mã chỉ tiêu KT-XH của LGSP |
| `KpiDefinition` (KPI) | 3 | `task.catalog_item` | `kpiId`, `code`, `name`, `unit`, `difficulty`, `qualityStandard`, `dataSource` | Mã danh mục chuẩn quốc gia (khi có) |
| `Program` (Chương trình) | 4 | `task.program` | `programId`, `name`, `objectiveIds[]`, `milestones[]` | Mã chương trình, đề án |
| `Task` (Nhiệm vụ) | 5 | `task.task` | `taskId`, `title`, `leadSubjectId`, `coSubjectIds[]`, `dueDate`, `status`, `sourceDocRef` | Mã văn bản chỉ đạo, mã nhiệm vụ của vOffice, iOffice |
| `Blocker` (Điểm nghẽn) | 6 | `task.task_blocker` | `blockerId`, `taskId`, `cause`, `handlerId`, `resolveBy`, `impact` | Không có |
| `Deliverable` (Sản phẩm) | 7 | `product.deliverable` | `deliverableId`, `taskId`, `name`, `unit`, `quantity` | Mã văn bản ban hành |
| `Evidence` (Minh chứng) | 7 | `product.evidence_version`, `evidence.object_meta` | `evidenceId`, `version`, `sha256`, `mediaType`, `submittedBy`, `acceptedBy`, `status` | Mã tệp của hệ văn bản |
| `Appraisal` (Kết quả đánh giá) | 8 | `appraisal.rating_proposal`, `appraisal.score_snapshot` | `personId`, `periodId`, `flow`, `score`, `ratingCode`, `decidedAt`, `policyVersion`, `signatureRef` | Mã hồ sơ CBCCVC |

### Quy ước mã định danh trong mô hình chuẩn

Mã định danh trong mô hình chuẩn là một URN ổn định, độc lập với cell: `urn:vt-okr:<mã-tenant>:<loại>:<uuid>`. Ví dụ `urn:vt-okr:hcm:task:01928c3e-7a51-7c2e-9b0f-1f2a3b4c5d6e`. Phần mã tenant lấy từ `registry.tenant.code`, nên khi một tenant được chuyển cell (ví dụ từ cell pool lên cell bridge khi phát triển lớn), mã định danh mà đối tác đã lưu không đổi. Ánh xạ giữa mã ngoài và mã nội bộ được lưu ở `integ.mapping_xref` (mục 6 schema `integ`), mỗi dòng gồm hệ ngoài, loại thực thể, mã ngoài, mã nội bộ và khoảng thời gian hiệu lực của ánh xạ.

Ví dụ một nhiệm vụ biểu diễn theo mô hình chuẩn, gửi tới hệ văn bản của tỉnh khi nhiệm vụ được giao, trong phong bì CloudEvents:

```json
{
  "specversion": "1.0",
  "type": "vn.viettel.okrkpi.task.assigned.v1",
  "source": "urn:vt-okr:hcm:svc:integration-gateway",
  "id": "01928c3e-8f10-7d55-a1b2-c3d4e5f60718",
  "time": "2027-01-08T02:15:04Z",
  "subject": "urn:vt-okr:hcm:task:01928c3e-7a51-7c2e-9b0f-1f2a3b4c5d6e",
  "datacontenttype": "application/json",
  "dataschema": "https://api.okrkpi.viettel.vn/cdm/v1/Task.json",
  "data": {
    "taskId": "urn:vt-okr:hcm:task:01928c3e-7a51-7c2e-9b0f-1f2a3b4c5d6e",
    "title": "Xây dựng báo cáo chuyên đề công tác kiểm tra quý I/2027",
    "periodId": "urn:vt-okr:hcm:period:01928a00-0000-7000-8000-000000020271",
    "leadSubjectId": "urn:vt-okr:hcm:person:01927f11-2b3c-7d4e-8f90-a1b2c3d4e5f6",
    "coSubjectIds": [],
    "keyResultIds": ["urn:vt-okr:hcm:kr:01928b77-1111-7222-8333-944455566677"],
    "dueDate": "2027-03-20",
    "status": "DA_GIAO",
    "sourceDocRef": { "system": "voffice", "externalId": "VB-2027-000317", "number": "15-KH/ĐU" },
    "classification": "C1"
  }
}
```

Mô hình chuẩn chỉ mang dữ liệu C0 và C1, cùng các trường C2 mà hợp đồng chia sẻ dữ liệu cho phép và chỉ qua kênh mã hoá hai chiều (mTLS) với đối tác. Dữ liệu C3 không bao giờ xuất hiện trong mô hình chuẩn; nhiệm vụ mật được đại diện bằng một bản ghi có tiêu đề che (`"title": "Nhiệm vụ hạn chế"`) và cờ `restricted: true`.

# Danh mục microservice, module và kho dữ liệu

## Cách đọc danh mục

Chương này mô tả trách nhiệm dữ liệu của từng thành phần trong baseline mục 5: 18 service SVC-01 đến SVC-18 và 8 module MOD-01 đến MOD-08 của mission-core. Với mỗi thành phần, tài liệu nêu loại kho, dữ liệu sở hữu (thành phần là nguồn sự thật duy nhất), dữ liệu nhận bản sao (thành phần giữ bản đọc của dữ liệu thuộc nơi khác), sự kiện phát, sự kiện nhận và quy mô ước tính tại cell lớn nhất CELL-HCM với khoảng 1,2 triệu tài khoản. Quy mô là số ước tính dựa trên giả định nêu ở chương 14, không phải số đo.

Nguyên tắc chung là mỗi loại dữ liệu có đúng một nơi sở hữu. Một thành phần nhận bản sao không bao giờ sửa bản sao theo ý mình; muốn thay đổi, nó phải gọi API của nơi sở hữu, và bản sao chỉ thay đổi khi sự kiện từ nơi sở hữu tới.

![Hình 4.1. Tổng quan quyền sở hữu dữ liệu và luồng bản sao trong một cell](db-so-huu-du-lieu.png){width=16cm}

Hình 4.1 cho thấy các kho dữ liệu trong một cell được nhóm theo vùng: vùng lõi nghiệp vụ (tám schema module và `org_replica` trong CSDL dữ liệu tenant), vùng microservice nghiệp vụ (org, scoring, evidence, docgen, notify, integ), vùng bảo mật (ledger, esign, ai) và vùng phía đọc (ClickHouse, OpenSearch). Kafka nằm ở giữa như trục sự kiện; mọi mũi tên từ kho này sang kho khác đều đi qua Kafka, trừ hai trường hợp được ghi rõ: lời gọi REST có Resilience4j cho truy vấn tức thời và các external worker của SVC-06 gọi API của service đích. Không có mũi tên nào nối trực tiếp hai CSDL, đó là cách thiết kế bảo đảm tiêu chí A2.

## Các service hạ tầng và vùng điều phối

### SVC-01 api-gateway

Cổng API Apache APISIX không giữ dữ liệu nghiệp vụ. Cấu hình định tuyến, plugin giới hạn tốc độ theo người dùng, tenant và API, cấu hình WAF Coraza và danh sách JWKS được lưu trong etcd của từng cell và được quản lý theo GitOps (ADR-16), nên etcd chỉ là kho cấu hình có thể dựng lại từ Git. Bộ đếm giới hạn tốc độ dùng plugin `limit-count` với kho Valkey của cell để đếm chính xác giữa nhiều node APISIX.

| Thuộc tính | Nội dung |
|---|---|
| Kho | etcd 3.5 (3 node mỗi cell); Valkey cho bộ đếm giới hạn tốc độ |
| Dữ liệu sở hữu | Route, upstream, consumer, cấu hình plugin, quy tắc strangler định tuyến giữa vOffice cũ và nền tảng mới |
| Dữ liệu nhận bản sao | Bản ánh xạ tên miền tenant → cell từ SVC-03 (chỉ ở lớp APISIX toàn cục) |
| Sự kiện phát / nhận | Không phát sự kiện nghiệp vụ; log truy cập đi Loki qua Fluent Bit |
| Quy mô | Vài nghìn khoá etcd; khoá đếm Valkey có TTL bằng cửa sổ giới hạn (1 giây đến 1 phút) |

### SVC-02 iam

Keycloak 26 lưu người dùng, thông tin xác thực, phiên, cấu hình Organizations và liên kết IdP trong CSDL riêng `iam` trên cụm `pg-iam`. Thiết kế không sửa bảng của Keycloak (baseline mục 9) và không cho service nào đọc trực tiếp CSDL này. Thông tin cá nhân của người dùng (họ tên, thư điện tử, số điện thoại) có bản gốc ở SVC-04; Keycloak chỉ giữ thuộc tính tối thiểu phục vụ đăng nhập và nhận cập nhật từ EVT-16 qua một consumer nhỏ dùng Admin REST API. Mật khẩu (nếu tenant dùng mật khẩu nội bộ) được băm bằng PBKDF2-SHA512 hoặc Argon2 theo chính sách của realm, đáp ứng tiêu chí D4.

| Thuộc tính | Nội dung |
|---|---|
| Kho | PostgreSQL CSDL `iam` trên cụm `pg-iam` riêng của cell; Infinispan nhúng làm cache phiên phân tán |
| Dữ liệu sở hữu | Thông tin xác thực, yếu tố MFA, phiên đăng nhập, liên kết VNeID, liên kết IdP khách hàng, client OIDC |
| Dữ liệu nhận bản sao | Thuộc tính hiển thị của người dùng và tư cách thành viên Organization từ EVT-16, EVT-14 |
| Sự kiện phát | Sự kiện đăng nhập, đăng xuất, đổi quyền qua Event Listener SPI ghi vào Kafka topic `iam.audit.v1` phục vụ SOC (không thuộc danh mục EVT nghiệp vụ) |
| Sự kiện nhận | EVT-14, EVT-16 |
| Quy mô | 1,2 triệu bản ghi `user_entity`; khoảng 60.000 phiên đồng thời lúc cao điểm; bảng sự kiện đăng nhập được tắt lưu trong CSDL và chuyển sang Kafka để tránh phình |

### SVC-03 tenant-registry (toàn cục)

tenant-registry là dịch vụ duy nhất có kho dữ liệu toàn cục. Nó trả lời câu hỏi "tenant này thuộc cell nào, dùng tên miền nào, mô hình thuê bao gì, IdP nào". Dữ liệu này nhỏ, đọc rất nhiều và hiếm khi ghi, nên được sao chép xuống mỗi cell dưới dạng bản đọc để cell vẫn hoạt động khi mất kết nối tới vùng điều phối.

| Thuộc tính | Nội dung |
|---|---|
| Kho | PostgreSQL CSDL `registry` trên cụm toàn cục ba node trải hai trung tâm dữ liệu |
| Dữ liệu sở hữu | `registry.tenant`, `registry.cell`, `registry.tenant_cell`, `registry.tenant_domain`, `registry.tenant_idp`, bảng bổ sung `registry.tenant_status_log` |
| Dữ liệu nhận bản sao | Không có |
| Sự kiện phát | `registry.tenant.changed.v1` (sự kiện hạ tầng, không thuộc danh mục EVT nghiệp vụ) trên Kafka toàn cục, MirrorMaker 2 sao xuống mọi cell |
| Sự kiện nhận | Không có |
| Quy mô | Tối đa 5.000 tenant, khoảng 10.000 tên miền; dưới 100 MB |

### SVC-16 search

OpenSearch là kho chỉ đọc dựng từ sự kiện, không phải nguồn sự thật. Chỉ mục có thể xoá và dựng lại hoàn toàn từ Kafka và API của nơi sở hữu. Theo baseline, chỉ mục không chứa dữ liệu C2 và C3; cơ chế bảo đảm điều này mô tả ở chương 10.

| Thuộc tính | Nội dung |
|---|---|
| Kho | OpenSearch 2.x, một cụm mỗi cell |
| Dữ liệu sở hữu | Không có (chỉ là chiếu dữ liệu) |
| Dữ liệu nhận bản sao | Tiêu đề, mô tả, trạng thái nhiệm vụ; trích yếu văn bản chỉ đạo; danh mục công việc; tên đơn vị |
| Sự kiện nhận | EVT-04, EVT-05, EVT-06, EVT-14, EVT-24 |
| Quy mô | Khoảng 15 triệu tài liệu nhiệm vụ mỗi quý tại CELL-HCM, giữ 8 quý trong chỉ mục nóng |

### SVC-17 web-frontend và SVC-18 mobile-app

Hai thành phần frontend không có CSDL máy chủ. Web lưu UI Pack và bản dịch trong IndexedDB của trình duyệt theo phiên bản (ETag), không lưu dữ liệu C2 hay C3. Ứng dụng di động Flutter có SQLite cục bộ mã hoá bằng SQLCipher (AES-256), khoá lưu trong Android Keystore hoặc iOS Keychain, chỉ chứa bản nháp check-in, bản nháp cập nhật tiến độ và hàng đợi tải minh chứng khi mạng yếu. Bản nháp được gửi lên máy chủ với `Idempotency-Key` sinh ở thiết bị, nên gửi lại nhiều lần không tạo bản ghi trùng.

| Thuộc tính | SVC-17 web-frontend | SVC-18 mobile-app |
|---|---|---|
| Kho | IndexedDB, Cache Storage của trình duyệt | SQLite mã hoá SQLCipher |
| Dữ liệu lưu cục bộ | UI Pack, Design Token, bản dịch, manifest remote | Bản nháp check-in, tiến độ, hàng đợi tải tệp, danh sách "Việc của tôi" chỉ đọc |
| Dữ liệu không được lưu | Token truy cập ngoài bộ nhớ, C2, C3 | C3; C2 dạng rõ ngoài phiên đang mở |
| Sự kiện nhận | EVT-23 (qua CDN, vô hiệu hoá cache UI Pack) | Thông báo đẩy do SVC-12 gửi |

## Lõi nghiệp vụ mission-core (SVC-05) và tám module

mission-core dùng chín schema (tám schema module và `org_replica`) đặt trong CSDL dữ liệu tenant `td_<mã tenant>` trên cụm `pg-biz` của cell. Mỗi module có outbox riêng (`<schema>.outbox_event`) và inbox riêng (`<schema>.inbox_processed`). Sự kiện ứng dụng nội bộ của Spring Modulith giữa hai module trong cùng tiến trình vẫn được ghi vào outbox của module phát, rồi đi qua Kafka như mọi sự kiện khác. Cách này tốn thêm vài mili giây so với gọi trong bộ nhớ nhưng bảo đảm module nhận có inbox idempotent và không mất sự kiện khi pod dừng giữa chừng (khắc phục vấn đề A03 về `MissionSideEffectExecutor`).

### MOD-01 okr

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Căn cứ, mục tiêu O, KR, liên kết dọc và ngang, check-in KR kèm mức tự tin |
| Kho | Schema `okr` trong CSDL dữ liệu tenant |
| Dữ liệu sở hữu | `okr.basis`, `okr.objective`, `okr.key_result`, `okr.okr_link`, `okr.kr_checkin`, `okr.period_state` |
| Dữ liệu nhận bản sao | Tổ chức, nhân sự, phân công qua `org_replica`; trạng thái kỳ qua cache từ EVT-09, EVT-22 |
| Sự kiện phát | EVT-01, EVT-02, EVT-21 |
| Sự kiện nhận | EVT-09, EVT-14, EVT-15, EVT-22 |
| Quy mô CELL-HCM | Khoảng 0,25 triệu O và 0,9 triệu KR mỗi quý (OKR không bắt buộc cho mọi vị trí); 4 triệu check-in mỗi quý |

### MOD-02 task

MOD-02 là module có khối lượng ghi lớn nhất, chứa vòng đời nhiệm vụ chạy bằng máy trạng thái khai báo trong mã (ADR-04 tầng 1), không qua BPMN.

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Danh mục công việc, chương trình, kế hoạch quý có phiên bản, nhiệm vụ, chủ trì và phối hợp, tiến độ, điểm nghẽn, đề nghị gia hạn, nhật ký chuyển trạng thái |
| Kho | Schema `task` trong CSDL dữ liệu tenant; bảng lớn phân vùng theo `period_bucket` |
| Dữ liệu sở hữu | `task.work_catalog`, `task.catalog_item`, `task.program`, `task.program_milestone`, `task.plan`, `task.plan_version`, `task.plan_item`, `task.task`, `task.task_assignee`, `task.task_progress`, `task.task_blocker`, `task.task_change_request`, `task.task_source_doc`, `task.task_state_log`, `task.task_secret_content`, `task.e2ee_key_wrap`, `task.period_state` |
| Dữ liệu nhận bản sao | `org_replica`; văn bản chỉ đạo từ EVT-24 |
| Sự kiện phát | EVT-03, EVT-04, EVT-05, EVT-06, EVT-21 |
| Sự kiện nhận | EVT-01, EVT-09, EVT-15, EVT-18, EVT-22, EVT-24 |
| Quy mô CELL-HCM | 14,4 triệu nhiệm vụ mỗi quý (12 nhiệm vụ mỗi người); 58 triệu dòng tiến độ; 72 triệu dòng nhật ký trạng thái |

### MOD-03 product

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Sản phẩm của nhiệm vụ, minh chứng và phiên bản minh chứng, nghiệm thu chất lượng 5 mức |
| Kho | Schema `product` trong CSDL dữ liệu tenant; tệp thật nằm ở Ceph do SVC-08 quản lý |
| Dữ liệu sở hữu | `product.deliverable`, `product.evidence`, `product.evidence_version`, `product.acceptance`, `product.e2ee_key_wrap`, `product.period_state` |
| Dữ liệu nhận bản sao | Kết quả quét mã độc và mã băm từ SVC-08 (qua phản hồi API tải lên và sự kiện nội bộ `evidence.object.scanned.v1`); trạng thái nhiệm vụ từ EVT-06 |
| Sự kiện phát | EVT-07, EVT-08, EVT-21 |
| Sự kiện nhận | EVT-06, EVT-09, EVT-22 |
| Quy mô CELL-HCM | 9,6 triệu phiên bản minh chứng mỗi quý; 3,6 triệu lượt nghiệm thu |

### MOD-04 appraisal

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Hồ sơ đánh giá theo luồng, tiêu chí chung, tự đánh giá, ảnh chụp điểm, bốn mức đề xuất, giải trình, xếp loại năm, phân nhóm triển vọng |
| Kho | Schema `appraisal` trong CSDL dữ liệu tenant |
| Dữ liệu sở hữu | `appraisal.appraisal_profile`, `appraisal.general_criteria_result`, `appraisal.self_assessment`, `appraisal.score_snapshot`, `appraisal.rating_proposal`, `appraisal.rating_explanation`, `appraisal.annual_rating`, `appraisal.prospect_group` |
| Dữ liệu nhận bản sao | `org_replica`; điểm từ EVT-10; trạng thái ký từ EVT-18; trường hợp đặc thù đọc qua interface công bố của MOD-06 trong cùng tiến trình |
| Sự kiện phát | EVT-11, EVT-12, EVT-21 |
| Sự kiện nhận | EVT-09, EVT-10, EVT-15, EVT-18, EVT-22 |
| Quy mô CELL-HCM | 1,2 triệu hồ sơ quý cho mỗi luồng áp dụng; tối đa 2,4 triệu hồ sơ khi một người thuộc cả hai luồng; 9,6 triệu dòng đề xuất mỗi quý |

### MOD-05 policy

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Kho Policy Pack của cell (đồng bộ từ kho gốc toàn cục), phiên bản, quy tắc CEL, bảng quyết định DMN, test case vàng, kết quả chạy kiểm thử, gắn phiên bản cho tenant, khối, kỳ |
| Kho | Schema `policy` trong CSDL dữ liệu tenant; tệp gói đã ký lưu Ceph bucket `policy-<cell>` |
| Dữ liệu sở hữu | `policy.policy_pack`, `policy.policy_pack_version`, `policy.policy_rule`, `policy.decision_table`, `policy.golden_test_case`, `policy.test_run`, `policy.policy_binding` |
| Dữ liệu nhận bản sao | Gói gốc từ kho Policy Pack toàn cục |
| Sự kiện phát | EVT-13 |
| Sự kiện nhận | Không có sự kiện nghiệp vụ |
| Quy mô | Vài trăm phiên bản, vài chục nghìn test case; dưới 1 GB |

### MOD-06 dossier

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Hội nghị kiểm điểm, góp ý, biên bản, gói hồ sơ số quý và năm, biểu mẫu đã sinh, 13 trường hợp đặc thù |
| Kho | Schema `dossier` trong CSDL dữ liệu tenant; tệp PDF/A do SVC-11 sinh lưu Ceph |
| Dữ liệu sở hữu | `dossier.review_meeting`, `dossier.review_comment`, `dossier.dossier_package`, `dossier.form_instance`, `dossier.special_case` |
| Sự kiện phát | EVT-21 và sự kiện nội bộ yêu cầu sinh biểu mẫu `dossier.form.requested.v1` |
| Sự kiện nhận | EVT-12, EVT-17, EVT-18 |
| Quy mô CELL-HCM | 1,2 triệu gói hồ sơ quý; 6 triệu biểu mẫu mỗi năm |

### MOD-07 oversight

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Khiếu nại và trả lời, yêu cầu mở lại kỳ và hai lượt phê duyệt, hậu kiểm, cảnh báo bất thường và hành động xử lý |
| Kho | Schema `oversight` trong CSDL dữ liệu tenant |
| Dữ liệu sở hữu | `oversight.complaint`, `oversight.complaint_response`, `oversight.reopen_request`, `oversight.reopen_approval`, `oversight.post_audit`, `oversight.anomaly_alert`, `oversight.alert_action` |
| Sự kiện phát | EVT-22, EVT-25, EVT-21 |
| Sự kiện nhận | EVT-20, EVT-12 |
| Quy mô CELL-HCM | Vài chục nghìn khiếu nại mỗi năm; khoảng 100.000 cảnh báo mỗi quý |

### MOD-08 config

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Cấu hình tenant, kỳ đánh giá và trạng thái khoá, lịch ngày nghỉ, UI Pack, biểu mẫu động, cờ tính năng, gắn vai trò theo đơn vị |
| Kho | Schema `config` trong CSDL dữ liệu tenant; tệp UI Pack đã biên dịch lưu Ceph, phát qua CDN |
| Dữ liệu sở hữu | `config.tenant_setting`, `config.period`, `config.holiday_calendar`, `config.ui_pack`, `config.ui_pack_version`, `config.form_schema`, `config.feature_flag`, `config.role_binding`, `config.period_status_log` |
| Sự kiện phát | EVT-09, EVT-23 |
| Sự kiện nhận | EVT-22 |
| Quy mô | Vài nghìn dòng mỗi tenant |

### org_replica trong mission-core

Schema `org_replica` không thuộc module nào; nó được một thành phần hạ tầng của mission-core (gói `replica`) cập nhật từ sự kiện của SVC-04, và mọi module được cấp quyền chỉ đọc. Đây là ngoại lệ có chủ đích với quy tắc "mỗi module chỉ đọc schema của mình", được ArchUnit và quyền CSDL cùng kiểm soát: vai trò `svc_org_replica` là vai trò duy nhất có quyền ghi.

| Thuộc tính | Nội dung |
|---|---|
| Dữ liệu nhận bản sao | `org_replica.org_unit_v`, `org_replica.person_v`, `org_replica.assignment_v`, bảng bổ sung `org_replica.unit_closure_v`, `org_replica.replica_checkpoint`, `org_replica.reconcile_run` |
| Sự kiện nhận | EVT-14, EVT-15, EVT-16 |
| Độ trễ mục tiêu | p99 ≤ 5 giây từ khi SVC-04 cam kết giao dịch |
| Quy mô CELL-HCM | 40.000 đơn vị, 1,2 triệu người, khoảng 1,5 triệu phân công hiệu lực |

## Các microservice nghiệp vụ

### SVC-04 org-service

org-service là nguồn sự thật về cây tổ chức, nhân sự, vị trí việc làm, chức danh và phân công, với dữ liệu hai trục thời gian (chương 7). Nó phục vụ cả nền tảng đánh giá và hệ điều hành văn bản (bước 01 của lộ trình tách nền tảng, slide 16), cung cấp SCIM 2.0 cho đồng bộ từ nguồn chuẩn (tiêu chí L4).

| Thuộc tính | Nội dung |
|---|---|
| Kho | PostgreSQL schema `org` trong CSDL dữ liệu tenant `td_<mã>` trên cụm `pg-biz` |
| Dữ liệu sở hữu | `org.org_unit`, `org.org_unit_relation`, `org.person`, `org.position`, `org.job_title`, `org.assignment`, `org.subject_group`, `org.scim_sync_log`, bảng bổ sung `org.person_contact`, `org.person_public_key`, `org.org_change_order` |
| Dữ liệu nhận bản sao | Không có; dữ liệu từ CSDL CBCCVC, CSDL đảng viên, HRM đi vào qua SCIM do SVC-13 gọi |
| Sự kiện phát | EVT-14, EVT-15, EVT-16 |
| Sự kiện nhận | Không có sự kiện nghiệp vụ; nhận lệnh SCIM qua API |
| Quy mô CELL-HCM | 1,2 triệu người; 40.000 đơn vị; 1,5 triệu phân công hiệu lực; khoảng 0,4 triệu phiên bản lịch sử mỗi năm |

### SVC-06 workflow-service

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Định nghĩa và thực thi luồng duyệt BPMN, bảng quyết định DMN, saga chốt kỳ và phát hành Policy Pack |
| Kho | PostgreSQL CSDL `flowable` trên cụm `pg-wf` riêng |
| Dữ liệu sở hữu | Bảng `ACT_*`, `FLW_*` của Flowable; `flowable.wf_template`, `flowable.wf_binding`, `flowable.wf_task_projection`, bảng bổ sung `flowable.saga_instance`, `flowable.saga_step` |
| Dữ liệu nhận bản sao | Không có; biến tiến trình chỉ chứa mã định danh, không chứa C2 |
| Sự kiện phát | Sự kiện tiến trình nội bộ `workflow.task.created.v1`, `workflow.task.completed.v1` dùng cho hộp việc; EVT-09 được phát qua MOD-08 theo lệnh của saga |
| Sự kiện nhận | EVT-13, EVT-25 và các yêu cầu bắt đầu luồng từ MOD-01, MOD-02, MOD-04, MOD-07 |
| Quy mô CELL-HCM | 2,4 triệu tiến trình và 9,6 triệu user task mỗi quý; bảng runtime chỉ giữ tiến trình đang chạy, cao điểm khoảng 0,7 triệu |

### SVC-07 scoring-service

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Tính KPI từng nhiệm vụ, tổng hợp điểm hồ sơ, xếp loại đề xuất, lưu vết tính và mã băm đầu vào |
| Kho | PostgreSQL schema `scoring` trong CSDL dữ liệu tenant `td_<mã>` trên cụm `pg-biz`; bảng `scoring.score_input_v` bổ sung giữ bản đọc dữ liệu đầu vào |
| Dữ liệu sở hữu | `scoring.score_job`, `scoring.score_result`, `scoring.score_trace` |
| Dữ liệu nhận bản sao | Dữ liệu đầu vào tối thiểu từ EVT-06, EVT-08 (số lượng, mức chất lượng, ngày chậm), Policy Pack từ EVT-13 |
| Sự kiện phát | EVT-10 |
| Sự kiện nhận | EVT-06, EVT-08, EVT-09, EVT-13, EVT-22 |
| Quy mô CELL-HCM | 2,4 triệu kết quả mỗi quý; 30 triệu dòng vết tính |

### SVC-08 evidence-service

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Lưu tệp minh chứng dạng luồng, tính SHA-256, khử trùng theo tenant, quét mã độc, gọi OCR, giữ Object Lock |
| Kho | PostgreSQL schema `evidence` trong CSDL dữ liệu tenant `td_<mã>` trên cụm `pg-biz`; Ceph RGW bucket `ev-<cell>-<tenant>` |
| Dữ liệu sở hữu | `evidence.object_meta`, `evidence.object_version`, `evidence.dedup_index`, `evidence.scan_result`, bảng bổ sung `evidence.upload_session` |
| Sự kiện phát | `evidence.object.scanned.v1` (nội bộ, MOD-03 tiêu thụ) |
| Sự kiện nhận | Không có sự kiện nghiệp vụ |
| Quy mô CELL-HCM | 9,6 triệu đối tượng mỗi quý, khoảng 38 TB dữ liệu gốc mỗi năm trước khử trùng |

### SVC-09 ledger-service

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Sổ cái chỉ ghi thêm, chuỗi băm 64 phân vùng mỗi tenant, niêm phong Merkle hai mức mỗi giờ (5 phút trong 3 ngày cuối kỳ), dấu thời gian RFC 3161, biên nhận bản neo, kiểm tra toàn vẹn |
| Kho | PostgreSQL CSDL `ledger` trên cụm `pg-ledger` riêng; bản lưu trữ phân vùng cũ dạng Parquet trên Ceph Object Lock |
| Dữ liệu sở hữu | `ledger.ledger_entry`, `ledger.merkle_seal`, `ledger.anchor_receipt`, `ledger.verify_run`, bảng bổ sung `ledger.chain_head`, `ledger.archive_segment` |
| Sự kiện phát | EVT-19 |
| Sự kiện nhận | EVT-01 đến EVT-05, EVT-07 đến EVT-09, EVT-11 đến EVT-13, EVT-18, EVT-22, EVT-25 |
| Quy mô CELL-HCM | Khoảng 180 triệu bản ghi mỗi quý, 720 triệu mỗi năm; 8.760 niêm phong mỗi năm cho mỗi tenant |

### SVC-10 esign-service

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Yêu cầu ký, từng mục ký trong lô, tham chiếu chứng thư, kết quả ký và dấu thời gian |
| Kho | PostgreSQL CSDL `esign` trên cụm `pg-sec` của vùng bảo mật |
| Dữ liệu sở hữu | `esign.sign_request`, `esign.sign_item`, `esign.certificate_ref` |
| Sự kiện phát | EVT-18 |
| Sự kiện nhận | EVT-17 và yêu cầu ký từ MOD-02, MOD-04, MOD-06 |
| Quy mô CELL-HCM | Khoảng 0,1 lần ký mỗi người mỗi tuần, tức 1,6 triệu chữ ký mỗi quý |

### SVC-11 document-service

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Mẫu biểu Mẫu 1A đến 1D, 2, 3, bảng chấm điểm; tác vụ sinh; tệp đầu ra PDF/A |
| Kho | PostgreSQL schema `docgen` trong CSDL dữ liệu tenant `td_<mã>` trên cụm `pg-biz`; Ceph bucket `docgen-<cell>` |
| Dữ liệu sở hữu | `docgen.template`, `docgen.render_job`, `docgen.render_output` |
| Sự kiện phát | EVT-17 |
| Sự kiện nhận | `dossier.form.requested.v1` |
| Quy mô CELL-HCM | 1,2 triệu đến 2,4 triệu biểu mẫu mỗi quý; lô 100.000 phiếu trong ≤ 4 giờ (NFR-06) |

### SVC-12 notification-service

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Thông báo, lượt gửi theo kênh, tuỳ chọn kênh của người dùng, mẫu thông báo |
| Kho | PostgreSQL schema `notify` trong CSDL dùng chung của cell `cell_shared` trên cụm `pg-biz`, tách tenant bằng RLS; Valkey Streams cho phân phối thời gian thực qua SSE, WebSocket |
| Dữ liệu sở hữu | `notify.notification`, `notify.delivery`, `notify.channel_pref`, `notify.template` |
| Sự kiện nhận | EVT-02, EVT-03, EVT-04, EVT-06, EVT-07, EVT-09, EVT-11, EVT-20, EVT-21, EVT-25 |
| Quy mô CELL-HCM | Khoảng 50 triệu thông báo mỗi quý; giữ 90 ngày |

### SVC-13 integration-gateway

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Cấu hình bộ kết nối, hộp thư đến từ hệ ngoài, ánh xạ mã ngoài và mã nội bộ, con trỏ đồng bộ, hàng thư chết, gói chuyển hồ sơ liên cell |
| Kho | PostgreSQL schema `integ` trong CSDL dùng chung của cell `cell_shared` trên cụm `pg-biz`, tách tenant bằng RLS |
| Dữ liệu sở hữu | `integ.connector`, `integ.inbox_message`, `integ.mapping_xref`, `integ.sync_cursor`, `integ.dead_letter`, bảng bổ sung `integ.outbound_message`, `integ.transfer_package` |
| Sự kiện phát | EVT-24 |
| Sự kiện nhận | EVT-12, EVT-19 |
| Quy mô CELL-HCM | Vài triệu thông điệp mỗi quý; `mapping_xref` khoảng 5 triệu dòng |

### SVC-14 ai-gateway

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Đoạn văn bản và véc-tơ nhúng cho RAG, nhật ký gợi ý (nội dung, nguồn, người chấp nhận), sự kiện rào chắn |
| Kho | PostgreSQL CSDL `ai` trên cụm `pg-sec` có pgvector |
| Dữ liệu sở hữu | `ai.doc_chunk`, `ai.suggestion_log`, `ai.guardrail_event` |
| Dữ liệu nhận bản sao | Văn bản chỉ đạo và quy định C0, C1 từ EVT-24 và kho văn bản quy phạm |
| Sự kiện nhận | EVT-24 |
| Quy mô CELL-HCM | Khoảng 20 triệu đoạn văn bản, véc-tơ 1.024 chiều, chỉ mục HNSW khoảng 100 GB |

### SVC-15 analytics

| Thuộc tính | Nội dung |
|---|---|
| Trách nhiệm dữ liệu | Kho phân tích CQRS phía đọc, mô hình sao, lớp ngữ nghĩa Cube, job phát hiện 8 dấu hiệu bất thường |
| Kho | ClickHouse CSDL `analytics_<cell>` (2 shard × 2 bản sao ở cell lớn) |
| Dữ liệu sở hữu | Bảng tổng hợp và kết quả phát hiện bất thường (nguồn của EVT-20) |
| Dữ liệu nhận bản sao | Dòng CDC từ các schema nghiệp vụ (chỉ cột C0, C1 và C2 đã giả danh hoá), sự kiện EVT-01 đến EVT-12 |
| Sự kiện phát | EVT-20 |
| Quy mô CELL-HCM | Khoảng 400 triệu dòng sự kiện mỗi quý, nén khoảng 10 lần |

Bảng tổng hợp sau giúp tra nhanh nơi lưu và cụm CSDL của từng thành phần trong một cell; cách phân bổ cụm theo mô hình thuê bao được giải thích ở chương 5.

| Thành phần | Kho chính | Cụm/hạ tầng | Vùng mạng |
|---|---|---|---|
| SVC-02 iam | PostgreSQL `iam` | `pg-iam` | Vùng định danh |
| SVC-04 org-service | Schema `org` trong `td_<mã>` | `pg-biz` | Vùng nghiệp vụ |
| SVC-05 mission-core | 9 schema trong CSDL `td_<mã>` | `pg-biz` | Vùng nghiệp vụ |
| SVC-06 workflow-service | PostgreSQL `flowable` | `pg-wf` | Vùng nghiệp vụ |
| SVC-07 scoring-service | Schema `scoring` trong `td_<mã>` | `pg-biz` | Vùng nghiệp vụ |
| SVC-08 evidence-service | Schema `evidence` trong `td_<mã>` + Ceph | `pg-biz`, Ceph RGW | Vùng nghiệp vụ |
| SVC-09 ledger-service | PostgreSQL `ledger` | `pg-ledger` | Vùng bảo mật |
| SVC-10 esign-service | PostgreSQL `esign` | `pg-sec` | Vùng bảo mật |
| SVC-11 document-service | Schema `docgen` trong `td_<mã>` + Ceph | `pg-biz`, Ceph RGW | Vùng nghiệp vụ |
| SVC-12 notification-service | Schema `notify` trong `cell_shared` + Valkey Streams | `pg-biz`, Valkey | Vùng nghiệp vụ |
| SVC-13 integration-gateway | Schema `integ` trong `cell_shared` | `pg-biz` | Vùng tích hợp (DMZ nội bộ) |
| SVC-14 ai-gateway | PostgreSQL `ai` (pgvector) | `pg-sec` | Vùng bảo mật, GPU |
| SVC-15 analytics | ClickHouse | Cụm ClickHouse của cell | Vùng phía đọc |
| SVC-16 search | OpenSearch | Cụm OpenSearch của cell | Vùng phía đọc |
| SVC-03 tenant-registry | PostgreSQL `registry` | Cụm toàn cục | Vùng điều phối toàn cục |

# Mô hình thuê bao và phân bổ cơ sở dữ liệu theo cell

## Ba mô hình thuê bao trên cùng một lược đồ

ADR-07 chọn kiến trúc cell với ba mô hình thuê bao: silo (cell riêng), bridge (CSDL riêng mỗi tenant trong cụm dùng chung) và pool (bảng chung, cột `tenant_id` và bảo mật theo dòng). Yêu cầu quan trọng nhất với tầng dữ liệu là cả ba mô hình phải dùng chung một lược đồ và một bộ migration. Nếu mỗi mô hình có lược đồ riêng thì mỗi bản phát hành phải kiểm thử ba lần và mã ứng dụng phải rẽ nhánh, đi ngược mục tiêu "một mã nguồn, ba cách triển khai" của Báo cáo BGĐ (slide 40). Thiết kế đạt yêu cầu này bằng ba quyết định.

Quyết định thứ nhất là mọi bảng nghiệp vụ luôn có cột `tenant_id` và luôn bật bảo mật theo dòng, kể cả ở cell silo chỉ có một tenant. Chi phí của RLS khi chỉ mục bắt đầu bằng `tenant_id` là không đáng kể (một phép so sánh với giá trị đã được bộ lập kế hoạch đánh giá một lần cho mỗi câu lệnh), trong khi lợi ích là lớp bảo vệ thứ hai không phụ thuộc vào việc lập trình viên nhớ thêm điều kiện lọc. Quyết định thứ hai là đơn vị đặt dữ liệu vật lý là "CSDL dữ liệu tenant" (`td_<mã>`), chứa các schema nghiệp vụ theo tenant. Ở silo và bridge, mỗi tenant có một CSDL dữ liệu tenant; ở pool, nhiều tenant dùng chung một CSDL `td_pool`. Quyết định thứ ba là các service phục vụ nhiều tenant cùng lúc theo bản chất (thông báo, tích hợp, luồng duyệt, sổ cái, ký số, AI) dùng CSDL cấp cell, tách tenant bằng RLS hoặc bằng cơ chế tenant riêng của engine (Flowable có cột `TENANT_ID_` trên mọi bảng runtime).

![Hình 5.1. Phân bổ cụm PostgreSQL và CSDL trong một cell theo ba mô hình thuê bao](db-cum-csdl-cell.png){width=16cm}

Hình 5.1 cho thấy năm cụm PostgreSQL trong một cell và cách CSDL được đặt vào từng cụm ở ba mô hình. Cụm `pg-biz` là nơi duy nhất có sự khác biệt giữa ba mô hình: silo có một CSDL dữ liệu tenant, bridge có từ bảy đến chín CSDL dữ liệu tenant (một CSDL mỗi tỉnh), pool có một CSDL `td_pool` chứa hàng nghìn tenant. Bốn cụm còn lại (`pg-wf`, `pg-ledger`, `pg-iam`, `pg-sec`) có cấu trúc giống nhau ở mọi cell, chỉ khác kích thước.

## Các cụm PostgreSQL trong một cell

Mỗi cụm là một đối tượng `Cluster` của CloudNativePG gồm một bản chính và hai bản dự phòng trong trung tâm dữ liệu chính, đặt trên ba node khác nhau của node pool CSDL (ràng buộc `podAntiAffinity` bắt buộc). Một bản dự phòng chạy chế độ đồng bộ (`synchronous_standby_names = 'ANY 1 (*)'`) để đạt RPO xấp xỉ 0 khi mất một node (NFR-05). Ở trung tâm dữ liệu dự phòng phía Nam, mỗi cụm có một cụm bản sao (CloudNativePG replica cluster) nhận WAL qua luồng sao chép và qua kho lưu trữ WAL trên Ceph, đạt RPO ≤ 15 phút khi mất cả trung tâm. Cách tách năm cụm được giải thích ở bảng sau; kích thước là của cell lớn nhất CELL-HCM và được tính ở chương 14.

| Cụm | CSDL chứa trong cụm | Lý do tách cụm riêng | Cấu hình node ở CELL-HCM (ước tính) |
|---|---|---|---|
| `pg-biz` | Các CSDL dữ liệu tenant `td_<mã>` (hoặc `td_pool`); `cell_shared` (schema `notify`, `integ`) | Khối lượng ghi nghiệp vụ lớn nhất, tối ưu cho OLTP | 3 × (32 vCPU, 256 GB RAM, NVMe 2 × 7,68 TB RAID 1) |
| `pg-wf` | `flowable` | Engine BPMN có mẫu truy cập riêng (job executor, khoá hàng đợi); cô lập để nghẽn engine không ảnh hưởng ghi nghiệp vụ (góp ý G2) | 3 × (16 vCPU, 128 GB, NVMe 3,84 TB) |
| `pg-ledger` | `ledger` | Vùng bảo mật, chỉ ghi thêm, quyền quản trị tách khỏi đội vận hành nghiệp vụ; dung lượng tăng tuyến tính và lưu ≥ 10 năm | 3 × (16 vCPU, 64 GB, NVMe 7,68 TB) |
| `pg-iam` | `iam` | Keycloak cần độ khả dụng cao nhất; sự cố ở CSDL nghiệp vụ không được làm mất khả năng đăng nhập | 3 × (8 vCPU, 32 GB, NVMe 960 GB) |
| `pg-sec` | `esign`, `ai` | Vùng bảo mật cho ký số và AI; `ai` có pgvector với chỉ mục HNSW cần nhiều RAM | 3 × (16 vCPU, 192 GB, NVMe 3,84 TB) |

Việc tách `pg-wf` khỏi `pg-biz` là một phần của câu trả lời cho góp ý G2 về nghẽn engine BPMN. Bảng `ACT_RU_JOB` và `ACT_RU_TIMER_JOB` của Flowable có tần suất `SELECT ... FOR UPDATE SKIP LOCKED` rất cao khi async executor chạy nhiều luồng; nếu chạy chung cụm với `task.task` thì độ trễ ghi của nhiệm vụ sẽ dao động theo tải của engine. Tách cụm giúp hai hồ sơ tải không ảnh hưởng nhau, và cho phép định cỡ cụm `pg-wf` theo đúng số user task mỗi giây (80 đến 110 lần mỗi giây ở cell lớn theo baseline mục 3.1).

## Bố trí theo mô hình silo

Cell silo (CELL-DTW, CELL-HCM, CELL-HN) phục vụ một tenant chính hoặc một nhóm tenant cùng chủ quản. Ví dụ CELL-DTW phục vụ tenant Đảng ủy các cơ quan Đảng Trung ương với 19 đảng bộ là các đơn vị cấp một trong cây tổ chức, không phải 19 tenant. Cụm `pg-biz` có một CSDL dữ liệu tenant, ví dụ `td_hcm`, cộng CSDL `cell_shared`. Toàn bộ hạ tầng (Kafka, Ceph, Valkey, OpenSearch, ClickHouse, HSM) là của riêng cell. Riêng CELL-DTW dùng HSM riêng và khoá KEK riêng của khối Đảng.

| CSDL trên `pg-biz` (CELL-HCM) | Schema | Chủ sở hữu |
|---|---|---|
| `td_hcm` | `config`, `policy`, `okr`, `task`, `product`, `appraisal`, `dossier`, `oversight`, `org_replica` | SVC-05 mission-core (mỗi schema một vai trò) |
| `td_hcm` | `org` | SVC-04 org-service |
| `td_hcm` | `scoring` | SVC-07 scoring-service |
| `td_hcm` | `evidence` | SVC-08 evidence-service |
| `td_hcm` | `docgen` | SVC-11 document-service |
| `cell_shared` | `notify` | SVC-12 notification-service |
| `cell_shared` | `integ` | SVC-13 integration-gateway |

## Bố trí theo mô hình bridge

Cell bridge (CELL-G1 đến CELL-G4) phục vụ bảy đến chín tỉnh. Mỗi tỉnh là một tenant với một CSDL dữ liệu tenant riêng trên cụm `pg-biz` dùng chung của cell, ví dụ `td_thainguyen`, `td_quangngai`. Mỗi CSDL dữ liệu tenant có cùng mười ba schema như silo. Các service có kết nối riêng tới từng CSDL thông qua PgBouncer: PgBouncer khai báo một mục `[databases]` cho mỗi CSDL dữ liệu tenant, còn ứng dụng chọn nguồn dữ liệu theo `tenant_id` của request bằng `AbstractRoutingDataSource` của Spring (Java) hoặc một bản đồ pool theo tenant (Go). Mỗi tenant có pool phía ứng dụng nhỏ (tối thiểu 2, tối đa 8 kết nối mỗi pod), và PgBouncer gom về tối đa 30 kết nối máy chủ cho mỗi CSDL.

Mô hình bridge có ba lợi ích cụ thể cho khách hàng cấp tỉnh. Thứ nhất, dữ liệu của tỉnh nằm trong một CSDL có thể sao lưu và khôi phục riêng bằng `pg_dump` hoặc khôi phục điểm thời gian vào một cụm tạm mà không ảnh hưởng tỉnh khác. Thứ hai, khoá mã hoá trường của tỉnh là khoá riêng (slide 40: "khoá theo tỉnh"). Thứ ba, khi tỉnh chuyển sang đám mây riêng của tỉnh, toàn bộ dữ liệu được chuyển bằng sao chép logic một CSDL. Chi phí là số slot sao chép logic tăng theo số tỉnh (chín slot cho chín CSDL dữ liệu tenant, cộng một cho `cell_shared`), vẫn trong ngưỡng an toàn của một cụm.

## Bố trí theo mô hình pool

Cell pool CELL-SH phục vụ xã, chi bộ, đơn vị nhỏ và doanh nghiệp, tới hàng nghìn tenant với khoảng 0,8 triệu tài khoản. Toàn bộ tenant dùng chung CSDL `td_pool`. Ở mô hình này RLS là cơ chế cô lập chính, nên tài liệu đặt thêm ba biện pháp. Biện pháp thứ nhất là phân vùng con theo băm `tenant_id`: các bảng lớn đã phân vùng LIST theo `period_bucket` được chia tiếp thành 16 phân vùng con theo `HASH (tenant_id)`, nhờ vậy truy vấn của một tenant nhỏ chỉ quét một phần mười sáu dữ liệu của kỳ và VACUUM chạy song song được. Biện pháp thứ hai là giới hạn tài nguyên theo tenant: cổng API giới hạn tốc độ theo tenant, vai trò ứng dụng đặt `statement_timeout = '5s'` và `idle_in_transaction_session_timeout = '10s'`, job nặng như tính lại điểm chạy theo hàng đợi có trọng số công bằng theo tenant. Biện pháp thứ ba là quy trình "tốt nghiệp" tenant: khi một tenant trong pool phát triển vượt 50.000 tài khoản hoặc yêu cầu khoá riêng, dữ liệu của nó được chuyển sang một CSDL dữ liệu tenant riêng ở cell bridge bằng sao chép logic có lọc dòng, tính năng có từ PostgreSQL 15.

```sql
-- Trên td_pool: công bố chỉ dữ liệu của một tenant (lọc dòng theo tenant_id)
CREATE PUBLICATION pub_graduate_t42
  FOR TABLE task.task WHERE (tenant_id = '01927a00-0000-7000-8000-000000000042'),
            task.task_assignee WHERE (tenant_id = '01927a00-0000-7000-8000-000000000042'),
            okr.objective WHERE (tenant_id = '01927a00-0000-7000-8000-000000000042')
  WITH (publish_via_partition_root = true);
-- Trên CSDL đích td_xa_tanphu (đã chạy đủ migration Flyway cùng phiên bản)
CREATE SUBSCRIPTION sub_graduate_t42
  CONNECTION 'host=pg-biz-g2-rw dbname=td_pool user=replicator sslmode=verify-full'
  PUBLICATION pub_graduate_t42 WITH (copy_data = true);
```

Danh sách bảng thực tế được công cụ sinh từ danh mục schema (mọi bảng có `tenant_id`), không viết tay. Sau khi bản sao đuổi kịp, quy trình chuyển đổi mô tả trong TL-04 khoá ghi tenant trong vài phút, cập nhật `registry.tenant_cell` để định tuyến sang cell mới, rồi xoá dữ liệu tenant khỏi `td_pool` theo lô.

## Bảo mật theo dòng (Row-Level Security)

### Chính sách mẫu

Mọi bảng có cột `tenant_id` được bật RLS ở chế độ bắt buộc. Chế độ `FORCE ROW LEVEL SECURITY` quan trọng vì theo mặc định chủ sở hữu bảng không chịu RLS; dù vai trò ứng dụng không phải chủ sở hữu, bật `FORCE` loại bỏ rủi ro khi ai đó vô tình chạy ứng dụng bằng vai trò `mig_<schema>`. Không vai trò ứng dụng nào có thuộc tính `BYPASSRLS`.

```sql
ALTER TABLE task.task ENABLE ROW LEVEL SECURITY;
ALTER TABLE task.task FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_task__tenant ON task.task
  AS PERMISSIVE
  FOR ALL
  TO svc_task, ro_task
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

Biểu thức `current_setting('app.tenant_id', true)` trả về NULL nếu biến chưa được đặt trong giao dịch, khi đó điều kiện so sánh cho kết quả NULL và không dòng nào được trả về hoặc được ghi. Đây là hành vi "mặc định từ chối" mong muốn: một đoạn mã quên đặt ngữ cảnh tenant sẽ thấy dữ liệu trống và lỗi ghi, không bao giờ thấy dữ liệu của tenant khác. Mệnh đề `WITH CHECK` ngăn việc chèn hoặc cập nhật một dòng mang `tenant_id` khác với ngữ cảnh, kể cả khi lỗi nằm ở tầng ứng dụng.

Với bảng phân vùng, chính sách đặt trên bảng cha chỉ áp dụng khi truy vấn đi qua bảng cha. Nếu ai đó truy vấn trực tiếp một phân vùng con thì chính sách của bảng cha không có hiệu lực. Vì vậy hàm `fn_create_period_partitions` (chương 14) khi tạo phân vùng mới sẽ thu hồi mọi quyền của vai trò ứng dụng trên phân vùng con và đồng thời bật RLS kèm chính sách tương tự trên phân vùng con, để bảo vệ hai lớp.

### Đặt ngữ cảnh tenant an toàn qua PgBouncer chế độ giao dịch

PgBouncer chạy ở chế độ `pool_mode = transaction`: một kết nối máy chủ chỉ gắn với một kết nối khách trong thời gian một giao dịch, sau đó được trả về pool cho khách khác. Hệ quả là mọi tham số đặt bằng `SET` ở mức phiên sẽ "rò" sang giao dịch của khách tiếp theo dùng lại kết nối đó, có thể là của tenant khác. Thiết kế cấm tuyệt đối `SET app.tenant_id` ở mức phiên và chỉ dùng `SET LOCAL` hoặc hàm tương đương `set_config(..., true)`, vốn tự huỷ khi giao dịch kết thúc dù là `COMMIT` hay `ROLLBACK`. Để `SET LOCAL` có hiệu lực, mọi truy cập CSDL phải nằm trong giao dịch tường minh, kể cả truy vấn đọc (dùng `@Transactional(readOnly = true)`); truy vấn ở chế độ tự động cam kết sẽ không có ngữ cảnh và nhận kết quả rỗng theo chính sách mặc định từ chối ở trên.

Ở mission-core và các service Java, ngữ cảnh tenant được đặt trong trình quản lý giao dịch, ngay sau khi giao dịch bắt đầu, nên không lập trình viên nào phải nhớ gọi.

```java
public class TenantJpaTransactionManager extends JpaTransactionManager {

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    super.doBegin(transaction, definition);
    UUID tenantId = TenantContext.requireTenantId(); // lấy từ JWT đã xác thực, ném lỗi nếu thiếu
    EntityManager em = EntityManagerFactoryUtils
        .getTransactionalEntityManager(obtainEntityManagerFactory());
    em.unwrap(Session.class).doWork(conn -> {
      try (PreparedStatement ps =
               conn.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
        ps.setString(1, tenantId.toString());
        ps.execute();
      }
    });
  }
}
```

Service viết bằng Go (SVC-03, SVC-09, SVC-12) dùng pgx và đặt ngữ cảnh ngay sau `BeginTx` bằng `tx.Exec(ctx, "select set_config('app.tenant_id', $1, true)", tenantID)`. Service Python (SVC-14) làm tương tự với asyncpg. Câu lệnh này được gửi cùng chuyến mạng với câu lệnh đầu tiên của giao dịch nhờ cơ chế pipeline, nên chi phí thêm dưới 0,1 ms.

PgBouncer 1.21 trở lên hỗ trợ câu lệnh chuẩn bị ở mức giao thức trong chế độ giao dịch (`max_prepared_statements`), nên trình điều khiển JDBC có thể giữ `prepareThreshold` mặc định để tận dụng kế hoạch truy vấn đã chuẩn bị. Cấu hình PgBouncer đặt `server_reset_query` rỗng (không cần ở chế độ giao dịch) và `server_check_query = 'select 1'`.

### Kiểm thử rò rỉ tenant

Rò rỉ dữ liệu giữa tenant là sự cố nghiêm trọng nhất có thể xảy ra ở cell pool, nên thiết kế đặt năm lớp kiểm thử, chạy tự động.

Lớp thứ nhất là kiểm tra danh mục trong CI. Sau khi áp toàn bộ migration lên CSDL trống, câu truy vấn sau phải trả về không dòng; nếu có dòng, bản build bị chặn.

```sql
SELECT n.nspname AS schema_name, c.relname AS table_name
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
WHERE c.relkind IN ('r', 'p')
  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
  AND (NOT c.relrowsecurity
       OR NOT c.relforcerowsecurity
       OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid));
```

Lớp thứ hai là bộ kiểm thử tích hợp bằng Testcontainers với hai tenant A và B: với mỗi bảng, đặt ngữ cảnh A rồi thử đọc, cập nhật, xoá dòng của B (kết quả phải là 0 dòng), thử chèn dòng mang `tenant_id` của B (phải lỗi vi phạm chính sách), thử truy vấn khi không đặt ngữ cảnh (phải rỗng). Bộ kiểm thử được sinh tự động từ danh mục bảng nên bảng mới có ngay ca kiểm thử. Lớp thứ ba là kiểm thử qua PgBouncer với `default_pool_size = 1` để ép mọi giao dịch dùng chung một kết nối máy chủ, chạy xen kẽ hàng nghìn giao dịch của hai tenant và kiểm tra không giao dịch nào thấy dữ liệu của tenant kia, nhằm phát hiện việc ai đó lỡ dùng `SET` mức phiên. Lớp thứ tư là kiểm thử tĩnh: Semgrep chặn mọi chuỗi `SET app.` không có `LOCAL` và mọi lời gọi `set_config(..., false)` trong mã nguồn. Lớp thứ năm chạy trên môi trường thật: mỗi cell pool có hai tenant "canh gác" với dữ liệu tổng hợp, một job chạy mỗi giờ đăng nhập bằng tài khoản của tenant canh gác thứ nhất và gọi các API danh sách chính; nếu bất kỳ phản hồi nào chứa mã định danh của tenant canh gác thứ hai thì cảnh báo mức nghiêm trọng được gửi tới SOC.

## Định tuyến tới đúng CSDL

Mỗi request đến cell mang `tenant_id` trong JWT (claim `tid` do Keycloak Organizations cấp). Service tra bảng định tuyến cục bộ (bản đọc của `registry.tenant_cell` được đồng bộ xuống cell, lưu trong bộ nhớ, làm mới theo sự kiện `registry.tenant.changed.v1`) để biết tenant thuộc CSDL dữ liệu tenant nào. Nếu tenant không thuộc cell hiện tại (ví dụ đã được chuyển cell nhưng DNS của người dùng chưa cập nhật), service trả HTTP 421 Misdirected Request kèm tên miền đúng, APISIX lớp ngoài định tuyến lại. Cách làm này giúp một request không bao giờ ghi nhầm vào CSDL của cell khác, kể cả trong thời gian chuyển đổi.

# Thiết kế chi tiết các schema

## Cách trình bày và các bảng dùng chung

Chương này thiết kế chi tiết hai mươi schema theo thứ tự: `registry`, `org`, `org_replica`, `config`, `policy`, `okr`, `task`, `product`, `appraisal`, `dossier`, `oversight`, `flowable`, `scoring`, `evidence`, `ledger`, `esign`, `docgen`, `notify`, `integ`, `ai`. Mỗi schema có một sơ đồ thực thể liên kết (ERD) chỉ thể hiện các cột khoá và cột quan trọng, một đoạn giải thích thiết kế, rồi từ điển dữ liệu cho từng bảng. Trong từ điển, dòng "(cột chung)" thay cho bảy cột chung ở mục Cột chung của bảng nghiệp vụ; nếu bảng có phân vùng theo kỳ thì có thêm dòng `period_id`, `period_bucket`. Cột "Null" ghi "Không" khi cột có ràng buộc `NOT NULL`. Ước tính số dòng là của CELL-HCM (1,2 triệu tài khoản) và là số ước tính, cách tính ở chương 14.

Tên bảng được giữ đúng baseline mục 9. Bảng bổ sung được ghi rõ "bảng bổ sung" trong đoạn giải thích. Các bảng kỹ thuật sau có mặt trong mọi schema nghiệp vụ (`outbox_event`, `inbox_processed`, `inbox_aggregate_version`, `idempotency_key`) hoặc trong mọi CSDL (`modulith.event_publication` ở CSDL dữ liệu tenant, `keyring.dek` ở mọi CSDL có cột C2), nên được mô tả một lần ở đây.

### Bảng outbox_event

Bảng `outbox_event` hiện thực mẫu hộp thư đi giao dịch (Transactional Outbox). Ứng dụng ghi dữ liệu nghiệp vụ và dòng outbox trong cùng một giao dịch; Debezium đọc dòng mới từ WAL và đẩy lên Kafka (chương 10). Bảng được phân vùng theo ngày để việc dọn dẹp là thao tác `DROP` phân vùng cũ, không phải `DELETE` hàng triệu dòng gây phình bảng và tải VACUUM.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK (`id`, `created_at`) | C1 | Mã sự kiện (`eventId`, UUIDv7) |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant phát sự kiện |
| `aggregate_type` | `varchar(64)` | Không | | C1 | Loại thực thể gốc, ví dụ `Task`, `EvidenceVersion` |
| `aggregate_id` | `uuid` | Không | | C1 | Mã thực thể gốc |
| `aggregate_version` | `bigint` | Không | | C1 | `row_version` của thực thể sau thay đổi; dùng chống sai thứ tự ở phía nhận |
| `event_type` | `varchar(96)` | Không | | C1 | Tên nghiệp vụ, ví dụ `NhiemVuDaGiao` |
| `topic` | `varchar(128)` | Không | | C1 | Topic đích, ví dụ `task.task.assigned.v1`; Debezium định tuyến theo cột này |
| `event_key` | `varchar(80)` | Không | | C1 | Khoá Kafka `tenantId:aggregateId` |
| `payload` | `bytea` | Không | | Theo trường | Avro nhị phân đã tuần tự hoá bằng serde Apicurio; trường C2 trong payload đã ở dạng bản mã |
| `schema_version` | `int` | Không | | C1 | Phiên bản lược đồ Avro trong Apicurio |
| `headers` | `jsonb` | Không | DEFAULT `'{}'` | C1 | `traceparent`, `actorId`, `policyVersion`, `classification`, `occurredAt` |
| `created_at` | `timestamptz` | Không | Khoá phân vùng RANGE theo ngày | C1 | Thời điểm ghi |

```sql
CREATE TABLE task.outbox_event (
  id                uuid         NOT NULL,
  tenant_id         uuid         NOT NULL,
  aggregate_type    varchar(64)  NOT NULL,
  aggregate_id      uuid         NOT NULL,
  aggregate_version bigint       NOT NULL,
  event_type        varchar(96)  NOT NULL,
  topic             varchar(128) NOT NULL,
  event_key         varchar(80)  NOT NULL,
  payload           bytea        NOT NULL,
  schema_version    int          NOT NULL,
  headers           jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at        timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_outbox_event PRIMARY KEY (id, created_at),
  CONSTRAINT ck_outbox_event__topic CHECK (topic ~ '^[a-z]+\.[a-z]+\.[a-z]+\.v[0-9]+$')
) PARTITION BY RANGE (created_at);

-- pg_partman tạo trước 7 phân vùng ngày, giữ 3 ngày (Debezium chỉ cần dòng tới khi đọc xong WAL)
SELECT partman.create_parent(
  p_parent_table   => 'task.outbox_event',
  p_control        => 'created_at',
  p_interval       => '1 day',
  p_premake        => 7);
UPDATE partman.part_config
   SET retention = '3 days', retention_keep_table = false
 WHERE parent_table = 'task.outbox_event';

ALTER TABLE task.outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE task.outbox_event FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_outbox_event__tenant ON task.outbox_event FOR ALL TO svc_task
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

Bảng outbox không có chỉ mục phụ vì không ai truy vấn nó theo điều kiện; Debezium đọc từ WAL chứ không đọc bảng. Vai trò `cdc_task` cần quyền `SELECT` chỉ để chụp ảnh ban đầu khi tạo connector, và connector outbox được cấu hình `snapshot.mode = never` nên trong vận hành thường không quét bảng.

### Bảng inbox_processed và inbox_aggregate_version

Bảng `inbox_processed` là hộp thư đến idempotent theo baseline mục 8. Khi nhận một sự kiện, consumer chèn `(consumer, event_id)` trong cùng giao dịch với thay đổi nghiệp vụ; nếu khoá chính đã tồn tại thì sự kiện đã được xử lý và consumer bỏ qua. Bảng bổ sung `inbox_aggregate_version` lưu phiên bản cao nhất của mỗi thực thể gốc mà consumer đã áp dụng, để xử lý sự kiện đến sai thứ tự (chương 10).

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `event_id` | `uuid` | Không | PK (`consumer`, `event_id`) | C1 | Mã sự kiện đã xử lý |
| `consumer` | `varchar(100)` | Không | PK | C1 | Tên consumer, ví dụ `mod-04.score-computed` |
| `processed_at` | `timestamptz` | Không | DEFAULT `now()`; chỉ mục `ix_inbox_processed__processed_at` | C1 | Thời điểm xử lý; job dọn xoá dòng cũ hơn 14 ngày |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant của sự kiện |
| `source_topic` | `varchar(128)` | Không | | C1 | Topic nguồn, phục vụ chẩn đoán |
| `kafka_partition` | `int` | Không | | C1 | Phân vùng Kafka |
| `kafka_offset` | `bigint` | Không | | C1 | Offset Kafka |

```sql
CREATE TABLE appraisal.inbox_processed (
  event_id        uuid         NOT NULL,
  consumer        varchar(100) NOT NULL,
  processed_at    timestamptz  NOT NULL DEFAULT now(),
  tenant_id       uuid         NOT NULL,
  source_topic    varchar(128) NOT NULL,
  kafka_partition int          NOT NULL,
  kafka_offset    bigint       NOT NULL,
  CONSTRAINT pk_inbox_processed PRIMARY KEY (consumer, event_id)
);
CREATE INDEX ix_inbox_processed__processed_at ON appraisal.inbox_processed (processed_at);

CREATE TABLE appraisal.inbox_aggregate_version (
  consumer       varchar(100) NOT NULL,
  aggregate_type varchar(64)  NOT NULL,
  aggregate_id   uuid         NOT NULL,
  tenant_id      uuid         NOT NULL,
  last_version   bigint       NOT NULL,
  last_event_id  uuid         NOT NULL,
  updated_at     timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_inbox_aggregate_version PRIMARY KEY (consumer, aggregate_type, aggregate_id)
);
```

Bảng `inbox_processed` không phân vùng, vì nếu phân vùng theo thời gian thì khoá phân vùng phải nằm trong khoá chính và ràng buộc duy nhất `(consumer, event_id)` sẽ không còn bảo đảm trên toàn bảng. Thời gian lưu 14 ngày được chọn lớn gấp đôi thời gian lưu mặc định của topic Kafka (7 ngày), nên một sự kiện bị phát lại từ Kafka luôn còn dấu vết trong inbox.

| Cột của `inbox_aggregate_version` | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `consumer` | `varchar(100)` | Không | PK | C1 | Tên consumer |
| `aggregate_type` | `varchar(64)` | Không | PK | C1 | Loại thực thể gốc |
| `aggregate_id` | `uuid` | Không | PK | C1 | Mã thực thể gốc |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `last_version` | `bigint` | Không | | C1 | `aggregate_version` cao nhất đã áp dụng |
| `last_event_id` | `uuid` | Không | | C1 | Sự kiện tương ứng |
| `updated_at` | `timestamptz` | Không | | C1 | Thời điểm cập nhật |

### Bảng idempotency_key

Bảng bổ sung `<schema>.idempotency_key` (ví dụ `task.idempotency_key`) hiện thực khoá chống lặp cho API ghi theo thư viện nền `vt-platform-starter` của TL-01. Khi client gửi tiêu đề `Idempotency-Key`, bộ lọc chèn khoá trong cùng giao dịch với thao tác nghiệp vụ; nếu khoá đã tồn tại với cùng băm yêu cầu, API trả lại đúng phản hồi đã lưu; nếu khoá trùng mà băm yêu cầu khác, API trả lỗi 422. Bảng phân vùng theo ngày bằng pg_partman và giữ 48 giờ, đủ cho ứng dụng di động gửi lại sau khi mất mạng.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `tenant_id` | `uuid` | Không | PK (`tenant_id`, `actor_id`, `idem_key`, `created_day`); RLS | C1 | Tenant |
| `actor_id` | `uuid` | Không | PK | C1 | Người gọi (`sub` của JWT); khoá chỉ có nghĩa trong phạm vi một người |
| `idem_key` | `varchar(64)` | Không | PK | C1 | Giá trị tiêu đề `Idempotency-Key` (UUID do client sinh) |
| `created_day` | `date` | Không | PK, khoá phân vùng RANGE ngày | C1 | Ngày tạo |
| `request_hash` | `bytea` | Không | | C1 | SHA-256 của phương thức, đường dẫn và thân yêu cầu chuẩn hoá |
| `status` | `varchar(12)` | Không | CHECK IN (`DANG_XU_LY`, `XONG`) | C1 | Trạng thái |
| `response_code` | `smallint` | Có | | C1 | Mã HTTP đã trả |
| `response_body` | `jsonb` | Có | | C1 | Phản hồi đã trả (không chứa trường C2, chỉ mã tham chiếu) |
| `resource_ref` | `varchar(128)` | Có | | C1 | Mã tài nguyên đã tạo |
| `created_at` | `timestamptz` | Không | | C1 | Thời điểm |

Các cột `idempotency_key` sẵn có trong `okr.kr_checkin` và `task.task_progress` là khoá chống trùng ở mức nghiệp vụ (một lần check-in, một báo cáo tiến độ), còn bảng này chống trùng ở mức API cho mọi thao tác ghi.

### Schema modulith và bảng event_publication

Trong mission-core, các module trao đổi sự kiện trong tiến trình bằng Spring Modulith; sự kiện ra ngoài luôn đi qua outbox và Kafka (ADR-23 của TL-01). Để sự kiện trong tiến trình không bị mất khi pod dừng giữa chừng, Spring Modulith dùng Event Publication Registry: mỗi sự kiện được ghi vào bảng `modulith.event_publication` cùng giao dịch phát, mỗi bộ lắng nghe đánh dấu hoàn tất sau khi xử lý xong, và sự kiện chưa hoàn tất được phát lại khi khởi động. Schema `modulith` là schema bổ sung, nằm trong CSDL dữ liệu tenant, do vai trò `mig_modulith` sở hữu; mọi vai trò `svc_<module>` của mission-core được cấp `SELECT, INSERT, UPDATE, DELETE` trên bảng này (ngoại lệ duy nhất cho nguyên tắc không truy cập chéo, vì bảng thuộc khung ứng dụng chứ không thuộc module nào). Cấu trúc bảng theo lược đồ chuẩn của Spring Modulith 1.3, bổ sung `tenant_id` và RLS.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã lần phát |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant (bổ sung so với lược đồ chuẩn) |
| `listener_id` | `varchar(512)` | Không | Chỉ mục (`listener_id`, `completion_date`) | C1 | Bộ lắng nghe đích |
| `event_type` | `varchar(512)` | Không | | C1 | Lớp sự kiện |
| `serialized_event` | `text` | Không | | C1 | Sự kiện tuần tự hoá JSON; không chứa trường C2 dạng rõ |
| `publication_date` | `timestamptz` | Không | | C1 | Thời điểm phát |
| `completion_date` | `timestamptz` | Có | Chỉ mục một phần `WHERE completion_date IS NULL` | C1 | Thời điểm xử lý xong |

Job dọn xoá bản ghi đã hoàn tất cũ hơn 7 ngày; sự kiện chưa hoàn tất quá 15 phút tạo cảnh báo.

### Schema keyring và bảng dek

Khoá mã hoá dữ liệu (DEK) của mọi cột C2 trong một CSDL được giữ ở schema bổ sung `keyring`, bảng `keyring.dek`, theo quyết định ADR-30 của TL-01: mỗi DEK ứng với một tổ hợp tenant, tháng và mục đích. CSDL chỉ chứa DEK đã bọc bằng khoá mã hoá khoá (KEK) nằm trong HSM qua OpenBao Transit, không bao giờ chứa DEK dạng rõ. Mọi cột `dek_id` trong các schema nghiệp vụ trỏ tới bảng này (tham chiếu logic trong cùng CSDL, có khoá ngoại khi cùng CSDL). Vai trò `svc_<module>` chỉ có `SELECT` và `INSERT`; chỉ dịch vụ quản lý khoá (job xoay vòng, job xoá bằng mật mã) chạy bằng vai trò `svc_keyring` mới được đổi trạng thái. Thiết kế chi tiết ở chương 9.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | `dek_id` được các cột `_enc` tham chiếu |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant sở hữu khoá |
| `purpose` | `varchar(24)` | Không | UQ (`tenant_id`, `purpose`, `valid_month`) | C1 | Mục đích, ví dụ `NHAN_XET_DANH_GIA`, `KHIEU_NAI`, `GOP_Y_KIEM_DIEM`, `DINH_DANH_CA_NHAN`, `TEP_MINH_CHUNG`, `BLIND_INDEX` |
| `valid_month` | `date` | Không | | C1 | Tháng khoá được dùng để mã hoá mới |
| `wrapped_dek` | `bytea` | Không | | C1 | DEK đã bọc bằng KEK (định dạng `vault:v<n>:...` của OpenBao Transit) |
| `kek_ref` | `varchar(200)` | Không | | C1 | Tên và phiên bản KEK, ví dụ `transit/keys/t-hcm-c2@v3` |
| `alg_version` | `smallint` | Không | DEFAULT 1 | C1 | Phiên bản thuật toán, trùng byte phiên bản trong bản mã (1 là AES-256-GCM; bản quốc phòng dùng thuật toán của Ban Cơ yếu) |
| `status` | `varchar(16)` | Không | CHECK IN (`ACTIVE`, `DECRYPT_ONLY`, `RETIRED`, `DESTROYED`) | C1 | Vòng đời khoá |
| `created_at` | `timestamptz` | Không | | C1 | Thời điểm tạo |
| `destroyed_at` | `timestamptz` | Có | | C1 | Thời điểm huỷ (xoá bằng mật mã) |

Chia DEK theo mục đích cho phép xoá bằng mật mã có chọn lọc: khi hết thời hạn lưu giữ của nội dung khiếu nại, chỉ cần huỷ các DEK mục đích `KHIEU_NAI` của những tháng tương ứng, không ảnh hưởng nhận xét đánh giá cùng tháng.

## Schema registry (toàn cục, SVC-03)

### Thiết kế

Schema `registry` là danh bạ tenant toàn cục, nằm ở cụm PostgreSQL của vùng điều phối toàn cục. Nó là schema duy nhất không có cột `tenant_id` theo nghĩa RLS, vì chính nó định nghĩa tenant; quyền truy cập được giới hạn cho SVC-03 và vai trò `platform_admin` qua API. Quan hệ tenant với cell là quan hệ có thời gian hiệu lực (`registry.tenant_cell`), vì một tenant có thể được chuyển cell khi tốt nghiệp từ pool lên bridge hoặc khi tách cell; lịch sử này cần cho việc định tuyến lại các tham chiếu cũ và cho kiểm toán. Ràng buộc loại trừ bảo đảm tại một thời điểm một tenant chỉ thuộc đúng một cell.

![Hình 6.1. ERD schema registry](db-erd-registry.png){width=14cm}

Hình 6.1 cho thấy `registry.tenant` là trung tâm, có nhiều tên miền, nhiều IdP và một chuỗi gắn cell theo thời gian. Bảng bổ sung `registry.tenant_status_log` lưu mọi lần đổi trạng thái hợp đồng của tenant (khởi tạo, hoạt động, tạm dừng, chuyển cell, chấm dứt), phục vụ kiểm toán và tính cước.

### Từ điển dữ liệu

#### Bảng registry.tenant

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã tenant, dùng làm `tenant_id` ở mọi cell |
| `code` | `varchar(32)` | Không | UQ; CHECK `^[a-z0-9_]+$` | C0 | Mã ngắn, dùng trong URN mô hình chuẩn và tên CSDL `td_<code>` |
| `name` | `varchar(255)` | Không | | C0 | Tên khách hàng, ví dụ "Thành uỷ Thành phố Hồ Chí Minh" |
| `sector` | `varchar(24)` | Không | CHECK IN (`DANG`, `CHINH_QUYEN`, `SU_NGHIEP`, `TRUONG_HOC`, `BENH_VIEN`, `LLVT`, `DOANH_NGHIEP`) | C1 | Nhóm khách hàng (Báo cáo BGĐ slide 23) |
| `tenancy_model` | `varchar(8)` | Không | CHECK IN (`SILO`, `BRIDGE`, `POOL`, `AIRGAP`) | C1 | Mô hình thuê bao |
| `status` | `varchar(24)` | Không | CHECK IN (`KHOI_TAO`, `DANG_CAP_PHAT`, `DANG_CAU_HINH`, `SAN_SANG`, `DANG_VAN_HANH`, `TAM_NGUNG`, `DANG_CHUYEN_CELL`, `DA_CHAM_DUT`) | C1 | Trạng thái vòng đời tenant (bộ trạng thái của TL-02a QT-01, thêm `DANG_CHUYEN_CELL` cho chuyển cell) |
| `contract_ref` | `varchar(100)` | Có | | C1 | Số hợp đồng |
| `contract_valid_to` | `date` | Có | | C1 | Ngày hết hạn hợp đồng |
| `max_accounts` | `int` | Không | CHECK > 0 | C1 | Hạn mức tài khoản theo hợp đồng |
| (cột chung trừ `tenant_id`) | | | | C1 | `created_at`, `created_by`, `updated_at`, `updated_by`, `row_version` |
| `onboarding_code` | `varchar(32)` | Có | UQ | C1 | Mã hồ sơ khởi tạo tenant (TL-02a QT-01) |
| `flows` | `varchar(12)[]` | Không | CHECK phần tử IN (`DANG`, `NHA_NUOC`) | C1 | Luồng đánh giá tenant sử dụng |
| `expected_accounts` | `int` | Có |  | C1 | Số tài khoản dự kiến khi khởi tạo |
| `account_count` | `int` | Không | DEFAULT 0 | C1 | Số tài khoản hiện có, cập nhật hằng đêm từ SVC-02 |
| `kek_ref` | `varchar(200)` | Không |  | C1 | Tên KEK của tenant trong OpenBao Transit (chương 9) |

#### Bảng registry.cell

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã cell |
| `code` | `varchar(16)` | Không | UQ | C0 | Mã cell theo baseline mục 6, ví dụ `CELL-HCM` |
| `name` | `varchar(255)` | Không | | C0 | Tên mô tả |
| `primary_dc` | `varchar(16)` | Không | CHECK IN (`HOALAC`, `PHIANAM`, `TAICHO`) | C1 | Trung tâm dữ liệu chính |
| `standby_dc` | `varchar(16)` | Có | | C1 | Trung tâm dữ liệu dự phòng |
| `tenancy_model` | `varchar(8)` | Không | CHECK IN (`SILO`, `BRIDGE`, `POOL`, `AIRGAP`) | C1 | Mô hình của cell |
| `api_base_url` | `varchar(255)` | Không | | C1 | Địa chỉ cổng API của cell |
| `capacity_accounts` | `int` | Không | | C1 | Sức chứa thiết kế (số tài khoản) |
| `status` | `varchar(16)` | Không | CHECK IN (`HOAT_DONG`, `BAO_TRI`, `NGUNG`) | C1 | Trạng thái cell |
| (cột chung trừ `tenant_id`) | | | | C1 | |

#### Bảng registry.tenant_cell

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã dòng |
| `tenant_id` | `uuid` | Không | FK `registry.tenant` | C1 | Tenant |
| `cell_id` | `uuid` | Không | FK `registry.cell` | C1 | Cell |
| `database_name` | `varchar(63)` | Không | | C1 | Tên CSDL dữ liệu tenant, ví dụ `td_hcm` hoặc `td_pool` |
| `valid_from` | `timestamptz` | Không | | C1 | Bắt đầu thuộc cell |
| `valid_to` | `timestamptz` | Không | DEFAULT `'infinity'` | C1 | Kết thúc thuộc cell |
| | | | EX `ex_tenant_cell__no_overlap`: `(tenant_id WITH =, tstzrange(valid_from, valid_to) WITH &&)` | | Một tenant chỉ thuộc một cell tại một thời điểm |
| (cột chung trừ `tenant_id`) | | | | C1 | |
| `tenancy_model` | `varchar(8)` | Không | CHECK IN (`silo`, `bridge`, `pool`) | C1 | Mô hình thuê bao trong lần gắn cell này |
| `data_region` | `varchar(32)` | Không |  | C1 | Vùng đặt dữ liệu (trung tâm dữ liệu) |

#### Bảng registry.tenant_domain

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã dòng |
| `tenant_id` | `uuid` | Không | FK `registry.tenant` | C1 | Tenant |
| `domain` | `varchar(253)` | Không | UQ | C0 | Tên miền, ví dụ `kpi.hochiminhcity.gov.vn` |
| `kind` | `varchar(8)` | Không | CHECK IN (`PRIMARY`, `ALIAS`, `EMBED`) | C1 | Tên miền chính, bí danh hoặc tên miền cổng văn phòng số nhúng giao diện |
| `tls_cert_ref` | `varchar(200)` | Có | | C1 | Tham chiếu chứng thư TLS trong OpenBao |
| `verified_at` | `timestamptz` | Có | | C1 | Thời điểm xác minh quyền sở hữu tên miền |
| (cột chung trừ `tenant_id`) | | | | C1 | |

#### Bảng registry.tenant_idp

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã dòng |
| `tenant_id` | `uuid` | Không | FK `registry.tenant` | C1 | Tenant |
| `idp_alias` | `varchar(64)` | Không | UQ (`tenant_id`, `idp_alias`) | C1 | Bí danh IdP trong Keycloak |
| `protocol` | `varchar(8)` | Không | CHECK IN (`OIDC`, `SAML`) | C1 | Giao thức liên kết |
| `issuer` | `varchar(255)` | Không | | C1 | Issuer của IdP khách hàng hoặc VNeID |
| `is_vneid` | `boolean` | Không | DEFAULT false | C1 | Đánh dấu IdP định danh điện tử VNeID |
| `email_domains` | `text[]` | Không | DEFAULT `'{}'` | C1 | Gợi ý khám phá IdP theo tên miền thư (home realm discovery) |
| `status` | `varchar(16)` | Không | CHECK IN (`HOAT_DONG`, `TAM_DUNG`) | C1 | Trạng thái |
| (cột chung trừ `tenant_id`) | | | | C1 | |

#### Bảng registry.tenant_status_log (bổ sung)

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã dòng |
| `tenant_id` | `uuid` | Không | FK `registry.tenant` | C1 | Tenant |
| `from_status` | `varchar(24)` | Có | | C1 | Trạng thái cũ |
| `to_status` | `varchar(24)` | Không | | C1 | Trạng thái mới |
| `reason` | `text` | Không | | C1 | Lý do, số văn bản |
| `actor_id` | `uuid` | Không | | C1 | Người thực hiện |
| `changed_at` | `timestamptz` | Không | | C1 | Thời điểm |

Schema `registry` có dưới 100.000 dòng tổng cộng, không cần phân vùng. Chỉ mục duy nhất trên `tenant_domain.domain` phục vụ tra cứu tên miền ở APISIX lớp ngoài (kết quả được cache trong bộ nhớ của APISIX, làm mới mỗi 30 giây).

#### Bảng registry.tenant_quota (bổ sung)

Bảng `registry.tenant_quota` (đề xuất của TL-01) lưu hạn mức tài nguyên của từng tenant. Cổng API đọc bản sao trong Valkey để giới hạn tần suất, SVC-08 kiểm hạn mức dung lượng trước khi cấp URL tải lên, SVC-14 kiểm hạn mức lượt suy luận.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | UQ (`tenant_id`, `quota_key`) | C1 | Tenant |
| `quota_key` | `varchar(48)` | Không | CHECK IN (`API_RPS`, `STORAGE_GB`, `ACCOUNTS`, `AI_TOKENS_MONTH`, `SIGN_PER_DAY`, `EXPORT_PER_DAY`) | C1 | Loại hạn mức |
| `limit_value` | `bigint` | Không | | C1 | Giá trị giới hạn |
| `soft_limit_pct` | `smallint` | Không | DEFAULT 85 | C1 | Ngưỡng cảnh báo |
| `used_value` | `bigint` | Không | DEFAULT 0 | C1 | Mức đã dùng, cập nhật định kỳ |
| `effective_from`, `effective_to` | `date` | Không/Có | | C1 | Hiệu lực theo hợp đồng |
| `updated_at`, `updated_by` | | Không | | C1 | Cập nhật |

## Schema org (SVC-04 org-service)

### Thiết kế

Schema `org` lưu cây tổ chức, nhân sự, vị trí việc làm, chức danh và phân công theo mô hình hai trục thời gian (bitemporal). Trục hiệu lực (`valid_from`, `valid_to`) trả lời "trên thực tế, điều này đúng trong khoảng thời gian nào", ví dụ quyết định điều động có hiệu lực từ 1/3/2027. Trục ghi nhận (`recorded_from`, `recorded_to`) trả lời "hệ thống biết điều này từ lúc nào đến lúc nào", ví dụ quyết định điều động trên được nhập vào hệ thống ngày 5/3/2027, và ngày 10/3/2027 có quyết định đính chính. Nhờ hai trục, nền tảng tái hiện được chính xác "hệ thống biết gì về cây tổ chức tại thời điểm cấp có thẩm quyền ra quyết định xếp loại", đáp ứng tiêu chí I7 và lớp bảo vệ thứ 4 (chống sửa lùi ngày). Cơ chế chi tiết ở chương 7.

Khác với các schema khác, bảng hai trục thời gian có hai loại mã: `version_id` là khoá chính của từng phiên bản dòng, còn `id` là mã thực thể ổn định mà mọi nơi khác tham chiếu (một đơn vị có một `id` suốt đời nhưng nhiều `version_id`). Phiên bản dòng không bao giờ bị sửa nội dung; thay đổi luôn là đóng trục ghi nhận của phiên bản cũ (`recorded_to = now()`) và chèn phiên bản mới. Vai trò `svc_org` có quyền `UPDATE` duy nhất trên cột `recorded_to` (cấp quyền theo cột), không có quyền `DELETE`.

Hai cây tổ chức song song (cây Đảng và cây chuyên môn, Báo cáo BGĐ slide 12) được biểu diễn bằng cột `tree` trong `org.org_unit_relation`, cho phép cùng một đơn vị nằm ở hai vị trí trên hai cây. Bảng bổ sung `org.org_change_order` lưu quyết định tổ chức cán bộ (bổ nhiệm, điều động, sáp nhập, chia tách) làm căn cứ cho mỗi phiên bản; mọi phiên bản có `change_order_id` trỏ tới quyết định sinh ra nó. Bảng bổ sung `org.person_contact` tách thông tin liên lạc C2 khỏi `org.person` để chỉ một số ít luồng được giải mã. Bảng bổ sung `org.person_public_key` lưu khoá công khai của người dùng phục vụ mã hoá đầu cuối dữ liệu C3 (chương 9).

![Hình 6.2. ERD schema org với các bảng hai trục thời gian](db-erd-org.png){width=16cm}

Hình 6.2 cho thấy `org.assignment` là bảng giao điểm: một phân công nối một người, một đơn vị, một vị trí việc làm và một chức danh trong một khoảng hiệu lực. Một người có thể có nhiều phân công đồng thời (một phân công chính và nhiều kiêm nhiệm), nhưng ràng buộc loại trừ chặn hai phân công chính chồng lấn.

### DDL mẫu: org.assignment hai trục thời gian

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE org.assignment (
  version_id      uuid         NOT NULL,
  id              uuid         NOT NULL,          -- mã phân công ổn định
  tenant_id       uuid         NOT NULL,
  person_id       uuid         NOT NULL,          -- org.person.id
  org_unit_id     uuid         NOT NULL,          -- org.org_unit.id
  position_id     uuid,                           -- org.position.id
  job_title_id    uuid         NOT NULL,          -- org.job_title.id
  kind            varchar(16)  NOT NULL,
  branch          varchar(12)  NOT NULL,
  is_head         boolean      NOT NULL DEFAULT false,
  change_order_id uuid,
  valid_from      date         NOT NULL,
  valid_to        date         NOT NULL DEFAULT 'infinity',
  recorded_from   timestamptz  NOT NULL DEFAULT now(),
  recorded_to     timestamptz  NOT NULL DEFAULT 'infinity',
  valid_period    daterange    GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  recorded_period tstzrange    GENERATED ALWAYS AS (tstzrange(recorded_from, recorded_to, '[)')) STORED,
  created_at      timestamptz  NOT NULL DEFAULT now(),
  created_by      uuid         NOT NULL,
  updated_at      timestamptz  NOT NULL DEFAULT now(),
  updated_by      uuid         NOT NULL,
  row_version     int          NOT NULL DEFAULT 0,
  CONSTRAINT pk_assignment PRIMARY KEY (version_id),
  CONSTRAINT ck_assignment__kind CHECK (kind IN ('CHINH','KIEM_NHIEM','DIEU_DONG','BIET_PHAI','TAM_GIU')),
  CONSTRAINT ck_assignment__branch CHECK (branch IN ('DANG','CHINH_QUYEN')),
  CONSTRAINT ck_assignment__valid CHECK (valid_from < valid_to),
  CONSTRAINT ck_assignment__recorded CHECK (recorded_from < recorded_to),
  -- Một người chỉ có một phân công CHÍNH trên mỗi nhánh tại mọi điểm của hai trục
  CONSTRAINT ex_assignment__one_primary EXCLUDE USING gist (
    tenant_id WITH =, person_id WITH =, branch WITH =,
    valid_period WITH &&, recorded_period WITH &&
  ) WHERE (kind = 'CHINH'),
  -- Một phân công (cùng id) không có hai phiên bản chồng lấn trên cả hai trục
  CONSTRAINT ex_assignment__version_no_overlap EXCLUDE USING gist (
    tenant_id WITH =, id WITH =,
    valid_period WITH &&, recorded_period WITH &&
  )
);

-- Truy vấn "hiện hành theo hiểu biết hiện tại" là phổ biến nhất: chỉ mục một phần
CREATE INDEX ix_assignment__person_current_p ON org.assignment (tenant_id, person_id, valid_from)
  WHERE recorded_to = 'infinity';
CREATE INDEX ix_assignment__unit_current_p ON org.assignment (tenant_id, org_unit_id, valid_from)
  WHERE recorded_to = 'infinity';

REVOKE UPDATE, DELETE ON org.assignment FROM svc_org;
GRANT UPDATE (recorded_to, updated_at, updated_by, row_version) ON org.assignment TO svc_org;
```

Ràng buộc `ex_assignment__one_primary` dùng chỉ mục GiST trên tổ hợp đẳng thức và hai khoảng, nhờ tiện ích `btree_gist`. Vì phiên bản đã bị thay thế có `recorded_period` đóng lại trước thời điểm phiên bản mới bắt đầu, hai phiên bản của cùng một sự thật không bao giờ chồng lấn trên trục ghi nhận, nên ràng buộc chỉ chặn đúng trường hợp sai nghiệp vụ: hai phân công chính cùng hiệu lực theo cùng một hiểu biết.

### Từ điển dữ liệu

#### Bảng org.org_unit

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `version_id` | `uuid` | Không | PK | C1 | Mã phiên bản dòng |
| `id` | `uuid` | Không | EX chống chồng lấn phiên bản | C1 | Mã đơn vị ổn định |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `code` | `varchar(50)` | Không | | C0 | Mã đơn vị nội bộ |
| `name` | `varchar(255)` | Không | | C0 | Tên đơn vị |
| `short_name` | `varchar(100)` | Có | | C0 | Tên viết tắt |
| `unit_type` | `varchar(24)` | Không | CHECK IN (`TO_CHUC_DANG`, `CHI_BO`, `CO_QUAN`, `DON_VI_SU_NGHIEP`, `PHONG_BAN`, `XA_PHUONG`, `DOANH_NGHIEP`) | C0 | Loại đơn vị |
| `subject_group_id` | `uuid` | Có | Tham chiếu `org.subject_group` | C1 | Khối đối tượng theo PL 2A |
| `is_collective_subject` | `boolean` | Không | DEFAULT true | C1 | Đơn vị có được đánh giá như tập thể không |
| `change_order_id` | `uuid` | Có | FK `org.org_change_order` | C1 | Quyết định sinh ra phiên bản |
| `valid_from`, `valid_to` | `date` | Không | CHECK `valid_from < valid_to` | C1 | Trục hiệu lực |
| `recorded_from`, `recorded_to` | `timestamptz` | Không | | C1 | Trục ghi nhận |
| `valid_period`, `recorded_period` | `daterange`, `tstzrange` | Không | Cột sinh; EX `(tenant_id, id, valid_period &&, recorded_period &&)` | C1 | Phục vụ ràng buộc loại trừ |
| (cột chung trừ `id`, `tenant_id`) | | | | C1 | |
| `flows` | `varchar(12)[]` | Không |  | C1 | Luồng áp dụng cho đơn vị (TL-02a) |
| `level` | `varchar(12)` | Không |  | C1 | Cấp (TW, tỉnh, xã) |

Chỉ mục: `ix_org_unit__current_p (tenant_id, id) WHERE recorded_to = 'infinity'`; `ix_org_unit__code (tenant_id, code)`. Ước tính 40.000 đơn vị và 8.000 phiên bản mới mỗi năm (sau sắp xếp đơn vị hành chính tần suất thay đổi thấp).

#### Bảng org.org_unit_relation

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `version_id` | `uuid` | Không | PK | C1 | Mã phiên bản |
| `id` | `uuid` | Không | | C1 | Mã quan hệ ổn định |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `tree` | `varchar(12)` | Không | CHECK IN (`DANG`, `CHUYEN_MON`) | C1 | Cây tổ chức |
| `parent_unit_id` | `uuid` | Không | | C1 | Đơn vị cấp trên |
| `child_unit_id` | `uuid` | Không | CHECK khác `parent_unit_id` | C1 | Đơn vị cấp dưới |
| `relation_type` | `varchar(12)` | Không | CHECK IN (`TRUC_THUOC`, `CHI_DAO`, `PHOI_HOP`) | C1 | Loại quan hệ |
| `change_order_id` | `uuid` | Có | FK | C1 | Quyết định căn cứ |
| Hai trục thời gian và cột sinh | | Không | EX `(tenant_id, tree, child_unit_id, valid_period &&, recorded_period &&) WHERE relation_type = 'TRUC_THUOC'` | C1 | Mỗi đơn vị có đúng một cấp trên trực thuộc trên mỗi cây tại mọi thời điểm |
| (cột chung trừ `id`, `tenant_id`) | | | | C1 | |

Việc chống chu trình (một đơn vị là tổ tiên của chính nó) được kiểm tra ở ứng dụng trước khi ghi bằng truy vấn đệ quy trên phiên bản hiện hành, vì ràng buộc khai báo của SQL không diễn đạt được tính chất đồ thị không chu trình. Ước tính 45.000 quan hệ hiện hành.

#### Bảng org.person

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `version_id` | `uuid` | Không | PK | C1 | Mã phiên bản |
| `id` | `uuid` | Không | | C1 | Mã người ổn định, trùng `sub` trong Keycloak |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `full_name` | `varchar(200)` | Không | | C1 | Họ và tên (dữ liệu cá nhân cơ bản, bảo vệ bằng RLS, ABAC) |
| `gender` | `varchar(8)` | Có | CHECK IN (`NAM`, `NU`, `KHAC`) | C1 | Giới tính |
| `birth_date_enc` | `bytea` | Có | | C2 | Ngày sinh, mã hoá trường |
| `national_id_enc` | `bytea` | Có | | C2 | Số định danh cá nhân, mã hoá trường |
| `national_id_bidx` | `bytea` | Có | Chỉ mục `(tenant_id, national_id_bidx)` | C1 | Chỉ mục mù HMAC để tra cứu chính xác khi đối soát với CSDL CBCCVC |
| `party_member_no_enc` | `bytea` | Có | | C2 | Số thẻ đảng viên |
| `party_member_no_bidx` | `bytea` | Có | | C1 | Chỉ mục mù |
| `is_party_member` | `boolean` | Không | DEFAULT false | C1 | Là đảng viên (xác định luồng `DANG`) |
| `cbccvc_code` | `varchar(32)` | Có | | C1 | Mã công chức, viên chức trong CSDL quốc gia (xác định luồng `NHA_NUOC`) |
| `employment_status` | `varchar(16)` | Không | CHECK IN (`DANG_CONG_TAC`, `NGHI_HUU`, `THOI_VIEC`, `TAM_NGHI`) | C1 | Tình trạng công tác |
| `dek_id` | `uuid` | Có | FK `keyring.dek` | C1 | Khoá dữ liệu dùng cho các cột `_enc` của phiên bản |
| Hai trục thời gian và cột sinh | | Không | EX chống chồng lấn phiên bản theo `id` | C1 | |
| (cột chung trừ `id`, `tenant_id`) | | | | C1 | |

Ước tính 1,2 triệu người hiện hành và khoảng 0,15 triệu phiên bản mới mỗi năm.

#### Bảng org.person_contact (bổ sung)

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `person_id` | `uuid` | Không | UQ (`tenant_id`, `person_id`) | C1 | Người |
| `phone_enc` | `bytea` | Có | | C2 | Số điện thoại di động |
| `phone_bidx` | `bytea` | Có | | C1 | Chỉ mục mù số điện thoại |
| `email_enc` | `bytea` | Có | | C2 | Thư điện tử công vụ |
| `email_bidx` | `bytea` | Có | Chỉ mục | C1 | Chỉ mục mù thư điện tử, dùng khi SCIM tra theo `userName` |
| `dek_id` | `uuid` | Không | FK `keyring.dek` | C1 | Khoá dữ liệu |

SVC-12 notification-service lấy số điện thoại, thư điện tử qua API nội bộ `GET /api/v1/org/persons/{id}/contact` với chứng thư mTLS của chính nó tại thời điểm gửi SMS hoặc thư, không lưu lại; thông báo đẩy và thông báo trong ứng dụng không cần thông tin liên lạc.

#### Bảng org.person_public_key (bổ sung)

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `person_id` | `uuid` | Không | | C1 | Chủ khoá |
| `key_alg` | `varchar(24)` | Không | CHECK IN (`ECDH-P256`, `RSA-OAEP-3072`, `BCY`) | C1 | Thuật toán khoá công khai |
| `public_key_spki` | `bytea` | Không | | C0 | Khoá công khai dạng SubjectPublicKeyInfo DER |
| `fingerprint` | `bytea` | Không | UQ (`tenant_id`, `fingerprint`) | C0 | SHA-256 của SPKI |
| `source` | `varchar(12)` | Không | CHECK IN (`CHUNG_THU`, `THIET_BI`) | C1 | Lấy từ chứng thư số hoặc sinh trên thiết bị và được chứng thực |
| `valid_from`, `valid_to` | `timestamptz` | Không | | C1 | Hiệu lực khoá |
| `status` | `varchar(12)` | Không | CHECK IN (`HIEU_LUC`, `THU_HOI`, `HET_HAN`) | C1 | Trạng thái |

#### Bảng org.position

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `version_id`, `id`, `tenant_id` | `uuid` | Không | PK `version_id`; RLS | C1 | Như các bảng hai trục khác |
| `org_unit_id` | `uuid` | Không | | C1 | Đơn vị có vị trí |
| `code` | `varchar(50)` | Không | | C0 | Mã vị trí việc làm |
| `name` | `varchar(255)` | Không | | C0 | Tên vị trí việc làm |
| `is_leadership` | `boolean` | Không | DEFAULT false | C1 | Vị trí lãnh đạo, quản lý (đánh giá quý bắt buộc) |
| `default_catalog_id` | `uuid` | Có | Tham chiếu logic `task.work_catalog.id` | C1 | Danh mục công việc chuẩn của vị trí (Báo cáo BGĐ slide 17, "thư viện danh mục chuẩn") |
| `headcount` | `smallint` | Không | DEFAULT 1 | C1 | Số người theo đề án vị trí việc làm |
| Hai trục thời gian và cột sinh | | Không | EX chống chồng lấn phiên bản | C1 | |
| (cột chung trừ `id`, `tenant_id`) | | | | C1 | |

Ước tính 120.000 vị trí.

#### Bảng org.job_title

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | `tenant_id` là tenant hệ thống cho danh mục quốc gia, hoặc tenant khách hàng cho danh mục riêng |
| `code` | `varchar(32)` | Không | UQ (`tenant_id`, `code`) | C0 | Mã chức danh |
| `name` | `varchar(255)` | Không | | C0 | Tên chức danh |
| `level` | `smallint` | Không | | C0 | Cấp chức danh, phục vụ sắp xếp |
| `is_central_managed` | `boolean` | Không | DEFAULT false | C0 | Thuộc 40 chức danh diện Trung ương quản lý (PL 2B) |
| `subject_group_id` | `uuid` | Có | | C1 | Khối đối tượng mặc định |
| `valid_from`, `valid_to` | `date` | Không | | C0 | Hiệu lực danh mục (một trục thời gian) |

#### Bảng org.assignment

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `version_id`, `id`, `tenant_id` | `uuid` | Không | PK `version_id`; RLS | C1 | |
| `person_id` | `uuid` | Không | Chỉ mục hiện hành | C1 | Người được phân công |
| `org_unit_id` | `uuid` | Không | Chỉ mục hiện hành | C1 | Đơn vị công tác |
| `position_id` | `uuid` | Có | | C1 | Vị trí việc làm |
| `job_title_id` | `uuid` | Không | | C1 | Chức danh |
| `kind` | `varchar(16)` | Không | CHECK IN (`CHINH`, `KIEM_NHIEM`, `DIEU_DONG`, `BIET_PHAI`, `TAM_GIU`) | C1 | Loại phân công |
| `branch` | `varchar(12)` | Không | CHECK IN (`DANG`, `CHINH_QUYEN`) | C1 | Nhánh của phân công |
| `is_head` | `boolean` | Không | DEFAULT false | C1 | Người đứng đầu (áp quy tắc người đứng đầu, QĐ 11.2.4) |
| `change_order_id` | `uuid` | Có | FK `org.org_change_order` | C1 | Quyết định căn cứ |
| Hai trục thời gian và cột sinh | | Không | EX `ex_assignment__one_primary`, `ex_assignment__version_no_overlap` | C1 | Xem DDL mẫu |
| (cột chung trừ `id`, `tenant_id`) | | | | C1 | |

Ước tính 1,5 triệu phân công hiện hành và 0,3 triệu phiên bản mới mỗi năm (điều động, bổ nhiệm, kiêm nhiệm, nghỉ hưu).

#### Bảng org.reporting_line (bổ sung)

Bảng `org.reporting_line` lưu quan hệ báo cáo trực tiếp giữa hai người (cấp trên trực tiếp, người thẩm định mặc định), tách khỏi cây đơn vị vì quan hệ này không luôn trùng với cây (kiêm nhiệm, phân công phụ trách lĩnh vực). TL-01 xếp bảng này vào nhóm bảng hai trục thời gian, nên cấu trúc giống `org.assignment`: `version_id` là khoá chính, hai cột sinh `valid_period`, `recorded_period` và ràng buộc loại trừ chặn hai cấp trên trực tiếp chồng lấn của cùng một người trong cùng loại quan hệ.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `version_id`, `id`, `tenant_id` | `uuid` | Không | PK `version_id`; RLS | C1 | |
| `subordinate_person_id` | `uuid` | Không | Chỉ mục hiện hành | C1 | Người báo cáo |
| `superior_person_id` | `uuid` | Không | CHECK khác `subordinate_person_id` | C1 | Cấp trên |
| `line_type` | `varchar(16)` | Không | CHECK IN (`TRUC_TIEP`, `THAM_DINH`, `PHU_TRACH_LINH_VUC`) | C1 | Loại quan hệ |
| `branch` | `varchar(12)` | Không | CHECK IN (`DANG`, `CHINH_QUYEN`) | C1 | Nhánh |
| `change_order_id` | `uuid` | Có | FK `org.org_change_order` | C1 | Quyết định căn cứ |
| Hai trục thời gian và cột sinh | | Không | EX (`tenant_id`, `subordinate_person_id`, `line_type`, `branch`, `valid_period`, `recorded_period`) | C1 | Như `org.assignment` |
| (cột chung trừ `id`, `tenant_id`) | | | | C1 | |

#### Bảng org.subject_group

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `code` | `varchar(16)` | Không | UQ (`tenant_id`, `code`) | C0 | Mã khối, ví dụ `K1` đến `K9` theo PL 2A |
| `name` | `varchar(255)` | Không | | C0 | Tên khối đối tượng |
| `flow` | `varchar(12)` | Không | CHECK IN (`DANG`, `NHA_NUOC`, `CA_HAI`) | C1 | Luồng áp dụng |
| `description` | `text` | Có | | C0 | Mô tả phạm vi |
| `valid_from`, `valid_to` | `date` | Không | | C0 | Hiệu lực |

#### Bảng org.org_change_order (bổ sung)

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `order_no` | `varchar(64)` | Không | UQ (`tenant_id`, `order_no`) | C1 | Số quyết định |
| `order_date` | `date` | Không | | C1 | Ngày ký |
| `kind` | `varchar(16)` | Không | CHECK IN (`BO_NHIEM`, `DIEU_DONG`, `KIEM_NHIEM`, `SAP_NHAP`, `CHIA_TACH`, `GIAI_THE`, `THOI_VIEC`, `DINH_CHINH`) | C1 | Loại quyết định |
| `effective_on` | `date` | Không | | C1 | Ngày hiệu lực |
| `signed_doc_ref` | `varchar(200)` | Có | | C1 | Tham chiếu văn bản đã ký (mã ở hệ văn bản qua `integ.mapping_xref`) |
| `payload` | `jsonb` | Không | | C1 | Nội dung có cấu trúc của quyết định (danh sách đơn vị, người bị tác động) |
| `status` | `varchar(12)` | Không | CHECK IN (`NHAP`, `DA_AP_DUNG`, `DA_HUY`) | C1 | Trạng thái áp dụng |
| `applied_at` | `timestamptz` | Có | | C1 | Thời điểm hệ thống áp dụng (bằng `recorded_from` của các phiên bản sinh ra) |

#### Bảng org.scim_sync_log

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `source_system` | `varchar(32)` | Không | | C1 | Nguồn, ví dụ `CSDL_CBCCVC`, `HRM_TCT`, `IDP_KH` |
| `operation` | `varchar(8)` | Không | CHECK IN (`CREATE`, `REPLACE`, `PATCH`, `DELETE`) | C1 | Thao tác SCIM 2.0 (RFC 7644) |
| `resource_type` | `varchar(8)` | Không | CHECK IN (`User`, `Group`) | C1 | Loại tài nguyên SCIM |
| `external_id` | `varchar(128)` | Không | | C1 | `externalId` của nguồn |
| `internal_id` | `uuid` | Có | | C1 | Mã nội bộ sau ánh xạ |
| `request_hash` | `bytea` | Không | | C1 | SHA-256 nội dung yêu cầu, chống xử lý trùng |
| `status` | `varchar(12)` | Không | CHECK IN (`THANH_CONG`, `LOI`, `BO_QUA`) | C1 | Kết quả |
| `error_detail` | `text` | Có | | C1 | Mô tả lỗi theo SCIM error schema |
| `received_at` | `timestamptz` | Không | Khoá phân vùng RANGE theo tháng | C1 | Thời điểm nhận |

Bảng `org.scim_sync_log` được phân vùng theo tháng bằng pg_partman, giữ 13 tháng. Ước tính 2 triệu dòng mỗi năm ở CELL-HCM (đồng bộ ban đầu cộng thay đổi hằng ngày).

#### Bảng org.sync_discrepancy (bổ sung)

Bảng `org.sync_discrepancy` (đề xuất của TL-02a, màn hình SCR-0104) lưu các lệch dữ liệu nhân sự giữa nguồn ngoài (CSDL CBCCVC, HRM, SCIM) và nền tảng mà bộ đồng bộ không tự xử lý được, để cán bộ tổ chức quyết định.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung trừ `period_bucket`) | | | PK `id` | C1 | |
| `person_id` | `uuid` | Có | Chỉ mục | C1 | Nhân sự liên quan |
| `field` | `varchar(64)` | Không | | C1 | Trường lệch, ví dụ `org_unit_id`, `job_title_id` |
| `source_connector` | `varchar(32)` | Không | | C1 | Nguồn ngoài |
| `source_value_enc`, `current_value_enc` | `bytea` | Có | | C2 | Giá trị hai phía (mã hoá vì có thể là ngày sinh, số định danh) |
| `dek_id` | `uuid` | Có | FK `keyring.dek` | C1 | Khoá dữ liệu |
| `severity` | `varchar(8)` | Không | CHECK IN (`CAO`, `VUA`, `THAP`) | C1 | Mức nghiêm trọng |
| `suggested_action` | `varchar(32)` | Không | | C1 | Đề xuất (nhận nguồn, giữ hiện tại, tạo nhân sự mới) |
| `resolution` | `varchar(16)` | Có | CHECK IN (`NHAN_NGUON`, `GIU_HIEN_TAI`, `SUA_TAY`, `BO_QUA`) | C1 | Cách xử lý |
| `resolved_by`, `resolved_at` | | Có | | C1 | Người và thời điểm xử lý |
| `sync_run_id` | `uuid` | Không | FK `org.scim_sync_log` | C1 | Lần đồng bộ phát hiện |

## Schema org_replica (bản sao trong mission-core)

### Thiết kế

Schema `org_replica` là bản đọc cục bộ của dữ liệu tổ chức theo mẫu Event-carried State Transfer. Nó cho phép tám module của mission-core kiểm tra phân quyền theo cây tổ chức, tra người đứng đầu, tìm cấp trên trực tiếp mà không gọi đồng bộ sang SVC-04 trên đường găng của request. Bản sao chỉ giữ trục hiệu lực của hiểu biết hiện tại (các phiên bản có `recorded_to = 'infinity'` ở nguồn), không giữ trục ghi nhận: khi cần tái hiện "hệ thống biết gì tại thời điểm X", module gọi API lịch sử của SVC-04, vốn là truy vấn hiếm (hậu kiểm, khiếu nại). Bản sao không chứa cột C2 nào; `person_v` chỉ có họ tên, mã CBCCVC và các cờ cần cho nghiệp vụ.

Bảng bổ sung `org_replica.unit_closure_v` là bảng bao đóng (closure table) của cây tổ chức hiện hành, mỗi dòng là một cặp tổ tiên và hậu duệ kèm độ sâu. Bảng này cho phép kiểm tra ABAC "người dùng có quyền trên đơn vị X vì X là cấp dưới của đơn vị mình" bằng một phép tra chỉ mục, thay vì truy vấn đệ quy ở mỗi request. Bảng bổ sung `org_replica.replica_checkpoint` theo dõi độ trễ áp dụng và `org_replica.reconcile_run` lưu kết quả đối soát hằng đêm (chương 10).

![Hình 6.3. ERD schema org_replica](db-erd-org-replica.png){width=13cm}

Hình 6.3 cho thấy cấu trúc bản sao phẳng hơn nguồn: không có `version_id`, khoá chính là mã thực thể cộng ngày bắt đầu hiệu lực, và mỗi dòng mang `source_version` để consumer bỏ qua sự kiện cũ đến muộn.

### Từ điển dữ liệu

#### Bảng org_replica.org_unit_v

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `tenant_id` | `uuid` | Không | PK (`tenant_id`, `id`, `valid_from`); RLS | C1 | Tenant |
| `id` | `uuid` | Không | PK | C1 | Mã đơn vị |
| `valid_from`, `valid_to` | `date` | Không | PK (`valid_from`) | C1 | Khoảng hiệu lực |
| `code`, `name` | `varchar` | Không | | C0 | Mã và tên |
| `unit_type` | `varchar(24)` | Không | | C0 | Loại đơn vị |
| `party_parent_id` | `uuid` | Có | | C1 | Cấp trên trực thuộc trên cây Đảng |
| `admin_parent_id` | `uuid` | Có | | C1 | Cấp trên trực thuộc trên cây chuyên môn |
| `subject_group_id` | `uuid` | Có | | C1 | Khối đối tượng |
| `source_version` | `bigint` | Không | | C1 | Phiên bản ở nguồn |
| `source_event_id` | `uuid` | Không | | C1 | Sự kiện đã áp dụng |
| `replicated_at` | `timestamptz` | Không | | C1 | Thời điểm áp dụng |

#### Bảng org_replica.person_v

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `tenant_id`, `id` | `uuid` | Không | PK (`tenant_id`, `id`); RLS | C1 | Mã người |
| `full_name` | `varchar(200)` | Không | | C1 | Họ và tên |
| `is_party_member` | `boolean` | Không | | C1 | Đảng viên |
| `cbccvc_code` | `varchar(32)` | Có | | C1 | Mã CBCCVC |
| `employment_status` | `varchar(16)` | Không | | C1 | Tình trạng công tác |
| `source_version`, `source_event_id`, `replicated_at` | | Không | | C1 | Như trên |

#### Bảng org_replica.assignment_v

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `tenant_id`, `id` | `uuid` | Không | PK (`tenant_id`, `id`, `valid_from`); RLS | C1 | Mã phân công |
| `valid_from`, `valid_to` | `date` | Không | PK | C1 | Khoảng hiệu lực |
| `person_id` | `uuid` | Không | Chỉ mục `(tenant_id, person_id, valid_to)` | C1 | Người |
| `org_unit_id` | `uuid` | Không | Chỉ mục `(tenant_id, org_unit_id, valid_to)` | C1 | Đơn vị |
| `position_id`, `job_title_id` | `uuid` | Có/Không | | C1 | Vị trí, chức danh |
| `kind`, `branch` | `varchar` | Không | | C1 | Loại phân công, nhánh |
| `is_head` | `boolean` | Không | Chỉ mục một phần `WHERE is_head` | C1 | Người đứng đầu |
| `source_version`, `source_event_id`, `replicated_at` | | Không | | C1 | |

#### Bảng org_replica.unit_closure_v (bổ sung)

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `tenant_id` | `uuid` | Không | PK (`tenant_id`, `tree`, `ancestor_id`, `descendant_id`) | C1 | Tenant |
| `tree` | `varchar(12)` | Không | PK | C1 | Cây Đảng hoặc chuyên môn |
| `ancestor_id` | `uuid` | Không | PK | C1 | Tổ tiên (kể cả chính nó với độ sâu 0) |
| `descendant_id` | `uuid` | Không | PK; chỉ mục `(tenant_id, tree, descendant_id)` | C1 | Hậu duệ |
| `depth` | `smallint` | Không | | C1 | Khoảng cách |

Bảng bao đóng có khoảng 40.000 × độ sâu trung bình 5 ≈ 0,2 triệu dòng mỗi cây ở CELL-HCM. Khi nhận EVT-14 thay đổi quan hệ trực thuộc, consumer tính lại bao đóng của cây con bị ảnh hưởng trong một giao dịch.

#### Bảng org_replica.replica_checkpoint (bổ sung) và org_replica.reconcile_run (bổ sung)

| Bảng | Cột chính | Mô tả |
|---|---|---|
| `org_replica.replica_checkpoint` | `tenant_id`, `entity_type` (PK); `last_event_id uuid`, `last_occurred_at timestamptz`, `last_applied_at timestamptz`, `lag_ms int` | Độ trễ áp dụng của từng loại thực thể, xuất thành metric Prometheus `org_replica_lag_ms` |
| `org_replica.reconcile_run` | `id` (PK), `tenant_id`, `entity_type`, `started_at`, `finished_at`, `buckets_total int`, `buckets_mismatch int`, `rows_repaired int`, `status varchar(12)` CHECK IN (`DANG_CHAY`, `KHOP`, `DA_SUA`, `LOI`) | Kết quả đối soát hằng đêm theo thuật toán băm theo nhóm (chương 10) |

Mọi bảng `_v` được cập nhật bằng câu lệnh `INSERT ... ON CONFLICT (...) DO UPDATE ... WHERE excluded.source_version > org_replica.<bảng>.source_version`, nên sự kiện cũ đến muộn không ghi đè dữ liệu mới.

## Schema config (MOD-08)

### Thiết kế

Schema `config` chứa cấu hình nền tảng của từng tenant. Bảng quan trọng nhất là `config.period`, vì trạng thái kỳ (mở, đang khoá, đã khoá, mở lại hậu kiểm) quyết định mọi thao tác ghi dữ liệu đánh giá có được phép hay không (lớp bảo vệ thứ 5). Mọi module ghi dữ liệu thuộc kỳ đều kiểm tra trạng thái kỳ trước khi ghi; để chi phí kiểm tra dưới 0,1 ms như baseline G3 đặt ra, trạng thái kỳ được giữ trong bộ nhớ đệm của từng pod (Caffeine) và được làm mới ngay khi nhận EVT-09 hoặc EVT-22, có thời hạn sống tối đa 30 giây làm lưới an toàn. Ngoài kiểm tra ở ứng dụng, các bảng dữ liệu đánh giá của kỳ đã khoá còn được bảo vệ bằng trigger kiểm tra ở CSDL (mô tả trong schema `appraisal`), nên kể cả khi bộ đệm chậm vài giây thì CSDL vẫn từ chối ghi.

Bảng bổ sung `config.period_status_log` lưu mọi lần chuyển trạng thái kỳ, kèm mã saga chốt kỳ hoặc mã yêu cầu mở lại hai người duyệt. Bảng `config.ui_pack_version` lưu Design Token theo chuẩn W3C Design Tokens và bố cục SDUI có giới hạn theo ADR-05, cùng mã băm và chữ ký của gói đã biên dịch để CDN và trình duyệt kiểm tra tính toàn vẹn. Bảng `config.form_schema` lưu biểu mẫu động (JSON Schema, cấu hình Formly, quy tắc CEL) có phiên bản và gắn với phiên bản Policy Pack.

![Hình 6.4. ERD schema config](db-erd-config.png){width=15cm}

Hình 6.4 cho thấy `config.period` có cấu trúc cha con (quý thuộc năm, tháng thuộc quý) và chuỗi nhật ký trạng thái; UI Pack và biểu mẫu động có phiên bản riêng, độc lập với nhau nhưng cùng tham chiếu tới phiên bản Policy Pack.

### DDL mẫu: config.period

```sql
CREATE TABLE config.period (
  id                     uuid         NOT NULL,
  tenant_id              uuid         NOT NULL,
  code                   varchar(32)  NOT NULL,          -- ví dụ 2027-Q1, 2027, 2027-03
  name                   varchar(100) NOT NULL,
  kind                   varchar(8)   NOT NULL,
  flow                   varchar(12)  NOT NULL,
  parent_period_id       uuid,
  start_date             date         NOT NULL,
  end_date               date         NOT NULL,          -- ngày cuối cùng, tính bao gồm
  period_bucket          int          NOT NULL,
  plan_deadline          date,                            -- hạn duyệt kế hoạch (trước tuần 2 tháng đầu quý)
  submission_deadline    date,                            -- hạn nộp hồ sơ đánh giá
  status                 varchar(16)  NOT NULL DEFAULT 'MO',
  locked_at              timestamptz,
  locked_by              uuid,
  lock_saga_id           uuid,                            -- flowable.saga_instance.id
  reopen_count           smallint     NOT NULL DEFAULT 0,
  created_at             timestamptz  NOT NULL DEFAULT now(),
  created_by             uuid         NOT NULL,
  updated_at             timestamptz  NOT NULL DEFAULT now(),
  updated_by             uuid         NOT NULL,
  row_version            int          NOT NULL DEFAULT 0,
  CONSTRAINT pk_period PRIMARY KEY (id),
  CONSTRAINT uq_period__code UNIQUE (tenant_id, code, flow),
  CONSTRAINT fk_period__parent FOREIGN KEY (parent_period_id) REFERENCES config.period (id),
  CONSTRAINT ck_period__kind CHECK (kind IN ('THANG','QUY','NAM','NAM_HOC')),
  CONSTRAINT ck_period__flow CHECK (flow IN ('DANG','NHA_NUOC','CA_HAI')),
  CONSTRAINT ck_period__status CHECK (status IN ('MO','DANG_KHOA','DA_KHOA','MO_LAI_HAU_KIEM')),
  CONSTRAINT ck_period__dates CHECK (start_date <= end_date),
  CONSTRAINT ck_period__bucket CHECK (period_bucket BETWEEN 20200 AND 21004
                                      AND period_bucket % 10 BETWEEN 0 AND 4),
  CONSTRAINT ck_period__locked CHECK (status NOT IN ('DA_KHOA') OR locked_at IS NOT NULL),
  -- Hai kỳ cùng loại, cùng luồng của một tenant không được chồng lấn ngày
  CONSTRAINT ex_period__no_overlap EXCLUDE USING gist (
    tenant_id WITH =, kind WITH =, flow WITH =,
    daterange(start_date, end_date, '[]') WITH &&)
);
CREATE INDEX ix_period__tenant_status ON config.period (tenant_id, status, end_date);

-- Kỳ chỉ đi theo các chuyển trạng thái hợp lệ của baseline mục 9.1
CREATE FUNCTION config.fn_check_period_transition() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = NEW.status THEN RETURN NEW; END IF;
  IF (OLD.status, NEW.status) IN (('MO','DANG_KHOA'), ('DANG_KHOA','MO'),
                                  ('DANG_KHOA','DA_KHOA'), ('DA_KHOA','MO_LAI_HAU_KIEM'),
                                  ('MO_LAI_HAU_KIEM','DA_KHOA')) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'Chuyển trạng thái kỳ không hợp lệ: % -> %', OLD.status, NEW.status
    USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER tg_period__transition BEFORE UPDATE OF status ON config.period
  FOR EACH ROW EXECUTE FUNCTION config.fn_check_period_transition();
```

Chuyển trạng thái `DANG_KHOA` về `MO` chỉ được dùng làm hành động bù trừ của saga chốt kỳ khi một bước trước điểm không quay lại thất bại (chương 10); khi đó MOD-08 phát EVT-26 KyDaHuyKhoa. Chuyển `DA_KHOA` sang `MO_LAI_HAU_KIEM` chỉ được thực hiện khi có một dòng `oversight.reopen_request` ở trạng thái đã đủ hai phê duyệt; ứng dụng kiểm tra điều kiện này và ghi mã yêu cầu vào `config.period_status_log`.

### Từ điển dữ liệu

#### Bảng config.tenant_setting

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `setting_key` | `varchar(100)` | Không | UQ NULLS NOT DISTINCT (`tenant_id`, `setting_key`, `scope_unit_id`) | C1 | Khoá cấu hình, ví dụ `task.late.dayType` |
| `scope_unit_id` | `uuid` | Có | | C1 | Đơn vị áp dụng; NULL là toàn tenant; đơn vị con kế thừa nếu không ghi đè |
| `value` | `jsonb` | Không | | C1 | Giá trị |
| `value_schema` | `varchar(100)` | Không | | C1 | Mã JSON Schema dùng kiểm tra giá trị |
| `description` | `text` | Có | | C0 | Ghi chú |

#### Bảng config.period

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `code` | `varchar(32)` | Không | UQ (`tenant_id`, `code`, `flow`) | C0 | Mã kỳ |
| `name` | `varchar(100)` | Không | | C0 | Tên hiển thị, ví dụ "Quý I/2027" |
| `kind` | `varchar(8)` | Không | CHECK IN (`THANG`, `QUY`, `NAM`, `NAM_HOC`) | C1 | Loại kỳ |
| `flow` | `varchar(12)` | Không | CHECK IN (`DANG`, `NHA_NUOC`, `CA_HAI`) | C1 | Luồng áp dụng |
| `parent_period_id` | `uuid` | Có | FK `config.period` | C1 | Kỳ cha (quý thuộc năm) |
| `start_date`, `end_date` | `date` | Không | EX chống chồng lấn | C1 | Khoảng ngày của kỳ |
| `period_bucket` | `int` | Không | CHECK | C1 | Giá trị phân vùng dùng cho mọi bảng thuộc kỳ |
| `plan_deadline` | `date` | Có | | C1 | Hạn duyệt kế hoạch quý (QĐ 12.1.1) |
| `submission_deadline` | `date` | Có | | C1 | Hạn nộp hồ sơ đánh giá |
| `status` | `varchar(16)` | Không | CHECK; trigger chuyển trạng thái | C1 | `MO`, `DANG_KHOA`, `DA_KHOA`, `MO_LAI_HAU_KIEM` |
| `locked_at`, `locked_by` | `timestamptz`, `uuid` | Có | | C1 | Thời điểm và người khoá |
| `lock_saga_id` | `uuid` | Có | | C1 | Saga chốt kỳ gần nhất |
| `reopen_count` | `smallint` | Không | | C1 | Số lần mở lại hậu kiểm |
| `milestones` | `jsonb` | Không | DEFAULT `'{}'` | C1 | Các mốc: hạn duyệt kế hoạch, hạn nộp minh chứng, hạn nghiệm thu tồn đọng, ngày khoá dự kiến (TL-02a) |

Ước tính mỗi tenant tạo 5 đến 17 kỳ mỗi năm (bốn quý, một năm, có thể thêm mười hai tháng cho luồng Nhà nước).

#### Bảng config.period_status_log (bổ sung)

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã dòng |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `period_id` | `uuid` | Không | FK `config.period` | C1 | Kỳ |
| `from_status`, `to_status` | `varchar(16)` | Không | | C1 | Chuyển trạng thái |
| `cause_type` | `varchar(16)` | Không | CHECK IN (`SAGA_CHOT_KY`, `BU_TRU_SAGA`, `MO_LAI_HAU_KIEM`, `KHOA_LAI`) | C1 | Nguyên nhân |
| `cause_ref` | `uuid` | Không | | C1 | Mã saga hoặc mã `oversight.reopen_request` |
| `actor_id` | `uuid` | Không | | C1 | Người hoặc tài khoản dịch vụ |
| `changed_at` | `timestamptz` | Không | | C1 | Thời điểm |

Bảng chỉ ghi thêm: vai trò `svc_config` không có quyền `UPDATE`, `DELETE`.

#### Bảng config.holiday_calendar

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `calendar_date` | `date` | Không | UQ (`tenant_id`, `calendar_date`) | C0 | Ngày |
| `day_kind` | `varchar(8)` | Không | CHECK IN (`NGHI_LE`, `NGHI_BU`, `LAM_BU`) | C0 | Loại ngày |
| `name` | `varchar(100)` | Không | | C0 | Tên ngày lễ |
| `scope` | `varchar(8)` | Không | CHECK IN (`QUOC_GIA`, `TENANT`) | C0 | Lịch quốc gia (tenant hệ thống) hoặc lịch riêng |

Lịch ngày nghỉ được SVC-07 dùng để tính số ngày làm việc chậm hạn khi Policy Pack chọn "ngày làm việc" (PL A4, điểm cần làm rõ thứ hai). Mỗi năm khoảng 20 dòng mỗi tenant.

#### Bảng config.ui_pack và config.ui_pack_version

| Cột của `config.ui_pack` | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `code` | `varchar(64)` | Không | UQ (`tenant_id`, `code`) | C0 | Mã UI Pack |
| `name` | `varchar(255)` | Không | | C0 | Tên |
| `current_version_id` | `uuid` | Có | FK `config.ui_pack_version` | C1 | Phiên bản đang phát hành |

| Cột của `config.ui_pack_version` | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `ui_pack_id` | `uuid` | Không | FK `config.ui_pack` | C1 | UI Pack |
| `version` | `varchar(32)` | Không | UQ (`ui_pack_id`, `version`) | C0 | Phiên bản ngữ nghĩa |
| `tokens` | `jsonb` | Không | | C0 | Design Token chuẩn W3C (màu, chữ, khoảng cách, bộ token "Đảng viên cao tuổi") |
| `layouts` | `jsonb` | Không | | C0 | Bố cục SDUI cho bề mặt cấu hình được, chỉ tham chiếu component trong danh mục |
| `component_registry_version` | `varchar(32)` | Không | | C0 | Phiên bản danh mục component được phép |
| `policy_pack_version_id` | `uuid` | Có | Tham chiếu logic `policy.policy_pack_version` | C1 | Phiên bản quy tắc mà biểu mẫu khớp |
| `bundle_object_key` | `varchar(512)` | Có | | C1 | Khoá đối tượng Ceph của gói đã biên dịch |
| `bundle_sha256` | `bytea` | Có | | C1 | Băm gói, dùng làm ETag và thuộc tính `integrity` |
| `signature` | `bytea` | Có | | C1 | Chữ ký số của gói |
| `status` | `varchar(16)` | Không | CHECK IN (`NHAP`, `DANG_KIEM_TRA`, `CHO_DUYET`, `DA_PHAT_HANH`, `THAY_THE`) | C1 | Trạng thái (bộ trạng thái của TL-02b) |
| `published_at` | `timestamptz` | Có | | C1 | Thời điểm phát hành (sinh EVT-23) |
| `policy_pack_ref` | `uuid` | Có |  | C1 | Phiên bản Policy Pack mà UI Pack đi kèm (TL-02b) |
| `tokens_json` | `jsonb` | Không |  | C0 | Design Token |
| `assets_json` | `jsonb` | Không |  | C0 | Tài nguyên (logo, phông) |
| `layout_json` | `jsonb` | Không |  | C0 | Bố cục SDUI, chỉ dùng component trong `config.component_registry` |
| `elder_mode`, `few_steps` | `boolean` | Không | DEFAULT false | C0 | Chế độ chữ lớn, chế độ ít bước |

#### Bảng config.form_schema

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `form_code` | `varchar(64)` | Không | UQ (`tenant_id`, `form_code`, `version`) | C0 | Mã biểu mẫu, ví dụ `phieu-tu-danh-gia-quy` |
| `version` | `varchar(32)` | Không | | C0 | Phiên bản |
| `json_schema` | `jsonb` | Không | | C0 | JSON Schema 2020-12 của dữ liệu |
| `ui_schema` | `jsonb` | Không | | C0 | Cấu hình trường Formly |
| `cel_rules` | `jsonb` | Không | DEFAULT `'[]'` | C0 | Quy tắc kiểm tra CEL chạy trên cel-js và cel-java |
| `policy_pack_version_id` | `uuid` | Có | | C1 | Phiên bản quy tắc gắn kèm |
| `status` | `varchar(16)` | Không | CHECK IN (`NHAP`, `DA_PHAT_HANH`, `NGUNG`) | C1 | Trạng thái |
| `form_key` | `varchar(64)` | Không |  | C0 | Khoá biểu mẫu do TL-02b dùng (tương đương `form_code`) |
| `schema_json` | `jsonb` | Không |  | C0 | JSON Schema có `x-cel` và `x-classification` |
| `ui_json` | `jsonb` | Không |  | C0 | Gợi ý hiển thị |

#### Bảng config.feature_flag

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `flag_key` | `varchar(100)` | Không | UQ (`tenant_id`, `flag_key`) | C1 | Mã cờ |
| `enabled` | `boolean` | Không | | C1 | Bật hay tắt |
| `rollout` | `jsonb` | Không | DEFAULT `'{}'` | C1 | Quy tắc bật dần theo đơn vị, tỷ lệ |
| `valid_from`, `valid_to` | `timestamptz` | Có | | C1 | Hiệu lực |

#### Bảng config.role_binding

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `person_id` | `uuid` | Không | Chỉ mục (`tenant_id`, `person_id`) | C1 | Người được gán |
| `role_code` | `varchar(32)` | Không | CHECK IN (baseline mục 10: `tenant_admin`, `policy_author`, `leader`, `manager`, `member`, `appraiser`, `decider`, `second_approver`, `inspector`) | C1 | Vai trò hệ thống |
| `org_unit_id` | `uuid` | Không | | C1 | Đơn vị phạm vi |
| `scope` | `varchar(24)` | Không | CHECK IN (`DON_VI`, `DON_VI_VA_CAP_DUOI`) | C1 | Phạm vi ABAC |
| `flow` | `varchar(12)` | Có | | C1 | Giới hạn theo luồng |
| `valid_from`, `valid_to` | `timestamptz` | Không | | C1 | Hiệu lực |
| `granted_by` | `uuid` | Không | | C1 | Người cấp |
| `approved_by` | `uuid` | Có | CHECK khác `granted_by` | C1 | Người duyệt thứ hai cho vai trò nhạy cảm (`decider`, `second_approver`, `policy_author`) |

Quy tắc tách vai trò theo PL 4 V.1 (khởi tạo khác nghiệm thu khác thẩm định khác quyết định khác khoá kỳ đối với cùng một hồ sơ) được kiểm tra bằng OPA ở thời điểm thao tác, dựa trên dữ liệu của `config.role_binding` và người đã thực hiện các bước trước trong hồ sơ; CSDL chỉ chặn trường hợp hiển nhiên là một người tự duyệt việc gán vai trò cho chính mình.

#### Bảng config.period_lock_order (bổ sung)

Bảng `config.period_lock_order` (đề xuất của TL-02a, QT-08) là lệnh khoá kỳ do người có thẩm quyền lập và duyệt trước khi saga chốt kỳ (mục 10.7) được khởi chạy. Lệnh mang khoá chống lặp để bấm hai lần không khởi chạy hai saga; huỷ lệnh đang chạy cần người duyệt thứ hai.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung trừ `period_bucket`) | | | PK `id` | C1 | |
| `period_id` | `uuid` | Không | UQ một phần (`tenant_id`, `period_id`) WHERE `status` NOT IN (`DA_HUY`) | C1 | Kỳ cần khoá |
| `requested_by` | `uuid` | Không | | C1 | Người lập |
| `approved_by`, `approved_at` | | Có | CHECK `approved_by <> requested_by` | C1 | Người duyệt |
| `started_by`, `started_at` | | Có | | C1 | Người khởi chạy |
| `basis_doc` | `varchar(200)` | Không | | C1 | Văn bản căn cứ |
| `saga_id` | `uuid` | Có | | C1 | `flowable.saga_instance.id` |
| `idempotency_key` | `varchar(64)` | Không | UQ (`tenant_id`, `idempotency_key`) | C1 | Chống khởi chạy trùng |
| `status` | `varchar(12)` | Không | CHECK IN (`NHAP`, `CHO_DUYET`, `DA_DUYET`, `DANG_CHAY`, `HOAN_TAT`, `DA_HUY`) | C1 | Trạng thái |
| `cancel_requested_by`, `cancel_approved_by` | `uuid` | Có | CHECK khác nhau | C1 | Huỷ lệnh (bốn mắt) |

#### Bảng config.period_lock_step (bổ sung)

Bảng `config.period_lock_step` là bản chiếu từng bước saga để màn hình SCR-0801 hiển thị tiến trình; nguồn sự thật vẫn là `flowable.saga_step`, bảng này được cập nhật từ sự kiện phản hồi của saga.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `lock_order_id` | `uuid` | Không | FK; UQ (`lock_order_id`, `step_no`) | C1 | Lệnh khoá |
| `step_no` | `int` | Không | | C1 | Thứ tự |
| `name` | `varchar(64)` | Không | | C1 | Tên bước |
| `status` | `varchar(16)` | Không | CHECK IN (`CHO`, `DANG_CHAY`, `DAT`, `DAT_CO_CANH_BAO`, `LOI`, `DA_BU_TRU`) | C1 | Trạng thái |
| `started_at`, `finished_at` | `timestamptz` | Có | | C1 | Thời gian |
| `result_summary` | `jsonb` | Có | | C1 | Tóm tắt kết quả (số hồ sơ, cảnh báo) |
| `compensation_action` | `varchar(48)` | Có | | C1 | Hành động bù trừ |
| `compensated_at` | `timestamptz` | Có | | C1 | Thời điểm bù trừ |

#### Bảng config.component_registry (bổ sung)

Bảng `config.component_registry` (đề xuất của TL-02b, QT-18) là danh mục component được phép dùng trong bố cục SDUI, theo phiên bản (ví dụ registry `v2026.3`). Bộ kiểm tra UI Pack từ chối bố cục dùng component hoặc thuộc tính không có trong phiên bản registry mà UI Pack khai báo.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C0 | Mã |
| `registry_version` | `varchar(16)` | Không | UQ (`registry_version`, `component_key`) | C0 | Phiên bản registry |
| `component_key` | `varchar(64)` | Không | | C0 | Khoá component |
| `props_schema` | `jsonb` | Không | | C0 | JSON Schema của thuộc tính |
| `allowed_classification` | `varchar(4)` | Không | | C0 | Mức phân loại cao nhất component được hiển thị |
| `status` | `varchar(12)` | Không | CHECK IN (`HIEU_LUC`, `NGUNG`) | C0 | Trạng thái |

Bảng là danh mục dùng chung của nền tảng (không có `tenant_id`), do đội nền tảng phát hành cùng bản web-frontend.

#### Bảng config.dashboard_def (bổ sung)

Bảng `config.dashboard_def` (đề xuất của TL-02b, QT-16) lưu định nghĩa dashboard người dùng tự dựng. TL-03 đặt bảng ở schema `config` của mission-core vì định nghĩa là cấu hình của tenant cần RLS và sao lưu cùng dữ liệu tenant; dữ liệu hiển thị vẫn lấy từ Cube và ClickHouse của SVC-15.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung trừ `period_bucket`) | | | PK `id` | C1 | |
| `owner_user_id` | `uuid` | Không | Chỉ mục | C1 | Người tạo |
| `title` | `varchar(255)` | Không | | C1 | Tên |
| `measures`, `dimensions` | `jsonb` | Không | | C1 | Chỉ số và chiều theo tên trong mô hình Cube |
| `layout_json` | `jsonb` | Không | | C1 | Bố cục |
| `share_scope` | `varchar(12)` | Không | CHECK IN (`RIENG`, `DON_VI`, `TENANT`) | C1 | Phạm vi chia sẻ |
| `shared_unit_ids` | `uuid[]` | Không | DEFAULT `'{}'` | C1 | Đơn vị được xem |

## Schema policy (MOD-05)

### Thiết kế

Schema `policy` lưu Policy Pack, tức "quy tắc là mã" theo ADR-19 và slide 38 của Báo cáo BGĐ. Một Policy Pack (`policy.policy_pack`) có nhiều phiên bản (`policy.policy_pack_version`); mỗi phiên bản chứa các quy tắc CEL (`policy.policy_rule`) và bảng quyết định DMN (`policy.decision_table`). Bộ test case vàng (`policy.golden_test_case`) gắn với Policy Pack và được khách hàng ký duyệt; mỗi lần chạy kiểm thử (`policy.test_run`) lưu tổng kết, và bảng bổ sung `policy.test_case_result` lưu kết quả từng ca để chứng minh tiêu chí J6 "đạt 100% trước mỗi lần phát hành". Bảng `policy.policy_binding` gắn một phiên bản cho tổ hợp tenant, luồng, khối đối tượng và kỳ.

Yêu cầu then chốt là tính bất biến: một phiên bản đã đạt kiểm thử thì nội dung không được sửa, và kỳ đã khoá giữ nguyên phiên bản cũ ngay cả khi có phiên bản mới. Thiết kế bảo đảm bằng ba cơ chế: mã băm nội dung (`content_hash`) tính trên dạng chuẩn hoá của toàn bộ quy tắc và bảng quyết định; chữ ký số của người phê duyệt trên mã băm; và trigger chặn mọi thay đổi nội dung khi trạng thái từ `DAT_KIEM_THU` trở đi. SVC-07 khi nạp một phiên bản luôn tính lại mã băm và so với giá trị đã ký trước khi dùng.

![Hình 6.5. ERD schema policy](db-erd-policy.png){width=15cm}

Hình 6.5 cho thấy test case vàng gắn với Policy Pack chứ không gắn với phiên bản, vì cùng một bộ ca được dùng để kiểm thử hồi quy qua nhiều phiên bản; kết quả chạy thì gắn với phiên bản cụ thể.

### DDL mẫu: policy.policy_pack_version

```sql
CREATE TABLE policy.policy_pack_version (
  id                  uuid          NOT NULL,
  tenant_id           uuid          NOT NULL,
  policy_pack_id      uuid          NOT NULL,
  version             varchar(32)   NOT NULL,
  status              varchar(16)   NOT NULL DEFAULT 'NHAP',
  effective_from      date,
  manifest            jsonb         NOT NULL,      -- metadata: tham số, làm tròn, nhãn, tham chiếu điều khoản
  content_hash        bytea,                       -- SHA-256 của dạng chuẩn hoá (quy tắc + DMN + manifest)
  bundle_object_key   varchar(512),                -- gói đã ký trên Ceph bucket policy-<cell>
  golden_pass_rate    numeric(6,4),
  last_test_run_id    uuid,
  signature           bytea,                       -- CAdES tách rời trên content_hash
  signer_cert_ref     uuid,                        -- esign.certificate_ref.id (tham chiếu logic)
  signed_at           timestamptz,
  tsa_token           bytea,                       -- dấu thời gian RFC 3161
  approved_by         uuid,
  approved_at         timestamptz,
  published_at        timestamptz,
  source_version_id   uuid,                        -- phiên bản gốc toàn cục nếu nhân bản từ kho gốc
  change_note         text          NOT NULL,
  created_at          timestamptz   NOT NULL DEFAULT now(),
  created_by          uuid          NOT NULL,
  updated_at          timestamptz   NOT NULL DEFAULT now(),
  updated_by          uuid          NOT NULL,
  row_version         int           NOT NULL DEFAULT 0,
  CONSTRAINT pk_policy_pack_version PRIMARY KEY (id),
  CONSTRAINT fk_policy_pack_version__pack FOREIGN KEY (policy_pack_id) REFERENCES policy.policy_pack (id),
  CONSTRAINT uq_policy_pack_version__ver UNIQUE (policy_pack_id, version),
  CONSTRAINT ck_policy_pack_version__status CHECK (status IN
    ('NHAP','DANG_KIEM_THU','DAT_KIEM_THU','CHO_PHE_DUYET','DA_PHAT_HANH','NGUNG')),
  CONSTRAINT ck_policy_pack_version__semver CHECK (version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
  CONSTRAINT ck_policy_pack_version__pass CHECK (
    status NOT IN ('DAT_KIEM_THU','CHO_PHE_DUYET','DA_PHAT_HANH') OR golden_pass_rate = 1),
  CONSTRAINT ck_policy_pack_version__signed CHECK (
    status <> 'DA_PHAT_HANH' OR (signature IS NOT NULL AND content_hash IS NOT NULL
                                 AND approved_by IS NOT NULL AND approved_by <> created_by))
);

CREATE FUNCTION policy.fn_freeze_policy_version() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('DAT_KIEM_THU','CHO_PHE_DUYET','DA_PHAT_HANH','NGUNG')
     AND (NEW.manifest IS DISTINCT FROM OLD.manifest
          OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
          OR NEW.version IS DISTINCT FROM OLD.version) THEN
    RAISE EXCEPTION 'Phiên bản Policy Pack % đã đóng băng nội dung', OLD.version
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tg_policy_pack_version__freeze BEFORE UPDATE ON policy.policy_pack_version
  FOR EACH ROW EXECUTE FUNCTION policy.fn_freeze_policy_version();
```

Trigger tương tự được đặt trên `policy.policy_rule` và `policy.decision_table`: mọi `UPDATE`, `DELETE` trên dòng thuộc một phiên bản đã đóng băng đều bị từ chối. Ràng buộc `ck_policy_pack_version__signed` biểu diễn bằng SQL nguyên tắc bốn mắt: người phê duyệt phát hành phải khác người soạn.

### Từ điển dữ liệu

#### Bảng policy.policy_pack

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `code` | `varchar(100)` | Không | UQ (`tenant_id`, `code`) | C0 | Mã gói, ví dụ `khoi-dang/quy-dinh-moi` |
| `name` | `varchar(255)` | Không | | C0 | Tên gói |
| `sector` | `varchar(24)` | Không | | C0 | Nhóm khách hàng |
| `flow` | `varchar(12)` | Không | CHECK IN (`DANG`, `NHA_NUOC`) | C0 | Luồng |
| `origin` | `varchar(16)` | Không | CHECK IN (`GOC_TOAN_CUC`, `TENANT`) | C1 | Gói gốc đồng bộ từ vùng điều phối hoặc gói riêng của tenant |
| `parent_pack_id` | `uuid` | Có | FK `policy.policy_pack` | C1 | Gói được kế thừa (nhân bản khi có văn bản mới) |
| `legal_basis` | `text` | Không | | C0 | Văn bản căn cứ |

#### Bảng policy.policy_pack_version

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `policy_pack_id` | `uuid` | Không | FK | C1 | Gói |
| `version` | `varchar(32)` | Không | UQ; CHECK semver | C0 | Phiên bản, ví dụ `2.1.0` |
| `status` | `varchar(16)` | Không | CHECK theo baseline 9.1 | C1 | Trạng thái |
| `effective_from` | `date` | Có | | C1 | Kỳ bắt đầu áp dụng |
| `manifest` | `jsonb` | Không | Đóng băng | C0 | Tham số, quy tắc làm tròn, nhãn hiển thị |
| `content_hash` | `bytea` | Có | Đóng băng | C1 | SHA-256 dạng chuẩn hoá |
| `bundle_object_key` | `varchar(512)` | Có | | C1 | Gói đã ký trên Ceph |
| `golden_pass_rate` | `numeric(6,4)` | Có | CHECK = 1 khi đạt | C1 | Tỷ lệ ca vàng đạt |
| `last_test_run_id` | `uuid` | Có | FK `policy.test_run` | C1 | Lần kiểm thử gần nhất |
| `signature`, `signer_cert_ref`, `signed_at`, `tsa_token` | | Có | | C1 | Chữ ký số và dấu thời gian |
| `approved_by`, `approved_at` | `uuid`, `timestamptz` | Có | CHECK `approved_by <> created_by` | C1 | Phê duyệt bốn mắt |
| `published_at` | `timestamptz` | Có | | C1 | Thời điểm phát hành (EVT-13) |
| `source_version_id` | `uuid` | Có | | C1 | Phiên bản gốc toàn cục |
| `change_note` | `text` | Không | | C0 | Ghi chú thay đổi |

#### Bảng policy.policy_rule

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `policy_pack_version_id` | `uuid` | Không | FK; UQ (`policy_pack_version_id`, `rule_key`) | C1 | Phiên bản |
| `rule_key` | `varchar(64)` | Không | | C0 | Tên quy tắc, ví dụ `kpi_nhiem_vu`, `A`, `B`, `C`, `diem` |
| `expression` | `text` | Không | Đóng băng | C0 | Biểu thức CEL, ví dụ `max(0.0, 1.0 - 0.05 * double(ngay_lam_viec_cham))` |
| `result_type` | `varchar(12)` | Không | CHECK IN (`DECIMAL`, `BOOLEAN`, `STRING`) | C0 | Kiểu kết quả |
| `rounding` | `jsonb` | Không | | C0 | Số chữ số và chế độ làm tròn |
| `legal_ref` | `varchar(200)` | Không | | C0 | Điều khoản căn cứ, ví dụ "PL 3 mục 2" |
| `eval_order` | `smallint` | Không | | C0 | Thứ tự đánh giá |
| `params_json` | `jsonb` | Không |  | C0 | Tham số của quy tắc (TL-02b) |
| `overridable` | `boolean` | Không | DEFAULT false | C0 | Tenant được ghi đè tham số |

#### Bảng policy.decision_table

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `policy_pack_version_id` | `uuid` | Không | FK | C1 | Phiên bản |
| `table_key` | `varchar(64)` | Không | UQ (`policy_pack_version_id`, `table_key`) | C0 | Ví dụ `xep_loai`, `tieu_chi_chung` |
| `dmn_xml` | `text` | Không | Đóng băng | C0 | Định nghĩa DMN 1.4 |
| `hit_policy` | `varchar(12)` | Không | CHECK IN (`FIRST`, `PRIORITY`, `UNIQUE`) | C0 | Chính sách khớp |
| `dmn_hash` | `bytea` | Không | | C1 | Băm của định nghĩa |
| `legal_ref` | `varchar(200)` | Không | | C0 | Ví dụ "QĐ Điều 11; PL 6 §12" |
| `rules_json` | `jsonb` | Không |  | C0 | Các dòng quy tắc dạng JSON |
| `explanation_templates_json` | `jsonb` | Không |  | C0 | Mẫu câu giải thích theo từng dòng |

#### Bảng policy.golden_test_case

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `policy_pack_id` | `uuid` | Không | FK; UQ (`policy_pack_id`, `case_code`) | C1 | Gói |
| `case_code` | `varchar(64)` | Không | | C0 | Mã ca, ví dụ `BTC-V3-017` |
| `description` | `text` | Không | | C0 | Mô tả tình huống |
| `input` | `jsonb` | Không | | C1 | Dữ liệu đầu vào (dữ liệu giả lập, không chứa dữ liệu cá nhân thật) |
| `expected` | `jsonb` | Không | | C1 | Kết quả mong đợi (điểm, mức, lý do hạ mức) |
| `legal_ref` | `varchar(200)` | Có | | C0 | Điều khoản |
| `source` | `varchar(12)` | Không | CHECK IN (`KHACH_HANG`, `BA`, `VAN_BAN`) | C1 | Nguồn ca |
| `approved_by_customer` | `uuid` | Có | | C1 | Người đại diện khách hàng ký duyệt |
| `approved_at` | `timestamptz` | Có | | C1 | Thời điểm ký duyệt |
| `signature` | `bytea` | Có | | C1 | Chữ ký số trên `input` và `expected` |
| `status` | `varchar(12)` | Không | CHECK IN (`NHAP`, `DA_DUYET`, `NGUNG`) | C1 | Chỉ ca `DA_DUYET` được tính vào tỷ lệ đạt |
| `approved_by` | `uuid` | Có |  | C1 | Người duyệt ca kiểm thử |

#### Bảng policy.test_run và policy.test_case_result (bổ sung)

| Cột của `policy.test_run` | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `policy_pack_version_id` | `uuid` | Không | FK | C1 | Phiên bản được kiểm thử |
| `engine` | `varchar(12)` | Không | CHECK IN (`CEL_JAVA`, `CEL_JS`) | C1 | Engine chạy (chạy cả hai để bảo đảm cùng kết quả, ADR-05) |
| `started_at`, `finished_at` | `timestamptz` | Không/Có | | C1 | Thời gian chạy |
| `total`, `passed`, `failed` | `int` | Không | CHECK `passed + failed = total` | C1 | Thống kê |
| `result` | `varchar(12)` | Không | CHECK IN (`DAT`, `KHONG_DAT`, `DANG_CHAY`) | C1 | Kết luận |
| `report_object_key` | `varchar(512)` | Có | | C1 | Báo cáo chi tiết trên Ceph |
| `engine_match` | `boolean` | Không |  | C1 | Kết quả Java và trình duyệt khớp nhau |
| `property_result_json` | `jsonb` | Có |  | C1 | Kết quả kiểm thử thuộc tính |
| `cases_json` | `jsonb` | Có |  | C1 | Tóm tắt từng ca (chi tiết ở `test_case_result`) |

| Cột của `policy.test_case_result` | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `test_run_id` | `uuid` | Không | PK (`test_run_id`, `golden_test_case_id`) | C1 | Lần chạy |
| `golden_test_case_id` | `uuid` | Không | PK | C1 | Ca kiểm thử |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `passed` | `boolean` | Không | | C1 | Đạt hay không |
| `actual` | `jsonb` | Không | | C1 | Kết quả thực tế |
| `diff` | `jsonb` | Có | | C1 | Khác biệt so với mong đợi |

#### Bảng policy.policy_binding

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `policy_pack_version_id` | `uuid` | Không | FK | C1 | Phiên bản áp dụng |
| `period_id` | `uuid` | Không | UQ NULLS NOT DISTINCT (`tenant_id`, `period_id`, `flow`, `subject_group_id`, `org_unit_id`) | C1 | Kỳ áp dụng |
| `flow` | `varchar(12)` | Không | | C1 | Luồng |
| `subject_group_id` | `uuid` | Có | | C1 | Khối đối tượng; NULL là mọi khối |
| `org_unit_id` | `uuid` | Có | | C1 | Đơn vị phạm vi; NULL là toàn tenant |
| `is_frozen` | `boolean` | Không | DEFAULT false | C1 | Đặt true khi kỳ chuyển `DA_KHOA`; trigger chặn đổi phiên bản |

Schema `policy` nhỏ: vài trăm phiên bản và vài chục nghìn ca kiểm thử mỗi cell, dưới 1 GB, không cần phân vùng.

## Schema okr (MOD-01)

### Thiết kế

Schema `okr` hiện thực nhóm dữ liệu 2 của PL 4 theo đúng định nghĩa của dự thảo (slide 27): mục tiêu O là ưu tiên của kỳ, khuyến nghị một đến ba mục tiêu mỗi kỳ; KR là kết quả đo được, có chỉ tiêu, giá trị hiện tại, trọng số và người xác nhận; KR không đồng nhất với nhiệm vụ. Bảng `okr.basis` lưu căn cứ (nghị quyết, kết luận, chương trình công tác). Bảng `okr.okr_link` là bảng liên kết đa hình nối căn cứ, mục tiêu, KR với nhau và với chương trình, nhiệm vụ của MOD-02 bằng mã định danh logic; đây là nơi lưu truy vết hai chiều theo PL 4 III.2 và liên kết ngang giữa đơn vị. Bảng `okr.kr_checkin` chỉ ghi thêm, mỗi lần check-in là một dòng mới gồm giá trị, mức tự tin và vướng mắc, để thấy được xu hướng trước khi hết quý.

Cảnh báo "đặt quá nhiều mục tiêu O" (quá ba mục tiêu mỗi kỳ) là cảnh báo mềm trên giao diện, không phải ràng buộc CSDL, vì dự thảo dùng chữ "khuyến nghị". Ràng buộc cứng được đặt cho các quy tắc chắc chắn: trọng số KR trong một mục tiêu không vượt 1, KR luôn thuộc một mục tiêu cùng kỳ. Cột `ai_suggestion_id` ghi nhận mục tiêu hoặc KR được tạo từ gợi ý của trợ lý AI (đã được người dùng chấp nhận và tự trình duyệt), phục vụ yêu cầu gắn nhãn theo Luật Trí tuệ nhân tạo; AI không có quyền ghi vào schema này (vai trò `ai_readonly` không có tài khoản CSDL nào).

![Hình 6.6. ERD schema okr](db-erd-okr.png){width=10cm}

Hình 6.6 cho thấy cây mục tiêu được biểu diễn bằng `parent_objective_id` (phân rã ba tầng, cho phép bỏ qua tầng không cần) và liên kết đa hình qua `okr.okr_link`. Mọi bảng có `period_id` đều được phân vùng theo `period_bucket`.

### Từ điển dữ liệu

#### Bảng okr.basis

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `basis_kind` | `varchar(16)` | Không | CHECK IN (`NGHI_QUYET`, `KET_LUAN`, `CHI_THI`, `CHUONG_TRINH`, `KE_HOACH`, `CHI_TIEU_KTXH`) | C0 | Loại căn cứ |
| `doc_number` | `varchar(64)` | Có | | C0 | Số, ký hiệu văn bản, ví dụ "15-NQ/ĐU" |
| `title` | `varchar(500)` | Không | | C0 | Trích yếu |
| `issued_by_unit_id` | `uuid` | Có | | C1 | Đơn vị ban hành |
| `issued_on` | `date` | Có | | C0 | Ngày ban hành |
| `source_doc_ref` | `varchar(200)` | Có | | C1 | Tham chiếu văn bản ở hệ ngoài |
| `parent_basis_id` | `uuid` | Có | FK `okr.basis` | C1 | Căn cứ cấp trên |
| `valid_from`, `valid_to` | `date` | Có | | C0 | Thời gian hiệu lực của căn cứ |

Ước tính 20.000 căn cứ ở CELL-HCM, tăng chậm.

#### Bảng okr.objective

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | `uuid`, `int` | Không | Khoá phân vùng LIST | C1 | Kỳ |
| `owner_type` | `varchar(8)` | Không | CHECK IN (`TAP_THE`, `CA_NHAN`) | C1 | Chủ thể sở hữu |
| `owner_unit_id` | `uuid` | Không | Chỉ mục (`tenant_id`, `owner_unit_id`, `period_id`) | C1 | Đơn vị (tập thể hoặc đơn vị của cá nhân) |
| `owner_person_id` | `uuid` | Có | CHECK có khi `owner_type = 'CA_NHAN'` | C1 | Cá nhân |
| `code` | `varchar(16)` | Không | | C0 | Mã hiển thị, ví dụ "O1" |
| `title` | `varchar(500)` | Không | | C1 | Nội dung mục tiêu |
| `description` | `text` | Có | | C1 | Diễn giải chuyển biến cần đạt |
| `parent_objective_id` | `uuid` | Có | | C1 | Mục tiêu cấp trên (phân rã) |
| `tier` | `smallint` | Không | CHECK BETWEEN 1 AND 3 | C1 | Tầng |
| `status` | `varchar(12)` | Không | CHECK IN (`NHAP`, `CHO_DUYET`, `DA_DUYET`, `DONG`) | C1 | Trạng thái |
| `progress` | `numeric(6,4)` | Không | DEFAULT 0 | C1 | Tiến độ tổng hợp từ KR |
| `approved_by`, `approved_at` | `uuid`, `timestamptz` | Có | | C1 | Phê duyệt (sinh EVT-01) |
| `wf_process_instance_id` | `varchar(64)` | Có | | C1 | Tiến trình duyệt trong Flowable |
| `ai_suggestion_id` | `uuid` | Có | Tham chiếu logic `ai.suggestion_log` | C1 | Mục tiêu bắt nguồn từ gợi ý AI đã được người dùng chấp nhận |
| `progress_pct` | `numeric(6,3)` | Có |  | C1 | Tiến độ tính từ KR (bản tiện dụng) |
| `over_limit_reason` | `varchar(1000)` | Có |  | C1 | Lý do khi vượt số mục tiêu khuyến nghị |
| `version_no` | `int` | Không | DEFAULT 1 | C1 | Lần trình duyệt |
| `ai_assisted` | `boolean` | Không | DEFAULT false | C1 | Nội dung chèn từ gợi ý AI (TL-02b) |

Ước tính 0,25 triệu dòng mỗi quý.

#### Bảng okr.key_result

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | `uuid`, `int` | Không | Phân vùng | C1 | Kỳ |
| `objective_id` | `uuid` | Không | FK (`period_bucket`, `objective_id`) → `okr.objective` | C1 | Mục tiêu |
| `code` | `varchar(16)` | Không | | C0 | Ví dụ "KR 1.1" |
| `title` | `varchar(500)` | Không | | C1 | Nội dung KR đo được |
| `metric_type` | `varchar(12)` | Không | CHECK IN (`SO_LUONG`, `TY_LE`, `CO_KHONG`, `CHI_SO`) | C1 | Loại đo |
| `unit` | `varchar(32)` | Không | | C0 | Đơn vị tính |
| `baseline_value` | `numeric(18,4)` | Có | | C1 | Giá trị xuất phát |
| `target_value` | `numeric(18,4)` | Không | | C1 | Chỉ tiêu cần đạt |
| `current_value` | `numeric(18,4)` | Có | | C1 | Giá trị hiện tại (từ check-in gần nhất đã xác nhận) |
| `direction` | `varchar(8)` | Không | CHECK IN (`TANG`, `GIAM`, `DAT`) | C1 | Chiều tốt |
| `weight` | `numeric(6,4)` | Không | CHECK BETWEEN 0 AND 1 | C1 | Trọng số trong mục tiêu; tổng ≤ 1 kiểm tra bằng trigger hoãn tới cuối giao dịch |
| `due_date` | `date` | Không | | C1 | Hạn |
| `verifier_person_id` | `uuid` | Không | | C1 | Người xác nhận kết quả |
| `data_source` | `varchar(200)` | Có | | C1 | Nguồn số liệu (thủ công, hệ thống nguồn, chỉ tiêu KT-XH đã công bố) |
| `confidence` | `varchar(12)` | Có | CHECK IN (`CAO`, `TRUNG_BINH`, `THAP`) | C1 | Mức tự tin gần nhất |
| `progress` | `numeric(6,4)` | Không | DEFAULT 0 | C1 | Tỷ lệ đạt |
| `status` | `varchar(12)` | Không | CHECK như `objective` | C1 | Trạng thái |
| `ai_suggestion_id` | `uuid` | Có | | C1 | Gợi ý AI được chấp nhận |
| `formula_text` | `varchar(1000)` | Có |  | C1 | Công thức đo |
| `content_source` | `varchar(12)` | Không | CHECK IN (`HUMAN`, `AI_ACCEPTED`, `AI_EDITED`) | C1 | Nguồn nội dung |
| `ai_assisted` | `boolean` | Không | DEFAULT false | C1 | Có dùng gợi ý AI |
| `risk_flag` | `varchar(12)` | Không | CHECK IN (`BINH_THUONG`, `THEO_DOI`, `RUI_RO`) | C1 | Cờ rủi ro (TL-02a) |
| `checkin_frequency` | `varchar(8)` | Không |  | C1 | Tần suất check-in |

Ước tính 0,9 triệu dòng mỗi quý.

#### Bảng okr.objective_basis (bổ sung)

Bảng nối nhiều nhiều giữa mục tiêu và căn cứ (đề xuất của TL-02a), thay cho việc chỉ có một căn cứ mỗi mục tiêu.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `period_bucket` | `int` | Không | PK (`period_bucket`, `objective_id`, `basis_id`) | C1 | Phân vùng |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `objective_id` | `uuid` | Không | FK | C1 | Mục tiêu |
| `basis_id` | `uuid` | Không | FK `okr.basis` | C1 | Căn cứ |
| `created_at`, `created_by` | | Không | | C1 | Tạo |

#### Bảng okr.objective_review (bổ sung)

Bảng lưu ý kiến của người duyệt OKR ở mỗi lần duyệt hoặc trả lại (đề xuất của TL-02a); chỉ ghi thêm.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `objective_id` | `uuid` | Không | FK | C1 | Mục tiêu |
| `version_no` | `int` | Không | UQ (`period_bucket`, `objective_id`, `version_no`, `reviewer_id`) | C1 | Lần trình |
| `reviewer_id` | `uuid` | Không | | C1 | Người duyệt |
| `decision` | `varchar(8)` | Không | CHECK IN (`DUYET`, `TRA_LAI`) | C1 | Quyết định |
| `comment` | `varchar(4000)` | Có | | C1 | Ý kiến |

#### Bảng okr.okr_link

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `period_id` | `uuid` | Không | | C1 | Kỳ |
| `from_type` | `varchar(12)` | Không | CHECK IN (`BASIS`, `OBJECTIVE`, `KEY_RESULT`) | C1 | Loại đầu nguồn |
| `from_id` | `uuid` | Không | Chỉ mục (`tenant_id`, `from_type`, `from_id`) | C1 | Mã đầu nguồn |
| `to_type` | `varchar(12)` | Không | CHECK IN (`OBJECTIVE`, `KEY_RESULT`, `PROGRAM`, `TASK`) | C1 | Loại đầu đích |
| `to_id` | `uuid` | Không | Chỉ mục (`tenant_id`, `to_type`, `to_id`) | C1 | Mã đầu đích; `PROGRAM`, `TASK` là mã logic của schema `task` |
| `link_kind` | `varchar(12)` | Không | CHECK IN (`PHAN_RA`, `DONG_GOP`, `NGANG`, `CAN_CU`) | C1 | Loại liên kết |
| `contribution_weight` | `numeric(6,4)` | Có | | C1 | Mức đóng góp |
| | | | UQ (`tenant_id`, `from_type`, `from_id`, `to_type`, `to_id`) | | Không trùng liên kết |

Hai chỉ mục theo hai chiều giúp truy vết hai chiều: từ căn cứ đi xuống nhiệm vụ và từ nhiệm vụ đi ngược lên căn cứ đều là phép tra chỉ mục. Ước tính 3 triệu dòng mỗi quý.

#### Bảng okr.kr_checkin

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | `uuid`, `int` | Không | Phân vùng | C1 | Kỳ |
| `key_result_id` | `uuid` | Không | Chỉ mục (`tenant_id`, `key_result_id`, `checkin_at DESC`) | C1 | KR |
| `checkin_at` | `timestamptz` | Không | | C1 | Thời điểm check-in |
| `value` | `numeric(18,4)` | Không | | C1 | Giá trị báo cáo |
| `confidence` | `varchar(12)` | Không | CHECK IN (`CAO`, `TRUNG_BINH`, `THAP`) | C1 | Mức tự tin |
| `blocker_note` | `varchar(2000)` | Có | | C1 | Vướng mắc |
| `channel` | `varchar(8)` | Không | CHECK IN (`WEB`, `MOBILE`, `TICH_HOP`) | C1 | Kênh nhập |
| `idempotency_key` | `varchar(64)` | Không | UQ (`tenant_id`, `idempotency_key`, `period_bucket`) | C1 | Chống trùng khi ứng dụng di động gửi lại bản nháp |
| `verified_by`, `verified_at` | `uuid`, `timestamptz` | Có | | C1 | Xác nhận của người xác nhận KR |
| `status` | `varchar(8)` | Không | CHECK IN (`NHAP`, `DA_GUI`); trigger chặn sửa khi `DA_GUI` | C1 | Trạng thái (TL-02a) |
| `source_value` | `numeric(18,4)` | Có |  | C1 | Giá trị lấy từ nguồn tự động |
| `source_fetched_at` | `timestamptz` | Có |  | C1 | Thời điểm lấy |
| `override_reason` | `varchar(1000)` | Có | CHECK bắt buộc khi khác `source_value` | C1 | Lý do nhập khác nguồn |
| `highlights` | `varchar(2000)` | Có |  | C1 | Điểm nổi bật |

Bảng chỉ ghi thêm (vai trò `svc_okr` không có quyền `DELETE`; `UPDATE` chỉ trên `verified_by`, `verified_at`). Ước tính 4 triệu dòng mỗi quý.

## Schema task (MOD-02)

### Thiết kế

Schema `task` là schema lớn nhất về số dòng và tần suất ghi. Nó hiện thực nhóm dữ liệu 3 (KPI dưới dạng danh mục công việc), nhóm 4 (chương trình), nhóm 5 (nhiệm vụ) và nhóm 6 (điểm nghẽn, rủi ro) của PL 4. Thiết kế dựa trên bốn quyết định.

Quyết định thứ nhất là tách danh mục khỏi kế hoạch và khỏi nhiệm vụ. Danh mục công việc (`task.work_catalog`, `task.catalog_item`) theo Bảng 3 của PL 1 là thư viện dùng lại qua các kỳ, gắn với vị trí việc làm; mỗi mục có sản phẩm, đơn vị tính, mức khó năm mức, điểm chuẩn và tiêu chuẩn chất lượng. Kế hoạch quý theo Bảng 1 (`task.plan`, `task.plan_version`, `task.plan_item`) có phiên bản: điều chỉnh kế hoạch tạo phiên bản mới chứ không sửa phiên bản đã duyệt, đúng trạng thái chuẩn ở baseline 9.1. Nhiệm vụ (`task.task`) được sinh từ dòng kế hoạch đã duyệt, từ văn bản chỉ đạo hoặc từ giao việc đột xuất.

Quyết định thứ hai là vòng đời nhiệm vụ chạy bằng máy trạng thái khai báo trong mã, không qua BPMN (ADR-04 tầng 1, câu trả lời cho góp ý G2). Bảng chuyển trạng thái được tham số hoá bằng Policy Pack, mỗi lần chuyển ghi một dòng vào `task.task_state_log` cùng giao dịch với cập nhật `task.task` và dòng outbox. Cột `state_machine_version` trên nhiệm vụ ghi phiên bản bảng chuyển trạng thái đã áp dụng, để hậu kiểm biết quy tắc nào có hiệu lực tại thời điểm chuyển.

Quyết định thứ ba là "một chủ trì, nhiều phối hợp" (QĐ Điều 3.3, PL 1 mục 2) được bảo đảm bằng chỉ mục duy nhất một phần trên `task.task_assignee`: mỗi nhiệm vụ có đúng một dòng vai trò `CHU_TRI` còn hiệu lực. Quyết định thứ tư là kiểm soát trùng lặp (PL 4, nhóm 5 và dấu hiệu bất thường "chẻ nhỏ công việc"): cột `dup_fingerprint` lưu băm của tiêu đề đã chuẩn hoá (bỏ dấu, bỏ khoảng trắng thừa, chữ thường) cộng đơn vị tính và người chủ trì; một chỉ mục trên cột này cho phép phát hiện nhiệm vụ gần trùng trong cùng kỳ khi tạo mới, kết quả là cảnh báo cho người giao việc chứ không chặn cứng.

Các bảng bổ sung gồm `task.program` và `task.program_milestone` cho nhóm 4 "Chương trình"; `task.task_secret_content` lưu nội dung nhiệm vụ mật dạng bản mã đầu cuối (C3); `task.e2ee_key_wrap` lưu khoá nội dung đã bọc cho từng người nhận. Nhiệm vụ mật vẫn có dòng trong `task.task` với tiêu đề che và cờ `is_restricted = true` để tham gia vòng đời, tính điểm và thống kê số lượng, nhưng nội dung thật chỉ người nhận giải mã được ở thiết bị.

![Hình 6.7. ERD schema task (phần danh mục, chương trình, kế hoạch)](db-erd-task-ke-hoach.png){width=15cm}

Hình 6.7 thể hiện chuỗi từ danh mục tới kế hoạch: một mục danh mục được chọn vào nhiều dòng kế hoạch qua các kỳ; mỗi kế hoạch có nhiều phiên bản và mỗi phiên bản có nhiều dòng; chương trình gom các dòng kế hoạch và nhiệm vụ thuộc tầng 2.

![Hình 6.8. ERD schema task (phần nhiệm vụ và các bảng vệ tinh)](db-erd-task-nhiem-vu.png){width=15cm}

Hình 6.8 thể hiện `task.task` và các bảng vệ tinh. Mọi bảng vệ tinh mang `period_bucket` của nhiệm vụ cha để khoá ngoại tham chiếu được khoá chính hai cột của bảng phân vùng, và để các phân vùng của cùng một kỳ có thể được tách ra lưu trữ cùng nhau.

### DDL mẫu: task.task

```sql
CREATE TABLE task.task (
  id                    uuid          NOT NULL,
  tenant_id             uuid          NOT NULL,
  period_id             uuid          NOT NULL,
  period_bucket         int           NOT NULL,
  code                  varchar(32)   NOT NULL,
  title                 varchar(500)  NOT NULL,
  description           text,
  task_group            varchar(16)   NOT NULL,
  is_key_task           boolean       NOT NULL DEFAULT false,
  source                varchar(16)   NOT NULL,
  plan_item_id          uuid,
  program_id            uuid,
  catalog_item_id       uuid,
  key_result_id         uuid,                       -- okr.key_result.id (tham chiếu logic)
  parent_task_id        uuid,
  assigner_id           uuid          NOT NULL,
  assigned_at           timestamptz,
  start_date            date,
  due_date              date          NOT NULL,
  extended_due_date     date,
  planned_quantity      numeric(18,4) NOT NULL DEFAULT 1,
  done_quantity         numeric(18,4) NOT NULL DEFAULT 0,
  unit                  varchar(32)   NOT NULL,
  difficulty            smallint,
  standard_score        numeric(6,3),
  progress_pct          numeric(6,4)  NOT NULL DEFAULT 0,
  status                varchar(20)   NOT NULL DEFAULT 'NHAP',
  is_overdue            boolean       NOT NULL DEFAULT false,
  is_restricted         boolean       NOT NULL DEFAULT false,
  dup_fingerprint       bytea         NOT NULL,
  state_machine_version varchar(32)   NOT NULL,
  completed_at          timestamptz,
  cancelled_reason      varchar(1000),
  created_at            timestamptz   NOT NULL DEFAULT now(),
  created_by            uuid          NOT NULL,
  updated_at            timestamptz   NOT NULL DEFAULT now(),
  updated_by            uuid          NOT NULL,
  row_version           int           NOT NULL DEFAULT 0,
  CONSTRAINT pk_task PRIMARY KEY (period_bucket, id),
  CONSTRAINT uq_task__code UNIQUE (period_bucket, tenant_id, code),
  CONSTRAINT ck_task__status CHECK (status IN
    ('NHAP','DA_GIAO','DANG_THUC_HIEN','CHO_NGHIEM_THU','HOAN_THANH','TAM_DUNG','HUY')),
  CONSTRAINT ck_task__group CHECK (task_group IN ('THUONG_XUYEN','THEN_CHOT')),
  CONSTRAINT ck_task__source CHECK (source IN ('KE_HOACH','VAN_BAN','DANG_KY','DOT_XUAT')),
  CONSTRAINT ck_task__progress CHECK (progress_pct BETWEEN 0 AND 1),
  CONSTRAINT ck_task__quantity CHECK (planned_quantity > 0 AND done_quantity >= 0),
  CONSTRAINT ck_task__difficulty CHECK (difficulty IS NULL OR difficulty BETWEEN 1 AND 5),
  CONSTRAINT ck_task__score CHECK (standard_score IS NULL OR standard_score BETWEEN 0 AND 100),
  CONSTRAINT ck_task__dates CHECK (start_date IS NULL OR start_date <= due_date),
  CONSTRAINT ck_task__done CHECK (status <> 'HOAN_THANH' OR completed_at IS NOT NULL)
) PARTITION BY LIST (period_bucket);

-- Phân vùng của quý I/2027; ở cell pool chia tiếp 16 phân vùng con theo băm tenant_id
CREATE TABLE task.task_p20271 PARTITION OF task.task FOR VALUES IN (20271);

CREATE INDEX ix_task__tenant_due_p ON task.task (tenant_id, due_date)
  WHERE status IN ('DA_GIAO','DANG_THUC_HIEN','CHO_NGHIEM_THU');
CREATE INDEX ix_task__plan_item ON task.task (tenant_id, plan_item_id);
CREATE INDEX ix_task__key_result ON task.task (tenant_id, key_result_id) WHERE key_result_id IS NOT NULL;
CREATE INDEX ix_task__program ON task.task (tenant_id, program_id) WHERE program_id IS NOT NULL;
CREATE INDEX ix_task__dup ON task.task (tenant_id, period_id, dup_fingerprint);
CREATE INDEX ix_task__overdue_p ON task.task (tenant_id, due_date) WHERE is_overdue;

ALTER TABLE task.task ENABLE ROW LEVEL SECURITY;
ALTER TABLE task.task FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_task__tenant ON task.task FOR ALL TO svc_task, ro_task
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

Danh sách "Việc của tôi" (SCR-0503, SCR-0504) là truy vấn đọc nhiều nhất của hệ thống. Nó đi qua `task.task_assignee` với chỉ mục `(tenant_id, person_id, period_bucket)` rồi nối sang `task.task` theo khoá chính, chỉ chạm phân vùng của kỳ hiện tại. Chỉ mục một phần `ix_task__tenant_due_p` chỉ chứa nhiệm vụ đang mở, nên kích thước nhỏ hơn khoảng ba lần so với chỉ mục toàn bộ và phục vụ job đánh dấu quá hạn chạy mỗi giờ.

### Từ điển dữ liệu

#### Bảng task.work_catalog

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `code` | `varchar(50)` | Không | UQ (`tenant_id`, `code`, `version`) | C0 | Mã danh mục |
| `name` | `varchar(255)` | Không | | C0 | Tên danh mục, ví dụ "Danh mục công việc chuyên viên Ban Tổ chức" |
| `scope` | `varchar(12)` | Không | CHECK IN (`VI_TRI`, `DON_VI`, `QUOC_GIA`) | C0 | Phạm vi |
| `position_id` | `uuid` | Có | | C1 | Vị trí việc làm gắn danh mục |
| `org_unit_id` | `uuid` | Có | | C1 | Đơn vị sở hữu; đơn vị con kế thừa |
| `version` | `int` | Không | | C0 | Phiên bản danh mục |
| `source` | `varchar(12)` | Không | CHECK IN (`EXCEL`, `THU_VIEN_QG`, `TU_TAO`, `DI_TRU`) | C1 | Nguồn (nhập Excel tối đa 5.000 dòng như hiện trạng, thư viện quốc gia, di trú) |
| `status` | `varchar(12)` | Không | CHECK IN (`NHAP`, `HIEU_LUC`, `NGUNG`) | C1 | Trạng thái |
| `parent_catalog_id` | `uuid` | Có | FK | C1 | Danh mục cấp trên được kế thừa |
| `version_no` | `int` | Không |  | C1 | Phiên bản |

#### Bảng task.catalog_item

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `work_catalog_id` | `uuid` | Không | FK; UQ (`work_catalog_id`, `code`) | C1 | Danh mục |
| `code` | `varchar(50)` | Không | | C0 | Mã công việc (Bảng 3) |
| `name` | `varchar(500)` | Không | | C0 | Tên công việc |
| `product_name` | `varchar(500)` | Không | | C0 | Sản phẩm đầu ra |
| `unit` | `varchar(32)` | Không | | C0 | Đơn vị tính |
| `difficulty` | `smallint` | Không | CHECK BETWEEN 1 AND 5 | C0 | Mức khó năm mức |
| `standard_score` | `numeric(6,3)` | Không | CHECK BETWEEN 0 AND 100 | C0 | Điểm chuẩn |
| `quality_standard` | `text` | Không | | C0 | Tiêu chuẩn chất lượng |
| `evidence_requirement` | `text` | Có | | C0 | Yêu cầu minh chứng |
| `task_group` | `varchar(16)` | Không | CHECK IN (`THUONG_XUYEN`, `THEN_CHOT`) | C0 | Nhóm (một nhiệm vụ chỉ tính ở một nhóm) |
| `data_source` | `varchar(200)` | Có | | C0 | Nguồn dữ liệu đo (PL 4 nhóm 3) |
| `position_id` | `uuid` | Có |  | C1 | Vị trí việc làm áp dụng |
| `default_group` | `varchar(16)` | Có |  | C1 | Nhóm mặc định (thường xuyên, then chốt) |
| `standard_days` | `int` | Có |  | C1 | Thời gian chuẩn |
| `base_score` | `numeric(6,3)` | Có | CHECK 0 đến 100 | C1 | Điểm chuẩn gợi ý |

Ước tính 0,5 triệu mục danh mục ở CELL-HCM, ổn định qua các kỳ.

#### Bảng task.program (bổ sung) và task.program_milestone (bổ sung)

| Cột của `task.program` | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `code` | `varchar(50)` | Không | UQ (`tenant_id`, `code`) | C0 | Mã chương trình, đề án |
| `name` | `varchar(500)` | Không | | C0 | Tên |
| `program_kind` | `varchar(12)` | Không | CHECK IN (`CHUONG_TRINH`, `DE_AN`, `GOI`, `DOT`) | C0 | Loại (PL 4 nhóm 4: chương trình, gói, đợt triển khai) |
| `owner_unit_id` | `uuid` | Không | | C1 | Đơn vị chủ trì |
| `parent_program_id` | `uuid` | Có | FK `task.program` | C1 | Chương trình cha |
| `start_date`, `end_date` | `date` | Không | | C0 | Thời gian |
| `status` | `varchar(12)` | Không | CHECK IN (`NHAP`, `DANG_TRIEN_KHAI`, `HOAN_THANH`, `DUNG`) | C1 | Trạng thái |

| Cột của `task.program_milestone` | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `program_id` | `uuid` | Không | FK | C1 | Chương trình |
| `name` | `varchar(255)` | Không | | C0 | Tên mốc kiểm soát |
| `due_date` | `date` | Không | | C0 | Hạn |
| `status` | `varchar(12)` | Không | CHECK IN (`CHUA_DEN`, `DAT`, `TRE`, `HUY`) | C1 | Trạng thái |
| `achieved_at` | `timestamptz` | Có | | C1 | Thời điểm đạt |

Liên kết giữa chương trình và mục tiêu O được lưu ở `okr.okr_link` với `to_type = 'PROGRAM'`.

#### Bảng task.plan

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | `uuid`, `int` | Không | Phân vùng | C1 | Kỳ |
| `plan_type` | `varchar(8)` | Không | CHECK IN (`TAP_THE`, `CA_NHAN`) | C1 | Kế hoạch tập thể hoặc cá nhân |
| `owner_unit_id` | `uuid` | Không | | C1 | Đơn vị |
| `owner_person_id` | `uuid` | Có | UQ NULLS NOT DISTINCT (`period_bucket`, `tenant_id`, `period_id`, `owner_unit_id`, `owner_person_id`) | C1 | Cá nhân (với kế hoạch cá nhân) |
| `current_version_id` | `uuid` | Có | | C1 | Phiên bản hiện hành đã duyệt |

#### Bảng task.plan_version

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `plan_id` | `uuid` | Không | FK (`period_bucket`, `plan_id`); UQ (`period_bucket`, `plan_id`, `version_no`) | C1 | Kế hoạch |
| `version_no` | `int` | Không | | C1 | Số phiên bản, bắt đầu từ 1 |
| `status` | `varchar(12)` | Không | CHECK IN (`NHAP`, `CHO_DUYET`, `DA_DUYET`, `TRA_LAI`) | C1 | Trạng thái baseline 9.1 |
| `is_current` | `boolean` | Không | DEFAULT false; UQ một phần | C1 | Phiên bản đã duyệt đang hiện hành (cột bổ sung) |
| `change_reason` | `text` | Có | CHECK có khi `version_no > 1` | C1 | Lý do điều chỉnh |
| `submitted_at` | `timestamptz` | Có | | C1 | Thời điểm trình |
| `approved_by`, `approved_at` | | Có | | C1 | Phê duyệt (sinh EVT-03) |
| `return_reason` | `text` | Có | | C1 | Lý do trả lại |
| `content_hash` | `bytea` | Có | | C1 | Băm nội dung các dòng kế hoạch, là dữ liệu được ký số ở mốc "duyệt kế hoạch" |
| `sign_request_id` | `uuid` | Có | | C1 | Tham chiếu logic `esign.sign_request` |
| `wf_process_instance_id` | `varchar(64)` | Có | | C1 | Tiến trình duyệt |
| `review_comment` | `varchar(4000)` | Có |  | C1 | Ý kiến duyệt hoặc trả lại |

Chỉ mục duy nhất một phần `uq_plan_version__approved_p (period_bucket, plan_id) WHERE status = 'DA_DUYET' AND is_current` bảo đảm một kế hoạch chỉ có một phiên bản đã duyệt hiện hành; cột `is_current boolean` được bổ sung cho mục đích này. Khi phiên bản mới được duyệt, giao dịch đặt `is_current = false` cho phiên bản cũ trước rồi mới đặt cho phiên bản mới. So sánh phiên bản (SCR-0403) là phép so sánh tập dòng của hai `plan_version_id`.

#### Bảng task.plan_item

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `plan_version_id` | `uuid` | Không | FK | C1 | Phiên bản kế hoạch |
| `item_no` | `int` | Không | UQ (`period_bucket`, `plan_version_id`, `item_no`) | C1 | Số thứ tự |
| `catalog_item_id` | `uuid` | Có | | C1 | Mục danh mục |
| `program_id` | `uuid` | Có | | C1 | Chương trình (tầng 2) |
| `key_result_id` | `uuid` | Có | | C1 | KR đóng góp |
| `title` | `varchar(500)` | Không | | C1 | Nội dung công việc |
| `task_group` | `varchar(16)` | Không | | C1 | Nhóm |
| `lead_unit_id`, `lead_person_id` | `uuid` | Có | CHECK đúng một trong hai khác NULL | C1 | Chủ trì duy nhất |
| `co_unit_ids` | `uuid[]` | Không | DEFAULT `'{}'` | C1 | Đơn vị phối hợp |
| `planned_quantity` | `numeric(18,4)` | Không | | C1 | Khối lượng kế hoạch |
| `unit` | `varchar(32)` | Không | | C0 | Đơn vị tính |
| `difficulty`, `standard_score` | | Có | | C1 | Mức khó, điểm chuẩn |
| `due_date` | `date` | Không | | C1 | Hạn |
| `origin_item_id` | `uuid` | Có |  | C1 | Dòng tương ứng ở phiên bản trước, dùng so sánh phiên bản (TL-02a) |
| `change_reason` | `varchar(1000)` | Có |  | C1 | Lý do điều chỉnh |
| `co_assignees` | `jsonb` | Có |  | C1 | Người phối hợp dự kiến |
| `recurrence` | `varchar(32)` | Có |  | C1 | Lặp lại (hằng tháng, hằng quý) |

Ước tính 14 triệu dòng mỗi quý (bao gồm các phiên bản điều chỉnh).

#### Bảng task.task

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng LIST | C1 | Kỳ |
| `code` | `varchar(32)` | Không | UQ (`period_bucket`, `tenant_id`, `code`) | C1 | Mã nhiệm vụ |
| `title` | `varchar(500)` | Không | | C1 | Tiêu đề; nhiệm vụ mật là chuỗi che "Nhiệm vụ hạn chế" |
| `description` | `text` | Có | | C1 | Mô tả; NULL với nhiệm vụ mật |
| `task_group` | `varchar(16)` | Không | CHECK | C1 | Thường xuyên hoặc then chốt |
| `is_key_task` | `boolean` | Không | | C1 | Nhiệm vụ then chốt (điều kiện HTTNV) |
| `source` | `varchar(16)` | Không | CHECK | C1 | Kế hoạch, văn bản, đăng ký, đột xuất |
| `plan_item_id`, `program_id`, `catalog_item_id`, `key_result_id`, `parent_task_id` | `uuid` | Có | Chỉ mục | C1 | Liên kết truy vết |
| `assigner_id` | `uuid` | Không | | C1 | Người giao |
| `assigned_at` | `timestamptz` | Có | | C1 | Thời điểm giao (EVT-04) |
| `start_date`, `due_date`, `extended_due_date` | `date` | Không/Có | CHECK | C1 | Thời hạn; hạn gia hạn do `task.task_change_request` được duyệt |
| `planned_quantity`, `done_quantity` | `numeric(18,4)` | Không | CHECK | C1 | Khối lượng kế hoạch và đã hoàn thành |
| `unit` | `varchar(32)` | Không | | C0 | Đơn vị tính |
| `difficulty`, `standard_score` | | Có | CHECK | C1 | Mức khó, điểm chuẩn |
| `progress_pct` | `numeric(6,4)` | Không | CHECK 0 đến 1 | C1 | Tiến độ |
| `status` | `varchar(20)` | Không | CHECK baseline 9.1 | C1 | Trạng thái |
| `is_overdue` | `boolean` | Không | Chỉ mục một phần | C1 | Cờ quá hạn |
| `is_restricted` | `boolean` | Không | | C1 | Nhiệm vụ mật, nội dung ở `task.task_secret_content` |
| `dup_fingerprint` | `bytea` | Không | Chỉ mục | C1 | Băm chống trùng |
| `state_machine_version` | `varchar(32)` | Không | | C1 | Phiên bản bảng chuyển trạng thái |
| `completed_at` | `timestamptz` | Có | CHECK | C1 | Thời điểm hoàn thành |
| `cancelled_reason` | `varchar(1000)` | Có | | C1 | Lý do huỷ |
| `source_type` | `varchar(16)` | Không | CHECK IN (`KE_HOACH`, `VAN_BAN`, `PHAT_SINH`) | C1 | Nguồn phát sinh nhiệm vụ (TL-02a) |
| `kr_id` | `uuid` | Có |  | C1 | KR mà nhiệm vụ đóng góp |
| `acceptor_id` | `uuid` | Có |  | C1 | Người nghiệm thu |
| `milestones` | `jsonb` | Có |  | C1 | Mốc trung gian |
| `score_due_date` | `date` | Có |  | C1 | Hạn tính hệ số tiến độ C, khác `due_date` là hạn thực hiện |
| `delay_risk` | `varchar(8)` | Có | CHECK IN (`THAP`, `VUA`, `CAO`) | C1 | Nguy cơ trễ hạn do hệ thống đánh giá |

Ước tính 14,4 triệu dòng mỗi quý ở CELL-HCM, khoảng 1 KB mỗi dòng kể cả chỉ mục, tức khoảng 15 GB mỗi quý.

#### Bảng task.task_assignee

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `task_id` | `uuid` | Không | FK (`period_bucket`, `task_id`) → `task.task` | C1 | Nhiệm vụ |
| `assignee_type` | `varchar(8)` | Không | CHECK IN (`CA_NHAN`, `DON_VI`) | C1 | Người hay đơn vị |
| `person_id` | `uuid` | Có | Chỉ mục (`tenant_id`, `person_id`, `period_bucket`) | C1 | Người thực hiện |
| `org_unit_id` | `uuid` | Có | Chỉ mục | C1 | Đơn vị thực hiện |
| `role` | `varchar(8)` | Không | CHECK IN (`CHU_TRI`, `PHOI_HOP`) | C1 | Vai trò |
| `accepted_at` | `timestamptz` | Có | | C1 | Thời điểm nhận việc |
| `removed_at` | `timestamptz` | Có | | C1 | Thời điểm rút khỏi nhiệm vụ (giao lại) |
| | | | UQ một phần `uq_task_assignee__one_lead_p (period_bucket, task_id) WHERE role = 'CHU_TRI' AND removed_at IS NULL` | | Đúng một chủ trì |
| `valid_from`, `valid_to` | `date` | Không/Có | Cột sinh `valid_period` | C1 | Trục hiệu lực của việc giao (TL-01 gọi là `task.assignment`) |
| `recorded_from`, `recorded_to` | `timestamptz` | Không/Có | Cột sinh `recorded_period`; EX chống chồng lấn theo (`task_id`, `person_id`, `role`) | C1 | Trục ghi nhận |

Theo TL-01, việc giao nhiệm vụ (TL-01 gọi là `task.assignment`, trong TL-03 giữ tên baseline `task.task_assignee`) là dữ liệu hai trục thời gian: giao lại cho người khác giữa chừng là đóng trục hiệu lực của dòng cũ và chèn dòng mới, còn đính chính một lần giao nhập sai là đóng trục ghi nhận. Nhờ đó hệ số tiến độ, phân chia điểm giữa người cũ và người mới, và câu hỏi "ai chủ trì nhiệm vụ này vào ngày X" đều trả lời được. Cột `removed_at` giữ lại làm bản tiện dụng, bằng `valid_to` của dòng hiện hành. Ước tính 21 triệu dòng mỗi quý.

#### Bảng task.task_progress

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `task_id` | `uuid` | Không | FK; chỉ mục (`tenant_id`, `task_id`, `reported_at DESC`) | C1 | Nhiệm vụ |
| `reported_at` | `timestamptz` | Không | | C1 | Thời điểm báo cáo |
| `progress_pct` | `numeric(6,4)` | Không | CHECK 0 đến 1 | C1 | Tiến độ báo cáo |
| `done_quantity` | `numeric(18,4)` | Có | | C1 | Khối lượng đạt |
| `note` | `varchar(2000)` | Có | | C1 | Ghi chú |
| `channel` | `varchar(8)` | Không | CHECK IN (`WEB`, `MOBILE`, `TICH_HOP`) | C1 | Kênh |
| `idempotency_key` | `varchar(64)` | Không | UQ (`period_bucket`, `tenant_id`, `idempotency_key`) | C1 | Chống gửi trùng |

Bảng chỉ ghi thêm. Ước tính 58 triệu dòng mỗi quý (bốn lần cập nhật mỗi nhiệm vụ), khoảng 20 GB mỗi quý; đây là bảng có tần suất ghi cao nhất lúc cao điểm cùng với `task.task_state_log`.

#### Bảng task.task_blocker

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `task_id` | `uuid` | Không | FK | C1 | Nhiệm vụ |
| `cause_category` | `varchar(16)` | Không | CHECK IN (`NGUON_LUC`, `PHOI_HOP`, `THU_TUC`, `KHACH_QUAN`, `KHAC`) | C1 | Nhóm nguyên nhân |
| `cause_detail` | `varchar(2000)` | Không | | C1 | Nguyên nhân cụ thể |
| `impact_level` | `varchar(12)` | Không | CHECK IN (`CAO`, `TRUNG_BINH`, `THAP`) | C1 | Mức ảnh hưởng |
| `handler_person_id` | `uuid` | Có | | C1 | Người xử lý |
| `resolve_by` | `date` | Có | | C1 | Hạn xử lý |
| `status` | `varchar(16)` | Không | CHECK IN (`MO`, `DANG_XU_LY`, `DA_GIAI_QUYET`, `DONG`) | C1 | Trạng thái |
| `resolution` | `varchar(2000)` | Có | | C1 | Kết quả xử lý |
| `resolved_at` | `timestamptz` | Có | | C1 | Thời điểm giải quyết |
| `escalated_to_person_id` | `uuid` | Có | | C1 | Cấp trên được báo |

Ước tính 0,7 triệu dòng mỗi quý.

#### Bảng task.task_change_request

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `task_id` | `uuid` | Không | FK | C1 | Nhiệm vụ |
| `request_type` | `varchar(24)` | Không | CHECK IN (`GIA_HAN`, `DIEU_CHINH_KHOI_LUONG`, `DIEU_CHINH_NOI_DUNG`, `HUY`) | C1 | Loại đề nghị |
| `proposed_due_date` | `date` | Có | | C1 | Hạn đề nghị |
| `proposed_quantity` | `numeric(18,4)` | Có | | C1 | Khối lượng đề nghị |
| `reason` | `varchar(2000)` | Không | | C1 | Lý do |
| `is_objective_reason` | `boolean` | Không | DEFAULT false | C1 | Lý do khách quan (miễn trừ trừ điểm tiến độ khi được duyệt) |
| `status` | `varchar(12)` | Không | CHECK IN (`NHAP`, `CHO_DUYET`, `DA_DUYET`, `TU_CHOI`, `DA_RUT`) | C1 | Trạng thái (bộ trạng thái của TL-02a) |
| `decided_by` | `uuid` | Có | CHECK khác `created_by` | C1 | Người duyệt |
| `decided_at` | `timestamptz` | Có | | C1 | Thời điểm |
| `decision_note` | `varchar(2000)` | Có | | C1 | Ý kiến |
| `cause_claimed` | `varchar(16)` | Có |  | C1 | Nguyên nhân người đề nghị khai |
| `cause_decided` | `varchar(16)` | Có | CHECK IN (`CHU_QUAN`, `KHACH_QUAN`) | C1 | Nguyên nhân người duyệt kết luận (ảnh hưởng hệ số C) |
| `blocker_id` | `uuid` | Có |  | C1 | Vướng mắc liên quan |
| `approver_id` | `uuid` | Có |  | C1 | Người duyệt |
| `attachment_ids` | `uuid[]` | Không | DEFAULT `'{}'` | C1 | Tệp kèm |
| `new_values` | `jsonb` | Không |  | C1 | Giá trị đề nghị (hạn, tỷ trọng, mục tiêu) |

Cột `decided_by` và `is_objective_reason` là dữ liệu đầu vào của dấu hiệu bất thường "miễn trừ khách quan lặp lại" (slide 31). Ước tính 1,5 triệu dòng mỗi quý.

#### Bảng task.task_source_doc

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `task_id` | `uuid` | Không | FK | C1 | Nhiệm vụ |
| `source_system` | `varchar(12)` | Không | CHECK IN (`VOFFICE`, `IOFFICE`, `EDXML`, `LGSP`, `THU_CONG`) | C1 | Hệ nguồn |
| `external_doc_id` | `varchar(128)` | Có | | C1 | Mã văn bản ở hệ nguồn |
| `doc_number` | `varchar(64)` | Có | | C0 | Số, ký hiệu |
| `doc_date` | `date` | Có | | C0 | Ngày văn bản |
| `excerpt` | `varchar(1000)` | Có | | C1 | Trích yếu |
| `received_event_id` | `uuid` | Có | | C1 | EVT-24 đã sinh nhiệm vụ |
| `ai_extracted` | `boolean` | Không | DEFAULT false | C1 | Nhiệm vụ do AI trích từ văn bản, đã được người dùng xác nhận |
| `paragraph_no` | `int` | Có | UQ (`period_bucket`, `tenant_id`, `doc_ref`, `paragraph_no`) chống tạo trùng | C1 | Đoạn văn bản giao việc |

#### Bảng task.task_state_log

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK (`period_bucket`, `id`) | C1 | Mã dòng |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `task_id` | `uuid` | Không | Chỉ mục (`tenant_id`, `task_id`, `occurred_at`) | C1 | Nhiệm vụ |
| `from_status`, `to_status` | `varchar(20)` | Có/Không | | C1 | Chuyển trạng thái |
| `transition` | `varchar(48)` | Không | | C1 | Tên chuyển, ví dụ `GIAO`, `NOP_NGHIEM_THU`, `TAM_DUNG` |
| `actor_id` | `uuid` | Không | | C1 | Người thực hiện |
| `reason` | `varchar(2000)` | Có | | C1 | Lý do |
| `before_after` | `jsonb` | Không | | C1 | Giá trị trước và sau của các trường thay đổi (PL 4 III.2) |
| `state_machine_version` | `varchar(32)` | Không | | C1 | Phiên bản quy tắc |
| `occurred_at` | `timestamptz` | Không | | C1 | Thời điểm |

Bảng chỉ ghi thêm (thu hồi `UPDATE`, `DELETE` của `svc_task`). Ước tính 72 triệu dòng mỗi quý, khoảng 30 GB. Đây là nhật ký nghiệp vụ trong module; nhật ký có giá trị chứng cứ là `ledger.ledger_entry` ở SVC-09, nhận cùng thông tin qua outbox.

#### Bảng task.task_secret_content (bổ sung)

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `task_id` | `uuid` | Không | UQ (`period_bucket`, `task_id`) | C1 | Nhiệm vụ mật |
| `ciphertext` | `bytea` | Không | | C3 | Nội dung (tiêu đề thật, mô tả, chỉ đạo) mã hoá AES-256-GCM tại client |
| `nonce` | `bytea` | Không | CHECK `length(nonce) = 12` | C1 | Nonce 96 bit |
| `content_alg` | `varchar(16)` | Không | DEFAULT `A256GCM` | C1 | Thuật toán nội dung |
| `aad_hash` | `bytea` | Không | | C1 | Băm dữ liệu xác thực kèm (`tenant_id`, `task_id`, phiên bản) |
| `title_bidx` | `bytea` | Có | Chỉ mục | C1 | Chỉ mục mù HMAC trên số ký hiệu văn bản mật, do client tính |
| `sender_key_fingerprint` | `bytea` | Không | | C1 | Khoá của người tạo |

#### Bảng task.e2ee_key_wrap (bổ sung)

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | | C1 | |
| `object_type` | `varchar(16)` | Không | CHECK IN (`TASK_CONTENT`) | C1 | Loại đối tượng |
| `object_id` | `uuid` | Không | UQ (`tenant_id`, `object_id`, `recipient_key_fingerprint`) | C1 | Mã đối tượng |
| `recipient_person_id` | `uuid` | Không | Chỉ mục | C1 | Người nhận |
| `recipient_key_fingerprint` | `bytea` | Không | | C1 | Khoá công khai đã dùng (`org.person_public_key.fingerprint`) |
| `wrap_alg` | `varchar(24)` | Không | CHECK IN (`ECDH-ES+A256KW`, `RSA-OAEP-256`, `BCY`) | C1 | Thuật toán bọc |
| `wrapped_cek` | `bytea` | Không | | C1 | Khoá nội dung đã bọc cho người nhận (chỉ người nhận mở được) |
| `ephemeral_pub` | `bytea` | Có | | C1 | Khoá công khai tạm thời (ECDH-ES) |
| `granted_by` | `uuid` | Không | | C1 | Người cấp quyền đọc |
| `revoked_at` | `timestamptz` | Có | | C1 | Thu hồi (chỉ ngăn tải bản mã về; không thu hồi được khoá đã tải) |

## Schema product (MOD-03)

### Thiết kế

Schema `product` hiện thực nhóm dữ liệu 7 về phía nghiệp vụ: sản phẩm của nhiệm vụ, minh chứng có phiên bản và nghiệm thu chất lượng năm mức. Tệp vật lý không nằm ở đây mà ở SVC-08 evidence-service (schema `evidence` và Ceph); `product.evidence_version` chỉ giữ tham chiếu `object_id`, mã băm SHA-256 và siêu dữ liệu. Thiết kế tách hai vòng đời đúng như PL 4 II.2 yêu cầu: vòng đời thực hiện nằm ở `task.task.status`, còn vòng đời nghiệm thu nằm ở `product.evidence_version.status`. Chỉ phiên bản ở trạng thái `DA_XAC_NHAN` mới được SVC-07 tính điểm (baseline 9.1), và sự kiện EVT-08 chỉ phát ra khi chuyển sang trạng thái này.

Ba quy tắc toàn vẹn được hiện thực bằng ràng buộc CSDL. Một là phiên bản minh chứng bất biến sau khi nộp: trigger chặn mọi thay đổi `object_id`, `content_sha256`, `size_bytes` khi trạng thái khác `NHAP`; bổ sung minh chứng luôn tạo phiên bản mới, phiên bản cũ vẫn còn (tiêu chí I5). Hai là dữ liệu do AI hoặc OCR điền sẵn phải được người dùng xác nhận trước khi nộp (slide 19): ràng buộc `CHECK` yêu cầu `user_confirmed_at` khác NULL nếu `ai_prefill = true` và trạng thái khác `NHAP`. Ba là người nghiệm thu khác người nộp (PL 4 V.1): ràng buộc trên `product.acceptance`.

Cảnh báo minh chứng trùng (dấu hiệu bất thường thứ tư, PL 4 III.5) dựa trên `content_sha256`: khi phiên bản được nộp, MOD-03 tra chỉ mục `(tenant_id, content_sha256)` để tìm phiên bản khác cùng băm thuộc nhiệm vụ khác hoặc người khác; nếu có, phiên bản được đặt `is_duplicate_suspect = true` và chưa được tính điểm cho tới khi có người xác nhận hợp lệ.

![Hình 6.9. ERD schema product và liên kết logic sang schema evidence](db-erd-product.png){width=13cm}

Hình 6.9 cho thấy chuỗi nhiệm vụ, sản phẩm, minh chứng, phiên bản, nghiệm thu; đường nét đứt sang `evidence.object_meta` là tham chiếu logic giữa hai service, không có khoá ngoại.

### DDL mẫu: product.evidence_version

```sql
CREATE TABLE product.evidence_version (
  id                    uuid          NOT NULL,
  tenant_id             uuid          NOT NULL,
  period_id             uuid          NOT NULL,
  period_bucket         int           NOT NULL,
  evidence_id           uuid          NOT NULL,
  task_id               uuid          NOT NULL,
  version_no            int           NOT NULL,
  status                varchar(20)   NOT NULL DEFAULT 'NHAP',
  object_id             uuid,                       -- evidence.object_meta.id (SVC-08, tham chiếu logic)
  object_version_id     uuid,
  content_sha256        bytea,
  size_bytes            bigint,
  media_type            varchar(127),
  file_name             varchar(255),
  source                varchar(20)   NOT NULL,
  source_doc_ref        varchar(200),
  ai_prefill            boolean       NOT NULL DEFAULT false,
  ai_suggestion_id      uuid,
  user_confirmed_at     timestamptz,
  scan_status           varchar(12)   NOT NULL DEFAULT 'CHO_QUET',
  is_duplicate_suspect  boolean       NOT NULL DEFAULT false,
  duplicate_of_id       uuid,
  has_e2ee              boolean       NOT NULL DEFAULT false,
  submitted_by          uuid,
  submitted_at          timestamptz,
  accepted_at           timestamptz,
  note                  varchar(2000),
  created_at            timestamptz   NOT NULL DEFAULT now(),
  created_by            uuid          NOT NULL,
  updated_at            timestamptz   NOT NULL DEFAULT now(),
  updated_by            uuid          NOT NULL,
  row_version           int           NOT NULL DEFAULT 0,
  CONSTRAINT pk_evidence_version PRIMARY KEY (period_bucket, id),
  CONSTRAINT fk_evidence_version__evidence FOREIGN KEY (period_bucket, evidence_id)
    REFERENCES product.evidence (period_bucket, id),
  CONSTRAINT uq_evidence_version__no UNIQUE (period_bucket, evidence_id, version_no),
  CONSTRAINT ck_evidence_version__status CHECK (status IN
    ('NHAP','DA_NOP','DANG_NGHIEM_THU','DA_XAC_NHAN','YEU_CAU_BO_SUNG','TU_CHOI')),
  CONSTRAINT ck_evidence_version__source CHECK (source IN
    ('TAI_LEN','VAN_BAN_HE_THONG','OCR_DI_DONG','TICH_HOP')),
  CONSTRAINT ck_evidence_version__scan CHECK (scan_status IN ('CHO_QUET','SACH','NHIEM','LOI')),
  CONSTRAINT ck_evidence_version__hash CHECK (content_sha256 IS NULL OR length(content_sha256) = 32),
  CONSTRAINT ck_evidence_version__submitted CHECK (
    status = 'NHAP' OR (object_id IS NOT NULL AND content_sha256 IS NOT NULL
                        AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL)),
  CONSTRAINT ck_evidence_version__ai_confirm CHECK (
    NOT ai_prefill OR status = 'NHAP' OR user_confirmed_at IS NOT NULL),
  CONSTRAINT ck_evidence_version__clean CHECK (
    status IN ('NHAP','DA_NOP','TU_CHOI') OR scan_status = 'SACH')
) PARTITION BY LIST (period_bucket);

CREATE INDEX ix_evidence_version__sha ON product.evidence_version (tenant_id, content_sha256)
  WHERE content_sha256 IS NOT NULL;
CREATE INDEX ix_evidence_version__task ON product.evidence_version (tenant_id, task_id, status);
CREATE INDEX ix_evidence_version__review_p ON product.evidence_version (tenant_id, submitted_at)
  WHERE status IN ('DA_NOP','DANG_NGHIEM_THU');

CREATE FUNCTION product.fn_freeze_evidence_version() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status <> 'NHAP' AND (
       NEW.object_id         IS DISTINCT FROM OLD.object_id OR
       NEW.object_version_id IS DISTINCT FROM OLD.object_version_id OR
       NEW.content_sha256    IS DISTINCT FROM OLD.content_sha256 OR
       NEW.size_bytes        IS DISTINCT FROM OLD.size_bytes OR
       NEW.version_no        IS DISTINCT FROM OLD.version_no) THEN
    RAISE EXCEPTION 'Phiên bản minh chứng đã nộp không được sửa nội dung; hãy tạo phiên bản mới'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF OLD.status = 'DA_XAC_NHAN' AND NEW.status <> 'DA_XAC_NHAN' THEN
    RAISE EXCEPTION 'Không được huỷ xác nhận phiên bản minh chứng; dùng quy trình hậu kiểm'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tg_evidence_version__freeze BEFORE UPDATE ON product.evidence_version
  FOR EACH ROW EXECUTE FUNCTION product.fn_freeze_evidence_version();
REVOKE DELETE ON product.evidence_version FROM svc_product;
```

### Từ điển dữ liệu

#### Bảng product.deliverable

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `task_id` | `uuid` | Không | Chỉ mục (`tenant_id`, `task_id`) | C1 | Nhiệm vụ (tham chiếu logic sang `task.task`) |
| `name` | `varchar(500)` | Không | | C1 | Tên sản phẩm |
| `deliverable_type` | `varchar(12)` | Không | CHECK IN (`VAN_BAN`, `BAO_CAO`, `SO_LIEU`, `SU_KIEN`, `KHAC`) | C1 | Loại sản phẩm |
| `unit` | `varchar(32)` | Không | | C0 | Đơn vị tính |
| `quantity_planned` | `numeric(18,4)` | Không | | C1 | Số lượng kế hoạch |
| `quantity_accepted` | `numeric(18,4)` | Không | DEFAULT 0 | C1 | Số lượng đã được xác nhận (đầu vào cấu phần A) |
| `status` | `varchar(16)` | Không | CHECK IN (`CHUA_CO`, `DANG_THUC_HIEN`, `DA_XAC_NHAN_MOT_PHAN`, `DA_XAC_NHAN`) | C1 | Trạng thái tổng hợp |

Ước tính 14 triệu dòng mỗi quý.

#### Bảng product.evidence

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `deliverable_id` | `uuid` | Không | FK (`period_bucket`, `deliverable_id`) | C1 | Sản phẩm |
| `task_id` | `uuid` | Không | Chỉ mục | C1 | Nhiệm vụ (dư thừa có chủ đích để truy vấn nhanh) |
| `title` | `varchar(500)` | Không | | C1 | Tên minh chứng |
| `current_version_id` | `uuid` | Có | | C1 | Phiên bản mới nhất |
| `current_status` | `varchar(20)` | Không | | C1 | Trạng thái của phiên bản mới nhất |
| `source_type` | `varchar(12)` | Không | CHECK IN (`TAI_LEN`, `VAN_BAN`, `HE_THONG`) | C1 | Nguồn minh chứng |
| `external_doc_ref` | `varchar(200)` | Có |  | C1 | Văn bản bên ngoài làm minh chứng |

#### Bảng product.evidence_version

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `evidence_id` | `uuid` | Không | FK; UQ (`evidence_id`, `version_no`) | C1 | Minh chứng |
| `task_id` | `uuid` | Không | Chỉ mục | C1 | Nhiệm vụ |
| `version_no` | `int` | Không | Đóng băng | C1 | Số phiên bản |
| `status` | `varchar(20)` | Không | CHECK; trigger | C1 | Trạng thái nghiệm thu |
| `object_id`, `object_version_id` | `uuid` | Có | Đóng băng sau nộp | C1 | Tham chiếu đối tượng ở SVC-08 |
| `content_sha256` | `bytea` | Có | CHECK 32 byte; chỉ mục | C1 | Mã băm tệp |
| `size_bytes` | `bigint` | Có | | C1 | Kích thước |
| `media_type` | `varchar(127)` | Có | | C1 | Kiểu MIME |
| `file_name` | `varchar(255)` | Có | | C1 | Tên tệp gốc |
| `source` | `varchar(20)` | Không | CHECK | C1 | Tải lên, văn bản từ hệ thống, OCR di động, tích hợp |
| `source_doc_ref` | `varchar(200)` | Có | | C1 | Văn bản nguồn khi lấy từ hệ văn bản |
| `ai_prefill` | `boolean` | Không | | C1 | Có trường do AI, OCR điền sẵn |
| `ai_suggestion_id` | `uuid` | Có | | C1 | Gợi ý AI tương ứng |
| `user_confirmed_at` | `timestamptz` | Có | CHECK | C1 | Người dùng xác nhận các trường do AI điền |
| `scan_status` | `varchar(12)` | Không | CHECK | C1 | Kết quả quét mã độc |
| `is_duplicate_suspect` | `boolean` | Không | | C1 | Nghi trùng theo băm |
| `duplicate_of_id` | `uuid` | Có | | C1 | Phiên bản trùng đầu tiên |
| `has_e2ee` | `boolean` | Không | | C1 | Tệp mã hoá đầu cuối (minh chứng của nhiệm vụ mật) |
| `submitted_by`, `submitted_at` | | Có | CHECK khi nộp | C1 | Người và thời điểm nộp (EVT-07) |
| `accepted_at` | `timestamptz` | Có | | C1 | Thời điểm xác nhận (EVT-08) |
| `note` | `varchar(2000)` | Có | | C1 | Ghi chú của người nộp |
| `excluded_period_id` | `uuid` | Có |  | C1 | Kỳ mà phiên bản không được tính (còn `DA_NOP` khi khoá kỳ) (TL-02a) |
| `reuse_justification` | `varchar(1000)` | Có |  | C1 | Giải trình khi dùng lại minh chứng của kỳ trước |
| `attested_at` | `timestamptz` | Có |  | C1 | Thời điểm cam kết tính xác thực |
| `doc_number`, `doc_date` |  | Có |  | C1 | Số, ngày văn bản khi minh chứng là văn bản |
| `doc_summary` | `varchar(2000)` | Có |  | C1 | Trích yếu |

Ước tính 9,6 triệu dòng mỗi quý (tám minh chứng mỗi người theo baseline mục 3.1).

#### Bảng product.acceptance

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `evidence_version_id` | `uuid` | Không | FK (`period_bucket`, `evidence_version_id`) | C1 | Phiên bản được nghiệm thu |
| `task_id` | `uuid` | Không | Chỉ mục | C1 | Nhiệm vụ |
| `reviewer_id` | `uuid` | Không | CHECK khác người nộp (kiểm tra bằng trigger so với `evidence_version.submitted_by`) | C1 | Người nghiệm thu |
| `decision` | `varchar(20)` | Không | CHECK IN (`DA_XAC_NHAN`, `YEU_CAU_BO_SUNG`, `TU_CHOI`) | C1 | Quyết định |
| `quality_level` | `numeric(3,2)` | Có | CHECK IN (1, 0.75, 0.5, 0.25, 0); bắt buộc khi `DA_XAC_NHAN` | C1 | Mức chất lượng năm mức (PL 3 mục 2) |
| `revision_cause` | `varchar(32)` | Có | CHECK IN (`THE_THUC`, `NOI_DUNG_KHONG_COT_LOI`, `NOI_DUNG_CO_BAN`, `SAI_SOT_NGHIEM_TRONG`, `SAI_CHU_TRUONG`) | C1 | Nguyên nhân chỉnh sửa |
| `is_objective_revision` | `boolean` | Không | DEFAULT false | C1 | Chỉnh sửa do nguyên nhân khách quan |
| `revision_count` | `smallint` | Không | DEFAULT 0 | C1 | Số lần chỉnh sửa (phục vụ cách tính trừ 25% mỗi lần nếu Policy Pack chọn, PL A4) |
| `comment` | `varchar(4000)` | Có | | C1 | Ý kiến nghiệm thu về sản phẩm |
| `decided_at` | `timestamptz` | Không | | C1 | Thời điểm |
| `criteria_results` | `jsonb` | Không |  | C1 | Kết quả từng tiêu chí chất lượng (TL-02a) |
| `comment_enc` | `bytea` | Có |  | C2 | Nhận xét nghiệm thu |
| `dek_id` | `uuid` | Có | FK `keyring.dek` | C1 | Khoá dữ liệu |

Ước tính 3,6 triệu dòng mỗi quý. Ý kiến nghiệm thu về sản phẩm được xếp C1 vì là nhận xét về sản phẩm công việc, khác với nhận xét đánh giá cá nhân (C2) ở schema `appraisal`.

#### Bảng product.e2ee_key_wrap (bổ sung)

Cấu trúc giống `task.e2ee_key_wrap`, với `object_type` nhận giá trị `EVIDENCE_FILE`. Khoá nội dung bọc ở đây là khoá đã dùng để mã hoá tệp minh chứng của nhiệm vụ mật tại client trước khi tải lên SVC-08; vì vậy SVC-08 không quét được mã độc trên nội dung (chỉ quét trên thiết bị trước khi mã hoá) và không khử trùng được, hai hạn chế được ghi nhận ở chương 9.

## Schema appraisal (MOD-04)

### Thiết kế

Schema `appraisal` hiện thực nhóm dữ liệu 8 của PL 4 và là nơi tạo ra kết quả có giá trị pháp lý cao nhất của nền tảng. Ba yêu cầu chi phối thiết kế. Yêu cầu thứ nhất là lưu riêng từng mức: tự đề xuất, thẩm định, đề xuất và quyết định (QĐ 16.3, Mẫu 3), bắt buộc giải trình khi các mức khác nhau. Yêu cầu thứ hai là mức tính tự động không thay quyết định (PL 4, slide 29). Yêu cầu thứ ba là tái lập được: kết quả điểm lưu kèm phiên bản quy tắc, mã băm dữ liệu đầu vào và vết tính, tính lại bất kỳ lúc nào ra đúng kết quả cũ (tiêu chí I6, vấn đề I03).

Thiết kế đáp ứng bằng cách tách ba khái niệm thường bị trộn lẫn. `appraisal.appraisal_profile` là hồ sơ đánh giá của một người, một kỳ, một luồng, mang trạng thái của luồng duyệt. `appraisal.score_snapshot` là ảnh chụp bất biến của điểm do SVC-07 tính, kèm `input_hash`, `policy_content_hash` và `snapshot_hash`; mỗi lần tính lại (tạm tính trong kỳ, chốt kỳ, tính lại khi hậu kiểm) tạo một ảnh chụp mới, ảnh chụp cũ được đánh dấu bị thay thế nhưng không bị xoá. `appraisal.rating_proposal` là các mức xếp loại do con người đưa ra ở bốn bước, mỗi dòng gắn với ảnh chụp điểm mà người đó đã nhìn thấy khi quyết định. Nhờ vậy, khi có khiếu nại, cơ quan kiểm tra biết chính xác người quyết định đã căn cứ vào con số nào, theo phiên bản quy tắc nào, và con số đó có còn đúng không.

Hồ sơ còn lưu "thời điểm hiểu biết tổ chức" tại lúc quyết định (`org_asof_recorded_at`). Khi xem lại hồ sơ, giao diện truy vấn SVC-04 theo hai trục thời gian với mốc này để hiển thị đúng chức vụ, đơn vị, cấp trên mà người quyết định đã thấy, kể cả khi sau đó có quyết định đính chính tổ chức (chương 7).

Hai luồng Đảng và Nhà nước được biểu diễn bằng hai hồ sơ với `flow` khác nhau, dùng chung dữ liệu nhiệm vụ và minh chứng. Xếp loại năm (`appraisal.annual_rating`) liên thông với bốn quý: một quý KHTNV thì không được xếp HTXSNV năm, hai quý liên tiếp KHTNV thì đề xuất bố trí lại (QĐ 15.5 đến 15.6); các cờ này được lưu thành dữ liệu để giải thích được. Phân nhóm triển vọng (`appraisal.prospect_group`) chỉ có ở kỳ năm (QĐ 12.1.5), ràng buộc bằng `CHECK` trên `period_bucket % 10 = 0`.

![Hình 6.10. ERD schema appraisal](db-erd-appraisal.png){width=16cm}

Hình 6.10 cho thấy hồ sơ là gốc của mọi bảng khác; ảnh chụp điểm và các mức đề xuất có quan hệ nhiều nhiều theo thời gian (nhiều ảnh chụp, nhiều mức đề xuất, mỗi mức chỉ trỏ tới một ảnh chụp); lời giải trình gắn với mức đề xuất.

### Bảo vệ dữ liệu kỳ đã khoá ở tầng CSDL

Kiểm tra trạng thái kỳ ở ứng dụng (bộ đệm trong pod) là lớp thứ nhất. Lớp thứ hai nằm ở CSDL để chặn mọi đường ghi vòng qua ứng dụng. Mỗi schema có dữ liệu thực hiện theo kỳ (`okr`, `task`, `product`) có bảng bổ sung `period_state`, là bản sao cục bộ rất nhỏ của trạng thái kỳ, được consumer của module cập nhật từ EVT-09, EVT-22, EVT-26 và từ sự kiện bắt đầu khoá của saga. Trigger trên các bảng thực hiện tra bảng này trước mỗi lần ghi. Với `appraisal`, đơn vị khoá là hồ sơ: khi hồ sơ ở trạng thái `DA_KHOA`, mọi bảng con của hồ sơ bị chặn ghi.

```sql
CREATE TABLE task.period_state (
  tenant_id       uuid        NOT NULL,
  period_id       uuid        NOT NULL,
  status          varchar(16) NOT NULL,
  source_event_id uuid        NOT NULL,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_period_state PRIMARY KEY (tenant_id, period_id)
);

CREATE FUNCTION task.fn_guard_period_write() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_status varchar(16);
BEGIN
  SELECT ps.status INTO v_status
    FROM task.period_state ps
   WHERE ps.tenant_id = NEW.tenant_id AND ps.period_id = NEW.period_id;
  IF v_status IN ('DANG_KHOA','DA_KHOA') THEN
    RAISE EXCEPTION 'Kỳ % đang khoá hoặc đã khoá, không được ghi', NEW.period_id
      USING ERRCODE = 'object_not_in_prerequisite_state';
  END IF;
  IF v_status = 'MO_LAI_HAU_KIEM'
     AND current_setting('app.reopen_request_id', true) IS NULL THEN
    RAISE EXCEPTION 'Kỳ đang mở lại hậu kiểm: chỉ ghi kèm mã yêu cầu mở lại đã được duyệt'
      USING ERRCODE = 'object_not_in_prerequisite_state';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER tg_task__period_guard BEFORE INSERT OR UPDATE ON task.task
  FOR EACH ROW EXECUTE FUNCTION task.fn_guard_period_write();
CREATE TRIGGER tg_task_progress__period_guard BEFORE INSERT ON task.task_progress
  FOR EACH ROW EXECUTE FUNCTION task.fn_guard_period_write();
```

Chi phí của trigger là một phép tra khoá chính trên bảng chỉ vài chục dòng mỗi tenant, nằm sẵn trong bộ nhớ đệm của PostgreSQL, ước tính dưới 20 micro giây mỗi dòng ghi. Khi kỳ được mở lại hậu kiểm, ứng dụng phải đặt `SET LOCAL app.reopen_request_id` bằng mã yêu cầu đã đủ hai phê duyệt; mã này cũng được ghi vào `before_after` của `task.task_state_log` và vào sổ cái, nên mọi thay đổi trong thời gian mở lại đều truy được về quyết định mở lại.

### DDL mẫu: appraisal.score_snapshot

```sql
CREATE TABLE appraisal.score_snapshot (
  id                      uuid          NOT NULL,
  tenant_id               uuid          NOT NULL,
  period_id               uuid          NOT NULL,
  period_bucket           int           NOT NULL,
  profile_id              uuid          NOT NULL,
  flow                    varchar(12)   NOT NULL,
  snapshot_kind           varchar(20)   NOT NULL,
  score_result_id         uuid          NOT NULL,      -- scoring.score_result.id (SVC-07)
  policy_pack_version_id  uuid          NOT NULL,
  policy_content_hash     bytea         NOT NULL,
  policy_version          varchar(32)   NOT NULL,      -- nhãn phiên bản đọc được, ví dụ 'KHOI_DANG v2.0'
  input_hash              bytea         NOT NULL,      -- SHA-256 của tập dữ liệu đầu vào chuẩn hoá
  total_score             numeric(6,3)  NOT NULL,
  regular_score           numeric(6,3),                -- điểm nhóm thường xuyên
  key_task_score          numeric(6,3),                -- điểm nhóm then chốt
  general_criteria_score  numeric(6,3),                -- luồng NHA_NUOC: phần 30 điểm
  kpi_score               numeric(6,3),                -- luồng NHA_NUOC: phần 70 điểm
  exceed_ratio            numeric(6,4),                -- tỷ lệ nhiệm vụ vượt mục tiêu
  objective_ratio         numeric(6,4),                -- tỷ lệ hoàn thành mục tiêu O
  general_criteria_level  varchar(20),
  auto_rating_code        varchar(8)    NOT NULL,      -- mức tính tự động, chỉ để tham khảo
  rule_checks             jsonb         NOT NULL,      -- từng điều kiện đạt/chưa đạt của mức cao nhất
  downgrade_reasons       jsonb         NOT NULL DEFAULT '[]'::jsonb,
  group_scores_json       jsonb         NOT NULL,      -- điểm theo nhóm và theo nhiệm vụ
  detail_json             jsonb,                       -- chi tiết tính để hiển thị SCR-0903
  computed_at             timestamptz   NOT NULL,
  snapshot_hash           bytea         NOT NULL,      -- SHA-256 dạng chuẩn hoá của toàn bộ dòng
  superseded_by_id        uuid,
  created_at              timestamptz   NOT NULL DEFAULT now(),
  created_by              uuid          NOT NULL,
  updated_at              timestamptz   NOT NULL DEFAULT now(),
  updated_by              uuid          NOT NULL,
  row_version             int           NOT NULL DEFAULT 0,
  CONSTRAINT pk_score_snapshot PRIMARY KEY (period_bucket, id),
  CONSTRAINT fk_score_snapshot__profile FOREIGN KEY (period_bucket, profile_id)
    REFERENCES appraisal.appraisal_profile (period_bucket, id),
  CONSTRAINT uq_score_snapshot__result UNIQUE (period_bucket, score_result_id),
  CONSTRAINT ck_score_snapshot__kind CHECK (snapshot_kind IN ('TAM_TINH','CHOT_KY','TINH_LAI_HAU_KIEM')),
  CONSTRAINT ck_score_snapshot__flow CHECK (flow IN ('DANG','NHA_NUOC')),
  CONSTRAINT ck_score_snapshot__score CHECK (total_score BETWEEN 0 AND 100),
  CONSTRAINT ck_score_snapshot__rating CHECK (auto_rating_code IN ('HTXSNV','HTTNV','HTNV','KHTNV')),
  CONSTRAINT ck_score_snapshot__hashes CHECK (length(input_hash) = 32 AND length(snapshot_hash) = 32
                                              AND length(policy_content_hash) = 32),
  CONSTRAINT ck_score_snapshot__nn CHECK (flow <> 'NHA_NUOC'
                                          OR (general_criteria_score IS NOT NULL AND kpi_score IS NOT NULL))
) PARTITION BY LIST (period_bucket);

CREATE UNIQUE INDEX uq_score_snapshot__current_p ON appraisal.score_snapshot
  (period_bucket, profile_id) WHERE superseded_by_id IS NULL;
CREATE INDEX ix_score_snapshot__tenant_period ON appraisal.score_snapshot (tenant_id, period_id, flow);

-- Bất biến: chỉ được điền superseded_by_id một lần
REVOKE UPDATE, DELETE ON appraisal.score_snapshot FROM svc_appraisal;
GRANT UPDATE (superseded_by_id, updated_at, updated_by, row_version)
  ON appraisal.score_snapshot TO svc_appraisal;
```

Chỉ mục duy nhất một phần `uq_score_snapshot__current_p` bảo đảm mỗi hồ sơ chỉ có một ảnh chụp hiện hành. Thay ảnh chụp là giao dịch hai bước: chèn ảnh chụp mới với `superseded_by_id` NULL sau khi đã đặt `superseded_by_id` của ảnh chụp cũ trỏ tới mã mới (mã UUIDv7 sinh trước ở ứng dụng).

### DDL mẫu: appraisal.rating_proposal

```sql
CREATE TABLE appraisal.rating_proposal (
  id                    uuid          NOT NULL,
  tenant_id             uuid          NOT NULL,
  period_id             uuid          NOT NULL,
  period_bucket         int           NOT NULL,
  profile_id            uuid          NOT NULL,
  level_kind                 varchar(12)   NOT NULL,
  rating_code           varchar(8)    NOT NULL,
  score_snapshot_id     uuid          NOT NULL,
  actor_id              uuid          NOT NULL,
  actor_role            varchar(24)   NOT NULL,
  is_current            boolean       NOT NULL DEFAULT true,
  differs_from_prev     boolean       NOT NULL DEFAULT false,
  explanation_required  boolean       NOT NULL DEFAULT false,
  is_bulk_decision      boolean       NOT NULL DEFAULT false,
  batch_decision_id     uuid,                        -- appraisal.batch_decision khi quyết định hàng loạt
  reason_enc            bytea,                       -- lý do đưa ra mức (C2)
  comment_enc           bytea,                       -- nhận xét kèm theo (C2)
  dek_id                uuid,
  sign_request_id       uuid,
  signed_at             timestamptz,
  proposed_at           timestamptz   NOT NULL,
  created_at            timestamptz   NOT NULL DEFAULT now(),
  created_by            uuid          NOT NULL,
  updated_at            timestamptz   NOT NULL DEFAULT now(),
  updated_by            uuid          NOT NULL,
  row_version           int           NOT NULL DEFAULT 0,
  CONSTRAINT pk_rating_proposal PRIMARY KEY (period_bucket, id),
  CONSTRAINT fk_rating_proposal__profile FOREIGN KEY (period_bucket, profile_id)
    REFERENCES appraisal.appraisal_profile (period_bucket, id),
  CONSTRAINT fk_rating_proposal__snapshot FOREIGN KEY (period_bucket, score_snapshot_id)
    REFERENCES appraisal.score_snapshot (period_bucket, id),
  CONSTRAINT ck_rating_proposal__level_kind CHECK (level_kind IN ('TU_DE_XUAT','THAM_DINH','DE_XUAT','QUYET_DINH')),
  CONSTRAINT ck_rating_proposal__rating CHECK (rating_code IN ('HTXSNV','HTTNV','HTNV','KHTNV')),
  CONSTRAINT ck_rating_proposal__role CHECK (
    (level_kind = 'TU_DE_XUAT' AND actor_role = 'staff') OR
    (level_kind = 'THAM_DINH'  AND actor_role = 'appraiser') OR
    (level_kind = 'DE_XUAT'    AND actor_role IN ('manager','appraiser')) OR
    (level_kind = 'QUYET_DINH' AND actor_role = 'decider')),
  CONSTRAINT ck_rating_proposal__actor_is_human CHECK (actor_id = created_by),
  CONSTRAINT ck_rating_proposal__signed CHECK (level_kind NOT IN ('TU_DE_XUAT','THAM_DINH','QUYET_DINH')
                                               OR signed_at IS NULL OR sign_request_id IS NOT NULL)
) PARTITION BY LIST (period_bucket);

CREATE UNIQUE INDEX uq_rating_proposal__current_p ON appraisal.rating_proposal
  (period_bucket, profile_id, level_kind) WHERE is_current;
CREATE INDEX ix_rating_proposal__decision ON appraisal.rating_proposal
  (tenant_id, period_id, level_kind, rating_code) WHERE is_current;

REVOKE DELETE ON appraisal.rating_proposal FROM svc_appraisal;
```

Ràng buộc `ck_rating_proposal__actor_is_human` yêu cầu người tạo dòng chính là người đưa ra mức; ứng dụng ghi `created_by` bằng `sub` của JWT người dùng, còn tài khoản dịch vụ có `sub` thuộc dải mã riêng bị OPA từ chối với thao tác này. Kết hợp với `ck_rating_proposal__role`, mức quyết định chỉ có thể do một người có vai trò `decider` tạo, đúng yêu cầu "AI không quyết định mức xếp loại, mức tính tự động chỉ để tham khảo". Việc sửa một mức đã đưa ra luôn tạo dòng mới và đặt `is_current = false` cho dòng cũ; dòng cũ vẫn còn để truy vết. Mức ở bước sau khác mức ở bước trước thì `explanation_required = true` và ứng dụng không cho chuyển bước khi chưa có dòng `appraisal.rating_explanation` tương ứng.

### Từ điển dữ liệu

#### Bảng appraisal.appraisal_profile

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `person_id` | `uuid` | Không | UQ (`period_bucket`, `tenant_id`, `period_id`, `person_id`, `flow`) | C1 | Người được đánh giá |
| `flow` | `varchar(12)` | Không | CHECK IN (`DANG`, `NHA_NUOC`) | C1 | Luồng |
| `org_unit_id` | `uuid` | Không | Chỉ mục (`tenant_id`, `org_unit_id`, `period_id`) | C1 | Đơn vị tại thời điểm lập hồ sơ |
| `assignment_id` | `uuid` | Không | | C1 | Phân công chính làm căn cứ |
| `job_title_id`, `subject_group_id` | `uuid` | Không/Có | | C1 | Chức danh, khối đối tượng |
| `is_head`, `is_leadership` | `boolean` | Không | | C1 | Người đứng đầu, lãnh đạo quản lý |
| `policy_pack_version_id` | `uuid` | Không | | C1 | Phiên bản quy tắc gắn khi lập hồ sơ, đóng băng khi kỳ khoá |
| `status` | `varchar(16)` | Không | CHECK theo baseline 9.1 | C1 | `CHUA_LAP` đến `DA_KHOA`, nhánh `KHIEU_NAI` |
| `current_snapshot_id` | `uuid` | Có | | C1 | Ảnh chụp điểm hiện hành |
| `final_rating_code` | `varchar(8)` | Có | | C1 | Mức quyết định cuối cùng |
| `decided_at` | `timestamptz` | Có | | C1 | Thời điểm quyết định |
| `org_asof_recorded_at` | `timestamptz` | Có | | C1 | Mốc trục ghi nhận của dữ liệu tổ chức tại lúc quyết định |
| `org_asof_valid_date` | `date` | Có | | C1 | Mốc trục hiệu lực (thường là ngày cuối kỳ) |
| `appraiser_id`, `decider_id` | `uuid` | Có | CHECK `decider_id <> appraiser_id` và khác `person_id` | C1 | Người thẩm định, người quyết định (tách vai trò PL 4 V.1) |
| `wf_process_instance_id` | `varchar(64)` | Có | | C1 | Tiến trình duyệt trong Flowable |
| `locked_at` | `timestamptz` | Có | | C1 | Thời điểm khoá hồ sơ |
| `submitted_at` | `timestamptz` | Có |  | C1 | Thời điểm nộp tự đánh giá |

Ước tính 1,2 triệu đến 2,4 triệu dòng mỗi quý ở CELL-HCM.

#### Bảng appraisal.general_criteria_result

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `profile_id` | `uuid` | Không | FK; UQ (`period_bucket`, `profile_id`, `stage`, `criteria_code`) | C1 | Hồ sơ |
| `stage` | `varchar(12)` | Không | CHECK IN (`TU_DANH_GIA`, `THAM_DINH`) | C1 | Bước |
| `criteria_code` | `varchar(16)` | Không | | C0 | Mã nhóm tiêu chí chung (9 nhóm theo dự thảo, cấu hình trong Policy Pack) |
| `compliance_ratio` | `numeric(6,4)` | Không | CHECK 0 đến 1 | C1 | Tỷ lệ đáp ứng |
| `level` | `varchar(20)` | Không | CHECK IN (`THUC_HIEN_TOT`, `DAM_BAO`, `CO_BAN_DAM_BAO`, `KHONG_DAM_BAO`) | C1 | Mức (nhãn hiển thị tuỳ biểu mẫu, PL A4) |
| `comment_enc` | `bytea` | Có | | C2 | Nhận xét |
| `dek_id` | `uuid` | Có | FK `keyring.dek` | C1 | Khoá dữ liệu |
| `met` | `boolean` | Không |  | C1 | Đạt hay không đạt nhóm tiêu chí |

#### Bảng appraisal.self_assessment

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `profile_id` | `uuid` | Không | FK; UQ (`period_bucket`, `profile_id`, `version_no`) | C1 | Hồ sơ |
| `version_no` | `int` | Không | | C1 | Lần nộp (nộp lại sau trả lại tạo phiên bản mới) |
| `form_code`, `form_version` | `varchar` | Không | | C1 | Biểu mẫu động đã dùng (`config.form_schema`) |
| `structured_data` | `jsonb` | Không | | C1 | Số liệu có cấu trúc (không chứa văn bản tự do) |
| `strengths_enc` | `bytea` | Có | | C2 | Ưu điểm tự nhận |
| `weaknesses_enc` | `bytea` | Có | | C2 | Hạn chế, khuyết điểm |
| `remedial_plan_enc` | `bytea` | Có | | C2 | Hướng khắc phục |
| `dek_id` | `uuid` | Có | | C1 | Khoá dữ liệu |
| `content_hash` | `bytea` | Có | | C1 | Băm dạng chuẩn hoá của phiếu, là nội dung được ký số ở mốc "tự đánh giá" |
| `submitted_at` | `timestamptz` | Có | | C1 | Thời điểm nộp |
| `sign_request_id`, `signed_at` | | Có | | C1 | Ký số |
| `content_enc` | `bytea` | Có |  | C2 | Nội dung tự đánh giá tổng hợp |
| `breakthrough_enc` | `bytea` | Có |  | C2 | Đột phá, sáng tạo |
| `claims_json` | `jsonb` | Có |  | C1 | Các mức tự nhận theo tiêu chí, không chứa văn bản tự do |

#### Bảng appraisal.score_snapshot

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`); bất biến trừ `superseded_by_id` | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `profile_id` | `uuid` | Không | FK; UQ một phần ảnh chụp hiện hành | C1 | Hồ sơ |
| `flow` | `varchar(12)` | Không | CHECK | C1 | Luồng |
| `snapshot_kind` | `varchar(20)` | Không | CHECK IN (`TAM_TINH`, `CHOT_KY`, `TINH_LAI_HAU_KIEM`) | C1 | Loại ảnh chụp |
| `score_result_id` | `uuid` | Không | UQ | C1 | Kết quả tính ở SVC-07 |
| `policy_pack_version_id`, `policy_content_hash` | | Không | | C1 | Phiên bản quy tắc và băm nội dung |
| `input_hash` | `bytea` | Không | CHECK 32 byte | C1 | Băm dữ liệu đầu vào |
| `total_score` | `numeric(6,3)` | Không | CHECK 0 đến 100 | C1 | Tổng điểm |
| `regular_score`, `key_task_score` | `numeric(6,3)` | Có | | C1 | Điểm theo nhóm |
| `general_criteria_score`, `kpi_score` | `numeric(6,3)` | Có | CHECK khi `NHA_NUOC` | C1 | Thang 30/70 |
| `exceed_ratio`, `objective_ratio` | `numeric(6,4)` | Có | | C1 | Điều kiện HTXSNV |
| `general_criteria_level` | `varchar(20)` | Có | | C1 | Mức tiêu chí chung |
| `auto_rating_code` | `varchar(8)` | Không | CHECK | C1 | Mức tính tự động (tham khảo) |
| `rule_checks` | `jsonb` | Không | | C1 | Danh sách điều kiện của mức cao nhất có thể, đạt hay chưa (SCR-0903) |
| `downgrade_reasons` | `jsonb` | Không | | C1 | Lý do hạ mức kèm điều khoản và phiên bản quy tắc |
| `computed_at` | `timestamptz` | Không | | C1 | Thời điểm tính |
| `snapshot_hash` | `bytea` | Không | | C1 | Băm toàn bộ ảnh chụp, được ghi vào sổ cái |
| `superseded_by_id` | `uuid` | Có | | C1 | Ảnh chụp thay thế |
| `policy_version` | `varchar(32)` | Không |  | C1 | Nhãn phiên bản quy tắc đọc được (TL-02b) |
| `group_scores_json` | `jsonb` | Không |  | C1 | Điểm theo nhóm và từng nhiệm vụ |
| `detail_json` | `jsonb` | Có |  | C1 | Chi tiết tính; `exceed_ratio` là `over_target_ratio` của TL-02b |

Điểm số và mức xếp loại được xếp C1 theo lập trường G3 của baseline: chúng không mã hoá trường để còn tính toán, phân tích, mà được bảo vệ bằng RLS, ABAC, sổ cái và giả danh hoá người khi sang ClickHouse. Ước tính 5 triệu dòng mỗi quý (trung bình hai lần tạm tính cộng một lần chốt cho mỗi hồ sơ).

#### Bảng appraisal.rating_proposal

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `profile_id` | `uuid` | Không | FK; UQ một phần (`profile_id`, `level_kind`) khi `is_current` | C1 | Hồ sơ |
| `level_kind` | `varchar(12)` | Không | CHECK IN (`TU_DE_XUAT`, `THAM_DINH`, `DE_XUAT`, `QUYET_DINH`) | C1 | Loại mức (tên cột thống nhất với TL-02b) |
| `rating_code` | `varchar(8)` | Không | CHECK | C1 | Mức xếp loại (TL-02b gọi là `level`) |
| `score_snapshot_id` | `uuid` | Không | FK | C1 | Ảnh chụp điểm làm căn cứ |
| `actor_id`, `actor_role` | | Không | CHECK vai trò theo bước | C1 | Người và vai trò |
| `is_current` | `boolean` | Không | | C1 | Dòng hiện hành của bước |
| `differs_from_prev`, `explanation_required` | `boolean` | Không | | C1 | Khác bước trước, bắt buộc giải trình |
| `is_bulk_decision` | `boolean` | Không | | C1 | Quyết định hàng loạt (SCR-0904) |
| `sign_request_id`, `signed_at` | | Có | | C1 | Ký số tại mốc pháp lý |
| `proposed_at` | `timestamptz` | Không | | C1 | Thời điểm đưa ra mức |
| `batch_decision_id` | `uuid` | Có | FK `appraisal.batch_decision` | C1 | Quyết định hàng loạt chứa dòng này |
| `reason_enc` | `bytea` | Có |  | C2 | Lý do đưa ra mức |
| `comment_enc` | `bytea` | Có |  | C2 | Nhận xét |
| `dek_id` | `uuid` | Có | FK `keyring.dek` | C1 | Khoá dữ liệu |

Ước tính 9,6 triệu dòng mỗi quý.

#### Bảng appraisal.rating_explanation

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `rating_proposal_id` | `uuid` | Không | FK | C1 | Mức được giải trình |
| `profile_id` | `uuid` | Không | Chỉ mục | C1 | Hồ sơ |
| `explanation_type` | `varchar(16)` | Không | CHECK IN (`HA_MUC`, `KHAC_MUC_TRUOC`, `BAO_LUU`) | C1 | Loại giải trình |
| `auto_text` | `varchar(2000)` | Có | | C1 | Câu giải thích do bộ máy quy tắc sinh ("Đủ điểm mức xuất sắc nhưng chưa đạt điều kiện 30% nhiệm vụ vượt mục tiêu...") |
| `legal_ref` | `varchar(200)` | Có | | C0 | Điều khoản, ví dụ "QĐ Điều 11; PL 6 §12" |
| `explanation_enc` | `bytea` | Có | | C2 | Lời giải trình của người đưa ra mức |
| `dek_id` | `uuid` | Có | | C1 | Khoá dữ liệu |
| `system_level` | `varchar(8)` | Có |  | C1 | Mức hệ thống tính |
| `conditions_json` | `jsonb` | Có |  | C1 | Điều kiện đạt hoặc chưa đạt |

#### Bảng appraisal.annual_rating

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`); CHECK `period_bucket % 10 = 0` | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng năm | C1 | Kỳ năm |
| `person_id` | `uuid` | Không | UQ (`period_bucket`, `tenant_id`, `person_id`, `flow`) | C1 | Người |
| `flow` | `varchar(12)` | Không | | C1 | Luồng |
| `profile_id` | `uuid` | Không | FK hồ sơ năm | C1 | Hồ sơ năm |
| `quarter_ratings` | `varchar(8)[]` | Không | CHECK độ dài ≤ 4 | C1 | Mức của các quý trong năm theo thứ tự |
| `has_khtnv_quarter` | `boolean` | Không | | C1 | Có quý KHTNV (chặn HTXSNV năm) |
| `consecutive_khtnv` | `boolean` | Không | | C1 | Hai quý liên tiếp KHTNV (đề xuất bố trí lại) |
| `annual_score` | `numeric(6,3)` | Có | | C1 | Điểm năm |
| `rating_code` | `varchar(8)` | Có | | C1 | Mức năm đã quyết định |
| `relocation_proposed` | `boolean` | Không | DEFAULT false | C1 | Đã đề xuất bố trí lại |
| `system_level` | `varchar(8)` | Có |  | C1 | Mức năm hệ thống tính (tham khảo) |
| `block_reason` | `varchar(32)` | Có |  | C1 | Lý do chặn mức cao (ví dụ `CO_QUY_KHTNV`) (TL-02b) |
| `reassign_flag` | `boolean` | Không | DEFAULT false | C1 | Cờ đề xuất bố trí lại (QĐ 15.6), TL-02b; trùng nghĩa `relocation_proposed` |

#### Bảng appraisal.prospect_group

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`); CHECK `period_bucket % 10 = 0` | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng năm | C1 | Kỳ năm |
| `person_id` | `uuid` | Không | UQ (`period_bucket`, `tenant_id`, `person_id`) | C1 | Người |
| `group_code` | `varchar(8)` | Không | CHECK IN (`NHOM_1`, `NHOM_2`, `NHOM_3`, `NHOM_4`) | C2 dạng rõ có kiểm soát | Nhóm triển vọng (PL 5); không đưa sang ClickHouse ở dạng gắn người |
| `rank_in_group` | `int` | Có | | C1 | Thứ tự trong nhóm |
| `training_plan_enc` | `bytea` | Có | | C2 | Kế hoạch đào tạo, bồi dưỡng |
| `dek_id` | `uuid` | Có | | C1 | Khoá dữ liệu |
| `decided_by`, `decided_at` | | Có | | C1 | Quyết định |
| `suggested_group` | `varchar(8)` | Có |  | C2 dạng rõ có kiểm soát | Nhóm do hệ thống gợi ý |
| `reason_enc` | `bytea` | Có |  | C2 | Lý do xếp nhóm |
| `training_plan_ref` | `varchar(200)` | Có |  | C1 | Tham chiếu kế hoạch đào tạo |

Cột `group_code` là trường hợp đặc biệt được ghi rõ: nội dung là thông tin quy hoạch cán bộ nhạy cảm (C2) nhưng cần lọc, đếm theo nhóm, nên không mã hoá trường mà được bảo vệ bằng quyền cột (chỉ vai trò `svc_appraisal` có `SELECT` trên cột, vai trò `ro_appraisal` không có), ABAC chỉ cho cơ quan tổ chức cán bộ, và connector CDC phân tích loại trừ cột này. Chú thích cột ghi `{"cls":"C2","enc":false,"justification":"..."}` để bài kiểm tra trong CI chấp nhận ngoại lệ có lý do.

#### Bảng appraisal.batch_decision (bổ sung)

Bảng `appraisal.batch_decision` (đề xuất của TL-02b, SCR-0904) lưu quyết định xếp loại hàng loạt: một văn bản quyết định của người có thẩm quyền áp dụng cho nhiều hồ sơ, ký lô tới 1.000 phiếu mỗi phiên (ADR-31 của TL-01). Mỗi hồ sơ trong lô vẫn có dòng `rating_proposal` mức `QUYET_DINH` riêng, trỏ về lô qua `batch_decision_id`.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `decision_no` | `varchar(64)` | Không | UQ (`period_bucket`, `tenant_id`, `decision_no`) | C1 | Số quyết định |
| `decision_date` | `date` | Không | | C1 | Ngày quyết định |
| `decider_id` | `uuid` | Không | | C1 | Người ký |
| `flow` | `varchar(12)` | Không | | C1 | Luồng |
| `profile_count` | `int` | Không | CHECK 1 đến 1000 | C1 | Số hồ sơ |
| `profiles_hash` | `bytea` | Không | | C1 | Băm danh sách (mã hồ sơ, mức) được ký |
| `sign_request_id` | `uuid` | Có | | C1 | Yêu cầu ký lô |
| `status` | `varchar(12)` | Không | CHECK IN (`NHAP`, `CHO_KY`, `DA_KY`, `LOI_MOT_PHAN`, `HUY`) | C1 | Trạng thái ký lô |

## Schema dossier (MOD-06)

### Thiết kế

Schema `dossier` quản lý kiểm điểm và hồ sơ số (nhóm tính năng 5). Hội nghị kiểm điểm (`dossier.review_meeting`) phải gửi dự thảo ít nhất ba ngày làm việc trước hội nghị (QĐ Điều 6 đến 7); thời điểm gửi được lưu để kiểm tra bằng lịch ngày nghỉ. Góp ý (`dossier.review_comment`) là dữ liệu C2. Gói hồ sơ (`dossier.dossier_package`) gom các biểu mẫu đã sinh (`dossier.form_instance`, Mẫu 1A đến 1D, 2, 3 và bảng chấm điểm) kèm danh mục mã băm, được ký số và nộp theo hạn; hồ sơ diện Trung ương quản lý được chuyển qua Ban Tổ chức Trung ương hoặc Văn phòng Trung ương (QĐ Điều 17). Bảng `dossier.special_case` lưu 13 trường hợp đặc thù của PL 6 (điều động dưới hai tháng, nghỉ thai sản, đang bị xác minh, đơn vị mới sáp nhập và các trường hợp khác); MOD-04 đọc các trường hợp này qua interface công bố của MOD-06 trong cùng tiến trình khi lập hồ sơ.

Tệp PDF/A của biểu mẫu và gói hồ sơ nằm ở Ceph do SVC-11 sinh; `dossier.form_instance` lưu `data_snapshot_hash` (băm dữ liệu đã dùng để sinh) và `pdf_sha256` (băm tệp), cho phép chứng minh tệp được sinh từ đúng dữ liệu nào.

![Hình 6.11. ERD schema dossier](db-erd-dossier.png){width=14cm}

Hình 6.11 cho thấy một gói hồ sơ chứa nhiều biểu mẫu, mỗi biểu mẫu sinh từ một hồ sơ đánh giá; hội nghị kiểm điểm có nhiều góp ý gắn với từng người được kiểm điểm.

### Từ điển dữ liệu

#### Bảng dossier.review_meeting

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `org_unit_id` | `uuid` | Không | | C1 | Tập thể tổ chức hội nghị |
| `meeting_type` | `varchar(16)` | Không | CHECK IN (`KIEM_DIEM_QUY`, `KIEM_DIEM_NAM`, `CHUYEN_DE`) | C1 | Loại |
| `scheduled_at` | `timestamptz` | Không | | C1 | Thời điểm họp |
| `draft_sent_at` | `timestamptz` | Có | | C1 | Thời điểm gửi dự thảo (kiểm tra ≥ 3 ngày làm việc) |
| `chair_person_id`, `secretary_person_id` | `uuid` | Không | | C1 | Chủ trì, thư ký |
| `status` | `varchar(12)` | Không | CHECK IN (`DU_KIEN`, `DA_GUI_DU_THAO`, `DA_HOP`, `DA_LAP_BIEN_BAN`, `DA_KY`) | C1 | Trạng thái |
| `minutes_object_key` | `varchar(512)` | Có | | C1 | Biên bản trên Ceph |
| `minutes_sha256` | `bytea` | Có | | C1 | Băm biên bản |
| `sign_request_id` | `uuid` | Có | | C1 | Ký biên bản |
| `meeting_at` | `timestamptz` | Có |  | C1 | Thời điểm họp thực tế |
| `subject_person_id` | `uuid` | Có |  | C1 | Người được kiểm điểm khi hội nghị dành cho một người |
| `participants_json` | `jsonb` | Có |  | C1 | Thành phần dự họp |
| `vote_json` | `jsonb` | Có |  | C1 | Kết quả biểu quyết (TL-02b) |

#### Bảng dossier.review_comment

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `meeting_id` | `uuid` | Không | FK | C1 | Hội nghị |
| `subject_person_id` | `uuid` | Không | Chỉ mục | C1 | Người được góp ý |
| `commenter_person_id` | `uuid` | Không | | C1 | Người góp ý |
| `comment_kind` | `varchar(12)` | Không | CHECK IN (`GOP_Y`, `TU_KIEM_DIEM`, `KET_LUAN`) | C1 | Loại |
| `comment_enc` | `bytea` | Không | | C2 | Nội dung góp ý |
| `dek_id` | `uuid` | Không | | C1 | Khoá dữ liệu |
| `remediation_due` | `date` | Có | | C1 | Hạn khắc phục hạn chế (theo dõi kỳ sau) |
| `kind` | `varchar(12)` | Không | CHECK IN (`UU_DIEM`, `HAN_CHE`, `KIEN_NGHI`) | C1 | Loại nội dung (TL-02b) |
| `phase` | `varchar(16)` | Không | CHECK IN (`TRUOC_HOI_NGHI`, `HOI_NGHI`) | C1 | Giai đoạn |

#### Bảng dossier.dossier_package

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `person_id` | `uuid` | Không | UQ (`period_bucket`, `tenant_id`, `person_id`, `package_kind`) | C1 | Chủ hồ sơ |
| `package_kind` | `varchar(8)` | Không | CHECK IN (`QUY`, `NAM`, `DIEN_TW`) | C1 | Loại gói |
| `manifest` | `jsonb` | Không | | C1 | Danh sách biểu mẫu, tệp kèm và băm từng tệp |
| `package_sha256` | `bytea` | Có | | C1 | Băm gói |
| `object_key` | `varchar(512)` | Có | | C1 | Gói trên Ceph |
| `status` | `varchar(16)` | Không | CHECK IN (`DANG_LAP`, `DANG_SINH`, `CHO_KY`, `DA_KY`, `DA_NOP`, `DA_LUU_TRU`) | C1 | Trạng thái (bộ trạng thái của TL-02b; chuyển hồ sơ diện TW ghi ở `transferred_to`) |
| `deadline` | `date` | Có | | C1 | Hạn nộp |
| `submitted_at` | `timestamptz` | Có | | C1 | Thời điểm nộp |
| `transferred_to` | `varchar(8)` | Có | CHECK IN (`BTC_TW`, `VPTW`) | C1 | Nơi chuyển hồ sơ diện TW |
| `sign_request_id` | `uuid` | Có | | C1 | Ký gói |
| `code` | `varchar(32)` | Không | UQ | C1 | Mã gói |
| `manifest_hash` | `bytea` | Có |  | C1 | Băm danh mục |

#### Bảng dossier.form_instance

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `dossier_package_id` | `uuid` | Có | FK | C1 | Gói hồ sơ |
| `profile_id` | `uuid` | Không | Chỉ mục | C1 | Hồ sơ đánh giá |
| `form_code` | `varchar(16)` | Không | CHECK IN (`MAU_1A`, `MAU_1B`, `MAU_1C`, `MAU_1D`, `MAU_2`, `MAU_3`, `BANG_CHAM_DIEM`) | C0 | Mẫu |
| `template_version` | `varchar(32)` | Không | | C1 | Phiên bản mẫu ở `docgen.template` |
| `data_snapshot_hash` | `bytea` | Không | | C1 | Băm dữ liệu đầu vào khi sinh |
| `render_job_id` | `uuid` | Có | | C1 | Tác vụ sinh ở SVC-11 |
| `object_key` | `varchar(512)` | Có | | C1 | Tệp PDF/A |
| `pdf_sha256` | `bytea` | Có | | C1 | Băm tệp (EVT-17) |
| `status` | `varchar(12)` | Không | CHECK IN (`CHO_SINH`, `DA_SINH`, `DA_KY`, `LOI`) | C1 | Trạng thái |
| `signed_at` | `timestamptz` | Có | | C1 | Thời điểm ký (EVT-18) |

Ước tính 6 triệu dòng mỗi năm.

#### Bảng dossier.special_case

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `person_id` | `uuid` | Không | Chỉ mục | C1 | Người |
| `case_code` | `varchar(32)` | Không | CHECK theo danh mục 13 trường hợp trong Policy Pack | C1 | Mã trường hợp, ví dụ `DIEU_DONG_DUOI_2_THANG` |
| `detected_by` | `varchar(8)` | Không | CHECK IN (`TU_DONG`, `THU_CONG`) | C1 | Hệ thống tự phát hiện (từ EVT-15) hay người nhập |
| `detail_enc` | `bytea` | Có | | C2 | Chi tiết (ví dụ thông tin thai sản, xác minh) |
| `dek_id` | `uuid` | Có | | C1 | Khoá dữ liệu |
| `effect` | `varchar(24)` | Không | CHECK IN (`KHONG_XEP_LOAI`, `XEP_LOAI_TAI_DON_VI_CU`, `BAO_LUU`, `XEP_LOAI_BINH_THUONG`, `CHO_KET_LUAN`) | C1 | Hệ quả với việc xếp loại |
| `effective_from`, `effective_to` | `date` | Có | | C1 | Thời gian áp dụng |
| `legal_ref` | `varchar(100)` | Không | | C0 | Ví dụ "PL 6 §3" |
| `confirmed_by`, `confirmed_at` | | Có | | C1 | Xác nhận của cơ quan tổ chức |

#### Bảng dossier.remediation_item (bổ sung)

Bảng `dossier.remediation_item` (đề xuất của TL-02b, SCR-1101) theo dõi từng hạn chế cần khắc phục rút ra từ biên bản kiểm điểm qua các kỳ, thay cho cột `remediation_due` đơn lẻ trên góp ý.

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng theo kỳ phát sinh | C1 | Kỳ |
| `person_id` | `uuid` | Không | Chỉ mục | C1 | Người phải khắc phục |
| `meeting_id`, `review_comment_id` | `uuid` | Không/Có | FK | C1 | Nguồn |
| `content_enc` | `bytea` | Không | | C2 | Nội dung hạn chế và yêu cầu khắc phục |
| `dek_id` | `uuid` | Không | | C1 | Khoá dữ liệu |
| `due_period_id` | `uuid` | Có | | C1 | Kỳ phải hoàn thành |
| `status` | `varchar(16)` | Không | CHECK IN (`MO`, `DANG_KHAC_PHUC`, `DA_KHAC_PHUC`, `CHUA_KHAC_PHUC`) | C1 | Trạng thái |
| `verified_by`, `verified_at` | | Có | | C1 | Xác nhận |

## Schema oversight (MOD-07)

### Thiết kế

Schema `oversight` quản lý nhóm tính năng giám sát, hậu kiểm. Bốn cụm bảng tương ứng bốn nghiệp vụ. Khiếu nại (`oversight.complaint`, `oversight.complaint_response`) lưu nội dung khiếu nại và trả lời ở dạng C2 mã hoá trường, gắn với hồ sơ đánh giá và bước bị khiếu nại; khi khiếu nại được gửi, hồ sơ chuyển sang nhánh `KHIEU_NAI` và luồng xử lý chạy trong Flowable (EVT-25). Mở lại kỳ (`oversight.reopen_request`, `oversight.reopen_approval`) hiện thực nguyên tắc bốn mắt (tiêu chí I3, lớp bảo vệ thứ 5): yêu cầu chỉ được thực hiện khi có đủ hai phê duyệt của hai người khác nhau, khác người yêu cầu, mỗi phê duyệt đều qua xác thực bước lên (step-up MFA) và ký số. Hậu kiểm (`oversight.post_audit`) lưu kết luận theo kế hoạch, đột xuất hoặc theo phản ánh. Cảnh báo bất thường (`oversight.anomaly_alert`, `oversight.alert_action`) nhận từ EVT-20 của SVC-15, mỗi cảnh báo mang chuỗi bằng chứng (danh sách mã nhiệm vụ, minh chứng, bản ghi sổ cái) để trung tâm cảnh báo (SCR-1401) đi ngược tới nguồn.

![Hình 6.12. ERD schema oversight](db-erd-oversight.png){width=15cm}

Hình 6.12 cho thấy yêu cầu mở lại có tối thiểu hai phê duyệt và có thể sinh ra một hậu kiểm; cảnh báo bất thường có nhiều hành động xử lý.

### Từ điển dữ liệu

#### Bảng oversight.complaint

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ của hồ sơ bị khiếu nại |
| `complainant_person_id` | `uuid` | Không | Chỉ mục | C1 | Người khiếu nại |
| `profile_id` | `uuid` | Không | | C1 | Hồ sơ đánh giá |
| `target_stage` | `varchar(12)` | Không | CHECK IN (`THAM_DINH`, `DE_XUAT`, `QUYET_DINH`) | C1 | Bước bị khiếu nại |
| `subject_line` | `varchar(255)` | Không | | C1 | Tiêu đề chung (không chứa nội dung nhạy cảm) |
| `content_enc` | `bytea` | Không | | C2 | Nội dung khiếu nại |
| `dek_id` | `uuid` | Không | | C1 | Khoá dữ liệu |
| `attachment_object_ids` | `uuid[]` | Không | DEFAULT `'{}'` | C1 | Tệp kèm ở SVC-08 |
| `filed_at` | `timestamptz` | Không | | C1 | Thời điểm gửi (EVT-25) |
| `response_deadline` | `date` | Không | | C1 | Hạn trả lời |
| `status` | `varchar(12)` | Không | CHECK IN (`MOI`, `DA_TIEP_NHAN`, `DANG_GIAI_TRINH`, `CHO_KET_LUAN`, `DA_TRA_LOI`, `DANG_THI_HANH`, `KHONG_THU_LY`, `RUT`, `DONG`) | C1 | Trạng thái (bộ của TL-02b, thêm `RUT`) |
| `handler_unit_id` | `uuid` | Không | | C1 | Đơn vị xử lý |
| `outcome` | `varchar(12)` | Có | CHECK IN (`GIU_NGUYEN`, `DIEU_CHINH`, `BAO_LUU`) | C1 | Kết quả |
| `wf_process_instance_id` | `varchar(64)` | Có | | C1 | Tiến trình xử lý |
| `kind` | `varchar(12)` | Không | CHECK IN (`KHIEU_NAI`, `GIAI_TRINH`, `BAO_LUU`) | C1 | Loại (TL-02b) |
| `targets_json` | `jsonb` | Không |  | C1 | Các nội dung bị khiếu nại |
| `request_text_enc` | `bytea` | Có |  | C2 | Yêu cầu cụ thể |
| `reserve_opinion_enc` | `bytea` | Có |  | C2 | Ý kiến bảo lưu |

#### Bảng oversight.complaint_response

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `complaint_id` | `uuid` | Không | FK | C1 | Khiếu nại |
| `responder_id` | `uuid` | Không | | C1 | Người trả lời |
| `response_enc` | `bytea` | Không | | C2 | Nội dung trả lời bằng văn bản |
| `dek_id` | `uuid` | Không | | C1 | Khoá dữ liệu |
| `response_doc_ref` | `varchar(200)` | Có | | C1 | Văn bản trả lời đã ban hành |
| `responded_at` | `timestamptz` | Không | | C1 | Thời điểm |
| `sign_request_id` | `uuid` | Có | | C1 | Ký số |
| `record_kind` | `varchar(12)` | Không | CHECK IN (`YEU_CAU`, `GIAI_TRINH`, `KET_LUAN`, `BAO_LUU`) | C1 | Loại bản ghi (TL-02b) |
| `outcome` | `varchar(12)` | Có |  | C1 | Kết quả |
| `adjustment_json` | `jsonb` | Có |  | C1 | Điều chỉnh mức hoặc điểm nếu có |

#### Bảng oversight.reopen_request

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ cần mở lại |
| `scope` | `varchar(12)` | Không | CHECK IN (`TOAN_KY`, `DON_VI`, `HO_SO`) | C1 | Phạm vi |
| `scope_ids` | `uuid[]` | Không | CHECK khác rỗng khi `scope <> 'TOAN_KY'` | C1 | Đơn vị hoặc hồ sơ |
| `reason` | `varchar(4000)` | Không | | C1 | Lý do |
| `basis_ref` | `varchar(200)` | Không | | C1 | Căn cứ (kết luận kiểm tra, quyết định hậu kiểm) |
| `requested_by` | `uuid` | Không | | C1 | Người yêu cầu |
| `approvals_required` | `smallint` | Không | DEFAULT 2; CHECK ≥ 2 | C1 | Số phê duyệt cần |
| `status` | `varchar(16)` | Không | CHECK IN (`CHO_DUYET_1`, `CHO_DUYET_2`, `DA_DUYET`, `TU_CHOI`, `DA_THUC_HIEN`, `DA_DONG`) | C1 | Trạng thái; bốn giá trị đầu là bộ của TL-02b, TL-03 thêm hai trạng thái thực hiện |
| `executed_at` | `timestamptz` | Có | | C1 | Thời điểm kỳ chuyển `MO_LAI_HAU_KIEM` (EVT-22) |
| `relocked_at` | `timestamptz` | Có | | C1 | Thời điểm khoá lại |
| `code` | `varchar(32)` | Không | UQ | C1 | Mã yêu cầu |
| `audit_kind` | `varchar(16)` | Không |  | C1 | Loại hậu kiểm làm căn cứ |
| `scope_json` | `jsonb` | Không |  | C1 | Phạm vi chi tiết (TL-02b); `scope`, `scope_ids` là bản chuẩn hoá để lọc |
| `open_until` | `timestamptz` | Không |  | C1 | Hạn mở lại, quá hạn tự khoá lại |

#### Bảng oversight.reopen_approval

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `reopen_request_id` | `uuid` | Không | FK; UQ (`period_bucket`, `reopen_request_id`, `approver_id`) | C1 | Yêu cầu |
| `approver_id` | `uuid` | Không | Trigger: khác `requested_by` | C1 | Người phê duyệt |
| `approver_role` | `varchar(24)` | Không | CHECK IN (`decider`, `second_approver`) | C1 | Vai trò |
| `decision` | `varchar(8)` | Không | CHECK IN (`DONG_Y`, `TU_CHOI`) | C1 | Quyết định |
| `mfa_acr` | `varchar(32)` | Không | | C1 | Mức xác thực (claim `acr` của phiên step-up) |
| `decided_at` | `timestamptz` | Không | | C1 | Thời điểm |
| `sign_request_id` | `uuid` | Không | | C1 | Ký số phê duyệt |
| `comment` | `varchar(2000)` | Có |  | C1 | Ý kiến người duyệt |

Ràng buộc duy nhất `(reopen_request_id, approver_id)` cộng trigger so sánh với `requested_by` bảo đảm hai phê duyệt đến từ hai người khác nhau và khác người yêu cầu. Trạng thái `DA_DUYET` chỉ được đặt khi số dòng `DONG_Y` đạt `approvals_required` và có ít nhất một dòng vai trò `second_approver`; điều kiện này kiểm tra trong cùng giao dịch bằng `SELECT ... FOR UPDATE` trên dòng yêu cầu để tránh hai phê duyệt đồng thời cùng đọc trạng thái cũ.

#### Bảng oversight.post_audit

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `audit_kind` | `varchar(16)` | Không | CHECK IN (`THEO_KE_HOACH`, `DOT_XUAT`, `THEO_PHAN_ANH`) | C1 | Loại hậu kiểm (QĐ 15.3) |
| `target_type` | `varchar(16)` | Không | CHECK IN (`HO_SO`, `DON_VI`, `NHIEM_VU`, `MINH_CHUNG`) | C1 | Đối tượng |
| `target_id` | `uuid` | Không | | C1 | Mã đối tượng |
| `inspector_id` | `uuid` | Không | | C1 | Người kiểm tra |
| `findings_enc` | `bytea` | Có | | C2 | Phát hiện, kết luận chi tiết |
| `dek_id` | `uuid` | Có | | C1 | Khoá dữ liệu |
| `conclusion` | `varchar(16)` | Có | CHECK IN (`KHONG_SAI_LECH`, `HUY_KET_QUA`, `DANH_GIA_LAI`) | C1 | Kết luận |
| `reopen_request_id` | `uuid` | Có | FK | C1 | Yêu cầu mở lại sinh từ hậu kiểm |
| `opened_at`, `closed_at` | `timestamptz` | Không/Có | | C1 | Thời gian |

#### Bảng oversight.anomaly_alert

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `signal_code` | `varchar(24)` | Không | CHECK IN (`TU_CHAM_CAO`, `CAO_BANG`, `MUC_TIEU_DE`, `MINH_CHUNG_TRUNG`, `CAP_NHAT_DON`, `SUA_SAU_KHOA`, `CHE_NHO`, `MIEN_TRU_LAP`) | C1 | Tám dấu hiệu (slide 31) |
| `severity` | `varchar(8)` | Không | CHECK IN (`CAO`, `VUA`, `THAP`) | C1 | Mức |
| `subject_type` | `varchar(12)` | Không | CHECK IN (`CA_NHAN`, `DON_VI`, `NGUOI_DUYET`) | C1 | Đối tượng |
| `subject_id` | `uuid` | Không | Chỉ mục | C1 | Mã đối tượng |
| `evidence_chain` | `jsonb` | Không | | C1 | Danh sách mã bằng chứng (nhiệm vụ, minh chứng, bản ghi sổ cái, băm trùng) |
| `detected_at` | `timestamptz` | Không | | C1 | Thời điểm phát hiện |
| `source_event_id` | `uuid` | Không | UQ (`tenant_id`, `source_event_id`, `period_bucket`) | C1 | EVT-20 nguồn |
| `model_version` | `varchar(32)` | Không | | C1 | Phiên bản quy tắc hoặc mô hình phát hiện |
| `status` | `varchar(20)` | Không | CHECK IN (`MOI`, `DANG_XEM`, `HOP_LE`, `DA_GIAO_KIEM_TRA`, `CO_VI_PHAM`, `KHONG_VI_PHAM`, `TU_DONG_DONG`, `DONG`) | C1 | Trạng thái (bộ của TL-02b) |

#### Bảng oversight.alert_action

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `anomaly_alert_id` | `uuid` | Không | FK | C1 | Cảnh báo |
| `action` | `varchar(20)` | Không | CHECK IN (`GIAO_KIEM_TRA`, `XAC_NHAN_HOP_LE`, `GHI_CHU`, `DONG`) | C1 | Hành động |
| `assigned_to` | `uuid` | Có | | C1 | Người được giao kiểm tra |
| `note` | `varchar(4000)` | Có | | C1 | Ghi chú |
| `acted_at` | `timestamptz` | Không | | C1 | Thời điểm |

Schema `oversight` có quy mô nhỏ so với `task` (vài chục nghìn khiếu nại mỗi năm, khoảng 100.000 cảnh báo mỗi quý ở CELL-HCM), nhưng dữ liệu có thời hạn lưu dài nhất cùng với `appraisal` (chương 16).

## Schema flowable và saga (SVC-06)

### Thiết kế

SVC-06 dùng Flowable 7 làm bộ máy quy trình, chạy trên CSDL riêng `workflow` ở cụm `pg-wf`. Bảng nội bộ `ACT_*` của Flowable do thư viện tự quản lý phiên bản (bảng `ACT_GE_PROPERTY`), nhưng nguyên tắc "ứng dụng không chạy DDL" vẫn giữ: thuộc tính `flowable.database-schema-update=false`, script DDL của Flowable cho đúng phiên bản được đưa vào thư mục Flyway của SVC-06 và chạy bằng Job di trú như mọi schema khác. Khi nâng Flowable, nhóm SVC-06 lấy script nâng cấp của nhà cung cấp, kiểm thử trên bản sao dữ liệu thật của CELL-HCM rồi mới đưa vào Flyway.

Ngoài bảng `ACT_*`, schema `flowable` có năm bảng do nền tảng định nghĩa. `flowable.wf_template` lưu mẫu quy trình BPMN theo phiên bản, gắn với Policy Pack; `flowable.wf_binding` gắn mẫu với khối đối tượng, loại kỳ, luồng (Đảng hoặc Nhà nước). `flowable.wf_task_projection` là bản chiếu phi chuẩn hoá của công việc người dùng đang chờ (hộp việc cần duyệt), được cập nhật trong cùng giao dịch với `ACT_RU_TASK` qua bộ lắng nghe sự kiện của Flowable; màn hình hộp việc đọc bảng chiếu này thay vì truy vấn API Flowable có chi phí nối nhiều bảng. `flowable.saga_instance` và `flowable.saga_step` lưu trạng thái các saga điều phối (chốt kỳ, mở lại hậu kiểm, tốt nghiệp tenant), mỗi bước có hành động bù trừ đã đăng ký.

Các bảng `ACT_*` được tinh chỉnh ba điểm. Thứ nhất, bảng lịch sử `ACT_HI_*` đặt mức lịch sử `audit` (không lưu biến chi tiết ở mức `full`), vì vết đầy đủ đã nằm ở sổ cái. Thứ hai, job dọn lịch sử của Flowable (`historyCleaningCycle`) chạy hằng đêm, giữ 400 ngày; dữ liệu cũ hơn đã có trong sổ cái và ClickHouse. Thứ ba, các chỉ mục bổ sung trên `ACT_RU_TASK (TENANT_ID_, ASSIGNEE_)` và `ACT_RU_VARIABLE (PROC_INST_ID_, NAME_)` được đưa vào Flyway sau khi đo trên tải thật. Cột `TENANT_ID_` sẵn có của Flowable được dùng để tách tenant ở cell pool; ở cell silo, mỗi tenant có CSDL `workflow` riêng.

![Hình 6.13. ERD schema flowable phần nền tảng](db-erd-flowable.png){width=14cm}

Hình 6.13 chỉ thể hiện các bảng do nền tảng định nghĩa và một bảng `ACT_RU_TASK` đại diện cho bộ bảng của Flowable.

### Từ điển dữ liệu

#### Bảng flowable.wf_template

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung trừ `period_bucket`) | | | PK `id` | C1 | |
| `template_code` | `varchar(64)` | Không | UQ (`tenant_id`, `template_code`, `version_no`) | C0 | Mã mẫu, ví dụ `DUYET_KE_HOACH`, `DANH_GIA_QUY_NN` |
| `version_no` | `int` | Không | | C0 | Phiên bản |
| `bpmn_xml` | `text` | Không | | C0 | Định nghĩa BPMN 2.0 |
| `bpmn_sha256` | `bytea` | Không | | C0 | Băm nội dung |
| `flowable_deployment_id` | `varchar(64)` | Có | | C0 | Mã triển khai trong Flowable |
| `policy_pack_version_id` | `uuid` | Có | | C1 | Gói quy tắc tương ứng |
| `status` | `varchar(12)` | Không | CHECK IN (`NHAP`, `HIEU_LUC`, `NGUNG`) | C0 | Trạng thái |

#### Bảng flowable.wf_binding

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung trừ `period_bucket`) | | | PK `id` | C1 | |
| `process_kind` | `varchar(32)` | Không | | C0 | Loại nghiệp vụ |
| `subject_group_id` | `uuid` | Có | UQ NULLS NOT DISTINCT (`tenant_id`, `process_kind`, `subject_group_id`, `flow`, `period_type`) | C1 | Khối đối tượng |
| `flow` | `varchar(12)` | Có | | C1 | Luồng |
| `period_type` | `varchar(8)` | Có | | C1 | Loại kỳ |
| `wf_template_id` | `uuid` | Không | FK | C1 | Mẫu áp dụng |
| `scope_json` | `jsonb` | Có |  | C1 | Phạm vi áp dụng mở rộng (TL-02b) |
| `params_json` | `jsonb` | Có |  | C1 | Tham số truyền vào tiến trình |

#### Bảng flowable.wf_task_projection

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `task_id` | `varchar(64)` | Không | PK | C1 | Mã `ACT_RU_TASK.ID_` |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `assignee_id` | `uuid` | Có | Chỉ mục (`tenant_id`, `assignee_id`, `due_at`) | C1 | Người được giao |
| `candidate_role` | `varchar(32)` | Có | Chỉ mục | C1 | Vai trò ứng viên |
| `candidate_unit_id` | `uuid` | Có | | C1 | Đơn vị ứng viên |
| `business_type`, `business_id` | | Không | | C1 | Đối tượng nghiệp vụ (kế hoạch, hồ sơ, khiếu nại) |
| `title` | `varchar(255)` | Không | | C1 | Tiêu đề hiển thị |
| `period_id` | `uuid` | Có | | C1 | Kỳ |
| `due_at` | `timestamptz` | Có | | C1 | Hạn |
| `created_at` | `timestamptz` | Không | | C1 | Thời điểm |

#### Bảng flowable.saga_instance

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung trừ `period_bucket`) | | | PK `id` | C1 | |
| `saga_type` | `varchar(32)` | Không | CHECK IN (`CHOT_KY`, `MO_LAI_HAU_KIEM`, `TOT_NGHIEP_TENANT`, `DONG_GOI_HO_SO`) | C1 | Loại saga |
| `correlation_id` | `uuid` | Không | UQ (`tenant_id`, `saga_type`, `correlation_id`) | C1 | Mã nghiệp vụ (kỳ, yêu cầu mở lại) |
| `status` | `varchar(16)` | Không | CHECK IN (`DANG_CHAY`, `CHO_SU_KIEN`, `HOAN_TAT`, `DANG_BU_TRU`, `DA_BU_TRU`, `LOI_CAN_XU_LY`) | C1 | Trạng thái |
| `current_step_no` | `int` | Không | | C1 | Bước hiện tại |
| `point_of_no_return_passed` | `boolean` | Không | DEFAULT false | C1 | Đã qua điểm không quay lại |
| `context` | `jsonb` | Không | | C1 | Tham số (mã kỳ, danh sách đơn vị) |
| `started_at`, `finished_at` | `timestamptz` | Không/Có | | C1 | Thời gian |

#### Bảng flowable.saga_step

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `saga_instance_id` | `uuid` | Không | FK; UQ (`saga_instance_id`, `step_no`) | C1 | Saga |
| `step_no` | `int` | Không | | C1 | Thứ tự |
| `step_code` | `varchar(48)` | Không | | C1 | Ví dụ `DAT_DANG_KHOA`, `CHO_XA_SU_KIEN`, `DAT_DA_KHOA`, `CHAY_TINH_DIEM`, `NIEM_PHONG` |
| `command_event_id` | `uuid` | Có | | C1 | Lệnh đã phát |
| `reply_event_id` | `uuid` | Có | | C1 | Phản hồi đã nhận |
| `compensation_code` | `varchar(48)` | Có | | C1 | Hành động bù trừ |
| `status` | `varchar(16)` | Không | CHECK IN (`CHO`, `DANG_CHAY`, `XONG`, `LOI`, `DA_BU_TRU`) | C1 | Trạng thái |
| `attempts` | `int` | Không | DEFAULT 0 | C1 | Số lần thử |
| `last_error` | `varchar(2000)` | Có | | C1 | Lỗi gần nhất |
| `started_at`, `finished_at` | `timestamptz` | Có | | C1 | Thời gian |

Hai bảng saga là bổ sung so với baseline (baseline nêu "saga chốt kỳ" ở mức cơ chế). Cơ chế saga được trình bày ở mục 10.6.

## Schema scoring (SVC-07)

### Thiết kế

SVC-07 là bộ máy tính điểm thuần, không giữ trạng thái nghiệp vụ. Schema `scoring` (trong CSDL dữ liệu tenant) lưu tác vụ tính, kết quả và vết tính. Thiết kế nhằm hai đích: tính lại được với đúng kết quả cũ, và giải thích được từng điểm số.

Để tái lập, mỗi `scoring.score_result` lưu `input_hash`, là SHA-256 của dạng chuẩn hoá (RFC 8785, JSON Canonicalization Scheme) của toàn bộ dữ liệu đầu vào, và `policy_content_hash` của gói quy tắc. Dữ liệu đầu vào được chụp vào `scoring.score_input_v` (bổ sung so với baseline): bảng phân vùng theo kỳ, lưu nguyên văn tập đầu vào đã chuẩn hoá của từng người ở dạng `jsonb` nén. Khi tính lại (EVT-16 kiểu `TINH_LAI_HAU_KIEM` hoặc kiểm tra định kỳ), SVC-07 đọc tập đầu vào này, chạy với đúng phiên bản quy tắc, so sánh băm kết quả. Bảng `scoring.score_trace` lưu từng bước tính (công thức, giá trị trung gian, điều khoản áp dụng), là nguồn của màn hình "vì sao có điểm này" (SCR-0903).

Tính điểm chạy theo lô (`scoring.score_job`) khi chốt kỳ và theo yêu cầu khi tạm tính. Một lô chốt kỳ ở CELL-HCM có khoảng 1,2 triệu hồ sơ; SVC-07 chia lô theo đơn vị, chạy song song 32 luồng, ghi theo lô 1.000 dòng bằng `COPY` vào bảng tạm rồi `INSERT ... SELECT`, đạt khoảng 8.000 hồ sơ mỗi giây trên một cụm `pg-biz`.

![Hình 6.14. ERD schema scoring](db-erd-scoring.png){width=14cm}

Hình 6.14 cho thấy một tác vụ sinh nhiều kết quả, mỗi kết quả có một tập đầu vào và nhiều dòng vết tính.

### Từ điển dữ liệu

#### Bảng scoring.score_job

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `job_kind` | `varchar(20)` | Không | CHECK IN (`TAM_TINH`, `CHOT_KY`, `TINH_LAI_HAU_KIEM`, `KIEM_TRA_TAI_LAP`) | C1 | Loại |
| `scope` | `jsonb` | Không | | C1 | Phạm vi (đơn vị, danh sách người) |
| `policy_pack_version_id` | `uuid` | Không | | C1 | Phiên bản quy tắc |
| `trigger_event_id` | `uuid` | Có | | C1 | Sự kiện kích hoạt (EVT-09, EVT-22) |
| `total_items`, `done_items`, `failed_items` | `int` | Không | | C1 | Tiến độ |
| `status` | `varchar(12)` | Không | CHECK IN (`CHO`, `DANG_CHAY`, `XONG`, `LOI`) | C1 | Trạng thái |
| `started_at`, `finished_at` | `timestamptz` | Có | | C1 | Thời gian |

#### Bảng scoring.score_input_v

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `period_bucket`, `id` | | Không | PK | C1 | |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `person_id`, `flow` | | Không | Chỉ mục | C1 | Người, luồng |
| `input_hash` | `bytea` | Không | UQ (`period_bucket`, `tenant_id`, `input_hash`) | C1 | Băm |
| `input_doc` | `jsonb` | Không | Nén TOAST lz4 | C1 | Tập đầu vào chuẩn hoá (nhiệm vụ, tiến độ, tỷ trọng, kết quả thẩm định từng nhiệm vụ, tiêu chí chung); không chứa văn bản C2 |
| `captured_at` | `timestamptz` | Không | | C1 | Thời điểm chụp |

Tập đầu vào trùng băm được dùng lại, nên nhiều lần tạm tính khi dữ liệu không đổi không làm phình bảng.

#### Bảng scoring.score_result

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `period_id`, `period_bucket` | | Không | Phân vùng | C1 | Kỳ |
| `score_job_id` | `uuid` | Không | FK | C1 | Tác vụ |
| `person_id`, `flow`, `profile_id` | | Không | Chỉ mục | C1 | Đối tượng |
| `input_hash` | `bytea` | Không | | C1 | Băm đầu vào |
| `policy_pack_version_id`, `policy_content_hash` | | Không | | C1 | Quy tắc |
| `engine_version` | `varchar(32)` | Không | | C1 | Phiên bản bộ máy tính (ảnh container) |
| `result_doc` | `jsonb` | Không | | C1 | Kết quả đầy đủ (điểm thành phần, mức tự động, điều kiện) |
| `result_hash` | `bytea` | Không | | C1 | Băm kết quả, dùng để so khi tính lại |
| `status` | `varchar(8)` | Không | CHECK IN (`OK`, `LOI`) | C1 | Trạng thái |
| `computed_at` | `timestamptz` | Không | | C1 | Thời điểm |

#### Bảng scoring.score_trace

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `period_bucket`, `id` | | Không | PK | C1 | |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `score_result_id` | `uuid` | Không | Chỉ mục | C1 | Kết quả |
| `step_no` | `int` | Không | | C1 | Thứ tự bước |
| `rule_code` | `varchar(64)` | Không | | C0 | Quy tắc áp dụng |
| `legal_ref` | `varchar(200)` | Có | | C0 | Điều khoản |
| `inputs` | `jsonb` | Không | | C1 | Giá trị vào của bước |
| `output` | `jsonb` | Không | | C1 | Giá trị ra |
| `explanation` | `varchar(1000)` | Có | | C1 | Câu giải thích |

Bảng `score_trace` lớn nhất của schema (khoảng 40 dòng mỗi kết quả, tức 150 đến 200 triệu dòng mỗi quý ở CELL-HCM), nên được nén ở mức trang bằng việc lưu `inputs` và `output` dạng `jsonb` ngắn, phân vùng theo kỳ, và tách phân vùng cũ hơn hai năm sang lưu trữ (chương 16).

## Schema evidence (SVC-08)

### Thiết kế

SVC-08 quản lý siêu dữ liệu của mọi tệp trên Ceph. Schema `evidence` không lưu nội dung tệp. Đối tượng được định địa chỉ theo nội dung: khoá đối tượng là `t/<tenant>/sha256/<2 ký tự đầu>/<băm>`, nên hai lần tải cùng nội dung trong một tenant cho cùng một đối tượng. `evidence.object_meta` là đối tượng logic (một tài liệu người dùng thấy), `evidence.object_version` là từng phiên bản nội dung trỏ tới khoá vật lý. `evidence.dedup_index` là chỉ mục băm phục vụ khử trùng và phát hiện "minh chứng trùng" (một trong tám dấu hiệu bất thường): khi cùng một băm xuất hiện ở hai minh chứng của hai người khác nhau trong cùng kỳ, SVC-15 nhận tín hiệu. Khử trùng chỉ trong phạm vi tenant, không bao giờ giữa các tenant, để không thể suy ra một tenant khác có tệp nào.

`evidence.scan_result` lưu kết quả quét mã độc (ClamAV) và kiểm tra định dạng (tệp có đúng kiểu khai báo, không chứa macro). `evidence.upload_session` (bổ sung so với baseline) quản lý tải lên nhiều phần (multipart upload) qua URL ký trước có hạn 15 phút, để tệp lớn đi thẳng từ trình duyệt tới Ceph RGW mà không qua pod ứng dụng.

![Hình 6.15. ERD schema evidence](db-erd-evidence.png){width=14cm}

Hình 6.15 cho thấy một đối tượng logic có nhiều phiên bản; mỗi phiên bản có một kết quả quét và một dòng trong chỉ mục khử trùng.

### Từ điển dữ liệu

#### Bảng evidence.object_meta

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung trừ `period_bucket`) | | | PK `id` | C1 | |
| `owner_module` | `varchar(16)` | Không | | C1 | Module sở hữu (`product`, `dossier`, `oversight`) |
| `owner_ref_id` | `uuid` | Không | Chỉ mục (`tenant_id`, `owner_module`, `owner_ref_id`) | C1 | Bản ghi nghiệp vụ |
| `file_name_enc` | `bytea` | Không | | C2 | Tên tệp gốc (có thể chứa thông tin nhạy cảm) |
| `dek_id` | `uuid` | Không | | C1 | Khoá dữ liệu |
| `media_type` | `varchar(100)` | Không | | C1 | Kiểu MIME đã xác minh |
| `classification` | `varchar(4)` | Không | CHECK IN (`C1`, `C2`, `C3`) | C1 | Phân loại tệp |
| `current_version_id` | `uuid` | Có | | C1 | Phiên bản hiện hành |
| `retention_class` | `varchar(16)` | Không | | C1 | Lớp lưu giữ (chương 16) |
| `legal_hold` | `boolean` | Không | DEFAULT false | C1 | Đang giữ pháp lý |

#### Bảng evidence.object_version

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `object_meta_id` | `uuid` | Không | FK; UQ (`object_meta_id`, `version_no`) | C1 | Đối tượng |
| `version_no` | `int` | Không | | C1 | Phiên bản |
| `sha256` | `bytea` | Không | Chỉ mục (`tenant_id`, `sha256`) | C1 | Băm nội dung |
| `size_bytes` | `bigint` | Không | | C1 | Kích thước |
| `bucket`, `object_key` | `varchar` | Không | | C1 | Vị trí trên Ceph |
| `ceph_version_id` | `varchar(64)` | Không | | C1 | Mã phiên bản Ceph (Object Lock yêu cầu versioning) |
| `lock_mode`, `lock_until` | | Có | CHECK `lock_mode IN ('COMPLIANCE','GOVERNANCE')` | C1 | Khoá đối tượng |
| `encrypted_dek` | `bytea` | Có | | C1 | DEK của tệp (mã hoá phía máy chủ SSE-C do SVC-08 quản lý) |
| `scan_status` | `varchar(12)` | Không | CHECK IN (`CHO_QUET`, `SACH`, `NHIEM`, `LOI`) | C1 | Trạng thái quét |
| `created_at`, `created_by` | | Không | | C1 | Tạo |

#### Bảng evidence.dedup_index

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `tenant_id` | `uuid` | Không | PK (`tenant_id`, `sha256`) | C1 | Tenant |
| `sha256` | `bytea` | Không | | C1 | Băm |
| `object_key` | `varchar(512)` | Không | | C1 | Khoá vật lý |
| `ref_count` | `int` | Không | | C1 | Số phiên bản đang tham chiếu |
| `first_seen_at` | `timestamptz` | Không | | C1 | Lần đầu |
| `owners_sample` | `uuid[]` | Không | | C1 | Tối đa 16 người sở hữu đầu tiên, phục vụ tín hiệu minh chứng trùng |

#### Bảng evidence.scan_result

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `object_version_id` | `uuid` | Không | FK | C1 | Phiên bản |
| `engine`, `engine_version` | `varchar` | Không | | C1 | Bộ quét và phiên bản chữ ký |
| `verdict` | `varchar(12)` | Không | CHECK IN (`SACH`, `NHIEM`, `NGHI_NGO`, `LOI`) | C1 | Kết luận |
| `detail` | `jsonb` | Có | | C1 | Chi tiết |
| `scanned_at` | `timestamptz` | Không | | C1 | Thời điểm |

#### Bảng evidence.upload_session

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `user_id` | `uuid` | Không | | C1 | Người tải |
| `rgw_upload_id` | `varchar(128)` | Không | | C1 | Mã multipart của RGW |
| `declared_sha256`, `declared_size` | | Không | | C1 | Khai báo trước (kiểm lại khi hoàn tất) |
| `parts_done` | `int` | Không | | C1 | Số phần đã xong |
| `expires_at` | `timestamptz` | Không | Chỉ mục | C1 | Hết hạn (job dọn huỷ multipart dở dang) |
| `status` | `varchar(12)` | Không | CHECK IN (`MO`, `HOAN_TAT`, `HUY`) | C1 | Trạng thái |

## Schema ledger (SVC-09)

### Thiết kế

Sổ cái bất biến là nền của chống gian lận (tiêu chí I1, I2, K4) và nằm trong cụm riêng `pg-ledger`. Thiết kế tổng thể của chuỗi băm, niêm phong và lưu trữ được trình bày ở chương 8, thống nhất với ADR-09 của baseline và ADR-26 của TL-01; mục này mô tả các bảng.

`ledger.ledger_entry` là bảng chỉ ghi thêm theo nghĩa chặt: sau khi chèn, không cột nào được cập nhật. Mỗi dòng là một sự kiện nghiệp vụ được ghi sổ, nối vào một trong 64 chuỗi của tenant theo `chain_partition = hash(aggregate_id) mod 64`. Chia 64 chuỗi giúp ghi song song mà vẫn giữ thứ tự chặt trong mỗi chuỗi; mọi bản ghi của cùng một đối tượng nghiệp vụ nằm cùng một chuỗi nên lịch sử của một nhiệm vụ đọc tuần tự được. `ledger.chain_head` giữ đầu chuỗi (số thứ tự và băm cuối) và được khoá dòng khi nối.

Niêm phong dùng cây Merkle hai mức. Ở mức tenant, `ledger.merkle_seal` ghi cho mỗi tenant và mỗi cửa sổ thời gian khoảng số thứ tự đã niêm phong của từng chuỗi và gốc Merkle của tenant; liên kết giữa bản ghi và niêm phong nằm ở bảng này (theo khoảng `seq`), không nằm ở `ledger_entry`. Ở mức cell, bảng bổ sung `ledger.cell_seal` lấy gốc của mọi tenant làm lá, dựng gốc cell, ký một lần bằng khoá của SVC-09 trong HSM và lấy dấu thời gian RFC 3161 một lần cho cả cell. Cửa sổ là một giờ, rút xuống 5 phút trong 3 ngày cuối kỳ. `ledger.anchor_receipt` lưu biên nhận neo gốc cell hằng ngày ra ít nhất hai bên độc lập. `ledger.verify_run` lưu các lần kiểm tra toàn vẹn. `ledger.archive_segment` lưu danh mục phân vùng đã chuyển sang Parquet trên Ceph Object Lock.

Vai trò `svc_ledger` chỉ có `INSERT` và `SELECT` trên `ledger_entry`, `merkle_seal`, `cell_seal`; không vai trò nào kể cả `mig_ledger` có `UPDATE` hay `DELETE` sau khi Flyway tạo bảng (quyền được thu hồi ngay trong script tạo bảng), và trigger `BEFORE UPDATE OR DELETE` cùng trigger mức câu lệnh `BEFORE TRUNCATE` ném lỗi để chặn cả siêu người dùng vô tình. Xoá phân vùng cũ chỉ thực hiện bằng `DETACH PARTITION` sau khi phân vùng đã được lưu trữ và kiểm tra, bởi vai trò riêng `svc_ledger_archiver` dùng trong job có phê duyệt hai người.

![Hình 6.16. ERD schema ledger](db-erd-ledger.png){width=15cm}

Hình 6.16 cho thấy bản ghi sổ cái liên kết với bản ghi trước bằng băm; niêm phong tenant trỏ tới khoảng số thứ tự của từng chuỗi và thuộc về một niêm phong cell; niêm phong cell có biên nhận neo; phân vùng lưu trữ trỏ tới dải số thứ tự.

### DDL mẫu: ledger.ledger_entry

```sql
CREATE TABLE ledger.ledger_entry (
  tenant_id         uuid         NOT NULL,
  recorded_month    int          NOT NULL,      -- yyyymm, khoá phân vùng mức 2
  chain_partition   smallint     NOT NULL,
  seq_no            bigint       NOT NULL,      -- tăng liên tục trong một chuỗi, không có lỗ
  id                uuid         NOT NULL,      -- UUIDv7
  event_id          uuid         NOT NULL,      -- id của CloudEvent nguồn
  event_type        varchar(64)  NOT NULL,
  aggregate_type    varchar(32)  NOT NULL,
  aggregate_id      uuid         NOT NULL,
  actor_id          uuid,
  actor_kind        varchar(8)   NOT NULL,      -- NGUOI, DICH_VU, HE_THONG
  occurred_at       timestamptz  NOT NULL,
  recorded_at       timestamptz  NOT NULL DEFAULT clock_timestamp(),
  payload_hash      bytea        NOT NULL,      -- SHA-256 của payload chuẩn hoá RFC 8785, chuỗi NFC
  metadata          jsonb        NOT NULL,      -- trạng thái trước/sau rút gọn, mã yêu cầu mở lại, ip băm
  metadata_hash     bytea        NOT NULL,
  prev_hash         bytea        NOT NULL,      -- entry_hash liền trước; bản ghi đầu chuỗi xem chương 8
  entry_hash        bytea        NOT NULL,      -- SHA-256(prev_hash || payload_hash || metadata_hash)
  CONSTRAINT pk_ledger_entry PRIMARY KEY (tenant_id, recorded_month, chain_partition, seq_no),
  CONSTRAINT ck_ledger_entry__part CHECK (chain_partition BETWEEN 0 AND 63),
  CONSTRAINT ck_ledger_entry__hash CHECK (length(entry_hash) = 32 AND length(prev_hash) = 32
                                          AND length(payload_hash) = 32 AND length(metadata_hash) = 32),
  CONSTRAINT ck_ledger_entry__actor CHECK (actor_kind IN ('NGUOI','DICH_VU','HE_THONG'))
) PARTITION BY LIST (tenant_id);              -- ở td_pool: PARTITION BY HASH (tenant_id), 16 phân vùng

-- Mỗi phân vùng tenant được chia tiếp theo tháng
CREATE TABLE ledger.ledger_entry_t_hcm PARTITION OF ledger.ledger_entry
  FOR VALUES IN ('3f2a0c1e-0000-7000-8000-000000000001')
  PARTITION BY RANGE (recorded_month);
CREATE TABLE ledger.ledger_entry_t_hcm_202610 PARTITION OF ledger.ledger_entry_t_hcm
  FOR VALUES FROM (202610) TO (202611);

CREATE UNIQUE INDEX uq_ledger_entry__event ON ledger.ledger_entry (tenant_id, recorded_month, event_id);
CREATE INDEX ix_ledger_entry__aggregate ON ledger.ledger_entry (tenant_id, aggregate_id, recorded_at);
CREATE INDEX ix_ledger_entry__chain_seq ON ledger.ledger_entry (tenant_id, chain_partition, seq_no);

CREATE FUNCTION ledger.fn_ledger_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'ledger_entry là bảng chỉ ghi thêm: % bị cấm', TG_OP
    USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE TRIGGER tg_ledger_entry__no_update BEFORE UPDATE OR DELETE ON ledger.ledger_entry
  FOR EACH ROW EXECUTE FUNCTION ledger.fn_ledger_immutable();
CREATE TRIGGER tg_ledger_entry__no_truncate BEFORE TRUNCATE ON ledger.ledger_entry
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.fn_ledger_immutable();

REVOKE ALL ON ledger.ledger_entry FROM PUBLIC;
GRANT SELECT, INSERT ON ledger.ledger_entry TO svc_ledger;
GRANT SELECT ON ledger.ledger_entry TO ro_ledger, cdc_ledger;
```

Phân vùng mức một theo `tenant_id` thống nhất với TL-01: dữ liệu của một tenant nằm gọn trong một nhánh phân vùng, nên chuyển tenant sang cell khác hoặc xuất toàn bộ sổ cái của một tenant cho cơ quan kiểm tra chỉ đụng tới nhánh đó. Phân vùng mức hai theo tháng ghi sổ (`recorded_month`) phục vụ lưu trữ: một sự kiện của kỳ quý I có thể được ghi sổ vào quý II (hậu kiểm), nên thời điểm ghi sổ mới là trục tự nhiên. Ở cell pool có hàng nghìn tenant, mức một dùng `HASH (tenant_id)` 16 phân vùng thay cho `LIST`. Tính liên tục của `seq_no` giữa hai tháng được kiểm tra bởi job xác minh.

### DDL mẫu: ledger.merkle_seal

```sql
CREATE TABLE ledger.cell_seal (
  id                 uuid         NOT NULL,
  cell_code          varchar(16)  NOT NULL,
  window_start       timestamptz  NOT NULL,
  window_end         timestamptz  NOT NULL,
  tenant_count       int          NOT NULL,
  cell_root          bytea        NOT NULL,
  prev_cell_root     bytea,
  signer_cert_sha256 bytea        NOT NULL,     -- chứng thư khoá niêm phong của SVC-09 (trong HSM)
  signature          bytea        NOT NULL,     -- chữ ký trên (cell_root || prev_cell_root || window_end)
  tsa_token          bytea        NOT NULL,     -- TimeStampResp RFC 3161 (DER) trên cell_root
  tsa_gen_time       timestamptz  NOT NULL,
  created_at         timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_cell_seal PRIMARY KEY (id),
  CONSTRAINT uq_cell_seal__window UNIQUE (cell_code, window_start)
);

CREATE TABLE ledger.merkle_seal (
  id                uuid         NOT NULL,
  tenant_id         uuid         NOT NULL,
  window_start      timestamptz  NOT NULL,
  window_end        timestamptz  NOT NULL,
  chain_ranges      jsonb        NOT NULL,      -- [{"p":0,"from":1201,"to":1388,"tip":"<hex>"}, ...] chuỗi có bản ghi mới
  entry_count       bigint       NOT NULL,
  tenant_root       bytea        NOT NULL,      -- gốc Merkle mức tenant
  prev_seal_id      uuid,
  prev_tenant_root  bytea,                      -- nối các niêm phong của tenant thành chuỗi
  cell_seal_id      uuid         NOT NULL,      -- niêm phong mức cell chứa gốc này
  cell_leaf_index   int          NOT NULL,      -- vị trí lá trong cây mức cell
  created_at        timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_merkle_seal PRIMARY KEY (id),
  CONSTRAINT uq_merkle_seal__window UNIQUE (tenant_id, window_start),
  CONSTRAINT ck_merkle_seal__window CHECK (window_end > window_start),
  CONSTRAINT ck_merkle_seal__root CHECK (length(tenant_root) = 32),
  CONSTRAINT fk_merkle_seal__prev FOREIGN KEY (prev_seal_id) REFERENCES ledger.merkle_seal (id),
  CONSTRAINT fk_merkle_seal__cell FOREIGN KEY (cell_seal_id) REFERENCES ledger.cell_seal (id)
);
CREATE INDEX ix_merkle_seal__tenant_window ON ledger.merkle_seal (tenant_id, window_end DESC);


REVOKE UPDATE, DELETE, TRUNCATE ON ledger.merkle_seal, ledger.cell_seal FROM PUBLIC, svc_ledger;
GRANT INSERT, SELECT ON ledger.merkle_seal, ledger.cell_seal TO svc_ledger;
```

Job niêm phong dựng cây, ký, lấy dấu thời gian TSA trước, rồi mới chèn một dòng `cell_seal` và các dòng `merkle_seal` của mọi tenant trong cùng một giao dịch; nhờ vậy cả hai bảng cũng không có cột nào phải cập nhật sau khi chèn. Nếu TSA không phản hồi, job thử lại trong cửa sổ kế tiếp và gộp hai cửa sổ; quá 24 giờ thì cảnh báo. Muốn sửa một bản ghi cũ mà không bị phát hiện thì phải tính lại chuỗi bản ghi, mọi gốc tenant và gốc cell sau đó, làm giả chữ ký trong HSM và token TSA, đồng thời sửa được bản neo ở hai bên độc lập, là điều không thể với người quản trị nền tảng.

### Từ điển dữ liệu

#### Bảng ledger.ledger_entry

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `tenant_id` | `uuid` | Không | PK; phân vùng mức 1 | C1 | Tenant |
| `recorded_month` | `int` | Không | PK; phân vùng mức 2 RANGE | C1 | Tháng ghi sổ |
| `chain_partition` | `smallint` | Không | PK; CHECK 0 đến 63 | C1 | Chuỗi |
| `seq_no` | `bigint` | Không | PK | C1 | Số thứ tự liên tục |
| `id` | `uuid` | Không | | C1 | Mã bản ghi |
| `event_id` | `uuid` | Không | UQ | C1 | Sự kiện nguồn (chống ghi trùng) |
| `event_type` | `varchar(64)` | Không | | C1 | Loại |
| `aggregate_type`, `aggregate_id` | | Không | Chỉ mục | C1 | Đối tượng |
| `actor_id`, `actor_kind` | | Có/Không | | C1 | Chủ thể |
| `occurred_at`, `recorded_at` | `timestamptz` | Không | | C1 | Thời điểm xảy ra, thời điểm ghi |
| `payload_hash` | `bytea` | Không | | C1 | Băm payload (payload đầy đủ không lưu ở đây, nằm ở module nguồn hoặc kho lưu trữ sự kiện) |
| `metadata` | `jsonb` | Không | | C1 | Siêu dữ liệu rút gọn, không chứa C2 |
| `metadata_hash`, `prev_hash`, `entry_hash` | `bytea` | Không | | C1 | Chuỗi băm; `metadata_hash` gồm cả băm `metadata_sha256` của cột `metadata` |

Không có cột nào được cập nhật sau khi chèn. Ước tính 720 triệu dòng mỗi năm ở CELL-HCM, khoảng 420 byte mỗi dòng kể cả chỉ mục, tức khoảng 300 GB mỗi năm trước khi lưu trữ.

#### Bảng ledger.chain_head

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `tenant_id` | `uuid` | Không | PK (`tenant_id`, `chain_partition`) | C1 | Tenant |
| `chain_partition` | `smallint` | Không | | C1 | Chuỗi |
| `last_seq_no` | `bigint` | Không | | C1 | Số thứ tự cuối |
| `last_hash` | `bytea` | Không | | C1 | Băm cuối |
| `last_sealed_seq_no` | `bigint` | Không | DEFAULT 0 | C1 | Số thứ tự cuối đã niêm phong (bản tiện dụng, nguồn sự thật là `merkle_seal.chain_ranges`) |
| `updated_at` | `timestamptz` | Không | | C1 | Thời điểm |

Bảng có `fillfactor = 50` để cập nhật HOT không cần sửa chỉ mục. Đây là bảng trạng thái vận hành, không phải bản ghi pháp lý, nên được phép cập nhật.

#### Bảng ledger.merkle_seal

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | UQ cửa sổ | C1 | Tenant |
| `window_start`, `window_end` | `timestamptz` | Không | | C1 | Cửa sổ (1 giờ, 5 phút trong 3 ngày cuối kỳ) |
| `chain_ranges` | `jsonb` | Không | | C1 | Khoảng `seq` đã niêm phong và băm đầu của từng chuỗi |
| `entry_count` | `bigint` | Không | | C1 | Số bản ghi |
| `tenant_root` | `bytea` | Không | | C1 | Gốc Merkle mức tenant |
| `prev_seal_id`, `prev_tenant_root` | | Có | FK | C1 | Niêm phong trước của tenant |
| `cell_seal_id`, `cell_leaf_index` | | Không | FK | C1 | Vị trí trong cây mức cell |

#### Bảng ledger.cell_seal (bổ sung)

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `cell_code` | `varchar(16)` | Không | UQ (`cell_code`, `window_start`) | C1 | Cell |
| `window_start`, `window_end` | `timestamptz` | Không | | C1 | Cửa sổ |
| `tenant_count` | `int` | Không | | C1 | Số lá (tenant có bản ghi mới) |
| `cell_root`, `prev_cell_root` | `bytea` | Không/Có | | C1 | Gốc cell và gốc trước |
| `signer_cert_sha256`, `signature` | `bytea` | Không | | C1 | Một chữ ký mỗi cell mỗi cửa sổ |
| `tsa_token`, `tsa_gen_time` | | Không | | C1 | Dấu thời gian RFC 3161 |

#### Bảng ledger.anchor_receipt

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `cell_seal_id` | `uuid` | Không | FK; UQ (`cell_seal_id`, `anchor_target`) | C1 | Niêm phong cell cuối ngày |
| `anchor_target` | `varchar(32)` | Không | CHECK IN (`CELL_KHAC`, `KHO_VPTW`, `TSA_DOC_LAP`, `OBJECT_LOCK`) | C1 | Nơi neo; mỗi ngày ít nhất hai đích độc lập |
| `receipt` | `bytea` | Không | | C1 | Biên nhận đã ký của nơi nhận |
| `anchored_at` | `timestamptz` | Không | | C1 | Thời điểm |

#### Bảng ledger.verify_run

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Có | | C1 | Tenant (NULL là toàn cell) |
| `verify_kind` | `varchar(16)` | Không | CHECK IN (`DAU_CHUOI`, `NIEM_PHONG`, `TOAN_BO`, `LUU_TRU`) | C1 | Loại |
| `range_from`, `range_to` | `timestamptz` | Không | | C1 | Phạm vi |
| `entries_checked` | `bigint` | Không | | C1 | Số bản ghi |
| `mismatches` | `int` | Không | | C1 | Số sai lệch |
| `first_mismatch` | `jsonb` | Có | | C1 | Bản ghi sai đầu tiên |
| `started_at`, `finished_at` | `timestamptz` | Không/Có | | C1 | Thời gian |

#### Bảng ledger.archive_segment (bổ sung)

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | UQ (`tenant_id`, `recorded_month`) | C1 | Tenant |
| `recorded_month` | `int` | Không | | C1 | Tháng |
| `seq_ranges` | `jsonb` | Không | | C1 | Dải số thứ tự từng chuỗi |
| `object_key` | `varchar(512)` | Không | | C1 | Tệp Parquet trên Ceph |
| `file_sha256` | `bytea` | Không | | C1 | Băm tệp |
| `row_count` | `bigint` | Không | | C1 | Số dòng |
| `lock_until` | `date` | Không | | C1 | Hạn Object Lock (compliance) |
| `verified_at` | `timestamptz` | Có | | C1 | Đã kiểm tra lại sau khi lưu trữ |
| `detached_at` | `timestamptz` | Có | | C1 | Đã tách phân vùng khỏi PostgreSQL |

## Schema esign (SVC-10)

### Thiết kế

SVC-10 chạy trong vùng bảo mật, CSDL `esign` ở cụm `pg-sec`. Khoá riêng của người ký không bao giờ đi vào CSDL: ký số từ xa qua MySign, Viettel-CA hoặc CA chuyên dùng Chính phủ thì khoá nằm ở HSM của nhà cung cấp; ký bằng chứng thư của Ban Cơ yếu thì khoá nằm trên thiết bị của người ký hoặc HSM của cơ quan. Schema chỉ lưu yêu cầu ký, danh sách tài liệu được ký kèm băm, và tham chiếu chứng thư. `esign.sign_request` là một yêu cầu ký của một người (có thể ký lô nhiều tài liệu), `esign.sign_item` là từng tài liệu với `document_sha256` (băm được ký) và `signature_value` (chữ ký CMS/CAdES hoặc PAdES đã nhận), `esign.certificate_ref` lưu chứng thư đã dùng cùng kết quả kiểm tra trạng thái thu hồi (OCSP) tại thời điểm ký, để sau này kiểm chứng được kể cả khi chứng thư đã hết hạn (định dạng chữ ký dài hạn PAdES-LTA).

![Hình 6.17. ERD schema esign](db-erd-esign.png){width=13cm}

Hình 6.17 cho thấy một yêu cầu ký gồm nhiều tài liệu và gắn với một chứng thư.

### Từ điển dữ liệu

#### Bảng esign.sign_request

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung trừ `period_bucket`) | | | PK `id` | C1 | |
| `signer_person_id` | `uuid` | Không | Chỉ mục | C1 | Người ký |
| `purpose` | `varchar(32)` | Không | CHECK IN (`TU_DANH_GIA`, `THAM_DINH`, `QUYET_DINH`, `BIEN_BAN`, `GOI_HO_SO`, `PHE_DUYET_MO_LAI`, `PHAT_HANH_POLICY`) | C1 | Mốc pháp lý |
| `source_module`, `source_ref_id` | | Không | | C1 | Bản ghi nghiệp vụ yêu cầu ký |
| `provider` | `varchar(16)` | Không | CHECK IN (`MYSIGN`, `VIETTEL_CA`, `CA_CHINH_PHU`, `BAN_CO_YEU`) | C1 | Nhà cung cấp |
| `is_batch` | `boolean` | Không | | C1 | Ký lô |
| `provider_txn_id` | `varchar(128)` | Có | | C1 | Mã giao dịch phía nhà cung cấp |
| `status` | `varchar(12)` | Không | CHECK IN (`TAO`, `CHO_XAC_NHAN`, `DA_KY`, `TU_CHOI`, `HET_HAN`, `LOI`) | C1 | Trạng thái |
| `expires_at` | `timestamptz` | Không | | C1 | Hạn xác nhận trên thiết bị |
| `completed_at` | `timestamptz` | Có | | C1 | Hoàn tất (EVT-18) |

#### Bảng esign.sign_item

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `sign_request_id` | `uuid` | Không | FK | C1 | Yêu cầu |
| `document_kind` | `varchar(24)` | Không | | C1 | Loại tài liệu (PDF/A, JSON chuẩn hoá) |
| `document_ref` | `varchar(512)` | Không | | C1 | Khoá đối tượng hoặc mã bản ghi |
| `document_sha256` | `bytea` | Không | | C1 | Băm được ký |
| `signature_format` | `varchar(12)` | Không | CHECK IN (`PADES_LTA`, `CADES_T`, `JWS`) | C1 | Định dạng |
| `signature_value` | `bytea` | Có | | C1 | Chữ ký |
| `signed_object_key` | `varchar(512)` | Có | | C1 | Tệp đã nhúng chữ ký |
| `certificate_ref_id` | `uuid` | Có | FK | C1 | Chứng thư |
| `signed_at` | `timestamptz` | Có | | C1 | Thời điểm |

#### Bảng esign.certificate_ref

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | UQ (`tenant_id`, `cert_sha256`) | C1 | Tenant |
| `person_id` | `uuid` | Không | | C1 | Chủ chứng thư |
| `cert_sha256` | `bytea` | Không | | C1 | Dấu vân tay |
| `subject_dn`, `issuer_dn` | `varchar(512)` | Không | | C1 | Chủ thể, nơi cấp |
| `serial_no` | `varchar(64)` | Không | | C1 | Số hiệu |
| `not_before`, `not_after` | `timestamptz` | Không | | C1 | Hiệu lực |
| `cert_der` | `bytea` | Không | | C1 | Chứng thư (công khai) |
| `last_ocsp_status`, `last_ocsp_at` | | Có | | C1 | Kiểm tra thu hồi gần nhất |

## Schema docgen (SVC-11)

### Thiết kế

SVC-11 sinh biểu mẫu từ mẫu docx4j và chuyển sang PDF/A-2b qua Gotenberg. Schema `docgen` nằm trong CSDL dữ liệu tenant. `docgen.template` lưu mẫu theo phiên bản (tệp `.docx` trên Ceph, băm nội dung, mã biểu mẫu và khối đối tượng áp dụng); `docgen.render_job` là tác vụ sinh nhận qua Kafka từ MOD-06; `docgen.render_output` là tệp kết quả kèm băm, phát EVT-17. Tác vụ sinh nhận dữ liệu đã chuẩn hoá trong thông điệp (không đọc CSDL của module khác), nên `render_job.input_sha256` khớp với `dossier.form_instance.data_snapshot_hash`.

![Hình 6.18. ERD schema docgen](db-erd-docgen.png){width=6cm}

Hình 6.18 cho thấy mẫu có nhiều tác vụ sinh, mỗi tác vụ có một hoặc nhiều tệp kết quả.

### Từ điển dữ liệu

#### Bảng docgen.template

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung trừ `period_bucket`) | | | PK `id` | C1 | |
| `form_code` | `varchar(16)` | Không | UQ (`tenant_id`, `form_code`, `version_no`) | C0 | Mẫu |
| `version_no` | `int` | Không | | C0 | Phiên bản |
| `subject_group_codes` | `varchar(16)[]` | Có | | C0 | Khối áp dụng |
| `object_key`, `content_sha256` | | Không | | C0 | Tệp mẫu |
| `status` | `varchar(12)` | Không | CHECK IN (`NHAP`, `HIEU_LUC`, `NGUNG`) | C0 | Trạng thái |

#### Bảng docgen.render_job

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung) | | | PK (`period_bucket`, `id`) | C1 | |
| `template_id` | `uuid` | Không | FK | C1 | Mẫu |
| `request_event_id` | `uuid` | Không | UQ | C1 | Sự kiện yêu cầu |
| `input_sha256` | `bytea` | Không | | C1 | Băm dữ liệu |
| `status` | `varchar(12)` | Không | CHECK IN (`CHO`, `DANG_SINH`, `XONG`, `LOI`) | C1 | Trạng thái |
| `attempts` | `int` | Không | | C1 | Số lần thử |
| `error` | `varchar(2000)` | Có | | C1 | Lỗi |

#### Bảng docgen.render_output

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `render_job_id` | `uuid` | Không | FK | C1 | Tác vụ |
| `output_kind` | `varchar(8)` | Không | CHECK IN (`PDFA`, `DOCX`) | C1 | Kiểu |
| `object_key`, `pdf_sha256`, `size_bytes` | | Không | | C1 | Tệp |
| `pdfa_validated` | `boolean` | Không | | C1 | Đã qua kiểm tra veraPDF |

## Schema notify (SVC-12)

### Thiết kế

SVC-12 viết bằng Go, CSDL dùng chung của cell (`cell_shared`), tách tenant bằng RLS. Thông báo là dữ liệu có vòng đời ngắn và khối lượng lớn (ước tính 40 triệu thông báo mỗi tháng ở CELL-HCM vào cao điểm cuối kỳ), nên `notify.notification` và `notify.delivery` phân vùng theo ngày bằng pg_partman và chỉ giữ 90 ngày. Nội dung thông báo không chứa dữ liệu C2: thông báo chỉ nói "Có hồ sơ cần thẩm định" kèm đường dẫn, người dùng mở ứng dụng để xem. Thông báo trong ứng dụng thời gian thực đi qua Valkey Streams (chương 12); bảng CSDL là nguồn bền vững cho hộp thông báo và cho việc gửi lại.

![Hình 6.19. ERD schema notify](db-erd-notify.png){width=12cm}

Hình 6.19 cho thấy một thông báo có nhiều lần gửi qua các kênh, theo mẫu và theo tuỳ chọn kênh của người nhận.

### Từ điển dữ liệu

#### Bảng notify.notification

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `created_day` | `date` | Không | PK (`created_day`, `id`), phân vùng RANGE ngày | C1 | Ngày tạo |
| `id` | `uuid` | Không | | C1 | Mã |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `recipient_user_id` | `uuid` | Không | Chỉ mục (`tenant_id`, `recipient_user_id`, `created_at` DESC) | C1 | Người nhận |
| `template_code` | `varchar(48)` | Không | | C0 | Mẫu |
| `params` | `jsonb` | Không | | C1 | Tham số (không chứa C2) |
| `deep_link` | `varchar(512)` | Có | | C1 | Đường dẫn |
| `priority` | `varchar(8)` | Không | | C1 | Mức ưu tiên |
| `source_event_id` | `uuid` | Không | UQ (`created_day`, `tenant_id`, `source_event_id`, `recipient_user_id`) | C1 | EVT-21 nguồn |
| `read_at` | `timestamptz` | Có | | C1 | Đã đọc |

#### Bảng notify.delivery

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `created_day`, `id` | | Không | PK | C1 | |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `notification_id` | `uuid` | Không | | C1 | Thông báo |
| `channel` | `varchar(8)` | Không | CHECK IN (`INAPP`, `PUSH`, `SMS`, `EMAIL`) | C1 | Kênh |
| `address_bidx` | `bytea` | Có | | C1 | Chỉ mục mù của địa chỉ (số điện thoại, email) |
| `status` | `varchar(12)` | Không | CHECK IN (`CHO`, `DA_GUI`, `THAT_BAI`, `BO_QUA`) | C1 | Trạng thái |
| `provider_msg_id` | `varchar(128)` | Có | | C1 | Mã phía nhà cung cấp |
| `attempts` | `int` | Không | | C1 | Số lần |
| `sent_at` | `timestamptz` | Có | | C1 | Thời điểm |

#### Bảng notify.channel_pref

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `tenant_id`, `user_id` | `uuid` | Không | PK (`tenant_id`, `user_id`, `category`) | C1 | Người dùng |
| `category` | `varchar(32)` | Không | | C0 | Nhóm thông báo |
| `channels` | `varchar(8)[]` | Không | | C1 | Kênh được bật |
| `quiet_from`, `quiet_to` | `time` | Có | | C1 | Giờ yên lặng |

#### Bảng notify.template

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C0 | Mã |
| `tenant_id` | `uuid` | Có | UQ NULLS NOT DISTINCT (`tenant_id`, `template_code`, `channel`, `locale`) | C0 | NULL là mẫu dùng chung của nền tảng |
| `template_code`, `channel`, `locale` | | Không | | C0 | Khoá |
| `subject`, `body` | `text` | Không | | C0 | Nội dung có biến |

## Schema integ (SVC-13)

### Thiết kế

SVC-13 là lớp chống hư hỏng (Anti-Corruption Layer) giữa nền tảng và hệ ngoài, CSDL `cell_shared`. Schema `integ` có năm bảng của baseline và hai bảng bổ sung. `integ.connector` cấu hình từng kết nối (vOffice, iOffice, EdXML/X-Road, LGSP/NDXP, CSDL CBCCVC, CSDL đảng viên, HRM); bí mật kết nối không lưu ở đây mà ở OpenBao, bảng chỉ lưu đường dẫn bí mật. `integ.inbox_message` lưu thông điệp nhận từ hệ ngoài ở dạng nguyên văn đã mã hoá, trước khi chuyển đổi sang mô hình dữ liệu chuẩn. `integ.mapping_xref` ánh xạ mã ngoài với mã nội bộ, có hiệu lực theo thời gian để xử lý trường hợp hệ ngoài tái sử dụng mã. `integ.sync_cursor` lưu con trỏ đồng bộ kéo (ví dụ dấu thời gian lần kéo cuối từ CSDL CBCCVC). `integ.dead_letter` giữ thông điệp không xử lý được kèm lỗi để xử lý tay.

Hai bảng bổ sung là `integ.outbound_message`, tức thông điệp gửi ra ngoài (kết quả xếp loại sang CSDL CBCCVC sau EVT-12, bản neo niêm phong sổ cái sau EVT-19) có trạng thái và số lần thử, và `integ.transfer_package`, tức gói chuyển hồ sơ cán bộ điều động liên cell (mục 10.8).

![Hình 6.20. ERD schema integ](db-erd-integ.png){width=15cm}

Hình 6.20 cho thấy mọi thông điệp vào, ra và gói chuyển đều gắn với một kết nối; ánh xạ mã gắn với kết nối nguồn.

### Từ điển dữ liệu

#### Bảng integ.connector

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung trừ `period_bucket`) | | | PK `id` | C1 | |
| `connector_code` | `varchar(32)` | Không | UQ (`tenant_id`, `connector_code`) | C0 | Ví dụ `VOFFICE`, `CSDL_CBCCVC` |
| `protocol` | `varchar(16)` | Không | CHECK IN (`REST`, `SOAP`, `EDXML`, `XROAD`, `SFTP`, `JDBC_RO`) | C0 | Giao thức |
| `endpoint` | `varchar(512)` | Không | | C1 | Địa chỉ |
| `secret_path` | `varchar(255)` | Không | | C1 | Đường dẫn bí mật ở OpenBao |
| `direction` | `varchar(8)` | Không | CHECK IN (`VAO`, `RA`, `HAI_CHIEU`) | C0 | Chiều |
| `schedule_cron` | `varchar(64)` | Có | | C0 | Lịch kéo |
| `enabled` | `boolean` | Không | | C0 | Bật |

#### Bảng integ.inbox_message

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `received_day`, `id` | | Không | PK, phân vùng ngày | C1 | |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `connector_id` | `uuid` | Không | | C1 | Kết nối |
| `external_msg_id` | `varchar(128)` | Không | UQ (`received_day`, `tenant_id`, `connector_id`, `external_msg_id`) | C1 | Mã thông điệp phía ngoài |
| `message_type` | `varchar(48)` | Không | | C1 | Loại |
| `raw_enc` | `bytea` | Không | | C2 | Nội dung nguyên văn |
| `dek_id` | `uuid` | Không | | C1 | Khoá dữ liệu |
| `status` | `varchar(12)` | Không | CHECK IN (`MOI`, `DA_CHUYEN_DOI`, `DA_PHAT`, `LOI`) | C1 | Trạng thái |
| `emitted_event_id` | `uuid` | Có | | C1 | Sự kiện đã phát (EVT-24) |

#### Bảng integ.mapping_xref

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `connector_id` | `uuid` | Không | | C1 | Hệ ngoài |
| `entity_type` | `varchar(24)` | Không | | C1 | Loại (`DON_VI`, `NHAN_SU`, `VAN_BAN`) |
| `external_id` | `varchar(128)` | Không | EXCLUDE (`tenant_id`, `connector_id`, `entity_type`, `external_id` WITH =, `valid_period` WITH &&) | C1 | Mã ngoài |
| `internal_id` | `uuid` | Không | Chỉ mục | C1 | Mã nội bộ |
| `valid_from`, `valid_to` | `date` | Không/Có | `valid_period` sinh | C1 | Hiệu lực |
| `source_note` | `varchar(255)` | Có | | C1 | Ghi chú đối chiếu |

#### Bảng integ.sync_cursor

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `tenant_id`, `connector_id`, `stream` | | Không | PK | C1 | Luồng đồng bộ |
| `cursor_value` | `varchar(255)` | Không | | C1 | Con trỏ |
| `last_success_at` | `timestamptz` | Có | | C1 | Lần thành công |
| `lag_seconds` | `int` | Có | | C1 | Độ trễ |

#### Bảng integ.dead_letter

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `direction` | `varchar(4)` | Không | | C1 | Vào hoặc ra |
| `ref_message_id` | `uuid` | Không | | C1 | Thông điệp |
| `error_code`, `error_detail` | | Không | | C1 | Lỗi |
| `resolved_by`, `resolved_at` | | Có | | C1 | Xử lý |

#### Bảng integ.outbound_message

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `created_day`, `id` | | Không | PK, phân vùng ngày | C1 | |
| `tenant_id`, `connector_id` | `uuid` | Không | | C1 | Đích |
| `source_event_id` | `uuid` | Không | UQ | C1 | Sự kiện nguồn (EVT-12, EVT-19) |
| `payload_enc` | `bytea` | Không | | C2 | Nội dung đã chuyển sang định dạng hệ ngoài |
| `dek_id` | `uuid` | Không | | C1 | Khoá |
| `status` | `varchar(12)` | Không | CHECK IN (`CHO`, `DA_GUI`, `DA_XAC_NHAN`, `LOI`) | C1 | Trạng thái |
| `attempts`, `next_attempt_at` | | Không/Có | | C1 | Thử lại theo luỹ thừa |
| `ack_ref` | `varchar(128)` | Có | | C1 | Xác nhận phía nhận |

#### Bảng integ.transfer_package

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| (cột chung trừ `period_bucket`) | | | PK `id` | C1 | |
| `person_id` | `uuid` | Không | | C1 | Cán bộ điều động |
| `direction` | `varchar(4)` | Không | CHECK IN (`DI`, `DEN`) | C1 | Chiều |
| `peer_cell_code`, `peer_tenant_code` | `varchar(32)` | Không | | C1 | Cell, tenant đối tác |
| `manifest` | `jsonb` | Không | | C1 | Danh mục nội dung và băm |
| `object_key` | `varchar(512)` | Không | | C1 | Gói mã hoá trên Ceph |
| `package_sha256` | `bytea` | Không | | C1 | Băm gói |
| `wrapped_key` | `bytea` | Không | | C1 | Khoá gói được bọc bằng khoá công khai của cell nhận |
| `signature` | `bytea` | Không | | C1 | Chữ ký của cell gửi |
| `status` | `varchar(16)` | Không | CHECK IN (`DONG_GOI`, `DA_GUI`, `DA_NHAN`, `DA_NHAP`, `TU_CHOI`) | C1 | Trạng thái |

## Schema ai (SVC-14)

### Thiết kế

SVC-14 là cổng AI chỉ đọc, CSDL `ai` ở cụm `pg-sec`, dùng extension pgvector. `ai.doc_chunk` lưu đoạn văn bản của tài liệu nguồn (văn bản chỉ đạo, danh mục công việc, quy định) cùng véc-tơ nhúng `vector(1024)`, chỉ mục HNSW `vector_cosine_ops` với `m = 16`, `ef_construction = 64`. Chỉ tài liệu C0, C1 được đưa vào kho RAG; văn bản C2 không bao giờ được nhúng. Mỗi đoạn mang `tenant_id` và `acl_unit_ids` để truy vấn RAG lọc theo quyền của người hỏi trước khi xếp hạng (lọc trước bằng điều kiện `WHERE` kèm `hnsw.iterative_scan = relaxed_order` của pgvector 0.8). `ai.suggestion_log` ghi mọi gợi ý AI kèm nhãn "do AI gợi ý", người dùng chấp nhận hay sửa; đây là căn cứ để chứng minh AI không quyết định. `ai.guardrail_event` ghi các lần rào chắn chặn (lộ dữ liệu, câu hỏi ngoài phạm vi, chèn lệnh).

![Hình 6.21. ERD schema ai](db-erd-ai.png){width=6cm}

Hình 6.21 cho thấy nhật ký gợi ý tham chiếu các đoạn tài liệu đã dùng; sự kiện rào chắn gắn với một lần gợi ý.

### Từ điển dữ liệu

#### Bảng ai.doc_chunk

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `id` | `uuid` | Không | PK | C1 | Mã |
| `tenant_id` | `uuid` | Không | RLS | C1 | Tenant |
| `source_type`, `source_id` | | Không | Chỉ mục | C1 | Nguồn |
| `chunk_no` | `int` | Không | UQ (`tenant_id`, `source_id`, `chunk_no`, `embed_model`) | C1 | Thứ tự |
| `content` | `text` | Không | | C1 | Nội dung (chỉ C0, C1) |
| `embedding` | `vector(1024)` | Không | HNSW cosine | C1 | Véc-tơ |
| `embed_model` | `varchar(64)` | Không | | C0 | Mô hình nhúng |
| `acl_unit_ids` | `uuid[]` | Không | GIN | C1 | Đơn vị được xem |
| `classification` | `varchar(4)` | Không | CHECK IN (`C0`, `C1`) | C1 | Phân loại |

#### Bảng ai.suggestion_log

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `created_day`, `id` | | Không | PK, phân vùng ngày | C1 | |
| `tenant_id`, `user_id` | `uuid` | Không | | C1 | Người hỏi |
| `use_case` | `varchar(32)` | Không | | C0 | Ví dụ `GOI_Y_NHIEM_VU`, `TOM_TAT_VAN_BAN`, `OCR` |
| `model`, `prompt_template_version` | | Không | | C0 | Mô hình, mẫu lời nhắc |
| `prompt_sha256` | `bytea` | Không | | C1 | Băm lời nhắc (không lưu nguyên văn khi có dữ liệu cá nhân) |
| `chunk_ids` | `uuid[]` | Không | | C1 | Đoạn đã dùng |
| `output_text` | `text` | Có | | C1 | Gợi ý |
| `user_action` | `varchar(12)` | Có | CHECK IN (`CHAP_NHAN`, `SUA`, `BO_QUA`) | C1 | Phản hồi |
| `target_ref` | `varchar(128)` | Có | | C1 | Bản ghi nhận gợi ý |
| `kind` | `varchar(24)` | Có |  | C0 | Loại gợi ý chi tiết, ví dụ `TOM_TAT_TUAN` |
| `prompt_redacted` | `text` | Có |  | C1 | Lời nhắc đã che dữ liệu cá nhân |
| `sources_json` | `jsonb` | Có |  | C1 | Nguồn trích dẫn |
| `confidence` | `numeric(6,4)` | Có |  | C1 | Độ tin cậy |
| `decision` | `varchar(12)` | Có |  | C1 | Quyết định của người dùng (TL-02a; trùng nghĩa `user_action`) |
| `edited_fields` | `varchar(64)[]` | Có |  | C1 | Các ô AI người dùng đã sửa (TL-02a) |
| `accepted_by` | `uuid` | Có |  | C1 | Người chấp nhận gợi ý (TL-02b) |

#### Bảng ai.guardrail_event

| Cột | Kiểu | Null | Khoá/Ràng buộc | Phân loại | Mô tả |
|---|---|---|---|---|---|
| `created_day`, `id` | | Không | PK | C1 | |
| `tenant_id` | `uuid` | Không | | C1 | Tenant |
| `suggestion_log_id` | `uuid` | Có | | C1 | Lần gợi ý |
| `rule_code` | `varchar(32)` | Không | | C0 | Rào chắn (`PII_OUT`, `PROMPT_INJECTION`, `OUT_OF_SCOPE`, `C2_REQUEST`) |
| `action` | `varchar(8)` | Không | CHECK IN (`CHAN`, `CHE`, `CANH_BAO`) | C1 | Hành động |
| `detail` | `jsonb` | Có | | C1 | Chi tiết |

## Schema iam (SVC-02)

Schema `iam` là bảng của Keycloak 26 trong CSDL riêng ở cụm `pg-iam`; nền tảng không sửa cấu trúc bảng của Keycloak và không truy vấn trực tiếp. Mọi tích hợp đi qua API quản trị, SPI sự kiện và luồng SCIM từ SVC-04. Script DDL của Keycloak vẫn đi qua Job di trú riêng (Keycloak dùng Liquibase nội bộ, chạy ở chế độ `--spi-connections-jpa-quarkus-migration-strategy=manual` để xuất script, kiểm duyệt rồi áp dụng), giữ nguyên tắc ứng dụng không có quyền DDL ở môi trường vận hành. Ở cell silo, mỗi tenant là một realm trong CSDL `iam` của cell; số realm mỗi cụm được giới hạn 300 để thời gian khởi động Keycloak nằm trong ngưỡng.

# Dữ liệu tổ chức hai trục thời gian

## Vì sao cần hai trục thời gian

Dữ liệu tổ chức, nhân sự và phân công thay đổi liên tục: sáp nhập đơn vị, điều động, bổ nhiệm, kiêm nhiệm. Đánh giá lại phải căn cứ vào tổ chức tại đúng thời điểm của kỳ, và khi có khiếu nại phải trả lời được câu hỏi "tại thời điểm quyết định, người quyết định đã thấy dữ liệu tổ chức như thế nào". Hai câu hỏi này cần hai trục thời gian khác nhau. Trục hiệu lực (valid time, `valid_from` và `valid_to`) cho biết một sự thật đúng trong thế giới thực từ ngày nào đến ngày nào, ví dụ ông A giữ chức Phó Giám đốc Sở từ 01/03/2026. Trục ghi nhận (transaction time hay recorded time, `recorded_from` và `recorded_to`) cho biết hệ thống biết điều đó từ lúc nào đến lúc nào, ví dụ quyết định bổ nhiệm được nhập ngày 10/03/2026, sau đó ngày 20/04/2026 phát hiện nhập sai ngày hiệu lực và được đính chính.

Mô hình hai trục thời gian (bitemporal) cho phép trả lời bốn loại câu hỏi. Câu hỏi "hiện tại" lấy dòng có hiệu lực hôm nay và chưa bị thay thế. Câu hỏi "tại ngày X" lấy dòng có hiệu lực tại X theo hiểu biết hiện tại. Câu hỏi "như đã biết tại thời điểm T" lấy dòng có hiệu lực hôm nay theo hiểu biết tại T. Câu hỏi đầy đủ "tại ngày X như đã biết tại thời điểm T" là câu hỏi mà quy trình khiếu nại cần.

![Hình 7.1. Hai trục thời gian của một phân công khi có đính chính](db-bitemporal.png){width=14cm}

Hình 7.1 minh hoạ một phân công qua ba lần ghi. Lần ghi thứ nhất (ngày 10/03) tạo phiên bản V1 với hiệu lực từ 01/03. Ngày 20/04 phát hiện ngày hiệu lực đúng là 15/03: hệ thống không sửa V1 mà đóng trục ghi nhận của V1 (`recorded_to = 20/04`) và chèn V2 với hiệu lực từ 15/03 và `recorded_from = 20/04`. Ngày 01/07 có quyết định điều động, hệ thống đóng V2 trên cả hai trục và chèn V3 (hiệu lực của phân công kết thúc 30/06) cùng phân công mới. Hồ sơ đánh giá quý I đã được quyết định ngày 15/04 với `org_asof_recorded_at = 15/04`; khi xem lại, truy vấn theo mốc này vẫn thấy V1, đúng như người quyết định đã thấy.

## Lưu trữ và ràng buộc

Mỗi phiên bản là một dòng có `version_id` riêng làm khoá chính và `id` là mã thực thể ổn định. Hai cột sinh `valid_period daterange` và `recorded_period tstzrange` cho phép dùng toán tử khoảng. Ràng buộc loại trừ (exclusion constraint) cùng extension btree_gist chặn hai phiên bản của cùng một thực thể chồng lấn trên cả hai trục:

```sql
ALTER TABLE org.assignment ADD CONSTRAINT ex_assignment__bitemporal
  EXCLUDE USING gist (tenant_id WITH =, id WITH =,
                      valid_period WITH &&, recorded_period WITH &&);
```

Theo TL-01, cơ chế này áp dụng cho bốn nhóm bảng: đơn vị (`org.org_unit`, cùng `org.org_unit_relation`), phân công (`org.assignment`, TL-01 gọi là `org.position_assignment`), quan hệ báo cáo (`org.reporting_line`, bảng bổ sung) và việc giao nhiệm vụ (`task.task_assignee`, TL-01 gọi là `task.assignment`). Tài liệu giữ tên bảng của baseline.

Quy tắc ghi gồm ba điều. Thứ nhất, không bao giờ `UPDATE` cột nghiệp vụ và không bao giờ `DELETE`; vai trò `svc_org` chỉ có quyền `UPDATE (recorded_to)`. Thứ hai, mọi thay đổi là một giao dịch đóng phiên bản cũ trên trục ghi nhận bằng `recorded_to = now()` và chèn một hoặc nhiều phiên bản mới với `recorded_from = now()`; vì cùng một giao dịch dùng cùng `now()`, hai khoảng nối liền không hở, không chồng. Thứ ba, chấm dứt hiệu lực trong thế giới thực (miễn nhiệm, giải thể đơn vị) là chèn phiên bản mới có `valid_to` xác định, không phải xoá.

Mỗi thay đổi gắn với `org.org_change_order` (quyết định tổ chức, số hiệu, ngày ký, người nhập), là căn cứ pháp lý của phiên bản và được ghi vào sổ cái.

## Mẫu truy vấn

Bốn mẫu truy vấn tương ứng bốn loại câu hỏi được đóng gói thành hàm SQL trong schema `org` để mọi đội dùng nhất quán:

```sql
-- Phân công tại ngày :d như đã biết tại thời điểm :t
CREATE FUNCTION org.assignment_asof(p_tenant uuid, p_valid date, p_recorded timestamptz)
RETURNS SETOF org.assignment LANGUAGE sql STABLE AS $$
  SELECT *
    FROM org.assignment a
   WHERE a.tenant_id = p_tenant
     AND a.valid_period @> p_valid
     AND a.recorded_period @> p_recorded
$$;

-- Cây đơn vị tại ngày :d như hiện biết (đệ quy trên quan hệ cha con)
WITH RECURSIVE tree AS (
  SELECT r.child_unit_id, r.parent_unit_id, 1 AS depth
    FROM org.org_unit_relation r
   WHERE r.tenant_id = :tenant AND r.parent_unit_id = :root
     AND r.valid_period @> :d::date AND upper_inf(r.recorded_period)
  UNION ALL
  SELECT r.child_unit_id, r.parent_unit_id, t.depth + 1
    FROM org.org_unit_relation r JOIN tree t ON r.parent_unit_id = t.child_unit_id
   WHERE r.tenant_id = :tenant
     AND r.valid_period @> :d::date AND upper_inf(r.recorded_period)
)
SELECT * FROM tree;
```

Chỉ mục GiST trên (`tenant_id`, `id`, `valid_period`, `recorded_period`) do ràng buộc loại trừ tạo ra phục vụ trực tiếp các truy vấn này. Truy vấn "hiện tại" phổ biến nhất được tăng tốc thêm bằng chỉ mục một phần `WHERE upper_inf(recorded_period)` trên (`tenant_id`, `person_id`) và (`tenant_id`, `org_unit_id`).

## Bản sao một trục ở mission-core

`org_replica` trong mission-core không cần trục ghi nhận đầy đủ, vì phần lớn truy vấn của module là "hiện tại" hoặc "tại ngày cuối kỳ". Bản sao giữ trục hiệu lực và chỉ phiên bản ghi nhận mới nhất, kèm `source_version` (số phiên bản tăng dần của SVC-04) để bỏ qua sự kiện cũ đến muộn. Khi cần câu hỏi "như đã biết tại thời điểm T" (xem lại hồ sơ khi khiếu nại), MOD-04 gọi API `GET /org/v1/assignments?asOfValid=...&asOfRecorded=...` của SVC-04 có Resilience4j. Đây là truy vấn không nằm trên đường găng nên không vi phạm nguyên tắc "không gọi đồng bộ để đọc dữ liệu tổ chức trên đường găng".

## Tác động khi đính chính lùi ngày

Một đính chính có hiệu lực lùi vào kỳ đã khoá (ví dụ quyết định điều động có hiệu lực từ 15/02 nhưng ký ngày 10/04, sau khi quý I đã khoá) không làm thay đổi hồ sơ đã khoá. SVC-04 phát EVT-15 với `valid_from` trong quá khứ; consumer của MOD-04 phát hiện `valid_from` rơi vào kỳ có trạng thái `DA_KHOA` và tạo một cảnh báo đối soát cho cơ quan tổ chức cán bộ thay vì tự động sửa. Nếu cần điều chỉnh, quy trình mở lại hậu kiểm bốn mắt được dùng. Như vậy, hai trục thời gian giúp hệ thống vừa phản ánh đúng sự thật tổ chức, vừa không làm lung lay kết quả đã chốt.

# Sổ cái bất biến

## Mục tiêu và phạm vi

Sổ cái bất biến (SVC-09) là bằng chứng chống sửa lùi dữ liệu, đáp ứng tiêu chí I1 (mọi thao tác quan trọng có vết không sửa được), I2 (phát hiện được sửa đổi trái phép kể cả bởi người quản trị) và K4 (lưu giữ có thể kiểm chứng sau nhiều năm). Sổ cái không thay CSDL nghiệp vụ; nó lưu dấu vân tay (băm) của mọi sự kiện nghiệp vụ quan trọng theo thứ tự, liên kết với nhau bằng băm và được niêm phong định kỳ với dấu thời gian của bên thứ ba. Nếu ai đó sửa một dòng trong `task.task_progress` bằng quyền quản trị CSDL, dòng đó sẽ không còn khớp với băm trong sổ cái, và job đối soát (chương 17) phát hiện.

Các sự kiện được ghi sổ gồm toàn bộ EVT có cột "SVC-09" trong danh mục tiêu thụ (EVT-01 đến EVT-05, EVT-07 đến EVT-09, EVT-11 đến EVT-13, EVT-18, EVT-22, EVT-25) cùng các sự kiện kiểm toán nội bộ: đăng nhập quản trị, thay đổi quyền, xem dữ liệu C2 của người khác, xuất dữ liệu hàng loạt.

## Cấu trúc chuỗi băm

Mỗi bản ghi tính:

`entry_hash = SHA-256(prev_hash || payload_hash || metadata_hash)`

trong đó `payload_hash` là SHA-256 của payload sự kiện ở dạng chuẩn hoá JCS (RFC 8785, mọi chuỗi chuẩn hoá Unicode NFC trước khi băm để chữ tiếng Việt dựng sẵn và tổ hợp cho cùng một băm), `metadata_hash` là SHA-256 của siêu dữ liệu chuẩn hoá (tenant, chuỗi, số thứ tự, loại sự kiện, mã đối tượng, chủ thể, thời điểm xảy ra, thời điểm ghi, và `metadata_sha256` là SHA-256 hex chữ thường của cột `metadata` chuẩn hoá theo cùng quy tắc JCS và NFC), và `prev_hash` là `entry_hash` của bản ghi liền trước trong cùng chuỗi. Đưa số thứ tự và thời điểm vào `metadata_hash` ngăn việc đổi chỗ hai bản ghi hoặc sửa thời điểm mà không phá chuỗi. Đưa băm của cột `metadata` vào `metadata_hash` làm cho việc sửa trạng thái trước, sau trong cột này cũng phá chuỗi; vì chỉ băm chứ không chép nguyên văn, gói bằng chứng xuất cho cơ quan kiểm tra vẫn tính lại được chuỗi của bản ghi ngoài phạm vi mà không phải mang metadata của chúng (bổ sung 23/10/2026, xem Phụ lục).

Mỗi tenant có 64 chuỗi độc lập, chọn bằng `hash(aggregate_id) mod 64` (hàm băm là 64 bit thấp của SHA-256, cố định để tính lại được ở mọi ngôn ngữ). Chia chuỗi có ba lợi ích: ghi song song tới 64 luồng mỗi tenant; lịch sử của một đối tượng nằm gọn trong một chuỗi; và kiểm tra lại một chuỗi không cần đọc cả 63 chuỗi kia. Bản ghi đầu của một chuỗi mới có `prev_hash` là 32 byte 0. Khi tenant được chuyển sang cell khác, chuỗi ở cell đích không bắt đầu lại mà nối tiếp: `chain_head` ở cell đích được khởi tạo bằng `entry_hash` cuối của cell nguồn, và bản ghi đầu tiên ở cell đích có loại `TENANT_MOVED_IN` mang mã niêm phong cuối cùng của cell nguồn (thống nhất với TL-01).

![Hình 8.1. Luồng ghi sổ cái, niêm phong Merkle và neo ra ngoài](db-so-cai.png){width=9cm}

Hình 8.1 mô tả đường đi của một sự kiện từ module nguồn tới sổ cái. Module ghi outbox, Debezium đẩy lên topic nghiệp vụ. Bộ thu (ledger-ingester) của SVC-09 tiêu thụ các topic nghiệp vụ được đăng ký và chuyển tiếp vào topic hệ thống `sys.ledger.append.v1` với khoá `tenant:partition`; nhờ khoá này, mọi bản ghi của một chuỗi đi vào cùng một phân vùng Kafka và được một luồng ghi duy nhất xử lý theo thứ tự. Luồng ghi khoá dòng `ledger.chain_head` bằng `SELECT ... FOR UPDATE`, tính băm, chèn lô bản ghi, cập nhật đầu chuỗi và cam kết. Job niêm phong đọc các bản ghi nằm sau khoảng đã niêm phong của từng chuỗi, dựng cây Merkle hai mức, ký, xin token TSA, ghi `cell_seal` và `merkle_seal` rồi phát EVT-19; SVC-13 gửi bản neo ra ngoài cell.

## Ghi một bản ghi

```sql
BEGIN;
SELECT last_seq_no, last_hash
  FROM ledger.chain_head
 WHERE tenant_id = :t AND chain_partition = :p
   FOR UPDATE;
-- ứng dụng tính entry_hash cho lô N bản ghi, seq_no = last_seq_no + 1 .. + N
INSERT INTO ledger.ledger_entry (...) VALUES (...), (...), ...;
UPDATE ledger.chain_head
   SET last_seq_no = :new_seq, last_hash = :new_hash, updated_at = now()
 WHERE tenant_id = :t AND chain_partition = :p;
COMMIT;
```

Vì chỉ một luồng tiêu thụ một phân vùng Kafka, khoá dòng hầu như không tranh chấp; nó là lớp an toàn khi xảy ra cân bằng lại nhóm tiêu thụ (rebalance) và hai luồng tạm thời cùng giữ một phân vùng. Ràng buộc duy nhất `uq_ledger_entry__event` làm việc ghi lại cùng một sự kiện (at-least-once) trở thành lỗi trùng được bỏ qua. Ghi theo lô 200 bản ghi đạt khoảng 25.000 bản ghi mỗi giây trên cụm `pg-ledger` ở CELL-HCM, dư nhiều so với đỉnh 3.000 bản ghi mỗi giây ước tính vào ngày chốt kỳ.

## Niêm phong Merkle và dấu thời gian

Cuối mỗi cửa sổ (một giờ, rút xuống 5 phút trong 3 ngày cuối kỳ), job niêm phong đọc với từng tenant và từng chuỗi các bản ghi có `seq_no` lớn hơn số thứ tự cuối đã niêm phong (lấy từ `merkle_seal.chain_ranges` gần nhất), sắp theo (`chain_partition`, `seq_no`), và dựng cây Merkle mức tenant với lá là `SHA-256(0x01 || entry_hash)` và nút là `SHA-256(0x02 || trái || phải)`. Tiền tố miền khác nhau cho lá và nút chống tấn công cây thứ hai (ADR-26 của TL-01). Gốc của mọi tenant có bản ghi mới lại làm lá của cây mức cell. Gốc cell cùng `prev_cell_root` được ký một lần bằng khoá của SVC-09 trong HSM và lấy dấu thời gian một lần từ dịch vụ cấp dấu thời gian (TSA) theo RFC 3161, nên chi phí ký và TSA không tăng theo số tenant. Bản ghi `ledger_entry` không bị đụng tới: liên kết giữa bản ghi và niêm phong suy ra từ khoảng `seq` lưu ở `merkle_seal`. Hằng ngày, gốc cell cuối ngày được neo ra ít nhất hai bên độc lập: một cell khác (cell nhận lưu vào sổ cái của nó như một sự kiện thường) và kho lưu trữ của Văn phòng Trung ương hoặc một TSA độc lập thứ hai; bản neo đồng thời được ghi vào bucket Object Lock chế độ compliance.

Bằng chứng tồn tại (inclusion proof) của một bản ghi gồm đường đi Merkle từ lá tới gốc tenant, từ gốc tenant tới gốc cell, cộng chữ ký, token TSA và biên nhận neo, có thể xuất cho cơ quan kiểm tra để họ tự xác minh bằng công cụ mã nguồn mở mà không cần tin hệ thống.

## Lưu trữ dài hạn

Phân vùng tháng (mức hai, bên dưới phân vùng tenant) của `ledger_entry` được giữ trong PostgreSQL 24 tháng. Sau đó job lưu trữ xuất phân vùng ra tệp Parquet (nén zstd, sắp theo chuỗi và số thứ tự), ghi vào bucket `ledger-archive` có Object Lock compliance với hạn bằng thời hạn lưu giữ dài nhất của dữ liệu liên quan (chương 16), đọc lại tệp và kiểm tra chuỗi băm khớp hoàn toàn với PostgreSQL và với các niêm phong, ghi `ledger.archive_segment`, rồi mới `DETACH PARTITION` và xoá. Truy vấn lịch sử cũ hơn 24 tháng dùng DuckDB đọc Parquet trực tiếp trên Ceph qua giao diện S3, chậm hơn nhưng hiếm khi cần.

## Kiểm tra toàn vẹn

Bốn loại kiểm tra được lập lịch và ghi kết quả vào `ledger.verify_run`. Kiểm tra đầu chuỗi chạy mỗi 15 phút, so `chain_head` với bản ghi cuối. Kiểm tra niêm phong chạy sau mỗi lần niêm phong, tính lại gốc tenant và gốc cell của cửa sổ trước và kiểm chữ ký, token TSA. Kiểm tra toàn bộ chạy hằng tuần theo vòng, mỗi tuần một phần tư số tenant, tính lại toàn bộ chuỗi trong PostgreSQL. Kiểm tra lưu trữ chạy hằng quý, chọn ngẫu nhiên 5% tệp Parquet. Bất kỳ sai lệch nào đều tạo cảnh báo mức nghiêm trọng gửi trung tâm điều hành an ninh (SOC) và khoá thao tác quản trị trên tenant liên quan đến khi điều tra xong.

# Mã hoá dữ liệu

## Ba lớp mã hoá

Dữ liệu được bảo vệ bằng ba lớp độc lập. Lớp lưu trữ mã hoá toàn bộ ổ đĩa (LUKS trên node CSDL, mã hoá OSD của Ceph) chống mất thiết bị vật lý. Lớp truyền tải dùng TLS 1.3 cho mọi kết nối, kể cả trong cụm (PostgreSQL `ssl_min_protocol_version = TLSv1.3`, mTLS giữa pod qua service mesh). Lớp ứng dụng mã hoá từng trường C2 trước khi ghi vào CSDL, nên người quản trị CSDL, bản sao lưu, WAL, bản sao logic và CDC đều chỉ thấy bản mã. Chương này tập trung vào lớp thứ ba.

## Mã hoá phong bì cho cột C2

Mỗi cột C2 được mã hoá bằng AES-256-GCM với khoá dữ liệu (DEK). DEK được bọc (wrap) bằng khoá mã hoá khoá (KEK) của tenant nằm trong OpenBao Transit, và KEK của tenant được bảo vệ bởi khoá gốc trong HSM. Mỗi CSDL có bảng `keyring.dek` lưu DEK đã bọc; ứng dụng gọi OpenBao để mở bọc DEK một lần rồi giữ trong bộ nhớ đệm tối đa 15 phút, không bao giờ ghi DEK rõ ra đĩa.

Định dạng bản mã trong cột `_enc` là `version(1 byte) || dek_ref(16 byte) || nonce(12 byte) || ciphertext || tag(16 byte)`. Dữ liệu liên kết bổ sung (AAD) của AES-GCM gồm `tenant_id`, tên bảng, tên cột và mã dòng; nhờ đó bản mã của dòng này không thể chép sang dòng khác hoặc tenant khác mà vẫn giải mã được.

![Hình 9.1. Phân cấp khoá và luồng mã hoá trường](db-ma-hoa.png){width=16cm}

Hình 9.1 cho thấy phân cấp ba tầng HSM, KEK, DEK và luồng ghi một trường C2: ứng dụng lấy DEK hiện hành của tenant từ bộ nhớ đệm (hoặc mở bọc qua OpenBao), mã hoá, ghi cột `_enc` và `dek_id`, đồng thời tính chỉ mục mù nếu cột cần tìm kiếm khớp chính xác.

Theo ADR-30 của TL-01, mỗi DEK ứng với một tổ hợp tenant, tháng và mục đích (bảng `keyring.dek`): đầu mỗi tháng, job khoá sinh DEK mới cho từng mục đích đang dùng, DEK tháng trước chuyển sang `DECRYPT_ONLY` và vẫn được giữ để giải mã dữ liệu cũ. Việc xoay vòng theo tháng vì vậy diễn ra tự nhiên mà không cần mã hoá lại. Chỉ khi có sự cố lộ khoá, job mã hoá lại mới chạy nền, đọc dòng có `dek_id` cũ, giải mã và mã hoá lại bằng DEK mới theo lô nhỏ ngoài giờ cao điểm. KEK được xoay hằng năm trong OpenBao (tạo phiên bản khoá mới, bọc lại DEK mà không cần đụng dữ liệu).

## Chỉ mục mù

Một số cột C2 cần tra cứu khớp chính xác, ví dụ số điện thoại, số CCCD trong `org.person_contact`. Cột chỉ mục mù có hậu tố `_bidx` lưu `HMAC-SHA256(khoá_bidx_tenant, giá_trị_chuẩn_hoá)` cắt còn 16 byte; truy vấn tính HMAC của giá trị cần tìm rồi so khớp. Khoá HMAC là khoá riêng của từng tenant và từng cột, khác DEK, nằm trong OpenBao. Chỉ mục mù chỉ hỗ trợ so khớp bằng, không hỗ trợ tìm theo tiền tố hay khoảng; đó là đánh đổi có chủ ý.

## Xoá bằng mật mã

Khi tenant chấm dứt hợp đồng hoặc khi hết thời hạn lưu giữ của một nhóm dữ liệu C2, hệ thống huỷ DEK tương ứng (đặt `DESTROYED` trong `keyring.dek` và vô hiệu hoá phiên bản khoá trong OpenBao). Bản mã còn nằm trong bản sao lưu, WAL lưu trữ và bản sao trên Ceph trở thành không đọc được mà không cần tìm và xoá từng bản. Đây là cách duy nhất khả thi để xoá dữ liệu khỏi bản sao lưu bất biến.

## Mã hoá đầu cuối cho nội dung mật

Nhiệm vụ có nội dung mật (`task.task_secret_content`) và minh chứng mật dùng mã hoá đầu cuối (E2EE): nội dung được mã hoá ở trình duyệt hoặc ứng dụng di động bằng khoá nội dung ngẫu nhiên, khoá này được bọc bằng khoá công khai của từng người được phép xem (`org.person_public_key`) và lưu ở `e2ee_key_wrap`. Theo ADR-27 của TL-01, khoá riêng của người dùng là khoá ECDH P-256 sinh trên thiết bị và không trích xuất được; danh bạ khoá công khai (`org.person_public_key`) được ký và mọi thay đổi được ghi sổ cái để chống tráo khoá. Để không mất dữ liệu khi người dùng mất thiết bị, nội dung còn được bọc thêm bằng khoá phục hồi của tổ chức, khoá này được chia theo sơ đồ Shamir 3 trên 5 cho năm người giữ. Máy chủ không có khả năng giải mã. Hệ quả là nội dung mật không thể tìm kiếm, không vào RAG, không vào ClickHouse; tính điểm chỉ dùng các trường siêu dữ liệu không mật (tiến độ, trạng thái, kết quả nghiệm thu).

## Bảng tổng hợp xử lý theo phân loại

Bảng sau tổng hợp cách mỗi mức phân loại được đối xử ở từng điểm đến của dữ liệu, là căn cứ để sinh cấu hình CDC và kiểm tra trong CI.

| Phân loại | PostgreSQL | Kafka (sự kiện) | ClickHouse | OpenSearch | Valkey | Nhật ký |
|---|---|---|---|---|---|---|
| C0 | Rõ | Rõ | Rõ | Rõ | Rõ | Rõ |
| C1 | Rõ, RLS | Rõ, ACL topic | Rõ, RLS phía Cube | Rõ, lọc theo tenant | Rõ, TTL | Rõ |
| C2 | `_enc` AES-GCM | Chỉ mã, không nội dung | Loại bỏ hoặc băm giả danh | Không đưa vào | Không lưu | Che |
| C3 | E2EE, máy chủ không giải mã | Không | Không | Không | Không | Không |

Bảng cho thấy dữ liệu C2 không bao giờ rời PostgreSQL ở dạng rõ, và C3 không bao giờ rõ ở bất kỳ đâu phía máy chủ. Cấu hình connector CDC sang ClickHouse và indexer OpenSearch được sinh tự động từ chú thích phân loại của cột, nên một cột mới được gắn C2 sẽ tự động bị loại khỏi phân tích mà không cần sửa tay cấu hình.

# Cơ chế đồng bộ dữ liệu

## Tổng quan

Vì mỗi service sở hữu dữ liệu riêng, dữ liệu phải được đưa từ nơi sở hữu tới nơi cần dùng bằng các cơ chế đồng bộ có kiểm soát. Baseline mục 8 xác định tám cơ chế; chương này chi tiết hoá từng cơ chế ở mức đủ để các đội triển khai giống nhau: cấu hình, bảng, bảo đảm giao nhận, xử lý lỗi và chỉ số giám sát. Bảng sau tóm tắt các cơ chế và bảo đảm của chúng.

| Mục | Cơ chế | Dùng cho | Bảo đảm | Độ trễ mục tiêu (p99) |
|---|---|---|---|---|
| 10.2 | Outbox và Debezium | Mọi sự kiện nghiệp vụ | Ít nhất một lần, có thứ tự theo khoá | 2 giây |
| 10.3 | Inbox idempotent | Mọi consumer | Hiệu lực đúng một lần | Không áp dụng |
| 10.4 | Chuyển trạng thái qua sự kiện (ECST) | Dữ liệu tổ chức sang `org_replica` | Hội tụ cuối cùng, phiên bản đơn điệu | 5 giây |
| 10.5 | CDC phân tích sang ClickHouse | Báo cáo, dashboard, phát hiện bất thường | Hội tụ cuối cùng | 60 giây |
| 10.6 | Đánh chỉ mục tìm kiếm | OpenSearch | Hội tụ cuối cùng | 10 giây |
| 10.7 | Saga điều phối | Chốt kỳ, mở lại, tốt nghiệp tenant | Hoàn tất hoặc bù trừ | Phút |
| 10.8 | REST có Resilience4j | Truy vấn tức thời ngoài đường găng | Đồng bộ, có thời hạn chờ 3 giây | 300 mili giây |
| 10.9 | Tích hợp hệ ngoài | vOffice, CSDL CBCCVC và các hệ khác | Ít nhất một lần, đối soát | Phút |
| 10.10 | Giữa các cell | Danh bạ tenant, phân tích quốc gia, điều động | Hội tụ cuối cùng | Phút đến giờ |

Nguyên tắc chung là không có cơ chế nào dựa vào giao dịch phân tán (2PC/XA). Tính nhất quán giữa các service đạt được bằng sự kết hợp của ghi nguyên tử cục bộ (outbox), tiêu thụ idempotent (inbox), thứ tự theo khoá và đối soát định kỳ (chương 17).

## Transactional Outbox và Debezium

### Vấn đề cần giải quyết

Một module vừa phải ghi dữ liệu vào CSDL, vừa phải phát sự kiện lên Kafka. Nếu ghi CSDL rồi gửi Kafka, tiến trình có thể chết giữa hai bước làm mất sự kiện; nếu gửi Kafka trước, giao dịch CSDL có thể huỷ làm sự kiện "ma". Mẫu Transactional Outbox giải quyết bằng cách ghi sự kiện vào bảng `outbox_event` trong cùng giao dịch với dữ liệu, rồi một tiến trình riêng đọc bảng này và đẩy lên Kafka.

### Cách hiện thực

Ứng dụng Spring Modulith phát sự kiện ứng dụng; một bộ chuyển đổi chung (`OutboxEventPublisher` trong thư viện nền tảng) tuần tự hoá payload sang Avro theo lược đồ đã đăng ký ở Apicurio, rồi chèn một dòng vào `<schema>.outbox_event` trong cùng giao dịch JPA. Debezium PostgreSQL connector (chạy trên Kafka Connect, mỗi CSDL một connector, một slot sao chép logic với plugin `pgoutput`) đọc WAL, chỉ lấy các bảng `*.outbox_event` qua publication, và dùng Outbox Event Router để định tuyến.

![Hình 10.1. Luồng Transactional Outbox qua Debezium tới Kafka](db-outbox-debezium.png){width=12cm}

Hình 10.1 cho thấy giao dịch nghiệp vụ ghi đồng thời bảng nghiệp vụ và bảng outbox; WAL là nguồn duy nhất mà Debezium đọc; Outbox Event Router lấy cột `topic` làm tên topic, cột `event_key` làm khoá thông điệp, cột `payload` làm giá trị và các cột còn lại làm tiêu đề Kafka.

Cấu hình connector mẫu cho CSDL dữ liệu tenant `td_hcm`:

```json
{
  "name": "outbox-td_hcm",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "plugin.name": "pgoutput",
    "database.hostname": "pg-biz-rw.cell-hcm.svc",
    "database.dbname": "td_hcm",
    "database.user": "cdc_td_hcm",
    "slot.name": "dbz_outbox_td_hcm",
    "publication.name": "pub_outbox",
    "publication.autocreate.mode": "disabled",
    "snapshot.mode": "never",
    "table.include.list": "okr.outbox_event,task.outbox_event,product.outbox_event,appraisal.outbox_event,policy.outbox_event,dossier.outbox_event,oversight.outbox_event,config.outbox_event,org_replica.outbox_event,scoring.outbox_event,docgen.outbox_event",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.by.field": "topic",
    "transforms.outbox.route.topic.replacement": "${routedByValue}",
    "transforms.outbox.table.field.event.id": "event_id",
    "transforms.outbox.table.field.event.key": "event_key",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.table.fields.additional.placement": "event_type:header:ce_type,tenant_id:header:ce_tenantid,trace_parent:header:traceparent,classification:header:ce_classification,schema_id:header:apicurio.value.globalId",
    "value.converter": "org.apache.kafka.connect.converters.ByteArrayConverter",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "heartbeat.interval.ms": "10000",
    "heartbeat.action.query": "INSERT INTO platform.dbz_heartbeat (id, ts) VALUES (1, now()) ON CONFLICT (id) DO UPDATE SET ts = now()",
    "producer.override.enable.idempotence": "true",
    "producer.override.acks": "all"
  }
}
```

TL-01 nêu mỗi schema có connector và slot riêng. TL-03 giữ một connector outbox cho mỗi CSDL, liệt kê mọi bảng `outbox_event` của các schema trong `table.include.list`, vì mỗi slot sao chép logic phải giải mã toàn bộ WAL của cả CSDL: với 13 schema trong một CSDL dữ liệu tenant, 13 slot sẽ giải mã cùng một WAL 13 lần, và ở cell bridge chín CSDL sẽ cần hơn một trăm slot. Ranh giới sở hữu vẫn giữ nguyên vì mỗi schema có bảng outbox, topic và ACL riêng; khi một module cần nhịp phát hành độc lập (ví dụ tách thành microservice), nó được tách sang connector riêng. Điểm này được ghi trong tệp ghi chú để người tổng hợp quyết định.

Có bốn điểm cấu hình đáng chú ý. Thứ nhất, `snapshot.mode=never` vì bảng outbox chỉ chứa sự kiện mới; chụp nhanh bảng khi khởi động sẽ phát lại sự kiện cũ. Thứ hai, payload đã là Avro nhị phân do ứng dụng tuần tự hoá, nên dùng `ByteArrayConverter` để Debezium không tuần tự hoá lại. Thứ ba, `heartbeat.action.query` ghi định kỳ vào bảng nhịp tim để slot tiến lên kể cả khi CSDL không có sự kiện, tránh WAL bị giữ lại phình ổ đĩa. Thứ tư, producer bật idempotence với `acks=all` để không sinh trùng do thử lại ở tầng Kafka.

### Dọn bảng outbox

Dòng outbox không bị xoá bởi Debezium. Bảng được phân vùng theo ngày bằng pg_partman (`p_retention = '3 days'`), job bảo trì tách và xoá phân vùng cũ; xoá phân vùng không sinh WAL cho từng dòng nên không làm Debezium phải đọc thêm. Ba ngày đủ để phát lại thủ công khi connector gặp sự cố kéo dài.

### Thứ tự và trùng lặp

Khoá thông điệp `tenantId:aggregateId` bảo đảm mọi sự kiện của một đối tượng vào cùng một phân vùng Kafka và giữ thứ tự ghi WAL. Sự kiện trùng có thể xuất hiện khi connector khởi động lại trước khi kịp ghi offset; consumer xử lý trùng bằng inbox. Mỗi sự kiện còn mang `aggregateVersion` (bằng `row_version` của đối tượng sau thay đổi) để consumer phát hiện sự kiện đến sai thứ tự trong các tình huống hiếm, như khi định tuyến lại topic.

### Giám sát

Các chỉ số bắt buộc gồm độ trễ slot (`pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)`), cảnh báo khi vượt 1 GB hoặc khi slot không hoạt động quá 5 phút; `MilliSecondsBehindSource` của Debezium, cảnh báo khi vượt 10 giây; số dòng outbox chưa được đọc ước tính theo phân vùng ngày. Tham số `max_slot_wal_keep_size = 64GB` được đặt để slot hỏng không làm đầy ổ đĩa chính; khi slot bị vô hiệu do vượt ngưỡng, quy trình khôi phục là tạo lại slot và phát lại từ bảng outbox theo dải thời gian bằng công cụ phát lại của nền tảng.

## Inbox idempotent

Mỗi consumer ghi `(consumer, event_id)` vào `<schema>.inbox_processed` trong cùng giao dịch với tác động nghiệp vụ của sự kiện. Nếu sự kiện đã được xử lý, câu lệnh chèn vi phạm khoá chính và consumer bỏ qua sự kiện, xác nhận offset.

```sql
INSERT INTO appraisal.inbox_processed (consumer, event_id, event_type, processed_at)
VALUES ('appraisal.score-listener', :event_id, 'scoring.score.computed.v1', now())
ON CONFLICT (consumer, event_id) DO NOTHING
RETURNING event_id;
-- không có dòng trả về: sự kiện đã xử lý, bỏ qua
```

![Hình 10.2. Consumer idempotent với inbox và kiểm tra phiên bản đối tượng](db-inbox.png){width=14cm}

Hình 10.2 trình bày luồng xử lý của consumer: nhận thông điệp, mở giao dịch, đặt ngữ cảnh tenant, chèn inbox, kiểm tra phiên bản đối tượng trong `inbox_aggregate_version`, áp dụng thay đổi, cam kết, rồi mới xác nhận offset Kafka. Nếu tiến trình chết sau cam kết nhưng trước xác nhận offset, sự kiện sẽ được giao lại và bị inbox chặn.

Bảng `inbox_aggregate_version (consumer, aggregate_type, aggregate_id, last_version)` xử lý sự kiện sai thứ tự: sự kiện có `aggregateVersion` nhỏ hơn hoặc bằng `last_version` bị bỏ qua (đã có trạng thái mới hơn). Bảng inbox giữ 14 ngày, lớn hơn thời gian giữ của topic Kafka (7 ngày), nên mọi sự kiện có thể được giao lại đều còn dấu vết.

Lỗi xử lý được phân hai loại. Lỗi tạm thời (mất kết nối CSDL, hết thời gian chờ) được thử lại tại chỗ ba lần với độ trễ tăng dần, sau đó chuyển sang topic thử lại `<topic>.retry.<consumer>` với độ trễ 1, 5 và 30 phút. Lỗi vĩnh viễn (lược đồ không giải mã được, vi phạm bất biến nghiệp vụ) chuyển ngay sang topic `<topic>.dlq.<consumer>` và tạo cảnh báo; không bao giờ bỏ qua im lặng.

## Event-carried State Transfer cho dữ liệu tổ chức

SVC-04 phát EVT-14, EVT-15, EVT-16 mang toàn bộ trạng thái mới của đơn vị, phân công hoặc nhân sự (không chỉ phần thay đổi). Consumer `org_replica` trong mission-core và trong các service cần dữ liệu tổ chức (SVC-02 cho nhóm quyền, SVC-15 cho chiều phân tích) ghi đè bản sao của mình bằng upsert có điều kiện phiên bản:

```sql
INSERT INTO org_replica.assignment_v AS r
  (tenant_id, id, valid_from, valid_to, person_id, org_unit_id, position_id,
   job_title_id, assignment_kind, is_primary, source_version, replicated_at)
VALUES (:tenant, :id, :vf, :vt, :person, :unit, :pos, :title, :kind, :primary, :ver, now())
ON CONFLICT (tenant_id, id, valid_from) DO UPDATE
   SET valid_to = EXCLUDED.valid_to, person_id = EXCLUDED.person_id,
       org_unit_id = EXCLUDED.org_unit_id, position_id = EXCLUDED.position_id,
       job_title_id = EXCLUDED.job_title_id, assignment_kind = EXCLUDED.assignment_kind,
       is_primary = EXCLUDED.is_primary, source_version = EXCLUDED.source_version,
       replicated_at = now()
 WHERE r.source_version < EXCLUDED.source_version;
```

![Hình 10.3. Nhân bản dữ liệu tổ chức bằng Event-carried State Transfer](db-ecst.png){width=16cm}

Hình 10.3 cho thấy một thay đổi tổ chức từ SCIM hoặc màn hình quản trị đi qua SVC-04, được ghi hai trục thời gian, phát qua outbox, và được ba consumer độc lập nhân bản; mỗi consumer cập nhật bảng closure của mình để truy vấn cây đơn vị không cần đệ quy.

Topic `org.*` được cấu hình `cleanup.policy=compact,delete` với thời gian giữ 30 ngày. Khi thêm một consumer mới hoặc dựng lại bản sao, SVC-04 cung cấp lệnh phát lại toàn bộ (`org-republish`) đẩy trạng thái hiện hành của mọi thực thể của một tenant lên topic khởi tạo riêng `org.bootstrap.v1`, consumer đọc topic này rồi chuyển sang topic thường. Bản sao được đối soát hằng đêm theo bucket băm (chương 17).

## CDC phân tích sang ClickHouse

### Đường đi dữ liệu

SVC-15 cần dữ liệu nghiệp vụ gần thời gian thực để dựng dashboard và chạy tám dấu hiệu bất thường, nhưng không được truy vấn CSDL nghiệp vụ. Một connector Debezium thứ hai trên mỗi CSDL (slot riêng `dbz_cdc_<db>`, publication riêng `pub_analytics` liệt kê các bảng cần phân tích) đọc thay đổi dòng của bảng nghiệp vụ, áp dụng các phép biến đổi rồi đẩy lên topic `cdc.<db>.<schema>.<table>`. ClickHouse tiêu thụ qua Kafka engine và materialized view, ghi vào bảng ReplacingMergeTree.

![Hình 10.4. Đường CDC phân tích từ PostgreSQL tới ClickHouse](db-cdc-clickhouse.png){width=8cm}

Hình 10.4 trình bày chuỗi biến đổi. `ExtractNewRecordState` làm phẳng bản ghi Debezium, thêm `__op`, `__lsn`, `__source_ts_ms` và đánh dấu xoá mềm `__deleted`. `column.exclude.list` loại bỏ mọi cột C2 và C3 (cột `_enc`, `_bidx` và cột có chú thích `cls` là C2 mà không mã hoá như `prospect_group.group_code`). `column.mask.hash.SHA-256.with.salt.<muối_tenant>` thay mã người (`person_id`, `actor_id`, `created_by`) bằng băm có muối cho các bảng mà dashboard cấp trên dùng ở dạng tổng hợp. Danh sách hai tham số này không viết tay mà do công cụ `cdc-config-gen` sinh ra từ `COMMENT ON COLUMN` trong CI, đi kèm mỗi lần phát hành schema.

### Bảng đích và mô hình sao

```sql
CREATE TABLE analytics_hcm.fact_task_progress ON CLUSTER cell_hcm
(
  tenant_id        UUID,
  period_bucket    Int32,
  id               UUID,
  task_id          UUID,
  person_key       FixedString(32),   -- băm giả danh
  org_unit_id      UUID,
  progress_pct     Decimal(6,3),
  status           LowCardinality(String),
  reported_at      DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _version         UInt64,             -- __lsn
  _is_deleted      UInt8
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY period_bucket
ORDER BY (tenant_id, org_unit_id, task_id, id);
```

Dữ liệu phân tích được tổ chức theo mô hình sao: bảng sự kiện (`fact_task`, `fact_task_progress`, `fact_evidence`, `fact_score`, `fact_rating`, `fact_okr_checkin`) và bảng chiều (`dim_org_unit` theo kiểu thay đổi chậm loại 2 lấy từ hai trục thời gian, `dim_period`, `dim_subject_group`, `dim_policy_version`). Hình 14.1 ở chương 14 trình bày mô hình sao. Truy vấn dashboard dùng `FINAL` có giới hạn phân vùng hoặc truy vấn qua Cube với pre-aggregation để tránh chi phí gộp dòng lúc đọc.

### Phân quyền phía đọc

ClickHouse không có RLS theo người dùng cuối; phân quyền được thực thi ở Cube (lớp ngữ nghĩa) bằng `queryRewrite` thêm điều kiện `tenant_id` và tập đơn vị mà người dùng được xem, lấy từ JWT. Người dùng không truy vấn ClickHouse trực tiếp; Superset nhúng chỉ nói chuyện với Cube. Ngoài ra ClickHouse dùng row policy theo `tenant_id` cho tài khoản kỹ thuật của Cube ở cell pool như lớp phòng thủ thứ hai.

## Đánh chỉ mục tìm kiếm OpenSearch

SVC-16 có indexer là consumer Kafka của EVT-04, EVT-06, EVT-24 và các sự kiện danh mục. Indexer không đọc CSDL; nó dựng tài liệu tìm kiếm từ payload sự kiện (chỉ các trường C0, C1) và gọi `_bulk` với `version_type=external` và `version = aggregateVersion`, nên sự kiện đến muộn không ghi đè tài liệu mới hơn.

![Hình 10.5. Luồng đánh chỉ mục OpenSearch](db-opensearch.png){width=14cm}

Hình 10.5 cho thấy indexer đọc sự kiện, lọc trường theo phân loại, chuẩn hoá tiếng Việt và ghi theo lô vào chỉ mục của tenant. Chỉ mục được đặt tên `t_<tenant>_task_v<n>` ở cell silo và bridge; ở cell pool dùng chỉ mục chung `pool_task_v<n>` với định tuyến (`_routing`) theo `tenant_id` và bí danh lọc theo tenant. Bộ phân tích tiếng Việt dùng `icu_tokenizer` cộng `icu_folding` để tìm "giao duc" khớp "giáo dục", kèm từ điển đồng nghĩa nghiệp vụ (KPI, chỉ tiêu; nhiệm vụ, công việc). Dựng lại chỉ mục khi đổi ánh xạ dùng chỉ mục mới và hoán đổi bí danh, nguồn dữ liệu là lệnh phát lại sự kiện của module sở hữu.

## Saga điều phối chốt kỳ

### Các bước

Chốt kỳ là thao tác liên quan nhiều module và service, không thể làm trong một giao dịch. SVC-06 điều phối bằng saga được lưu ở `flowable.saga_instance` và `flowable.saga_step`; lệnh khởi chạy là một dòng `config.period_lock_order` đã được duyệt, và tiến trình từng bước được chiếu sang `config.period_lock_step` để màn hình SCR-0801 hiển thị. Quy trình có sáu bước:

| Bước | Hành động | Service | Bù trừ |
|---|---|---|---|
| 1 | Đặt kỳ `DANG_KHOA`, chặn ghi mới | MOD-08 | Đặt lại `MO`, phát EVT-26 KyDaHuyKhoa |
| 2 | Chờ hết thời hạn bộ đệm trạng thái kỳ trong pod (30 giây) và xả hết sự kiện đang bay của kỳ (độ trễ outbox về 0 và inbox các consumer đã tới điểm đánh dấu) | SVC-06 | Không cần |
| 3 | Đặt kỳ `DA_KHOA`, phát EVT-09 | MOD-08 | Không có: điểm không quay lại |
| 4 | Chạy tác vụ tính điểm chốt kỳ, phát EVT-10 | SVC-07 | Chạy lại (idempotent theo `input_hash`) |
| 5 | Tạo ảnh chụp điểm `CHOT_KY` cho mọi hồ sơ | MOD-04 | Chạy lại |
| 6 | Niêm phong sổ cái đột xuất cho kỳ | SVC-09 | Chạy lại |

![Hình 10.6. Saga chốt kỳ và điểm không quay lại](db-saga.png){width=11cm}

Hình 10.6 thể hiện các bước và nhánh bù trừ. Bước 2 dùng một sự kiện đánh dấu (marker): SVC-06 yêu cầu từng module ghi một dòng outbox đặc biệt `period.drain.marker`; khi mọi consumer liên quan báo đã xử lý marker, chắc chắn mọi sự kiện ghi trước đó đã được xử lý, vì chúng nằm trước marker trong cùng phân vùng. Bước 3 là điểm không quay lại: trước bước này, bù trừ đưa kỳ từ `DANG_KHOA` về `MO` và phát EVT-26 (KyDaHuyKhoa, `config.period.unlocked.v1`) để MOD-01, MOD-02, MOD-03 mở ghi lại và SVC-07 đánh dấu lô điểm tạm là bị thay thế; sau khi EVT-09 đã phát, kỳ không được mở lại bằng bù trừ mà chỉ bằng quy trình mở lại hậu kiểm bốn mắt (EVT-22). Các bước 4 đến 6 vì vậy chỉ được thử lại, không bù trừ; nếu thất bại liên tục, saga chuyển `LOI_CAN_XU_LY` và đội vận hành can thiệp.

Saga mở lại hậu kiểm đi ngược lại: đặt kỳ `MO_LAI_HAU_KIEM` (EVT-22) cho phạm vi được duyệt, cho phép ghi có kèm mã yêu cầu, khi hoàn tất chạy tính lại kiểu `TINH_LAI_HAU_KIEM`, tạo ảnh chụp mới thay thế ảnh chụp cũ, khoá lại và niêm phong.

## REST đồng bộ có Resilience4j

Một số truy vấn cần dữ liệu tức thời của service khác mà không nằm trên đường găng, như xem tổ chức "như đã biết tại thời điểm T" hay lấy tệp đã ký từ SVC-10. Các lời gọi này đi qua client sinh từ OpenAPI, bọc bởi Resilience4j với thời hạn chờ 3 giây, ngắt mạch khi tỷ lệ lỗi vượt 50% trong cửa sổ 20 lời gọi, bulkhead 20 lời gọi đồng thời mỗi pod, thử lại tối đa hai lần chỉ với phương thức `GET`. Nguyên tắc là không có lời gọi REST đồng bộ nào được phép làm thay đổi dữ liệu ở service đích trong cùng luồng xử lý với một giao dịch CSDL cục bộ đang mở; mọi thay đổi chéo service đi qua sự kiện.

## Tích hợp hệ ngoài qua SVC-13

SVC-13 có hai chiều. Chiều vào nhận văn bản chỉ đạo từ vOffice, iOffice, EdXML/X-Road (đẩy) và kéo dữ liệu nhân sự từ CSDL CBCCVC, CSDL đảng viên, HRM (kéo theo `integ.sync_cursor`). Thông điệp vào được lưu nguyên văn ở `integ.inbox_message`, chuyển đổi sang mô hình dữ liệu chuẩn, ánh xạ mã qua `integ.mapping_xref`, rồi phát sự kiện nội bộ (EVT-24, hoặc lệnh tới SVC-04 cho nhân sự). Chiều ra tiêu thụ EVT-12, EVT-19, chuyển thành định dạng của hệ nhận, lưu `integ.outbound_message` và gửi với thử lại theo luỹ thừa; thông điệp chỉ được coi là xong khi hệ nhận xác nhận.

![Hình 10.7. Luồng tích hợp hai chiều qua lớp chống hư hỏng](db-integ.png){width=16cm}

Hình 10.7 cho thấy lớp chống hư hỏng tách mô hình của hệ ngoài khỏi mô hình nội bộ: không một bảng nội bộ nào chứa mã của hệ ngoài, mọi mã ngoài chỉ nằm ở `integ.mapping_xref`. Khi hệ ngoài đổi định dạng, chỉ bộ chuyển đổi trong SVC-13 phải sửa.

## Đồng bộ giữa các cell

Các cell không chia sẻ CSDL. Có ba luồng dữ liệu được phép giữa các cell.

Luồng thứ nhất là danh bạ tenant toàn cục: SVC-03 ở vùng toàn cục giữ `registry`; mỗi cell có bản sao chỉ đọc nhận qua sự kiện `registry.tenant.changed.v1`, phục vụ định tuyến tại cổng.

Luồng thứ hai là phân tích quốc gia: MirrorMaker 2 sao chép các topic tổng hợp đã ẩn danh (`agg.*`, sinh từ ClickHouse của cell bằng job tổng hợp theo đơn vị cấp tỉnh, không có mã người) sang Kafka của kho phân tích quốc gia. Không topic nghiệp vụ chi tiết nào được sao chép.

Luồng thứ ba là điều động cán bộ liên cell. Khi một cán bộ chuyển từ tenant ở CELL-HN sang tenant ở CELL-HCM, SVC-13 của cell gửi đóng gói hồ sơ cần mang theo (kết quả xếp loại các kỳ, hồ sơ số đã ký, nhận xét được phép chuyển) thành gói mã hoá, ký và bọc khoá bằng khoá công khai của cell nhận, lưu ở `integ.transfer_package`, gửi qua kênh liên cell. Cell nhận kiểm tra chữ ký, giải mã, nhập vào SVC-04 và MOD-06 của tenant đích, ghi sổ cái cả hai phía.

![Hình 10.8. Ba luồng dữ liệu được phép giữa các cell](db-lien-cell.png){width=16cm}

Hình 10.8 cho thấy ba luồng trên và nhấn mạnh rằng không có kết nối CSDL nào vượt ranh giới cell.

# Lược đồ sự kiện

## Phong bì sự kiện

Mọi sự kiện nội bộ có phong bì chung theo CloudEvents 1.0, ánh xạ vào tiêu đề Kafka (chế độ nhị phân), còn phần dữ liệu là Avro đăng ký ở Apicurio Registry với quy tắc tương thích `BACKWARD_TRANSITIVE`. Các thuộc tính phong bì gồm `id` (UUIDv7, là `event_id`), `source` (`urn:vt-okr:<cell>:<service>`), `type` (tên topic), `time`, `subject` (`<aggregateType>/<aggregateId>`), và các phần mở rộng `tenantid`, `actorid`, `traceparent`, `policyversion`, `classification`, `aggregateversion`. Khi ra ngoài qua SVC-13, sự kiện được chuyển sang CloudEvents JSON chế độ cấu trúc.

Lược đồ Avro mẫu của EVT-05:

```json
{
  "type": "record",
  "name": "TienDoDaCapNhat",
  "namespace": "vn.vtokr.task.v1",
  "fields": [
    {"name": "taskId",        "type": {"type": "string", "logicalType": "uuid"}},
    {"name": "periodId",      "type": {"type": "string", "logicalType": "uuid"}},
    {"name": "progressId",    "type": {"type": "string", "logicalType": "uuid"}},
    {"name": "reporterId",    "type": {"type": "string", "logicalType": "uuid"}},
    {"name": "progressPct",   "type": {"type": "bytes", "logicalType": "decimal", "precision": 6, "scale": 3}},
    {"name": "actualValue",   "type": ["null", {"type": "bytes", "logicalType": "decimal", "precision": 18, "scale": 4}], "default": null},
    {"name": "evidenceCount", "type": "int", "default": 0},
    {"name": "reportedAt",    "type": {"type": "long", "logicalType": "timestamp-micros"}},
    {"name": "aggregateVersion", "type": "int"}
  ]
}
```

Quy tắc tiến hoá lược đồ: chỉ thêm trường có giá trị mặc định; không đổi kiểu, không đổi tên, không xoá trường bắt buộc; thay đổi phá vỡ tạo topic phiên bản mới `.v2`, chạy song song hai phiên bản ít nhất một chu kỳ quý, consumer chuyển dần. Lược đồ được kiểm tra tương thích trong CI bằng plugin Maven của Apicurio trước khi phát hành.

## Danh mục lược đồ EVT-01 đến EVT-26

Bảng sau liệt kê trường dữ liệu chính của từng sự kiện, bảng outbox phát ra, khoá thông điệp và phân loại cao nhất của dữ liệu trong sự kiện. Không sự kiện nào mang nội dung C2; khi cần, sự kiện chỉ mang mã tham chiếu để consumer có quyền đọc qua API.

| Mã | Topic | Outbox nguồn | Khoá | Trường dữ liệu chính | Phân loại |
|---|---|---|---|---|---|
| EVT-01 | okr.objective.approved.v1 | `okr.outbox_event` | `tenant:objectiveId` | objectiveId, periodId, level, ownerUnitId, parentObjectiveId, basisIds, approvedBy | C1 |
| EVT-02 | okr.keyresult.checkedin.v1 | `okr.outbox_event` | `tenant:keyResultId` | keyResultId, objectiveId, value, confidence, checkinAt | C1 |
| EVT-03 | task.plan.approved.v1 | `task.outbox_event` | `tenant:planId` | planId, planVersionId, versionNo, periodId, ownerType, ownerId, itemCount, approvedBy | C1 |
| EVT-04 | task.task.assigned.v1 | `task.outbox_event` | `tenant:taskId` | taskId, planItemId, title, leadAssigneeId, assigneeIds, dueDate, weight, isKeyTask, isSecret | C1 |
| EVT-05 | task.task.progressed.v1 | `task.outbox_event` | `tenant:taskId` | taskId, progressId, progressPct, actualValue, evidenceCount, reportedAt | C1 |
| EVT-06 | task.task.statuschanged.v1 | `task.outbox_event` | `tenant:taskId` | taskId, fromStatus, toStatus, isOverdue, reasonCode, changedBy | C1 |
| EVT-07 | product.evidence.submitted.v1 | `product.outbox_event` | `tenant:evidenceId` | evidenceId, versionId, deliverableId, taskId, objectSha256, aiConfirmed, submittedBy | C1 |
| EVT-08 | product.evidence.accepted.v1 | `product.outbox_event` | `tenant:evidenceId` | evidenceId, versionId, taskId, qualityLevel, acceptedBy, acceptedAt | C1 |
| EVT-09 | config.period.locked.v1 | `config.outbox_event` | `tenant:periodId` | periodId, periodBucket, lockedAt, sagaId, policyPackVersionIds | C1 |
| EVT-10 | scoring.score.computed.v1 | `scoring.outbox_event` | `tenant:profileId` | scoreResultId, profileId, jobKind, totalScore, autoRatingCode, inputHash, policyContentHash | C1 |
| EVT-11 | appraisal.profile.stepcompleted.v1 | `appraisal.outbox_event` | `tenant:profileId` | profileId, fromStatus, toStatus, stage, actorId | C1 |
| EVT-12 | appraisal.rating.decided.v1 | `appraisal.outbox_event` | `tenant:profileId` | profileId, personId, flow, ratingCode, scoreSnapshotId, deciderId, signRequestId | C1 |
| EVT-13 | policy.pack.published.v1 | `policy.outbox_event` | `tenant:packId` | packId, versionId, contentHash, effectiveFrom, approvedBy, testRunId | C0 |
| EVT-14 | org.unit.changed.v1 | `org.outbox_event` | `tenant:unitId` | unitId, versionId, sourceVersion, trạng thái đầy đủ của đơn vị, validFrom, validTo, changeOrderId | C1 |
| EVT-15 | org.assignment.changed.v1 | `org.outbox_event` | `tenant:personId` | assignmentId, personId, unitId, positionId, jobTitleId, kind, isPrimary, validFrom, validTo, sourceVersion | C1 |
| EVT-16 | org.person.changed.v1 | `org.outbox_event` | `tenant:personId` | personId, displayName, subjectGroupId, isPartyMember, status, sourceVersion | C1 |
| EVT-17 | dossier.form.generated.v1 | `docgen.outbox_event` | `tenant:formInstanceId` | renderJobId, formInstanceId, formCode, objectKey, pdfSha256 | C1 |
| EVT-18 | esign.signature.completed.v1 | `esign.outbox_event` | `tenant:signRequestId` | signRequestId, purpose, sourceModule, sourceRefId, items (documentSha256, signedObjectKey), certSha256, signedAt | C1 |
| EVT-19 | ledger.seal.created.v1 | `ledger.outbox_event` | `tenant:sealId` | sealId, sealKind, windowStart, windowEnd, merkleRoot, prevSealRoot, tsaSerial | C1 |
| EVT-20 | analytics.anomaly.detected.v1 | Producer ClickHouse job (không có outbox PostgreSQL) | `tenant:subjectId` | alertKey, signalCode, severity, subjectType, subjectId, evidenceChain, modelVersion | C1 |
| EVT-21 | notify.message.requested.v1 | outbox của module yêu cầu | `tenant:recipientId` | recipientIds, templateCode, params, deepLink, priority | C1 |
| EVT-22 | oversight.period.reopened.v1 | `oversight.outbox_event` | `tenant:periodId` | periodId, reopenRequestId, scope, scopeIds, approverIds, executedAt | C1 |
| EVT-23 | config.uipack.published.v1 | `config.outbox_event` | `tenant:uiPackId` | uiPackId, versionId, contentHash, cdnPaths | C0 |
| EVT-24 | integ.document.received.v1 | `integ.outbox_event` | `tenant:documentId` | documentId, connectorCode, externalId, docNumber, issuedDate, issuer, summary, objectKey | C1 |
| EVT-25 | oversight.complaint.filed.v1 | `oversight.outbox_event` | `tenant:complaintId` | complaintId, profileId, targetStage, complainantId, filedAt, responseDeadline | C1 |
| EVT-26 | config.period.unlocked.v1 | `config.outbox_event` | `tenant:periodId` | periodId, periodBucket, lockOrderId, sagaId, reason, unlockedAt | C1 |

Sự kiện EVT-20 là ngoại lệ duy nhất không đi qua outbox PostgreSQL: job phát hiện chạy trên ClickHouse, ghi kết quả vào bảng `anomaly_out` và một producer nhỏ đọc bảng này phát lên Kafka với `alertKey` tất định (băm của tín hiệu, đối tượng và cửa sổ thời gian) làm `event_id`, nên chạy lại job không sinh cảnh báo trùng ở MOD-07.

Ngoài 26 sự kiện nghiệp vụ, nền tảng dùng các topic kỹ thuật: `sys.ledger.append.v1` (nội bộ SVC-09), `sys.audit.v1` (sự kiện kiểm toán nội bộ: đăng nhập quản trị, đổi quyền, xem C2 của người khác, xuất hàng loạt, được SVC-09 ghi sổ), `sys.evidence.object.stored.v1` (SVC-08 báo tệp đã lưu và khoá, MOD-03 tiêu thụ), `sys.tenant.provisioned.v1` (SVC-03 báo tenant đã khởi tạo xong CSDL, realm, bucket), `iam.auth.event.v1` (sự kiện xác thực của Keycloak), `period.drain.marker` (saga), `org.bootstrap.v1` (khởi tạo bản sao), `registry.tenant.changed.v1`, các topic `cdc.*` và `agg.*`, các topic thử lại và thư chết. Topic kỹ thuật không được ứng dụng nghiệp vụ tiêu thụ.

## Cấu hình topic

Topic nghiệp vụ dùng 24 phân vùng ở cell bridge và pool, 12 phân vùng ở cell silo; hệ số nhân bản 3, `min.insync.replicas=2`; thời gian giữ 7 ngày; nén `zstd`. ACL Kafka cấp quyền ghi topic cho đúng tài khoản Kafka Connect của CSDL nguồn, quyền đọc cho đúng nhóm tiêu thụ trong cột "Tiêu thụ chính" của baseline. Ở cell pool, Kafka dùng chung cho mọi tenant; tách tenant dựa vào trường `tenantid` và bảo đảm bởi consumer đặt ngữ cảnh RLS từ trường này, không dựa vào topic riêng.

# Bộ nhớ đệm Valkey

## Vai trò và giới hạn

Valkey 8 (nhánh mã nguồn mở giấy phép BSD của Redis) được dùng ở mỗi cell cho bốn mục đích: bộ nhớ đệm đọc theo mẫu cache-aside, giới hạn tần suất (rate limit) ở cổng API, khoá phân tán ngắn hạn cho job, và Valkey Streams cho thông báo thời gian thực của SVC-12. Valkey không bao giờ là nguồn sự thật; mất toàn bộ Valkey chỉ làm hệ thống chậm đi, không mất dữ liệu. Theo ADR-18 và tiêu chí E5, Valkey không lưu dữ liệu C2 hay C3 ở dạng rõ; bộ nhớ đệm chỉ chứa dữ liệu C0, C1 và các khoá DEK không bao giờ được đưa vào Valkey (DEK chỉ nằm trong bộ nhớ của pod).

Mỗi cell có một cụm Valkey chế độ cluster gồm 3 shard chính và 3 bản sao (cell bridge, pool) hoặc một cặp chính và bản sao với Sentinel (cell silo nhỏ). Bộ nhớ mỗi shard ở CELL-HCM là 16 GB, chính sách thu hồi `allkeys-lfu`.

## Quy ước khoá

Khoá có dạng `t:<tenant>:<module>:<loại>:<mã>[:<phụ>]`, ví dụ `t:3f2a…:task:task:018f…`, `t:3f2a…:config:period:current`, `t:3f2a…:org:unit_tree:2026-03-31`. Tiền tố tenant là bắt buộc và được thư viện nền tảng `TenantCacheKey` tự thêm từ ngữ cảnh tenant; mã nguồn không được tự ghép chuỗi khoá. Bài kiểm tra ArchUnit chặn gọi trực tiếp `RedisTemplate` ngoài thư viện này.

Thẻ băm (hash tag, phần trong ngoặc nhọn quyết định slot) chỉ được dùng cho nhóm khoá nhỏ cần thao tác nguyên tử nhiều khoá, ví dụ `t:<tenant>:{rl:<user>}:minute` và `t:<tenant>:{rl:<user>}:hour` của bộ giới hạn tần suất. Không đặt thẻ băm trên toàn bộ tiền tố tenant, vì ở cell silo chỉ có một tenant, mọi khoá sẽ dồn vào một slot và một shard, làm mất ý nghĩa của cụm.

## Chính sách theo loại dữ liệu

| Loại | Ví dụ khoá | TTL | Vô hiệu hoá |
|---|---|---|---|
| Cấu hình tenant, kỳ hiện hành | `t:<t>:config:period:current` | 10 phút | Sự kiện EVT-09, EVT-22, EVT-23 xoá khoá |
| Cây đơn vị theo ngày | `t:<t>:org:unit_tree:<ngày>` | 1 giờ | EVT-14 xoá các khoá `unit_tree` của tenant |
| Phiên bản Policy Pack đã biên dịch | `t:<t>:policy:compiled:<versionId>` | 24 giờ | Không cần (bất biến theo phiên bản) |
| Thẻ nhiệm vụ hiển thị danh sách | `t:<t>:task:card:<taskId>` | 5 phút | EVT-05, EVT-06 xoá khoá |
| Bộ đếm dashboard cá nhân | `t:<t>:task:mycount:<userId>` | 60 giây | Hết hạn tự nhiên |
| Giới hạn tần suất | `t:<t>:{rl:<userId>}:minute` | 60 giây | Hết hạn tự nhiên |
| Khoá phân tán của job | `t:<t>:lock:<jobName>` | 30 giây, gia hạn | Giải phóng khi xong |
| Luồng thông báo | `t:<t>:notify:stream:<userId>` | Giới hạn 200 phần tử | `XTRIM` |

Vô hiệu hoá chạy bằng consumer Kafka của chính module sở hữu dữ liệu, sau khi giao dịch đã cam kết, nên không có trường hợp xoá khoá trước khi dữ liệu mới hiển thị. Để tránh đọc trúng giá trị cũ trong khoảng giữa cam kết và vô hiệu hoá, giá trị trong bộ nhớ đệm mang `row_version`; màn hình sửa luôn đọc trực tiếp từ CSDL. Chống hiện tượng nhiều yêu cầu cùng nạp lại một khoá vừa hết hạn bằng khoá nạp ngắn `SET NX PX 2000` và TTL có nhiễu ngẫu nhiên ±10%.

Xoá toàn bộ khoá của một tenant (khi chuyển cell hoặc chấm dứt) dùng `SCAN` với mẫu `t:<tenant>:*` theo lô trên từng shard; không dùng `KEYS`.

# Kho đối tượng Ceph

## Tổ chức bucket

Ceph RGW cung cấp giao diện S3 cho minh chứng, biểu mẫu, gói hồ sơ, mẫu tài liệu và lưu trữ sổ cái. Mỗi cell có các bucket sau, tách theo vòng đời và chế độ khoá:

| Bucket | Nội dung | Versioning | Object Lock | Lớp lưu trữ |
|---|---|---|---|---|
| `evidence` | Tệp minh chứng | Bật | Compliance, hạn theo lớp lưu giữ | NVMe 30 ngày, sau đó HDD mã hoá xoá EC 4+2 |
| `dossier` | Biểu mẫu PDF/A, gói hồ sơ đã ký | Bật | Compliance 20 năm | HDD EC 4+2 |
| `templates` | Mẫu docx, mẫu BPMN | Bật | Không | NVMe nhân bản 3 |
| `ledger-archive` | Parquet sổ cái, niêm phong ngày | Bật | Compliance 20 năm | HDD EC 4+2 |
| `uploads-tmp` | Phần tải lên dở dang | Không | Không | NVMe, vòng đời xoá sau 1 ngày |
| `transfer` | Gói chuyển liên cell | Bật | Governance 1 năm | HDD |
| `backup` | Sao lưu PostgreSQL (pgBackRest, Barman Cloud) | Bật | Compliance 35 ngày | HDD EC 4+2, sao chép sang cell dự phòng |

Object Lock chế độ compliance yêu cầu versioning bật từ lúc tạo bucket và không cho bất kỳ ai, kể cả tài khoản root của RGW, xoá phiên bản trước hạn; đây là điều kiện để minh chứng đáp ứng tiêu chí I5 và E4. Chế độ governance chỉ dùng cho gói chuyển, nơi cần khả năng xoá sớm có phê duyệt.

## Khoá đối tượng và khử trùng

Khoá đối tượng có dạng `t/<tenant>/sha256/<ab>/<băm đầy đủ>`, trong đó `<ab>` là hai ký tự đầu của băm để phân tán chỉ mục bucket. Định địa chỉ theo nội dung cho ba lợi ích: khử trùng tự nhiên trong tenant, kiểm tra toàn vẹn chỉ bằng tính lại băm, và không thể ghi đè nội dung khác vào cùng khoá. Tiền tố tenant cho phép chính sách bucket (bucket policy) và vòng đời theo tenant, và cho phép xuất toàn bộ dữ liệu của một tenant khi tốt nghiệp sang cell silo hoặc khi chấm dứt hợp đồng.

Khử trùng chỉ trong tenant. Nếu khử trùng giữa các tenant, việc tải một tệp lên và quan sát thời gian phản hồi hoặc số đếm tham chiếu có thể tiết lộ rằng một tenant khác có tệp giống hệt, là kênh rò rỉ không chấp nhận được.

## Mã hoá và truy cập

Mỗi tệp được mã hoá phía máy chủ bằng SSE-C với khoá riêng sinh bởi SVC-08, khoá này được bọc bằng KEK của tenant và lưu ở `evidence.object_version.encrypted_dek`. Trình duyệt không bao giờ có khoá Ceph; tải lên và tải xuống dùng URL ký trước có hạn 15 phút, phạm vi đúng một khoá đối tượng, do SVC-08 cấp sau khi kiểm tra quyền qua OPA. Tệp C3 được mã hoá đầu cuối ở trình duyệt trước khi tải lên nên Ceph chỉ thấy bản mã.

## Vòng đời tệp tải lên

Tệp đi qua bốn trạng thái. Tệp được tải lên `uploads-tmp` qua multipart; khi hoàn tất, SVC-08 tính lại băm, so với băm khai báo, quét mã độc và kiểm tra định dạng. Nếu đạt, SVC-08 sao chép phía máy chủ sang `evidence` với khoá theo nội dung và đặt Object Lock; nếu băm đã tồn tại trong tenant, chỉ tăng `ref_count`. Cuối cùng SVC-08 ghi `object_version` và trả mã cho MOD-03. Tệp nhiễm mã độc được chuyển sang khu cách ly và không bao giờ vào `evidence`.

# Hiệu năng và mở rộng

## Ước tính khối lượng

Bảng sau ước tính khối lượng dữ liệu của các bảng lớn nhất ở CELL-HCM (khoảng 300.000 cán bộ, 1,2 triệu hồ sơ đánh giá mỗi quý tính cả hai luồng) trong năm đầu vận hành đầy đủ. Các con số dùng để định cỡ cụm và chọn chiến lược phân vùng; chúng sẽ được hiệu chỉnh sau giai đoạn thí điểm.

| Bảng | Dòng mỗi năm | Kích thước mỗi dòng gồm chỉ mục | Dung lượng mỗi năm |
|---|---|---|---|
| `ledger.ledger_entry` | 720 triệu | 420 byte | 300 GB |
| `scoring.score_trace` | 700 triệu | 260 byte | 180 GB |
| `task.task_state_log` | 120 triệu | 380 byte | 45 GB |
| `task.task_progress` | 90 triệu | 520 byte | 47 GB |
| `task.task` | 18 triệu | 1,4 KB | 25 GB |
| `product.evidence_version` | 30 triệu | 900 byte | 27 GB |
| `appraisal.score_snapshot` | 20 triệu | 1,1 KB | 22 GB |
| `appraisal.rating_proposal` | 38 triệu | 400 byte | 15 GB |
| `notify.notification` (giữ 90 ngày) | 480 triệu | 350 byte | 42 GB tại một thời điểm |
| `*.outbox_event` (giữ 3 ngày) | 1,5 tỷ | 600 byte | 8 GB tại một thời điểm |

Tổng dung lượng nóng của cụm `pg-biz` ở CELL-HCM sau năm đầu khoảng 450 GB, cụm `pg-ledger` khoảng 350 GB; cả hai nằm thoải mái trong NVMe 3,84 TB mỗi node đã chọn ở chương 5, kể cả khi giữ hai năm trước khi lưu trữ.

## Phân vùng

Ba chiến lược phân vùng được dùng nhất quán. Bảng nghiệp vụ theo kỳ phân vùng LIST theo `period_bucket`, mỗi quý một phân vùng, kỳ năm một phân vùng riêng; phân vùng được tạo trước hai kỳ bởi job của MOD-08 khi tạo kỳ (không dùng phân vùng mặc định để lỗi nhập kỳ sai lộ ra ngay). Ở `td_pool`, mỗi phân vùng kỳ được chia tiếp 16 phân vùng con HASH theo `tenant_id`. Bảng nhật ký và sự kiện (`outbox_event`, `notify.*`, `integ.inbox_message`, `ai.suggestion_log`) phân vùng RANGE theo ngày với pg_partman. Sổ cái phân vùng RANGE theo tháng.

Truy vấn của ứng dụng luôn kèm `period_bucket` khi đọc bảng phân vùng theo kỳ để PostgreSQL loại bỏ phân vùng lúc lập kế hoạch; quy tắc này được kiểm bằng bài kiểm tra tích hợp ghi lại kế hoạch thực thi (`EXPLAIN (FORMAT JSON)`) của các truy vấn trong repository và báo lỗi nếu quét quá một phân vùng mà không được đánh dấu cho phép.

## Chỉ mục

Quy tắc chỉ mục gồm năm điều. Mọi chỉ mục trên bảng có RLS bắt đầu bằng `tenant_id` ở cell pool (ở silo và bridge, cột này có độ chọn lọc thấp nên được bỏ khỏi chỉ mục không phải khoá chính bằng biến thể migration theo mô hình). Mọi khoá ngoại có chỉ mục. Chỉ mục một phần cho các truy vấn trạng thái hay dùng (`WHERE status IN ('DA_GIAO','DANG_THUC_HIEN')`, `WHERE is_current`). Chỉ mục GIN cho mảng và `jsonb` chỉ khi có truy vấn chứng minh cần. Tạo chỉ mục trên bảng lớn dùng `CREATE INDEX CONCURRENTLY` trên từng phân vùng con rồi gắn vào chỉ mục cha, trong migration riêng không giao dịch.

## Kết nối và PgBouncer

Ứng dụng kết nối qua PgBouncer chế độ giao dịch chạy cạnh mỗi cụm (pooler của CloudNativePG). Số kết nối hoạt động cần thiết được ước tính theo định luật Little: với 6.000 yêu cầu mỗi giây vào giờ cao điểm cuối kỳ ở CELL-HCM, mỗi yêu cầu giữ kết nối trung bình 6 mili giây, cần khoảng 36 kết nối hoạt động đồng thời; cộng biên dao động, cụm `pg-biz` cần 25 đến 60 kết nối máy chủ, phù hợp với 32 nhân của node chính. PgBouncer đặt `default_pool_size = 60`, `max_client_conn = 4000`; mỗi pod ứng dụng dùng HikariCP 20 đến 40 kết nối tới PgBouncer. Tham số `max_prepared_statements = 200` (PgBouncer 1.21 trở lên) cho phép dùng câu lệnh chuẩn bị ở chế độ giao dịch, giữ lợi ích của kế hoạch thực thi được lưu của JDBC.

Chế độ giao dịch là lý do ngữ cảnh tenant chỉ được đặt bằng `set_config(..., true)` trong giao dịch (chương 5): biến phiên không tồn tại qua ranh giới giao dịch, nên kết nối máy chủ được trả về hồ chứa không mang ngữ cảnh của tenant trước.

## Đọc từ bản sao

Truy vấn chỉ đọc nặng (danh sách lớn, xuất báo cáo trong ứng dụng) được định tuyến tới bản sao đọc của CloudNativePG qua `@Transactional(readOnly = true)` và `AbstractRoutingDataSource`. Màn hình ngay sau thao tác ghi đọc từ bản chính để tránh trễ sao chép. Phân tích chéo tenant hoặc chéo kỳ không chạy trên PostgreSQL mà trên ClickHouse.

## Mô hình sao trong ClickHouse

![Hình 14.1. Mô hình sao phục vụ phân tích trong ClickHouse](db-clickhouse-star.png){width=15cm}

Hình 14.1 trình bày các bảng sự kiện ở giữa và bảng chiều xung quanh. `dim_org_unit` là chiều thay đổi chậm loại 2 dựng từ trục hiệu lực của `org_replica`, nên báo cáo theo đơn vị của một kỳ cũ dùng đúng cơ cấu tổ chức của kỳ đó. `dim_person` chỉ có khoá giả danh và thuộc tính không định danh (khối đối tượng, nhóm tuổi, giới tính được làm thô), phục vụ phân tích công bằng mà không lộ danh tính. Pre-aggregation của Cube theo (tenant, đơn vị, kỳ) làm mới mỗi 5 phút giúp dashboard điều hành phản hồi dưới 1 giây.

## Kiểm thử hiệu năng

Mỗi bản phát hành chạy bộ kiểm thử tải k6 trên môi trường có dữ liệu tổng hợp bằng quy mô CELL-HCM (sinh bằng công cụ `datagen` của nền tảng từ phân phối thống kê thực, không dùng dữ liệu thật). Ngưỡng chấp nhận: p95 các API đọc dưới 300 mili giây, p95 API ghi dưới 500 mili giây, saga chốt kỳ cho 1,2 triệu hồ sơ dưới 60 phút, độ trễ CDC sang ClickHouse p99 dưới 60 giây khi đang chốt kỳ.

# Quản lý thay đổi lược đồ và di trú từ Oracle

## Flyway và quy trình thay đổi lược đồ

Mỗi schema có thư mục migration riêng trong repository của module hoặc service sở hữu (`src/main/resources/db/migration/<schema>`), được Flyway áp dụng bởi một Kubernetes Job chạy trước khi Deployment mới khởi động (Argo CD sync wave âm), dùng vai trò `mig_<schema>`. Ứng dụng chạy với `spring.jpa.hibernate.ddl-auto=validate` và `spring.flyway.enabled=false`, nên không pod ứng dụng nào có quyền DDL hay tự thay đổi lược đồ (tiêu chí K1).

Thay đổi lược đồ theo mô hình mở rộng rồi thu hẹp (expand and contract) để triển khai không dừng: bản phát hành N thêm cột mới có thể NULL và ứng dụng ghi cả hai cột; job nền điền dữ liệu cũ; bản N+1 đọc cột mới; bản N+2 xoá cột cũ. Thao tác nặng (thêm ràng buộc trên bảng lớn) dùng `NOT VALID` rồi `VALIDATE CONSTRAINT` riêng. Migration được kiểm trong CI bằng ba bước: áp dụng trên PostgreSQL 17 trống, áp dụng trên ảnh chụp lược đồ của bản đang chạy, và chạy bộ kiểm tra chú thích phân loại, RLS và quyền (chương 5).

Ở cell bridge có nhiều CSDL dữ liệu tenant, Job di trú lặp qua danh sách CSDL lấy từ registry, áp dụng tuần tự theo nhóm 5 CSDL song song; một CSDL thất bại không chặn các CSDL khác nhưng chặn việc khởi động phiên bản ứng dụng mới cho tenant đó (định tuyến giữ phiên bản cũ cho tenant đó cho tới khi sửa xong).

## Di trú từ hệ hiện tại theo mẫu Strangler Fig

> Cập nhật 23/10/2026: mục này không áp dụng, vì Mission được phát triển như một ứng dụng mới và không di trú dữ liệu từ hệ hiện tại (ADR-20 không áp dụng, xem TL-01 Phụ lục D). Nội dung dưới đây chỉ còn giá trị tham khảo.

Hệ hiện tại chạy trên Oracle, dùng chung schema `VOFFICE` với vOffice và có 17 điểm ghép nối ở mức bảng dữ liệu: đọc trực tiếp `VHR_ORG` (32 tệp mã nguồn) và `VHR_EMPLOYEE` (30 tệp) cho tổ chức và nhân sự, kiểm token qua `USER_TOKENS`, ghi thẳng vào `NOTIFICATION` và `SMS_MASTER` để gửi thông báo, cùng các điểm đọc ghi khác vào bảng của vOffice; ngoài ra có 23 bảng mới của chức năng OKR, KPI được tạo trong schema `VOFFICE` (tiêu chí A2, K3, K4 đều chưa đạt). Di trú theo ADR-20 làm từng bước, không có ngày "chuyển đổi toàn bộ".

![Hình 15.1. Các giai đoạn di trú theo mẫu Strangler Fig](db-strangler.png){width=16cm}

Hình 15.1 trình bày bốn giai đoạn. Ở giai đoạn 1, APISIX đứng trước cả hai hệ, định tuyến theo đường dẫn; SVC-13 đọc `VHR_ORG`, `VHR_EMPLOYEE` qua kết nối JDBC chỉ đọc (connector `JDBC_RO`) và phát EVT-14 đến EVT-16, nên SVC-04 có dữ liệu tổ chức mà nền tảng mới không đọc thẳng bảng vOffice. Ở giai đoạn 2, 23 bảng OKR, KPI được di trú sang các schema mới theo bảng ánh xạ dưới đây; trong thời gian song song, CDC từ Oracle (Debezium Oracle connector dùng LogMiner) đẩy thay đổi của hệ cũ sang nền tảng mới để hai bên nhất quán, còn chiều ngược lại dùng bộ chuyển đổi trong SVC-13 ghi về bảng cũ cho các màn hình chưa chuyển. Ở giai đoạn 3, thông báo chuyển từ ghi `NOTIFICATION`, `SMS_MASTER` sang EVT-21 và SVC-12; xác thực chuyển từ `USER_TOKENS` sang Keycloak OIDC. Ở giai đoạn 4, các tuyến cũ bị tắt, kết nối Oracle bị thu hồi, bảng cũ được đóng băng chỉ đọc và lưu trữ.

| Nhóm bảng cũ trong `VOFFICE` | Schema đích | Cách chuyển |
|---|---|---|
| Bảng kế hoạch, nhiệm vụ, tiến độ (khoảng 9 bảng) | `task` | ETL lần đầu theo kỳ, sau đó CDC song song |
| Bảng mục tiêu, kết quả then chốt (khoảng 4 bảng) | `okr` | ETL lần đầu |
| Bảng sản phẩm, tệp đính kèm (khoảng 3 bảng) | `product`, `evidence` | ETL siêu dữ liệu; tệp chép từ đĩa cục bộ sang Ceph, tính băm, đặt Object Lock |
| Bảng đánh giá, xếp loại (khoảng 4 bảng) | `appraisal` | ETL dữ liệu đã chốt, đánh dấu `snapshot_kind = 'CHOT_KY'` kèm `policy_pack_version` "di trú" |
| Bảng cấu hình, danh mục (khoảng 3 bảng) | `config`, `task.work_catalog` | ETL và rà soát thủ công |

Danh sách chính xác 23 bảng được chốt ở giai đoạn khảo sát của dự án di trú. Dữ liệu cũ đưa vào được ghi sổ cái với loại sự kiện `MIGRATED` và mang băm của dòng nguồn, để sau này chứng minh được dữ liệu di trú khớp nguồn. Các cấu trúc đặc thù Oracle (`ROWNUM`, `CONNECT BY`, `NVL`, `DECODE`, chuỗi rỗng bằng NULL, kiểu `NUMBER` không giới hạn, `DATE` có giờ) được chuyển bằng ora2pg cho lược đồ và bằng viết lại thủ công có kiểm thử cho hơn 1.000 câu SQL đặc thù đã được bản đánh giá đếm (tiêu chí E2); trong nền tảng mới không còn SQL đặc thù hãng ngoài danh sách extension được phép ở chương 2.

Đối soát di trú chạy sau mỗi lần ETL và hằng ngày trong giai đoạn song song: đếm dòng, tổng kiểm theo nhóm (tổng tiến độ, số nhiệm vụ theo trạng thái theo đơn vị), và so băm dòng sau khi chuẩn hoá kiểu. Sai lệch phải bằng không trước khi tắt một tuyến cũ.

# Lưu giữ, lưu trữ và xoá dữ liệu

## Thời hạn lưu giữ

Thời hạn lưu giữ được xác định theo giá trị pháp lý của dữ liệu. Bảng sau là đề xuất; thời hạn chính thức do cơ quan chủ quản quyết định theo quy định về lưu trữ hồ sơ cán bộ và được cấu hình ở `config.tenant_setting` theo từng lớp.

| Lớp lưu giữ | Dữ liệu | Thời hạn nóng (PostgreSQL) | Lưu trữ | Tổng thời hạn | Kết thúc |
|---|---|---|---|---|---|
| `HO_SO_CAN_BO` | Hồ sơ đánh giá, mức xếp loại, ảnh chụp điểm, biểu mẫu đã ký, gói hồ sơ | 5 năm | Parquet trên Ceph Object Lock | 20 năm | Chuyển lưu trữ lịch sử theo quy định |
| `KHIEU_NAI` | Khiếu nại, trả lời, hậu kiểm | 5 năm | Parquet | 20 năm | Như trên |
| `THUC_HIEN` | Kế hoạch, nhiệm vụ, tiến độ, minh chứng | 3 năm | Parquet và tệp trên Ceph | 10 năm | Xoá bằng mật mã |
| `SO_CAI` | Sổ cái, niêm phong | 2 năm | Parquet | 20 năm | Theo hồ sơ dài nhất |
| `VET_TINH` | Vết tính điểm, tập đầu vào | 2 năm | Parquet | 10 năm | Xoá bằng mật mã |
| `VAN_HANH` | Thông báo, nhật ký tích hợp, nhật ký AI | 90 ngày đến 1 năm | Không | 1 năm | Xoá phân vùng |
| `KY_THUAT` | Outbox, inbox | 3 đến 14 ngày | Không | 14 ngày | Xoá phân vùng |

## Cơ chế lưu trữ

Lưu trữ theo phân vùng: khi một phân vùng kỳ vượt thời hạn nóng, job lưu trữ xuất phân vùng của mọi bảng thuộc kỳ đó (cùng `period_bucket`) sang Parquet, kèm tệp kê khai chứa băm từng tệp và số dòng, ghi vào bucket có Object Lock, ghi sổ cái sự kiện lưu trữ, rồi tách phân vùng. Cột C2 được giữ ở dạng bản mã trong Parquet; DEK tương ứng được giữ cho tới khi hết tổng thời hạn. Truy cập dữ liệu đã lưu trữ cần yêu cầu có phê duyệt và chạy bằng công cụ khôi phục vào CSDL tạm cô lập.

## Xoá và giữ pháp lý

Xoá dữ liệu cá nhân khi hết thời hạn dùng xoá bằng mật mã (chương 9) cho cột C2 và tệp, kết hợp xoá phân vùng cho dữ liệu C1. Khi có giữ pháp lý (legal hold) do khiếu nại, thanh tra hay tố tụng, cờ `legal_hold` trên hồ sơ và `evidence.object_meta` chặn mọi job xoá với phạm vi liên quan, và Object Lock của tệp được gia hạn. Yêu cầu của chủ thể dữ liệu theo Nghị định 13/2023/NĐ-CP về bảo vệ dữ liệu cá nhân được xử lý trong giới hạn của nghĩa vụ lưu trữ hồ sơ cán bộ: dữ liệu bắt buộc lưu không bị xoá theo yêu cầu, nhưng được hạn chế xử lý.

# Đối soát và kiểm tra nhất quán

## Vì sao cần đối soát

Hội tụ cuối cùng giữa các service dựa trên giả định outbox, Kafka, inbox hoạt động đúng. Đối soát là lưới an toàn phát hiện các trường hợp giả định bị vi phạm: slot bị vô hiệu do vượt ngưỡng WAL, sự kiện rơi vào thư chết mà không ai xử lý, lỗi lập trình trong consumer, hoặc sửa dữ liệu trái phép trực tiếp trong CSDL. Mọi job đối soát ghi kết quả vào bảng `reconcile_run` của schema đích, xuất chỉ số Prometheus và tạo cảnh báo khi có sai lệch.

## Các job đối soát

| Job | Nguồn và đích | Cách so | Tần suất | Xử lý sai lệch |
|---|---|---|---|---|
| Bản sao tổ chức | `org` (SVC-04) và `org_replica` của từng consumer | Chia mã thực thể thành 256 bucket theo băm, so băm tổng hợp từng bucket, đi sâu vào bucket lệch | Hằng đêm | Phát lại thực thể lệch qua `org.bootstrap.v1` |
| Sổ cái và dữ liệu nghiệp vụ | `ledger_entry` và bảng nguồn | Lấy mẫu 1% đối tượng thay đổi trong ngày, tính lại `payload_hash` từ dữ liệu hiện tại và so với bản ghi cuối trong sổ cái | Hằng đêm | Cảnh báo SOC mức cao |
| Điểm và ảnh chụp | `scoring.score_result` và `appraisal.score_snapshot` | Mỗi ảnh chụp có kết quả tương ứng cùng `input_hash`, `result_hash` | Sau mỗi saga chốt kỳ | Chặn bước ký số cho hồ sơ lệch |
| Tái lập điểm | `scoring.score_input_v` | Tính lại 0,5% kết quả đã chốt với đúng phiên bản quy tắc, so `result_hash` | Hằng tuần | Cảnh báo, điều tra phiên bản bộ máy tính |
| Kho phân tích | PostgreSQL và ClickHouse | Đếm dòng và tổng kiểm theo (tenant, kỳ, đơn vị) cho bảng sự kiện | Hằng giờ | Phát lại CDC theo dải LSN hoặc nạp lại phân vùng |
| Minh chứng và tệp | `evidence.object_version` và Ceph | Kiểm tra tồn tại và băm tệp, lấy mẫu 0,1% mỗi ngày, quét toàn bộ mỗi quý | Hằng ngày | Cảnh báo, khôi phục từ bản sao |
| Outbox | `outbox_event` và Kafka | Đếm sự kiện theo giờ ở hai phía | Hằng giờ | Phát lại từ outbox |
| Di trú | Oracle và PostgreSQL | Đếm, tổng kiểm, băm dòng (chương 15) | Hằng ngày trong giai đoạn song song | Chặn tắt tuyến cũ |

Đối soát theo bucket băm có chi phí thấp: với 300.000 nhân sự và 256 bucket, mỗi bên chỉ trả về 256 giá trị băm; chỉ bucket lệch mới phải so chi tiết. Hàm băm tổng hợp là phép XOR của SHA-256 từng dòng chuẩn hoá, không phụ thuộc thứ tự dòng.

# Truy vết đáp ứng tiêu chí

Bảng sau liên kết các tiêu chí của bảng kiểm trong bản đánh giá kiến trúc mà tài liệu này góp phần đáp ứng với phần thiết kế cụ thể. Trạng thái hiện tại lấy từ bản đánh giá; cột cuối chỉ chương và mục của tài liệu này nơi thiết kế được trình bày.

| Mã | Tiêu chí | Hiện trạng theo bản đánh giá | Thiết kế đáp ứng | Chương |
|---|---|---|---|---|
| A2 | Mỗi service sở hữu dữ liệu riêng | Chưa đạt: 23 bảng mới trong schema `VOFFICE` | Database per Service, schema per module, bốn vai trò mỗi schema, không truy cập chéo, kiểm thử quyền | 2, 4, 5 |
| A4 | Tích hợp bất đồng bộ, đảm bảo giao nhận | Chưa đạt: không có outbox | Transactional Outbox, Debezium Outbox Event Router, inbox idempotent, thử lại và thư chết | 6.1, 10.2, 10.3, 11 |
| A5 | Tách mô hình ghi và mô hình đọc | Một phần | CDC sang ClickHouse, mô hình sao, Cube, OpenSearch, bản sao đọc | 10.5, 10.6, 14 |
| A6 | Lưu vết bất biến cho dữ liệu pháp lý | Chưa đạt: cập nhật đè, xoá mềm | Bảng chỉ ghi thêm (`rating_proposal`, `score_snapshot`, `task_state_log`), sổ cái chuỗi băm | 6, 8 |
| E2 | CSDL đa khách thuê, cài cách ly | Chưa đạt: Oracle, hơn 1.000 câu SQL đặc thù | PostgreSQL 17, ba mô hình silo, bridge, pool trên cùng lược đồ, không SQL đặc thù hãng | 2, 5, 15 |
| E4 | Kho đối tượng cho minh chứng | Chưa đạt: đĩa cục bộ | Ceph RGW, định địa chỉ theo nội dung, Object Lock compliance | 6 (evidence), 13 |
| E5 | Cache phân tán có thiết kế | Một phần: không có tiền tố | Valkey, khoá có tiền tố tenant, chính sách TTL và vô hiệu hoá theo sự kiện, không C2 | 12 |
| I1 | Nhật ký thay đổi đầy đủ | Chưa đạt | `task_state_log`, nhật ký trạng thái kỳ, sổ cái với trước, sau, lý do, người | 6, 8 |
| I2 | Sổ cái chỉ ghi, chuỗi băm, niêm phong | Chưa đạt | 64 chuỗi mỗi tenant, Merkle hai mức hằng giờ, TSA RFC 3161, neo ngoài, lưu trữ Object Lock | 6 (ledger), 8 |
| I5 | Minh chứng bất biến có mã băm | Chưa đạt: ghi đè được | `evidence_version` đóng băng bằng trigger, SHA-256, Object Lock | 6 (product, evidence), 13 |
| I6 | Snapshot kết quả gắn phiên bản quy tắc | Một phần: không băm | `score_snapshot` với `input_hash`, `policy_content_hash`, `snapshot_hash`; `score_input_v`; đối soát tái lập | 6 (appraisal, scoring), 17 |
| I7 | Dữ liệu hai trục thời gian | Chưa đạt | Bảng `org` bitemporal, ràng buộc loại trừ, hàm truy vấn as-of, `org_asof_*` trong hồ sơ | 6 (org), 7 |
| K1 | Schema riêng, migration có phiên bản | Chưa đạt: ứng dụng tự `ALTER TABLE` | Flyway trong Job, `ddl-auto=validate`, vai trò `mig_<schema>` tách khỏi `svc_<schema>` | 2, 15 |
| K3 | Dữ liệu tổ chức qua API, không đọc bảng | Chưa đạt: đọc `VHR_ORG`, `VHR_EMPLOYEE` | SVC-04 sở hữu, ECST sang `org_replica`, SVC-13 làm ACL khi di trú | 7, 10.4, 15 |
| K4 | Thông báo, SMS, liên thông qua sự kiện | Chưa đạt: ghi thẳng bảng vOffice | EVT-21 tới SVC-12, `integ.outbound_message`, bỏ ghi `NOTIFICATION`, `SMS_MASTER` | 6 (notify, integ), 10.9, 15 |
| K5 | Đa khách thuê trong một bản cài | Chưa đạt: một tenant mỗi bản cài | `tenant_id` mọi bảng, RLS FORCE, CSDL dữ liệu tenant, tốt nghiệp tenant bằng publication | 5 |
| L4 | Đồng bộ tổ chức, người dùng từ nguồn chuẩn | Chưa đạt | `org.scim_sync_log`, SCIM vào SVC-04, ECST tới SVC-02 và các module | 6 (org), 10.4 |
| L11 | Mô hình dữ liệu chuẩn dùng chung | Chưa đạt: mô hình theo bảng vOffice | CDM, URN định danh, CloudEvents, lược đồ Avro có quản lý tương thích | 3, 11 |

Bảng truy vết cho thấy mỗi tiêu chí chưa đạt liên quan tới dữ liệu đều có ít nhất một cơ chế cụ thể, có bảng hoặc cấu hình kiểm chứng được, trong tài liệu này. Việc nghiệm thu từng tiêu chí dựa trên các bài kiểm tra tự động đã nêu ở chương tương ứng: kiểm tra quyền và RLS (A2, K5), kiểm tra chú thích phân loại và cấu hình CDC sinh tự động (E5, A5), kiểm tra bất biến bằng trigger (A6, I5), kiểm tra toàn vẹn sổ cái (I1, I2), đối soát tái lập (I6) và kiểm tra truy vấn as-of (I7).

# Phụ lục. Cập nhật sau triển khai đợt 1 (10/2026) {-}

Phụ lục này ghi lại các bảng, cột, ràng buộc và cơ chế đồng bộ mà migration trên nhánh `main` (ngày 23/10/2026) đã hiện thực khác với TL-03 phiên bản 1.0. Theo baseline mục 12, TL-03 được thêm bảng mới nhưng không đổi tên bảng đã có; mọi thay đổi dưới đây tuân thủ quy tắc đó. Tên migration được nêu để người đọc tra lại mã; trạng thái ghi theo cùng quy ước với Phụ lục D của TL-01. Các thay đổi mà BS-02 (ADR-32) và BS-03 (ADR-33) đã đề nghị cho TL-03 cũng được tập hợp ở đây, vì hai tài liệu bổ sung đó nay đã nhập vào TL-01 Phụ lục F và G.

## Quy ước chung đã chốt khi hiện thực {-}

Một số quy ước được áp dụng đồng loạt cho mọi schema. Chúng không đổi mô hình dữ liệu của TL-03 nhưng làm rõ những chỗ TL-03 để ngỏ, nên người viết migration mới phải theo.

| Chủ đề | TL-03 ghi | Hiện thực trên main | Trạng thái |
|---|---|---|---|
| Khoá của bảng phân vùng theo kỳ | Khoá chính `id` | Khoá chính, khoá duy nhất và khoá ngoại của bảng phân vùng theo kỳ đều gồm `(period_bucket, tenant_id, …)` ở mọi mô hình thuê bao; mọi chỉ mục bắt đầu bằng `tenant_id` | Đã chốt (NEN-004) |
| Tên chính sách RLS | Không quy định | `rls_<bảng>__tenant`, điều kiện dùng `nullif(current_setting(...), '')` để phiên thiếu ngữ cảnh không thấy dòng nào | Đã chốt |
| Chỉ mục duy nhất một phần | Tên tự do | Tiền tố `ix_` và hậu tố `_p`, kiểm bằng lint NEN-030 | Đã chốt |
| Cột thời gian hiệu lực | `valid_to`, `recorded_to` cho phép NULL | `NOT NULL DEFAULT 'infinity'` | Đã chốt |
| Bảng outbox | Cột `headers jsonb` | Giữ các cột của TL-03 (`aggregate_id`, `aggregate_version`, `event_key`, `schema_version`, `created_at`, khoá `(id, created_at)`), đổi `headers` thành `ce_headers`; biểu thức kiểm tên topic nới để nhận topic bốn đoạn | Đã chốt |
| Sự kiện nội bộ mission-core | Ghi outbox | Bảng xuất bản sự kiện của Spring Modulith (Event Publication Registry), không đi ra Kafka | Đã chốt |
| Chặn ghi dữ liệu kỳ đã khoá | Ở tầng ứng dụng | Thêm bảng chiếu `period_state` và hàm `fn_guard_period_write` trong từng schema `okr`, `task`, `product`, `appraisal`, áp cho cả `DELETE`; chỉ cho ghi khi mang mã yêu cầu mở lại hợp lệ | Theo mã, chờ xác nhận |
| Thời hạn câu lệnh | Không quy định | `statement_timeout` 5 giây cho vai trò `svc_`, 60 giây cho job báo cáo | Đã chốt |
| Bảo trì phân vùng | pg_partman | Mỗi schema có hàm `<schema>.fn_run_partition_maintenance()`; phân vùng `okr` tạo khi mở kỳ; thông báo phân vùng theo ngày bằng PL/pgSQL, giữ 90 ngày | Theo mã |
| Số cụm PostgreSQL mỗi cell lớn | Năm cụm | Sáu cụm theo TL-04, TL-05; tên CSDL `td_<mã>`; Pooler tên `<cụm>-pooler-rw` | Đã chốt |
| Khoá đệm Valkey | `{tenant}:{module}:` (baseline) | `t:<tenant>:<module>:...` theo TL-03 | Đã chốt |

## Bảng và cột bổ sung theo schema {-}

Bảng dưới liệt kê những gì đã được thêm. Cột "Lý do" giải thích vì sao TL-03 phiên bản 1.0 chưa có chỗ cho dữ liệu đó; phần lớn đến từ hợp đồng API (ADR-32) đòi những trường mà bảng chưa có, hoặc từ các điểm cắm DC-01 đến DC-06 của ADR-33.

| Schema | Bảng, cột bổ sung | Lý do | Trạng thái |
|---|---|---|---|
| `registry` | `tenant_onboarding`, `job`, `tenant_status_log` | Saga cấp phát và chuyển cell chạy trong tenant-registry; lưu vết trạng thái tenant | Theo mã |
| `org` | `sync_run`, `sync_schedule`, `import_job`, `scim_resource`, `recovery_key` | Đồng bộ nhân sự theo lô, nhập Excel, SCIM trên org-service, khoá phục hồi C3 (Shamir 3 trên 5) | Theo mã |
| `okr` | Cột `progress`, `progress_pct`, `checkin_frequency`; `kr_checkin_delegate`; kiểu đo `MOC`, chiều `TOI_DA`, bảng `kr_milestone`; `ai_content_trace` | Check-in thay, kiểu đo mốc (DC-04), lưu vết nội dung AI | Theo mã (DC-04 theo BS-03) |
| `okr` | `key_result.target_value`, `due_date` cho phép NULL khi nháp | KR nháp chưa đủ thông tin | Theo mã |
| `task` | `catalog_import_job`, `plan_item_confirmation`, `task_secret_content`, `e2ee_key_wrap` | Nhập danh mục, xác nhận người chủ trì khi sinh nhiệm vụ từ kế hoạch, nội dung mật C3 | Theo mã |
| `task` | `coefficient_levels`, `axis_code`, `score_adjust_min`, `score_adjust_max`, giá trị `scope` mới (`KHOI`, `TINH`); `node_kind`, `component_score`; `task_blocker.origin`, `rule_code` | Điểm cắm DC-01, DC-02, DC-06 của ADR-33 | Theo BS-03 |
| `task` | `plan_item.product_name` (C1, `varchar(255)`, cho phép NULL) | Hợp đồng có `productName` | Đã chốt 23/10/2026, chờ hiện thực |
| `product` | `evidence_version_file` (tối đa 20 tệp mỗi phiên bản), `evidence_object` (bản chiếu của SVC-08), cột `evidence.reviewer_id`, `is_duplicate_suspect`, `duplicate_of_id` | Nhiều tệp mỗi phiên bản (BS-02), hàng chờ nghiệm thu, nghi trùng | Theo mã |
| `product` | `acceptance.on_behalf_of uuid` (C1) và `delegation_id uuid`, cùng trường tương ứng trong `products.yaml` | Hiển thị nhãn "nghiệm thu thay" | Đã chốt 23/10/2026, chờ hiện thực |
| `flowable` | `wf_binding.policy_pack_version_id`, đưa vào khoá duy nhất (`tenant_id`, `policy_pack_version_id`, `process_kind`, `subject_group_id`, `flow`, `period_type`) | Tham số luồng được soạn và phát hành theo phiên bản Policy Pack | Đã chốt 23/10/2026, chờ hiện thực |
| `evidence` | `upload_session`, `ocr_text` (mã hoá C2) | Tải tệp nhiều phần qua service; lưu văn bản OCR | Theo mã, chờ người phụ trách CSDL |
| `appraisal` | `self_assessment.reason_enc`, `appraisal_profile.is_self_assessment_missing`, `return_count`, bảng `incident`; ràng buộc lý do khi mức đề xuất khác mức gợi ý | Tự đánh giá, trả lại có giới hạn, sự việc (DC-05) | Theo mã |
| `config` | `period_lock_order.planned_lock_at`, `period_reopen`, `delegation`, `user_preference`, `saved_filter`; cột `expiry_emitted_at` ở bảng vai trò và uỷ quyền; `ui_pack_version.forms_json`, `plugins_json`; `form_schema.last_test_run`; cột `container`, `since`, `mobile`, `loaded_at` của registry component | Lệnh khoá kỳ, mở lại hậu kiểm, quản trị vai trò (EVT-27), cá nhân hoá, UI Pack | Theo mã |
| `policy` | Ba bảng và một cột của hồ sơ tập khách hàng | ADR-33 | Theo BS-03 |
| `oversight` | Khiếu nại cho lưu nháp (bỏ NOT NULL, thay bằng CHECK), cột `code`, `reserve_opinion`, `sign_request_id`; `alert_action` sáu giá trị, cột `is_active`, `verdict` | Lưu nháp khiếu nại, xử lý cảnh báo | Theo mã, chờ xác nhận |
| `notify` | `push_device`, `channel_pref_meta`, `bulk_job` | Thông báo đẩy, gửi hàng loạt | Đã chốt |
| `flowable` | `wf_task_batch`, `wf_holiday_calendar`, schema `org_replica` trong CSDL của workflow-service | Duyệt hàng loạt, phân giải cấp duyệt | Theo mã; consumer đổ dữ liệu chưa có |
| `ledger` | `chain_head.is_locked`, `locked_reason` | Khoá chuỗi khi chuyển cell | Theo mã |
| `ledger` | Trường `metadata_sha256` trong siêu dữ liệu chuẩn hoá của `metadata_hash` (không thêm cột): SHA-256 hex chữ thường của cột `metadata` chuẩn hoá JCS và NFC | Bản 1.0 không băm cột `metadata`, nên sửa trạng thái trước, sau không bị kiểm tra toàn vẹn hay `ledger-verify` phát hiện; gói bằng chứng mang `metadataSha256` cho mọi bản ghi | Đã chốt 23/10/2026 (người phụ trách chọn), đã hiện thực ở ledger-service; chuỗi ghi theo công thức cũ phải ghi lại |
| `esign` | Mở rộng `sign_request.purpose` với `KHIEU_NAI` | Ký quyết định giải quyết khiếu nại | Đã chốt |

## Đồng bộ dữ liệu và kho phân tích {-}

Cơ chế đồng bộ ở chương "Cơ chế đồng bộ dữ liệu" được giữ, với các điều chỉnh sau. Debezium chạy một connector mỗi CSDL cho outbox, `snapshot.mode = no_data`, và một connector phân tích riêng mỗi CSDL với slot `dbz_cdc_<db>` và publication `pub_analytics`. Mã người trong luồng phân tích được giả danh bằng SMT `TenantPseudonymize` dùng HMAC theo tenant, thay cho `column.mask.hash` của TL-03, để cùng một người ở hai tenant có mã giả danh khác nhau. Bảng nguồn phân tích dùng `REPLICA IDENTITY FULL`.

Kho ClickHouse có thư mục migration riêng `analytics/clickhouse/migrations` (V005 đến V013). Cube đọc qua view `cube_*` khai báo `SQL SECURITY INVOKER` thay vì đọc thẳng bảng fact, vì ClickHouse 25.8 từ chối tạo pre-aggregation khi tài khoản chỉ có quyền theo cột; row policy theo `SQL_tenant_id` vẫn áp lên bảng bên dưới. Job phát hiện bất thường dùng tài khoản `<db>_anomaly_job` có quyền ghi riêng vào `anomaly_out` và `anomaly_run`. Chỉ mục tìm kiếm OpenSearch theo tên `t_<tenant>_task_v<n>` kèm alias, ghép tài liệu từ nhiều sự kiện bằng `scripted_upsert` với phiên bản theo nhóm trường, khác cách dùng external version của TL-03.

## Câu hỏi mở cho người phụ trách TL-03 {-}

Ngày 23/10/2026, người phụ trách đã chấp nhận phương án mặc định cho ba điểm trước đây còn mở: thêm cột phiên bản Policy Pack vào khoá duy nhất của `wf_binding`; thêm `plan_item.product_name`; thêm `acceptance.on_behalf_of` và `delegation_id` (bảng ở trên). Ba điểm này chờ task backend hiện thực bằng migration. Các việc sau vẫn cần quyết định trước khi phát hành TL-03 phiên bản tiếp theo. Thứ nhất, lược đồ EVT-10 thiếu luồng và câu giải thích, EVT-17 thiếu `packageId`, EVT-18 thiếu băm tệp đã ký, EVT-22 thiếu `openUntil` và phạm vi, EVT-24 thiếu đơn vị nhận. Thứ hai, verdict quét mã độc dùng bốn giá trị `CLEAN`, `INFECTED`, `UNSCANNABLE`, `ERROR`, cần sửa danh mục của TL-03. Thứ ba, bộ sinh dữ liệu thử tải DL-08 ghi chỉ mục mù 32 byte với khoá khác, trong khi mã Java cắt 16 byte theo ADR-30; dữ liệu nạp được nhưng tra cứu theo chỉ mục mù sẽ không khớp.

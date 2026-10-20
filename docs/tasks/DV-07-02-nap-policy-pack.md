# DV-07-02 · ADR-19 Nạp Policy Pack, biên dịch sẵn CEL và DMN, đệm theo phiên bản

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-07-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | DV-07-01, NEN-041, CORE-05-03 |
| Truy vết | QT-15 → `GET /api/v1/policies/resolve` (`resolvePolicyPack`, nội bộ, owner MOD-05) → SVC-07 → bảng `policy.policy_binding` (đọc qua API), `scoring.score_result.policy_content_hash`, `engine_version` → tiêu thụ EVT-13 `policy.pack.published.v1`; ADR-19; BR-08-07, BR-15-01 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

scoring-service lấy đúng phiên bản Policy Pack ghim cho kỳ, tenant, khối, luồng; biên dịch biểu thức CEL và bảng DMN một lần rồi dùng lại cho mọi hồ sơ; khi có phiên bản mới phát hành thì nạp mà không khởi động lại. Khi xong, mỗi lần tính ghi được `policy_content_hash` và `engine_version`, và không bao giờ dùng phiên bản khác phiên bản ghim của kỳ (BR-08-07).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương "Policy Pack: quy tắc chấm điểm như mã có phiên bản" (dòng 2380), mục "Ngôn ngữ biểu thức CEL và bảng quyết định DMN" (dòng 2448), ADR-19 (dòng 3572), mục "SVC-07 scoring-service" (dòng 1072–1084).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-15: môi trường CEL và DMN ở SVC-07 (dòng 1325, 1333), BR-15-01, BR-15-06.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-08 BR-08-07.
- Hợp đồng: `contracts/openapi/modules/policies.yaml` `resolvePolicyPack`; lược đồ Avro EVT-13.
- Wiki: `wiki/03-giao-dien-theo-tenant.md` (tách UI Pack và Policy Pack), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Viết client gọi `resolvePolicyPack` (client nội bộ, Resilience4j: timeout 3 s, circuit breaker) theo khoá (tenant, khối, luồng, kỳ) và nhận nội dung phiên bản gồm quy tắc CEL, tham số, bảng quyết định DMN.
- Biên dịch CEL bằng `vt-cel` (NEN-041, cel-java, hàm `sum`, `ngay_lam_viec`, `min_muc`) có kiểm kiểu theo khai báo biến ngữ cảnh (BR-15-01); nạp DMN bằng Flowable DMN độc lập (không cần engine BPMN).
- Bộ đệm trong tiến trình theo khoá `policy_content_hash`, giới hạn kích thước, không hết hạn theo thời gian (nội dung phiên bản bất biến); tên phiên bản và kỳ ánh xạ sang băm nội dung.
- Consumer EVT-13 (inbox idempotent): nạp trước phiên bản mới và đánh dấu kỳ đang mở bị ảnh hưởng để DV-07-06 tính lại (TL-02b dòng 1376).
- Từ chối tính và ghi lỗi rõ khi không tìm thấy phiên bản ghim của kỳ (không bao giờ rơi về phiên bản mới nhất).
- Kiểm thử: biên dịch một gói mẫu từ `data/policy-pack/`; hai lần tính cùng phiên bản chỉ biên dịch một lần; EVT-13 trùng không nạp hai lần; biểu thức sai kiểu bị từ chối khi nạp.

## 4. Ngoài phạm vi

- API soạn, kiểm thử, phát hành Policy Pack (CORE-05); thư viện CEL dùng chung (NEN-041).

## 5. Tệp được phép sửa

- `backend/services/scoring-service/**`
- `data/policy-pack/` (chỉ thêm gói mẫu dùng cho kiểm thử)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/scoring-service -am verify` chạy xanh gồm Testcontainers Kafka + Apicurio cho consumer EVT-13.
- Đổi công thức không cần phát hành phần mềm: kiểm thử nhận EVT-13 của một phiên bản Policy Pack mới đổi công thức thì scoring-service đang chạy nạp phiên bản đó và tính kỳ ghim phiên bản mới theo công thức mới, không đổi mã, không khởi động lại.
- Đo thời gian biên dịch một gói và thời gian đánh giá một hồ sơ khi trúng đệm; ghi số đo vào báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.

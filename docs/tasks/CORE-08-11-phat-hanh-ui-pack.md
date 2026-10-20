# CORE-08-11 · QT-18/EVT-23 Biên dịch, ký và phát hành UI Pack qua CDN

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-11` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | L (nên tách: a. biên dịch token và đóng gói; b. ký, lưu kho, chuyển trạng thái, EVT-23; c. phục vụ `current` có ETag, bố cục trang chủ và vô hiệu CDN) |
| Phụ thuộc | CORE-08-09, CORE-08-10, DV-10-01; WEB-004 (công cụ biên dịch token, bổ sung so với khung) |
| Truy vết | QT-18 → SCR-1801, SCR-0002 → `POST /api/v1/ui/packs/{id}/versions/{v}/publish` (`publishUiPackVersion`, 202 job `RENDER`), `GET /api/v1/ui/packs/current` (`getCurrentUiPack`, ETag, 304), `GET /api/v1/ui/layouts/home` (`getHomeLayout`) → MOD-08, SVC-10 → `config.ui_pack`, `config.ui_pack_version` (`bundle_object_key`, `bundle_sha256`, `signature`, `published_at`) → EVT-23 `config.uipack.published.v1`; ADR-05; NFR-07 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn tất đường phát hành UI Pack phía máy chủ theo TL-01 chương 7: người duyệt (khác người soạn) phát hành phiên bản `CHO_DUYET`, MOD-08 biên dịch token sang CSS custom properties và preset PrimeNG, đóng gói, băm, ký số, đẩy lên kho đối tượng gốc của CDN theo đường dẫn bất biến, chuyển phiên bản cũ sang `THAY_THE`, phát EVT-23 và vô hiệu con trỏ `current.json`; shell và ứng dụng di động lấy UI Pack hiện hành qua CDN hoặc qua API có ETag. Mục tiêu toàn trình ≤ 15 phút, đáp ứng NFR-07 (UI Pack ≤ 1 ngày).

## 2. Đầu vào bắt buộc đọc

- `TL-01-thiet-ke-kien-truc.md` G1: "Tổng quan kiến trúc giao diện theo tenant" (dòng 1528), "Pipeline token sang CSS variables và preset PrimeNG" (dòng 1600), "Cơ chế bộ đệm: CDN, ETag, IndexedDB" (dòng 1753), "Bảo mật: chống XSS và CSP" (dòng 1759); ADR-05 (dòng 3432).
- `TL-02b-quy-trinh.md` QT-18 "Mô tả các bước" (dòng 1974), SCR-1801 (dòng 2035), MSG-18-03; `TL-02a-quy-trinh.md` "SCR-0002. Trang chủ" (dòng 426, phần tải trang).
- Baseline danh mục EVT (EVT-23, bên nhận SVC-17 vô hiệu cache CDN).
- `contracts/openapi/modules/config.yaml` `publishUiPackVersion`, `getCurrentUiPack`, `getHomeLayout`, schema `UiPackRuntime`, `HomeLayout`; `contracts/events/avro/` lược đồ EVT-23.
- `docs/tasks/WEB-004-bien-dich-token.md`; bản trích `extract/tl01-xuyen-suot.md` X-G1-02, 03, 05, 06.

## 3. Phạm vi công việc

- Biên dịch token: chạy công cụ biên dịch của WEB-004 (Style Dictionary) sinh CSS dưới `:root[data-tenant]` và `:root[data-mode="elderly"]` cùng JSON preset PrimeNG. Điểm lệch cần lưu ý: TL-01 ghi job Node/TypeScript, `docs/quy-dinh/BACKEND.md` không liệt kê Node; chọn chạy công cụ dưới dạng ảnh container riêng gọi qua Job hoặc tiến trình con, ghi lựa chọn vào `wiki/14` và nêu trong PR để người phụ trách duyệt.
- Đóng gói `pack.json` theo `UiPackRuntime`, tính `contentHash` SHA-256, ký qua SVC-10 (`SignatureProvider`, DV-10-01) bằng khoá nền tảng; shell kiểm bằng khoá công khai nền tảng (X-G1-05).
- Lưu kho đối tượng: `/ui-packs/{tenant}/{version}/pack.json` với `Cache-Control: public, max-age=31536000, immutable`; `/ui-packs/{tenant}/current.json` chỉ chứa phiên bản và chữ ký, `Cache-Control: max-age=60, stale-while-revalidate=600`, có ETag.
- `publishUiPackVersion`: OPA người duyệt khác người soạn (`UI-403-SAME-PERSON`), step-up AAL2 theo TL-01 Phụ lục C, job 202 loại `RENDER`; khi xong ghi `bundle_object_key`, `bundle_sha256`, `signature`, `published_at`, cập nhật `ui_pack.current_version_id`, chuyển phiên bản cũ `THAY_THE`, phát EVT-23 qua outbox trong cùng giao dịch.
- Consumer EVT-23 (có inbox) gọi API vô hiệu bộ đệm CDN cho `current.json` của tenant. Điểm lệch cần lưu ý: baseline ghi bên nhận là SVC-17, nhưng SVC-17 là nginx tĩnh; chọn đặt consumer trong `config.infrastructure` của mission-core (suy luận), ghi vào `wiki/14`.
- `getCurrentUiPack`: trả `UiPackRuntime` hiện hành, ETag theo phiên bản, `If-None-Match` trả 304; là nguồn dự phòng khi CDN lỗi và cho di động.
- `getHomeLayout`: lấy cây `layouts.home`, đánh giá `visibleWhen` bằng `vt-cel` trên ngữ cảnh phân công đang chọn và lược nút không thấy được, trả `HomeLayout` có ETag; trình duyệt chỉ đánh giá lại để phản hồi nhanh.
- Ghi chú phụ thuộc phía khác: đẩy thông báo giao diện mới tới phiên đang mở qua SSE là việc của SVC-12 (ngoài phạm vi).

## 4. Ngoài phạm vi

- Quay lui (CORE-08-12); plugin (CORE-08-15); kiểm chữ ký phía shell (WEB-009).
- Cấu hình CDN và kho đối tượng ở hạ tầng (nhóm INF); task chỉ dùng giao diện S3 tương thích Ceph RGW qua Testcontainers MinIO hoặc tương đương khi kiểm thử.

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `backend/services/ui-pack-compiler/**` (chỉ khi chọn ảnh container riêng bọc công cụ WEB-004; tên thư mục suy luận)
- `deploy/charts/mission-core/**` (chỉ khoá cấu hình kho đối tượng và CDN, không giá trị bí mật)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử tích hợp Testcontainers (PostgreSQL, Kafka, kho S3 giả lập) cho toàn chuỗi publish → EVT-23 → vô hiệu CDN (bộ giả lập ghi nhận lời gọi).
- `getCurrentUiPack` trả 304 khi `If-None-Match` khớp; đổi phiên bản thì ETag đổi; chữ ký kiểm được bằng khoá công khai trong kiểm thử.
- Tự phát hành bị chặn 403; phát hành lại cùng Idempotency-Key trả cùng job.
- Thời gian job `RENDER` với gói mẫu ≤ 2 phút trên máy kiểm thử (ngân sách thật ≤ 15 phút).
- Kiểm thử hợp đồng ba thao tác khớp `config.yaml`; lược đồ EVT-23 tương thích BACKWARD.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.

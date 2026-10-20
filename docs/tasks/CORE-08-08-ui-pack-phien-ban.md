# CORE-08-08 · QT-18/SCR-1801 UI Pack có phiên bản và máy trạng thái

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-08-01 |
| Truy vết | QT-18 → SCR-1801 → `GET /api/v1/ui/packs` (`listUiPacks`), `GET/POST /api/v1/ui/packs/{id}/versions` (`listUiPackVersions`, `createUiPackVersion`), `GET/PUT /api/v1/ui/packs/{id}/versions/{v}` (`getUiPackVersion`, `updateUiPackVersion`), `GET .../preview` (`previewUiPackVersion`), `POST .../submit` (`submitUiPackVersion`), `POST .../reject` (`rejectUiPackVersion`) → MOD-08 → `config.ui_pack`, `config.ui_pack_version` → EVT-21; ADR-05 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng mô hình UI Pack của tenant theo TL-01 chương 7 và TL-02b QT-18: mỗi tenant có UI Pack, mỗi UI Pack có nhiều phiên bản, phiên bản đi qua `NHAP` → `DANG_KIEM_TRA` → `CHO_DUYET` → `DA_PHAT_HANH` → `THAY_THE`, người duyệt luôn khác người soạn. Khi xong, Config Studio (WEB-050) có đủ API để soạn nháp, xem trước, gửi duyệt và từ chối; các bước kiểm tra tự động, biên dịch và phát hành gắn vào ở các task sau qua điểm mở rõ ràng.

## 2. Đầu vào bắt buộc đọc

- `TL-01-thiet-ke-kien-truc.md` "Chuyên đề G1: Giao diện riêng theo tenant" (dòng 1478), các mục "Tổng quan kiến trúc giao diện theo tenant" (dòng 1528), "Cấu trúc UI Pack" (dòng 1534), "Quản trị phiên bản UI Pack và Policy Pack" (dòng 1799); ADR-05 (dòng 3432).
- `TL-02b-quy-trinh.md` mục "QT-18. Cấu hình giao diện tenant (UI Pack)" (dòng 1943): "Trạng thái đối tượng" (dòng 1991), "Quy tắc nghiệp vụ" (dòng 2008), "Ngoại lệ và xử lý" (dòng 2025), "SCR-1801" (dòng 2035).
- `TL-03-thiet-ke-csdl.md` "Bảng config.ui_pack và config.ui_pack_version" (dòng 1541).
- `contracts/openapi/modules/config.yaml` nhóm `/ui/packs` và các schema `UiPackVersion*`, `UiPackReviewInput`, `UiPackRejectInput`.
- Bản trích `extract/tl01-xuyen-suot.md` mục 1 (X-G1-01, X-G1-02) và `extract/tl02b-may-chu.md` QT-18 (BE-Q18-01).

## 3. Phạm vi công việc

- Viết JSON Schema lược đồ UI Pack 1.0 (năm phần: siêu dữ liệu với `requires.registry` và `requires.policyPack`, `tokens` dạng W3C Design Tokens, `modes.elderly` với `flags.reducedSteps`, `layouts`, `forms`, `plugins`, `i18n`) làm tài nguyên của mô-đun; không trường nào nhận HTML hay JavaScript (X-G1-01).
- Aggregate `UiPackVersion` với máy trạng thái đúng TL-02b dòng 1991; chuyển trái phép trả 409 `UI-409-NOT-DRAFT`; mọi chuyển ghi outbox trong cùng giao dịch.
- `createUiPackVersion` nhân bản từ phiên bản chỉ định (Idempotency-Key); `updateUiPackVersion` chỉ khi `NHAP`, bắt buộc `If-Match`, kiểm lược đồ đồng bộ và trả lỗi trường theo RFC 9457.
- `submitUiPackVersion`: chuyển `DANG_KIEM_TRA`, tạo job 202 loại `TEST_RUN`, gọi cổng `UiPackChecker` (giao diện định nghĩa ở task này, hiện thực đủ ở CORE-08-09, CORE-08-10); đạt thì `CHO_DUYET` và phát EVT-21 cho người duyệt, không đạt thì về `NHAP` kèm danh sách lỗi.
- `rejectUiPackVersion`: người duyệt khác người soạn (OPA, `UI-403-SAME-PERSON`), ý kiến bắt buộc, về `NHAP`.
- `previewUiPackVersion`: trả cấu trúc `UiPackRuntime` của bản nháp để xem trước (biên dịch token tạm thời dùng cổng `TokenCompiler` của CORE-08-11; trước khi có, trả token thô và ghi rõ trong báo cáo).
- `listUiPacks`, `listUiPackVersions` có phân trang con trỏ.

## 4. Ngoài phạm vi

- Biên dịch, ký, đẩy CDN, phát hành (CORE-08-11); quay lui (CORE-08-12); gắn biểu mẫu (CORE-08-13).
- Đo thời gian dựng 50 trường bằng trình duyệt không giao diện (kiểm thử hồi quy giao diện phía web, WEB-005).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/main/resources/config/ui-pack/**` (lược đồ JSON UI Pack)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử đơn vị máy trạng thái đủ mọi chuyển hợp lệ và trái phép; kiểm thử tích hợp Testcontainers cho `If-Match` 409, tự duyệt bị chặn 403.
- Kiểm thử hợp đồng bảy thao tác khớp `config.yaml`; `openapi-diff` không phá tương thích.
- Lược đồ UI Pack từ chối gói có chuỗi chứa thẻ HTML ở trường văn bản tự do theo bộ ca kiểm thử.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

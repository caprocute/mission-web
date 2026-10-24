# CORE-05-05 · QT-15/SCR-1502 Bảng quyết định DMN 1.4

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-05-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | L (nên tách: a. lưu và kiểm FEEL, dòng bị che; b. mẫu câu giải thích và xuất XML DMN 1.4) |
| Phụ thuộc | CORE-05-02 |
| Truy vết | QT-15 → SCR-1502 → `GET /api/v1/policies/packs/{id}/versions/{v}/decision-tables` (`listDecisionTables`), `GET/PUT .../decision-tables/{key}` (`getDecisionTable`, `saveDecisionTable`), `GET .../decision-tables/{key}/dmn` (`exportDecisionTableDmn`) → MOD-05 → `policy.decision_table` → BR-15-06, MSG-15-04, MSG-15-05, lỗi `POLICY-422-FEEL`, `POLICY-422-TEMPLATE-VAR`; ADR-19 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Lưu và kiểm bảng quyết định xếp loại theo DMN 1.4 (FEEL) cho từng phiên bản Policy Pack: hit policy `FIRST` mặc định (hỗ trợ `UNIQUE`, `PRIORITY`), mỗi dòng bắt buộc có điều khoản căn cứ và giải thích, phát hiện dòng không bao giờ được chọn và khoảng hở, kiểm biến trong mẫu câu giải thích, xuất XML DMN 1.4 chuẩn để kiểm tra độc lập. Khi xong, SCR-1502 soạn bảng xếp loại (ví dụ `xep-loai-dang` của TL-01) trên API thật.

## 2. Đầu vào bắt buộc đọc

- `TL-02b-quy-trinh.md` QT-15 "Quy tắc nghiệp vụ" (BR-15-06), "SCR-1502. Bảng quyết định xếp loại" (dòng 1450), MSG-15-04, MSG-15-05 (dòng 1492–1493).
- `TL-01-thiet-ke-kien-truc.md` "Ngôn ngữ biểu thức CEL và bảng quyết định DMN" (dòng 2448, ví dụ bảng `xep-loai-dang`).
- `TL-03-thiet-ke-csdl.md` "Bảng policy.decision_table" (dòng 1797).
- `contracts/openapi/modules/policies.yaml` nhóm `/decision-tables`.
- Bản trích `extract/tl02b-may-chu.md` QT-15 (BE-Q15-04), `extract/tl01-xuyen-suot.md` X-PP-03.

## 3. Phạm vi công việc

- `saveDecisionTable` (bắt buộc `If-Match`): nhận `rules_json` (đầu vào, đầu ra, điều khoản ≤ 100 ký tự, giải thích ≤ 200 ký tự mỗi dòng), `hit_policy`, `explanation_templates_json`; sinh `dmn_xml` DMN 1.4 và `dmn_hash`; quy tắc đổi trạng thái `DAT_KIEM_THU` về `NHAP` giống CORE-05-04.
- Kiểm FEEL bằng engine Flowable DMN (đã chốt trong stack): biểu thức ô không phân tích được trả `POLICY-422-FEEL` kèm dòng, cột.
- Phân tích bảng: với `FIRST`, phát hiện dòng bị dòng trên che hoàn toàn và trả cảnh báo MSG-15-04 (không chặn lưu); phát hiện khoảng hở đầu vào không dòng nào khớp (cảnh báo, suy luận theo TL-01 X-PP-08).
- Kiểm biến trong mẫu câu giải thích thuộc ngữ cảnh xếp loại; biến lạ trả `POLICY-422-TEMPLATE-VAR` theo MSG-15-05.
- `exportDecisionTableDmn` trả XML DMN 1.4 hợp lệ theo XSD chuẩn; `listDecisionTables`, `getDecisionTable` có `ETag`.

## 4. Ngoài phạm vi

- Chạy ca vàng trên bảng (CORE-05-07); hậu xử lý hạn mức theo lô (scoring-service).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/pom.xml` (chỉ thêm Flowable DMN engine nếu thư viện nền chưa đưa vào)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử với bảng `xep-loai-dang` mẫu của TL-01 cho đúng bốn mức trên các ca biên; dòng bị che được phát hiện; FEEL sai và biến lạ bị 422.
- XML xuất ra kiểm hợp lệ với XSD DMN 1.4 và nạp lại được vào Flowable DMN cho cùng kết quả.
- Kiểm thử hợp đồng bốn thao tác khớp `policies.yaml`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

# DV-10-06 · SVC-10/ADR-31 Ký lô 100.000 phiếu trong 4 giờ: phiên ký một lần xác nhận, worker co giãn

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-10-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | DV-10-03; DV-10-02 (ký lô bằng MySign của người quyết định) nên gộp trước (bổ sung) |
| Truy vết | QT-09 (quyết định xếp loại hàng loạt), QT-10 → API `POST /api/v1/appraisals/batch-decisions` → `POST /api/v1/esign/requests` (`isBatch`), `GET /api/v1/esign/requests/{id}` → SVC-10 → bảng `esign.sign_request`, `esign.sign_item` → EVT-18 → NFR-06, ADR-31, PERF-17, PERF-20 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực ký lô: người quyết định xác nhận một lần cho danh sách tới 1.000 phiếu, worker esign ký từng phiếu song song, co giãn theo hàng đợi, thử lại không ký trùng, và phát EVT-18 cho từng phiếu. Khi xong, một cell ký được 100.000 phiếu trong ≤ 4 giờ (mục tiêu ≤ 1 giờ) với 100% chữ ký hợp lệ, HSM dùng ≤ 50% năng lực.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-10 esign-service" (L1118–1140, đoạn ký lô), ADR-31 (L3692), lớp 2 chương G3 (L2187–2193, ước tính đỉnh ký).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Danh mục kịch bản thử tải PERF" (L1743, PERF-20) và "Kịch bản riêng cho góp ý G3" (L1810, PERF-17).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` từ điển `esign.sign_request` (`is_batch`), `esign.sign_item` (L3706–3736) và đoạn ADR-31 về `appraisal.batch_decision`.
- `contracts/openapi/modules/appraisals.yaml` `createAppraisalBatchDecision`; `contracts/openapi/modules/dossiers.yaml` `createEsignRequest`, `getEsignRequest` (`itemCount`, `signedCount`).
- `00-co-so-thiet-ke-chung.md` NFR-06.

## 3. Phạm vi công việc

- Mở rộng `createEsignRequest` cho yêu cầu lô (`is_batch = true`, tối đa 1.000 `sign_item`): danh sách tài liệu và băm lấy từ bản ghi `appraisal.batch_decision` qua cổng nội bộ của MOD-04, không nhận băm từ client.
- Một lần xác nhận cho cả lô: với MySign dùng một phiên ủy quyền ký nhiều băm (SAD dùng cho danh sách băm), với HSM tổ chức dùng một lần step-up của người quyết định; ghi rõ trong `sign_request`.
- Worker ký lô chạy riêng (`esign-batch-worker`), đọc mục chưa ký theo lô nhỏ, ký song song có giới hạn, cập nhật `signedCount`; mỗi mục ký xong ghi chữ ký và EVT-18 qua outbox trong cùng giao dịch; khoá idempotent theo (`sign_request_id`, `document_sha256`) để thử lại không ký trùng.
- Mục lỗi được thử lại có giới hạn với backoff; mục lỗi vĩnh viễn để `LOI` ở mức mục, không làm hỏng cả lô; trạng thái lô `DA_KY` khi mọi mục xong, kèm danh sách lỗi trong `problem`.
- Xuất metric độ dài hàng đợi mục chưa ký để KEDA co giãn worker (ngưỡng đặt ở values, cấu hình hạ tầng do INF-16), cùng metric chữ ký/giây và tỷ lệ năng lực HSM.
- Điểm lệch cần lưu ý: ADR-31 mô tả "một chữ ký cho cấu trúc chứa danh sách băm, kiểm chứng một phiếu cần danh sách băm của lô", còn hợp đồng `createAppraisalBatchDecision` ghi "mỗi hồ sơ một chữ ký PAdES" và PERF-17 kiểm "100% chữ ký hợp lệ" theo từng phiếu; task theo hợp đồng (API-first, ADR-32): một lần xác nhận, mỗi phiếu một chữ ký PAdES, và ghi điểm lệch vào `wiki/14`.

## 4. Ngoài phạm vi

- Sinh 100.000 PDF/A (DV-11-04) và tạo `batch_decision` ở MOD-04 (CORE-04 tương ứng).
- Kịch bản k6/Gatling PERF-17, PERF-20 đầy đủ trên CELL-PERF (QA-10, QA-11).

## 5. Tệp được phép sửa

- `backend/services/esign/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/esign -am verify` xanh, gồm kiểm thử tích hợp: lô 1.000 mục với HSM SoftHSM và MySign giả lập 1–3 giây/lần, giả lập lỗi ngẫu nhiên 10%, kết thúc với đúng 1.000 chữ ký, không mục nào ký hai lần (đối soát `sign_item` theo khoá idempotent), đúng 1.000 EVT-18.
- Đo cục bộ thông lượng ký lô và ngoại suy cho 100 phiên × 1.000 phiếu; ghi số đo vào báo cáo cùng giả định (mục tiêu ≤ 4 giờ, NFR-06).
- `getEsignRequest` trả `itemCount`, `signedCount` đúng trong lúc lô đang chạy; phản hồi khớp `dossiers.yaml`.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki trang 14 ghi điểm lệch ADR-31 với hợp đồng.

## 7. Báo cáo

Theo AGENTS.md mục 6.

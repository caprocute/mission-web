# CORE-02-16 · QT-05/SCR-0502 Nhiệm vụ từ văn bản chỉ đạo: consumer EVT-24, trích AI, xác nhận, khoá đoạn

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-16` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | L |
| Phụ thuộc | CORE-02-14, DV-13-03, DV-14-04; cần CORE-02-15 (gần giống trên nhiệm vụ trích) (bổ sung) |
| Truy vết | QT-05 bước 1b–2 → SCR-0502 → API `GET /api/v1/tasks/source-docs`, `GET /tasks/source-docs/{id}`, `POST /tasks/source-docs/{id}/dismiss`, `POST /tasks/from-document`, `POST /tasks/{id}/source-docs`, `POST /tasks/assign-batch`; `GET /api/v1/ai/extractions?sourceDocId=`, `POST /ai/suggestions/{id}/decision` (SVC-14) → MOD-02, SVC-13, SVC-14 → bảng `task.task_source_doc`, `task.task`, `task.inbox_processed`, `ai.suggestion_log` → nhận EVT-24 `integ.document.received.v1`, phát EVT-04, EVT-21; BR-05-05, BR-05-07; MSG-05-06, MSG-05-08, MSG-05-09, MSG-05-10 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Biến văn bản chỉ đạo đến từ vOffice, iOffice (EdXML) thành nhiệm vụ: MOD-02 và ai-gateway cùng nhận EVT-24, ai-gateway trích các đoạn giao việc cho đơn vị nhận (tên, chủ trì, phối hợp, hạn, sản phẩm, gợi ý mục danh mục và KR, độ tin cậy) trong ≤ 1 phút; người giao xác nhận từng nhiệm vụ, sửa nếu cần, rồi giao theo lô. Mỗi đoạn giao việc chỉ sinh nhiệm vụ một lần; văn bản có thể bỏ qua có lý do hoặc gộp làm căn cứ bổ sung của nhiệm vụ cũ.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-05 "Mô tả các bước" bước 1b, 2 (L1425–1426, L1435), BR-05-05, BR-05-07 (L1457–1471), "Ngoại lệ và xử lý" (không trích được, ai-gateway lỗi, gửi nhầm, L1479–1481), SCR-0502 (L1536–1584, API L1570–1575); mục "Quy ước về nội dung do AI tạo" (L255–259).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Bảng task.task_source_doc".
- `contracts/openapi/modules/tasks.yaml` nhóm "Văn bản đến" (`listSourceDocs`, `getSourceDoc`, `dismissSourceDoc`, `createTaskFromDocument`, `mergeSourceDocIntoTask`, `assignTaskBatch`); `contracts/openapi/modules/platform.yaml` `getDocExtraction`, `/ai/suggestions/{id}/decision`.

## 3. Phạm vi công việc

- Điểm lệch cần lưu ý (phải chốt trước khi viết mã): hợp đồng dùng `task.task_source_doc.id` làm mã văn bản đến ngay khi chưa có nhiệm vụ, nhưng TL-03 đặt `task_source_doc.task_id` NOT NULL và UQ dùng cột `doc_ref` không tồn tại. Task đề xuất một bảng tiếp nhận văn bản đến chờ xử lý trong schema `task` (đánh dấu "bổ sung", tên do người phụ trách CSDL chốt) giữ trạng thái `CHO_XU_LY`, `DA_XU_LY`, `DA_BO_QUA` và trạng thái trích; `task_source_doc` giữ đúng nghĩa liên kết văn bản–nhiệm vụ. Ghi lựa chọn vào `wiki/14` và báo cáo.
- Viết consumer EVT-24 (inbox khử trùng): lưu văn bản đến cho đơn vị nhận với trạng thái `CHO_XU_LY`. Theo baseline, SVC-14 cũng tiêu thụ EVT-24 và tự trích, tự chạy lại khi phục hồi; MOD-02 không gửi yêu cầu trích mà đọc kết quả qua `getDocExtraction` (`DANG_TRICH`, `XONG`, `KHONG_TRICH_DUOC`) khi người dùng mở văn bản. Thông báo MSG-05-06 và MSG-05-10 phát qua EVT-21 bởi bên biết kết quả trích (SVC-14, suy luận); task ghi rõ phân công này trong `wiki/14` và phối hợp với chủ task DV-14-04.
- Hiện thực `listSourceDocs`, `getSourceDoc` (toàn văn, đoạn, người nhận; chỉ đơn vị nhận xem), `dismissSourceDoc` (lý do bắt buộc).
- Hiện thực `createTaskFromDocument` (Idempotency-Key): tạo nhiệm vụ `NHAP`, `source='VAN_BAN'`, ghi `task_source_doc` với `paragraph_no`, `ai_extracted`, danh sách ô AI giữ nguyên và ô đã sửa (BR-05-05); đoạn đã sinh nhiệm vụ trả 409 `TASK-409-DUPLICATE-PARAGRAPH` (MSG-05-09, BR-05-07); chạy kiểm gần giống của CORE-02-15.
- Hiện thực `mergeSourceDocIntoTask` (văn bản thành căn cứ bổ sung của nhiệm vụ cũ, EVT-21 tới chủ trì nhiệm vụ cũ) và `assignTaskBatch` (giao các nhiệm vụ đã xác nhận, mỗi nhiệm vụ một EVT-04; nhiệm vụ AI chưa xác nhận bị chặn với MSG-05-08).
- Bảo đảm không gửi dữ liệu C3 tới SVC-14 và nội dung văn bản đến được coi là dữ liệu không tin cậy khi đưa vào lời nhắc (chống chèn lệnh, việc lọc chính ở SVC-14).
- Gợi ý tách: (a) bảng tiếp nhận, consumer EVT-24, đọc kết quả trích; (b) các API xem, bỏ, xác nhận, gộp, giao theo lô.

## 4. Ngoài phạm vi

- Bộ kết nối vOffice, iOffice và phản hồi gửi nhầm (DV-13-03); mô hình trích và `ai.suggestion_log` (DV-14-04).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/main/resources/db/migration/task/**` (chỉ migration mới cho bảng tiếp nhận bổ sung)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, kiểm thử tích hợp Testcontainers PostgreSQL + Kafka, stub HTTP cho SVC-14.
- EVT-24 nhận hai lần chỉ tạo một văn bản đến; khi SVC-14 báo `KHONG_TRICH_DUOC` hoặc lỗi, màn hình vẫn đọc được văn bản và người giao tạo nhiệm vụ thủ công được.
- Xác nhận cùng đoạn hai lần trả 409 `TASK-409-DUPLICATE-PARAGRAPH`; giao theo lô có nhiệm vụ AI chưa xác nhận bị chặn với MSG-05-08.
- Từ EVT-24 tới lúc văn bản hiện trong `listSourceDocs` ≤ 5 giây (suy luận, để phần trích ≤ 1 phút của SVC-14 không bị lõi làm chậm).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

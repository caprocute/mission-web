# DV-06-12 · ADR-04/SCR-1504 Mẫu BPMN duyệt kép chỉ huy và cấp uỷ `duyet-kep-chi-huy-cap-uy`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-06-12` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 (lực lượng vũ trang, bản cách ly) |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | DV-06-04 (mẫu `duyet-ho-so-danh-gia`, ký số qua EVT-18, timer ngày làm việc); DV-06-03 (linter mẫu), DV-06-08 (`wf_binding`) (bổ sung) |
| Truy vết | ADR-04 (tầng 2 Flowable cho luồng duyệt nhiều bước) → QT-15 → SCR-1504 (mẫu `duyet-kep-chi-huy-cap-uy` v1, "lực lượng vũ trang, chỉ huy song song chính uỷ") → SVC-06 → bảng `flowable.wf_template`, `flowable.wf_binding` → EVT-18 (ký số), EVT-21; BR-15-11, BR-15-12 |
| Trạng thái | Chưa bắt đầu (ngữ nghĩa duyệt kép cần người phụ trách nghiệp vụ xác nhận trước khi viết BPMN, xem mục 3) |

## 1. Mục tiêu

TL-02b liệt kê mẫu luồng `duyet-kep-chi-huy-cap-uy` v1 cho lực lượng vũ trang, nơi chỉ huy và chính uỷ (cấp uỷ) cùng duyệt song song, nhưng chưa có task nào làm mẫu này. Task này viết định nghĩa BPMN dùng chung cho mọi tenant thuộc khối, có bộ test và qua linter, để `wf_binding` của tenant lực lượng vũ trang chọn mẫu này thay cho bước duyệt một người trong các luồng duyệt hồ sơ và kế hoạch. Khi xong, một hồ sơ chỉ đi tiếp khi cả hai nhánh chỉ huy và cấp uỷ đã duyệt, và một nhánh trả lại thì cả lượt duyệt quay về người trình.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` SCR-1504 (L1541–1590, vùng 1 danh sách mẫu L1555), BR-15-11, BR-15-12 (L1367–1368), ràng buộc tham số (L1421–1428).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "ADR-04. Ba tầng điều phối luồng" (L3422), tầng 2 Flowable (L1885), "Quy tắc quản trị chống mạng nhện" (L2149–2153: mẫu dùng chung, không gắn `tenantId`, tối đa một tầng call activity), xử lý `FlowableOptimisticLockingException` khi hai nhánh song song cùng kết thúc (L1833).
- `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-02b.md` L64 (danh sách mẫu, 27 mẫu nhân phiên bản).
- Thẻ `docs/tasks/DV-06-04-bpmn-duyet-phan-2.md` (cấu trúc mẫu, ký số, timer), `DV-06-03-*.md` (linter), `DV-06-08-api-wf-binding.md`.

## 3. Phạm vi công việc

- Viết `duyet-kep-chi-huy-cap-uy` v1 dạng call activity được luồng cha gọi ở bước duyệt (đúng giới hạn một tầng): cổng song song tách hai user task "Chỉ huy duyệt" và "Cấp uỷ duyệt", ứng viên lấy từ vai trò theo `wf_binding`, cổng song song gộp; kết quả trả cha là `DUYET` khi cả hai duyệt, `TRA_LAI` khi một nhánh trả lại (nhánh còn lại bị huỷ bằng boundary hoặc event sub-process, suy luận).
- Ràng buộc tách vai trò: người duyệt nhánh chỉ huy khác người duyệt nhánh cấp uỷ và khác người trình (OPA hoặc kiểm trong listener, theo cách DV-06-04 đã dùng cho bốn mắt).
- Mỗi nhánh có timer hạn theo ngày làm việc từ tham số, nhắc trước 1 ngày (EVT-21), quá hạn chuyển người được uỷ quyền của nhánh đó; bước ký số tuỳ chọn mỗi nhánh chờ EVT-18 như DV-06-04.
- Tham số khai báo cho `wf_binding` (miền giá trị để DV-06-08 kiểm): vai trò từng nhánh, thời hạn 1–15 ngày làm việc, có ký hay không, có cho uỷ quyền hay không.
- Ngữ nghĩa cần xác nhận trước khi viết (ghi thành câu hỏi trong `wiki/14`, không tự quyết): (a) hai nhánh song song hay tuần tự khi một bên vắng; (b) một bên trả lại thì bên kia có cần ý kiến không; (c) bất đồng (một duyệt, một trả) có chuyển cấp trên không; (d) tên vai trò "chính uỷ, chính trị viên" ánh xạ vai trò baseline nào (`leader` với thuộc tính khối, suy luận). Điểm lệch cần lưu ý: TL chỉ có một dòng mô tả mẫu, mọi hành vi trên là suy luận cho tới khi được xác nhận.
- Bộ test Flowable: đường chính (hai duyệt), một nhánh trả lại, hai nhánh kết thúc cùng lúc (khoá lạc quan, thử lại), quá hạn chuyển uỷ quyền, cùng một người ở hai nhánh bị chặn; mẫu qua linter của DV-06-03.

## 4. Ngoài phạm vi

- Gói chính sách và giao diện riêng của khối lực lượng vũ trang (`fe-plugin-llvt`), bản cách ly; các mẫu khác (DV-06-03, DV-06-04).

## 5. Tệp được phép sửa

- `backend/services/workflow-service/**` (thư mục mẫu BPMN, DMN, test)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/workflow-service -am verify` chạy xanh gồm các test Flowable ở mục 3 và linter mẫu.
- Mẫu không gắn `tenantId`, không gọi tiến trình con khác, không có service task gọi đồng bộ ra ngoài (BR-15-12) – kiểm bằng linter.
- Báo cáo nêu bốn câu hỏi ngữ nghĩa và câu trả lời (nếu đã có); báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

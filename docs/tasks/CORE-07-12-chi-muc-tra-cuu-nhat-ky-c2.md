# CORE-07-12 · ADR-06/G6 Chỉ mục kiểm toán và API tra cứu nhật ký truy cập dữ liệu C2

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-07-12` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-00-05 (sự kiện truy cập C2 lên `sys.audit.v1`); NEN-012 (consumer, inbox), NEN-021 (lược đồ topic kỹ thuật), INF-11 (OpenSearch của cell) (bổ sung) |
| Truy vết | ADR-06, ADR-09; TL-01 "Tách nhật ký kiểm toán" (G6) → BR-12-10, BR-16-04 → topic `sys.audit.v1` → MOD-07 (consumer ghi chỉ mục, API tra cứu) → OpenSearch chỉ mục kiểm toán `audit-*` → API đề xuất `GET /api/v1/oversight/access-logs` (`searchAccessLogs`, chưa có trong hợp đồng) → `sys.audit.v1` (ghi lại chính việc tra cứu) |
| Trạng thái | Chưa bắt đầu (màn hình tra cứu chưa có mã SCR, xem mục 3) |

## 1. Mục tiêu

CORE-00-05 bảo đảm mỗi lần giải mã C2 phát một sự kiện kiểm toán, nhưng TL-01 còn yêu cầu các sự kiện này được ghi vào chỉ mục kiểm toán OpenSearch chỉ đọc với mọi vai trò trừ consumer ghi, lưu 5 năm (chuyển lưu trữ lạnh sau 1 năm), và cán bộ an ninh, thanh tra tra cứu qua màn hình riêng, có ghi lại chính việc tra cứu. Task này làm phần máy chủ đó: consumer đánh chỉ mục, mẫu chỉ mục và vòng đời, và API tra cứu có phân quyền. Khi xong, người có thẩm quyền trả lời được câu hỏi "ai đã xem nội dung khiếu nại hay nhận xét đánh giá của cán bộ X, lúc nào, trong vai trò gì".

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Tách nhật ký kiểm toán" (L2994–2996), "Phân loại dữ liệu C0 đến C3" (L2235–2248).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` BR-12-10 (L815), BR-16-04 (L1662), các hành động "ghi nhật ký truy cập" (L219, L662, L708, L900, L1807).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` đoạn topic kỹ thuật `sys.audit.v1` (L4470), "Sổ cái bất biến › Mục tiêu và phạm vi" (L4090–4094).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` luồng `sys.audit.v1` tới SIEM và `audit-archiver` (L2174, L2318, L3169), bảng lưu giữ của TL-03 chương "Lưu giữ, lưu trữ và xoá dữ liệu" (L4620–4642).
- Thẻ `docs/tasks/CORE-00-05-nhat-ky-truy-cap-c2.md` (trường của bản ghi truy cập), `INF-24-*.md` (lưu trữ bất biến), `INF-19-*.md` (SIEM); `wiki/15a-ma-tran-phan-quyen.md` (vai trò `inspector`).

## 3. Phạm vi công việc

- Consumer `oversight.audit-indexer` (NEN-012, inbox theo `event_id`) đọc `sys.audit.v1`, ghi theo lô vào chỉ mục `audit-<tenant>-<yyyy.MM>` (tenant pool dùng chỉ mục chung có trường `tenant_id` và bí danh lọc, theo cách TL-01 mô tả cho SVC-16); tài liệu chỉ mang siêu dữ liệu (tác nhân, phân công và vai trò đang chọn, đối tượng, `schema.bảng.cột`, mục đích, `traceparent`, thời điểm, cờ "của người khác", cờ `system`), không bao giờ chứa bản rõ hay bản mã C2.
- Viết mẫu chỉ mục và chính sách ISM: quyền ghi chỉ cấp cho tài khoản consumer, mọi vai trò khác chỉ đọc qua API; sau 1 năm chuyển sang snapshot lưu trữ lạnh trên Ceph (searchable snapshot hoặc khôi phục khi cần, suy luận theo năng lực OpenSearch 2.x), xoá sau 5 năm trừ khi có giữ pháp lý (đối chiếu DL-13).
- Bước đầu trước khi viết API: thêm vào `contracts/openapi/modules/oversight.yaml` thao tác `GET /oversight/access-logs` (`searchAccessLogs`: lọc theo người bị xem, người xem, loại đối tượng, khoảng thời gian; phân trang con trỏ `limit ≤ 200`), `owner: MOD-07`, roles `inspector` (và vai trò an ninh nếu ma trận có), `aal: 2`; chỉ thêm, chạy lint và `openapi-diff`, người phụ trách hợp đồng duyệt.
- Hiện thực `searchAccessLogs`: OPA kiểm phạm vi đơn vị của người tra cứu; mỗi lần tra cứu tự phát một sự kiện `sys.audit.v1` loại "xem nhật ký kiểm toán" (TL-01: ghi lại chính việc truy cập nhật ký); kết quả hiển thị tên người qua `org_replica`, không giải mã gì.
- Điểm lệch cần lưu ý: TL-01 nói "màn hình riêng" cho cán bộ an ninh và thanh tra nhưng TL-02 không có mã SCR và ma trận chưa có dòng chức năng; không tự đặt mã SCR, ghi đề nghị cấp mã và dòng ma trận vào `wiki/14`; task WEB làm màn hình sau khi có mã.
- Chỉ số: độ trễ từ sự kiện tới khi tra cứu được (mục tiêu ≤ 10 giây theo cơ chế đánh chỉ mục TL-03 mục 10.6), số tài liệu lỗi đưa vào DLQ.

## 4. Ngoài phạm vi

- Phát sự kiện truy cập (CORE-00-05); ghi sổ cái (DV-09-02); bản sao bất biến Parquet 10 năm (INF-24); luồng SIEM (INF-19); màn hình tra cứu (WEB, sau khi cấp mã SCR).

## 5. Tệp được phép sửa

- `contracts/openapi/modules/oversight.yaml` (chỉ thêm `searchAccessLogs` sau khi được duyệt)
- `frontend/projects/api-client/src/lib/generated/**` (chỉ sinh lại bằng `npm run api:gen`)
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/oversight/**`, `backend/mission-core/src/main/resources/opensearch/audit/**` (mẫu chỉ mục, ISM; vị trí suy luận)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/oversight/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh gồm Testcontainers Kafka + OpenSearch 2.x: một sự kiện truy cập C2 tra cứu được trong ≤ 10 giây; nhận trùng không tạo tài liệu trùng; hai tenant không thấy nhật ký của nhau; người không phải `inspector` bị 403; mỗi lần tra cứu sinh đúng một sự kiện kiểm toán mới.
- Kiểm thử chứng minh tài liệu chỉ mục không có trường nào thuộc danh sách cột C2; tài khoản API không có quyền ghi chỉ mục.
- `cd contracts && npm run lint` xanh, `openapi-diff` chỉ báo thêm thao tác; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

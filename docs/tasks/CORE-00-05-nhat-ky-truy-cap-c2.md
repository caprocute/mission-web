# CORE-00-05 · ADR-06/ADR-09 Nhật ký truy cập dữ liệu C2 và ghi sổ cái lần giải mã

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-00-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B4 |
| Cỡ việc | S |
| Phụ thuộc | NEN-015, DV-09-02 (bổ sung nên có: NEN-011, NEN-016 cho outbox và `AuditLogger`; CORE-00-02 cho chỗ đặt mã dùng chung trong mission-core) |
| Truy vết | ADR-06, ADR-09 → BR-12-10, BR-16-04 → TL-01 "Tách nhật ký kiểm toán" → topic `sys.audit.v1` (qua `<schema>.outbox_event`) → SVC-09 `ledger.ledger_entry` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Mỗi lần dữ liệu C2 được giải mã để một người xem (nội dung khiếu nại, giải trình, nhận xét đánh giá, góp ý kiểm điểm, minh chứng, kết quả phân nhóm), hệ thống phải để lại vết không sửa được: ai, trong ngữ cảnh phân công nào, xem đối tượng nào, lúc nào. Task này nối `FieldEncryptor` của NEN-015 với nhật ký kiểm toán: mọi lần giải mã cho người dùng phát một sự kiện kiểm toán qua outbox lên `sys.audit.v1`, để SVC-09 nối vào sổ cái và chỉ mục kiểm toán lưu 5 năm. Khi xong, các task nghiệp vụ có trường C2 tự động đáp ứng BR-12-10 mà không phải viết thêm mã ghi vết.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` BR-12-10 (L815), BR-16-04 (L1662), bảng trường SCR-1202 dòng nội dung khiếu nại (L900), hành động "Mở minh chứng" (L219, L1807) và "Xem biểu mẫu" (L662, L708) có ghi nhật ký truy cập.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Tách nhật ký kiểm toán" (L2994–2996), "Phân loại dữ liệu C0 đến C3" (L2235–2248).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` chương "Sổ cái bất biến" mục "Mục tiêu và phạm vi" (L4090–4094: ghi sổ "xem dữ liệu C2 của người khác" qua sự kiện kiểm toán), đoạn topic kỹ thuật `sys.audit.v1` (L4470).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` BR-00-08 (ngữ cảnh phiên ghi vào mọi bản ghi sổ cái).
- Kết quả NEN-015 (`FieldEncryptor`), NEN-016 (`AuditLogger`), NEN-007 (lược đồ `sys.audit.v1`), DV-09-02 (consumer sổ cái).

## 3. Phạm vi công việc

- Thêm điểm móc vào `FieldEncryptor.decrypt` (thư viện nền): khi giải mã trong ngữ cảnh yêu cầu của người dùng, ghi một bản ghi truy cập gồm `tenant_id`, `actor_id`, phân công và vai trò đang chọn (BR-00-08), loại và mã đối tượng, `schema.bảng.cột`, mục đích DEK, `traceparent`, thời điểm, cờ "của chính mình" hay "của người khác"; không bao giờ ghi bản rõ hay bản mã.
- Gom theo yêu cầu: một yêu cầu giải mã nhiều trường/dòng phát một sự kiện kiểm toán liệt kê các đối tượng (giới hạn kích thước, chia lô khi vượt) để không làm phình outbox; giải mã do tác vụ hệ thống (tính điểm, sinh biểu mẫu) ghi với tác nhân là tài khoản dịch vụ của mission-core và cờ `system=true` (suy luận cả cách gom lẫn cách ghi tác nhân, ghi vào báo cáo).
- Phát qua `OutboxWriter` lên `sys.audit.v1` trong cùng giao dịch đọc (giao dịch chỉ đọc vẫn ghi được outbox nhờ tài khoản `svc_`; nếu cấu hình bản sao đọc đang bật thì chuyển ghi vết sang giao dịch riêng trên bản chính, suy luận); lỗi ghi vết thì từ chối trả dữ liệu C2 (thà không hiển thị còn hơn hiển thị không vết).
- Cung cấp annotation `@C2Access(objectType)` cho use case đọc tệp C2 không đi qua `FieldEncryptor` (mở minh chứng, tải biểu mẫu) để ghi cùng loại sự kiện.
- Kiểm thử tích hợp (PostgreSQL, Kafka, Apicurio): đọc một trường C2 mẫu ở MOD-07 giả lập tạo đúng một sự kiện `sys.audit.v1` có đủ trường; đọc danh sách 50 dòng tạo một sự kiện gom; lỗi outbox làm phản hồi thất bại; nhật ký vận hành không chứa nội dung C2.

## 4. Ngoài phạm vi

- Ghi sổ cái, niêm phong: DV-09-02, DV-09-03; chỉ mục kiểm toán OpenSearch và màn hình tra cứu nhật ký: chưa có hàng riêng, ghi vào báo cáo.
- Trường C2 cụ thể của từng module: các task CORE-04, CORE-06, CORE-07, CORE-03 dùng lại cơ chế này.

## 5. Tệp được phép sửa

- `backend/libs/vt-platform-starter/**` (gói `crypto`, `audit`: điểm móc và annotation)
- `backend/mission-core/src/test/**` (kiểm thử tích hợp)
- `wiki/20-bao-mat-phia-may-chu.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-platform-starter,mission-core -am verify` chạy xanh với các kịch bản ở mục 3.
- Sự kiện phát ra hợp lệ theo lược đồ Avro `sys.audit.v1` của NEN-007 và được consumer sổ cái của DV-09-02 nhận (kiểm thử chung nếu DV-09-02 đã gộp; nếu chưa, ghi rõ trong báo cáo).
- Chi phí thêm cho một lần đọc có giải mã dưới 1 ms p95 trong kiểm thử (báo số đo).
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 20 (nếu đã có) mục "Nhật ký truy cập C2".

## 7. Báo cáo

Theo AGENTS.md mục 6.

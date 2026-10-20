# LEG-06 · Đợt 0b/ADR-20 APISIX đứng trước hệ cũ, OTel Java agent, log JSON, Fluent Bit

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh LEG-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b – nền vận hành tối thiểu (6 tuần) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | LEG-05, DV-01-01, INF-14 |
| Truy vết | ADR-20, ADR-12; NFR-10; TL-04 "Đợt 0b" (APISIX trước hệ cũ kèm `traceparent`, OTel agent, Fluent Bit thay LogCenter); SVC-01 |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền truy cập hệ cũ |

## 1. Mục tiêu

Mọi lưu lượng vào hệ cũ đi qua APISIX (SVC-01), là điểm Strangler cho các đợt sau, và mỗi request có trace xuyên bốn tầng trình duyệt → APISIX → dịch vụ → Oracle xem được trên Tempo; nhật ký JSON của hệ cũ đi qua Fluent Bit vào Loki thay LogCenter. Điều kiện chuyển bước đợt 0b: một trace đi qua đủ bốn tầng, tỷ lệ request có trace đầu cuối ≥ 90%.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/HA-TANG.md`, `docs/quy-dinh/BACKEND.md`.
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`: "Đợt 0b" (dòng 3899–3913), chương "Quan sát hệ thống" (dòng 3253: OTel Collector, Tempo, Loki, Fluent Bit, chỉ số `trace_completeness_ratio`).
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`: "Chuyển dần theo Strangler Fig với APISIX" (dòng 1428), "ADR-20".
- Kết quả DV-01-01 (ApisixRoute, JWT qua JWKS), INF-14 (Collector, Tempo, Loki, Fluent Bit), LEG-05.
- Đầu vào người dùng cần cấp:
  - Quyền đổi DNS hoặc cân bằng tải để chuyển lưu lượng hệ cũ qua APISIX, kèm khung giờ và phương án quay lui.
  - Quyền ghi các repo hệ cũ (thêm OTel Java agent, truyền `traceparent` từ phía web cũ nếu cần).
  - Danh sách tên miền, chứng chỉ TLS đang dùng cho hệ cũ.

## 3. Phạm vi công việc

- Viết `ApisixRoute` cho hệ cũ theo đường dẫn, upstream `backend-missions-legacy` (tên theo TL-01), giữ nguyên hành vi xác thực hiện tại của hệ cũ trong đợt này; bật plugin truyền và sinh `traceparent`, giới hạn tốc độ mặc định.
- Gắn OpenTelemetry Java agent cho mọi dịch vụ hệ cũ qua chart, xuất về OTel Collector; bật instrument JDBC để thấy span Oracle.
- Chuẩn hoá nhật ký JSON có `trace_id`, không chứa token hay dữ liệu C2 (giữ kết quả LEG-04); Fluent Bit thu stdout, đẩy Loki; lập kế hoạch tắt LogCenter sau khi chạy song song.
- Thêm `traceparent` từ giao diện ZK cũ nếu khả thi; nếu không, ghi rõ trace bắt đầu từ APISIX và đề xuất cách đo tầng trình duyệt.
- Dựng bảng Grafana cho hệ cũ: RED theo route, tỷ lệ trace đầu cuối, kết nối Oracle.
- Chuyển lưu lượng theo từng bước (một tenant hoặc một đường dẫn trước), có bước quay lui đổi DNS về như cũ.

## 4. Ngoài phạm vi

- Định tuyến theo tenant sang nền mới (LEG-10); APISIX là điểm kiểm JWT duy nhất cho nền mới (DV-01-01).

## 5. Tệp được phép sửa

- Cấu hình agent, Logback trong các repo hệ cũ (đường dẫn do người dùng cấp)
- `deploy/environments/<môi-trường>/legacy/**` (ApisixRoute, giá trị OTel, Fluent Bit cho hệ cũ)
- `ops/runbooks/apisix-truoc-he-cu.md`

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template`, `kubeconform` không lỗi cho manifest mới.
- Trên môi trường thử: tìm thấy trên Tempo một trace có span APISIX, dịch vụ hệ cũ và Oracle (và trình duyệt nếu khả thi); `trace_completeness_ratio` ≥ 0,9 trong 1 giờ tải nền.
- Nhật ký hệ cũ xem được trên Loki theo `trace_id`; không có `eyJ` hay `token=` trong nhật ký.
- Quay lui DNS được diễn tập một lần; báo cáo theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

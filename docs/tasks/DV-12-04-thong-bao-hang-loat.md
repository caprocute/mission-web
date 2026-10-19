# DV-12-04 · SVC-12/NFR-06 Thông báo hàng loạt cuối kỳ chia theo tenant, giới hạn tốc độ theo kênh

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-12-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B7 |
| Cỡ việc | S |
| Phụ thuộc | DV-12-03 |
| Truy vết | QT-08, QT-09, QT-11 (nhắc nộp hồ sơ cuối kỳ) → SVC-12 → bảng `notify.notification`, `notify.delivery` → nhận EVT-21 → NFR-06 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Xử lý các đợt thông báo lớn cuối kỳ (ví dụ nhắc 1 triệu người nộp hồ sơ) bằng cách chia nhỏ theo tenant, xếp hàng công bằng giữa các tenant và giới hạn tốc độ theo kênh để không làm nghẽn cổng SMS hay làm chậm thông báo thường. Khi xong, một đợt 1 triệu người nhận hoàn tất trong khoảng thời gian tính được từ giới hạn kênh, trong khi thông báo thường vẫn đến đúng hạn.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-12 notification-service" (L1166, đoạn thông báo hàng loạt cuối kỳ), "Giới hạn tốc độ và cắt tải" (L2882), "San phẳng đỉnh bằng hàng đợi" (L2773).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Chính sách theo loại dữ liệu" (L4490, giới hạn tần suất 60 giây trong Valkey), từ điển `notify.notification` (L3809, khoảng 40 triệu thông báo/tháng cao điểm).
- `00-co-so-thiet-ke-chung.md` NFR-06.
- Bản trích `extract/tl01-thanh-phan.md` mục C11.5.

## 3. Phạm vi công việc

- Nhận diện đợt hàng loạt từ EVT-21 có danh sách người nhận lớn hoặc cờ hàng loạt trong phong bì (theo lược đồ EVT-21 của NEN-007; không thêm trường nếu lược đồ chưa có, khi đó dùng ngưỡng số người nhận) và tách thành lô theo tenant.
- Hàng đợi công bằng giữa tenant (round-robin hoặc token bucket theo tenant) để một tenant lớn không chiếm hết năng lực kênh.
- Giới hạn tốc độ theo kênh dùng bộ đếm Valkey (khoá theo kênh, cửa sổ 60 giây), cấu hình được theo môi trường; thông báo ưu tiên cao và thông báo thường không đi qua hàng đợi hàng loạt.
- Metric tiến độ đợt (đã gửi/tổng theo kênh, theo tenant) và ước tính thời gian còn lại.
- Ghi vào wiki trang hạ tầng hoặc trang 14 công thức tính thời gian đợt theo giới hạn kênh (suy luận vì TL-01 không ghi con số).

## 4. Ngoài phạm vi

- Ngưỡng KEDA cho worker kênh (INF-16); nội dung mẫu nhắc của từng quy trình (task CORE tương ứng).

## 5. Tệp được phép sửa

- `services-go/notification/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/notification && go test ./...` xanh, gồm kiểm thử: hai tenant 100.000 và 1.000 người nhận cùng lúc, tenant nhỏ hoàn tất mà không chờ tenant lớn; tốc độ gửi SMS không vượt giới hạn cấu hình trong mọi cửa sổ 60 giây.
- Trong lúc đợt hàng loạt chạy, thông báo thường có độ trễ `INAPP` p95 ≤ 2 giây (cùng tiêu chí PERF-22).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

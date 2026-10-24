# DV-15-10 · QT-06/SCR-0601 Dự báo nguy cơ trễ nhiệm vụ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-15-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-15-01; CORE-02 (cột `task.task.delay_risk` và cổng ghi nội bộ) (bổ sung) |
| Truy vết | QT-06 bước 2 (MSG-06-02) → SCR-0601 (trường "Dự báo trễ hạn") → SVC-15 → ClickHouse `fact_task`, `fact_task_progress`; đích `task.task.delay_risk` (`THAP`, `VUA`, `CAO`) → EVT-05 (nguồn kích hoạt) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tính nguy cơ trễ hạn của mỗi nhiệm vụ đang thực hiện từ nhịp thực hiện (lịch sử tiến độ, sản phẩm đã nộp) so với số ngày làm việc còn lại, xếp mức thấp, trung bình, cao kèm giải thích, và cập nhật ≤ 5 phút sau mỗi lần cập nhật tiến độ. Khi xong, SCR-0601 hiển thị mức nguy cơ và MSG-06-02 cho nhiệm vụ mức Cao.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-06 "Thông tin chung" (L1672), "Mô tả các bước" (L1698, bước 2), SCR-0601 bảng trường (L1775, trường 6), thông điệp MSG-06-02 (L1794), liên kết thiết kế (L1849).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `task.task` (L2270: `delay_risk varchar(8)` CHECK IN (`THAP`, `VUA`, `CAO`), C1).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-15 analytics" (L1194–1204).
- Bản trích `extract/tl02a-may-chu.md` QT-06 (BE-06-8).

## 3. Phạm vi công việc

- Viết bộ tính trong `services-py/anomaly-job/` hoặc dịch vụ nhỏ cùng gói (suy luận; tách tệp `delay_risk/`): tiêu thụ EVT-05 TienDoDaCapNhat, đọc lịch sử tiến độ và sản phẩm của nhiệm vụ từ ClickHouse, tính số ngày làm việc cần theo nhịp hiện tại (n) và số ngày còn lại (m) theo lịch ngày làm việc.
- Xếp mức `THAP`, `VUA`, `CAO` theo tỷ lệ n/m (ngưỡng mặc định là suy luận, đặt trong cấu hình và ghi wiki); giải thích dạng dữ liệu (n, m, nhịp) để giao diện dựng MSG-06-02, không sinh câu tự do.
- Ghi kết quả về `task.task.delay_risk` qua cổng nội bộ của MOD-02 (API dịch vụ có `If-Match` hoặc sự kiện mà MOD-02 tiêu thụ), không ghi thẳng vào schema `task` (xem điểm lệch).
- Bảo đảm độ trễ từ EVT-05 tới khi `delay_risk` cập nhật ≤ 5 phút ở p95; khi CDC chậm thì giữ giá trị cũ, không xoá.
- Chỉ dùng dữ liệu C0, C1 và mã giả danh; không đọc nội dung ghi chú tiến độ.
- Điểm lệch cần lưu ý: TL-02a ghi `delay_risk` "ghi bởi SVC-15" nhưng TL-03 chương 2 (L143–151, sở hữu dữ liệu theo service, mỗi schema có tài khoản riêng) không cho SVC-15 ghi schema `task`, và hợp đồng `tasks.yaml` chưa có operation nội bộ cập nhật `delay_risk`; task đề xuất bổ sung operation nội bộ (hoặc sự kiện) do CORE-02 hiện thực, chờ người phụ trách hợp đồng duyệt. TL-02a dùng nhãn "trung bình", TL-03 dùng giá trị `VUA`; giữ `VUA` trong dữ liệu và nhãn "Trung bình" trên giao diện.

## 4. Ngoài phạm vi

- Cột `delay_risk` và hiển thị trên SCR-0601 (CORE-02, WEB); thông báo cho quản lý khi mức Cao (nếu có, DV-12).

## 5. Tệp được phép sửa

- `services-py/anomaly-job/**` (thư mục `delay_risk/`)
- `data/golden/delay-risk/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-py/anomaly-job && ruff check . && mypy app && pytest` xanh; `pip-audit` sạch.
- Kiểm thử với bộ ca vàng `data/golden/delay-risk/` (ít nhất mười nhiệm vụ với n, m tính tay): mức đúng 100%.
- Kiểm thử tích hợp Testcontainers Kafka + ClickHouse + mock cổng MOD-02: từ EVT-05 tới lời gọi cập nhật ≤ 5 phút; phát lại EVT-05 cũ không làm lùi giá trị.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

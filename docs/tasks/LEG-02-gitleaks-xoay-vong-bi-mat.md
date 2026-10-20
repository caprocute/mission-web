# LEG-02 · SEC-01/SEC-09 Quét toàn bộ lịch sử bằng gitleaks, xoay vòng bí mật, dọn lịch sử Git

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh LEG-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0a – khắc phục khẩn (2 tuần) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày phần agent; xoay vòng phụ thuộc người giữ bí mật) |
| Phụ thuộc | LEG-01 |
| Truy vết | S01; SEC-01, SEC-09; TL-04 "Khắc phục khẩn năm lỗ hổng nghiêm trọng S01 đến S05" và "Đợt 0a"; tiêu chí D1 |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền truy cập hệ cũ |

## 1. Mục tiêu

Không còn bí mật nào trong mã và lịch sử Git của các repo hệ hiện hành: mọi bí mật bị lộ (ước tính khoảng 65 phát hiện) đã được xoay vòng theo đúng thứ tự TL-04, lịch sử đã được dọn và đẩy lại, và gitleaks chặn ở pre-commit lẫn pipeline để không lộ lại. Đây là điều kiện chuyển bước của đợt 0a ("quét bí mật toàn kho = 0").

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/BACKEND.md`, `docs/quy-dinh/HA-TANG.md` mục 2 (bí mật).
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`, mục "Khắc phục khẩn năm lỗ hổng nghiêm trọng S01 đến S05" (dòng 2443–2468: lệnh gitleaks, thứ tự xoay vòng, `git filter-repo`), "Danh mục SEC và ánh xạ trục D" (SEC-01, SEC-09), "Đợt 0a" (dòng 3883).
- Kết quả LEG-01 (vị trí đã che của các bí mật).
- Đầu vào người dùng cần cấp:
  - Quyền quản trị các repo hệ cũ (đẩy cưỡng bức, sửa bảo vệ nhánh, bật chặn đẩy bí mật).
  - Người giữ quyền xoay vòng từng loại bí mật (kubeconfig sản xuất, khoá RSA ký JWT, mật khẩu Oracle, SSO, VNeID) và khung giờ bảo trì được duyệt.
  - Kênh thông báo tới mọi người đang có bản clone để họ clone lại.

## 3. Phạm vi công việc

- Chạy `gitleaks git --redact --report-format sarif` trên toàn bộ lịch sử từng repo; phân loại phát hiện (thật, giả, đã hết hiệu lực); lưu báo cáo SARIF ở nơi lưu trữ nội bộ có kiểm soát, không đưa vào repo `mission-web`.
- Lập danh sách xoay vòng theo thứ tự TL-04: kubeconfig sản xuất → khoá RSA ký JWT → mật khẩu Oracle → SSO → VNeID; với mỗi mục ghi người thực hiện, thời điểm, cách kiểm chứng bí mật cũ đã vô hiệu.
- Đưa bí mật ra biến môi trường hoặc Secret tạm thời (OpenBao tối thiểu thuộc LEG-04) và sửa mã đọc cấu hình tương ứng.
- Sau khi xoay vòng xong: dọn lịch sử bằng `git filter-repo --invert-paths` và `--replace-text`, đẩy cưỡng bức, yêu cầu clone lại, bật chặn đẩy bí mật trên máy chủ Git.
- Thêm gitleaks vào pre-commit và vào pipeline hiện có của từng repo ở chế độ chặn (SEC-09); thêm cấu hình cho phép có kiểm soát nếu có phát hiện giả.
- Viết runbook xoay vòng và dọn lịch sử có bước kiểm chứng và quay lui (giữ bản sao lịch sử cũ ở nơi cách ly có hạn xoá).

## 4. Ngoài phạm vi

- Vá S02–S05 (LEG-03); CORS, cookie, token trong URL và nhật ký, OpenBao tối thiểu (LEG-04).

## 5. Tệp được phép sửa

- Trong các repo hệ cũ (đường dẫn do người dùng cấp): `.gitleaks.toml`, `.pre-commit-config.yaml`, tệp pipeline hiện có, tệp cấu hình đọc bí mật từ biến môi trường
- `ops/runbooks/xoay-vong-bi-mat-he-cu.md` trong repo này

## 6. Tiêu chí hoàn thành

- `gitleaks git` trên toàn lịch sử mọi repo hệ cũ trả 0 phát hiện (đo lại sau khi đẩy cưỡng bức).
- Mọi bí mật trong danh sách có bằng chứng đã vô hiệu (đăng nhập bằng giá trị cũ thất bại).
- Một commit thử chứa bí mật giả bị pre-commit và pipeline chặn.
- Báo cáo không chứa bất kỳ giá trị bí mật nào; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

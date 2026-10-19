# MOB-10 · NFR-11/SVC-18 Pipeline build, ký và phát hành ứng dụng; kiểm thử đầu cuối và khả năng truy cập trên di động

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh MOB-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – quy mô quốc gia (ứng dụng di động) |
| Luồng đề xuất | B10 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | MOB-03, INF-06 (nên chạy lại sau khi MOB-04..MOB-09 gộp để phủ đủ màn hình) |
| Truy vết | SVC-18; NFR-11, NFR-08; SEC-09 (gitleaks), SEC-23, SEC-37 (chuỗi cung ứng, theo TL-04) → pipeline `vt-pipeline` (INF-06) → kho bí mật OpenBao (khoá ký ứng dụng) |
| Trạng thái | Bị chặn bởi bên ngoài: cần tài khoản nhà phát triển Google Play và Apple cho bước phát hành; phần build, ký nội bộ, kiểm thử làm được ngay |

## 1. Mục tiêu

Ứng dụng di động có pipeline Jenkins dùng thư viện `vt-pipeline`: phân tích tĩnh, kiểm thử, kiểm thử đầu cuối trên trình giả lập, kiểm tra khả năng truy cập cho mọi màn hình ở chế độ thường và chế độ cao tuổi, quét bí mật và phụ thuộc, build bản Android và iOS có ký, kèm SBOM, rồi phát hành nội bộ và (khi có tài khoản) lên cửa hàng. Bản build lỗi kiểm thử, có bí mật hoặc lỗ hổng mức cao bị chặn như với dịch vụ máy chủ.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/DI-DONG.md`, `docs/quy-dinh/HA-TANG.md`.
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, chương "Kiểm thử và tích hợp, triển khai liên tục" (tháp kiểm thử, cổng chất lượng).
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`, mục "Chuỗi cung ứng phần mềm: SLSA, Jenkins, Argo CD" và danh mục SEC liên quan (SEC-09, SEC-23, SEC-37).
- TL-02a `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`, mục "Khả năng truy cập", "Chế độ chữ lớn và người dùng cao tuổi"; baseline NFR-11.
- Kết quả INF-06 (giai đoạn Flutter trong `vt-pipeline`); `wiki/11-kiem-thu-va-chat-luong.md`.
- Đầu vào người dùng cần cấp: tài khoản Google Play Console và Apple Developer của đơn vị chủ quản, quyết định kênh phân phối nội bộ (MDM hoặc kênh thử nghiệm của cửa hàng), máy build macOS cho iOS.

## 3. Phạm vi công việc

- Viết `mobile/Jenkinsfile` gọi `vt-pipeline`: `flutter analyze`, `flutter test --coverage` (ngưỡng phủ theo quy định chung cho mã mới), `integration_test` trên trình giả lập Android (và iOS khi có máy macOS), gitleaks, quét phụ thuộc Dart, sinh SBOM CycloneDX.
- Viết bộ kiểm thử đầu cuối các hành trình di động chính: đăng nhập, cập nhật tiến độ, check-in KR, nộp minh chứng, tự đánh giá và chờ ký, chạy với máy chủ giả lập theo hợp đồng (và tuỳ chọn với môi trường dev thật).
- Viết bộ kiểm tra khả năng truy cập tự động cho mọi màn hình ở cả chế độ thường và chế độ cao tuổi bằng `meetsGuideline` (tương phản, vùng chạm Android và iOS, nhãn), cùng phép thử cỡ chữ hệ thống lớn nhất.
- Build `appbundle` và `ipa` theo ba môi trường; khoá ký Android và chứng chỉ iOS lấy từ OpenBao lúc build, không có trong repo hay nhật ký; ghi phiên bản và mã build tự động.
- Bước phát hành nội bộ và bước đẩy cửa hàng tách riêng, bước đẩy cửa hàng chỉ bật khi có tài khoản.
- Viết runbook phát hành và thu hồi bản phát hành lỗi, có bước kiểm chứng và quay lui.

## 4. Ngoài phạm vi

- Thư viện `vt-pipeline` và giai đoạn Flutter dùng chung (INF-06); tạo tài khoản cửa hàng (người dùng).

## 5. Tệp được phép sửa

- `mobile/Jenkinsfile`, `mobile/integration_test/**`, `mobile/test/**`, cấu hình build trong `mobile/android/**`, `mobile/ios/**` (không chứa khoá thật)
- `ops/runbooks/phat-hanh-ung-dung-di-dong.md`
- `wiki/11-kiem-thu-va-chat-luong.md` (mục di động)

## 6. Tiêu chí hoàn thành

- Pipeline chạy xanh trên nhánh thử; ba yêu cầu gộp cố ý (kiểm thử lỗi, bí mật giả, phụ thuộc có lỗ hổng mức cao) đều bị chặn.
- Bộ đầu cuối chạy xanh trên trình giả lập Android; iOS chạy xanh hoặc được ghi rõ là chưa có máy build.
- Kiểm tra khả năng truy cập đạt cho 100% màn hình đã có ở hai chế độ.
- Có bản build ký nội bộ tải được từ kênh nội bộ; bước phát hành cửa hàng ghi rõ trạng thái chờ tài khoản.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

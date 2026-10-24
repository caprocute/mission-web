# INF-27 · SEC-42/SEC-58 Quản lý truy cập đặc quyền PAM cho quản trị hạ tầng và CSDL

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-27` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 (bắt buộc trước khi vận hành dữ liệu thật; thuộc hồ sơ cấp độ 4) |
| Luồng đề xuất | B8 |
| Cỡ việc | M |
| Phụ thuộc | INF-13 (OpenBao, PKI); INF-19 (RBAC sáu vai trò, luồng SIEM), INF-11 (vai trò `dba_ops` trên CNPG) (bổ sung) |
| Truy vết | SEC-42 (PAM ghi phiên truy cập đặc quyền, D6, cấp độ 4), SEC-29 (RBAC), SEC-58 (DBA không đọc được C2, phá kính) → TL-04 mục 9.3 (L2693–2703), L2971, L3163, L3169 → Kubernetes `ClusterRoleBinding` có thời hạn, PostgreSQL vai trò `dba_ops`, OpenBao, HSM → SIEM; VH-07 |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

TL-04 không cho ai có quyền cao thường trực: quyền `debug-app`, `operate-data`, `platform-admin` và quyền phá kính đọc dữ liệu nghiệp vụ của DBA chỉ được cấp có thời hạn qua hệ thống PAM, có người duyệt, và toàn bộ phiên được ghi hình, lưu 1 năm, báo cáo hằng tháng (SEC-42). INF-19 đã làm RBAC nhưng ghi PAM là việc cần hệ thống thật. Task này tích hợp giải pháp PAM được chọn với Keycloak vận hành, Kubernetes, CSDL, OpenBao và HSM, sao cho mọi đường vào đặc quyền đều đi qua PAM và có vết trong SIEM. Khi có sản phẩm, kiểm chứng được: không còn đường nào cấp `cluster-admin` hay đọc bảng nghiệp vụ mà không qua yêu cầu, duyệt và ghi phiên.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "RBAC (SEC-29)" và bảng vai trò (L2693–2703: PAM cấp `ClusterRoleBinding` 4 giờ, `platform-admin` hai người duyệt, phá kính), "DBA không đọc được dữ liệu C2 (SEC-58)" (L2971: `dba_ops`, phá kính hai người duyệt trong đó một từ khách hàng, đọc có thời hạn 2 giờ), bảng cấp độ 4 (L3163: PAM có ghi phiên, lưu 1 năm, báo cáo hằng tháng), SOC và SIEM (L3169), ngân sách kết nối quản trị qua PAM (L799: 10 kết nối mỗi cụm).
- `docs/quy-dinh/HA-TANG.md`; thẻ `docs/tasks/INF-19-bao-mat-nen-tang.md` (RBAC, ngoài phạm vi PAM), `INF-13-openbao-pki-hsm.md`, `VH-07-ho-so-cap-do-4.md`.
- Đầu vào người dùng cần cấp: sản phẩm PAM được chọn (thương mại hoặc mã nguồn mở) và môi trường thử; danh sách người duyệt phía khách hàng cho phá kính.

## 3. Phạm vi công việc

- Lập bảng yêu cầu chức năng để chọn sản phẩm (không tự chọn): yêu cầu và duyệt nhiều cấp, cấp quyền có thời hạn, ghi hình phiên SSH, `kubectl`, `psql`, giao diện web, lưu 1 năm bất biến, tích hợp OIDC với Keycloak `platform-ops`, xuất sự kiện sang SIEM, chạy được ở bản cách ly; nộp cho người phụ trách để chốt.
- Kubernetes: luồng yêu cầu → duyệt → tạo `ClusterRoleBinding` hoặc `RoleBinding` có thời hạn 4 giờ cho ba vai trò cấp qua PAM, tự thu hồi khi hết hạn; `platform-admin` cần hai người duyệt; phiên `kubectl exec`, `port-forward` đi qua proxy ghi phiên của PAM.
- CSDL: tài khoản `dba_ops` (không `SELECT` bảng nghiệp vụ) chỉ lấy được qua PAM bằng thông tin động có hạn từ OpenBao database engine; quy trình phá kính cấp vai trò đọc 2 giờ khi đủ hai người duyệt (một từ khách hàng), ghi phiên; giới hạn 10 kết nối quản trị mỗi cụm theo ngân sách TL-04.
- OpenBao và HSM: thao tác quản trị (unseal thủ công, đổi chính sách, quản trị khoá) chỉ qua PAM; tài khoản root token không dùng trong vận hành thường ngày.
- Luồng sự kiện PAM (yêu cầu, duyệt, bắt đầu, kết thúc phiên, lệnh nhạy cảm) chuẩn hoá ECS và đẩy vào luồng SIEM của INF-19; báo cáo phiên PAM hằng tháng (mẫu báo cáo cho VH-07).
- Kiểm chứng "không có cửa sau": quét RBAC định kỳ tìm binding đặc quyền không do PAM tạo (CronJob cảnh báo), quét vai trò CSDL tìm người dùng có quyền đọc nghiệp vụ ngoài phá kính.
- Khi chưa có sản phẩm: chỉ làm phần quét "không có cửa sau", tài liệu tích hợp và mẫu cấu hình trung lập, ghi rõ trong báo cáo; không dựng giải pháp tạm thay PAM thật.

## 4. Ngoài phạm vi

- RBAC sáu vai trò, audit API Kubernetes, Falco (INF-19); mua sắm sản phẩm PAM, dịch vụ SOC (VH-07, ngoài kỹ thuật).
- Nối và kiểm thử với sản phẩm PAM thật và SIEM khi đã chọn sản phẩm và có môi trường; khi đó chỉ đổi cấu hình kết nối.

## 5. Tệp được phép sửa

- `deploy/charts/pam-integration/**` (mới, tên suy luận: CronJob quét, cấu hình proxy, chính sách)
- `deploy/clusters/**` (chỉ cấu hình OIDC và webhook phục vụ binding có thời hạn), `deploy/environments/kind/**`
- `ops/runbooks/pam-pha-kinh.md`, `ops/runbooks/pam-cap-quyen.md`
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template`, `kubeconform -strict` sạch; CronJob quét trên kind phát hiện một `ClusterRoleBinding` tạo tay và một vai trò CSDL thừa quyền trong kiểm thử.
- Với PAM giả lập trên kind (ví dụ một dịch vụ cấp quyền tạm qua API cùng giao diện bọc): thử cấp `debug-app` 4 giờ có ghi phiên, tự thu hồi đúng hạn; phá kính DBA đủ hai người duyệt; sự kiện cấp quyền xuất hiện ở đích nhật ký thử. Địa chỉ và tham số của sản phẩm PAM nằm trong cấu hình, nên thay bằng sản phẩm thật chỉ đổi cấu hình.
- Runbook có bước kiểm chứng và quay lui; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

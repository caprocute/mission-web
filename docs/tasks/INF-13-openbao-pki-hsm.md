# INF-13 · ADR-17, SEC-10, SEC-11, SEC-59 OpenBao năm node tự mở khoá bằng HSM, PKI nội bộ, cert-manager, External Secrets Operator

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-13` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-08 |
| Truy vết | ADR-17, ADR-30 → TL-04 mục 9.4 "Quản lý bí mật và khoá: OpenBao, ESO, HSM" → SEC-10, SEC-11, SEC-12, SEC-20, SEC-31, SEC-59 → OPS-20 → bảng `keyring.dek` → tiêu chí D1, D8 |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Dựng nền bí mật và khoá của cell theo TL-04 mục 9.4: OpenBao năm node trong namespace `secure`, tự mở khoá bằng HSM qua PKCS#11, các engine KV v2, database, Transit, PKI; cert-manager cấp chứng thư từ CA trung gian của cell; External Secrets Operator nạp bí mật vào pod; chính sách truy cập theo service. Khi xong, trên kind một pod `mission-core` thử lấy được mật khẩu CSDL động thời hạn 24 giờ và mã hoá được một DEK bằng KEK theo tenant, mà không có bí mật nào nằm trong Git.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Quản lý bí mật và khoá: OpenBao, ESO, HSM" (2866–2971), "OpenBao, HSM và khoá" ở chương sao lưu (2297–2303), "Bảo mật nền tảng Kubernetes" phần KMS v2 (2647–2663).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` Phụ lục A, ADR-17 và ADR-30.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục mã hoá (4142–4183, `keyring.dek`).
- `docs/quy-dinh/HA-TANG.md` mục 2, `docs/quy-dinh/BACKEND.md` mục 3 quy ước 8.

## 3. Phạm vi công việc

- Viết values OpenBao năm node Raft trong `secure`, seal PKCS#11 tới HSM (tham số khe, nhãn khoá trong values; PIN qua bí mật khởi tạo ngoài Git), TLS từ PKI nội bộ, audit device bật.
- Viết cấu hình dạng mã (Terraform/OpenTofu provider OpenBao, hoặc script `bao` có kiểm thử): bật auth `kubernetes` theo cell (`k8s-cell-<x>`), chính sách theo service (ví dụ `mission-core`: đọc KV của mình, `database/creds/pg-core-mission`, Transit encrypt/decrypt/datakey trên `kek-tenant-*`), role gắn đúng ServiceAccount, `token_ttl=1h`, `max_ttl=4h`.
- Cấu hình secret động PostgreSQL: `database/config/pg-core-<cell>` `sslmode=verify-full`, `rotate-root` ngay sau tạo, role `pg-core-mission` `default_ttl=24h`, `max_ttl=72h`, câu lệnh tạo role `LOGIN NOBYPASSRLS ... IN ROLE svc_<module>` và thu hồi bằng `REASSIGN OWNED`/`DROP OWNED`/`DROP ROLE`.
- Cấu hình Transit: KEK `kek-tenant-<tenantId>` kiểu `aes256-gcm96` (managed key HSM ở production), lệnh `transit/datakey/wrapped` cho DEK theo tenant × tháng × mục đích (ADR-30); khoá ký `cosign-release` cho INF-05.
- Cấu hình PKI: CA gốc ngoại tuyến (chỉ tài liệu quy trình ký, không tạo ở cụm), CA trung gian mỗi cell `pki_int_cell_<x>`, role `internal-services`; `ClusterIssuer openbao-pki-cell` của cert-manager; cảnh báo `CertificateExpiringSoon` < 14 ngày.
- Cấu hình ESO `ClusterSecretStore openbao-cell` và mẫu `ExternalSecret` (`refreshInterval 15m`, `creationPolicy Owner`, `deletionPolicy Retain`); chart `vt-service` dùng mẫu này.
- Ghi lịch xoay vòng SEC-12/OPS-20 thành CronJob hoặc cấu hình tự động (KEK năm, DEK tháng, mật khẩu CSDL 24 giờ, chứng thư 90 ngày gia hạn ngày 60) và metric `vt_key_age_days`.
- Gợi ý tách: (a) OpenBao + seal + auth + chính sách; (b) database và Transit; (c) PKI, cert-manager, ESO, xoay vòng.

## 4. Ngoài phạm vi

- Mua, lắp và khởi tạo HSM vật lý, lễ khởi tạo CA gốc, thẻ M trong N (chỉ viết quy trình trong `ops/runbooks/`).
- Thư viện mã hoá phía ứng dụng `FieldEncryptor`, `DekCache` (NEN-015); mã hoá Secret etcd bằng KMS v2 trên cụm thật (INF-19 kiểm cấu hình API server).
- Nối và kiểm thử với HSM thật cho production khi có thiết bị; khi đó chỉ đổi cấu hình seal theo môi trường, không đổi chart.

## 5. Tệp được phép sửa

- `deploy/charts/openbao-cell/**`, `deploy/charts/cert-manager-config/**`, `deploy/charts/eso-config/**` (tên suy luận), `deploy/openbao/**` (cấu hình dạng mã).
- `deploy/charts/vt-service/**` chỉ phần mẫu `ExternalSecret`; `ops/runbooks/openbao-*.md`; `deploy/environments/kind/**`.
- `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template`, `kubeconform -strict` không lỗi; `tofu plan` (nếu dùng OpenTofu) sạch trên OpenBao thử.
- Trên kind: OpenBao 3–5 node Raft tự mở khoá bằng HSM mềm (SoftHSM2 qua PKCS#11 nếu bản OpenBao hỗ trợ seal PKCS#11; nếu không, seal transit từ một OpenBao thứ hai) và mở khoá lại sau khi xoá toàn bộ pod; cấu hình seal (thư viện PKCS#11, khe, nhãn khoá) nằm trong values theo môi trường, nên đổi sang HSM thật chỉ đổi cấu hình, không đổi chart.
- Pod thử với ServiceAccount `mission-core` lấy được credential PostgreSQL động và kết nối được; ServiceAccount khác bị từ chối; lease bị thu hồi thì kết nối mới thất bại.
- cert-manager cấp chứng thư từ `openbao-pki-cell`; ESO tạo Secret từ KV; `gitleaks detect` trên nhánh không có phát hiện.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

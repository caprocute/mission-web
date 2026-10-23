> Nguồn: TL-04 mục 9.4 (SEC-10, SEC-11, SEC-12), mục 9.7; task NEN-015, INF-13. Ngày đánh giá: 23/10/2026.

# Bằng chứng 02: Khoá theo khách hàng trong HSM (SEC-11)

## Yêu cầu

Dữ liệu C2 được mã hoá phong bì: mỗi tenant có khoá mã hoá khoá (KEK) riêng, và khoá gốc nằm trong HSM. Khách hàng giữ các mảnh khoá mở theo cơ chế M trong N. TL-04 yêu cầu bằng chứng là danh sách khoá Transit và biên bản giao thẻ.

## Biện pháp đã có

Ở phía ứng dụng, `backend/libs/vt-platform-starter/src/main/java/vn/viettel/okrkpi/shared/crypto/FieldEncryptor.java` mã hoá từng trường bằng AES-256-GCM với khoá dữ liệu (DEK). DEK được sinh và bọc bằng KEK của tenant trong OpenBao Transit qua `backend/libs/vt-platform-starter/src/main/java/vn/viettel/okrkpi/shared/crypto/DekService.java`. Trường cần tìm kiếm dùng chỉ mục mù HMAC (`backend/libs/vt-platform-starter/src/main/java/vn/viettel/okrkpi/shared/crypto/BlindIndexer.java`), nên CSDL không giữ bản rõ. Việc bọc lại khi xoay KEK do `backend/libs/key-rotation/src/main/java/vn/viettel/okrkpi/platform/keyrotation/KekRewrapJob.java` thực hiện.

Ở phía hạ tầng, `deploy/openbao/cau-hinh.sh` tạo một khoá Transit `kek-tenant-<tenant>` loại AES-256-GCM96 cho mỗi tenant, với hai thuộc tính `deletion_allowed=false` và `exportable=false`, nghĩa là khoá không xoá được và không xuất ra ngoài OpenBao được. Chính sách `deploy/openbao/chinh-sach/mission-core.hcl` chỉ cho dịch vụ ứng dụng quyền mã hoá và giải mã, không cho đọc khoá. OpenBao tự mở khoá (auto-unseal) bằng HSM qua PKCS#11. Trong môi trường thử, HSM được giả lập bằng SoftHSM (`deploy/environments/kind/openbao-softhsm/Dockerfile`). Quy trình vận hành nằm ở `ops/runbooks/openbao-hsm.md` và `ops/runbooks/xoay-vong-khoa-du-lieu.md`.

## Cách thu bằng chứng

Script `thu-bang-chung.sh` liệt kê tên các khoá Transit có tiền tố `kek-tenant-` cùng cấu hình `exportable` và `deletion_allowed`, rồi ghi vào `khoa-transit.txt`. Script chỉ ghi tên và thuộc tính khoá, không ghi vật liệu khoá. Biên bản giao thẻ là giấy tờ có chữ ký, được lưu tại đơn vị chủ quản; hồ sơ chỉ ghi số biên bản khi có.

## Trạng thái và điểm còn thiếu

Trạng thái: **Đáp ứng (giả lập)**. Cơ chế đã làm xong và đã kiểm trên SoftHSM. Còn thiếu HSM thật (cần mua sắm) và biên bản giao thẻ M trong N, vì chưa có khách hàng vận hành.

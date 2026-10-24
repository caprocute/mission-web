# DV-10-03 · SVC-10/ADR-17 Nhà cung cấp HSM qua PKCS#11 và dấu thời gian TSA RFC 3161

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-10-03` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | L |
| Phụ thuộc | DV-10-01 |
| Truy vết | QT-08 (niêm phong sau chốt kỳ), QT-13 (BR-13-09), QT-11 (ký gói bằng dấu cơ quan) → SVC-10 → bảng `esign.sign_request`, `esign.sign_item`, `esign.certificate_ref` → EVT-18; phục vụ SVC-09 ký `ledger.cell_seal` → ADR-17, ADR-09 |
| Trạng thái | Chưa bắt đầu (môi trường dev dùng SoftHSM và TSA giả lập) |

## 1. Mục tiêu

Hiện thực nhà cung cấp ký bằng khoá của tổ chức nằm trong HSM qua PKCS#11 (ECDSA P-256), dịch vụ ký niêm phong nội bộ cho ledger-service, và client dấu thời gian RFC 3161 dùng chung cho mọi chữ ký PAdES-LTA và niêm phong sổ cái. Khi xong, esign-service ký được với thông lượng của HSM (cỡ 1.000 chữ ký/giây theo TL-01), khoá riêng không bao giờ rời HSM, và mọi chữ ký có token dấu thời gian kiểm được.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-10 esign-service" (L1118–1140, đoạn HSM và ký lô), "Niêm phong Merkle, dấu thời gian và bản neo" (L2601), "Ký số tại mốc pháp lý" (L2626, gia hạn dấu thời gian lưu trữ), "Các bộ kết nối" (L1409, dòng TSA), ADR-17 (L3542 trở đi, mục Phụ lục A).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` từ điển `esign.*` (L3704–3749), `ledger.cell_seal` (L3644).
- `TL-04-trien-khai-van-hanh.md` "Kịch bản riêng cho góp ý G3" (L1810, PERF-17 "HSM ≤ 50% năng lực ký/s"), mục node pool vùng bảo mật (NIC riêng vào mạng HSM) và readiness esign "kèm kiểm HSM".
- Bản trích `extract/tl01-thanh-phan.md` mục C9, `extract/tl04-phan1.md` mục WI-22.

## 3. Phạm vi công việc

- Viết `HsmPkcs11Provider` (id dùng cho chữ ký tổ chức) qua SunPKCS11 hoặc thư viện PKCS#11 Apache 2.0: nạp cấu hình slot và nhãn khoá từ OpenBao, giữ pool phiên PKCS#11 có giới hạn, ký `CKM_ECDSA` trên băm SHA-256, chuyển chữ ký sang DER và đóng CMS/PAdES.
- Viết `TsaClient` RFC 3161 (HTTP, `application/timestamp-query`): yêu cầu có nonce, kiểm token trả về (chuỗi chứng thư TSA, khớp băm, nonce), cấu hình nhiều TSA theo thứ tự ưu tiên, Resilience4j riêng; dùng cho PAdES-B-T/LTA của mọi nhà cung cấp và cho niêm phong sổ cái.
- Cung cấp dịch vụ ký niêm phong nội bộ cho ledger-service: nhận băm gốc cell, ký bằng khoá niêm phong của cell, trả chữ ký, chứng thư (`signer_cert_sha256`) và token TSA; chỉ chấp nhận gọi qua mTLS từ tài khoản dịch vụ `ledger` (AuthorizationPolicy do task hạ tầng cấu hình, ở đây kiểm danh tính bên gọi trong mã).
- Thêm kiểm tra sẵn sàng (readiness) gồm mở được phiên HSM và ký thử một băm cố định; metric số chữ ký/giây, độ trễ ký, lỗi HSM.
- Viết job gia hạn dấu thời gian lưu trữ cho tài liệu PAdES-LTA có thời hạn lưu trữ dài (chạy hằng năm, chọn tài liệu có chứng thư TSA sắp hết hạn), ghi kết quả vào `sign_item` hoặc nhật ký job (suy luận về nơi ghi).
- Thêm SoftHSM2 và TSA giả lập vào profile dev (`infra/dev`) và Testcontainers; bí mật PIN HSM chỉ ở OpenBao, tệp mẫu chỉ có giá trị giả.

## 4. Ngoài phạm vi

- Lắp đặt HSM vật lý, OpenBao tự mở khoá bằng HSM (INF-13).
- Ký lô có xác nhận một lần (DV-10-06); dựng cây Merkle (DV-09-03).

## 5. Tệp được phép sửa

- `backend/services/esign/**`
- `infra/dev/` (dịch vụ SoftHSM2, TSA giả lập trong profile backend)
- `.env.example`, `infra/dev/.env.example` (tên biến, không có giá trị thật)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/esign -am verify` xanh, gồm kiểm thử tích hợp với SoftHSM2 trong Testcontainers: ký, kiểm chữ ký ECDSA P-256 bằng khoá công khai, token TSA kiểm được bằng `openssl ts -verify`.
- Thông lượng đo trên SoftHSM ghi vào báo cáo; mục tiêu thiết kế với HSM thật là ~1.000 chữ ký/giây, chỉ dùng ≤ 50% năng lực khi chạy PERF-17 (kiểm ở QA-10).
- Kiểm thử chứng minh khoá riêng không trích xuất được (thuộc tính `CKA_EXTRACTABLE=false`) và không có giá trị khoá hay PIN trong nhật ký.
- Readiness trả không sẵn sàng khi HSM giả lập bị dừng; dịch vụ ký niêm phong từ chối bên gọi không phải `ledger`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

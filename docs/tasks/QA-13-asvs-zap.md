# QA-13 · NFR-08 Kiểm thử an toàn thông tin: OWASP ASVS 4.0.3 mức 2 (mức 3 cho V2, V3, V6) và OWASP ZAP toàn hệ thống

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-13` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (kiểm thử xâm nhập độc lập toàn diện ở đợt 3) |
| Luồng đề xuất | B9 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | QA-03 |
| Truy vết | NFR-08 → TL-04 mục 9.8 → SEC-19, SEC-39, SEC-40, SEC-41 → SVC-01 APISIX, SVC-02 Keycloak, SVC-05 và mọi service có OpenAPI → SVC-17 web-frontend (CSP) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng lớp kiểm thử bảo mật thứ hai và thứ ba của TL-04 mục 9.8: ZAP quét thụ động lưu lượng của bộ hành trình đầu cuối và quét chủ động hằng đêm theo OpenAPI của từng service, cộng bảng tự đánh giá ASVS 4.0.3 có bằng chứng cho từng mục. Khi xong, pipeline staging chặn bản phát hành có cảnh báo High, bảng ASVS mức 2 đạt 100% mục áp dụng, mức 3 cho V2, V3, V6, và gói bằng chứng sẵn sàng giao cho đơn vị kiểm thử xâm nhập độc lập.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 9.8 "Kiểm thử bảo mật: ASVS mức 2 và OWASP ZAP" (dòng 3173–3206: cấu hình ZAP Automation Framework, CSP SEC-19, tiêu chí đạt); bảng SEC mục 9.2 (dòng 2519, hàng SEC-19, SEC-39..SEC-41).
- `wiki/08-bao-mat-frontend.md` (CSP, Trusted Types, bảng kiểm khi viết mã), `wiki/07-xac-thuc-phan-quyen.md`, `wiki/15-phan-quyen.md`.
- `docs/tasks/QA-03-e2e-he-thong-that.md` (khung chạy hành trình làm nguồn lưu lượng quét thụ động), `INF-05-pipeline-jenkins-java.md` (cổng Semgrep, gitleaks, Trivy).

## 3. Phạm vi công việc

- Kế hoạch ZAP `infra/security/zap/okr-api.yaml` theo mẫu TL-04: context `okr-api`, xác thực bằng script client credentials (tên client thử, không bí mật), job `openapi` cho từng service, `activeScan` chính sách `API-Minimal`, báo cáo SARIF, `exitStatus` High lỗi, Medium cảnh báo.
- Quét thụ động: chạy bộ hành trình QA-03..QA-07 qua proxy ZAP trên staging; tổng hợp cảnh báo theo tuyến.
- Quét chủ động hằng đêm: Jenkins job gọi ZAP cho mỗi tệp `contracts/openapi/modules/*.yaml` với người dùng thử theo từng vai trò AC (để kiểm IDOR và phân quyền ngang).
- Kiểm CSP: tiêu đề trên mọi tuyến HTML khớp chuỗi SEC-19, nonce khác nhau mỗi lần tải, không vi phạm CSP khi chạy hành trình (thu `report-to`).
- Bảng tự đánh giá ASVS 4.0.3 `docs/attt/asvs-4.0.3-tu-danh-gia.md` (suy luận về vị trí): mỗi mục áp dụng có trạng thái, bằng chứng (kiểm thử, cấu hình, mã), mã SEC; mức 3 cho V2, V3, V6.
- Bộ kiểm thử phủ định tự động cho mục ASVS kiểm được bằng mã: thiếu token, token hết hạn, sai tenant, thiếu `If-Match`, lặp `Idempotency-Key` với thân khác, tải tệp sai loại, vượt `limit` 200.
- Quy trình ngoại lệ: cảnh báo Medium được chấp nhận phải có giải trình, người duyệt, hạn xem lại, lưu trong `infra/security/zap/ngoai-le.yaml`.
- Gói bằng chứng cho kiểm thử xâm nhập độc lập (SEC-41): phạm vi, tài khoản thử theo vai trò, danh mục ASVS.
- Gợi ý tách: (a) ZAP và pipeline; (b) bảng ASVS V1–V5; (c) bảng ASVS V6–V14 và bộ kiểm thử phủ định.

## 4. Ngoài phạm vi

- Thực hiện kiểm thử xâm nhập độc lập (do đơn vị độc lập bên ngoài thực hiện); sửa lỗ hổng tìm được (mở task theo phân hệ).
- Hồ sơ cấp độ 4 (VH-07).

## 5. Tệp được phép sửa

- `infra/security/zap/**`, `infra/security/kiem-thu-phu-dinh/**`, `docs/attt/asvs-4.0.3-tu-danh-gia.md`, `ci/vt-pipeline/` chỉ bước ZAP (`vtZapScan`, suy luận về tên).

## 6. Tiêu chí hoàn thành

- `zap.sh -cmd -autorun infra/security/zap/okr-api.yaml` chạy trên server dev (hoặc staging) và xuất `zap.sarif`; 0 cảnh báo High, Medium có giải trình.
- `cd infra/security/kiem-thu-phu-dinh && npm test` (hoặc Playwright API) xanh trên server dev.
- Bảng ASVS điền đủ mọi mục; số mục đạt, chưa đạt, không áp dụng được thống kê ở đầu tệp.
- Một bản build cố ý chứa lỗ hổng (ví dụ tắt CSP ở môi trường thử) bị bước ZAP chặn.

## 7. Báo cáo

Theo AGENTS.md mục 6.

### Điểm lệch cần lưu ý

- Khung kế hoạch ghi "TL-04 mục 9.7" cho hàng này; mục 9.7 là TCVN 11930, nội dung ASVS và ZAP nằm ở mục 9.8 (bảng SEC ghi SEC-40, SEC-41 thuộc mục 9.8). Thẻ dùng mục 9.8.

# MOB-07 · QT-09/SVC-10 Ký số MySign trên di động

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh MOB-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – quy mô quốc gia (ứng dụng di động) |
| Luồng đề xuất | B10 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | MOB-06, DV-10-02 |
| Truy vết | QT-09 (BR-09-15, ký tại mốc phiếu tự đánh giá) → API `POST /api/v1/esign/requests` (`createEsignRequest`), `GET /api/v1/esign/requests/{id}` (`getEsignRequest`), `GET /api/v1/esign/requests` (`listMyEsignRequests`), `GET /api/v1/esign/certificates/mine` (`listMyEsignCertificates`), `GET /api/v1/jobs/{id}` (`getJob`) → SVC-10, MOD-04 → bảng `esign.sign_request`, `esign.sign_item`, `esign.certificate_ref` → EVT-18 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Sau khi nộp phiếu tự đánh giá trên di động (MOB-06), cán bộ ký số phiếu bằng MySign: ứng dụng tạo hoặc mở yêu cầu ký, người ký xác nhận trên ứng dụng MySign, ứng dụng hiển thị "chờ ký" cho tới khi máy chủ nhận EVT-18 và hồ sơ chuyển `CHO_THAM_DINH`. Khoá ký không bao giờ nằm trên thiết bị của nền tảng; ký từ xa luôn bất đồng bộ (TL-01 SVC-10).

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/DI-DONG.md`.
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, mục "SVC-10 esign-service" (ký từ xa MySign bất đồng bộ, `SignatureProvider`, ký lô) và "Ký số tại mốc pháp lý" (chương "Toàn vẹn dữ liệu và bằng chứng pháp lý").
- TL-02b `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md`: QT-09 "Trạng thái đối tượng" (`TU_DANH_GIA` → `CHO_THAM_DINH` khi ký nộp), "Quy tắc nghiệp vụ" (BR-09-15), "Ngoại lệ và xử lý" (ký thất bại, chứng thư hết hạn), đặc tả SCR-0901.
- TL-03 mục "Schema esign (SVC-10)"; hợp đồng `contracts/openapi/modules/dossiers.yaml` (nhóm `/esign/*`), `contracts/openapi/modules/jobs.yaml`.
- `wiki/07-xac-thuc-phan-quyen.md` (step-up AAL2).
- Đầu vào bên ngoài: tài liệu tích hợp MySign cho ứng dụng di động (cách chuyển sang ứng dụng MySign, nếu nhà cung cấp hỗ trợ) và môi trường thử của MySign; khi chưa có, chỉ làm luồng chờ xác nhận trên ứng dụng MySign.

## 3. Phạm vi công việc

- Sau `submitAppraisalSelfAssessment` (202), lấy yêu cầu ký liên quan (`listMyEsignRequests` hoặc theo `Location`), hiển thị nội dung cần ký (tên phiếu, băm tài liệu rút gọn) và chứng thư sẽ dùng từ `listMyEsignCertificates`.
- Chặn ký khi chứng thư còn hạn dưới 1 ngày và hiển thị hướng dẫn gia hạn; yêu cầu xác thực bổ sung (AAL2) qua Keycloak khi máy chủ trả yêu cầu step-up.
- Tạo yêu cầu ký bằng `createEsignRequest` khi cần (kèm `Idempotency-Key`), sau đó hướng người dùng xác nhận trên ứng dụng MySign; nếu nhà cung cấp hỗ trợ liên kết chuyển ứng dụng thì mở trực tiếp.
- Theo dõi trạng thái bằng `getEsignRequest` hoặc `getJob` với khoảng thăm dò lùi dần, cập nhật khi có thông báo (MOB-09); hiển thị "chờ ký", "đã ký", "thất bại" bằng nhãn nghiệp vụ, không in mã trạng thái thô.
- Xử lý thất bại theo QT-09: hồ sơ không chuyển trạng thái, cho ký lại; ứng dụng bị đóng giữa chừng thì mở lại vẫn thấy yêu cầu đang chờ.
- Không lưu tài liệu đã ký hay dữ liệu C2 ngoài CSDL mã hoá của MOB-02.

## 4. Ngoài phạm vi

- Bộ kết nối MySign phía máy chủ (DV-10-02); ký lô quyết định xếp loại (chỉ trên máy tính); ký biên bản, gói hồ sơ.

## 5. Tệp được phép sửa

- `mobile/lib/features/esign/**`, `mobile/lib/features/appraisal/**` (chỉ điểm nối sang ký), `mobile/test/**`, `mobile/integration_test/**`, tệp ARB tiếng Việt trong `mobile/`

## 6. Tiêu chí hoàn thành

- `cd mobile && flutter analyze` sạch, `flutter test` xanh (chặn chứng thư sắp hết hạn, máy trạng thái hiển thị chờ ký, thăm dò lùi dần).
- Kiểm thử tích hợp với máy chủ giả lập: tạo yêu cầu ký hai lần cùng khoá chỉ tạo một yêu cầu; trạng thái chuyển "đã ký" khi máy chủ giả lập báo hoàn tất.
- Nếu có môi trường thử MySign: ký thành công một phiếu thử và hồ sơ chuyển `CHO_THAM_DINH`; nếu chưa có, ghi rõ trong báo cáo.
- Chạy trên trình giả lập Android và iOS (ghi rõ nền tảng chưa chạy được); báo cáo theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

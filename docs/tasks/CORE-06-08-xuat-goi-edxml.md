# CORE-06-08 · BR-11-11 Xuất gói ZIP có manifest ký và gửi gói con diện Trung ương qua EdXML

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-06-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-06-07, DV-13-05 |
| Truy vết | QT-11 bước 14 → SCR-1102 → API `POST /api/v1/dossiers/packages/{id}/export` (`exportDossierPackage`), `POST .../packages/{id}/transfers` (`createDossierPackageTransfer`) → MOD-06 → `dossier.dossier_package` (`transferred_to`, `DA_LUU_TRU`) → SVC-13 (EdXML), `integ.dead_letter` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cơ quan tổ chức xuất được gói hồ sơ thành tệp ZIP kèm manifest JSON có chữ ký để lưu trữ hoặc nộp ngoại tuyến, và gửi gói con của cán bộ diện Trung ương quản lý tới Ban Tổ chức Trung ương hoặc Văn phòng Trung ương qua trục EdXML, có ký số và mã hoá theo chứng thư bên nhận (BR-11-11, QĐ Điều 17). Gửi lỗi không chặn nộp gói chính và được thử lại theo lịch (MSG-11-06).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-11: "Mô tả các bước" (dòng 539, bước 14), BR-11-11 (dòng 579), "Ngoại lệ và xử lý" (dòng 613, gửi gói con thất bại), "SCR-1102" (dòng 675, MSG-11-06).
- TL-03 bảng `dossier_package` (dòng 3021, `transferred_to`); "Schema integ" của SVC-13.
- TL-01 chương tích hợp: bộ kết nối EdXML/X-Road và Anti-Corruption Layer.
- `contracts/openapi/modules/dossiers.yaml`: `exportDossierPackage`, `createDossierPackageTransfer` (lỗi `DOSSIER-409-PACKAGE-NOT-SIGNED`, `DOSSIER-409-NO-CENTRAL-PROFILES`, `DOSSIER-422-DESTINATION-NOT-CONFIGURED`); `contracts/openapi/modules/jobs.yaml`.
- Bản trích `tl02b §QT-11 BE-Q11-10, 11`.

## 3. Phạm vi công việc

- `exportDossierPackage` (202, `Idempotency-Key`): chỉ gói đã ký; tạo tác vụ ghép các tệp từ SVC-08 thành ZIP theo luồng (không nạp toàn bộ vào bộ nhớ), kèm manifest JSON đã ký ở bước ký gói; lưu ZIP vào kho đối tượng qua SVC-08 và trả URL tải có hạn qua tác vụ.
- `createDossierPackageTransfer` (202): chỉ gói đã ký và có hồ sơ diện TW (40 chức danh PL 2B, đọc từ `org_replica`); tách gói con theo nơi nhận (`BTC_TW`, `VPTW`), gửi tới SVC-13 qua cổng tích hợp; nơi nhận chưa cấu hình trả 422.
- Nhận kết quả gửi từ SVC-13 (sự kiện hoặc gọi lại theo cách DV-13-05 chốt, ghi rõ trong báo cáo): thành công ghi `transferred_to`; thất bại phát EVT-21 cho cơ quan tổ chức với MSG-11-06 và thời điểm thử lại; có biên nhận thì gói sang `DA_LUU_TRU`.
- Xuất và gửi đều ghi sổ cái (xuất dữ liệu hàng loạt là sự kiện kiểm toán).
- Kiểm thử với bản giả SVC-13: gửi thành công, bên nhận không phản hồi, chứng thư bên nhận hết hạn.

## 4. Ngoài phạm vi

- Bộ kết nối EdXML, mã hoá theo chứng thư bên nhận, `integ.dead_letter` và lịch thử lại (DV-13-05); kiểm thử với trục quốc gia thật (bị chặn bởi môi trường bên ngoài).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/dossier/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/dossier/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; mã lỗi khớp `dossiers.yaml`.
- Kiểm thử: gói chưa ký xuất hoặc gửi bị 409; ZIP 1 GB dựng được với bộ nhớ heap không tăng tương ứng (đo và ghi vào báo cáo); gửi lỗi không đổi trạng thái `DA_NOP` của gói chính.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.

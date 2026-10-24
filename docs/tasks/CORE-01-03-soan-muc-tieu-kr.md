# CORE-01-03 · QT-02/SCR-0202 API soạn mục tiêu và KR

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-01-02, CORE-08-03 |
| Truy vết | QT-02 → SCR-0202 → API `POST /api/v1/okr/objectives`, `PUT /okr/objectives/{id}`, `POST /okr/objectives/copy-from-period`, `POST /okr/key-results`, `POST /okr/links`, `DELETE /okr/links/{id}`, `GET /okr/compose-context` → MOD-01 → bảng `okr.objective`, `okr.key_result`, `okr.objective_basis`, `okr.okr_link` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho người soạn (AC-05, AC-06) tạo, lưu nháp và sao chép mục tiêu cùng KR theo SCR-0202 với khoá lạc quan, sinh mã tự động và chặn ghi khi kỳ đã khoá. Khi xong, màn hình soạn OKR của web và di động lưu nháp tự động mỗi 30 giây mà không ghi đè lẫn nhau, mọi mục tiêu có mã `O{n}` và mọi KR có mã `KR {o}.{n}` duy nhất trong đơn vị và kỳ.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-02 "Mô tả các bước" (L781–797, sao chép kỳ trước ở L785) và SCR-0202 (L889–940, ràng buộc trường L907–922).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Quản lý giao dịch" (L710).
- `contracts/openapi/modules/okr.yaml`: `createOkrObjective`, `updateOkrObjective`, `copyOkrObjectivesFromPeriod`, `createOkrKeyResult`, `createOkrLink`, `deleteOkrLink`, `getOkrComposeContext`; `contracts/openapi/common/common.yaml` (`IdempotencyKey`, `IfMatch`, `Problem`).
- `wiki/06-quy-uoc-api.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-0202).

## 3. Phạm vi công việc

- Hiện thực `createOkrObjective`: tạo mục tiêu `NHAP`, `version_no = 1`, ghi `objective_basis` cho từng căn cứ; sinh mã `O{n}` có khoá tuần tự theo `(tenant_id, owner_unit_id, period_id)` để không trùng khi hai người tạo cùng lúc.
- Hiện thực `updateOkrObjective` (PUT toàn bộ mục tiêu kèm danh sách KR: có `id` thì sửa, không có thì thêm, vắng mặt thì xoá) với `If-Match`; lệch `row_version` trả 409 `OKR-409-VERSION-CONFLICT`, thiếu `If-Match` trả 428, mục tiêu không ở `NHAP` trả 409 `OKR-409-INVALID-STATE`.
- Sinh mã KR `KR {o}.{n}` khi thêm và không đánh lại mã KR đã có khi xoá một KR ở giữa (suy luận, để giữ truy vết check-in và điều chỉnh).
- Hiện thực `copyOkrObjectivesFromPeriod`: sao chép mục tiêu, KR, căn cứ, liên kết của kỳ trước sang kỳ hiện tại ở trạng thái `NHAP`, đặt `current_value` về giá trị đầu, không sao chép check-in.
- Hiện thực `createOkrLink`, `deleteOkrLink` (chỉ khi mục tiêu nguồn còn `NHAP`, BR-02-10) và `getOkrComposeContext` (mục tiêu cấp trên chọn được, căn cứ, danh sách người xác nhận, tần suất check-in cho phép theo Policy Pack qua `PolicyQueryApi`).
- Kiểm ràng buộc trường ở máy chủ: tên mục tiêu ≤ 200 ký tự, nội dung KR ≤ 300, chỉ tiêu khác giá trị đầu, hạn KR trong kỳ, người xác nhận khác chủ sở hữu KR, đơn vị sở hữu thuộc phạm vi người soạn; lỗi 422 theo RFC 9457.
- Gọi `PeriodApi.assertWritable` trong mọi lệnh ghi; kỳ `DANG_KHOA`/`DA_KHOA` trả 409 `OKR-409-PERIOD-LOCKED`. Mọi lệnh ghi nhận `Idempotency-Key`, lưu 48 giờ; trùng khoá khác băm yêu cầu trả 422.
- Chỉ nhận `contentSource` khác `HUMAN` khi yêu cầu kèm mã gợi ý AI hợp lệ (chuẩn bị cho CORE-01-13, BR-02-11).

### Bổ sung theo BS-03 (ADR-33, 22/10/2026)

Nguồn: `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-03-ho-so-tap-khach-hang-va-diem-cam-loi.md`. Phần dưới mở rộng phạm vi của thẻ; mọi giá trị riêng của khách hàng đi qua cấu hình, không viết cứng trong mã (YC-TK-11). Nếu thẻ phụ thuộc chưa gộp, làm phần gốc của thẻ trước và để phần bổ sung sau cờ hoặc ghi rõ trong báo cáo.

Phụ thuộc thêm CORE-01-14.

- Soạn KR nhận kiểu đo `MOC` kèm danh sách mốc, chiều `TOI_DA`, nhãn tử số và mẫu số (DC-04); sao chép kỳ trước sao chép cả mốc với hạn dời theo kỳ.

## 4. Ngoài phạm vi

- Bộ kiểm tra BR-02-01..07 đầy đủ (CORE-01-04); task này chỉ kiểm ràng buộc trường.
- Trình duyệt và luồng BPMN (CORE-01-05).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử tích hợp: hai PUT cùng `If-Match` thì một thành công, một nhận 409; PUT lặp lại cùng `Idempotency-Key` trả cùng kết quả và không tăng `row_version` hai lần; 50 luồng tạo mục tiêu song song cùng đơn vị, kỳ không sinh trùng mã.
- Ghi khi kỳ khoá trả 409 `OKR-409-PERIOD-LOCKED`; kiểm thử hợp đồng với `okr.yaml` cho bảy operationId chạy xanh.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

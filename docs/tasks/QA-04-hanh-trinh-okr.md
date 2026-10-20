# QA-04 · QT-00..QT-03 Hành trình đầu cuối: đăng nhập, khởi tạo tenant, tổ chức, OKR, check-in

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B9 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | QA-03, CORE-01-08, DV-03-05 |
| Truy vết | QT-00 → SCR-0001, SCR-0002; QT-01 → SCR-0101..SCR-0104; QT-02 → SCR-0201..SCR-0203; QT-03 → SCR-0301 → API `/api/v1/org/*`, `/api/v1/config/tenants*`, `/api/v1/okr/*` → MOD-01, MOD-08, SVC-02, SVC-03, SVC-04 → EVT-01, EVT-02, EVT-14..EVT-16 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực các hành trình đầu cuối của nhóm QT-00..QT-03 trong danh mục QA-01 trên khung QA-03, chạy với hệ thống thật: đăng nhập và chọn ngữ cảnh, khởi tạo tenant, xem và điều chỉnh cây tổ chức, lập và phê duyệt OKR, check-in KR. Khi xong, mọi hành trình nhóm này chạy xanh trên server dev, kiểm được cả kết quả màn hình, trạng thái dữ liệu và sự kiện phát ra.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` các mục QT-00, QT-01, QT-02, QT-03 (đặc tả SCR, BR, MSG).
- `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR liên quan), `wiki/07-xac-thuc-phan-quyen.md` (AAL2, step-up).
- `contracts/openapi/modules/org.yaml` (`getMyOrgProfile`, `setMyContext`, `getOrgTree`, `changeOrgUnit`, `startOrgSyncRun`, `listOrgDiscrepancies`), `config.yaml` (`createConfigTenant`, `provisionConfigTenant`, `activateConfigTenant`), `okr.yaml` (`createOkrObjective`, `submitOkrObjective`, `approveOkrObjective`, `returnOkrObjective`, `createOkrCheckin`, `getOkrTree`).
- Danh mục hành trình của QA-01, khung của QA-03.

## 3. Phạm vi công việc

- Hành trình đăng nhập: tài khoản AC-06 đăng nhập, chọn đơn vị và vai trò (`setMyContext`), thấy trang "Việc của tôi hôm nay"; tài khoản có vai trò AAL2 (ví dụ AC-07) bị hỏi OTP; thao tác cần step-up (ví dụ phê duyệt) yêu cầu xác thực lại theo `acr=aal2`.
- Hành trình khởi tạo tenant: AC-01 tạo tenant, chạy cấp phát (`provisionConfigTenant`, trả 202 và theo dõi job qua `getJob`), kích hoạt; giao diện hiển thị trạng thái bằng nhãn nghiệp vụ, không hiện mã thô.
- Hành trình tổ chức: AC-02 xem cây tổ chức có lịch sử, ghi một thay đổi đơn vị có hiệu lực từ ngày, kiểm EVT-14 `org.unit.changed.v1`; chạy một lượt đồng bộ nhân sự và xử lý một sai lệch đối soát.
- Hành trình OKR: AC-06 tạo O và KR, gửi duyệt; AC-05 trả lại kèm lý do; AC-06 sửa và gửi lại; AC-05 phê duyệt; kiểm EVT-01 `okr.objective.approved.v1` và cây OKR hiển thị liên kết.
- Hành trình check-in: AC-06 check-in KR (gửi `Idempotency-Key`, gửi lại cùng khoá không tạo bản ghi thứ hai), kiểm EVT-02 `okr.keyresult.checkedin.v1` và tiến độ KR cập nhật.
- Ca phủ định: gọi `approveOkrObjective` bằng tài khoản không có quyền phê duyệt theo `wiki/15a-ma-tran-phan-quyen.md` bị máy chủ trả 403 và giao diện hiện thông báo nghiệp vụ tương ứng; cập nhật với `If-Match` cũ nhận 409 và giao diện mời tải lại.
- Mỗi QT có ít nhất một hành trình chạy ở chế độ Đảng viên cao tuổi (`data-mode="elderly"`).

## 4. Ngoài phạm vi

- Check-in trên di động SCR-0302 (bộ kiểm thử MOB); trợ lý AI soạn OKR (QA-07).
- Sửa lỗi phát hiện được (mở task sửa riêng, ghi trong báo cáo).

## 5. Tệp được phép sửa

- `frontend/e2e/hanh-trinh/qt00-03/**`, `frontend/e2e/hanh-trinh/danh-muc.json` (chỉ cập nhật trạng thái hành trình), `data/seed/e2e/**`.

## 6. Tiêu chí hoàn thành

- `E2E_MODE=he-thong npm run e2e:he-thong -- --grep "QT-0[0-3]"` xanh trên server dev, hai lần liên tiếp; không lỗi console.
- Mỗi EVT nêu ở mục 3 được kiểm thấy trên Kafka dev; ca phủ định trả đúng mã HTTP và thông báo.
- Hành trình chạy ở chế độ người cao tuổi đạt và axe-core không có lỗi mức serious trên các màn hình đi qua.
- Báo cáo cuối task theo AGENTS.md mục 6, kèm bảng hành trình × kết quả và danh sách lỗi đã mở task.

## 7. Báo cáo

Theo AGENTS.md mục 6.

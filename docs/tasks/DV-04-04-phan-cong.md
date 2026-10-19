# DV-04-04 · QT-01 Phân công: một vị trí chính tại một thời điểm, kiêm nhiệm, điều động tương lai

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-04-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-04-02 |
| Truy vết | QT-01 → SCR-0103 (vùng phân công), SCR-0001 → `GET /api/v1/org/persons` (`listOrgPersons`), `GET /api/v1/org/persons/{id}/assignments` (`listPersonAssignments`), `GET /api/v1/org/units/{id}/assignments` (`listOrgUnitAssignments`) → SVC-04 → bảng `org.assignment`, `org.reporting_line`, `org.position` → EVT-15; BR-01-08, BR-01-09 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Quản lý phân công của cán bộ đúng quy tắc: mỗi người chỉ một phân công chính tại một thời điểm trên mỗi nhánh, kiêm nhiệm và biệt phái được nhiều, điều động có ngày hiệu lực tương lai tự kết thúc phân công hiện tại ngày hôm trước, và tính đơn vị đánh giá khi điều động giữa kỳ (BR-01-09). Khi xong, API đọc phân công phục vụ chọn ngữ cảnh đăng nhập (DV-02-02) và màn hình cây tổ chức, mọi thay đổi phân công phát EVT-15.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01: BR-01-08, BR-01-09 (dòng 541–556), điều động tương lai (dòng 566), tính `danh_gia_tai_don_vi` (dòng 553).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` DDL mẫu `org.assignment` (dòng 1060–1115), bảng `org.assignment`, `org.reporting_line` (dòng 1237–1268), mục "Mẫu truy vấn" (dòng 4048).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-04 org-service" (dòng 1028–1046: `POST /api/v1/org/assignments`).
- Hợp đồng: `contracts/openapi/modules/org.yaml` các thao tác ở bảng, schema `Assignment`, `AssignmentKind`, `OrgUnitAssignment`.
- Wiki: `wiki/15-phan-quyen.md` (phạm vi dữ liệu theo phân công), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Viết dịch vụ miền ghi phân công (bổ nhiệm `CHINH`, `KIEM_NHIEM`, `DIEU_DONG`, `BIET_PHAI`, `TAM_GIU`; nhánh `DANG`, `CHINH_QUYEN`; cờ `is_head`) trên thành phần ghi phiên bản; ràng buộc `ex_assignment__one_primary` là lớp chặn cuối, tầng ứng dụng kiểm trước để trả lỗi nghiệp vụ rõ (BR-01-08).
- Điều động tương lai: tạo phân công mới với `valid_from` = ngày hiệu lực và đóng phân công chính hiện tại ở ngày trước đó trong cùng giao dịch.
- Tính `danh_gia_tai_don_vi` cho kỳ: nếu thời gian tại đơn vị mới trong kỳ < 2 tháng thì đơn vị cũ đánh giá (BR-01-09); cung cấp qua trường của phân công hoặc API nội bộ cho MOD-04 (ghi rõ cách chọn), ghi vết.
- Hiện thực `listOrgPersons`, `listPersonAssignments` (tham số `asOf`), `listOrgUnitAssignments` theo hợp đồng, phân trang con trỏ, ABAC theo phạm vi đơn vị.
- Ghi `org.reporting_line` (`TRUC_TIEP`, `THAM_DINH`, `PHU_TRACH_LINH_VUC`) khi phân công có quan hệ báo cáo đi kèm (suy luận: TL-03 có bảng nhưng TL-02 không có màn hình ghi riêng).
- Phát EVT-15 qua outbox cho mọi phân công thêm, đóng hoặc chuyển.
- Kiểm thử: hai phân công chính chồng lấn bị chặn ở ứng dụng và ở CSDL; kiêm nhiệm song song được; điều động tương lai đóng đúng ngày; BR-01-09 với các mốc 1 tháng 29 ngày, 2 tháng, 2 tháng 1 ngày.
- Điểm lệch cần lưu ý: TL-01 có `POST /api/v1/org/assignments` (bổ nhiệm, kiêm nhiệm, điều động) nhưng hợp đồng `org.yaml` không có thao tác ghi phân công trực tiếp; phân công chỉ đổi qua `changeOrgUnit` (`moveAssignmentsTo`), đối soát (`resolveOrgDiscrepancies`), nhập và SCIM. Theo API-first không thêm controller ngoài hợp đồng; viết dịch vụ miền để các đường đó gọi, và ghi đề nghị bổ sung thao tác ghi phân công vào wiki trang 14 cho task sửa hợp đồng riêng.

## 4. Ngoài phạm vi

- Chọn ngữ cảnh đăng nhập (DV-02-02); đối soát nhân sự (DV-04-07); SCIM (DV-04-06).
- Tiêu thụ EVT-15 ở MOD-02, MOD-04 (CORE).

## 5. Tệp được phép sửa

- `backend/services/org-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, `wiki/15-phan-quyen.md` (chỉ ghi chú hiện thực phạm vi theo phân công)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/org-service -am verify` chạy xanh gồm Testcontainers và kiểm thử hợp đồng; độ phủ dòng `domain`, `application` ≥ 80%.
- Mọi ca BR-01-08, BR-01-09 ở mục 3 có kiểm thử riêng.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.

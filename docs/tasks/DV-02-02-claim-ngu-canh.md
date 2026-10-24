# DV-02-02 · QT-00/SCR-0001 Protocol mapper claim ngữ cảnh và API chọn đơn vị, vai trò

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-02-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-02-01, DV-04-04 |
| Truy vết | QT-00 → SCR-0001, SCR-0002 → `PUT /api/v1/org/me/context` (`setMyContext`), `GET /api/v1/org/me` (`getMyOrgProfile`) → SVC-04 org-service, SVC-02 iam → bảng `org.assignment`, `config.role_binding` (đọc); BR-00-01, BR-00-07, MSG-00-05, MSG-00-06 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Sau đăng nhập, người dùng chọn một phân công còn hiệu lực (đơn vị, vị trí, vai trò) làm ngữ cảnh làm việc, và mọi token cấp sau đó mang đúng ngữ cảnh đó để cổng, mission-core và OPA kiểm quyền. Khi xong, `GET /org/me` trả danh sách phân công hiệu lực, `PUT /org/me/context` ghi lựa chọn, token làm mới chứa claim tenant, đơn vị, luồng, vai trò và `acr`; vai trò cần AAL2 không chọn được trong phiên AAL1.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-00 (dòng 291–474), bảng chức năng SCR-0001 (dòng 413), SCR-0002 (dòng 459), BR-00-01, BR-00-07 (dòng 353–364), ngữ cảnh làm việc (dòng 150–152).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-02 iam" (dòng 987–1005: mapper `tenant_id`, `org_unit_ids`, `flows`, `acr`) và mục "Lan truyền ngữ cảnh tenant từ JWT tới RLS" (dòng 2670).
- Hợp đồng: `contracts/openapi/modules/org.yaml` thao tác `getMyOrgProfile`, `setMyContext`, schema `WorkContext`, `Assignment`, `AssignmentKind`.
- Wiki: `wiki/07-xac-thuc-phan-quyen.md`, `wiki/15-phan-quyen.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-0001, SCR-0002), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Hiện thực `getMyOrgProfile` trong org-service: đọc phân công hiệu lực của người dùng tại thời điểm hiện tại theo hai trục thời gian (BR-00-07) kèm vai trò từ `config.role_binding` (qua API nội bộ hoặc bản sao, ghi rõ cách chọn), trả `lastAssignmentId`.
- Hiện thực `setMyContext` (Idempotency-Key): kiểm `assignmentId` thuộc người gọi và còn hiệu lực (404 nếu không), kiểm vai trò của phân công cần AAL2 (`appraiser`, `decider`, `second_approver`, `tenant_admin`, `platform_admin`) trong khi token hiện tại là AAL1 thì trả 401 yêu cầu bước lên (BR-00-01, MSG-00-05); ghi ngữ cảnh vào thuộc tính phiên người dùng trong Keycloak qua Admin API mạng quản trị.
- Viết protocol mapper Keycloak (SPI Java) đọc ngữ cảnh đã ghi và phát claim tenant, đơn vị, danh sách luồng (`DANG`, `NHA_NUOC`), vai trò của phân công và `cell`; không bao giờ lấy giá trị do client gửi.
- Người dùng không có phân công hiệu lực: trả lỗi để giao diện hiện MSG-00-06, không cấp vai trò nghiệp vụ.
- Ghi sổ cái qua outbox mỗi lần đổi ngữ cảnh (BR-00-08 áp cho bản ghi do người dùng tạo; ngữ cảnh được gắn vào bản ghi sau đó).
- Viết kiểm thử: đơn vị cho kiểm hiệu lực và luật AAL2; tích hợp Testcontainers (PostgreSQL + Keycloak) cho luồng chọn ngữ cảnh rồi làm mới token, khẳng định claim đúng; kiểm thử hợp đồng so phản hồi với `org.yaml`.
- Điểm lệch cần lưu ý: tên claim khác nhau giữa TL-01 SVC-02 (`tenant_id`, `org_unit_ids`), TL-01 chương "Đa khách thuê" và TL-04 (`tenant`, `cell`) và hợp đồng (`tid`, claim `org`); theo API-first dùng tên trong hợp đồng, ánh xạ tên còn lại bằng cấu hình mapper, ghi quyết định vào wiki trang 14 và báo DV-01-01, NEN-010 dùng cùng tên.

## 4. Ngoài phạm vi

- Authenticator MFA và step-up (DV-02-03); bộ lọc kiểm `acr`/`auth_time` ở mission-core (NEN-014).
- Giao diện chọn phân công (task WEB).

## 5. Tệp được phép sửa

- `backend/services/org-service/src/**` (chỉ các lớp của hai thao tác `/org/me*`)
- `backend/services/iam/**` (SPI protocol mapper)
- `deploy/charts/iam/**` (đăng ký mapper vào client scope)
- `wiki/07-xac-thuc-phan-quyen.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/org-service,services/iam -am verify` chạy xanh, gồm Testcontainers Keycloak + PostgreSQL và kiểm thử hợp đồng với `contracts/openapi/modules/org.yaml`; `openapi-diff` không phá tương thích.
- Ca kiểm thử bắt buộc: chọn phân công hết hiệu lực bị từ chối; chọn vai trò AAL2 khi AAL1 trả 401; token sau làm mới chứa đúng ngữ cảnh; header hay tham số client gửi không làm đổi claim.
- Độ phủ dòng lớp `domain`, `application` ≥ 80%.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.

# DL-07 · QT-01/SVC-04 Dữ liệu khởi tạo: 9 khối đối tượng, lịch nghỉ lễ quốc gia, danh mục chức danh

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 – định danh, tổ chức và tích hợp |
| Luồng đề xuất | B11 |
| Cỡ việc | S (≤ nửa ngày agent) |
| Phụ thuộc | DV-04-01, CORE-08-04 |
| Truy vết | QT-01 (BR-01-05), PL 2A, PL 2B → API `GET /api/v1/org/subject-groups` (`listSubjectGroups`), `GET /api/v1/config/holiday-calendars` (`listHolidayCalendarDays`), `GET /api/v1/config/calendar` (`getWorkingCalendar`) → SVC-04, MOD-08 → bảng `org.subject_group`, `org.job_title`, `config.holiday_calendar` → không phát EVT (dữ liệu của tenant hệ thống) |
| Trạng thái | Một phần (20/10/2026): lịch nghỉ quốc gia 2026, 2027 có nguồn; khung khối và chức danh chờ PL 2A, PL 2B; migration lặp chờ DV-04-01, CORE-08-04 |

## 1. Mục tiêu

Mọi tenant mới dùng được ngay ba danh mục quốc gia: 9 khối đối tượng theo PL 2A (BR-01-05 chặn nhập đơn vị không thuộc khối nào), lịch ngày nghỉ lễ, nghỉ bù, làm bù quốc gia dùng để tính ngày làm việc chậm (tham số `loai_ngay`), và danh mục chức danh có cờ thuộc 40 chức danh diện Trung ương quản lý (PL 2B, dùng ở BR-11-11). Dữ liệu nạp lặp lại được, có nguồn trích dẫn và kiểm thử.

## 2. Đầu vào bắt buộc đọc

- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: mục "Schema org (SVC-04 org-service)", các bảng `org.subject_group` (mã `K1`..`K9`, `flow`), `org.job_title` (`is_central_managed`, tenant hệ thống); mục "Schema config (MOD-08)", bảng `config.holiday_calendar` (`day_kind` `NGHI_LE`/`NGHI_BU`/`LAM_BU`, `scope` `QUOC_GIA`/`TENANT`).
- TL-02a `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`: QT-01 "Thông tin chung" (căn cứ PL 2A), "Quy tắc nghiệp vụ" (BR-01-05), đặc tả SCR-0103.
- Baseline `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục danh mục schema `org`.
- Hợp đồng `contracts/openapi/modules/org.yaml` (`listSubjectGroups`), `contracts/openapi/modules/config.yaml` (`listHolidayCalendarDays`, `getWorkingCalendar`).
- Đầu vào người dùng cần cấp: văn bản PL 2A (tên 9 khối, luồng áp dụng) và PL 2B (40 chức danh) của dự thảo Quy định mới; thông báo lịch nghỉ lễ, nghỉ bù năm 2026, 2027 của cơ quan có thẩm quyền.

## 3. Phạm vi công việc

- Soạn tệp dữ liệu nguồn có trích dẫn trong `data/seed/org/`: 9 khối `K1`..`K9` với tên, `flow` (`DANG`, `NHA_NUOC`, `CA_HAI`), `valid_from`; danh mục chức danh quốc gia với `level`, `is_central_managed`, `subject_group_id`.
- Soạn lịch nghỉ quốc gia năm 2026, 2027 trong `data/seed/config/`: ngày lễ theo Bộ luật Lao động 2019 (Tết Dương lịch, Tết Nguyên đán, Giỗ Tổ Hùng Vương, 30/4, 1/5, Quốc khánh), ngày nghỉ bù và làm bù theo thông báo hằng năm; mỗi dòng ghi nguồn; ngày âm lịch quy đổi phải được đối chiếu với thông báo chính thức.
- Viết cơ chế nạp lặp lại được cho tenant hệ thống: migration lặp (`R__`) hoặc bộ nạp khởi động (agent chọn một và nêu lý do), bảo đảm chạy lại không tạo dòng trùng nhờ khoá duy nhất `(tenant_id, code)` và `(tenant_id, calendar_date)`.
- Bảo đảm tenant thường đọc được danh mục của tenant hệ thống theo đúng chính sách RLS đã chốt ở DV-04-01, CORE-08-01 (không mở RLS rộng hơn).
- Nếu chưa có PL 2A, PL 2B: nạp lịch nghỉ trước, giữ khung tệp cho khối và chức danh với mã chưa điền tên, ghi rõ trong báo cáo và wiki trang 14; không tự đặt tên khối.

## 4. Ngoài phạm vi

- Cây tổ chức, nhân sự của tenant (QT-01, nhóm DV-04); lịch riêng của tenant (người dùng tự nhập qua SCR).
- Thư viện tính ngày làm việc (CORE-08-04).

## 5. Tệp được phép sửa

- `data/seed/org/**`, `data/seed/config/**`
- `backend/services/org-service/src/main/resources/db/migration/org/R__*.sql` (chỉ migration lặp nạp danh mục)
- `backend/mission-core/src/main/resources/db/migration/config/R__*.sql` (chỉ migration lặp nạp lịch quốc gia)
- Kiểm thử tương ứng trong `backend/services/org-service/src/test/**` và `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/org-service,mission-core -am verify` xanh (đường dẫn mô-đun Maven theo khung NEN-005).
- Kiểm thử Testcontainers: nạp hai lần vẫn đúng 9 khối, số chức danh và số ngày nghỉ như tệp nguồn; `listSubjectGroups` và `listHolidayCalendarDays` trả đúng dữ liệu cho một tenant thường.
- Thư viện ngày làm việc của CORE-08-04 tính đúng số ngày làm việc của tháng có Tết Nguyên đán và tháng 4–5/2026 (giá trị mong đợi đối chiếu thủ công, ghi trong kiểm thử).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

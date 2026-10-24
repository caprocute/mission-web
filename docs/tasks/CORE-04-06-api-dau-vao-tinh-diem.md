# CORE-04-06 · SVC-07 API đọc lô `inputs` cho scoring và listener nhiệm vụ kết thúc

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-04-01, CORE-02-12 |
| Truy vết | QT-08 bước 2, QT-09 → API `GET /api/v1/appraisals/inputs` (`listAppraisalScoringInputs`, client nội bộ SVC-07) → MOD-04 → `appraisal.appraisal_profile`, `appraisal.general_criteria_result`; listener `TaskStatusListener` từ MOD-02 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

SVC-07 đọc được đầu vào tính điểm của cả kỳ theo lô qua API nội bộ có phân trang con trỏ, và MOD-04 làm mới đầu vào của người được giao ngay khi một nhiệm vụ kết thúc. Nhờ đó tính điểm dần trong kỳ và tính lại hàng loạt khi khoá kỳ dùng chung một nguồn đầu vào nhất quán.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`: "Giao tiếp giữa module" (dòng 553, ví dụ `TaskStatusListener` → `RefreshAppraisalInputsUseCase.forAssignees`), "API chính" (dòng 809), SVC-07 "Công nghệ, dữ liệu, sự kiện" (dòng 1078); phụ lục C mục "Đánh giá, xếp loại (MOD-04, SVC-07)" (dòng 3807).
- TL-03 "Schema scoring" (dòng 3316) để hiểu cách SVC-07 dùng đầu vào và `input_hash`.
- `contracts/openapi/modules/appraisals.yaml`: `listAppraisalScoringInputs`; `contracts/openapi/common/common.yaml` tham số `Limit`, `Cursor`.
- Bản trích `tl01-thanh-phan §B4`.

## 3. Phạm vi công việc

- Hiện thực `listAppraisalScoringInputs` chỉ cho client credentials của SVC-07 (`serviceClient`), trả các trường theo hợp đồng (luồng, khối đối tượng, người đứng đầu, phiên bản Policy Pack, kết quả tiêu chí chung), sắp xếp ổn định theo khoá để con trỏ không bỏ sót khi có ghi đồng thời.
- Truy vấn theo chỉ mục `(tenant_id, org_unit_id, period_id)` và đọc từ bản sao chỉ đọc khi thư viện nền hỗ trợ; đặt ngữ cảnh tenant bằng `SET LOCAL`.
- Viết listener nội bộ đăng ký sự kiện ứng dụng do `TaskApi` (CORE-02-12) công bố khi nhiệm vụ chuyển trạng thái kết thúc; ghi đánh dấu làm mới đầu vào cho các người được giao, idempotent theo khoá sự kiện.
- Không trả trường C2 hay C3 trong đầu vào; kiểm bằng kiểm thử so khớp lược đồ.
- Điểm lệch cần lưu ý: TL-01 ghi trang 500 hồ sơ (dòng 1080), hợp đồng dùng tham số `Limit` chung với tối đa 200; chọn hợp đồng và ghi vào `wiki/14`. Đường dẫn TL-01 phụ lục C `/appraisals/inputs?period=&cursor=` khớp hợp đồng.

### Bổ sung theo BS-03 (ADR-33, 22/10/2026)

Nguồn: `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-03-ho-so-tap-khach-hang-va-diem-cam-loi.md`. Phần dưới mở rộng phạm vi của thẻ; mọi giá trị riêng của khách hàng đi qua cấu hình, không viết cứng trong mã (YC-TK-11). Nếu thẻ phụ thuộc chưa gộp, làm phần gốc của thẻ trước và để phần bổ sung sau cờ hoặc ghi rõ trong báo cáo.

Phần mở rộng `workItems`, `responsibilityScope` do CORE-04-13 làm.

- Thiết kế truy vấn và con trỏ sao cho CORE-04-13 thêm được `workItems` (giới hạn 200 việc lá mỗi hồ sơ, con trỏ con) và `responsibilityScope` dưới dạng trường tùy chọn, chỉ đọc khi gói của kỳ khai báo biến tương ứng (DC-01, DC-03); không làm chậm trường hợp gói không khai báo.

## 4. Ngoài phạm vi

- Phía SVC-07 đọc lô và tính điểm (DV-07-04, DV-07-06); `TaskApi` (CORE-02-12).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; `ApplicationModules.verify()` xác nhận MOD-04 chỉ nhận sự kiện của MOD-02 qua gói `api`.
- Kiểm thử: token người dùng thường gọi API nội bộ bị 403; duyệt hết 10.000 hồ sơ qua con trỏ không trùng, không sót khi có chèn đồng thời; một trang 200 hồ sơ p95 ≤ 300 ms trên dữ liệu kiểm thử.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.

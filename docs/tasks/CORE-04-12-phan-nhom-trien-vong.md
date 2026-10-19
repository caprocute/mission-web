# CORE-04-12 · QT-10/SCR-1002 Phân nhóm triển vọng: lưu nháp, kiểm quy tắc, trình tập thể, ký danh sách

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-12` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-04-10 |
| Truy vết | QT-10 → SCR-1002 → API `GET/PUT /api/v1/appraisals/prospect-groups/{periodId}/{orgUnitId}` (`getProspectGroupBoard`, `saveProspectGroupBoard`), `POST .../submit` (`submitProspectGroupBoard`); `GET /api/v1/policies/packs/{id}/prospect-criteria` (MOD-05) → MOD-04 → `appraisal.prospect_group` → EVT-11, EVT-18 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Lãnh đạo đơn vị lập danh sách phân nhóm triển vọng (Nhóm 1–4) ở kỳ năm từ gợi ý của hệ thống, ghi lý do khi khác gợi ý, trình tập thể lãnh đạo thông qua và ký số danh sách. Dữ liệu phân nhóm là C2 được kiểm soát chặt, chỉ `leader` và `appraiser` trong phạm vi được xem (BR-10-10).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-10: "Quy tắc nghiệp vụ" (dòng 385: BR-10-07..10), "Ngoại lệ và xử lý" (dòng 402: tập thể không thông qua, thứ tự trong nhóm không trùng), "SCR-1002. Phân nhóm triển vọng" (dòng 462, MSG-10-05..07).
- TL-03 bảng `prospect_group` (dòng 2936: quyền cột `group_code`, `reason_enc`, `training_plan_enc`, CDC loại trừ).
- `contracts/openapi/modules/appraisals.yaml`: `getProspectGroupBoard`, `saveProspectGroupBoard`, `submitProspectGroupBoard`; `contracts/openapi/modules/policies.yaml` (tiêu chí phân nhóm).
- Bản trích `tl02b §QT-10 BE-Q10-06, 07`.

## 3. Phạm vi công việc

- `getProspectGroupBoard`: trả danh sách người, mức năm, `suggested_group`, nhóm đã chọn, thứ tự; gọi ở kỳ quý trả 409 + MSG-10-05 (BR-10-07); OPA chỉ cho `leader`, `appraiser` của đơn vị (BR-10-10).
- `saveProspectGroupBoard`: `If-Match`, lưu cả danh sách trong một giao dịch; khác gợi ý thì bắt buộc lý do 20–1.000 ký tự mã hoá `reason_enc` (BR-10-08, MSG-10-07); KHTNV năm hoặc có `reassign_flag` không được vào Nhóm 1, 2 (BR-10-09, MSG-10-06) kiểm bằng CEL khi lưu và khi trình; thứ tự trong nhóm không trùng.
- `submitProspectGroupBoard`: `Idempotency-Key`, khoá chỉnh sửa, khởi động user task thông qua tập thể qua SVC-06, phát EVT-11; tập thể không thông qua thì về nháp, trình lại tạo phiên bản mới; thông qua thì tạo yêu cầu ký danh sách tới SVC-10 và hoàn tất khi nhận EVT-18.
- Truy vấn dùng tài khoản `svc_appraisal` cho cột `group_code`; không đưa `group_code` vào sự kiện, nhật ký hay bộ đệm; chỉ đẩy số lượng theo nhóm cho phân tích.
- Điểm lệch cần lưu ý: TL-02b chưa đặt tên trạng thái của danh sách phân nhóm (nháp, trình, đã ký); không tự đặt mã trạng thái mới trong CSDL, dùng trường hợp đã có trong hợp đồng hoặc ghi câu hỏi vào `wiki/14`.

## 4. Ngoài phạm vi

- API tiêu chí phân nhóm của MOD-05 (CORE-05-xx); gợi ý nhóm nếu do SVC-07 sinh (DV-07-07).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`
- `deploy/policies/` (chỉ quy tắc Rego cho phân nhóm triển vọng)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; `opa test` xanh.
- Kiểm thử: `manager` không phải lãnh đạo bị 403; KHTNV vào Nhóm 1 bị 422; khác gợi ý thiếu lý do bị 422; sau khi trình, `PUT` bị 409; không có `group_code` trong payload outbox.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.

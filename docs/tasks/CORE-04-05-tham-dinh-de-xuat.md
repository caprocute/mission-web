# CORE-04-05 · QT-09/SCR-0902 Thẩm định, trả lại, đề xuất và tách vai trò qua OPA

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-04-04 |
| Truy vết | QT-09 → SCR-0902 → API `GET /api/v1/appraisals/profiles`, `POST .../profiles/{id}/return`, `POST .../profiles/{id}/appraisal`, `POST .../profiles/{id}/proposal`, `POST /api/v1/appraisals/exports` → MOD-04 → `appraisal.rating_proposal` (`THAM_DINH`, `DE_XUAT`), `appraisal.general_criteria_result` (`THAM_DINH`) → EVT-11, EVT-18 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người thẩm định (AC-07) xem hàng đợi hồ sơ trong phạm vi, trả lại hồ sơ tối đa hai lần, ký số kết quả thẩm định; quản lý trực tiếp (AC-05) ghi mức đề xuất hoặc bước này được bỏ qua theo `flowable.wf_binding`. Mọi thao tác ghi kiểm tách vai trò BR-09-14 bằng OPA, nên người đã nghiệm thu nhiệm vụ của hồ sơ không thể thẩm định hồ sơ đó (MSG-09-08).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-09: "Trạng thái đối tượng" (dòng 65), "Quy tắc nghiệp vụ" (dòng 87: BR-09-12, BR-09-14), "SCR-0902. Thẩm định" (dòng 183, MSG-09-06..09), "SCR-0903" (dòng 233, MSG-09-12 khi đề xuất khác mức thẩm định).
- TL-03 DDL `rating_proposal` (dòng 2728, CHECK vai trò theo bước).
- `contracts/openapi/modules/appraisals.yaml`: `listAppraisalProfiles`, `returnAppraisalProfile`, `appraiseAppraisalProfile`, `proposeAppraisalRating`, `exportAppraisalProfiles`.
- `wiki/15-phan-quyen.md` (tách năm vai), `wiki/15a-ma-tran-phan-quyen.md` dòng SCR-0902, SCR-0903; bản trích `tl02b §QT-09 BE-Q09-05`.

## 3. Phạm vi công việc

- `listAppraisalProfiles`: hàng đợi theo đơn vị và trạng thái, phân trang con trỏ `limit ≤ 200`, lọc phạm vi bằng đánh giá từng phần của OPA thành điều kiện SQL (NEN-014).
- `returnAppraisalProfile`: bắt buộc lý do, đếm lần trả lại (tối đa theo tham số, MSG-09-09), chuyển `TU_DANH_GIA`, phát EVT-11, EVT-21.
- `appraiseAppraisalProfile`: kiểm AAL2, ghi `general_criteria_result` giai đoạn `THAM_DINH` và `rating_proposal` mức `THAM_DINH`; khác mức tự đề xuất thì bắt buộc lý do (MSG-09-06); tạo yêu cầu ký, trả 202; chuyển `DA_THAM_DINH` khi nhận EVT-18.
- `proposeAppraisalRating`: AC-05 ghi mức `DE_XUAT`, khác mức thẩm định thì bắt buộc lý do (MSG-09-12); khi `wf_binding` cho bỏ qua cấp đề xuất thì hồ sơ chuyển thẳng `CHO_QUYET_DINH`.
- Viết chính sách Rego cho BR-09-14: người thẩm định, đề xuất, quyết định khác người được đánh giá và khác người đã nghiệm thu bất kỳ minh chứng nào của hồ sơ (đọc qua cổng `api` của MOD-03); từ chối trả 403 với mã lỗi nghiệp vụ (MSG-09-08); tài khoản dịch vụ luôn bị từ chối ghi mức.
- `exportAppraisalProfiles`: tác vụ 202 sinh XLSX không chứa trường C2.
- Xung đột hai thẩm định viên cùng lưu: `If-Match`, 409 + MSG-09-07.

## 4. Ngoài phạm vi

- Quyết định xếp loại và ký lô (CORE-04-07); user task BPMN và timer hạn (DV-06-04).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`
- `deploy/policies/` (chỉ quy tắc Rego tách vai trò hồ sơ đánh giá và ca `opa test`)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; `opa test` gói chính sách xanh.
- Kiểm thử: người đã nghiệm thu một minh chứng của hồ sơ gọi thẩm định bị 403; trả lại lần ba bị chặn; đề xuất khác mức thiếu lý do bị 422; XLSX xuất ra không có cột C2.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.

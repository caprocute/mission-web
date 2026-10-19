# WEB-065 · SCR-0402, SCR-0403 Đối chiếu client kế hoạch với hợp đồng OpenAPI

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-065` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ2 – Nghiệp vụ lõi (phát sinh sau WEB-063, WEB-064) |
| Cỡ việc | M |
| Phụ thuộc | WEB-026, WEB-027, WEB-063, WEB-064 |
| Truy vết | QT-04 → SCR-0402, SCR-0403 → `/api/v1/plans`, `/api/v1/esign/requests/{id}` → MOD-02/SVC-06, SVC-10 → EVT-03, EVT-18, EVT-21 |
| Trạng thái | Hoàn thành (chờ gộp) |

## 1. Mục tiêu
Màn hình lập kế hoạch quý (SCR-0402) và phê duyệt kế hoạch (SCR-0403) gọi đúng các đường dẫn và lược đồ của `contracts/openapi/modules/tasks.yaml`, để khi backend sinh mã từ cùng hợp đồng thì giao diện chạy được ngay mà không phải sửa model. Người dùng vẫn thấy cùng chức năng như WEB-026, WEB-027; điểm khác người dùng nhận ra là lý do điều chỉnh được ghi ở từng dòng thêm hoặc sửa thay cho một lý do chung.

## 2. Đầu vào bắt buộc đọc
- `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md` mục SCR-0402, SCR-0403; `wiki/06-quy-uoc-api.md` mục kiểu sinh từ hợp đồng; `wiki/14-quyet-dinh-va-cau-hoi-mo.md` các dòng về kế hoạch.
- `contracts/openapi/modules/tasks.yaml` (thao tác `listPlans` … `approvePlanVersion`, lược đồ `Plan*`), `contracts/openapi/modules/dossiers.yaml` (`EsignRequest`), `frontend/projects/api-client/src/lib/generated/tasks.ts`.
- TL-02 SCR-0402, SCR-0403 (Hình 7.13, 7.14), BR-04-02 … BR-04-12.

## 3. Phạm vi công việc
- Viết lại `models/plan.ts` theo lược đồ hợp đồng: `Plan`, `PlanVersionSummary`, `PlanItemInput`, `PlanItem` (`lineNo`, `leadAssignee`/`coAssignees` kiểu `AssigneeRef`, `quantity`, `dueDate` bắt buộc), `PlanCheck`, `PlanVersion`, `PlanVersionInput`, `PlanDiffLine`, `PlanDiff` phẳng; giữ `PlanItemRef` của WEB-028.
- `PlanApi` dùng `apiUrl()`; ghép dữ liệu SCR-0402 từ `GET /plans`, `GET /plans/{id}/versions`, `GET /plans/{id}/versions/{v}` (bỏ `GET /plans/{id}`); `diff` truyền `base` bắt buộc; `approve` nhận 202 `Job`, theo dõi yêu cầu ký qua `PlanVersion.signRequestId` và `GET /esign/requests/{id}`.
- Giả lập `mock/plans` trả đúng hình dạng hợp đồng; kiểm tra BR-04-10 lý do ở từng dòng của bản điều chỉnh.
- Màn hình `plan.page.ts`, `plan-item-editor.ts`, `plan-review.page.ts` và kiểm thử theo model mới.
- Đưa các model kế hoạch vào `contract-check.spec.ts`.
- Gộp hai điểm đã báo: `GET /tasks/{id}/draft` (đã theo hợp đồng ở WEB-028 sửa) và tên trường `standardScore`, `productName`.
- Ghi giả định vào wiki/14; gửi luồng hợp đồng các lời gọi, trường còn thiếu.

## 4. Ngoài phạm vi
- Sửa hợp đồng `contracts/openapi/` (thuộc luồng hợp đồng).
- Theo dõi job tổng quát `/jobs/{id}` cho phê duyệt (giao diện theo dõi yêu cầu ký, đủ cho MSG-04-09, MSG-04-13).
- Ghi lý do bỏ dòng từ client (hợp đồng chưa có chỗ ghi). *Đã làm ở nhánh sửa `fix/WEB-065-hop-dong-ke-hoach` sau khi hợp đồng bổ sung `removedItems`, `check`, `changeReason` và các trường hiển thị (19/10/2026).*

## 5. Tệp được phép sửa
- `frontend/projects/api-client/src/lib/models/plan.ts`, `plan-api.ts`, `models/contract-check.spec.ts`
- `frontend/projects/api-client/src/mock/plans/**`
- `frontend/projects/fe-task/src/app/pages/plan.page.ts`, `plan-review.page.ts`, `plan/plan-item-editor.ts` và các `*.spec.ts` tương ứng
- `wiki/05a-*.md`, `wiki/06-*.md`, `wiki/14-*.md`, `docs/tasks/README.md`, tệp này

## 6. Tiêu chí hoàn thành
- `npx ng build` cho `fe-task`, `fe-shell` thành công, không cảnh báo mới về ngân sách.
- `ng test api-client`, `ng test fe-task` xanh; `contract-check.spec.ts` biên dịch được với các model kế hoạch.
- SCR-0402, SCR-0403 mở trên trình duyệt không lỗi console; axe-core không có lỗi mức serious mới.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật.

## 7. Báo cáo
Theo AGENTS.md mục 6.

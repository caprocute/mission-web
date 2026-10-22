# NEN-044 · ADR-33/BS-03 Sửa hợp đồng OpenAPI cho hồ sơ tập khách hàng và điểm cắm DC-01..DC-06

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-044` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c (gộp trước các task CORE, DV, DL của BS-03) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-043; người phụ trách hợp đồng duyệt |
| Truy vết | ADR-33, ADR-32, YC-PT-09 → `contracts/openapi/modules/policies.yaml`, `appraisals.yaml`, `tasks.yaml`, `okr.yaml` → MOD-05, MOD-04, MOD-02, MOD-01, SVC-07 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Bộ hợp đồng có đủ thao tác và lược đồ cho sáu điểm cắm của BS-03 trước khi bất kỳ phía nào viết mã, theo ADR-32. Mọi thay đổi chỉ thêm (trường tùy chọn, giá trị enum mới, tài nguyên mới), chứng minh tương thích bằng `openapi-diff`, và sinh lại kiểu cho frontend.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-03-ho-so-tap-khach-hang-va-diem-cam-loi.md` (ADR-33): toàn bộ, đặc biệt dòng "Hợp đồng" của từng điểm cắm.
- Báo cáo phân tích `/mnt/project-files/phan-tich-ueb/phan-tich-ueb-hap-thu-vao-mission.md` mục "Đề xuất cụ thể".
- `contracts/openapi/common/common.yaml`, `contracts/redocly.yaml`; tiền lệ NEN-043 về thao tác cho dịch vụ gọi dịch vụ (`roles: [service_client]`).

## 3. Phạm vi công việc

- `policies.yaml`: tài nguyên `customer-profiles` và phiên bản (vòng đời giống `policy-packs`, có ký và hai người), `param-constraints` của phiên bản hồ sơ, cho phép `classification.chain`; `PolicyBinding` thêm `customerProfileVersionId`.
- `appraisals.yaml`: `AppraisalScoringInput` thêm `workItems` (`WorkItem`) và `responsibilityScope` (`ResponsibilityItem`), đều tùy chọn; tài nguyên `incidents`; lỗi bắt buộc lý do khi mức khác mức gợi ý (nếu chưa có).
- `tasks.yaml`: `CatalogItem`, `Task`, `PlanItem` thêm `coefficientLevels`, `axisCode`, `nodeKind`, `componentScore`, `scoreAdjustMin`, `scoreAdjustMax`; enum phạm vi danh mục thêm `KHOI`, `TINH`; `TaskBlocker` thêm `origin`, `ruleCode`; lỗi `TASK-422-ASSIGNMENT-MODE`.
- `okr.yaml`: `OkrMetricType` thêm `MOC`, chiều `TOI_DA`, nhãn tử số và mẫu số, tài nguyên `milestones` của KR.
- Mỗi thao tác mới có `x-mission` đủ truy vết, ví dụ trong `examples`, và đánh dấu "(suy luận)" ở chỗ BS-03 không ghi trực tiếp.

- Không viết hằng số, nhánh mã hay tên riêng của hồ sơ TWĐ trong mã lõi; mọi giá trị của khách hàng đi qua cấu hình (YC-TK-11, ADR-33). Mặc định của điểm cắm phải giữ đúng hành vi hiện tại.

## 4. Ngoài phạm vi

- Mã phía máy chủ và giao diện; lớp cấp trên giữa các tenant (B7).

## 5. Tệp được phép sửa

- `contracts/openapi/**`
- `frontend/projects/api-client/src/lib/generated/**` (sinh lại kiểu)
- `docs/phan-quyen/ma-tran.json`, `wiki/15a-ma-tran-phan-quyen.md` (nếu có thao tác mới cần quyền)

## 6. Tiêu chí hoàn thành

- `npx @redocly/cli lint` đạt; `openapi-diff` so với `main` không có thay đổi phá vỡ; `npm run api:check` xanh sau khi sinh lại kiểu.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.

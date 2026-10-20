# DV-06-09 · BS-01/SCR-1505 Mẫu luồng nội bộ: mở rộng `wf_template`, `wf_template_rollout`, linter, saga phát hành cuốn chiếu

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-06-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | L |
| Phụ thuộc | DV-06-08; DV-06-05 (khung saga) |
| Truy vết | QT-15 (BS-01) → SCR-1505 → `GET/POST /api/v1/workflow/templates` (`listWorkflowTemplates`, `createWorkflowTemplate`), `GET/PUT /api/v1/workflow/templates/{id}` (`getWorkflowTemplate`, `updateWorkflowTemplate`), `POST .../test` (`runWorkflowTemplateTests`), `POST .../submit` (`submitWorkflowTemplate`), `POST .../approve` (`approveWorkflowTemplate`), `GET .../rollouts` (`listWorkflowTemplateRollouts`) → SVC-06 → bảng `flowable.wf_template`, `flowable.wf_template_rollout` (bổ sung theo BS-01); ADR-04, ADR-16 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Đội sản phẩm nền tảng soạn, kiểm thử, đề nghị phát hành và triển khai mẫu luồng BPMN mà vẫn giữ một nguồn sự thật trong Git: máy chủ chạy lại linter, chạy bộ test trên engine thử với mọi `wf_binding` đang dùng mẫu, tạo merge request vào repo mẫu luồng, CI ký cosign, rồi saga triển khai cuốn chiếu lên engine của từng cell có bù trừ. Khi xong, một phiên bản mẫu đi từ `NHAP` tới `HIEU_LUC` không cần thao tác tay trên cụm, và phiên bản `HIEU_LUC` không sửa được.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-01-giao-dien-cau-hinh-quy-trinh-Flowable.md` toàn bộ (mục SCR-1505, "Giới hạn để không ảnh hưởng mức tải 150 user task mỗi giây mỗi cell", "Thay đổi dữ liệu đề xuất").
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương G2 "Quy tắc quản trị chống mạng nhện" (dòng 2149–2153) và "Kịch bản thử tải tham chiếu" (dòng 2155).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `wf_template` (dòng 3243).
- Hợp đồng: `contracts/openapi/modules/platform.yaml` các thao tác ở bảng (lỗi linter 422 với `errors[].rule`).
- Wiki: `wiki/15a-ma-tran-phan-quyen.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Migration mở rộng (không sửa migration cũ): thêm `param_schema` (jsonb), `test_suite_ref`, `approved_by`, `signature` vào `wf_template`; mở rộng CHECK `status` thêm `DAT_KIEM_THU`, `CHO_PHE_DUYET`; tạo `wf_template_rollout` (`wf_template_id`, `cell_code`, `flowable_deployment_id`, `status` `CHO`/`DA_TRIEN_KHAI`/`LOI`/`DA_BU_TRU`, `deployed_at`); thêm `version_no`, `status` cho `wf_binding`; đánh dấu "bổ sung" theo quy ước TL-03.
- Hiện thực các thao tác ở bảng truy vết với vòng đời `NHAP` → `DAT_KIEM_THU` → `CHO_PHE_DUYET` → `HIEU_LUC` → `NGUNG`; phiên bản `HIEU_LUC` bất biến; người phê duyệt khác người soạn (bốn mắt).
- Linter máy chủ: bộ quy tắc của TL-01 cộng giới hạn BS-01 (≤ 12 user task mỗi nhánh, ≤ 50 phần tử đa thể hiện, chỉ external worker task cho tác vụ máy, cấm signal phát rộng, cấm định nghĩa gắn `tenantId`); máy chủ là nơi quyết định.
- Chạy test: nạp mẫu vào engine thử, chạy mọi kịch bản với tham số của mọi `wf_binding` đang dùng mẫu, đo số câu SQL mỗi lần hoàn thành user task (ngân sách ≤ 20); vượt ngân sách thì không được đề nghị phát hành.
- Đề nghị phát hành: tạo merge request vào repo mẫu luồng (tệp BPMN, khai báo tham số, bộ test) qua API của hệ quản lý mã; trạng thái chuyển `HIEU_LUC` chỉ khi pipeline báo đã ký cosign (ghi `signature`, `approved_by`).
- Saga phát hành cuốn chiếu trên khung DV-06-05: lần lượt từng cell, kiểm sức khoẻ engine trước khi sang cell kế, bù trừ là đặt lại phiên bản trước làm mặc định; chặn triển khai trong giờ cao điểm và 3 ngày cuối kỳ; phát `config.wf_template.published.v1` khi lên `HIEU_LUC`.
- Điểm lệch cần lưu ý: BS-01 là đề xuất chưa vào TL; vai trò `wf_template_author` chưa có trong `docs/phan-quyen/ma-tran.json` (không tự thêm vai trò vào mã, dùng quyền theo ma trận hiện có và ghi đề nghị); BS-01 nhắc "cell thí điểm CELL-NB" không có trong danh mục cell của baseline mục 6, nên thứ tự cuốn chiếu là cấu hình, không cố định mã cell; sự kiện `config.wf_template.published.v1` là đề xuất của BS-01, không gán mã EVT. Ghi các điểm vào wiki trang 14.
- Gợi ý tách: (a) migration, vòng đời, linter, chạy test; (b) merge request, ký, saga triển khai cuốn chiếu.

## 4. Ngoài phạm vi

- Trình soạn BPMN trên giao diện SCR-1505 (WEB-061); pipeline CI ký cosign của repo mẫu luồng (task CI).

## 5. Tệp được phép sửa

- `backend/services/workflow-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/workflow-service -am verify` chạy xanh gồm Testcontainers, kiểm thử hợp đồng các thao tác ở bảng, `openapi-diff`.
- Ca bắt buộc: sửa phiên bản `HIEU_LUC` bị từ chối; người soạn tự duyệt bị từ chối; mẫu vượt 20 câu SQL mỗi lần hoàn thành bị chặn; saga dừng và bù trừ khi một cell không khoẻ.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.

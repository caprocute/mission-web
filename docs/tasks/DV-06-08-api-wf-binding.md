# DV-06-08 · QT-15/SCR-1504 Tham số luồng duyệt `wf_binding`: lưu, linter tham số, mô phỏng trên engine thử

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-06-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | DV-06-03 |
| Truy vết | QT-15 → SCR-1504 → `GET /api/v1/policies/packs/{id}/versions/{v}/workflow-bindings` (`listWorkflowBindings`), `GET/PUT .../workflow-bindings/{key}` (`getWorkflowBinding`, `saveWorkflowBinding`), `POST /api/v1/policies/workflow-bindings/simulate` (`simulateWorkflowBinding`), `GET /api/v1/policies/workflow-templates*` (owner MOD-05) → dữ liệu ở SVC-06 → bảng `flowable.wf_binding`, `flowable.wf_template`; BR-15-11, BR-15-12 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người soạn chính sách cấu hình luồng duyệt cho tenant, khối, loại hồ sơ bằng tham số trên một mẫu có sẵn, không vẽ BPMN: số cấp, vai trò từng cấp, thời hạn, nhắc, uỷ quyền, bước ký, điều kiện bỏ qua. Khi xong, tham số được kiểm bằng linter và miền giá trị của mẫu trước khi lưu, có thể mô phỏng trên engine Flowable thử riêng (không phải engine của cell), và luồng chỉ ghi vào `wf_binding` (BR-15-11).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-15 (dòng 1284–1609), BR-15-11, BR-15-12, ràng buộc tham số (dòng 1421–1428, 1583–1590), SCR-1504 "Cấu hình luồng duyệt (mẫu + tham số)" (dòng 1541).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương G2 "Bảng tham số theo tenant" (dòng 2021–2023) và "Quy tắc quản trị chống mạng nhện" (dòng 2149).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `wf_binding` (dòng 3256).
- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-01-giao-dien-cau-hinh-quy-trinh-Flowable.md` mục "Thay đổi dữ liệu đề xuất" (`wf_binding.version_no`, `status`).
- Hợp đồng: `contracts/openapi/modules/policies.yaml` các thao tác ở bảng (schema `WorkflowBinding`, `WorkflowSimulateRequest`).
- Wiki: `wiki/05b-quy-trinh-va-man-hinh-qt09-18.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-1504), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Viết API nội bộ của SVC-06 (client dịch vụ, không công bố ra cổng ngoài) để đọc, lưu `wf_binding` theo khoá (tenant, `process_kind`, `subject_group_id`, `flow`, `period_type`) với `If-Match`, và đọc danh mục mẫu, phiên bản mẫu.
- Linter tham số khi lưu: số cấp trong khoảng mẫu cho phép (2–4), vai trò tồn tại trong `config.role_binding`, thời hạn 1–15 ngày làm việc, không bỏ bước ký bắt buộc, điều kiện bỏ qua là biểu thức CEL trả bool và chỉ đặt ở cấp mà mẫu cho phép, trừ tiến độ 0,00–0,20 (TL-02b); lỗi trả 422 với danh sách quy tắc vi phạm.
- Mô phỏng: nạp mẫu và tham số vào engine Flowable thử tách biệt (cấu hình riêng, không dùng CSDL của cell), chạy với vài hồ sơ mẫu, trả danh sách bước sinh ra, nhãn linter và kết quả bộ test của mẫu; không ghi `wf_binding`.
- Khởi động tiến trình ở DV-06-03 và DV-06-04 tra `wf_binding` theo (tenant, khối, loại quy trình, kỳ), thiếu dòng thì dùng mặc định khối.
- Kiểm thử: mỗi quy tắc linter có ca đỏ; mô phỏng không tạo dòng nào ở engine của cell; 409 khi `If-Match` lệch.
- Điểm lệch cần lưu ý: hợp đồng đặt các thao tác `/policies/.../workflow-bindings*` và `/policies/workflow-templates*` dưới `x-mission.owner: MOD-05`, trong khi dữ liệu nằm ở `flowable.wf_binding` của SVC-06. Chọn: MOD-05 công bố API đúng hợp đồng và gọi API nội bộ của SVC-06 do task này làm. Khung kế hoạch chưa có hàng CORE-05 cho lớp công bố này; ghi vào báo cáo để bổ sung hàng việc.
- Điểm lệch cần lưu ý: cột `version_no`, `status` của `wf_binding` chỉ có trong BS-01 (đề xuất, chưa vào TL-03); chỉ thêm khi DV-06-09 đã gộp migration mở rộng, nếu chưa thì giữ đúng TL-03.

## 4. Ngoài phạm vi

- Lớp API công bố ở MOD-05 và màn hình SCR-1504 (task CORE-05 cần bổ sung, WEB-046).
- Soạn và phát hành mẫu luồng (DV-06-09).

## 5. Tệp được phép sửa

- `backend/services/workflow-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/workflow-service -am verify` chạy xanh gồm Testcontainers và kiểm thử linter, mô phỏng.
- Mô phỏng một mẫu với 10 hồ sơ mẫu trả kết quả trong ≤ 30 giây trên môi trường kiểm thử (suy luận ngưỡng; ghi số đo thật).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.

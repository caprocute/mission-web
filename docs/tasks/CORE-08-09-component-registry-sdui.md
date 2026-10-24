# CORE-08-09 · ADR-05 Component registry có phiên bản và kiểm bố cục SDUI

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-08-08; kiểm biểu thức `visibleWhen` cần NEN-041 (bổ sung so với khung) |
| Truy vết | QT-18 → SCR-1801 → `GET /api/v1/ui/components` (`listUiComponents`) → MOD-08 → `config.component_registry` → BR-18-02, BR-18-12, MSG-18-02, lỗi `UI-422-UNKNOWN-COMPONENT`; ADR-05 lớp 2 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Đưa component registry của SDUI thành dữ liệu có phiên bản phía máy chủ và dùng nó để kiểm mọi bố cục trong UI Pack trước khi phát hành: chỉ component có trong registry thuộc dải `requires.registry`, thuộc tính đúng `propsSchema`, tối đa 12 widget, không HTML hay JavaScript, `visibleWhen` là CEL hợp lệ trong giới hạn chi phí. Khi xong, trình biên tập bố cục ở Config Studio đọc danh mục từ API và máy chủ chặn được mọi bố cục tham chiếu component lạ.

## 2. Đầu vào bắt buộc đọc

- `TL-01-thiet-ke-kien-truc.md` "Chuyên đề G1", mục "Component registry" (dòng 1650) và "Lớp 2" (đoạn kết luận dòng 1502).
- `TL-02b-quy-trinh.md` QT-18 "Quy tắc nghiệp vụ" (BR-18-02, BR-18-12) và SCR-1801 (dòng 2035), bảng MSG-18-02 (dòng 2100).
- `TL-03-thiet-ke-csdl.md` "Bảng config.component_registry (bổ sung)" (dòng 1646).
- `contracts/openapi/modules/config.yaml` `/ui/components`, schema `SduiNode`.
- Mã registry phía web để đối chiếu tên và `propsSchema`: `frontend/projects/sdui-renderer/src/lib/` (chỉ đọc).

## 3. Phạm vi công việc

- Định nghĩa định dạng manifest registry (tên `vt.widget.*`, `vt.layout.*`, `vt.input.*`, `propsSchema` với `additionalProperties: false`, `container`, `since`, cờ `mobile`, `allowed_classification`) và lệnh nạp manifest vào `config.component_registry` theo `registry_version`; phiên bản đã nạp không được sửa, chỉ nạp phiên bản mới (suy luận: manifest sinh từ bản build shell, TL-01 dòng 1807).
- Hiện thực `listUiComponents`: mặc định phiên bản mới nhất, lọc theo `registry`, kèm widget của plugin có trong manifest của tenant (BR-18-12, dữ liệu plugin lấy từ CORE-08-15 qua cổng; trước khi có thì trả danh sách rỗng).
- Hiện thực `LayoutValidator` (một phần của `UiPackChecker`): duyệt cây `SduiNode`, kiểm tên component thuộc dải registry (MSG-18-02, `UI-422-UNKNOWN-COMPONENT`), kiểm `props` theo `propsSchema`, đếm ≤ 12 widget mỗi bố cục, cấm chuỗi có thẻ HTML hay `javascript:`, URL chỉ `https:` hoặc tương đối thuộc danh sách cho phép.
- Kiểm `visibleWhen` bằng `vt-cel`: biên dịch được, kiểu trả bool, độ dài và chi phí trong giới hạn (TL-01 X-G1-04), chỉ tham chiếu biến ngữ cảnh người dùng đã khai báo.
- Nạp seed registry hiện hành của web làm phiên bản đầu tiên.

## 4. Ngoài phạm vi

- Renderer SDUI và registry phía web (`@vt/sdui-renderer`, WEB-009).
- Lọc bố cục theo vai trò khi phục vụ trang chủ (CORE-08-11).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/main/resources/db/migration/config/**` (migration seed registry mới)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `data/seed/component-registry/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; bộ ca kiểm thử bố cục gồm: component lạ, props thừa, 13 widget, chuỗi chứa thẻ HTML, `visibleWhen` sai kiểu, URL `http:`; mỗi ca trả đúng mã lỗi.
- Kiểm thử hợp đồng `listUiComponents` khớp `config.yaml`.
- Tên component trong seed khớp đúng registry web tại thời điểm làm (kiểm thử so khớp hoặc báo cáo nêu chênh lệch).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

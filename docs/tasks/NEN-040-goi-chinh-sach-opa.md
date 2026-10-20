# NEN-040 · ADR-21 Gói chính sách OPA (Rego) sinh từ ma trận phân quyền, kiểm thử `opa test`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-040` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | L (nên tách: a) bộ sinh Rego RBAC + phạm vi từ `ma-tran.json`; b) quy tắc ABAC cây tổ chức, luồng, mức dữ liệu, tách vai trò, bốn mắt + kiểm thử) |
| Phụ thuộc | NEN-001 |
| Truy vết | ADR-21 → TL-01 "Uỷ quyền tập trung bằng ABAC" → `docs/phan-quyen/ma-tran.json` (12 vai trò AC-01..AC-12, 166 chức năng, 24 dòng API, 12 dòng mức dữ liệu, 32 ràng buộc) → `wiki/15-phan-quyen.md`, `wiki/15a-ma-tran-phan-quyen.md` → `x-mission.roles/scope/aal/fourEyes` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Biến ma trận phân quyền đã chốt thành chính sách OPA chạy được ở máy chủ, sinh tự động từ cùng nguồn `docs/phan-quyen/ma-tran.json` mà frontend đang dùng, để giao diện và máy chủ không bao giờ lệch nhau. Gói chính sách gồm RBAC theo vai trò, ABAC theo cây tổ chức và phạm vi (B, Gn, Đv, ĐvD, Tn, N), luồng Đảng/Nhà nước, mức dữ liệu C0–C3, tách vai trò năm vai và bốn mắt; có bộ kiểm thử `opa test` bao phủ từng ô ma trận. Khi xong, NEN-014 có bundle thật để đánh giá, và mọi thay đổi quyền chỉ cần sửa ma trận rồi sinh lại.

## 2. Đầu vào bắt buộc đọc

- `wiki/15-phan-quyen.md` (toàn trang), `wiki/15a-ma-tran-phan-quyen.md`; `docs/phan-quyen/ma-tran.json`, `docs/phan-quyen/gen_matrix.py` (cách sinh hiện có cho xlsx, wiki, TypeScript).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Uỷ quyền tập trung bằng ABAC" (L3018–3060, chính sách mẫu `package okrkpi.task`: người được giao, lãnh đạo đọc cây đơn vị, `c3_not_recipient`, `step_up_required`), ADR-21 (L3592–3600).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` mục "Danh sách tác nhân" (L123–142), "Nguyên tắc tách vai trò" (L144–148), "Ngữ cảnh làm việc" (L150–152).
- `contracts/openapi/README.md` mục `x-mission`.

## 3. Phạm vi công việc

- Thêm chế độ `--rego` vào `docs/phan-quyen/gen_matrix.py` (giữ nguyên các chế độ cũ) sinh `backend/opa/okrkpi/generated/data.json` gồm: vai trò → AC, chức năng → quyền theo vai trò và phạm vi, API module → phương thức theo vai trò, mức dữ liệu theo vai trò, ràng buộc (tách vai trò, bốn mắt, AAL2) đã chuẩn hoá; ô có dấu `*` (suy luận) được giữ cờ để báo cáo.
- Viết chính sách tay `backend/opa/okrkpi/authz.rego` (gói `okrkpi.authz`) đọc `data.json`: quyết định `allow` và tập `deny` có lý do (`out_of_scope`, `role_not_allowed`, `separation_of_duties`, `four_eyes_required`, `step_up_required`, `c3_not_recipient`, `classification_denied`); đầu vào theo hợp đồng với NEN-014 (chủ thể, hành động, tài nguyên); mặc định từ chối.
- ABAC cây tổ chức: phạm vi Đv/ĐvD dựa trên dữ liệu đơn vị và quan hệ tổ tiên được nạp vào OPA (định dạng dữ liệu cây do task này đặc tả, nguồn là `org_replica.unit_closure_v` qua CORE-00-03, suy luận); luồng `DANG`/`NHA_NUOC` theo phân công; C3 chỉ cho người nhận bất kể vai trò.
- Tách vai trò năm vai (khởi tạo, nghiệm thu, thẩm định, quyết định, khoá kỳ không trùng người trên cùng hồ sơ) dựa trên danh sách người đã tham gia đối tượng do máy chủ truyền vào; ngoại lệ đơn vị nhỏ theo tham số Policy Pack được cho phép nhưng gắn cờ ghi vết (TL-02a L148).
- Bộ kiểm thử `backend/opa/okrkpi/*_test.rego`: sinh ca kiểm thử từ ma trận (mỗi ô có quyền một ca cho phép, mỗi ô `-` một ca từ chối, cho ít nhất mọi dòng `api`) cộng ca tay cho các ràng buộc; độ phủ báo bằng `opa test --coverage`.
- Đóng gói bundle (`opa build`) và ký bundle (khoá ký lấy từ OpenBao ở môi trường thật; kiểm thử dùng khoá tạm, không commit khoá); ghi cách nạp bundle vào OPA cùng pod.
- Cập nhật `wiki/15-phan-quyen.md` mục "Hiện thực ở từng tầng" phần OPA và cách sinh lại.

## 4. Ngoài phạm vi

- Thư viện gọi OPA và đánh giá từng phần: NEN-014; đưa dữ liệu cây tổ chức vào OPA lúc chạy: CORE-00-03 (và DV-04).
- Sửa nội dung ma trận (thêm vai trò `wf_template_author`, quyết định WEB-058): cần người phụ trách chốt, ghi vào báo cáo.

## 5. Tệp được phép sửa

- `backend/opa/**` (mới)
- `docs/phan-quyen/gen_matrix.py` (chỉ thêm chế độ `--rego`, không đổi đầu ra cũ)
- `wiki/15-phan-quyen.md`

## 6. Tiêu chí hoàn thành

- `opa fmt --diff backend/opa` rỗng, `opa check backend/opa` sạch, `opa test backend/opa --coverage` xanh với độ phủ ≥ 90% (báo con số thật).
- `python3 docs/phan-quyen/gen_matrix.py --check` vẫn xanh (đầu ra cũ không đổi); chạy `--rego` hai lần cho kết quả giống hệt (tất định).
- Đối chiếu tự động: với mỗi thao tác trong hợp đồng, vai trò ở `x-mission.roles` được chính sách cho phép ở phạm vi `x-mission.scope`; lệch thì liệt kê trong báo cáo (không tự sửa hợp đồng hay ma trận).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

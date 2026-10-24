# CORE-08-16 · MOD-08 Lưu phân công vai trò và uỷ quyền (`config.role_binding`)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-16` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-08-01, NEN-040; đề xuất WEB-058 phải được duyệt và cấp mã trước |
| Truy vết | WEB-058 (đề xuất) → API đề xuất `/api/v1/config/role-bindings`, `/api/v1/config/delegations` (chưa có trong hợp đồng) → MOD-08 → `config.role_binding`, `config.delegation` (đề xuất) → sự kiện thay đổi vai trò (chờ cấp mã EVT) → Keycloak Admin API |
| Trạng thái | Chờ người dùng chốt (duyệt đề xuất WEB-058 trong `docs/phan-quyen/de-xuat-quan-tri-vai-tro.md`) |

## 1. Mục tiêu

Hiện thực phía máy chủ việc cấp, duyệt thứ hai, thu hồi và tra cứu vai trò có phạm vi đơn vị và thời hạn, cùng uỷ quyền tạm thời (check-in thay, duyệt thay, nghiệm thu thay), theo phương án được người phụ trách chọn trong đề xuất WEB-058. Khi xong, quản trị tenant không còn phải cấp vai trò trực tiếp trên CSDL hay Keycloak; mọi thay đổi quyền có step-up, có người duyệt thứ hai với ba vai trò nhạy cảm, có vết kiểm toán, và OPA cùng token Keycloak nhất quán.

## 2. Đầu vào bắt buộc đọc

- `docs/phan-quyen/de-xuat-quan-tri-vai-tro.md` toàn bộ, đặc biệt mục 2 (nguồn sự thật vai trò), mục 6 (API đề xuất), mục 7 (điểm cần quyết định).
- `TL-03-thiet-ke-csdl.md` "Bảng config.role_binding" (dòng 1596).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` và bản trích `extract/tl05-bs-hientrang.md` mục 4.8.
- `wiki/15-phan-quyen.md`, `wiki/15a-ma-tran-phan-quyen.md` (ràng buộc 29, 30).

## 3. Phạm vi công việc

- Điều kiện bắt đầu: người phụ trách đã chọn phương án nguồn sự thật (A, B hoặc C), đã cấp mã SCR, BR, MSG, EVT, và hợp đồng `config.yaml` đã có các đường dẫn quản trị vai trò, uỷ quyền qua một task sửa hợp đồng riêng. Thiếu một trong các điều đó thì dừng và báo cáo, không tự đặt mã.
- Theo phương án A (đề xuất mặc định): API cấp vai trò ghi `config.role_binding` (người, `role_code` thuộc chín giá trị CHECK, đơn vị, phạm vi `DON_VI` hoặc `DON_VI_VA_CAP_DUOI`, luồng, `valid_from/to`, lý do), step-up bắt buộc; vai trò `decider`, `second_approver`, `policy_author` chưa hiệu lực tới khi người khác người cấp duyệt (`approved_by <> granted_by`).
- Thu hồi và hết hạn: tác vụ định kỳ idempotent đóng bản ghi quá `valid_to`; mỗi lần cấp, duyệt, thu hồi, hết hạn ghi outbox với mã sự kiện được cấp.
- Consumer đồng bộ realm role sang Keycloak qua Admin API (tài khoản dịch vụ lấy bí mật từ OpenBao), có thử lại và đối soát định kỳ giữa `role_binding` và realm.
- Uỷ quyền: bảng `config.delegation` theo đề xuất (migration mới chỉ thêm), công bố `config.api.DelegationApi` để MOD-01, MOD-03 kiểm người làm thay (CORE-03-08 đang chờ cổng này).
- Cung cấp dữ liệu cho OPA (gói NEN-040) qua bundle hoặc API dữ liệu theo cách NEN-040 đã chọn.

## 4. Ngoài phạm vi

- Màn hình quản trị vai trò (WEB-058 pha 2).
- Sửa hợp đồng OpenAPI và cấp mã (task riêng của người phụ trách).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/main/resources/db/migration/config/**` (migration mới cho `config.delegation`, cột trạng thái nếu được chốt)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `deploy/policies/**` (dữ liệu và quy tắc OPA liên quan)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử tích hợp Testcontainers (PostgreSQL, Kafka, Keycloak) cho: tự duyệt vai trò nhạy cảm bị chặn, thiếu step-up bị chặn, cấp xong thì realm role xuất hiện trong Keycloak, hết hạn thì bị gỡ.
- `opa test` xanh cho quy tắc mới; `python docs/phan-quyen/check_realm.py` không báo lệch với realm kiểm thử.
- Báo cáo cuối task theo AGENTS.md mục 6, ghi rõ phương án và mã đã được cấp.

## 7. Báo cáo

Theo AGENTS.md mục 6.

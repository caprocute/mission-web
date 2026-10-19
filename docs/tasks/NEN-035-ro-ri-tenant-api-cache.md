# NEN-035 · ADR-07/ADR-18 Kiểm thử rò rỉ tenant lớp API, bộ đệm Valkey, chỉ mục tìm kiếm và kho phân tích

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-035` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c (mốc M0 – Nền móng) |
| Luồng đề xuất | B9 |
| Cỡ việc | M |
| Phụ thuộc | NEN-034, CORE-00-02 |
| Truy vết | ADR-07, ADR-14, ADR-15, ADR-18 → TL-03 "Kiểm thử rò rỉ tenant", "Quy ước khoá" (Valkey), "Đánh chỉ mục tìm kiếm OpenSearch", "CDC phân tích sang ClickHouse" → mọi thao tác đọc danh sách trong `contracts/openapi/modules/*.yaml` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Mở rộng lưới an toàn chống rò rỉ tenant lên các tầng ngoài CSDL: API (một tenant gọi API không bao giờ nhận đối tượng của tenant khác), bộ đệm Valkey (khoá luôn mang tenant, không lớp nào dùng client Valkey trực tiếp), chỉ mục OpenSearch (alias có lọc `tenant_id`) và kho phân tích ClickHouse/Cube (lọc an ninh theo tenant). Vì SVC-15, SVC-16 chưa có khi task này chạy, phần tìm kiếm và phân tích được giao dưới dạng bộ kiểm thử dùng lại để DV-15, DV-16 gắn vào; phần API và bộ đệm chạy được ngay trên mission-core.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Kiểm thử rò rỉ tenant" (L736–754), chương "Bộ nhớ đệm Valkey" (L4476–4505: "Quy ước khoá" `t:<tenant>:<module>:<loại>:<mã>`, `TenantCacheKey`, ArchUnit chặn `RedisTemplate`), "Đánh chỉ mục tìm kiếm OpenSearch" (L4351–4357), "CDC phân tích sang ClickHouse" (L4313–4349).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Bộ đệm phân tán Valkey" (L2748–2761), SVC-15 (L1194–1204), SVC-16 (L1206–1208).
- `contracts/openapi/README.md` (`x-mission.scope`), `wiki/15a-ma-tran-phan-quyen.md`; kết quả NEN-034, CORE-00-02.

## 3. Phạm vi công việc

- Thư viện khoá đệm: thêm `TenantCacheKey` vào `vt-platform-starter` (khoá dạng `t:<tenant>:<module>:<loại>:<mã>[:<phụ>]`, tenant lấy từ `TenantContext`, không cho tạo khoá thiếu tenant) và luật ArchUnit cấm dùng `RedisTemplate`/client Valkey trực tiếp ngoài thư viện nền; bổ sung vì danh mục chưa có hàng riêng cho thư viện này (ghi vào báo cáo).
- Điểm lệch cần lưu ý: TL-01 dùng khoá `{<tenant>}:<module>:...` (thẻ băm theo tenant), TL-03 dùng `t:<tenant>:...` và nói không đặt thẻ băm trên tiền tố tenant; task theo TL-03 (chi tiết hơn về đệm) và ghi vào báo cáo.
- Kiểm thử API hai tenant sinh từ hợp đồng: đọc mọi thao tác `get` trả danh sách (`items` + `nextCursor`) của owner SVC-05 trong `contracts/openapi/modules/*.yaml`, với hai JWT thử thuộc hai tenant và dữ liệu mồi, gọi từng thao tác đã hiện thực (thao tác chưa hiện thực trả 501 thì ghi nhận, không tính lỗi) và kiểm không có `id` của tenant kia trong phản hồi; thao tác lấy một đối tượng của tenant kia phải trả 404 hoặc 403 (suy luận cách sinh, ghi vào báo cáo).
- Kiểm thử bộ đệm: dựng Valkey Testcontainers, hai tenant cùng mã đối tượng không đọc nhầm giá trị của nhau; xoá theo tenant bằng `SCAN` chỉ xoá khoá của tenant đó.
- Bộ kiểm thử dùng lại cho OpenSearch: lớp cơ sở nhận tên alias và kiểm truy vấn qua alias của tenant A không bao giờ trả tài liệu có `tenant_id` của B (Testcontainers OpenSearch); chưa gắn vào service nào, chỉ có kiểm thử tự kiểm với chỉ mục mẫu.
- Bộ kiểm thử dùng lại cho ClickHouse/Cube: lớp cơ sở kiểm truy vấn dưới ngữ cảnh bảo mật tenant A trả 0 dòng của B trên bảng mẫu có `tenant_id` (row policy ClickHouse hoặc bộ lọc Cube, suy luận theo TL-01 SVC-15); kiểm cột C2 dạng rõ không có trong bảng phân tích mẫu.
- Viết một mục ngắn trong README mô tả lớp 5 (hai tenant canh gác ở cell pool, job mỗi giờ gọi API danh sách, cảnh báo SOC) để nhóm vận hành làm sau; không hiện thực job này.

## 4. Ngoài phạm vi

- Gắn kiểm thử vào search và analytics thật: DV-16, DV-15 (bắt buộc kế thừa lớp cơ sở của task này).
- Job tenant canh gác trên môi trường thật: nhóm VH/QA.

## 5. Tệp được phép sửa

- `backend/libs/vt-platform-starter/**` (gói `cache`, luật ArchUnit)
- `backend/libs/vt-tenant-test/**` hoặc thư mục kiểm thử tương ứng đã chọn ở NEN-034
- `backend/mission-core/src/test/**` (chỉ thêm kiểm thử API hai tenant)
- `wiki/17-thiet-ke-csdl-va-da-khach-thue.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-platform-starter,mission-core -am verify` chạy xanh; báo cáo liệt kê số thao tác danh sách đã kiểm và số thao tác còn 501.
- Kiểm thử âm: một bộ đệm thử ghi khoá không có tenant bị chặn; một alias OpenSearch mẫu thiếu bộ lọc làm kiểm thử dùng lại thất bại.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 17 (nếu đã có) mục "Kiểm thử rò rỉ tenant".

## 7. Báo cáo

Theo AGENTS.md mục 6.

# DV-04-05 · QT-01/SCR-0103 Nhập cây tổ chức từ Excel (202, báo cáo lỗi)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-04-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | S |
| Phụ thuộc | DV-04-02; DV-04-03 (đường ghi thay đổi và EVT-14) nên có trước (suy luận) |
| Truy vết | QT-01 → SCR-0103 → `POST /api/v1/org/imports` (`importOrgExcel`, 202) → `GET /api/v1/jobs/{id}` (`getJob`) → SVC-04 → bảng `org.org_unit`, `org.org_unit_relation`, `org.org_change_order` → EVT-14; BR-01-05, BR-01-06 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Quản trị tenant nạp cây tổ chức ban đầu từ một tệp Excel theo mẫu, hệ thống xử lý nền, trả mã theo dõi ngay và cho tải báo cáo lỗi từng dòng. Khi xong, tenant mới có cây tổ chức trong vài phút mà không phải nhập tay, mỗi đơn vị nhập được phát EVT-14 để mission-core có bản sao.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01 SCR-0103 (dòng 656–698, chức năng nhập ở dòng 686–691), BR-01-05, BR-01-06, mã đơn vị duy nhất (dòng 674).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `org.org_unit` (dòng 1119–1140).
- Hợp đồng: `contracts/openapi/modules/org.yaml` `importOrgExcel`; `contracts/openapi/modules/jobs.yaml` `getJob`.
- Wiki: `wiki/06-quy-uoc-api.md` (tác vụ 202), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Hiện thực `importOrgExcel` (Idempotency-Key, `tenant_admin`): nhận tệp qua tham chiếu đối tượng đã tải lên evidence-service hoặc multipart theo đúng hợp đồng, xếp job, trả 202 + `Location: /api/v1/jobs/{id}` trong ≤ 200 ms (NFR-03).
- Đọc tệp theo luồng (không nạp cả tệp vào bộ nhớ), kiểm từng dòng: mã đơn vị duy nhất, đơn vị cha tồn tại trong tệp hoặc trong cây, không vòng, khối thuộc 9 khối PL 2A (BR-01-05), ngày hiệu lực hợp lệ.
- Ghi theo lô qua đường ghi phiên bản (BR-01-06), gắn một `org_change_order` cho cả lần nhập; dòng lỗi không chặn dòng hợp lệ nếu chế độ "bỏ qua dòng lỗi" được chọn, ngược lại toàn bộ lần nhập dừng (theo tham số của hợp đồng).
- Sinh báo cáo lỗi (số dòng, cột, lý do bằng tiếng Việt nghiệp vụ) lưu cùng job để tải về; phát EVT-14 cho mỗi đơn vị được tạo.
- Kiểm thử: tệp mẫu 1.000 đơn vị nhập thành công; tệp có vòng và mã trùng cho báo cáo lỗi đúng dòng; gọi lại cùng `Idempotency-Key` không nhập hai lần.
- Điểm lệch cần lưu ý: stack đã chốt trong `docs/quy-dinh/BACKEND.md` không ghi thư viện đọc Excel; nếu cần thêm (ví dụ Apache POI ở chế độ đọc luồng), nêu lý do trong PR và chờ người phụ trách duyệt theo quy tắc 1 của AGENTS.md.

## 4. Ngoài phạm vi

- Nhập nhân sự và đồng bộ từ nguồn ngoài (DV-04-07, DV-13-06); màn hình nhập (WEB-018).

## 5. Tệp được phép sửa

- `backend/services/org-service/**`
- `data/seed/` (thêm tệp Excel mẫu và mẫu tiêu đề cột, không chứa dữ liệu thật)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/org-service -am verify` chạy xanh gồm Testcontainers và kiểm thử hợp đồng `importOrgExcel`.
- Nhập 1.000 đơn vị trên môi trường kiểm thử, ghi thời gian và bộ nhớ đỉnh vào báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.

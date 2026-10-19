# DL-13 · NFR-09/TL-03 ch.16 Lưu giữ, lưu trữ lạnh, giữ pháp lý, xoá bằng mật mã theo bảy lớp lưu giữ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-13` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – quy mô quốc gia (suy luận: dữ liệu bắt đầu hết thời hạn nóng sau 2–5 năm; cấu hình lớp lưu giữ nên có sớm) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày; nên tách theo bốn việc ở cuối mục 3 nếu lớn) |
| Phụ thuộc | NEN-015, INF-18 (bổ sung hiển nhiên: CORE-08-07 cho `config.tenant_setting`) |
| Truy vết | NFR-09; TL-03 "Lưu giữ, lưu trữ và xoá dữ liệu"; NĐ 13/2023 → MOD-08, SVC-08, SVC-09 và mọi schema có phân vùng kỳ → bảng `config.tenant_setting`, `keyring.dek`, `evidence.object_meta` (`legal_hold`), `ledger.archive_segment` → bucket Ceph Object Lock |
| Trạng thái | Chưa bắt đầu (thời hạn chính thức do cơ quan chủ quản quyết định; giá trị trong TL là đề xuất) |

## 1. Mục tiêu

Hệ thống áp được bảy lớp lưu giữ của TL-03: mỗi lớp có thời hạn nóng, thời hạn tổng và cách kết thúc (lưu trữ Parquet có Object Lock, xoá bằng mật mã, xoá phân vùng), cấu hình theo tenant, tôn trọng cờ giữ pháp lý, và có công cụ khôi phục dữ liệu lưu trữ vào CSDL tạm cô lập khi được phê duyệt. Việc này đáp ứng NFR-09 và yêu cầu xử lý dữ liệu cá nhân theo NĐ 13/2023 (dữ liệu bắt buộc lưu thì chỉ hạn chế xử lý, không xoá).

## 2. Đầu vào bắt buộc đọc

- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: chương "Lưu giữ, lưu trữ và xoá dữ liệu" (dòng 4620–4642: "Thời hạn lưu giữ", "Cơ chế lưu trữ", "Xoá và giữ pháp lý"); "Sổ cái bất biến › Lưu trữ dài hạn"; mục mã hoá (DEK theo tenant × tháng × mục đích, `keyring.dek`).
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`, chương "Sao lưu dự phòng và phục hồi thảm hoạ", mục "Bảng lịch sao lưu" (thời hạn bucket `backup`, `transfer`).
- Baseline NFR-09; `docs/quy-dinh/BACKEND.md` mục 3 (C2, khoá từ OpenBao).
- Hợp đồng `contracts/openapi/modules/config.yaml` (nhóm cấu hình tenant) để biết khoá cấu hình nào đã có.

## 3. Phạm vi công việc

- Khai báo bảy lớp `HO_SO_CAN_BO`, `KHIEU_NAI`, `THUC_HIEN`, `SO_CAI`, `VET_TINH`, `VAN_HANH`, `KY_THUAT` với giá trị mặc định đúng bảng TL-03 trong `config.tenant_setting`, đánh dấu là đề xuất; không cho đặt thấp hơn mức tối thiểu của NFR-09 (sổ cái, nhật ký kiểm toán ≥ 10 năm).
- Viết job lưu trữ: xuất toàn bộ bảng của cùng `period_bucket` hết hạn nóng sang Parquet kèm tệp kê khai, ghi bucket Object Lock, ghi sổ cái, rồi tách phân vùng; cột C2 giữ dạng bản mã.
- Viết job xoá: lớp `THUC_HIEN`, `VET_TINH` hết tổng thời hạn thì huỷ DEK tương ứng (xoá bằng mật mã); lớp `VAN_HANH`, `KY_THUAT` xoá phân vùng; bỏ qua và báo cáo mọi đối tượng có `legal_hold`, đồng thời gia hạn Object Lock cho đối tượng giữ pháp lý.
- Viết công cụ khôi phục dữ liệu lưu trữ vào CSDL tạm cô lập, chỉ chạy khi có phê duyệt (ghi người phê duyệt vào sổ cái) và tự xoá CSDL tạm sau thời hạn.
- Ghi rõ rằng việc tách phân vùng sổ cái do `svc_ledger_archiver` của SVC-09 làm với hai người phê duyệt; task này chỉ gọi đúng quy trình, không cấp quyền mới.
- Viết runbook lưu trữ, xoá, khôi phục có bước kiểm chứng và quay lui.
- Gợi ý tách theo TL-03: DB-RET-01 cấu hình lớp; DB-RET-02 job lưu trữ; DB-RET-03 job xoá có kiểm `legal_hold`; DB-RET-04 công cụ khôi phục.

## 4. Ngoài phạm vi

- Sao lưu và DR (nhóm INF); lưu trữ dài hạn sổ cái bên trong ledger-service (nhóm DV-09).
- Giao diện cấu hình thời hạn cho quản trị tenant (task web riêng nếu cần).

## 5. Tệp được phép sửa

- `backend/libs/retention/**` (suy luận vị trí), khai báo mô-đun trong `backend/pom.xml`
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**` (chỉ khoá cấu hình lưu giữ) và kiểm thử tương ứng
- `deploy/charts/*/templates/*retention*`
- `ops/runbooks/luu-tru-xoa-khoi-phuc.md`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q verify` cho các mô-đun bị đụng tới xanh, gồm kiểm thử tích hợp Testcontainers (PostgreSQL 17 và kho S3 tương thích có Object Lock, ví dụ MinIO; thêm phụ thuộc thử nghiệm phải nêu lý do trong PR).
- Kiểm thử: phân vùng quá hạn được xuất Parquet, tệp kê khai khớp số dòng và băm, phân vùng bị tách; đối tượng có `legal_hold` không bị xoá; sau khi huỷ DEK, đọc lại cột C2 thất bại có kiểm soát.
- Công cụ khôi phục từ chối chạy khi không có phê duyệt.
- `helm lint` và `helm template` không lỗi; runbook có bước kiểm chứng và quay lui.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

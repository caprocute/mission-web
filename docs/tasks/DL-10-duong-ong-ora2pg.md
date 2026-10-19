# DL-10 · ADR-20/TL-03 ch.15 Đường ống chuyển đổi ora2pg và kiểm tra chất lượng dữ liệu

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – lõi nghiệp vụ trên nền tảng mới |
| Luồng đề xuất | B11 |
| Cỡ việc | L (> 1 ngày, nên tách: xem cuối mục 3) |
| Phụ thuộc | DL-09 (bổ sung hiển nhiên: NEN-015 cho mã hoá cột C2, DV-08-02 cho chép tệp minh chứng sang Ceph) |
| Truy vết | ADR-20; TL-03 "Quản lý thay đổi lược đồ và di trú từ Oracle", job R8 → Oracle `VOFFICE` → PostgreSQL 17 schema `task`, `okr`, `product`, `evidence`, `appraisal`, `config` → `integ.mapping_xref` → bucket Ceph Object Lock (SVC-08) |
| Trạng thái | Chưa bắt đầu (làm được khi DL-09 xong, tức là khi đã có quyền đọc Oracle hệ hiện hành) |

## 1. Mục tiêu

Có đường ống chuyển dữ liệu từ Oracle hệ hiện hành sang lược đồ mới, chạy lặp lại được, có chế độ chạy thử, và có bước kiểm tra chất lượng cho ra con số đối soát R8 (đếm dòng, tổng kiểm theo nhóm, băm dòng sau chuẩn hoá) bằng 0 trên dữ liệu thử. Lược đồ đích do Flyway quản lý; ora2pg chỉ dùng để xuất dữ liệu và tham khảo đổi kiểu, không sinh lược đồ đích.

## 2. Đầu vào bắt buộc đọc

- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: "Flyway và quy trình thay đổi lược đồ", "Di trú từ hệ hiện tại theo mẫu Strangler Fig" (dòng 4592–4618), chương "Đối soát và kiểm tra nhất quán" (R8, băm tổng hợp XOR của SHA-256 từng dòng chuẩn hoá); chương "Kho đối tượng Ceph", mục "Khoá đối tượng và khử trùng" (khoá theo băm SHA-256, Object Lock).
- Kết quả DL-09: `data/migration/oracle/anh-xa.yaml`, `data/migration/oracle/ddl/`.
- `docs/quy-dinh/BACKEND.md` mục 3 (C2 mã hoá, khoá từ OpenBao, nhật ký không chứa C2).
- Đầu vào người dùng cần cấp: như DL-09; thêm một bản sao dữ liệu Oracle tại môi trường thử (đã giả danh hoá hoặc được phép dùng theo quy định dữ liệu cá nhân) để chạy thử toàn bộ.

## 3. Phạm vi công việc

- Viết công cụ chuyển đổi Java 21 trong `backend/tools/legacy-migration/` (suy luận vị trí) chia theo năm nhóm bảng của DL-09, mỗi nhóm một bước có thể chạy riêng; cấu hình ora2pg cho bước xuất dữ liệu nằm trong `data/migration/oracle/ora2pg/`.
- Hiện thực chuẩn hoá theo TL-03: chuỗi NFC, chuỗi rỗng thành NULL, `DATE` giờ địa phương sang `timestamptz` UTC, `NUMBER` sang `numeric` đúng độ chính xác, mã trạng thái cũ sang trạng thái chuẩn theo bảng ánh xạ.
- Ánh xạ định danh qua `integ.mapping_xref` (tạo khi chưa có, dùng lại khi đã có) để chạy lại không sinh bản ghi trùng.
- Mã hoá cột C2 bằng `FieldEncryptor` của `vt-platform-starter` khi ghi; không ghi giá trị C2 vào nhật ký hay báo cáo.
- Chép tệp minh chứng sang Ceph theo khoá băm SHA-256, đặt Object Lock, ghi `evidence.object_version` (qua API hoặc thư viện của SVC-08, không ghi thẳng bucket ngoài quy ước).
- Viết bước kiểm tra chất lượng sinh `reconcile_run` loại R8: đếm dòng, tổng kiểm theo nhóm, băm dòng sau chuẩn hoá kiểu cho từng bảng; xuất báo cáo sai lệch.
- Có chế độ `--dry-run` (không ghi đích, chỉ báo cáo) và chế độ chạy theo kỳ (ETL theo kỳ đánh giá như TL-03).
- Gợi ý tách: DL-10a khung, nhóm `task` và `okr`; DL-10b nhóm `product`/`evidence` kèm chép tệp; DL-10c nhóm `appraisal`, `config`, danh mục công việc (rà soát thủ công).

## 4. Ngoài phạm vi

- Đưa kỳ cũ về chỉ đọc và niêm phong sổ cái (DL-11); CDC Oracle chạy song song và ghi ngược (LEG-10).
- Khung `reconcile_run` dùng chung và các job R1–R7 (DL-12).

## 5. Tệp được phép sửa

- `backend/tools/legacy-migration/**`, khai báo mô-đun trong `backend/pom.xml`
- `data/migration/oracle/**`
- `infra/dev/` (chỉ thêm hồ sơ Docker Compose Oracle Free cho kiểm thử cục bộ, cần người phụ trách duyệt ảnh)
- `ops/runbooks/chuyen-du-lieu-he-cu.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl tools/legacy-migration -am verify` xanh, gồm kiểm thử tích hợp Testcontainers với Oracle Free (DDL của DL-09, dữ liệu giả lập) và PostgreSQL 17 đã áp migration thật.
- Trên bộ dữ liệu giả lập: R8 báo 0 sai lệch cho mọi bảng; chạy lại lần hai không thêm dòng; `--dry-run` không ghi gì.
- Kiểm thử chứng minh cột C2 ở đích là bản mã và nhật ký không chứa giá trị gốc.
- Runbook có bước kiểm chứng và bước quay lui.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

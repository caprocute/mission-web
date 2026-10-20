# INF-12 · ADR-13 Ceph RGW, bucket minh chứng Object Lock compliance, multisite

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-12` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B8 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | INF-09 |
| Truy vết | ADR-13 → TL-04 mục 3.8, mục 8.4 "Ceph: multisite RGW và Object Lock" → TL-03 ch. "Kho đối tượng Ceph" → SVC-08, SVC-11 → bảng `evidence.object_meta`, `evidence.evidence_version` → SEC-44 → tiêu chí E4, I5, C8 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khai báo dạng mã kho đối tượng của một cell trên Ceph RGW: realm, zonegroup, zone cho multisite giữa hai TTDL, các bucket theo TL-03 với Object Lock chế độ COMPLIANCE, chính sách bucket theo tiền tố tenant, vòng đời và người dùng S3 tách quyền. Khi xong, ghi đè hoặc xoá một phiên bản minh chứng trong bucket bị khoá bị RGW từ chối, và mỗi service chỉ đọc ghi đúng tiền tố được cấp.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Lưu trữ: Ceph RBD và volume NVMe cục bộ" (486–532), "Định cỡ kho đối tượng Ceph" (812–825), "Ceph: multisite RGW và Object Lock" (2176–2215, lệnh `radosgw-admin` 2181–2200).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` chương "Kho đối tượng Ceph" (4507–4537) và "Lưu giữ, lưu trữ và xoá dữ liệu" (4620–4642, `legal_hold`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` "Kho đối tượng cho minh chứng" (441–461).
- `infra/dev/minio/init-buckets.sh` (bucket dev hiện có), `docs/quy-dinh/HA-TANG.md`.

## 3. Phạm vi công việc

- Viết script khởi tạo realm cho cell (ví dụ `cell-g4`), zonegroup `cell-<x>-zg`, zone master `cell-<x>-hl` và zone `cell-<x>-sn`, người dùng hệ thống đồng bộ, đúng trình tự lệnh TL-04 mục 8.4; khoá truy cập lấy bằng `bao kv get` lúc chạy, không ghi vào tệp.
- Viết IaC bucket theo TL-03: `evidence` (versioning, COMPLIANCE theo lớp lưu giữ), `dossier` (COMPLIANCE), `templates` (không khoá), `ledger-archive` (COMPLIANCE), `uploads-tmp` (không versioning, xoá sau 1 ngày), `transfer` (GOVERNANCE 1 năm); tên bucket mang tiền tố cell theo TL-04 (ví dụ `ev-cell-g4`).
- Viết chính sách bucket theo tiền tố khoá `t/<tenant>/sha256/<ab>/<băm>`: người dùng S3 của evidence-service, document-service chỉ `PutObject`, `GetObject`, `ListBucket` trên bucket của mình; không ai ngoài tài khoản quản trị kho có `DeleteObject` hoặc đổi cấu hình Object Lock.
- Viết vòng đời: `uploads-tmp` hết hạn 1 ngày; chuyển lớp lưu trữ NVMe sang HDD EC 4+2 sau 30 ngày cho `evidence` (TL-03), cấu hình qua values.
- Viết cảnh báo đồng bộ multisite `CephSyncBehind` dựa trên `ceph_rgw_sync_behind_shards` và tuổi đối tượng chưa đồng bộ > 10 phút (dùng lại ở INF-15).
- Điểm lệch cần lưu ý: TL-04 mục 8.4 đặt bucket minh chứng `DefaultRetention COMPLIANCE Years 10`, còn TL-03 đặt `dossier` và `ledger-archive` 20 năm và `evidence` theo lớp lưu giữ (lớp `THUC_HIEN` 10 năm). Chọn thời hạn theo TL-03 cho từng bucket (TL-03 là nguồn của quy tắc lưu giữ dữ liệu; thời hạn dài hơn không vi phạm TL-04), tham số hoá trong values và ghi vào `wiki/14-quyet-dinh-va-cau-hoi-mo.md` để chủ đầu tư xác nhận.

## 4. Ngoài phạm vi

- Dựng cụm Ceph vật lý bằng cephadm (phụ thuộc hạ tầng thật) và kho sao lưu `ceph-sn-bk` (INF-17).
- Sao lưu hằng ngày bằng `rclone` và kiểm mẫu 1.000 đối tượng (INF-18); mã hoá SSE-C phía evidence-service (task DV-08).

## 5. Tệp được phép sửa

- `deploy/charts/rgw-cell/**` (Job khởi tạo bucket và chính sách, tên suy luận), `ops/runbooks/ceph-multisite.md`.
- `deploy/environments/kind/**` (Rook-Ceph một node cho kiểm thử, nếu dùng).
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template`, `kubeconform` không lỗi; `shellcheck` sạch cho script khởi tạo.
- Trên Rook-Ceph một node chạy trong kind/k3d (hoặc RGW chạy bằng container nếu máy không đủ tài nguyên, ghi rõ): tạo bucket có Object Lock COMPLIANCE, ghi một đối tượng, thử xoá phiên bản và giảm hạn giữ đều bị từ chối; người dùng của evidence-service không đọc được tiền tố tenant khác qua chính sách (kiểm bằng `aws s3api` hoặc `s5cmd`).
- Multisite: nếu dựng được hai RGW, `radosgw-admin sync status` báo đồng bộ; nếu không, báo cáo ghi rõ chưa kiểm chứng và để lại kịch bản kiểm thử cho QA-14.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

# DV-01-04 · ADR-07 APISIX lớp ngoài toàn cục: phân giải tenant sang cell, đệm 5 phút, khám phá cell

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-01-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-01-01, DV-03-02 |
| Truy vết | QT-00 bước 2 → API `GET /api/v1/registry/resolve` (`resolveTenantCell`) → SVC-01 lớp ngoài, SVC-03 → bảng `registry.tenant_domain`, `registry.tenant_cell`; ADR-07, ADR-29; TL-04 WI-07, WI-08 (OPS-07, DR-10) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng APISIX lớp ngoài ở vùng điều phối toàn cục: nhận request trên tên miền của tenant hoặc tên miền chung, gọi SVC-03 để biết tenant thuộc cell nào, đệm kết quả và chuyển tiếp hoặc chuyển hướng tới cổng của cell. Khi xong, người dùng gõ một địa chỉ duy nhất vẫn tới đúng cell, request sau đi thẳng nhờ cookie định tuyến ký HMAC, tenant đã chuyển cell nhận 421 để shell tự chuyển, và khi registry ngừng thì tenant đã đệm vẫn dùng được.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-01 api-gateway" (dòng 907), "SVC-03 tenant-registry" (dòng 1007–1026) và "Định tuyến tới cell" (dòng 2718–2720).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "GeoDNS và định tuyến tenant tới cell" (dòng 166–193).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-00 bước 2 (dòng 325: phân giải ≤ 50 ms).
- Hợp đồng: `contracts/openapi/modules/platform.yaml` thao tác `resolveTenantCell` (cookie định tuyến, 421 `REGISTRY-421-TENANT-MOVED`, 503 `Retry-After: 30`), `listCells`.
- Wiki: `wiki/01-tong-quan-he-thong.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Tạo chart hoặc values riêng cho APISIX lớp ngoài (triển khai ở vùng toàn cục, 3 vùng sẵn sàng) tách khỏi APISIX của cell.
- Viết plugin Lua phân giải: theo `Host` gọi `GET /api/v1/registry/resolve?host=` (hoặc `?tenant=` khi khám phá bằng mã đơn vị/email công vụ trên tên miền chung), đệm kết quả trong bộ nhớ chia sẻ 5 phút, chuyển tiếp tới `apiBaseUrl` của cell hoặc trả 307 tới URL cell trên tên miền chung.
- Đặt và kiểm cookie định tuyến ký HMAC hạn 5 phút; khoá HMAC nạp từ OpenBao qua External Secrets, không đặt trong values.
- Chuyển tiếp nguyên vẹn mã 421 kèm `Location` khi tenant đã chuyển cell và xoá mục đệm tương ứng; khi SVC-03 trả 503 thì dùng mục đệm còn hạn, không có mục đệm thì trả 503 + `Retry-After: 30`.
- Phía cell: route của DV-01-01 kiểm cookie định tuyến và claim `cell`, từ chối khi không khớp.
- Viết kiểm thử tích hợp với SVC-03 giả lập: trúng đệm không gọi registry, hết hạn gọi lại, 421 làm sạch đệm, registry ngừng vẫn phục vụ tenant đã đệm; đo độ trễ phân giải p95 ≤ 50 ms khi trúng đệm.
- Điểm lệch cần lưu ý: TL-01 ghi đệm 5 phút ở APISIX, TL-03 ghi `registry.tenant_domain` được đệm 30 giây và TL-04 WI-08 ghi đệm 60 giây cho khám phá trên tên miền chung; dùng 5 phút cho phân giải theo tên miền tenant (đúng hợp đồng `Cache-Control: private, max-age=300`) và để thời gian đệm là tham số, ghi lựa chọn vào wiki trang 14.

## 4. Ngoài phạm vi

- GeoDNS PowerDNS, tác vụ `dns-sync`, `/healthz/edge` (task INF của vùng toàn cục).
- API phân giải ở SVC-03 (DV-03-02); xử lý 421 trong `fe-shell` (task WEB).

## 5. Tệp được phép sửa

- `deploy/charts/api-gateway-global/**` (chart mới) hoặc `deploy/charts/api-gateway/**` nếu dùng chung chart với cờ
- `deploy/environments/global/**`
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template | kubeconform -strict` không lỗi.
- Kiểm thử tích hợp bốn ca ở mục 3 chạy xanh; p95 phân giải khi trúng đệm ≤ 50 ms trên máy kiểm thử.
- Cookie định tuyến bị sửa một byte bị từ chối ở cổng cell.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.

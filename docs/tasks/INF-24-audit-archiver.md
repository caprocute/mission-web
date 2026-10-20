# INF-24 · NFR-09/G6 Dịch vụ `audit-archiver`: chuyển nhật ký kiểm toán sang kho lưu trữ bất biến

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-24` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 (cần có trước khi dữ liệu thật vào hệ thống) |
| Luồng đề xuất | B8 |
| Cỡ việc | M |
| Phụ thuộc | INF-12 (bucket Object Lock), INF-19 (luồng `sys.audit.v1`, SIEM); NEN-017 (thư viện Go), INF-13 (khoá ký trong OpenBao Transit), INF-10 (topic, ACL) (bổ sung) |
| Truy vết | NFR-09 (nhật ký kiểm toán ≥ 10 năm), G6 (tách audit log) → TL-04 "Kafka: MirrorMaker 2" (L2174), "Bảng lịch sao lưu" (L2318), SEC-43 → topic `sys.audit.v1`, `iam.auth.event.v1` → `audit-archiver` → Parquet có chữ ký trên bucket Object Lock 10 năm của kho sao lưu |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

TL-04 đặt cho `sys.audit.v1` một đường đi riêng không qua MirrorMaker 2: consumer `audit-archiver` ghi từng lô 5 phút ra tệp Parquet có chữ ký vào bucket Object Lock 10 năm của kho sao lưu, để nhật ký kiểm toán có bản sao bất biến trong vòng 5 phút kể cả khi Kafka phía Nam gặp sự cố. INF-18 và INF-19 đều ghi phần này là "đề xuất hàng khung mới". Task này dựng dịch vụ đó, kiểm chữ ký và đếm bản ghi hằng ngày theo bảng lịch sao lưu. Khi xong, mọi sự kiện kiểm toán của một cell có mặt trong kho bất biến không quá 5 phút sau khi phát, và một công cụ độc lập kiểm được tệp không bị sửa.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Kafka: MirrorMaker 2" (L2114–2174, đoạn `audit-archiver` L2174), "Bảng lịch sao lưu" (L2309–2320, dòng `sys.audit.v1`: 5 phút, giữ 10 năm, Object Lock 10 năm, kiểm chữ ký và đếm bản ghi hằng ngày), "Mục tiêu và nguyên tắc 3-2-1-1-0" (L1981), SOC và SIEM (L3169), G6 (L3578).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Tách nhật ký kiểm toán" (L2994–2996: OpenSearch 5 năm, lưu trữ lạnh sau 1 năm).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` NFR-09; TL-03 "Lưu giữ, lưu trữ và xoá dữ liệu" (L4620–4642).
- `docs/quy-dinh/HA-TANG.md`; thẻ `docs/tasks/INF-18-sao-luu-du-lieu-khac.md`, `INF-19-bao-mat-nen-tang.md`, `INF-12-ceph-object-lock.md`, `CORE-07-12-chi-muc-tra-cuu-nhat-ky-c2.md`.

## 3. Phạm vi công việc

- Viết `services-go/audit-archiver/` (NEN-017): consumer `sys.audit.v1` (và `iam.auth.event.v1` nếu người phụ trách an ninh xác nhận cần cùng kho, suy luận), gom lô theo cửa sổ 5 phút hoặc ngưỡng kích thước, ghi Parquet nén zstd có lược đồ cố định (giữ nguyên thân Avro và header phong bì), commit offset chỉ sau khi tệp đã ghi và khoá thành công.
- Mỗi tệp có tệp kê khai (số bản ghi, offset đầu cuối theo phân vùng, SHA-256 của tệp, băm của kê khai trước để nối chuỗi) và chữ ký qua OpenBao Transit (khoá ký riêng, không xuất được); tải lên bucket kho sao lưu với Object Lock chế độ compliance 10 năm; tên đối tượng theo `cell/yyyy/MM/dd/HH/<phân vùng>-<offset đầu>.parquet` (suy luận).
- Bảo đảm không mất, không trùng: chạy lại sau sự cố ghi lại cùng tên đối tượng cho cùng dải offset (idempotent); dải offset liên tục giữa các tệp được kiểm khi khởi động; chậm quá 5 phút thì phát metric và cảnh báo.
- CronJob kiểm hằng ngày: kiểm chữ ký từng tệp và chuỗi kê khai, đếm bản ghi so với offset Kafka đã đọc, ghi kết quả metric; sai lệch kích hoạt cảnh báo an ninh.
- Công cụ dòng lệnh `audit-verify` độc lập (chỉ cần khoá công khai và quyền đọc bucket) cho kiểm toán viên.
- Chart `deploy/charts/audit-archiver/`: không root, hệ tệp chỉ đọc, NetworkPolicy chỉ tới Kafka, OpenBao, RGW kho sao lưu; quyền ghi bucket không có quyền xoá hay đổi retention.
- Điểm lệch cần lưu ý: TL-01 giữ nhật ký kiểm toán 5 năm trong OpenSearch (chuyển lạnh sau 1 năm), TL-04 giữ bản Parquet 10 năm, NFR-09 ghi "≥ 10 năm, cần đối chiếu quy định lưu trữ hồ sơ cán bộ" còn TL-03 giữ hồ sơ cán bộ và sổ cái 20 năm; task cấu hình 10 năm theo TL-04 dạng tham số, ghi câu hỏi kéo dài lên 20 năm vào `wiki/14`.

## 4. Ngoài phạm vi

- Chỉ mục tra cứu OpenSearch và API tra cứu (CORE-07-12); luồng SIEM (INF-19); dựng cụm Ceph kho sao lưu phía Nam vật lý (hạ tầng thật).

## 5. Tệp được phép sửa

- `services-go/audit-archiver/**` (mới)
- `deploy/charts/audit-archiver/**` (mới), `deploy/environments/kind/**` (cài thử)
- `ops/runbooks/audit-archiver.md`
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/audit-archiver && go vet ./... && go test ./... && govulncheck ./...` xanh, gồm Testcontainers Kafka + MinIO có Object Lock + OpenBao dev: 10.000 sự kiện vào đủ tệp, không trùng sau khi giết tiến trình giữa lô; sửa một byte tệp thì `audit-verify` báo lỗi; xoá đối tượng bị kho từ chối.
- `helm lint`, `helm template`, `kubeconform -strict` sạch; cài lên kind, pod sẵn sàng, độ trễ từ sự kiện tới tệp ≤ 5 phút đo được.
- Runbook có bước kiểm chứng và quay lui; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

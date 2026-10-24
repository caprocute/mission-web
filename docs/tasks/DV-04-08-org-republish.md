# DV-04-08 · SVC-04/EVT-14..16 Lệnh phát lại sự kiện tổ chức `org-republish` cho bên nhận bị lệch

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-04-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | S |
| Phụ thuộc | DV-04-03 (phát EVT-14, EVT-15, EVT-16 trạng thái đầy đủ), NEN-022 (danh mục nguồn outbox, quy ước phát lại); NEN-021 hoặc NEN-007 (lược đồ `org.bootstrap.v1`) (bổ sung) |
| Truy vết | TL-03 "Event-carried State Transfer cho dữ liệu tổ chức" → SVC-04 lệnh `org-republish` → topic khởi tạo `org.bootstrap.v1` (trạng thái hiện hành của mọi đơn vị, phân công, nhân sự của một tenant) → consumer `org_replica` (CORE-00-03), SVC-02 (DV-02-06, DV-02-07), SVC-15 → đối soát hằng đêm theo bucket băm (TL-03 chương 17, DL-12) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khi thêm một bên nhận mới hoặc phải dựng lại bản sao tổ chức bị lệch (sau sự cố, sau đối soát phát hiện bucket lệch), không thể chờ dữ liệu "tự hội tụ" vì topic `org.*` chỉ giữ 30 ngày. TL-03 giao cho SVC-04 lệnh `org-republish` đẩy trạng thái hiện hành của mọi thực thể của một tenant lên topic khởi tạo riêng `org.bootstrap.v1`; bên nhận đọc topic này rồi chuyển sang topic thường. Task này viết lệnh đó và giao thức đánh dấu đầu, cuối để bên nhận biết khi nào bản sao đã đủ. Khi xong, một bản sao `org_replica` trống dựng lại được cho tenant 100.000 tài khoản trong thời gian đo được và khớp đối soát.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Event-carried State Transfer cho dữ liệu tổ chức" (L4291–4311: upsert có điều kiện `source_version`, `cleanup.policy=compact,delete`, giữ 30 ngày, `org-republish`, `org.bootstrap.v1`), bảng đối soát bản sao tổ chức (L4654: 256 bucket theo băm, "phát lại"), danh mục EVT-14..EVT-16 (L4454–4456).
- `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-03.md` mục 6 (topic kỹ thuật `org.bootstrap.v1`).
- Thẻ `docs/tasks/DV-04-03-thay-doi-to-chuc.md`, `CORE-00-03-org-replica-consumer.md`, `DL-12-job-doi-soat.md`, `NEN-022-phat-lai-outbox-connector.md`.

## 3. Phạm vi công việc

- Viết lệnh `org-republish` trong org-service, chạy như Job Kubernetes hoặc lệnh CLI của ảnh dịch vụ (không mở endpoint người dùng; nếu cần gọi từ xa thì qua `vtctl` của INF-26): tham số `--tenant`, tuỳ chọn `--entity unit|assignment|person`, `--bucket` (một hoặc nhiều trong 256 bucket băm, để phát lại có chọn lọc sau đối soát), `--as-of` (mặc định hiện tại), `--dry-run`.
- Đọc trạng thái hiện hành theo thời điểm hiệu lực và thời điểm ghi nhận (hai trục thời gian của DV-04-01), phát mỗi thực thể một thông điệp đúng lược đồ EVT-14/15/16 (trạng thái đầy đủ, giữ `sourceVersion` thật để upsert có điều kiện của bên nhận không ghi lùi) lên `org.bootstrap.v1`, khoá `tenantId:aggregateId`.
- Giao thức đánh dấu: thông điệp đầu và cuối loạt (`republishId`, số thực thể theo loại, băm tổng theo bucket) để bên nhận biết đã nhận đủ và đối chiếu; thứ tự phát đơn vị → nhân sự → phân công để bên nhận dựng bảng bao đóng trước (suy luận, ghi vào wiki).
- Phát trực tiếp lên Kafka từ một ảnh chụp đọc nhất quán (giao dịch `REPEATABLE READ` chỉ đọc), không qua bảng outbox để không làm phình outbox; sự kiện thường phát song song vẫn đi qua outbox và bên nhận dùng `source_version` để hội tụ (suy luận, ghi lý do vào báo cáo).
- Ghi sự kiện kiểm toán `sys.audit.v1` mỗi lần chạy (người chạy, tenant, phạm vi, số thực thể) và metric thời gian chạy, thông lượng.
- Viết `ops/runbooks/org-republish.md`: khi nào dùng, chạy thử, chạy thật, xác nhận bên nhận đã chuyển sang topic thường, kiểm bằng job đối soát DL-12.

## 4. Ngoài phạm vi

- Phía bên nhận đọc `org.bootstrap.v1` rồi chuyển topic (CORE-00-03 và các consumer khác tự làm theo giao thức đánh dấu); khai báo topic `org.bootstrap.v1` dạng mã (INF-10); job đối soát (DL-12).

## 5. Tệp được phép sửa

- `backend/services/org-service/**`
- `deploy/charts/org-service/**` (chỉ mẫu Job `org-republish`)
- `ops/runbooks/org-republish.md`
- `wiki/18-su-kien-va-dong-bo-du-lieu.md` (chỉ mục ECST và phát lại)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/org-service -am verify` chạy xanh gồm Testcontainers PostgreSQL + Kafka: phát lại một tenant mẫu, một consumer thử dựng bản sao từ trống khớp hoàn toàn bản gốc theo băm bucket; phát lại một bucket chỉ phát thực thể thuộc bucket đó; chạy lại hai lần không đổi bản sao.
- Đo và ghi vào báo cáo thời gian phát lại cho tenant có 100.000 nhân sự sinh bằng bộ dữ liệu thử (mục tiêu đề xuất: dưới 30 phút, suy luận).
- `helm lint deploy/charts/org-service` không lỗi; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

# NEN-003 · TL-01/TL-03/TL-04 Wiki backend phần 2: sự kiện và đồng bộ, hạ tầng production, bảo mật phía máy chủ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-003` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-001 |
| Truy vết | ADR-03, ADR-11, ADR-12, ADR-16, ADR-17, ADR-21, ADR-23 → EVT-01..EVT-26, topic kỹ thuật `sys.*` → `<schema>.outbox_event`, `inbox_processed` → SEC, OPS theo TL-04 |
| Trạng thái | Hoàn thành (20/10/2026, đã gộp vào `main`: `wiki/18-su-kien-va-dong-bo-du-lieu.md`, `wiki/19-ha-tang-production-va-trien-khai.md`, `wiki/20-bao-mat-phia-may-chu.md`) |

## 1. Mục tiêu

Viết ba trang wiki mới cho người làm phía máy chủ và hạ tầng: trang 18 "Sự kiện và đồng bộ dữ liệu", trang 19 "Hạ tầng production và triển khai", trang 20 "Bảo mật phía máy chủ". Khi xong, agent làm bất kỳ task nào có phát hoặc nhận sự kiện, cấu hình Helm, hay kiểm quyền và mã hoá có một nơi tra nhanh: tên topic, khoá, phong bì, outbox và inbox, thứ tự xử lý, quy ước DLQ; cấu trúc cell và cụm Kubernetes; bảy lớp bảo vệ, OPA, step-up, mã hoá phong bì, che nhật ký. Mỗi trang dẫn đúng mục TL để tra sâu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 7 (EVT), 8 (cơ chế đồng bộ), 14 G3.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương "Kiến trúc tích hợp và dữ liệu luân chuyển" (L1228–1477: "Bảo đảm giao nhận: outbox và inbox", "Thứ tự và phân vùng", "Quy ước topic", "Phong bì sự kiện", "AsyncAPI"); chương "Bảo mật" (L3002–3080); chương "Quan sát và nhật ký" (L2923–3000).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` chương "Cơ chế đồng bộ dữ liệu" (L4185–4404) và "Lược đồ sự kiện" (L4406–4475, gồm bảng "Danh mục lược đồ EVT-01 đến EVT-26").
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` chương "Mô hình triển khai" (L89–193), "Thiết kế cụm Kubernetes cho một cell" (L195–570), "Cấu hình triển khai từng service" (L833–1196), "An toàn thông tin" (L2439–3252, nhất là "Quản lý bí mật và khoá: OpenBao, ESO, HSM" L2866).
- `docs/quy-dinh/BACKEND.md`, `docs/quy-dinh/HA-TANG.md`, `wiki/08-bao-mat-frontend.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/15-phan-quyen.md` (để dẫn chéo, không chép lại).

## 3. Phạm vi công việc

- Tạo `wiki/18-su-kien-va-dong-bo-du-lieu.md`: bảng 26 sự kiện (mã, topic, outbox nguồn, khoá, trường chính, phân loại) lấy nguyên từ bảng TL-03 L4439–4466; danh sách topic kỹ thuật (`sys.ledger.append.v1`, `sys.audit.v1`, `sys.evidence.object.stored.v1`, `sys.tenant.provisioned.v1`, `iam.auth.event.v1`, `period.drain.marker`, `org.bootstrap.v1`, `registry.tenant.changed.v1`); cấu hình topic (phân vùng, RF, giữ 7 ngày, nén).
- Trang 18 mô tả tám cơ chế đồng bộ của baseline mục 8 gắn với chi tiết TL-03: outbox + Debezium EventRouter, inbox `ON CONFLICT DO NOTHING` + `inbox_aggregate_version`, thứ tự xử lý (mở giao dịch, đặt tenant, chèn inbox, kiểm phiên bản, áp dụng, commit, xác nhận offset), thử lại `<topic>.retry.<consumer>` 1/5/30 phút và `<topic>.dlq.<consumer>`, ECST `org_replica`, CDC sang ClickHouse, saga.
- Trang 18 nêu phong bì sự kiện (CloudEvents nhị phân trên header Kafka, dữ liệu Avro qua Apicurio, phần mở rộng `tenantid`, `actorid`, `traceparent`, `policyversion`, `classification`, `aggregateversion`) và quy tắc tiến hoá lược đồ (chỉ thêm trường có mặc định, thay đổi phá vỡ tạo `.v2` chạy song song ít nhất một quý).
- Tạo `wiki/19-ha-tang-production-va-trien-khai.md`: ba mô hình phân phối, hai trung tâm dữ liệu, vùng điều phối toàn cục và cell, node pool, namespace theo vùng bảo mật, NetworkPolicy mặc định chặn, Istio ambient, APISIX, mặc định an toàn của chart `vt-service` (không root, FS chỉ đọc, probe, `preStop`), bảng cấu hình theo service (min/max replica, CPU, bộ nhớ) tóm tắt từ TL-04 L851–881, và GitOps (Argo CD, Rollouts canary, Kyverno).
- Tạo `wiki/20-bao-mat-phia-may-chu.md`: zero trust và mTLS, Keycloak và thời hạn token, step-up `acr`/`auth_time`, OPA cùng pod với đánh giá từng phần (ADR-21), bảy lớp bảo vệ G3, mã hoá phong bì C2 (định dạng bản mã, AAD, DEK theo tenant × tháng × mục đích, bộ đệm 15 phút), chỉ mục mù, E2EE C3 phía máy chủ chỉ lưu bản mã, che dữ liệu ba lớp trong nhật ký, nhật ký kiểm toán tách riêng, biện pháp S01–S05, và bảng kiểm khi viết mã máy chủ.
- Điểm lệch cần lưu ý ghi trên trang tương ứng, dẫn sang trang 14: DLQ `<topic>.dlq` (TL-01 L1246) so với `.dlq.<consumer>` (TL-01 L1281, TL-03); `iam.auth.event.v1` không có tiền tố `sys.`; một connector outbox mỗi CSDL (TL-03) so với mỗi schema (TL-01); tương thích `BACKWARD` (TL-01) so với `BACKWARD_TRANSITIVE` (TL-03); năm cụm PostgreSQL (TL-03) so với sáu cụm (TL-04).
- Cập nhật `wiki/README.md` mục lục cho ba trang mới.

## 4. Ngoài phạm vi

- Trang 16, 17 (kiến trúc mission-core, CSDL): NEN-002.
- Đề xuất chốt điểm lệch: NEN-004.
- Viết lược đồ Avro, AsyncAPI thật: NEN-007, NEN-008; dựng hạ tầng thật: nhóm INF.

## 5. Tệp được phép sửa

- `wiki/18-su-kien-va-dong-bo-du-lieu.md` (mới)
- `wiki/19-ha-tang-production-va-trien-khai.md` (mới)
- `wiki/20-bao-mat-phia-may-chu.md` (mới)
- `wiki/README.md` (chỉ mục lục)

## 6. Tiêu chí hoàn thành

- Bảng 26 sự kiện trên trang 18 khớp từng ô với TL-03 L4439–4466 (topic, khoá, phân loại); kiểm bằng script so khớp hoặc đối chiếu tay, ghi cách kiểm vào báo cáo.
- Mọi mã SEC, OPS, PERF, DR, ADR, EVT dẫn trên ba trang tra được trong TL; không đặt mã mới.
- Không có địa chỉ máy chủ, tài khoản, mật khẩu, khoá thật trong ba trang (lệnh `grep` như NEN-002).
- Mỗi trang có dòng "Nguồn" và mục "Liên quan"; liên kết sang trang 08, 12, 15, 16, 17 mở được.
- Báo cáo cuối task theo AGENTS.md mục 6; mục "Cập nhật wiki" liệt kê ba trang và README.

## 7. Báo cáo

Theo AGENTS.md mục 6.

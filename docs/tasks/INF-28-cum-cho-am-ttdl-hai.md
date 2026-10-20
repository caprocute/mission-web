# INF-28 · NFR-05/DR Cụm chờ ấm ở trung tâm dữ liệu thứ hai: CNPG replica cluster, MirrorMaker 2, Ceph multisite

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-28` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 (quy mô quốc gia, DR) |
| Luồng đề xuất | B8 |
| Cỡ việc | L (gợi ý tách: (a) cụm Kubernetes và node pool phía Nam, ApplicationSet `values-sn`; (b) lớp dữ liệu: CNPG replica cluster sáu cụm, nối cấu hình MirrorMaker 2 và Ceph multisite của INF-18; (c) giám sát độ trễ sao chép và kiểm tra sẵn sàng hằng ngày) |
| Phụ thuộc | INF-11 (CNPG sáu cụm), INF-18 (MirrorMaker 2, Ceph multisite, giữ outbox 72 giờ), INF-20 (GeoDNS, `dns-sync`, cụm quản trị); INF-04 (ApplicationSet theo cell), INF-08, INF-09 (cụm cell), INF-17 (Barman Cloud, kho sao lưu) (bổ sung) |
| Truy vết | NFR-05 (RPO ≤ 15 phút, RTO ≤ 4 giờ khi mất TTDL) → TL-04 "Topology hai trung tâm dữ liệu" (L133–146), năng lực phía Nam (L658–660), "PostgreSQL: CloudNativePG và Barman Cloud" (L2003–2112, replica cluster L2081–2083), "Kafka: MirrorMaker 2" (L2114–2174), "Ceph: multisite RGW" (L2176–2215), "Kịch bản DR" (L2336) → DR-10, DR-16, OPS-18 → cụm `pg-*-sn`, `mm2-hl-to-sn`, zone RGW thứ hai |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Ở trạng thái bình thường mọi cell chạy tại Hoà Lạc, còn TTDL phía Nam giữ bản sao ấm của từng cell: lớp dữ liệu chạy đủ kích thước và nhận dữ liệu liên tục (CNPG replica cluster nhận WAL, MirrorMaker 2 nhân bản topic, Ceph RGW multisite nhân bản đối tượng), lớp ứng dụng chạy tối thiểu để sẵn sàng tăng lên khi chuyển vùng. Task này dựng toàn bộ bản sao ấm đó dạng mã trên cụm phía Nam của mỗi cell và chứng minh nó luôn sẵn sàng: độ trễ sao chép trong ngưỡng, ứng dụng khởi động được ở phía Nam ở chế độ chờ. Khi xong, QA-15 chỉ còn việc diễn tập DR-10 trên một hệ chờ ấm đã có.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`: "Topology hai trung tâm dữ liệu" (L133–146: sao chép bất đồng bộ sang phía Nam, vai trò primary khai báo trong `cells/<cell>/cell.yaml`, đề xuất primary CELL-HCM phía Nam), bảng năng lực (L658–660: giữ chỗ khoảng 2.000 vCPU, AI tạm ngừng khi chuyển vùng), "PostgreSQL: CloudNativePG và Barman Cloud" (L2003–2112, values `values-cell-hcm-sn.yaml` L2083), "Kafka: MirrorMaker 2" (L2114–2174), "Ceph: multisite RGW và Object Lock" (L2176–2215), "Giữ outbox 72 giờ" (L2332), "Kịch bản DR" (L2336).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` phần định cỡ TTDL phía Nam (bảng tổng hợp theo bậc).
- `docs/quy-dinh/HA-TANG.md`; thẻ `docs/tasks/INF-11-csdl-va-kho-du-lieu.md`, `INF-17-sao-luu-csdl.md`, `INF-18-sao-luu-du-lieu-khac.md`, `INF-20-vung-dieu-phoi-toan-cuc.md`, `QA-15-dr-chuyen-vung.md`.

## 3. Phạm vi công việc

- (a) Khai báo cụm phía Nam cho mỗi cell trong `deploy/clusters/` (Cluster API, ba vùng sự cố `sn-a`, `sn-b`, `sn-c`), node pool dữ liệu đủ kích thước và node pool ứng dụng tối thiểu; `deploy/cells/<cell>/values-sn.yaml` đặt mỗi Deployment 1 replica, KEDA và HPA giữ `minReplicaCount` thấp, ai-gateway và vLLM tắt; ApplicationSet của INF-04 sinh Application phía Nam từ cùng chart.
- (b) CNPG replica cluster cho sáu cụm `pg-core`, `pg-flowable`, `pg-platform`, `pg-iam`, `pg-ledger`, `pg-secure` theo cơ chế distributed topology (`replica.enabled`, `replica.primary`, `externalClusters` từ luồng WAL và kho Barman Cloud), cùng một manifest với tham số khác nhau giữa hai TTDL như TL-04 mô tả.
- (b) Nối cấu hình MirrorMaker 2 (`mm2-hl-to-sn`, `IdentityReplicationPolicy`, loại `<topic>.replay.*` khỏi nhân bản) và zone RGW thứ hai mà INF-18 đã viết vào values phía Nam của từng cell; xác nhận bucket Object Lock tồn tại đủ ở zone thứ hai.
- (c) Giám sát sẵn sàng: cảnh báo độ trễ WAL 5 phút (cảnh báo) và 10 phút (nghiêm trọng), độ trễ MirrorMaker 2, độ trễ đồng bộ RGW; CronJob kiểm tra sẵn sàng hằng ngày (replica nhận WAL mới, topic có thông điệp mới, đối tượng mẫu đã sang) ghi metric cho OPS-18; dashboard "Bản sao ấm" cho từng cell.
- Ghi rõ trong `cell.yaml` TTDL primary của từng cell (mặc định Hoà Lạc), để việc đổi vai trò khi chuyển vùng là một thay đổi Git (thực thi ở QA-15 và runbook DR-10).
- Điểm lệch cần lưu ý: QA-15 hiện cũng ghi "viết values replica cluster CNPG phía Nam"; phân định: INF-28 dựng và duy trì bản sao ấm (gồm replica cluster), QA-15 giữ diễn tập DR-10..DR-16 và runbook; ghi vào báo cáo để điều chỉnh thẻ QA-15.

## 4. Ngoài phạm vi

- Mua sắm và lắp đặt TTDL phía Nam, giữ chỗ năng lực Viettel Cloud (hạ tầng thật); diễn tập chuyển vùng và quay về (QA-15); GeoDNS, `dns-sync` (INF-20); cấu hình MirrorMaker 2 và multisite gốc (INF-18).
- Dựng và kiểm thử trên TTDL thứ hai thật khi có; khi đó chỉ đổi values theo môi trường.

## 5. Tệp được phép sửa

- `deploy/clusters/**` (cụm phía Nam), `deploy/cells/*/values-sn.yaml`, `deploy/cells/*/cell.yaml` (chỉ trường TTDL primary)
- `deploy/charts/pg-cell/**` (khối `replica`, `externalClusters`), `deploy/charts/kafka-cell/**` và `deploy/charts/rgw-cell/**` (chỉ values phía Nam)
- `deploy/charts/standby-check/**` (mới, CronJob kiểm sẵn sàng, tên suy luận), `deploy/environments/kind/**` (hai cụm mô phỏng)
- `ops/runbooks/ban-sao-am.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template`, `kubeconform -strict` sạch cho mọi values phía Nam; `kube-linter` không lỗi mới.
- Trên hai cụm kind mô phỏng: một replica CNPG nhận WAL từ cụm "Hoà Lạc" với độ trễ đo được dưới 1 phút; MirrorMaker 2 sao một topic thử; CronJob kiểm sẵn sàng báo xanh, và báo đỏ khi ngắt đường sao chép.
- Địa chỉ, kích thước và tham số riêng của phía Nam nằm trong values theo môi trường, nên áp lên TTDL thật chỉ đổi cấu hình; runbook có bước kiểm chứng và quay lui; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

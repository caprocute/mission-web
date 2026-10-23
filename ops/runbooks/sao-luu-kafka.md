# Sổ tay vận hành: Nhân bản Kafka bằng MirrorMaker 2 và phát lại outbox sau chuyển vùng

> **Mã truy vết:** INF-18 · TL-04 chương 8 "Kafka: MirrorMaker 2", "Bảng lịch sao lưu" (dòng Kafka), "Giữ outbox 72 giờ để phát lại sau chuyển vùng" · DR-10 bước 8 · TL-03 "Transactional Outbox và Debezium", mục inbox.
> **Thành phần:** khối `mirrorMaker2` của chart `deploy/charts/vt-platform/ha-tang/kafka` (template `mirrormaker2.yaml`); công cụ `services-go/outbox-replay` (cờ `--from-safe-point`); sổ tay `phat-lai-outbox.md`.

## 1. Mô hình

Kafka không được sao lưu mà được nhân bản một chiều từ trung tâm dữ liệu đang hoạt động (Hòa Lạc, bí danh `hl`) sang phía Nam (bí danh `sn`). MirrorMaker 2 chạy ở cụm đích (`mirrorMaker2.vaiTro: dich`), 3 replica, kéo dữ liệu qua mTLS bằng người dùng chỉ đọc `mm2-hl-user` mà cụm nguồn tạo (`vaiTro: nguon`); toàn bộ ghi (topic nhân bản, topic nội bộ `mm2-hl-to-sn-config/-status/-offset`, `offset-syncs`, đặt offset nhóm tiêu thụ) diễn ra trên cụm đích, nên MM2 không cần quyền ghi nào ở cụm đang phục vụ người dùng. `IdentityReplicationPolicy` giữ nguyên tên topic, nhờ vậy sau chuyển vùng các service ở phía Nam đọc đúng topic như cũ. Topic `retry`, `replay` của từng consumer không được nhân bản vì chúng chỉ có nghĩa trong cụm đã sinh ra chúng. Checkpoint connector đồng bộ offset nhóm tiêu thụ mỗi 30 giây, nên consumer ở phía Nam tiếp tục gần đúng chỗ đã đọc ở Hòa Lạc; phần đọc lại do khoảng 30 giây này được inbox (`processed_event`, khoá `eventId`) loại bỏ.

Phiên bản MM2 lấy theo phiên bản Kafka của cụm (`kafkaVersion`, ảnh Strimzi đã ghim), không theo con số 4.0.0 trong đoạn mẫu của TL-04 (lệch ghi ở wiki 14).

Kiểm tra trạng thái ở cụm đích: `kubectl -n data-kafka get kafkamirrormaker2 mm2-hl-to-sn -o jsonpath='{.status.conditions}'` phải có `Ready=True`, và `.status.connectors` liệt kê ba connector (source, checkpoint, heartbeat) ở trạng thái `RUNNING`. Topic `heartbeats` ở phía Nam nhận bản ghi mới mỗi giây là bằng chứng luồng nhân bản còn sống.

## 2. Chuyển vùng (DR-10) và đảo chiều

1. Khi Hòa Lạc mất, dừng MM2 ở phía Nam bằng cách đặt `mirrorMaker2.vaiTro: ""` trong values của cell phía Nam rồi để Argo CD đồng bộ. MM2 không được chạy tiếp vì cụm nguồn không còn, và nếu Hòa Lạc sống lại giữa chừng thì MM2 sẽ đè dữ liệu cũ lên topic đang được phía Nam ghi mới.
2. Nâng cấp cụm PostgreSQL phía Nam (QA-15, VH-02), chuyển lưu lượng, rồi phát lại outbox từ điểm an toàn theo mục 3.
3. Khi Hòa Lạc dựng lại xong và trở thành bản dự phòng, đảo chiều nhân bản: ở cell phía Nam đặt `vaiTro: nguon` (tạo người dùng chỉ đọc cho MM2 phía kia); ở cell Hòa Lạc đặt `vaiTro: dich`, `ten: mm2-sn-to-hl`, `nguon.alias: sn`, `nguon.bootstrapServers` trỏ broker phía Nam, `dich.alias: hl`, và nạp CA cùng chứng thư khách của cụm phía Nam vào OpenBao `kv/cell-<mã>/kafka/mm2-nguon`. Không xoá các topic cũ ở Hòa Lạc trước khi MM2 mới chạy ổn định; MM2 sẽ bổ sung phần còn thiếu.
4. Chuyển về Hòa Lạc theo kế hoạch là quy trình ngược lại; giữ cùng tên CR theo chiều (`mm2-<nguồn>-to-<đích>`) để topic nội bộ không lẫn giữa hai chiều.

## 3. Phát lại outbox từ điểm an toàn

Slot logic của Debezium không đi theo sang trung tâm dữ liệu khác, và Kafka phía Nam có thể thiếu vài giây tới vài phút sự kiện cuối cùng. Vì dòng outbox đã đọc vẫn được giữ 72 giờ (pg_partman `retention = '3 days'` trong migration chung `V0_20__outbox_event.sql`, cơ chế của TL-03 được chọn thay cho mô tả "DROP PARTITION" của TL-04 vì cùng thời hạn), sự kiện có thể được phát lại từ CSDL phía Nam.

Điểm an toàn bằng thời điểm của giao dịch cuối cùng đã áp dụng ở phía Nam (`pg_last_xact_replay_timestamp()`, vẫn giữ giá trị sau khi cụm được nâng cấp) trừ 15 phút. `outbox-replay` tính sẵn mốc này:

```bash
outbox-replay --contract contracts/events/outbox-sources.yaml --schema task --tenant <uuid> \
  --from-safe-point --dry-run                     # in "ĐIỂM AN TOÀN: --from=…" ra stderr và số dòng sẽ phát
outbox-replay … --from-safe-point --confirm --actor <người trực> --reason DR-10-buoc-8
```

`--from-safe-point` không dùng cùng `--from`; cụm chưa từng ở chế độ khôi phục (không phải bản vừa nâng cấp) làm công cụ dừng với thông báo yêu cầu `--from`. `--safe-point-margin` đổi biên 15 phút khi cần. Phát lại chồng lên phần MM2 đã chép là chủ ý: consumer khử trùng theo `eventId`, nên mỗi sự kiện chỉ được xử lý một lần (kiểm thử `TestSafePointPhatLaiKhongTrungOConsumerCoInbox`). Các bước còn lại (danh mục nguồn, chạy theo từng schema và tenant, kiểm toán) theo `phat-lai-outbox.md`. Nếu sự cố được phát hiện muộn hơn 72 giờ, phân vùng outbox đã bị dọn và công cụ cảnh báo; khi đó chỉ còn cách đối soát nghiệp vụ theo các sổ tay `doi-soat-*`.

## 4. Giới hạn hiện tại

Bảng lịch sao lưu yêu cầu đối soát offset hằng ngày giữa hai cụm. Chart Kafka chưa bật số liệu của MM2 và broker (JMX exporter, kafka-exporter), nên việc đối soát tự động chưa có; trong lúc chờ, người trực kiểm tra mục 1 và so độ trễ bằng `kafka-consumer-groups.sh --describe` trên nhóm quan trọng ở cả hai phía sau mỗi lần thay đổi lớn.

# Sổ tay vận hành: Snapshot OpenSearch, sao lưu ClickHouse và quyết định không sao lưu Valkey

> **Mã truy vết:** INF-18 · TL-04 chương 8 "OpenSearch, ClickHouse, Valkey", "Bảng lịch sao lưu" · mức suy giảm 2 (TL-04 mục 6.9).
> **Thành phần:** `snapshot` của chart `vt-platform/ha-tang/opensearch` (repo `bk-sn`, `OpenSearchSnapshotPolicy daily-to-sn`); `saoLuu` và người dùng `sao_luu` của chart `vt-platform/ha-tang/clickhouse` (tập hợp có tên `bk_sn`); CronJob `khoi-phuc-thu-opensearch`, `sao-luu-clickhouse`, `khoi-phuc-thu-clickhouse` của chart `backup-cell` (namespace `sao-luu`); bucket `bk-cell-<mã>-os` (Object Lock 14 ngày), `bk-cell-<mã>-ch` (35 ngày).

## 1. Vì sao sao lưu kho dẫn xuất

OpenSearch và ClickHouse dựng lại được từ CSDL nguồn và Kafka, nhưng dựng lại chỉ mục OpenSearch của một cell lớn mất 6–8 giờ, vượt RTO 4 giờ; khôi phục snapshot rồi bù phần chênh từ Kafka mất khoảng 1 giờ. Trong lúc chưa khôi phục xong, tìm kiếm toàn văn hạ cấp (mức suy giảm 2), nghiệp vụ chính vẫn chạy vì đọc CSDL.

## 2. OpenSearch

Plugin `repository-s3` được operator cài lúc khởi động; khoá S3 nằm trong keystore (Secret `bk-sn-s3-os` từ `kv/cell-<mã>/backup/os`), điểm cuối trong `opensearch.yml`. Chính sách `daily-to-sn` tạo snapshot 02:00 và dọn 04:00 giờ Việt Nam, giữ tối thiểu 7, tối đa 14 bản, chỉ mục `okr-*,mission-*,evidence-*,org-*`, không kèm trạng thái toàn cụm. Khoá S3 chỉ được tạo delete marker: khi OpenSearch xoá bản hết hạn, phiên bản đã khoá vẫn còn trên kho tới khi hết 14 ngày rồi bị vòng đời dọn; bucket không bao giờ hết hạn tệp theo tuổi vì bản gia tăng dùng lại tệp của bản cũ.

Kiểm tra: `GET _plugins/_sm/policies/daily-to-sn/_explain` (trạng thái lần tạo, lần xoá gần nhất), `GET _cat/snapshots/bk-sn?v`.

Khôi phục thử hằng tuần (thứ bảy 06:00): job `khoi-phuc-thu-opensearch` lấy snapshot `SUCCESS` mới nhất, khôi phục một nhóm chỉ mục luân phiên theo tuần vào `khoi-phuc-thu-<tên>` không bản sao, so số tài liệu với chỉ mục gốc rồi xoá chỉ mục tạm. Mỗi tuần một nhóm để không nhân đôi dung lượng cụm. Khi `OpenSearchRestoreCheckStale` báo, xem nhật ký job: "chưa có snapshot SUCCESS" nghĩa là chính sách không tạo được (xem `_explain`, quyền S3, mạng ra kho sao lưu); "không trọn" nghĩa là shard khôi phục hỏng; "có N tài liệu sau khôi phục" nghĩa là snapshot thiếu dữ liệu.

Khôi phục thật sau sự cố: đóng hoặc xoá chỉ mục hỏng, `POST _snapshot/bk-sn/<snapshot>/_restore` với danh sách chỉ mục, rồi cho search-indexer bù từ Kafka kể từ thời điểm của snapshot.

## 3. ClickHouse: sao lưu hằng ngày

01:30 hằng ngày, `sao-luu-clickhouse` gửi lệnh qua HTTPS bằng người dùng `sao_luu` (chỉ có quyền `BACKUP` trên `analytics`): chủ nhật, hoặc khi không có bản đầy đủ hoàn tất trong 6 ngày, là `BACKUP DATABASE analytics ON CLUSTER 'analytics' TO S3(bk_sn, 'full-<ngày>')`; ngày khác là `incr-<ngày>` với `base_backup` là bản đầy đủ gần nhất. Bản chỉ được coi là hoàn tất khi có tệp `.backup`. Khoá S3 không xuất hiện trong câu lệnh: tập hợp có tên `bk_sn` lấy khoá từ biến môi trường của pod ClickHouse (Secret `bk-sn-s3-ch`). Giữ 5 tuần bằng Object Lock 35 ngày và vòng đời dọn sau 36 ngày.

`ClickHouseBackupStale` báo khi 26 giờ không có bản thành công. Xem `SELECT id, name, status, error, start_time, end_time FROM system.backups ORDER BY start_time DESC LIMIT 5` và nhật ký job; lỗi thường gặp là thiếu quyền `S3` hoặc `NAMED COLLECTION`, chứng thư của kho sao lưu không được tin cậy, hoặc mạng ra kho sao lưu (`saoLuu.egressTo`).

## 4. ClickHouse: khôi phục thử hằng tháng

Ngày 1 hằng tháng, `khoi-phuc-thu-clickhouse` khôi phục bản mới nhất vào `analytics_khoi_phuc_thu` (`RESTORE … AS …`), so số dòng từng bảng MergeTree với CSDL gốc rồi luôn xoá CSDL tạm. Giả định (suy luận): bảng Replicated dùng đường dẫn Keeper chứa `{uuid}` nên bản khôi phục dưới tên khác không đụng bản sao của bảng gốc; nếu đường dẫn chứa tên CSDL cố định thì phải khôi phục thử ở cụm staging thay vì cụm thật. Khôi phục thật: `RESTORE DATABASE analytics FROM S3(bk_sn, '<tên bản>')` sau khi đổi tên CSDL hỏng, rồi bù từ Kafka.

## 5. Valkey không sao lưu

Valkey chỉ chứa bộ nhớ đệm, khoá chống gửi trùng và bộ đếm giới hạn tốc độ. Sau khi mất Valkey, bộ nhớ đệm tự nạp lại từ CSDL, khoá chống gửi trùng hết hiệu lực trong thời gian ngắn (chặn trùng ở tầng CSDL bằng `Idempotency-Key` và inbox vẫn còn), bộ đếm giới hạn tốc độ bắt đầu lại từ 0. TL-04 chấp nhận hệ quả này nên không có lịch, bucket hay cảnh báo sao lưu cho Valkey; không bật RDB hay AOF lên kho sao lưu.

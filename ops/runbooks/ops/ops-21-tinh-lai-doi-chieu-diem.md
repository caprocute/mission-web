# OPS-21 · Tính lại và đối chiếu điểm hằng đêm

> **Truy vết:** TL-04 mục 11.9 (00:30, saga SVC-06, `scoring.score_job` loại `KIEM_TRA_TAI_LAP`, hồ sơ khác `result_hash` đánh dấu `LOI`) · tiêu chí I6 · NFR-10 · DL-12 đối soát R3, R4.
> **Chu kỳ:** ngày · **Chịu trách nhiệm:** chủ service scoring · **Tự động hoá:** saga tính điểm.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Có hồ sơ bị đánh dấu `LOI` sau lượt tính lại, cảnh báo `MissionDoiSoatLechChuaXuLy` với `reconcile` thuộc nhóm điểm, hoặc cảnh báo ảnh chụp điểm lệch (`MissionDoiSoatR3ChanKySo`).

## Tác động

`LOI` cho biết điểm tính dần trong ngày khác điểm tính lại từ đầu với cùng đầu vào: thường do sự kiện bị bỏ sót hoặc xử lý không idempotent. Khi lệch ở ảnh chụp điểm, ký số các hồ sơ liên quan bị chặn cho tới khi đối soát hết lệch. Mức mặc định SEV-3; SEV-2 trong tuần khoá kỳ.

Hiện thực trong repo: lượt tính lại toàn bộ kỳ đang mở lúc 00:30 chưa có lịch riêng (suy luận từ `deploy/charts/scoring-service` và `../doi-soat-tai-lap-diem.md`). Đang chạy hai đối soát gần nhất: R3 đối soát ảnh chụp điểm với kết quả tính mỗi 30 phút (`../doi-soat-diem-anh-chup.md`) và R4 lấy mẫu tái lập điểm đã chốt hằng tuần bằng tác vụ `KIEM_TRA_TAI_LAP` (`../doi-soat-tai-lap-diem.md`). Sổ tay này xử lý `LOI` của cả hai và áp nguyên cách xử lý khi lượt 00:30 được bật.

## Kiểm tra nhanh

```sql
-- Chạy với ngữ cảnh tenant (SET app.tenant_id) trong giao dịch, tài khoản chỉ đọc.
SELECT job, started_at, status, detail->>'mismatched' AS lech, detail->>'reason' AS ly_do
FROM scoring.reconcile_run ORDER BY started_at DESC LIMIT 10;
```

```promql
sum by (reconcile) (increase(mission_reconcile_runs_total{status="LOI"}[2h]))
```

## Các bước

1. **Lệch ở ảnh chụp điểm (R3):** làm theo `../doi-soat-diem-anh-chup.md`; sau khi sửa, chạy lại đối soát để mở chặn ký số.
2. **Lệch khi tái lập (R4, hoặc lượt 00:30):** làm theo `../doi-soat-tai-lap-diem.md` mục 2: `engineFrom` khác `engineTo` là thay đổi hành vi không chủ đích của bộ máy tính, chặn triển khai bản mới; cùng phiên bản mà khác băm là tính không tất định hoặc dữ liệu bị sửa, điều tra như sự cố an toàn dữ liệu.
3. **Thiếu sự kiện:** kiểm DLQ của consumer tính điểm (OPS-24) và outbox của nguồn (`../doi-soat-outbox-kafka.md`); phát lại rồi chờ lượt đối soát kế tiếp.
4. Không sửa tay `scoring.score_result`; điểm đúng phải đến từ lần tính lại.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Phát lại DLQ của consumer tính điểm | `vtctl dlq replay --topic <topic> --consumer scoring-service ...` | xem OPS-24 |
| Phát lại outbox nguồn | `vtctl outbox replay --schema <schema> --tenant <id> --from --to` | `../phat-lai-outbox.md` |

## Kiểm sau khi làm

Lượt đối soát kế tiếp của tenant báo `KHOP`; cảnh báo đối soát tự đóng; ký số không còn bị chặn.

## Quay lui

Không áp dụng: đối soát chỉ đọc; phát lại sự kiện idempotent ở consumer.

## Leo thang

Cùng phiên bản mà khác băm, hoặc lệch ở tenant đang khoá kỳ: SEV-2, gọi chủ service scoring và SOC.

## Liên quan

`../doi-soat-diem-anh-chup.md`, `../doi-soat-tai-lap-diem.md`, [scoring-lag](../canh-bao/scoring-lag.md), [doi-soat](../canh-bao/doi-soat.md).

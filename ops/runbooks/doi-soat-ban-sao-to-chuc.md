# Sổ tay vận hành: Đối soát bản sao tổ chức (R1)

> **Mã truy vết:** DL-12 · TL-03 "Đối soát và kiểm tra nhất quán" (dòng "Bản sao tổ chức") · NFR-10 · SVC-04 → SVC-05 `org_replica` · topic `org.bootstrap.v1`.
> **Thành phần:** CronJob `<org-service>-doi-soat-r1-<n>` (chart `org-service`, `templates/reconcile-cronjob.yaml`); consumer `org-replica.reconcile` và job `OrgReplicaReconcileJob` trong mission-core; bảng `org_replica.reconcile_run`.

## 1. Cách job chạy

Hằng đêm lúc 01:00 giờ Việt Nam, CronJob của org-service chạy lệnh `org-republish --hashes-only` cho từng tenant của cell. Lệnh đọc ảnh chụp nguồn trong một giao dịch `REPEATABLE READ`, tính số thực thể theo loại và băm tổng hợp của 256 bucket, rồi chỉ phát hai thông điệp đánh dấu `BAT_DAU` và `KET_THUC` lên `org.bootstrap.v1`; không phát thực thể nào. Cách tính băm ở wiki trang 18 (mục ECST) và trang 14 (mục DL-12).

Ở mission-core, consumer `org-replica.reconcile` nhận `KET_THUC` và ghi một dòng `org_replica.reconcile_run` trạng thái `DANG_CHAY`, cột `detail` giữ băm mong đợi. Mười lăm phút sau (thời gian lắng `mission.reconcile.org-replica.settle`), `OrgReplicaReconcileJob` tính lại băm từ `org_unit_v`, `person_v`, `assignment_v` theo đúng công thức, so từng bucket rồi kết thúc lần chạy:

| Trạng thái | Ý nghĩa |
|---|---|
| `KHOP` | Mọi bucket khớp. |
| `LOI` | Có bucket lệch; `buckets_mismatch` là số bucket, `detail.mismatched` là danh sách, `detail.replay` là lệnh phát lại gợi ý. |
| `DA_SUA` | Một lần phát lại có thực thể khớp ngay sau một lần `LOI`; `rows_repaired` là số thực thể đã phát. |

Mỗi lần kết thúc tăng `mission_reconcile_runs_total{reconcile="R1_BAN_SAO_TO_CHUC",status=...}` và `mission_reconcile_mismatches_total`. Cảnh báo `MissionDoiSoatLechChuaXuLy` bật khi có `LOI` trong hai giờ; `MissionDoiSoatR1NgungChay` bật khi 30 giờ không có lần nào kết thúc.

## 2. Xử lý khi có `LOI`

1. Xem lần chạy lệch (kết nối bằng tài khoản đọc của `org_replica`, đặt ngữ cảnh tenant):

   ```sql
   SET app.tenant_id = '<uuid-tenant>';
   SELECT id, started_at, entity_type, buckets_mismatch, detail->'mismatched', detail->>'replay'
     FROM org_replica.reconcile_run
    WHERE job = 'R1_BAN_SAO_TO_CHUC' AND status = 'LOI'
    ORDER BY started_at DESC LIMIT 5;
   ```

2. Kiểm tra độ trễ consumer của bản sao trên các topic `org.*.changed.v1`. Nếu consumer đang tụt xa, lệch có thể chỉ do chưa áp kịp: chờ độ trễ về 0 rồi để lần đối soát đêm sau xác nhận.
3. Nếu độ trễ bằng 0, phát lại đúng các bucket lệch theo `detail.replay`, bằng Job phát lại của org-service (sổ tay `org-republish.md`, mục 4), ví dụ `--set 'republish.buckets={17,203}' --set republish.dryRun=false`.
4. Lần phát lại thật cũng kết thúc bằng `KET_THUC`, nên mission-core tự đối soát lại các bucket đó sau thời gian lắng. Kết quả mong đợi là một dòng `DA_SUA`.
5. Nếu cùng bucket lệch lặp lại sau khi phát lại, lỗi nằm ở phía ghi bản sao (ví dụ dòng có `source_version` lớn hơn nguồn do sửa trực tiếp CSDL). Báo luồng phụ trách mission-core kèm mã lần chạy; không xoá dòng bản sao bằng tay.

## 3. Ghi chú

- Lệnh `--hashes-only` chỉ đọc nguồn và ghi một sự kiện kiểm toán `PHAT_LAI_TO_CHUC` như mọi lần chạy `org-republish`.
- Đối soát so cả các dòng lịch sử của đơn vị và phân công đang có trong bản sao. Một dòng nguồn đã bị đính chính mất khỏi ảnh chụp mà bản sao vẫn giữ cũng làm bucket lệch; phát lại không xoá dòng thừa, nên trường hợp này cần luồng mission-core xử lý (suy luận, wiki trang 14).
- Lệch ngay sau thay đổi tổ chức lớn có thể chỉ là chênh thời điểm giữa ảnh chụp nguồn và lúc so; lần đêm sau sẽ khớp nếu không có lỗi thật.

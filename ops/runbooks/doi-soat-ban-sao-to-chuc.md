# Sổ tay vận hành: Đối soát bản sao tổ chức (R1)

> **Mã truy vết:** DL-12 · DV-06-10 · TL-03 "Đối soát và kiểm tra nhất quán" (dòng "Bản sao tổ chức") · NFR-10 · SVC-04 → SVC-05, SVC-06 `org_replica` · topic `org.bootstrap.v1`.
> **Thành phần:** CronJob `<org-service>-doi-soat-r1-<n>` (chart `org-service`, `templates/reconcile-cronjob.yaml`); bộ so dùng chung `OrgReplicaReconciler` của thư viện `vt-reconcile`; consumer `org-replica.reconcile` và job `OrgReplicaReconcileJob` trong mission-core; consumer `workflow.org-replica.reconcile` và job `OrgReplicaReconcileJob` trong workflow-service (mục 3); bảng `org_replica.reconcile_run` của từng dịch vụ.

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

## 3. Bản sao của workflow-service

workflow-service (SVC-06) giữ bản sao `org_replica` riêng trong CSDL `flowable` để phân giải cấp duyệt (DV-06-10). Bản sao này được đối soát bằng cùng bộ so `OrgReplicaReconciler` của `vt-reconcile`, cùng mã job `R1_BAN_SAO_TO_CHUC`, cùng thông điệp `KET_THUC` của CronJob org-service; workflow-service nhận qua nhóm consumer riêng `workflow.org-replica.reconcile` nên mỗi dịch vụ đều nhận đủ thông điệp. Kết quả ghi `org_replica.reconcile_run` của CSDL `flowable`, không lẫn với bảng của mission-core.

Khác mission-core ở hai điểm:

- workflow-service không giữ danh bạ tenant. Job lấy danh sách tenant có lần chạy chờ so qua hàm `org_replica.fn_reconcile_pending_tenants` (V14, chạy bằng chủ sở hữu bảng, chỉ trả mã tenant). Nếu job không đọc được danh sách, nhật ký có dòng "Không đọc được danh sách tenant chờ đối soát R1".
- Cấu hình bằng biến môi trường `WF_RECONCILE_ORG_REPLICA_ENABLED`, `WF_RECONCILE_ORG_REPLICA_SETTLE` (mặc định 15 phút), `WF_RECONCILE_ORG_REPLICA_INTERVAL` (mặc định 10 phút).

Cảnh báo nằm ở chart `workflow-service` (`templates/reconcile-alerts.yaml`, tắt bằng `reconcile.alerts.enabled=false`), mọi biểu thức lọc `application="workflow-service"`:

| Cảnh báo | Điều kiện | Ngưỡng |
|---|---|---|
| `WorkflowBanSaoToChucTreCao` | `org_replica_lag_ms` của một tenant và loại thực thể vượt ngưỡng trong 10 phút | 5 000 ms: độ trễ mục tiêu p99 của cơ chế chuyển trạng thái qua sự kiện cho dữ liệu tổ chức (TL-03 bảng "Cơ chế đồng bộ dữ liệu", mục 10.4); giá trị `reconcile.alerts.lagTargetMs` |
| `WorkflowDoiSoatR1Lech` | Có lần chạy `LOI` trong 2 giờ | như `MissionDoiSoatLechChuaXuLy` của mission-core |
| `WorkflowDoiSoatR1DaTuSua` | Có thực thể được xác nhận đã phát lại trong 2 giờ (mức thông tin) | như `MissionDoiSoatDaTuSua` |
| `WorkflowDoiSoatR1NgungChay` | 30 giờ không có lần nào kết thúc | như `MissionDoiSoatR1NgungChay` |

Ngưỡng độ trễ là mục tiêu thiết kế của TL-03, chưa hiệu chỉnh theo số đo tải thật (YC-PT-19); đổi theo cell khi có số đo.

Xử lý khi có `LOI`: làm như mục 2, nhưng truy vấn trên CSDL `flowable` của workflow-service (tài khoản đọc `ro_flowable`, đặt `app.tenant_id`), độ trễ consumer xem ở các nhóm `workflow.org-replica.*`. Phát lại qua `org.bootstrap.v1` đến cả mission-core và workflow-service, nên phát lại một bucket cho bản sao này cũng làm mission-core đối soát lại bucket đó (vô hại). Khi `WorkflowBanSaoToChucTreCao` bật, kiểm `org_replica.replica_checkpoint` của tenant trong nhãn cảnh báo, độ trễ consumer và thư chết `org.*.changed.v1.dlq.workflow.org-replica.*` trước khi phát lại.

## 4. Ghi chú

- Lệnh `--hashes-only` chỉ đọc nguồn và ghi một sự kiện kiểm toán `PHAT_LAI_TO_CHUC` như mọi lần chạy `org-republish`.
- Đối soát so cả các dòng lịch sử của đơn vị và phân công đang có trong bản sao. Một dòng nguồn đã bị đính chính mất khỏi ảnh chụp mà bản sao vẫn giữ cũng làm bucket lệch; phát lại không xoá dòng thừa, nên trường hợp này cần luồng mission-core xử lý (suy luận, wiki trang 14).
- Lệch ngay sau thay đổi tổ chức lớn có thể chỉ là chênh thời điểm giữa ảnh chụp nguồn và lúc so; lần đêm sau sẽ khớp nếu không có lỗi thật.

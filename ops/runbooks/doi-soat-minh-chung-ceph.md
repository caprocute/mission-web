# Sổ tay vận hành: Đối soát tệp minh chứng ↔ Ceph (R6)

> **Mã truy vết:** DL-12 · TL-03 "Đối soát và kiểm tra nhất quán" (dòng "Minh chứng và tệp") · ADR-13 · NFR-10 · SVC-08 · bảng `evidence.object_version`, `evidence.reconcile_run`; bucket `ev-<cell>-<tenant>` có Object Lock.
> **Thành phần:** `EvidenceObjectReconciler`, `EvidenceObjectReconcileJob` trong evidence-service (gói `evidence.infrastructure.reconcile`); khôi phục theo `ceph-multisite.md`.

## 1. Cách job chạy

Hằng ngày (03:30, `mission.reconcile.evidence.daily-cron`), job lấy mẫu 0,1% (`rate`, tối đa `max-sample` 5.000) phiên bản tệp của mỗi tenant, mẫu tất định theo ngày; ngày đầu mỗi quý (01:00) quét toàn bộ. Với mỗi phiên bản, job đọc đúng `ceph_version_id` trên RGW theo luồng, tính SHA-256 và kích thước rồi so với `sha256`, `size_bytes`. Mỗi lần chạy một dòng `evidence.reconcile_run` (`job = 'R6_MINH_CHUNG_CEPH'`, `detail.mode` là `NGAY` hoặc `QUY`, `detail.period` là ngày hoặc quý).

Loại lệch: `THIEU_TEP` (RGW trả 404 cho phiên bản), `LECH_BAM` (băm hoặc kích thước khác), `KHONG_DOC_DUOC` (lỗi khác khi đọc). `detail.versions` ghi tối đa 100 phiên bản lệch với bucket, khoá, mã phiên bản. Lỗi CSDL giữa chừng kết thúc lần chạy `LOI` với `detail.reason = 'KHONG_DOC_DUOC'`.

Bật job: đặt `MISSION_RECONCILE_EVIDENCE_ENABLED=true` và `MISSION_RECONCILE_EVIDENCE_TENANTS=<uuid>,<uuid>` (các tenant của cell) qua khoá `env` của values chart evidence-service.

## 2. Xử lý khi có `LOI`

1. Xem chi tiết:

   ```sql
   SET app.tenant_id = '<uuid-tenant>';
   SELECT started_at, detail->>'mode', detail->'kinds', detail->'versions'
     FROM evidence.reconcile_run
    WHERE job = 'R6_MINH_CHUNG_CEPH' AND status = 'LOI'
    ORDER BY started_at DESC LIMIT 5;
   ```

2. `THIEU_TEP` hoặc `LECH_BAM`: Object Lock chế độ compliance không cho xoá hay ghi đè phiên bản, nên đây là sự cố nghiêm trọng của kho (mất dữ liệu đĩa, đồng bộ đa vùng lỗi) hoặc can thiệp trái phép. Báo SOC, rồi khôi phục phiên bản từ vùng Ceph còn lại theo `ceph-multisite.md`; tệp khôi phục phải cho đúng `sha256` đã ghi.
3. `KHONG_DOC_DUOC`: kiểm tra RGW và thông tin truy cập của evidence-service; lần chạy ngày sau lấy mẫu khác nên cần chạy tay cho các phiên bản đã báo nếu nghi ngờ.
4. Ghi kết quả khôi phục vào phiếu sự cố; lần quét quý kế tiếp xác nhận toàn bộ.

## 3. Ghi chú

- Tệp E2EE được băm trên bản mã như lúc tải lên, job không cần khoá giải mã.
- Cảnh báo ở chart evidence-service: `EvidenceTepMinhChungLech` (mức critical), `EvidenceDoiSoatTepNgungChay` (30 giờ không có lần nào kết thúc).

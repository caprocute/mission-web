// Nhóm 4: nghiệp vụ vận hành kỳ đánh giá (TL-04 mục 10.6). Metric vt_* là metric ứng dụng chưa có trong mã
// (suy luận về tên), cần khớp khi mission-core và các service xuất metric nghiệp vụ.
local c = import '../lib/chung.libsonnet';
local q = c.q;

c.dashboard('vt-nghiep-vu', 'Vận hành kỳ đánh giá', [c.bienCell], [
  c.so('Người dùng đang hoạt động', [q('sum(vt_rum_concurrent_users{cell=~"$cell"})')]),
  c.so('Minh chứng chờ quét', [q('sum(vt_evidence_scan_pending{cell=~"$cell"})')]),
  c.so('Tiến độ tính lại điểm', [q('min(vt_score_recompute_progress_ratio{cell=~"$cell"})')], 'percentunit'),
  c.so('Tiến độ ký lô', [q('min(vt_esign_batch_progress_ratio{cell=~"$cell"})')], 'percentunit'),
  c.bieuDo('Người dùng đang hoạt động theo cell', [q('sum by (cell) (vt_rum_concurrent_users{cell=~"$cell"})', '{{cell}}')]),
  c.bieuDo('Hồ sơ đã nộp', [q('sum by (cell) (increase(vt_dossier_submitted_total{cell=~"$cell"}[1h]))', '{{cell}} mỗi giờ')]),
  c.bieuDo('Minh chứng chờ quét', [q('sum by (cell) (vt_evidence_scan_pending{cell=~"$cell"})', '{{cell}}')]),
  c.bieuDo('Tiến độ tính lại điểm và ký lô', [
    q('min by (cell) (vt_score_recompute_progress_ratio{cell=~"$cell"})', '{{cell}} tính lại điểm'),
    q('min by (cell) (vt_esign_batch_progress_ratio{cell=~"$cell"})', '{{cell}} ký lô'),
  ], 'percentunit'),
])

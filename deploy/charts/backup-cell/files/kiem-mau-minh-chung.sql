-- INF-18 · Mẫu kiểm sao lưu minh chứng của một tenant (kiem-mau-minh-chung.sh). Mẫu tất định theo ngày (băm id kèm
-- ngày), chỉ lấy phiên bản tạo trước ngày kiểm để chắc lần sao lưu đêm trước đã chạy qua. Giao dịch riêng để SET LOCAL
-- ngữ cảnh tenant đi qua PgBouncer chế độ transaction; RLS của evidence.object_version lọc theo app.tenant_id.
-- Biến psql: tenant, ngay, n.
BEGIN READ ONLY;
SET LOCAL app.tenant_id = :'tenant';
SELECT bucket, object_key, encode(sha256, 'hex'), size_bytes
  FROM evidence.object_version
 WHERE created_at < (:'ngay')::date
 ORDER BY md5(id::text || :'ngay')
 LIMIT :n;
COMMIT;

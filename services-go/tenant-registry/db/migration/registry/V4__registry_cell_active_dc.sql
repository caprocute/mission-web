-- INF-20 · SVC-03 tenant-registry · cờ TTDL đang hoạt động của cell (TL-04 "GeoDNS và định tuyến tenant tới cell",
-- DR-10, DR-16: cờ `active_site` mà dns-sync đọc để ghi bản ghi A của điểm vào cell). TL-03 bảng registry.cell
-- chưa có cột này (bổ sung, suy luận tên theo quy ước primary_dc/standby_dc). NULL nghĩa là cell đang chạy ở
-- primary_dc; chỉ người trực đổi sang standby_dc khi IC duyệt chuyển vùng, không bao giờ tự động.
ALTER TABLE registry.cell ADD COLUMN active_dc varchar(16);
ALTER TABLE registry.cell ADD CONSTRAINT ck_cell__active_dc
  CHECK (active_dc IS NULL OR active_dc = primary_dc OR active_dc = standby_dc);
COMMENT ON COLUMN registry.cell.active_dc IS '{"cls":"C1","desc":"TTDL đang phục vụ cell (NULL: primary_dc); đổi khi chuyển vùng có người duyệt (DR-10)"}';

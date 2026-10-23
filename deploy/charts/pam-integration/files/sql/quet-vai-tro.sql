-- Quét vai trò CSDL "không có cửa sau" (INF-27; SEC-58, SEC-42). Chạy bằng psql trên từng CSDL của một cụm, chỉ đọc
-- danh mục hệ thống (không đọc dữ liệu nghiệp vụ). Cần PostgreSQL ≥ 16 (cột pg_auth_members.inherit_option).
-- Biến psql (-v):
--   schemas   regex tên schema nghiệp vụ, ví dụ ^(task|okr)$
--   dich_vu   regex vai trò dịch vụ được đọc hợp lệ (svc_, ro_, mig_, cdc_ theo TL-03)
--   dong      regex tên người dùng động của OpenBao (v-...)
--   pha_kinh  tên vai trò đọc của phá kính (dba_pha_kinh_doc)
--   dba       tên vai trò quản trị (dba_ops)
--   han       thời hạn tối đa của phá kính cộng lệch đồng hồ, dạng interval (ví dụ '2 hours 10 minutes')
--   sieu      danh sách siêu người dùng được phép, cách nhau dấu phẩy (postgres)
-- Kết quả: mỗi dòng "loai|vai_tro|chi_tiet". Loại:
--   cong-khai            PUBLIC có SELECT trên bảng hoặc cột nghiệp vụ
--   doc-truc-tiep        vai trò ngoài vai trò dịch vụ được cấp SELECT (bảng hoặc cột) hay sở hữu bảng nghiệp vụ
--   thua-ke-dich-vu      vai trò thừa kế hoặc SET ROLE được sang vai trò dịch vụ mà không phải người dùng động có
--                        hạn của OpenBao
--   thua-ke-khac         vai trò thừa kế một vai trò không hợp lệ đọc được bảng nghiệp vụ
--   doc-toan-bo          thành viên (thừa kế) pg_read_all_data hoặc pg_write_all_data
--   sieu-nguoi-dung      siêu người dùng ngoài danh sách được phép, hoặc vai trò BYPASSRLS ngoài vai trò dịch vụ
--   pha-kinh-qua-han     thành viên vai trò phá kính không có VALID UNTIL hoặc hạn vượt thời hạn cho phép
--   dba-doc-nghiep-vu    vai trò dba_ops đọc được bảng nghiệp vụ (vi phạm SEC-58)
--   pha-kinh-dang-mo     (thông tin) phá kính đang hiệu lực, hạn hợp lệ
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'
WITH RECURSIVE
bang AS (
  SELECT c.oid, c.relowner, c.relacl, n.nspname, c.relname
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname ~ :'schemas' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
),
-- Vai trò (oid; 0 là PUBLIC) có SELECT trực tiếp: ACL bảng (NULL nghĩa là mặc định: chủ sở hữu), ACL cột, chủ sở hữu.
doc AS (
  SELECT DISTINCT a.grantee AS r, b.nspname FROM bang b, aclexplode(coalesce(b.relacl, acldefault('r', b.relowner))) a
  WHERE a.privilege_type = 'SELECT'
  UNION
  SELECT DISTINCT a.grantee, b.nspname FROM bang b JOIN pg_attribute at ON at.attrelid = b.oid AND at.attacl IS NOT NULL,
         aclexplode(at.attacl) a
  WHERE a.privilege_type = 'SELECT'
  UNION
  SELECT b.relowner, b.nspname FROM bang b
),
-- Bao đóng thành viên: (g, x) khi g dùng được quyền của x, tự động (INHERIT) hoặc bằng SET ROLE (SET); tuỳ chọn
-- của từng lần cấp từ PostgreSQL 16. Cấp chỉ ADMIN (INHERIT FALSE, SET FALSE) không cho dùng quyền nên không tính.
bao AS (
  SELECT oid AS g, oid AS x FROM pg_roles
  UNION
  SELECT bao.g, m.roleid FROM bao JOIN pg_auth_members m ON m.member = bao.x AND (m.inherit_option OR m.set_option)
),
-- Vai trò hợp lệ được đọc: vai trò dịch vụ và vai trò phá kính.
hop_le AS (
  SELECT oid FROM pg_roles WHERE rolname ~ :'dich_vu' OR rolname = :'pha_kinh'
),
-- Thành viên trực tiếp (bước đầu) dùng được quyền của vai trò cha.
buoc1 AS (
  SELECT m.member AS r, m.roleid AS g FROM pg_auth_members m WHERE m.inherit_option OR m.set_option
),
-- Vai trò g đọc được bảng nghiệp vụ qua bao đóng.
doc_qua AS (
  SELECT DISTINCT bao.g FROM bao JOIN doc ON doc.r = bao.x
),
vt AS (
  SELECT oid, rolname, rolsuper, rolbypassrls, rolvaliduntil FROM pg_roles WHERE rolname !~ '^pg_'
),
phat_hien AS (
  SELECT 'cong-khai' AS loai, 'PUBLIC' AS vai_tro, string_agg(DISTINCT nspname, ',') AS chi_tiet FROM doc WHERE r = 0
  HAVING count(*) > 0
  UNION ALL
  SELECT 'doc-truc-tiep', v.rolname, string_agg(DISTINCT d.nspname, ',')
  FROM doc d JOIN vt v ON v.oid = d.r
  WHERE v.oid NOT IN (SELECT oid FROM hop_le) AND NOT v.rolsuper
  GROUP BY v.rolname
  UNION ALL
  SELECT 'thua-ke-dich-vu', v.rolname, string_agg(DISTINCT g.rolname, ',')
  FROM buoc1 b JOIN vt v ON v.oid = b.r JOIN pg_roles g ON g.oid = b.g
  WHERE g.rolname ~ :'dich_vu' AND b.g IN (SELECT g FROM doc_qua)
    AND v.oid NOT IN (SELECT oid FROM hop_le) AND NOT v.rolsuper
    AND NOT (v.rolname ~ :'dong' AND v.rolvaliduntil IS NOT NULL)
  GROUP BY v.rolname
  UNION ALL
  SELECT 'thua-ke-khac', v.rolname, string_agg(DISTINCT g.rolname, ',')
  FROM buoc1 b JOIN vt v ON v.oid = b.r JOIN pg_roles g ON g.oid = b.g
  WHERE b.g IN (SELECT g FROM doc_qua) AND b.g NOT IN (SELECT oid FROM hop_le)
    AND v.oid NOT IN (SELECT oid FROM hop_le) AND NOT v.rolsuper AND g.rolname !~ '^pg_'
  GROUP BY v.rolname
  UNION ALL
  SELECT 'doc-toan-bo', v.rolname, string_agg(DISTINCT p.rolname, ',')
  FROM bao JOIN vt v ON v.oid = bao.g JOIN pg_roles p ON p.oid = bao.x
  WHERE p.rolname IN ('pg_read_all_data', 'pg_write_all_data') AND NOT v.rolsuper
  GROUP BY v.rolname
  UNION ALL
  SELECT 'sieu-nguoi-dung', v.rolname, CASE WHEN v.rolsuper THEN 'SUPERUSER' ELSE 'BYPASSRLS' END
  FROM vt v
  WHERE (v.rolsuper AND NOT v.rolname = ANY (string_to_array(:'sieu', ',')))
     OR (NOT v.rolsuper AND v.rolbypassrls AND v.rolname !~ :'dich_vu')
  UNION ALL
  SELECT 'pha-kinh-qua-han', v.rolname, coalesce(v.rolvaliduntil::text, 'không có VALID UNTIL')
  FROM pg_auth_members m JOIN vt v ON v.oid = m.member JOIN pg_roles p ON p.oid = m.roleid AND p.rolname = :'pha_kinh'
  WHERE (m.inherit_option OR m.set_option) AND (v.rolvaliduntil IS NULL OR v.rolvaliduntil > now() + :'han'::interval)
  UNION ALL
  SELECT 'dba-doc-nghiep-vu', v.rolname, 'dba_ops đọc được bảng nghiệp vụ'
  FROM vt v WHERE v.rolname = :'dba' AND v.oid IN (SELECT g FROM doc_qua)
  UNION ALL
  SELECT 'pha-kinh-dang-mo', v.rolname, v.rolvaliduntil::text
  FROM pg_auth_members m JOIN vt v ON v.oid = m.member JOIN pg_roles p ON p.oid = m.roleid AND p.rolname = :'pha_kinh'
  WHERE (m.inherit_option OR m.set_option) AND v.rolvaliduntil IS NOT NULL AND v.rolvaliduntil <= now() + :'han'::interval AND v.rolvaliduntil > now()
)
SELECT loai, vai_tro, chi_tiet FROM phat_hien ORDER BY loai, vai_tro;

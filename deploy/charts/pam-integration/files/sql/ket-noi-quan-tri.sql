-- Số kết nối quản trị đang mở trên cụm (INF-27; TL-04 mục 4 ngân sách kết nối, C_admin = 10 mỗi cụm): phiên của
-- thành viên dba_ops hoặc vai trò phá kính. pg_stat_activity là toàn cụm nên chỉ cần chạy trên một CSDL.
-- Biến psql: dba, pha_kinh. Kết quả: "tong|dba_ops|pha_kinh".
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'
WITH vai AS (
  SELECT (SELECT oid FROM pg_roles WHERE rolname = :'dba') AS dba,
         (SELECT oid FROM pg_roles WHERE rolname = :'pha_kinh') AS pk
),
phien AS (
  SELECT coalesce(pg_has_role(r.oid, vai.dba, 'MEMBER'), false) AS la_dba,
         coalesce(pg_has_role(r.oid, vai.pk, 'MEMBER'), false) AS la_pk
  FROM pg_stat_activity a JOIN pg_roles r ON r.oid = a.usesysid CROSS JOIN vai
  WHERE a.backend_type = 'client backend' AND NOT r.rolsuper
)
SELECT count(*) FILTER (WHERE la_dba OR la_pk), count(*) FILTER (WHERE la_dba), count(*) FILTER (WHERE la_pk) FROM phien;

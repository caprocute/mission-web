-- Vai trò quản trị CSDL theo SEC-58 (INF-27; TL-04 mục 9.3 "DBA không đọc được dữ liệu C2").
-- Chạy một lần mỗi cụm CNPG bằng siêu người dùng (phiên PAM của DBA có hai người duyệt, hoặc postInitSQLRefs của
-- data-pg khi INF-11 đưa vào), chạy lại bao nhiêu lần cũng được. Vai trò và thành viên vai trò là toàn cụm, nên không
-- cần chạy theo từng CSDL. Không chứa mật khẩu: người dùng đăng nhập đều do OpenBao tạo động (database engine).
--
--   dba_ops           NOLOGIN. Chỉ danh mục hệ thống, thống kê, chức năng quản trị (pg_monitor, pg_signal_backend,
--                     pg_checkpoint, pg_maintain từ PostgreSQL 17); không SELECT bảng nghiệp vụ nào.
--   dba_pha_kinh_doc  NOLOGIN. Vai trò đọc của phá kính: thành viên các vai trò ro_<schema> (TL-03), nên vẫn chịu RLS và
--                     cột C2 vẫn là bản mã (DEK chỉ mở được bằng tài khoản dịch vụ, SEC-58). Chỉ cấp cho người dùng động
--                     2 giờ của OpenBao khi PAM đủ hai người duyệt, trong đó một từ khách hàng.
--   pam_quet          LOGIN, không mật khẩu ở đây (OpenBao static role xoay 24 giờ). Thành viên pg_monitor để CronJob
--                     quét đọc pg_stat_activity, quyền và thành viên vai trò; không đọc bảng nghiệp vụ.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dba_ops') THEN
    CREATE ROLE dba_ops NOLOGIN INHERIT NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dba_pha_kinh_doc') THEN
    CREATE ROLE dba_pha_kinh_doc NOLOGIN INHERIT NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pam_quet') THEN
    CREATE ROLE pam_quet LOGIN INHERIT NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION CONNECTION LIMIT 2;
  END IF;
END
$$;

-- Thuộc tính có thể đã bị sửa tay: đặt lại mỗi lần chạy.
ALTER ROLE dba_ops NOLOGIN INHERIT NOSUPERUSER NOBYPASSRLS NOREPLICATION;
ALTER ROLE dba_pha_kinh_doc NOLOGIN INHERIT NOSUPERUSER NOBYPASSRLS NOREPLICATION;
ALTER ROLE pam_quet NOSUPERUSER NOBYPASSRLS NOREPLICATION;

-- Quyền quản trị của dba_ops: vai trò định sẵn của PostgreSQL, không có pg_read_all_data hay pg_write_all_data.
GRANT pg_monitor, pg_signal_backend TO dba_ops;
GRANT pg_monitor TO pam_quet;
DO $$
BEGIN
  IF current_setting('server_version_num')::int >= 150000 THEN
    EXECUTE 'GRANT pg_checkpoint TO dba_ops';
  END IF;
  IF current_setting('server_version_num')::int >= 170000 THEN
    EXECUTE 'GRANT pg_maintain TO dba_ops';
  END IF;
END
$$;

-- Vai trò đọc của phá kính: thành viên mọi ro_<schema> hiện có. Schema thêm sau thì chạy lại tệp này.
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT rolname FROM pg_roles WHERE rolname ~ '^ro_[a-z_]+$' LOOP
    EXECUTE format('GRANT %I TO dba_pha_kinh_doc', r.rolname);
  END LOOP;
END
$$;

-- OpenBao (bao_admin, INF-13) tạo người dùng động "IN ROLE dba_ops" hoặc "IN ROLE dba_pha_kinh_doc" và xoay mật khẩu
-- pam_quet: chỉ cần ADMIN OPTION, không thừa kế quyền và không SET ROLE (cú pháp PostgreSQL 16). pg_signal_backend để
-- câu thu hồi ngắt phiên còn mở khi lease hết hạn (VALID UNTIL chỉ chặn đăng nhập mới, không cắt phiên đang chạy).
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'bao_admin') THEN
    GRANT dba_ops TO bao_admin WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
    GRANT dba_pha_kinh_doc TO bao_admin WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
    GRANT pam_quet TO bao_admin WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
    GRANT pg_signal_backend TO bao_admin;
  END IF;
END
$$;

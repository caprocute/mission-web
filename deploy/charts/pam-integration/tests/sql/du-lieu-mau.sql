-- Dữ liệu mẫu cho kiểm thử quét vai trò CSDL (INF-27): hai schema nghiệp vụ theo bốn vai trò TL-03, bao_admin như
-- INF-13 (svc_task WITH ADMIN, INHERIT TRUE), rồi một loạt "cửa sau" mà quet-vai-tro.sql phải tìm ra. Chạy bằng siêu
-- người dùng trên CSDL thử tạm thời; không chứa mật khẩu (xác thực trust trên socket cục bộ của cụm thử).
CREATE ROLE mig_task LOGIN; CREATE ROLE svc_task LOGIN; CREATE ROLE ro_task LOGIN; CREATE ROLE cdc_task LOGIN REPLICATION;
CREATE ROLE mig_okr LOGIN; CREATE ROLE svc_okr LOGIN; CREATE ROLE ro_okr LOGIN;
CREATE SCHEMA task AUTHORIZATION mig_task;
CREATE SCHEMA okr AUTHORIZATION mig_okr;
CREATE SCHEMA ngoai_nghiep_vu;
SET ROLE mig_task;
CREATE TABLE task.giao_viec (id bigint PRIMARY KEY, tieu_de text, noi_dung_c2 bytea);
GRANT USAGE ON SCHEMA task TO svc_task, ro_task, cdc_task;
GRANT SELECT, INSERT, UPDATE, DELETE ON task.giao_viec TO svc_task;
GRANT SELECT ON task.giao_viec TO ro_task, cdc_task;
INSERT INTO task.giao_viec VALUES (1, 'Mẫu', '\x00');
RESET ROLE;
SET ROLE mig_okr;
CREATE TABLE okr.muc_tieu (id bigint PRIMARY KEY, ten text, ghi_chu text);
CREATE TABLE okr.cong_khai (id bigint);
GRANT USAGE ON SCHEMA okr TO svc_okr, ro_okr, PUBLIC;
GRANT SELECT ON okr.muc_tieu TO svc_okr, ro_okr;
-- Cửa sau 1: PUBLIC đọc được bảng nghiệp vụ.
GRANT SELECT ON okr.cong_khai TO PUBLIC;
RESET ROLE;
-- Bảng ngoài schema nghiệp vụ: quyền ở đây không phải phát hiện.
CREATE TABLE ngoai_nghiep_vu.thong_ke (id int);
CREATE ROLE henry LOGIN;
GRANT USAGE ON SCHEMA ngoai_nghiep_vu TO henry;
GRANT SELECT ON ngoai_nghiep_vu.thong_ke TO henry;

-- bao_admin như INF-13 (data-pg: GRANT svc_<schema> TO bao_admin WITH ADMIN TRUE, INHERIT TRUE, SET TRUE).
CREATE ROLE bao_admin LOGIN CREATEROLE;
GRANT svc_task TO bao_admin WITH ADMIN TRUE, INHERIT TRUE, SET TRUE;

-- Các cửa sau còn lại.
CREATE ROLE alice LOGIN;
GRANT USAGE ON SCHEMA task TO alice;
GRANT SELECT ON task.giao_viec TO alice;                       -- doc-truc-tiep
CREATE ROLE gina LOGIN;
GRANT SELECT (tieu_de) ON task.giao_viec TO gina;              -- doc-truc-tiep (quyền cột)
CREATE ROLE bob LOGIN IN ROLE ro_task;                         -- thua-ke-dich-vu
CREATE ROLE nhom_x NOLOGIN;
GRANT SELECT ON okr.muc_tieu TO nhom_x;                        -- doc-truc-tiep
CREATE ROLE carol LOGIN IN ROLE nhom_x;                        -- thua-ke-khac
CREATE ROLE dave LOGIN IN ROLE pg_read_all_data;               -- doc-toan-bo
CREATE ROLE erin LOGIN SUPERUSER;                              -- sieu-nguoi-dung
CREATE ROLE frank LOGIN BYPASSRLS;                             -- sieu-nguoi-dung (BYPASSRLS)
-- Không thừa kế nhưng còn SET ROLE được: vẫn đọc được bằng SET ROLE ro_task, là phát hiện thua-ke-dich-vu.
CREATE ROLE ivan LOGIN;
GRANT ro_task TO ivan WITH INHERIT FALSE;
-- Chỉ ADMIN (không thừa kế, không SET ROLE): không dùng được quyền đọc, không phải phát hiện.
CREATE ROLE jack LOGIN;
GRANT ro_task TO jack WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;

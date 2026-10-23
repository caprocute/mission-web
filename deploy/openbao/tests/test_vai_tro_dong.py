#!/usr/bin/env python3
"""Kiểm thử thật vai trò động của OpenBao database engine trên PostgreSQL ≥ 16 (INF-13, sửa ở INF-27; SEC-58).

Dựng PostgreSQL tạm trong thư mục tạm (chỉ socket Unix, xác thực trust, xoá khi xong), chạy đúng khối SQL initdb
của bao_admin mà chart data-pg render ra, rồi bằng bao_admin (không phải siêu người dùng) chạy câu tạo và câu thu
hồi lấy từ deploy/openbao/cau-hinh.sh (--in-sql) và cau-hinh-pam.sh của pam-integration. Khẳng định:
  - bao_admin chỉ có ADMIN trên svc_<schema>: không SELECT được bảng nghiệp vụ, không SET ROLE sang svc_ hay sang
    người dùng động do chính nó tạo, nhưng vẫn tạo được người dùng động IN ROLE svc_<schema>;
  - người dùng động đọc được bảng của svc_ mà không tạo được bảng (không sở hữu được đối tượng);
  - câu thu hồi chạy được bằng bao_admin khi người dùng động còn phiên mở (kể cả có bảng tạm): phiên bị ngắt, vai
    trò bị xoá; rotate-root (ALTER ROLE bao_admin PASSWORD) chạy được;
  - quét "không có cửa sau" của pam-integration không báo bao_admin.
Bỏ qua khi máy không có PostgreSQL ≥ 16 cài sẵn (PG_BIN) hoặc không có helm.

Dùng: python3 -m unittest discover -s deploy/openbao/tests
"""
import datetime as dt
import glob
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

import yaml

GOC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
CAU_HINH = os.path.join(GOC, "deploy", "openbao", "cau-hinh.sh")
PAM = os.path.join(GOC, "deploy", "charts", "pam-integration")
DATA_PG = os.path.join(GOC, "deploy", "charts", "vt-platform", "ha-tang", "data-pg")
HELM = os.environ.get("HELM") or shutil.which("helm") or "/root/bin/helm"
sys.dont_write_bytecode = True


def tim_pg_bin():
    if os.environ.get("PG_BIN"):
        return os.environ["PG_BIN"]
    ung = sorted(glob.glob("/usr/lib/postgresql/*/bin/initdb"), key=lambda p: int(p.split("/")[4]))
    if ung and int(ung[-1].split("/")[4]) >= 16:
        return os.path.dirname(ung[-1])
    return None


def sql_khoi_tao_bao_admin():
    """Khối khoi-tao.sql của ExternalSecret <cụm>-bao-admin-khoi-tao (pg-core) do data-pg render."""
    r = subprocess.run([HELM, "template", "d", DATA_PG, "--set", "global.cell=g4"], capture_output=True, text=True, check=True)
    for d in yaml.safe_load_all(r.stdout):
        if d and d["kind"] == "ExternalSecret" and d["metadata"]["name"].endswith("bao-admin-khoi-tao"):
            return d["spec"]["target"]["template"]["data"]["khoi-tao.sql"].replace("{{ .password }}", "khong-dung")
    raise AssertionError("không thấy khối SQL initdb của bao_admin")


def cau(script, *args):
    return subprocess.run(["bash", script, "--in-sql", *args], capture_output=True, text=True, check=True).stdout.splitlines()


@unittest.skipUnless(tim_pg_bin() and os.path.exists(os.path.join(tim_pg_bin() or "", "postgres")) and os.path.exists(HELM),
                     "không có PostgreSQL ≥ 16 cài sẵn (PG_BIN) hoặc helm")
class VaiTroDong(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bin = tim_pg_bin()
        cls.tmp = tempfile.mkdtemp(prefix="bao-pg-")
        cls.chay_bang = []
        if os.geteuid() == 0:
            cls.chay_bang = ["runuser", "-u", "postgres", "--"]  # initdb từ chối chạy bằng root
            shutil.chown(cls.tmp, "postgres")
        cls.data, cls.port = os.path.join(cls.tmp, "data"), "55434"
        cls.lenh([f"{cls.bin}/initdb", "-D", cls.data, "-A", "trust", "-U", "postgres", "--no-instructions", "-E", "UTF8"])
        cls.lenh([f"{cls.bin}/pg_ctl", "-D", cls.data, "-w", "-l", os.path.join(cls.tmp, "log"),
                  "-o", f"-c listen_addresses='' -k {cls.tmp} -p {cls.port}", "start"])
        cls.psql("-c", "CREATE DATABASE thu")
        cls.psql("-d", "thu", "-c", sql_khoi_tao_bao_admin())
        # Phần CNPG managed.roles và Job cấp quyền (INF-11) làm sau initdb: mig_ sở hữu schema, svc_ được đọc ghi.
        cls.psql("-d", "thu", "-c", """
            CREATE ROLE mig_task LOGIN;
            CREATE SCHEMA task AUTHORIZATION mig_task;
            SET ROLE mig_task;
            CREATE TABLE task.giao_viec (id bigint PRIMARY KEY, tieu_de text);
            INSERT INTO task.giao_viec VALUES (1, 'Mẫu');
            GRANT USAGE ON SCHEMA task TO svc_task;
            GRANT SELECT, INSERT, UPDATE, DELETE ON task.giao_viec TO svc_task;
            RESET ROLE;""")
        cls.psql("-d", "thu", "-f", os.path.join(PAM, "files", "sql", "vai-tro-dba.sql"))
        cls.tao, cls.thu_hoi = cau(CAU_HINH, "svc_task,svc_okr")

    @classmethod
    def lenh(cls, args):
        return subprocess.run(cls.chay_bang + args, capture_output=True, text=True, check=True, cwd=cls.tmp)

    @classmethod
    def psql(cls, *args, nguoi="postgres", kiem=True):
        a = [f"{cls.bin}/psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-h", cls.tmp, "-p", cls.port, "-U", nguoi]
        return subprocess.run(a + list(args), capture_output=True, text=True, check=kiem)

    @classmethod
    def tearDownClass(cls):
        subprocess.run(cls.chay_bang + [f"{cls.bin}/pg_ctl", "-D", cls.data, "-m", "immediate", "stop"], capture_output=True)
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def tao_dong(self, ten, cau_tao=None, gio=24):
        het = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=gio)).isoformat()
        sql = (cau_tao or self.tao).replace("{{name}}", ten).replace("{{password}}", "khong-dung").replace("{{expiration}}", het)
        self.psql("-d", "thu", "-c", sql, nguoi="bao_admin")

    def con_vai_tro(self, ten):
        return self.psql("-d", "thu", "-c", f"SELECT count(*) FROM pg_roles WHERE rolname = '{ten}'").stdout.strip() == "1"

    def test_bao_admin_khong_doc_duoc_nghiep_vu(self):
        r = self.psql("-d", "thu", "-c", "SELECT count(*) FROM task.giao_viec", nguoi="bao_admin", kiem=False)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("permission denied", r.stderr)
        r = self.psql("-d", "thu", "-c", "SET ROLE svc_task", nguoi="bao_admin", kiem=False)
        self.assertIn("permission denied to set role", r.stderr)
        ra = self.psql("-d", "thu", "-c", "SELECT rolname, admin_option, inherit_option, set_option FROM pg_auth_members m "
                       "JOIN pg_roles r ON r.oid = m.roleid WHERE m.member = 'bao_admin'::regrole AND rolname LIKE 'svc\\_%' "
                       "ORDER BY 1 LIMIT 1").stdout.strip()
        self.assertTrue(ra.endswith("|t|f|f"), ra)
        self.assertEqual(self.psql("-d", "thu", "-c", "SELECT setconfig FROM pg_db_role_setting WHERE setrole = 'bao_admin'::regrole").stdout.strip(), "")

    def test_tao_doc_va_khong_so_huu_duoc(self):
        self.tao_dong("v-tok-pg-core-mission-a1")
        self.assertEqual(self.psql("-d", "thu", "-c", "SELECT count(*) FROM task.giao_viec", nguoi="v-tok-pg-core-mission-a1").stdout.strip(), "1")
        for bang in ("task.tu_tao", "public.tu_tao"):
            r = self.psql("-d", "thu", "-c", f"CREATE TABLE {bang} (x int)", nguoi="v-tok-pg-core-mission-a1", kiem=False)
            self.assertIn("permission denied", r.stderr, bang)
        # bao_admin không SET ROLE được sang người dùng động do nó tạo (không có createrole_self_grant).
        r = self.psql("-d", "thu", "-c", 'SET ROLE "v-tok-pg-core-mission-a1"', nguoi="bao_admin", kiem=False)
        self.assertIn("permission denied to set role", r.stderr)
        self.psql("-d", "thu", "-c", self.thu_hoi.replace("{{name}}", "v-tok-pg-core-mission-a1"), nguoi="bao_admin")
        self.assertFalse(self.con_vai_tro("v-tok-pg-core-mission-a1"))

    def test_thu_hoi_khi_con_phien_mo_co_bang_tam(self):
        ten = "v-tok-pg-core-mission-a2"
        self.tao_dong(ten)
        p = subprocess.Popen([f"{self.bin}/psql", "-X", "-h", self.tmp, "-p", self.port, "-U", ten, "-d", "thu", "-c",
                              "CREATE TEMP TABLE tam (x int)", "-c", "SELECT pg_sleep(60)"],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for _ in range(100):
                if self.psql("-d", "thu", "-c", f"SELECT count(*) FROM pg_stat_activity WHERE usename = '{ten}' AND query LIKE '%pg_sleep%'").stdout.strip() == "1":
                    break
                time.sleep(0.1)
            else:
                self.fail("phiên của người dùng động không mở được")
            bat_dau = time.time()
            self.psql("-d", "thu", "-c", self.thu_hoi.replace("{{name}}", ten), nguoi="bao_admin")
            self.assertLess(time.time() - bat_dau, 10)
            self.assertFalse(self.con_vai_tro(ten))
            self.assertIsNotNone(p.wait(timeout=10))
        finally:
            if p.poll() is None:
                p.kill()

    def test_thu_hoi_lap_lai_khong_loi(self):
        # OpenBao có thể gửi lại câu thu hồi khi lần trước lỗi mạng: lần hai phải chạy được (DROP ROLE IF EXISTS).
        self.psql("-d", "thu", "-c", self.thu_hoi.replace("{{name}}", "v-khong-con"), nguoi="bao_admin")

    def test_rotate_root(self):
        self.psql("-d", "thu", "-c", "ALTER ROLE \"bao_admin\" WITH PASSWORD 'mat-khau-moi-thu';", nguoi="bao_admin")

    def test_vai_tro_pam_cung_chay_bang_bao_admin(self):
        tao, thu_hoi = cau(os.path.join(PAM, "files", "openbao", "cau-hinh-pam.sh"), "dba_ops", "2")
        self.tao_dong("v-pam-pg-core-dba-ops-b1", cau_tao=tao, gio=4)
        r = self.psql("-d", "thu", "-c", "SELECT count(*) FROM task.giao_viec", nguoi="v-pam-pg-core-dba-ops-b1", kiem=False)
        self.assertIn("permission denied", r.stderr)
        self.psql("-d", "thu", "-c", thu_hoi.replace("{{name}}", "v-pam-pg-core-dba-ops-b1"), nguoi="bao_admin")
        self.assertFalse(self.con_vai_tro("v-pam-pg-core-dba-ops-b1"))

    def test_quet_khong_bao_bao_admin(self):
        self.tao_dong("v-tok-pg-core-mission-a3")
        ra = self.psql("-d", "thu", "-v", "schemas=^(task)$", "-v", "dich_vu=^(svc|ro|mig|cdc)_[a-z_]+$", "-v", "dong=^v-",
                       "-v", "pha_kinh=dba_pha_kinh_doc", "-v", "dba=dba_ops", "-v", "han=2 hours 10 minutes",
                       "-v", "sieu=postgres", "-f", os.path.join(PAM, "files", "sql", "quet-vai-tro.sql")).stdout
        self.assertNotIn("bao_admin", ra)
        self.assertEqual([d for d in ra.splitlines() if d], [])


if __name__ == "__main__":
    unittest.main()

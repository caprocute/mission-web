"""Nạp tệp COPY vào PostgreSQL đã áp migration thật.

PostgreSQL không cho COPY FROM vào bảng bật RLS với vai trò không BYPASSRLS, còn svc_<schema> không
có quyền tạo bảng tạm và mig_<schema> (chủ bảng) bị FORCE RLS chặn vì chính sách chỉ cấp cho svc_ và
ro_. Vì vậy bộ nạp dùng tài khoản quản trị của CSDL thử (ở CELL-PERF là một vai trò nạp riêng có
BYPASSRLS, do nhóm vận hành cấp tạm thời): khoá ngoại, CHECK và ràng buộc loại trừ vẫn áp khi COPY,
còn RLS được kiểm ngay sau nạp bằng svc_<schema>: dưới ngữ cảnh tenant thấy đủ số dòng đã nạp, dưới
ngữ cảnh tenant khác thấy 0 dòng.
"""

from __future__ import annotations

import os
import time
from dataclasses import dataclass
from pathlib import Path

import psycopg

from .bang import COT, PHAN_VUNG, THU_TU_NAP, schema


@dataclass(frozen=True)
class KetNoi:
    host: str
    port: int
    db: str
    admin_user: str
    admin_password: str
    role_password: str

    @staticmethod
    def tu_moi_truong(tep_env: Path | None = None) -> "KetNoi":
        env = dict(os.environ)
        if tep_env and tep_env.exists():
            for dong in tep_env.read_text().splitlines():
                if "=" in dong and not dong.startswith("#"):
                    k, v = dong.split("=", 1)
                    env.setdefault(k, v)
        return KetNoi(env["DATAGEN_PG_HOST"], int(env["DATAGEN_PG_PORT"]), env["DATAGEN_PG_DB"],
                      env["DATAGEN_PG_ADMIN_USER"], env["DATAGEN_PG_ADMIN_PASSWORD"],
                      env["DATAGEN_PG_ROLE_PASSWORD"])

    def mo(self, user: str | None = None) -> psycopg.Connection:
        return psycopg.connect(host=self.host, port=self.port, dbname=self.db,
                               user=user or self.admin_user,
                               password=self.role_password if user else self.admin_password)


def thu_muc_tenant(goc: Path) -> list[tuple[str, Path]]:
    return [(p.name.split("-", 2)[2], p) for p in sorted(goc.glob("tenant-*")) if p.is_dir()]


def _xoa_tenant(kn: KetNoi, tenant: str) -> None:
    with kn.mo() as c:
        for bang in reversed(THU_TU_NAP):
            c.execute(f"DELETE FROM {bang} WHERE tenant_id = %s", (tenant,))


def da_co_du_lieu(kn: KetNoi, tenant: str) -> bool:
    with kn.mo() as c:
        return c.execute("SELECT EXISTS (SELECT 1 FROM org.org_unit WHERE tenant_id = %s)",
                         (tenant,)).fetchone()[0]


def nap(kn: KetNoi, goc: Path, thay_the: bool = False) -> dict:
    ket_qua: dict[str, dict] = {}
    for tenant, thu_muc in thu_muc_tenant(goc):
        if da_co_du_lieu(kn, tenant):
            if not thay_the:
                raise RuntimeError(f"Tenant {tenant} đã có dữ liệu; chạy lại với --thay-the để nạp lại")
            _xoa_tenant(kn, tenant)
        bat_dau = time.perf_counter()
        dem: dict[str, int] = {}
        with kn.mo() as c:
            for bang in THU_TU_NAP:
                d = thu_muc / bang
                if not d.is_dir():
                    continue
                s = schema(bang)
                cot = ", ".join(COT[bang])
                for tep in sorted(d.glob("*.tsv")):
                    with c.transaction():
                        if bang in PHAN_VUNG:
                            c.execute(f"SELECT {s}.fn_create_period_partitions(%s::regclass, %s)",
                                      (bang, int(tep.stem[1:])))
                        with c.cursor().copy(f"COPY {bang} ({cot}) FROM STDIN") as cp:
                            with tep.open("rb") as f:
                                while khoi := f.read(1 << 20):
                                    cp.write(khoi)
                dem[bang] = c.execute(f"SELECT count(*) FROM {bang} WHERE tenant_id = %s",
                                      (tenant,)).fetchone()[0]
        ket_qua[tenant] = {"dem": dem, "giay_nap": round(time.perf_counter() - bat_dau, 3),
                           "rls": kiem_rls(kn, tenant, dem)}
    return ket_qua


def kiem_rls(kn: KetNoi, tenant: str, dem: dict[str, int]) -> list[str]:
    """Đọc lại bằng svc_<schema>: ngữ cảnh đúng tenant thấy đủ dòng, tenant khác thấy 0 dòng."""
    loi = []
    tenant_khac = "00000000-0000-4000-8000-0000000000ff"
    for s in sorted({schema(b) for b in dem}):
        with kn.mo(f"svc_{s}") as c:
            for bang in (b for b in dem if schema(b) == s):
                for t, ky_vong in ((tenant, dem[bang]), (tenant_khac, 0)):
                    with c.transaction():
                        c.execute("SELECT set_config('app.tenant_id', %s, true)", (t,))
                        thay = c.execute(f"SELECT count(*) FROM {bang}").fetchone()[0]
                    if thay != ky_vong:
                        loi.append(f"{bang}: ngữ cảnh {t} thấy {thay} dòng, cần {ky_vong}")
    return loi

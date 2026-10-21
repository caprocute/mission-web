# Mẫu mã Python ĐÚNG quy ước NEN-034 lớp 4 (SVC-14).


async def apply_tenant_safe(conn, tenant_id: str) -> None:
    await conn.execute("SELECT set_config('app.tenant_id', $1, true)", tenant_id)

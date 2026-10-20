# Mẫu mã Python SAI để chứng minh luật Semgrep NEN-034 lớp 4 bắt được đặt tenant mức phiên (SVC-14).


async def apply_tenant_wrong(conn, tenant_id: str) -> None:
    # ruleid: mission-no-session-set-app-param
    await conn.execute(f"SET app.tenant_id TO '{tenant_id}'")
    # ruleid: mission-no-set-session
    await conn.execute("SET SESSION search_path = ai")
    # ruleid: mission-no-set-config-is-local-false
    await conn.execute("SELECT set_config('app.tenant_id', $1, false)", tenant_id)

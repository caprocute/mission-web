-- DV-13-05 · SVC-13: worker gửi ra lọc theo mã kết nối, để route Trục liên thông chạy ở Deployment
-- riêng (TL-01 "Các bộ kết nối": đối tác chậm không ảnh hưởng bộ kết nối khác). p_include = true chỉ
-- lấy các mã trong p_codes; false lấy mọi mã trừ p_codes. Chỉ trả mã và tenant như fn_thu_ra_den_han.
CREATE FUNCTION ${flyway:defaultSchema}.fn_thu_ra_den_han_theo_ma(p_limit int, p_codes varchar[], p_include boolean)
    RETURNS TABLE (tenant_id uuid, created_day date, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT m.tenant_id, m.created_day, m.id FROM ${flyway:defaultSchema}.outbound_message m
      JOIN ${flyway:defaultSchema}.connector c ON c.tenant_id = m.tenant_id AND c.id = m.connector_id
     WHERE m.status IN ('CHO', 'DA_GUI') AND m.next_attempt_at <= now()
       AND (c.connector_code = ANY (p_codes)) = p_include
     ORDER BY m.next_attempt_at
     LIMIT least(greatest(p_limit, 1), 500)
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_thu_ra_den_han_theo_ma(int, varchar[], boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_thu_ra_den_han_theo_ma(int, varchar[], boolean) TO svc_${flyway:defaultSchema};

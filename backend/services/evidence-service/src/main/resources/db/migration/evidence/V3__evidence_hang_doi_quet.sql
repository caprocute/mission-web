-- DV-08-04 · Hàng đợi quét mã độc (SEC-47, SEC-48; TL-01 SVC-08 "ClamAV daemon riêng, worker KEDA").
--
-- Điểm lệch và lựa chọn (wiki trang 14 mục 49):
--   * Hàng đợi là chính object_version có scan_status = 'CHO_QUET' (không thêm bảng): worker nhận việc bằng
--     UPDATE có điều kiện, nên nhiều worker chạy song song không quét trùng; scan_next_at vừa là hạn thuê việc
--     (worker chết thì việc tự trả lại) vừa là mốc thử lại có giãn cách.
--   * Hàm fn_ban_cho_quet cho worker thấy việc của mọi tenant (chỉ mã và tenant), như fn_phien_tai_len_het_han.

ALTER TABLE ${flyway:defaultSchema}.object_version
    ADD COLUMN scan_attempts int NOT NULL DEFAULT 0,
    ADD COLUMN scan_next_at timestamptz NOT NULL DEFAULT now(),
    ADD CONSTRAINT ck_object_version__scan_attempts CHECK (scan_attempts >= 0);

COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.scan_attempts IS '{"cls":"C1","desc":"Số lần đã nhận việc quét"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.scan_next_at IS '{"cls":"C1","desc":"Mốc sớm nhất được nhận việc quét (hạn thuê hoặc thử lại)"}';

CREATE INDEX ix_object_version__cho_quet ON ${flyway:defaultSchema}.object_version (scan_next_at)
    WHERE scan_status = 'CHO_QUET';

CREATE FUNCTION ${flyway:defaultSchema}.fn_ban_cho_quet(p_limit int)
  RETURNS TABLE (id uuid, tenant_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ${flyway:defaultSchema}, pg_temp
AS $$
  SELECT v.id, v.tenant_id FROM ${flyway:defaultSchema}.object_version v
   WHERE v.scan_status = 'CHO_QUET' AND v.scan_next_at <= now()
   ORDER BY v.scan_next_at
   LIMIT least(greatest(p_limit, 1), 500)
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_ban_cho_quet(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_ban_cho_quet(int) TO svc_${flyway:defaultSchema};

-- Số bản đang chờ quét của mọi tenant, cho chỉ số co giãn worker (KEDA theo độ dài hàng đợi).
CREATE FUNCTION ${flyway:defaultSchema}.fn_dem_cho_quet()
  RETURNS bigint
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ${flyway:defaultSchema}, pg_temp
AS $$
  SELECT count(*) FROM ${flyway:defaultSchema}.object_version v WHERE v.scan_status = 'CHO_QUET'
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_dem_cho_quet() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_dem_cho_quet() TO svc_${flyway:defaultSchema};

-- Như V2: hàm SECURITY DEFINER chạy với quyền mig_evidence nên cần chính sách đọc riêng dưới FORCE RLS.
CREATE POLICY rls_object_version__cho_quet ON ${flyway:defaultSchema}.object_version
    AS PERMISSIVE FOR SELECT TO mig_${flyway:defaultSchema}
    USING (scan_status = 'CHO_QUET');

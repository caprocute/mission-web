-- DV-07-04, DV-07-06 · Hàng đợi lô tính điểm và hàng đợi lỗi của SVC-07 (TL-01 "SVC-07 scoring-service",
-- TL-03 "Schema scoring (SVC-07)", NFR-06).
--
-- Điểm lệch và lựa chọn (wiki trang 14 mục DV-07-04, DV-07-06):
--   * Hàng đợi lô nằm trong CSDL (score_job_chunk), không phải topic scoring.recompute.requested.v1: topic chỉ
--     có ở TL-04, chưa có lược đồ Avro trong contracts/events. Mỗi lô là một trang 200 hồ sơ của
--     listAppraisalScoringInputs theo con trỏ (hoặc một đoạn danh sách kết quả của tác vụ KIEM_TRA_TAI_LAP);
--     worker nhận lô bằng UPDATE có điều kiện nên nhiều pod chạy song song không trùng; lease_until vừa là
--     hạn thuê (pod chết thì lô tự trả lại) vừa là mốc thử lại có giãn cách.
--   * Lô lỗi thử lại tối đa 3 lần; hồ sơ vẫn lỗi vào score_job_error có mã lý do để DV-07-06 chạy lại.
--   * Hàm fn_lo_tinh_den_han và fn_tac_vu_tinh_cho cho worker thấy việc của mọi tenant (chỉ mã, tenant và
--     phân vùng), như fn_ban_cho_quet của evidence; fn_dem_lo_tinh_cho cấp chỉ số co giãn KEDA.

-- ---------------------------------------------------------------- score_job_chunk
CREATE TABLE ${flyway:defaultSchema}.score_job_chunk (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    period_bucket int NOT NULL,
    score_job_id uuid NOT NULL,
    seq int NOT NULL,
    page_cursor varchar(512),
    status varchar(12) NOT NULL DEFAULT 'CHO',
    attempts int NOT NULL DEFAULT 0,
    lease_until timestamptz NOT NULL DEFAULT now(),
    items int NOT NULL DEFAULT 0,
    last_error varchar(500),
    CONSTRAINT pk_score_job_chunk PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_score_job_chunk__seq UNIQUE (period_bucket, tenant_id, score_job_id, seq),
    CONSTRAINT fk_score_job_chunk__score_job FOREIGN KEY (period_bucket, tenant_id, score_job_id)
        REFERENCES ${flyway:defaultSchema}.score_job (period_bucket, tenant_id, id),
    CONSTRAINT ck_score_job_chunk__status CHECK (status IN ('CHO', 'DANG_CHAY', 'XONG', 'LOI')),
    CONSTRAINT ck_score_job_chunk__counts CHECK (seq >= 1 AND attempts >= 0 AND items >= 0)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_score_job_chunk__den_han ON ${flyway:defaultSchema}.score_job_chunk (lease_until)
    WHERE status IN ('CHO', 'DANG_CHAY');

COMMENT ON TABLE ${flyway:defaultSchema}.score_job_chunk IS 'Lô của tác vụ tính (một trang đầu vào), hàng đợi worker co giãn';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.id IS '{"cls":"C1","desc":"Mã lô (UUIDv7)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.created_at IS '{"cls":"C1","desc":"Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.score_job_id IS '{"cls":"C1","desc":"Tác vụ tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.seq IS '{"cls":"C1","desc":"Thứ tự lô trong tác vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.page_cursor IS '{"cls":"C1","desc":"Con trỏ trang đầu vào hoặc vị trí trong danh sách kết quả"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.status IS '{"cls":"C1","desc":"Trạng thái lô"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.attempts IS '{"cls":"C1","desc":"Số lần đã nhận lô"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.lease_until IS '{"cls":"C1","desc":"Mốc sớm nhất được nhận lại (hạn thuê hoặc thử lại)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.items IS '{"cls":"C1","desc":"Số hồ sơ đã tính của lô"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_chunk.last_error IS '{"cls":"C1","desc":"Lỗi kỹ thuật lần nhận gần nhất, không chứa dữ liệu C2"}';

-- ---------------------------------------------------------------- score_job_error
CREATE TABLE ${flyway:defaultSchema}.score_job_error (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    period_bucket int NOT NULL,
    score_job_id uuid NOT NULL,
    chunk_seq int NOT NULL,
    profile_id uuid,
    person_id uuid,
    flow varchar(12),
    reason_code varchar(40) NOT NULL,
    detail varchar(500),
    attempts int NOT NULL,
    CONSTRAINT pk_score_job_error PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_score_job_error__score_job FOREIGN KEY (period_bucket, tenant_id, score_job_id)
        REFERENCES ${flyway:defaultSchema}.score_job (period_bucket, tenant_id, id),
    CONSTRAINT ck_score_job_error__flow CHECK (flow IS NULL OR flow IN ('DANG', 'NHA_NUOC'))
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_score_job_error__score_job ON ${flyway:defaultSchema}.score_job_error (tenant_id, score_job_id);

COMMENT ON TABLE ${flyway:defaultSchema}.score_job_error IS 'Hàng đợi lỗi: hồ sơ hoặc trang đầu vào không tính được sau khi thử lại, chờ chạy lại (DV-07-06)';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_error.id IS '{"cls":"C1","desc":"Mã dòng lỗi (UUIDv7)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_error.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_error.created_at IS '{"cls":"C1","desc":"Thời điểm ghi lỗi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_error.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_error.score_job_id IS '{"cls":"C1","desc":"Tác vụ tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_error.chunk_seq IS '{"cls":"C1","desc":"Lô phát sinh lỗi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_error.profile_id IS '{"cls":"C1","desc":"Hồ sơ lỗi; trống khi cả trang đầu vào không đọc được"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_error.person_id IS '{"cls":"C1","desc":"Người được tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_error.flow IS '{"cls":"C1","desc":"Luồng đánh giá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_error.reason_code IS '{"cls":"C1","desc":"Mã lý do lỗi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_error.detail IS '{"cls":"C1","desc":"Mô tả kỹ thuật, không chứa dữ liệu C2"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job_error.attempts IS '{"cls":"C1","desc":"Số lần đã thử"}';

-- ---------------------------------------------------------------- RLS và quyền
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.score_job_chunk');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.score_job_error');
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.score_job_chunk FROM svc_${flyway:defaultSchema};
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.score_job_error FROM svc_${flyway:defaultSchema};

-- Hàm SECURITY DEFINER chạy với quyền mig_scoring nên cần chính sách đọc riêng dưới FORCE RLS.
CREATE POLICY rls_score_job_chunk__den_han ON ${flyway:defaultSchema}.score_job_chunk
    AS PERMISSIVE FOR SELECT TO mig_${flyway:defaultSchema}
    USING (status IN ('CHO', 'DANG_CHAY'));
CREATE POLICY rls_score_job__cho ON ${flyway:defaultSchema}.score_job
    AS PERMISSIVE FOR SELECT TO mig_${flyway:defaultSchema}
    USING (status = 'CHO');

-- Lô đến hạn của mọi tenant: chờ nhận, hoặc đang chạy mà hết hạn thuê (pod chết).
CREATE FUNCTION ${flyway:defaultSchema}.fn_lo_tinh_den_han(p_limit int)
  RETURNS TABLE (id uuid, tenant_id uuid, period_bucket int)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ${flyway:defaultSchema}, pg_temp
AS $$
  SELECT c.id, c.tenant_id, c.period_bucket FROM ${flyway:defaultSchema}.score_job_chunk c
   WHERE c.status IN ('CHO', 'DANG_CHAY') AND c.lease_until <= now()
   ORDER BY c.lease_until, c.seq
   LIMIT least(greatest(p_limit, 1), 500)
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_lo_tinh_den_han(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_lo_tinh_den_han(int) TO svc_${flyway:defaultSchema};

-- Tác vụ chờ bắt đầu của mọi tenant, cũ nhất trước.
CREATE FUNCTION ${flyway:defaultSchema}.fn_tac_vu_tinh_cho(p_limit int)
  RETURNS TABLE (id uuid, tenant_id uuid, period_bucket int)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ${flyway:defaultSchema}, pg_temp
AS $$
  SELECT j.id, j.tenant_id, j.period_bucket FROM ${flyway:defaultSchema}.score_job j
   WHERE j.status = 'CHO'
   ORDER BY j.created_at, j.id
   LIMIT least(greatest(p_limit, 1), 100)
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_tac_vu_tinh_cho(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_tac_vu_tinh_cho(int) TO svc_${flyway:defaultSchema};

-- Số lô còn phải tính của mọi tenant, cho chỉ số co giãn worker (KEDA).
CREATE FUNCTION ${flyway:defaultSchema}.fn_dem_lo_tinh_cho()
  RETURNS bigint
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ${flyway:defaultSchema}, pg_temp
AS $$
  SELECT count(*) FROM ${flyway:defaultSchema}.score_job_chunk c WHERE c.status IN ('CHO', 'DANG_CHAY')
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_dem_lo_tinh_cho() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_dem_lo_tinh_cho() TO svc_${flyway:defaultSchema};

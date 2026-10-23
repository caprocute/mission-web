-- DV-13-06 · SVC-13: lần đồng bộ nhân sự theo yêu cầu của SVC-04 hoặc theo lịch đêm, và chỉ tiêu
-- KT-XH nhận qua LGSP (BR-16-05). Hai bảng bổ sung, TL-03 chưa có (suy luận, ghi ở wiki trang 14):
--   * sync_run giữ trạng thái và số đếm của một lần kéo hết trang (CSDL CBCCVC qua NDXP, HRM) để
--     SVC-04 hỏi cho listOrgSyncRuns và để chạy lại thư chết dưới lần chạy mới; mỗi kết nối chỉ có
--     một lần chạy đang chờ hoặc đang chạy.
--   * ktxh_value giữ giá trị chỉ tiêu KT-XH mới nhất theo kỳ và địa bàn kèm cờ da_cong_bo; kho phân
--     tích đọc bảng này qua CDC (TL-03 "CDC phân tích"), MOD-01 đọc giá trị đã công bố qua cổng số
--     liệu nguồn của KR (ánh xạ CHI_SO). Chỉ tiêu chưa công bố vẫn lưu nhưng không được tính.

-- ---------------------------------------------------------------- sync_run
CREATE TABLE ${flyway:defaultSchema}.sync_run (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    connector_id uuid NOT NULL,
    kind varchar(8) NOT NULL,
    status varchar(9) NOT NULL DEFAULT 'CHO',
    retry_of uuid,
    pages int NOT NULL DEFAULT 0,
    messages int NOT NULL DEFAULT 0,
    duplicates int NOT NULL DEFAULT 0,
    dead_letters int NOT NULL DEFAULT 0,
    error_detail varchar(500),
    requested_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    heartbeat_at timestamptz,
    finished_at timestamptz,
    CONSTRAINT pk_sync_run PRIMARY KEY (id),
    CONSTRAINT fk_sync_run__connector FOREIGN KEY (tenant_id, connector_id)
        REFERENCES ${flyway:defaultSchema}.connector (tenant_id, id),
    CONSTRAINT ck_sync_run__kind CHECK (kind IN ('LICH', 'NGAY', 'CHAY_LAI')),
    CONSTRAINT ck_sync_run__status CHECK (status IN ('CHO', 'DANG_CHAY', 'XONG', 'LOI')),
    CONSTRAINT ck_sync_run__retry CHECK ((kind = 'CHAY_LAI') = (retry_of IS NOT NULL)),
    CONSTRAINT ck_sync_run__counts CHECK (pages >= 0 AND messages >= 0 AND duplicates >= 0 AND dead_letters >= 0),
    CONSTRAINT ck_sync_run__finished CHECK ((status IN ('XONG', 'LOI')) = (finished_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_sync_run__dang_mo ON ${flyway:defaultSchema}.sync_run (tenant_id, connector_id)
    WHERE status IN ('CHO', 'DANG_CHAY');
CREATE INDEX ix_sync_run__connector ON ${flyway:defaultSchema}.sync_run (tenant_id, connector_id, kind, requested_at DESC);
CREATE INDEX ix_sync_run__can_chay ON ${flyway:defaultSchema}.sync_run (requested_at)
    WHERE status IN ('CHO', 'DANG_CHAY');

COMMENT ON TABLE ${flyway:defaultSchema}.sync_run IS 'Lần đồng bộ kéo hết trang của một kết nối (theo lịch, đồng bộ ngay, chạy lại thư chết)';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.id IS '{"cls":"C1","desc":"Mã lần chạy, trùng mã lần chạy ở SVC-04"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.connector_id IS '{"cls":"C1","desc":"Kết nối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.kind IS '{"cls":"C1","desc":"LICH, NGAY hoặc CHAY_LAI"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.status IS '{"cls":"C1","desc":"Trạng thái"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.retry_of IS '{"cls":"C1","desc":"Lần chạy có thư chết được chạy lại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.pages IS '{"cls":"C1","desc":"Số trang đã kéo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.messages IS '{"cls":"C1","desc":"Số thông điệp đã xử lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.duplicates IS '{"cls":"C1","desc":"Số thông điệp trùng bỏ qua"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.dead_letters IS '{"cls":"C1","desc":"Số thư chết của lần chạy"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.error_detail IS '{"cls":"C1","desc":"Loại lỗi khi lần chạy hỏng, không chứa dữ liệu nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.requested_at IS '{"cls":"C1","desc":"Thời điểm yêu cầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.started_at IS '{"cls":"C1","desc":"Thời điểm bắt đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.heartbeat_at IS '{"cls":"C1","desc":"Nhịp sống của worker đang chạy"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_run.finished_at IS '{"cls":"C1","desc":"Thời điểm kết thúc"}';

-- ---------------------------------------------------------------- ktxh_value
CREATE TABLE ${flyway:defaultSchema}.ktxh_value (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    connector_id uuid NOT NULL,
    indicator_code varchar(64) NOT NULL,
    period_code varchar(16) NOT NULL,
    area_code varchar(64) NOT NULL,
    value numeric(24, 6) NOT NULL,
    unit varchar(32),
    da_cong_bo boolean NOT NULL DEFAULT false,
    published_at timestamptz,
    source_updated_at timestamptz,
    received_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_ktxh_value PRIMARY KEY (id),
    CONSTRAINT uq_ktxh_value__ky UNIQUE (tenant_id, connector_id, indicator_code, period_code, area_code),
    CONSTRAINT fk_ktxh_value__connector FOREIGN KEY (tenant_id, connector_id)
        REFERENCES ${flyway:defaultSchema}.connector (tenant_id, id),
    CONSTRAINT ck_ktxh_value__cong_bo CHECK (da_cong_bo = (published_at IS NOT NULL))
);
CREATE INDEX ix_ktxh_value__chi_tieu ON ${flyway:defaultSchema}.ktxh_value (tenant_id, connector_id, indicator_code, published_at DESC)
    WHERE da_cong_bo;

COMMENT ON TABLE ${flyway:defaultSchema}.ktxh_value IS 'Chỉ tiêu KT-XH nhận qua LGSP theo kỳ và địa bàn, kèm cờ đã công bố (BR-16-05)';
COMMENT ON COLUMN ${flyway:defaultSchema}.ktxh_value.id IS '{"cls":"C0"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ktxh_value.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ktxh_value.connector_id IS '{"cls":"C1","desc":"Kết nối LGSP"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ktxh_value.indicator_code IS '{"cls":"C0","desc":"Mã chỉ tiêu ở LGSP"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ktxh_value.period_code IS '{"cls":"C0","desc":"Kỳ số liệu, ví dụ 2026 hoặc 2026-Q3"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ktxh_value.area_code IS '{"cls":"C0","desc":"Mã địa bàn hoặc đơn vị báo cáo ở LGSP"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ktxh_value.value IS '{"cls":"C0","desc":"Giá trị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ktxh_value.unit IS '{"cls":"C0","desc":"Đơn vị tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ktxh_value.da_cong_bo IS '{"cls":"C0","desc":"Số liệu đã công bố chính thức (BR-16-05)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ktxh_value.published_at IS '{"cls":"C0","desc":"Thời điểm công bố"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ktxh_value.source_updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật ở LGSP"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ktxh_value.received_at IS '{"cls":"C1","desc":"Thời điểm nhận"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.sync_run');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.ktxh_value');
-- Lần chạy và số liệu nguồn không xoá; số liệu cũ được ghi đè theo kỳ.
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.sync_run FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.ktxh_value FROM svc_${flyway:defaultSchema};

-- Worker lần chạy cần biết việc của mọi tenant: lần chạy đang chờ, hoặc đang chạy mà worker giữ đã
-- mất nhịp quá p_lease_seconds (bản sao chết giữa chừng). Chỉ trả mã và tenant như các hàm quét V1.
CREATE FUNCTION ${flyway:defaultSchema}.fn_lan_dong_bo_can_chay(p_limit int, p_lease_seconds int)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT r.tenant_id, r.id FROM ${flyway:defaultSchema}.sync_run r
     WHERE r.status = 'CHO'
        OR (r.status = 'DANG_CHAY'
            AND r.heartbeat_at < now() - make_interval(secs => greatest(p_lease_seconds, 30)))
     ORDER BY r.requested_at
     LIMIT least(greatest(p_limit, 1), 100)
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_lan_dong_bo_can_chay(int, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_lan_dong_bo_can_chay(int, int) TO svc_${flyway:defaultSchema};

CREATE POLICY rls_sync_run__quet_can_chay ON ${flyway:defaultSchema}.sync_run AS PERMISSIVE FOR SELECT TO CURRENT_USER
    USING (status IN ('CHO', 'DANG_CHAY'));

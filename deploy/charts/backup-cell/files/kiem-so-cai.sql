-- Kiểm chuỗi băm sổ cái trên cụm khôi phục thử (INF-17; DV-09, BR-13-11; TL-04 bảng lịch sao lưu "kèm verify_run
-- chuỗi băm"): mỗi phân vùng (tenant_id, chain_partition) có seq_no liền nhau, prev_hash bằng entry_hash của bản ghi
-- liền trước, và chain_head trỏ đúng bản ghi cuối. Không tính lại băm (việc của ledger-service, ledger-verify).
SET statement_timeout = 0;
DO $$
DECLARE
  v_dem bigint;
BEGIN
  IF to_regclass('ledger.ledger_entry') IS NULL OR to_regclass('ledger.chain_head') IS NULL THEN
    RAISE NOTICE 'CSDL % chưa có bảng sổ cái, bỏ qua', current_database();
    RETURN;
  END IF;
  SELECT count(*) INTO v_dem
  FROM (
    SELECT seq_no, prev_hash,
           lag(seq_no) OVER w AS seq_truoc,
           lag(entry_hash) OVER w AS hash_truoc
    FROM ledger.ledger_entry
    WINDOW w AS (PARTITION BY tenant_id, chain_partition ORDER BY seq_no)
  ) x
  WHERE seq_truoc IS NOT NULL AND (seq_no <> seq_truoc + 1 OR prev_hash <> hash_truoc);
  IF v_dem > 0 THEN
    RAISE EXCEPTION 'sổ cái: % bản ghi không nối liền với bản ghi liền trước', v_dem;
  END IF;
  SELECT count(*) INTO v_dem
  FROM ledger.chain_head h
  WHERE h.last_seq_no > 0
    AND NOT EXISTS (
      SELECT 1 FROM ledger.ledger_entry e
      WHERE e.tenant_id = h.tenant_id AND e.chain_partition = h.chain_partition
        AND e.seq_no = h.last_seq_no AND e.entry_hash = h.last_hash);
  IF v_dem > 0 THEN
    RAISE EXCEPTION 'sổ cái: % đầu chuỗi không khớp bản ghi cuối', v_dem;
  END IF;
  RAISE NOTICE 'sổ cái %: chuỗi băm nối liền', current_database();
END
$$;

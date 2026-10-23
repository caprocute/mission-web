-- DV-09-05: chỉ mục cho API tra cứu sổ cái (oversight.yaml getLedgerEntry, getLedgerInclusionProof,
-- listLedgerEntries không lọc đối tượng). Khoá chính và uq_ledger_entry__event đều cần recorded_month nên
-- không phục vụ tra theo id, event_id; listLedgerEntries sắp mới nhất trước theo (recorded_at, id).
-- Ba chỉ mục là suy luận từ hợp đồng, TL-03 chỉ khai ix_ledger_entry__aggregate và ix_ledger_entry__chain_seq.
CREATE INDEX ix_ledger_entry__id ON ledger.ledger_entry (tenant_id, id);
CREATE INDEX ix_ledger_entry__event_id ON ledger.ledger_entry (tenant_id, event_id);
CREATE INDEX ix_ledger_entry__recorded ON ledger.ledger_entry (tenant_id, recorded_at DESC, id DESC);

-- DV-09-03: thời điểm TSA cấp dấu thời gian (TL-03 cột tsa_gen_time) và cờ "dấu thời gian trễ" khi
-- TSA lỗi làm cửa sổ được gộp vào lần niêm phong sau (TL-03 "Niêm phong Merkle", TL-02b QT-13).
ALTER TABLE ledger.cell_seal
  ADD COLUMN tsa_gen_time timestamptz,
  ADD COLUMN tsa_late     boolean NOT NULL DEFAULT false,
  ADD COLUMN seal_kind    varchar(16) NOT NULL DEFAULT 'DINH_KY',
  ADD CONSTRAINT ck_cell_seal__kind CHECK (seal_kind IN ('DINH_KY', 'DOT_XUAT'));

COMMENT ON COLUMN ledger.cell_seal.tsa_gen_time IS '{"cls":"C1","desc":"Thời điểm genTime trong token RFC 3161"}';
COMMENT ON COLUMN ledger.cell_seal.tsa_late IS '{"cls":"C1","desc":"Dấu thời gian trễ: cửa sổ gộp do TSA lỗi ở lần trước"}';
COMMENT ON COLUMN ledger.cell_seal.seal_kind IS '{"cls":"C1","desc":"Loại niêm phong: DINH_KY theo lịch, DOT_XUAT khi saga chốt kỳ yêu cầu"}';

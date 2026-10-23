# Ca kiểm thử chọn người xử lý khiếu nại (CORE-07-03, BR-12-04, QT-12 "Ngoại lệ và xử lý").
package okrkpi.complaint_test

import rego.v1

import data.okrkpi.complaint

# object.union gộp đệ quy, nên thay hẳn một khoá bằng with_key.
with_key(k, v) := object.union(object.remove(base, [k]), {k: v})

base := {
	"units": ["u11", "u1", "u0"],
	"appraisers": {"u11": ["tc1", "tc2"], "u1": ["tc9"], "u0": ["tc0"]},
	"participants": {"appraiser": ["tc1"], "acceptor": ["nt1"], "decider": ["ld1"]},
}

test_loai_nguoi_da_tham_dinh if {
	d := complaint.decision with input as base
	d.unit == "u11"
	d.candidates == ["tc2"]
	d.escalated == false
	d.reason == null
}

test_moi_nhom_tham_gia_deu_bi_loai if {
	inp := with_key("participants", {
		"acceptor": ["tc1"],
		"proposer": ["tc2"],
	})
	d := complaint.decision with input as inp
	d.unit == "u1"
	d.candidates == ["tc9"]
}

test_don_vi_nho_nang_cap_tren if {
	inp := with_key("appraisers", {"u11": ["tc1"], "u1": ["tc9", "tc8"]})
	d := complaint.decision with input as inp
	d.unit == "u1"
	d.candidates == ["tc8", "tc9"]
	d.escalated == true
	d.reason == "NANG_CAP_TREN"
}

test_bo_qua_cap_tren_khong_co_ung_vien if {
	inp := with_key("appraisers", {"u11": ["tc1"], "u1": ["ld1"], "u0": ["tc0"]})
	d := complaint.decision with input as inp
	d.unit == "u0"
	d.candidates == ["tc0"]
}

test_khong_co_ung_vien if {
	inp := with_key("appraisers", {"u11": ["tc1"]})
	d := complaint.decision with input as inp
	d.unit == null
	d.candidates == []
	d.reason == "KHONG_CO_UNG_VIEN"
	d.allowed == false
}

test_nguoi_goi_hop_le if {
	d := complaint.decision with input as object.union(base, {"subject_id": "tc2"})
	d.allowed == true
	d.conflicts == []
}

test_nguoi_goi_da_tham_dinh_bi_tu_choi if {
	d := complaint.decision with input as object.union(base, {"subject_id": "tc1"})
	d.allowed == false
	d.conflicts == ["appraiser"]
}

test_nguoi_goi_ngoai_cap_xu_ly_bi_tu_choi if {
	d := complaint.decision with input as object.union(base, {"subject_id": "tc9"})
	d.allowed == false
	d.conflicts == []
}

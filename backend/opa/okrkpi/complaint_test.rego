# Ca kiểm thử của okrkpi.complaint (CORE-07-03): loại người đã tham gia hồ sơ, nâng cấp trên khi đơn vị
# nhỏ chỉ có một cán bộ tổ chức đã thẩm định, không còn ai ở mọi cấp.
package okrkpi.complaint_test

import rego.v1

import data.okrkpi.complaint

participants := {
	"acceptor": ["nt1"],
	"appraiser": ["td1"],
	"proposer": ["dx1"],
	"decider": ["qd1"],
}

test_loai_nguoi_da_tham_gia if {
	r := complaint with input as {
		"levels": [["td1", "ha1", "nt1", "kn1"]],
		"participants": participants,
		"complainant": "kn1",
	}
	r.candidates == ["ha1"]
	r.level == 0
	not r.escalated
}

test_don_vi_nho_nang_cap_tren if {
	r := complaint with input as {
		"levels": [["td1"], ["qd1", "ct2", "ct3"]],
		"participants": participants,
	}
	r.candidates == ["ct2", "ct3"]
	r.level == 1
	r.escalated
}

test_khong_con_ai if {
	r := complaint with input as {
		"levels": [["td1"], ["dx1"]],
		"participants": participants,
	}
	r.candidates == []
	r.level == -1
	not r.escalated
}

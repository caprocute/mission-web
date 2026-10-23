# Ca kiểm thử viết tay cho hai lời gọi đọc dùng chung của mission-core, dựng input đúng như PermissionInterceptor
# gửi: GET /api/v1/periods (listPeriods, getPeriod) chỉ xét dòng API `periods` của ma trận, không gắn chức năng;
# GET /api/v1/catalog/items (listApplicableCatalogItems) gắn chức năng SCR-0401.xem phạm vi đơn vị kèm dòng API
# `catalog`. Trước đây hai lời gọi gắn SCR-0801.xem và SCR-0503.xem nên cán bộ bị từ chối (QA-05, QA-06).
package okrkpi.doc_chung_test

import rego.v1

import data.okrkpi.authz

now := 10000

nguoi(roles, acr) := {
	"kind": "user",
	"user_id": "nv1",
	"tenant_id": "t1",
	"unit_id": "u1",
	"roles": roles,
	"acr": acr,
	"auth_time": now,
	"flows": ["DANG", "NHA_NUOC"],
}

doc_ky(roles, acr) := d if {
	d := authz.decision with input as {
		"subject": nguoi(roles, acr),
		"request": {"module": "periods", "method": "GET"},
		"resource": {"type": "period", "tenant_id": "t1", "owner_unit_id": "u1", "classification": "C1"},
		"context": {"now": now},
	}
}

doc_danh_muc(roles, don_vi) := d if {
	d := authz.decision with input as {
		"subject": nguoi(roles, "aal1"),
		"action": "SCR-0401.xem",
		"request": {"module": "catalog", "method": "GET"},
		"resource": {"type": "catalog_item", "tenant_id": "t1", "owner_unit_id": don_vi, "classification": "C1"},
		"context": {"now": now},
	}
}

# ------------------------------------------------------------------ Danh sách kỳ: mọi vai trò nghiệp vụ đọc được

test_doc_ky_vai_tro_aal1 if {
	every r in ["member", "manager", "leader", "inspector"] {
		doc_ky([r], "aal1").allow
	}
}

test_doc_ky_vai_tro_aal2 if {
	every r in ["tenant_admin", "appraiser", "decider", "second_approver"] {
		doc_ky([r], "aal2").allow
	}
}

# Thẩm định viên chưa bước lên vẫn đọc được kỳ nhờ vai trò cán bộ đi kèm.
test_doc_ky_tham_dinh_kem_can_bo if doc_ky(["member", "appraiser"], "aal1").allow

test_doc_ky_vai_tro_ngoai_dong_api if {
	every r in ["policy_author", "ai_readonly"] {
		d := doc_ky([r], "aal1")
		not d.allow
		"role_not_allowed" in d.deny
	}
}

test_doc_ky_khac_tenant if {
	d := authz.decision with input as {
		"subject": nguoi(["member"], "aal1"),
		"request": {"module": "periods", "method": "GET"},
		"resource": {"type": "period", "tenant_id": "t2", "classification": "C1"},
		"context": {"now": now},
	}
	not d.allow
	"out_of_scope" in d.deny
}

# ------------------------------------------------------------------ Danh mục áp dụng: phạm vi đơn vị

test_doc_danh_muc_don_vi_minh if {
	every r in ["member", "manager", "leader"] {
		doc_danh_muc([r], "u1").allow
	}
}

test_doc_danh_muc_don_vi_khac if {
	every r in ["member", "manager"] {
		d := doc_danh_muc([r], "u2")
		not d.allow
		"out_of_scope" in d.deny
	}
}

test_doc_danh_muc_vai_tro_khong_co_quyen if {
	every r in ["appraiser", "decider", "inspector"] {
		d := doc_danh_muc([r], "u1")
		not d.allow
		"role_not_allowed" in d.deny
	}
}

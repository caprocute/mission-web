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

# Quản trị nghiệp vụ (policy_author) đọc được kỳ từ 23/10/2026, xem ca ở cuối tệp.
test_doc_ky_vai_tro_ngoai_dong_api if {
	every r in ["platform_admin", "ai_readonly"] {
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

# ------------------------------------------------------------------ Quản trị nghiệp vụ (AC-03) đọc dữ liệu dùng chung
# GET /ui/layouts/home, /ui/feature-flags, /ui/packs/current gắn dòng API `ui` (trước đây gắn `config` nên AC-03 bị
# từ chối); GET /config/anomaly-thresholds và GET /periods là ô suy luận của AC-03 (đọc trước khi sửa ngưỡng,
# chọn kỳ áp dụng khi gửi duyệt bộ quy định, BR-15-10), QA-05 23/10/2026.

doc_api(roles, acr, module) := d if {
	d := authz.decision with input as {
		"subject": nguoi(roles, acr),
		"request": {"module": module, "method": "GET"},
		"resource": {"type": module, "tenant_id": "t1", "owner_unit_id": "u1", "classification": "C1"},
		"context": {"now": now},
	}
}

test_quan_tri_nghiep_vu_doc_trang_chu_co_ky_nguong if {
	every m in ["ui", "config", "periods"] {
		doc_api(["policy_author"], "aal1", m).allow
	}
}

test_quan_tri_nghiep_vu_khong_ghi_ky if {
	d := authz.decision with input as {
		"subject": nguoi(["policy_author"], "aal1"),
		"request": {"module": "periods", "method": "POST"},
		"resource": {"type": "period", "tenant_id": "t1", "owner_unit_id": "u1", "classification": "C1"},
		"context": {"now": now},
	}
	not d.allow
	"role_not_allowed" in d.deny
}

# Quản trị nền tảng đọc được giao diện trang chủ (ô suy luận) nhưng không đọc kỳ của tenant.
test_quan_tri_nen_tang_doc_giao_dien_khong_doc_ky if {
	doc_api(["platform_admin"], "aal2", "ui").allow
	not doc_api(["platform_admin"], "aal2", "periods").allow
}

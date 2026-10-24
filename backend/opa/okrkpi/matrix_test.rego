# Bộ chạy ca kiểm thử sinh từ ma trận (matrix_cases_test.rego). Viết tay; chỉ dựng đầu vào theo phạm vi
# của từng ô, không đọc lại data.okrkpi.generated để ca kiểm thử không tự khẳng định chính nó (NEN-040).
package okrkpi.authz_matrix_test

import rego.v1

import data.okrkpi.authz

org_fixture := {"t1": {"subtree": {
	"u1": ["u1", "u11", "u12"],
	"u11": ["u11"],
	"u2": ["u2"],
}}}

client_role := "(client credentials)"

subject_for(role) := {"kind": "client", "client_id": "voffice", "tenant_id": "t1"} if role == client_role

else := {
	"user_id": "nv1",
	"tenant_id": "t1",
	"unit_id": "u1",
	"roles": [role],
	"acr": "aal2",
	"auth_time": 1000,
	"flows": ["DANG", "NHA_NUOC"],
}

# Tài nguyên nằm đúng phạm vi của ô (không rộng hơn cần thiết).
in_scope_resource("B") := {"tenant_id": "t1", "owner_unit_id": "u1", "owner_ids": ["nv1"]}

in_scope_resource("Gn") := {"tenant_id": "t1", "owner_unit_id": "u2", "designated_ids": ["nv1"]}

in_scope_resource("Đv") := {"tenant_id": "t1", "owner_unit_id": "u1"}

in_scope_resource("Cd") := {"tenant_id": "t1", "owner_unit_id": "u11"}

in_scope_resource("Tn") := {"tenant_id": "t1", "owner_unit_id": "u2"}

in_scope_resource("N") := {"tenant_id": "t1", "owner_unit_id": "u2"}

in_scope_resource(null) := {"tenant_id": "t1", "owner_unit_id": "u2"}

# Tài nguyên vừa ra ngoài phạm vi của ô.
out_of_scope_resource("B") := {"tenant_id": "t1", "owner_unit_id": "u1", "owner_ids": ["nv2"]}

out_of_scope_resource("Gn") := {"tenant_id": "t1", "owner_unit_id": "u1", "designated_ids": ["nv2"]}

out_of_scope_resource("Đv") := {"tenant_id": "t1", "owner_unit_id": "u11"}

out_of_scope_resource("Cd") := {"tenant_id": "t1", "owner_unit_id": "u2"}

out_of_scope_resource("Tn") := {"tenant_id": "t2", "owner_unit_id": "u1"}

# Tài nguyên rộng nhất trong tenant: ca role_not_allowed không thể thất bại vì phạm vi.
widest_resource := {"tenant_id": "t1", "owner_unit_id": "u1", "owner_ids": ["nv1"], "designated_ids": ["nv1"]}

resource_for(c) := object.union(in_scope_resource(c.scope), {"classification": "C1"}) if c.expect == "allow"

resource_for(c) := object.union(out_of_scope_resource(c.scope), {"classification": "C1"}) if c.expect == "out_of_scope"

resource_for(c) := object.union(widest_resource, {"classification": "C1"}) if c.expect == "role_not_allowed"

function_decision(c) := d if {
	d := authz.decision with input as {
		"subject": subject_for(c.role),
		"action": c.key,
		"resource": resource_for(c),
		"context": {"now": 1000},
	}
		with data.org as org_fixture
}

api_decision(c) := d if {
	d := authz.decision with input as {
		"subject": subject_for(c.role),
		"request": {"module": c.module, "method": c.method},
		"resource": {"tenant_id": "t1", "owner_unit_id": "u1"},
		"context": {"now": 1000},
	}
		with data.org as org_fixture
}

decision_ok(c, d) if {
	c.expect == "allow"
	d.allow == true
	count(d.deny) == 0
	inferred_flag_ok(c, d)
}

decision_ok(c, d) if {
	c.expect != "allow"
	d.allow == false
	c.expect in d.deny
}

inferred_flag_ok(c, d) if {
	c.inferred
	"inferred_grant" in d.flags
}

inferred_flag_ok(c, d) if {
	not c.inferred
	not "inferred_grant" in d.flags
}

function_failures(group) := {c |
	some c in function_cases[group]
	not decision_ok(c, function_decision(c))
}

api_failures(mod) := {c |
	some c in api_cases[mod]
	not decision_ok(c, api_decision(c))
}

# Mỗi nhóm QT là một test (sinh ở matrix_cases_test.rego) để không vượt thời hạn mặc định của `opa test`.
function_group_ok(group) if {
	count(function_cases[group]) > 0
	failures := function_failures(group)
	print("ca chức năng sai:", failures)
	count(failures) == 0
}

test_matrix_has_every_function_group if count(function_cases) >= 19

# Mỗi module API là một test, cùng lý do thời hạn.
api_group_ok(mod) if {
	count(api_cases[mod]) > 0
	failures := api_failures(mod)
	print("ca API sai:", failures)
	count(failures) == 0
}

test_matrix_has_every_api_module if count(api_cases) >= 20

# METADATA
# title: Quy tắc đánh giá từng phần (Partial Evaluation) cho truy vấn danh sách (NEN-014, ADR-21)
# description: |
#   Biểu diễn phẳng (DNF, không dùng hàm tổng hợp count hay object.get trên gốc input) dựa trên
#   data.okrkpi.generated của NEN-040 để OPA POST /v1/compile với unknowns = ["input.resource"]
#   rút gọn thành các biểu thức nguyên tử trên input.resource.* và dịch sang mệnh đề SQL.
package okrkpi_partial

import rego.v1

gen := data.okrkpi.generated

held_roles contains gen.client_role if input.subject.kind == "client"

held_roles contains r if {
	input.subject.kind != "client"
	some r in input.subject.roles
	gen.roles[r].kind != "client"
}

effective_roles contains r if {
	some r in held_roles
	not gen.roles[r].aal2
}

effective_roles contains r if {
	some r in held_roles
	gen.roles[r].aal2
	input.subject.acr == gen.params.aal2_acr
}

fn := gen.functions[input.action]

fn_effective contains r if {
	some r in effective_roles
	fn.grants[r]
}

context_units contains input.subject.unit_id if {
	is_string(input.subject.unit_id)
	input.subject.unit_id != ""
}

context_units contains u if {
	not is_string(input.subject.unit_id)
	some u in data.org[input.subject.tenant_id].managed_units[input.subject.user_id]
}

scope_ok("N")

scope_ok("Tn") if {
	input.resource.tenant_id == input.subject.tenant_id
}

scope_ok("Đv") if {
	input.resource.tenant_id == input.subject.tenant_id
	some u in context_units
	input.resource.owner_unit_id == u
}

scope_ok("Cd") if {
	input.resource.tenant_id == input.subject.tenant_id
	some u in context_units
	input.resource.ancestor_unit_id == u
}

scope_ok("B") if {
	input.resource.tenant_id == input.subject.tenant_id
	input.resource.owner_id == input.subject.user_id
}

scope_ok("B") if {
	input.resource.tenant_id == input.subject.tenant_id
	input.resource.assignee_id == input.subject.user_id
}

scope_ok("Gn") if {
	input.resource.tenant_id == input.subject.tenant_id
	input.resource.designated_id == input.subject.user_id
}

# Cd/Đv xét theo đơn vị gắn vai trò (data.org[tenant].role_units do mission-core nạp), như authz.rego; người dùng
# chưa có dữ liệu vai trò thì giữ cách cũ theo đơn vị của phân công (QA-06, wiki 14).
role_bound := data.org[input.subject.tenant_id].role_units[input.subject.user_id]

allow if {
	some r in fn_effective
	g := fn.grants[r]
	not g.scope in {"Cd", "Đv"}
	scope_ok(g.scope)
}

allow if {
	some r in fn_effective
	g := fn.grants[r]
	g.scope in {"Cd", "Đv"}
	not role_bound[r]
	scope_ok(g.scope)
}

allow if {
	some r in fn_effective
	g := fn.grants[r]
	g.scope in {"Cd", "Đv"}
	input.resource.tenant_id == input.subject.tenant_id
	some u in role_bound[r].unit
	input.resource.owner_unit_id == u
}

allow if {
	some r in fn_effective
	fn.grants[r].scope == "Cd"
	input.resource.tenant_id == input.subject.tenant_id
	some x in role_bound[r].subtree
	input.resource.ancestor_unit_id == x
}

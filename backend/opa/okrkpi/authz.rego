# METADATA
# title: Quyết định uỷ quyền tập trung (NEN-040, ADR-21)
# description: |
#   RBAC theo ma trận phân quyền sinh ra ở data.okrkpi.generated (docs/phan-quyen/ma-tran.json),
#   ABAC theo cây tổ chức và phạm vi (B, Gn, Đv, Cd, Tn, N), luồng DANG/NHA_NUOC, mức dữ liệu C0..C3,
#   tách vai trò, bốn mắt và xác thực bước lên. Mặc định từ chối; service chỉ cho phép khi
#   decision.allow đúng (allow đã gồm điều kiện deny rỗng). Hợp đồng đầu vào: backend/opa/README.md.
# related_resources:
#   - ref: https://www.openpolicyagent.org/docs/latest/policy-language/
package okrkpi.authz

import rego.v1

gen := data.okrkpi.generated

subject := object.get(input, "subject", {})

resource := object.get(input, "resource", {})

participants := object.get(resource, "participants", {})

subject_kind := object.get(subject, "kind", "user")

# Giây Unix hiện tại; service truyền input.context.now để quyết định tái lập được.
now_s := input.context.now if is_number(input.context.now)

else := time.now_ns() / 1000000000

# ------------------------------------------------------------------ Vai trò

# AC-11 (client credentials) không có realm role: chủ thể kind = "client" mang đúng vai trò của ma trận.
held_roles contains gen.client_role if subject_kind == "client"

held_roles contains r if {
	subject_kind != "client"
	some r in object.get(subject, "roles", [])
	gen.roles[r].kind != "client"
}

aal2_session if subject.acr == gen.params.aal2_acr

# BR-00-01: vai trò AAL2 chỉ có hiệu lực trong phiên AAL2.
effective_roles contains r if {
	some r in held_roles
	not gen.roles[r].aal2
}

effective_roles contains r if {
	some r in held_roles
	gen.roles[r].aal2
	aal2_session
}

# Bước lên còn mới: phiên AAL2 và auth_time không quá params.step_up_max_age_seconds.
fresh_step_up if {
	aal2_session
	is_number(subject.auth_time)
	now_s - subject.auth_time <= gen.params.step_up_max_age_seconds
}

# ------------------------------------------------------------------ Phạm vi (ABAC)

# Cây tổ chức theo tenant do CORE-00-03 nạp (định dạng ở README); thiếu dữ liệu thì Cd/Đv thất bại (fail closed).
org := data.org[subject.tenant_id]

same_tenant if {
	is_string(subject.tenant_id)
	resource.tenant_id == subject.tenant_id
}

# Đơn vị của phân công đang chọn; không có thì lấy các đơn vị người dùng quản lý (TL-01 mẫu okrkpi.task).
assignment_unit := object.get(subject, "unit_id", null)

context_units contains assignment_unit if is_string(assignment_unit)

context_units contains u if {
	not is_string(assignment_unit)
	some u in org.managed_units[subject.user_id]
}

subtree_units contains u if some u in context_units

subtree_units contains v if {
	some u in context_units
	some v in org.subtree[u]
}

owner_ids := array.concat(object.get(resource, "owner_ids", []), object.get(resource, "assignee_ids", []))

designated_ids := array.concat(object.get(resource, "designated_ids", []), object.get(resource, "delegate_ids", []))

scope_match("N")

scope_match("Tn") if same_tenant

scope_match("Cd") if {
	same_tenant
	resource.owner_unit_id in subtree_units
}

scope_match("Đv") if {
	same_tenant
	resource.owner_unit_id in context_units
}

scope_match("B") if {
	same_tenant
	subject.user_id in owner_ids
}

scope_match("Gn") if {
	same_tenant
	subject.user_id in designated_ids
}

# H: hệ thống ngoài qua integration-gateway; A: trợ lý AI dưới danh tính người dùng (lọc dữ liệu ở service).
grant_in_scope(g) if {
	g.level == "H"
	subject_kind == "client"
	same_tenant
}

grant_in_scope(g) if {
	g.level == "A"
	same_tenant
}

grant_in_scope(g) if {
	not g.level in {"A", "H"}
	scope_match(g.scope)
}

# Luồng DANG/NHA_NUOC: đối tượng có luồng thì phân công phải cho phép luồng đó.
flow_ok if not resource.flow

flow_ok if resource.flow in object.get(subject, "flows", [])

# ------------------------------------------------------------------ Chức năng (input.action)

has_action if is_string(input.action)

has_request if {
	is_string(input.request.module)
	is_string(input.request.method)
}

fn := gen.functions[input.action]

fn_grant_roles contains r if {
	some r in held_roles
	fn.grants[r]
}

fn_effective contains r if {
	some r in effective_roles
	fn.grants[r]
}

fn_in_scope contains r if {
	some r in fn_effective
	grant_in_scope(fn.grants[r])
}

fn_ok if {
	count(fn_in_scope) > 0
	flow_ok
	not hidden_from_subject
}

fn_constraints contains c if {
	some id in fn.constraints
	c := gen.constraints[id]
	constraint_applies(c)
}

constraint_applies(c) if {
	not c.when_classification
	not c.when_delegate
}

constraint_applies(c) if c.when_classification == resource.classification

constraint_applies(c) if {
	c.when_delegate
	subject.user_id in object.get(resource, "delegate_ids", [])
}

subject_in_any(groups) if {
	some g in groups
	subject.user_id in object.get(participants, g, [])
}

hidden_from_subject if {
	some c in fn_constraints
	c.kind == "hidden_from_subject"
	subject.user_id in object.get(participants, c.participant, [])
	not resource.concluded == true
}

# Ngoại lệ đơn vị nhỏ (TL-02a "Nguyên tắc tách vai trò"): service đặt cờ theo tham số Policy Pack.
small_unit_exception(c) if {
	c.exception == "small_unit"
	resource.small_unit_exception == true
}

sod_violation contains c if {
	some c in fn_constraints
	c.kind == "separation_of_duties"
	subject_in_any(c.subject_not_in)
}

# ------------------------------------------------------------------ API (input.request)

api_row := gen.api[input.request.module]

api_grant_roles contains r if {
	some r in held_roles
	input.request.method in api_row.roles[r].methods
}

api_effective contains r if {
	some r in effective_roles
	input.request.method in api_row.roles[r].methods
}

api_tenant_ok if not resource.tenant_id

api_tenant_ok if same_tenant

api_tenant_ok if {
	some r in api_effective
	gen.roles[r].default_scope == "N"
}

api_ok if {
	count(api_effective) > 0
	api_tenant_ok
}

# ------------------------------------------------------------------ Mức dữ liệu

granting_roles contains r if some r in fn_in_scope

granting_roles contains r if some r in api_effective

class_role(r) := gen.roles[r].parent

class_role(r) := r if not gen.roles[r].parent

class_allowed(cls) if {
	some r in granting_roles
	gen.classification[class_role(r)][cls].allowed
}

# ------------------------------------------------------------------ Lý do từ chối

deny contains "role_not_allowed" if {
	not has_action
	not has_request
}

deny contains "role_not_allowed" if {
	has_action
	count(fn_grant_roles) == 0
}

deny contains "role_not_allowed" if {
	has_request
	count(api_grant_roles) == 0
}

# Có vai trò AAL2 cấp quyền nhưng phiên chưa AAL2.
deny contains "step_up_required" if {
	has_action
	count(fn_grant_roles) > 0
	count(fn_effective) == 0
}

deny contains "step_up_required" if {
	has_request
	count(api_grant_roles) > 0
	count(api_effective) == 0
}

deny contains "step_up_required" if {
	some c in fn_constraints
	c.kind == "step_up"
	not fresh_step_up
}

deny contains "step_up_required" if {
	some c in fn_constraints
	c.kind == "aal2_session"
	not aal2_session
}

deny contains "out_of_scope" if {
	count(fn_effective) > 0
	count(fn_in_scope) == 0
}

deny contains "out_of_scope" if {
	count(fn_in_scope) > 0
	not flow_ok
}

deny contains "out_of_scope" if hidden_from_subject

deny contains "out_of_scope" if {
	count(api_effective) > 0
	not api_tenant_ok
}

deny contains "separation_of_duties" if {
	some c in sod_violation
	not small_unit_exception(c)
}

deny contains "separation_of_duties" if {
	some c in fn_constraints
	c.kind == "disjoint"
	some p in object.get(participants, c.participant, [])
	some g in c.not_in
	p in object.get(participants, g, [])
}

deny contains "four_eyes_required" if {
	some c in fn_constraints
	c.kind == "four_eyes"
	subject_in_any(c.subject_not_in)
}

# C3 chỉ cho người nhận, bất kể vai trò (TL-01 mẫu okrkpi.task).
deny contains "c3_not_recipient" if {
	resource.classification == "C3"
	not subject.user_id in object.get(resource, "recipient_ids", [])
}

# Trợ lý AI không bao giờ nhận C3 (BR-17-07, BR-14-06).
deny contains "classification_denied" if {
	resource.classification == "C3"
	"ai_readonly" in granting_roles
}

deny contains "classification_denied" if {
	resource.classification in {"C1", "C2"}
	count(granting_roles) > 0
	not class_allowed(resource.classification)
}

# ------------------------------------------------------------------ Cờ cho service ghi vết

flags contains "inferred_grant" if {
	count(fn_in_scope) > 0
	every r in fn_in_scope {
		fn.grants[r].inferred
	}
}

flags contains "inferred_grant" if {
	count(api_effective) > 0
	every r in api_effective {
		input.request.method in api_row.roles[r].inferred_methods
	}
}

flags contains "sod_small_unit_exception" if {
	some c in sod_violation
	small_unit_exception(c)
}

flags contains c.flag if {
	some c in fn_constraints
	c.kind == "obligation"
}

flags contains c.flag if {
	some c in fn_constraints
	c.kind == "sensitive_role_grant"
	resource.granted_role in c.roles
}

flags contains "ai_on_behalf_of_user" if "ai_readonly" in granting_roles

# ------------------------------------------------------------------ Quyết định

granted if {
	has_action
	not has_request
	fn_ok
}

granted if {
	has_request
	not has_action
	api_ok
}

granted if {
	has_action
	has_request
	fn_ok
	api_ok
}

default allow := false

allow if {
	granted
	count(deny) == 0
}

decision := {"allow": allow, "deny": deny, "flags": flags}

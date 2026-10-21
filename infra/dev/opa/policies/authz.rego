# NEN-020 · ADR-05 · TL-01 "Mô hình phân quyền bốn lớp" (RBAC + ABAC qua OPA 1.x).
# Chính sách Rego khởi tạo cho môi trường dev; NEN-023 bổ sung đầy đủ ma trận phân quyền.
package okrkpi.authz

import rego.v1

default allow := false

default reason := "DENY_BY_DEFAULT"

# Cho phép quản trị hệ thống hoặc khi chủ thể khớp tenant_id của tài nguyên trong môi trường dev
allow if {
	"SYS_ADMIN" in input.subject.roles
}

allow if {
	input.subject.tenant_id != ""
	input.subject.tenant_id == input.resource.tenant_id
}

reason := "ALLOW_SYS_ADMIN" if {
	"SYS_ADMIN" in input.subject.roles
}

reason := "ALLOW_SAME_TENANT" if {
	not "SYS_ADMIN" in input.subject.roles
	input.subject.tenant_id != ""
	input.subject.tenant_id == input.resource.tenant_id
}

decision := {
	"allow": allow,
	"reason": reason,
}

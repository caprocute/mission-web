# Ca kiểm thử viết tay cho ABAC và ràng buộc của okrkpi.authz (NEN-040): cây tổ chức, luồng, mức dữ liệu,
# AAL2, bước lên, tách vai trò, bốn mắt, ngoại lệ đơn vị nhỏ, cờ nghĩa vụ, hệ thống ngoài, trợ lý AI.
package okrkpi.authz_test

import rego.v1

import data.okrkpi.authz

org := {"t1": {
	"subtree": {
		"u1": ["u1", "u11", "u12"],
		"u11": ["u11"],
		"u2": ["u2"],
	},
	"managed_units": {"ld9": ["u1"]},
}}

now := 10000

user(roles) := {
	"user_id": "nv1",
	"tenant_id": "t1",
	"unit_id": "u1",
	"roles": roles,
	"acr": "aal2",
	"auth_time": now,
	"flows": ["DANG"],
}

decide(subject, action, resource) := d if {
	d := authz.decision with input as {
		"subject": subject,
		"action": action,
		"resource": object.union({"tenant_id": "t1", "classification": "C1"}, resource),
		"context": {"now": now},
	}
		with data.org as org
}

# ------------------------------------------------------------------ Mặc định từ chối

test_empty_input_denied if {
	d := authz.decision with input as {}
	d.allow == false
	"role_not_allowed" in d.deny
}

test_unknown_action_denied if {
	d := decide(user(["leader"]), "SCR-9999.khong-co", {"owner_unit_id": "u1"})
	d.allow == false
	d.deny == {"role_not_allowed"}
}

test_unknown_role_ignored if {
	d := decide(user(["LANH_DAO"]), "SCR-0203.duyet", {"owner_unit_id": "u1"})
	"role_not_allowed" in d.deny
}

test_client_role_name_not_accepted_from_user_token if {
	d := decide(user(["(client credentials)"]), "SCR-0502.nhan-van-ban", {"owner_unit_id": "u1"})
	"role_not_allowed" in d.deny
}

# ------------------------------------------------------------------ Cây tổ chức và phạm vi

test_leader_reads_subtree if {
	d := decide(user(["leader"]), "SCR-0203.xem", {"owner_unit_id": "u12"})
	d.allow
}

test_leader_cannot_read_sibling_unit if {
	d := decide(user(["leader"]), "SCR-0203.xem", {"owner_unit_id": "u2"})
	d.allow == false
	d.deny == {"out_of_scope"}
}

test_missing_org_tree_fails_closed if {
	d := authz.decision with input as {
		"subject": user(["leader"]),
		"action": "SCR-0203.xem",
		"resource": {"tenant_id": "t1", "owner_unit_id": "u12"},
		"context": {"now": now},
	}
		with data.org as {}
	d.allow == false
	"out_of_scope" in d.deny
}

test_managed_units_used_when_no_assignment_unit if {
	s := object.union(object.remove(user(["leader"]), ["unit_id"]), {"user_id": "ld9"})
	d := decide(s, "SCR-0203.xem", {"owner_unit_id": "u11"})
	d.allow
}

test_unit_scope_is_own_unit_only if {
	ok := decide(user(["manager"]), "SCR-0501.soan", {"owner_unit_id": "u1"})
	ok.allow
	ko := decide(user(["manager"]), "SCR-0501.soan", {"owner_unit_id": "u11"})
	ko.deny == {"out_of_scope"}
}

test_self_scope_uses_assignee_ids if {
	d := decide(user(["member"]), "SCR-0503.nhan-viec", {"owner_unit_id": "u2", "assignee_ids": ["nv1"]})
	d.allow
}

test_cross_tenant_denied if {
	d := decide(user(["tenant_admin"]), "SCR-0103.xem", {"tenant_id": "t2"})
	d.deny == {"out_of_scope"}
}

test_designated_scope_accepts_delegate if {
	d := decide(user(["manager"]), "SCR-0703.mo", {"owner_unit_id": "u2", "delegate_ids": ["nv1"]})
	d.allow
}

# ------------------------------------------------------------------ Luồng DANG / NHA_NUOC

test_flow_of_assignment_allowed if {
	d := decide(user(["appraiser"]), "SCR-0902.xem", {"owner_unit_id": "u11", "flow": "DANG"})
	d.allow
}

test_flow_not_in_assignment_denied if {
	d := decide(user(["appraiser"]), "SCR-0902.xem", {"owner_unit_id": "u11", "flow": "NHA_NUOC"})
	d.deny == {"out_of_scope"}
}

# ------------------------------------------------------------------ Mức dữ liệu

test_c3_requires_recipient if {
	ko := decide(user(["leader"]), "SCR-0203.xem", {"owner_unit_id": "u1", "classification": "C3"})
	ko.deny == {"c3_not_recipient"}
	ok := decide(user(["leader"]), "SCR-0203.xem", {
		"owner_unit_id": "u1",
		"classification": "C3",
		"recipient_ids": ["nv1"],
	})
	ok.allow
}

test_c2_denied_for_role_without_c2 if {
	d := decide(user(["policy_author"]), "SCR-1501.soan", {"classification": "C2"})
	d.deny == {"classification_denied"}
}

test_c2_allowed_for_role_with_c2 if {
	d := decide(user(["appraiser"]), "SCR-0902.xem", {"owner_unit_id": "u11", "classification": "C2"})
	d.allow
	"c2_access_log" in d.flags
}

test_c0_always_readable_by_granted_role if {
	d := decide(user(["policy_author"]), "SCR-1501.soan", {"classification": "C0"})
	d.allow
}

test_sub_role_uses_parent_classification if {
	d := decide(user(["wf_template_author"]), "SCR-1505.soan", {"classification": "C2"})
	d.deny == {"classification_denied"}
}

# ------------------------------------------------------------------ AAL2 và bước lên

test_aal2_role_inactive_in_aal1_session if {
	s := object.union(user(["appraiser"]), {"acr": "aal1"})
	d := decide(s, "SCR-0902.xem", {"owner_unit_id": "u11"})
	d.allow == false
	d.deny == {"step_up_required"}
}

test_non_aal2_role_works_in_aal1_session if {
	s := object.union(user(["leader"]), {"acr": "aal1"})
	d := decide(s, "SCR-0203.xem", {"owner_unit_id": "u1"})
	d.allow
}

test_step_up_fresh_allowed if {
	d := decide(user(["leader"]), "SCR-0403.duyet-ky", {"owner_unit_id": "u1"})
	d.allow
}

test_step_up_stale_denied if {
	s := object.union(user(["leader"]), {"auth_time": now - 301})
	d := decide(s, "SCR-0403.duyet-ky", {"owner_unit_id": "u1"})
	d.deny == {"step_up_required"}
}

test_step_up_requires_aal2 if {
	s := object.union(user(["leader"]), {"acr": "aal1"})
	d := decide(s, "SCR-0403.duyet-ky", {"owner_unit_id": "u1"})
	d.deny == {"step_up_required"}
}

test_step_up_without_auth_time_denied if {
	s := object.remove(user(["leader"]), ["auth_time"])
	d := decide(s, "SCR-0403.duyet-ky", {"owner_unit_id": "u1"})
	d.deny == {"step_up_required"}
}

test_aal2_session_constraint if {
	s := object.union(user(["member"]), {"acr": "aal1"})
	d := decide(s, "SCR-0901.ky-nop", {"owner_unit_id": "u1", "owner_ids": ["nv1"]})
	d.deny == {"step_up_required"}
	ok := decide(object.union(user(["member"]), {"auth_time": 1}), "SCR-0901.ky-nop", {
		"owner_unit_id": "u1",
		"owner_ids": ["nv1"],
	})
	ok.allow
}

test_step_up_only_for_c2_export if {
	s := object.union(user(["leader"]), {"auth_time": 1})
	c1 := decide(s, "SCR-1603.xuat", {"owner_unit_id": "u1"})
	c1.allow
	c2 := decide(s, "SCR-1603.xuat", {"owner_unit_id": "u1", "classification": "C2"})
	c2.deny == {"step_up_required"}
}

test_now_defaults_to_clock if {
	d := authz.decision with input as {
		"subject": object.union(user(["leader"]), {"auth_time": 1}),
		"action": "SCR-0403.duyet-ky",
		"resource": {"tenant_id": "t1", "owner_unit_id": "u1"},
	}
		with data.org as org
	d.deny == {"step_up_required"}
}

# ------------------------------------------------------------------ Tách vai trò

test_okr_approver_not_author if {
	d := decide(user(["leader"]), "SCR-0203.duyet", {"owner_unit_id": "u1", "participants": {"initiator": ["nv1"]}})
	d.deny == {"separation_of_duties"}
	ok := decide(user(["leader"]), "SCR-0203.duyet", {"owner_unit_id": "u1", "participants": {"initiator": ["nv2"]}})
	ok.allow
}

test_acceptor_not_submitter if {
	d := decide(user(["manager"]), "SCR-0703.xac-nhan", {
		"designated_ids": ["nv1"],
		"participants": {"submitter": ["nv1"]},
	})
	d.deny == {"separation_of_duties"}
}

test_small_unit_exception_allows_with_flag if {
	d := decide(user(["manager"]), "SCR-0703.xac-nhan", {
		"designated_ids": ["nv1"],
		"participants": {"lead": ["nv1"]},
		"small_unit_exception": true,
	})
	d.allow
	"sod_small_unit_exception" in d.flags
}

test_small_unit_exception_not_for_other_constraints if {
	d := decide(user(["leader"]), "SCR-0203.duyet", {
		"owner_unit_id": "u1",
		"participants": {"initiator": ["nv1"]},
		"small_unit_exception": true,
	})
	d.deny == {"separation_of_duties"}
}

test_assignment_acceptor_disjoint_from_assignees if {
	d := decide(user(["manager"]), "SCR-0501.giao", {
		"owner_unit_id": "u1",
		"participants": {"acceptor": ["nv5"], "collaborator": ["nv4", "nv5"]},
	})
	d.deny == {"separation_of_duties"}
	ok := decide(user(["manager"]), "SCR-0501.giao", {
		"owner_unit_id": "u1",
		"participants": {"acceptor": ["nv5"], "lead": ["nv3"], "collaborator": ["nv4"]},
	})
	ok.allow
}

test_period_lock_launcher_not_approver if {
	d := decide(user(["tenant_admin"]), "SCR-0801.khoi-chay", {"participants": {"approver": ["nv1"]}})
	d.deny == {"separation_of_duties"}
}

test_appraiser_not_appraisee if {
	d := decide(user(["appraiser"]), "SCR-0902.ky", {"owner_unit_id": "u11", "participants": {"appraisee": ["nv1"]}})
	d.deny == {"separation_of_duties"}
}

test_complaint_handler_not_decider if {
	d := decide(user(["appraiser"]), "SCR-1202.tiep-nhan", {
		"designated_ids": ["nv1"],
		"participants": {"decider": ["nv1"]},
	})
	d.deny == {"separation_of_duties"}
}

# ------------------------------------------------------------------ Bốn mắt

test_reopen_second_approval_differs_from_first if {
	s := user(["second_approver"])
	d := decide(s, "SCR-1301.duyet-2", {"designated_ids": ["nv1"], "participants": {"approver": ["nv1"]}})
	d.deny == {"four_eyes_required"}
	ok := decide(s, "SCR-1301.duyet-2", {
		"designated_ids": ["nv1"],
		"participants": {"approver": ["nv7"], "requester": ["nv8"]},
	})
	ok.allow
}

test_reopen_approver_not_requester if {
	d := decide(user(["decider"]), "SCR-1301.duyet-1", {"designated_ids": ["nv1"], "participants": {"requester": ["nv1"]}})
	d.deny == {"four_eyes_required"}
}

test_saga_cancel_second_approver_not_requester if {
	d := decide(user(["second_approver"]), "SCR-0801.duyet-huy", {"participants": {"requester": ["nv1"]}})
	d.deny == {"four_eyes_required"}
}

test_role_grant_second_approval_not_grantee if {
	d := decide(user(["tenant_admin"]), "SCR-0105.duyet", {"participants": {"grantee": ["nv1"]}})
	d.deny == {"four_eyes_required"}
}

test_sensitive_role_grant_flag if {
	d := decide(user(["tenant_admin"]), "SCR-0105.cap", {"granted_role": "decider"})
	d.allow
	"second_approval_required" in d.flags
	plain := decide(user(["tenant_admin"]), "SCR-0105.cap", {"granted_role": "member"})
	not "second_approval_required" in plain.flags
}

test_workflow_template_approver_not_author if {
	s := user(["second_approver"])
	d := decide(s, "SCR-1505.phe-duyet", {"participants": {"initiator": ["nv1"]}})
	d.deny == {"four_eyes_required"}
}

# ------------------------------------------------------------------ Ẩn với đối tượng cảnh báo

test_alert_hidden_from_subject_until_concluded if {
	s := user(["inspector"])
	hidden := decide(s, "SCR-1401.xem", {"owner_unit_id": "u1", "participants": {"alert_subject": ["nv1"]}})
	hidden.deny == {"out_of_scope"}
	shown := decide(s, "SCR-1401.xem", {
		"owner_unit_id": "u1",
		"participants": {"alert_subject": ["nv1"]},
		"concluded": true,
	})
	shown.allow
}

test_alert_handler_not_subject if {
	d := decide(user(["inspector"]), "SCR-1401.ket-luan", {"owner_unit_id": "u1", "participants": {"alert_subject": ["nv1"]}})
	d.deny == {"separation_of_duties"}
}

# ------------------------------------------------------------------ Cờ nghĩa vụ

test_delegation_flag_only_for_delegate if {
	d := decide(user(["manager"]), "SCR-0703.xac-nhan", {"delegate_ids": ["nv1"]})
	d.allow
	"record_delegation" in d.flags
	plain := decide(user(["manager"]), "SCR-0703.xac-nhan", {"designated_ids": ["nv1"]})
	not "record_delegation" in plain.flags
}

test_inferred_grant_flag if {
	d := decide(user(["leader"]), "SCR-0601.duyet-de-nghi", {"owner_unit_id": "u11"})
	d.allow
	"inferred_grant" in d.flags
}

test_inferred_flag_absent_when_any_grant_certain if {
	d := decide(user(["leader", "manager"]), "SCR-0601.duyet-de-nghi", {"owner_unit_id": "u1"})
	d.allow
	not "inferred_grant" in d.flags
}

test_obligation_flag if {
	d := decide(user(["leader"]), "SCR-1601.nhac", {"owner_unit_id": "u1"})
	"act_as_caller" in d.flags
}

# ------------------------------------------------------------------ Hệ thống ngoài và trợ lý AI

client := {"kind": "client", "client_id": "voffice", "tenant_id": "t1"}

test_client_receives_documents if {
	d := decide(client, "SCR-0502.nhan-van-ban", {})
	d.allow
}

test_client_other_tenant_denied if {
	d := decide(client, "SCR-0502.nhan-van-ban", {"tenant_id": "t2"})
	d.deny == {"out_of_scope"}
}

test_client_cannot_use_user_function if {
	d := decide(client, "SCR-0501.soan", {"owner_unit_id": "u1"})
	d.deny == {"role_not_allowed"}
}

test_ai_reads_on_behalf if {
	d := decide(user(["ai_readonly"]), "SCR-0502.ai-trich", {})
	d.allow
	"ai_on_behalf_of_user" in d.flags
	"ai_no_c2_log" in d.flags
}

test_ai_never_reads_c3 if {
	d := decide(user(["ai_readonly"]), "SCR-0201.xem", {"classification": "C3", "recipient_ids": ["nv1"]})
	d.deny == {"classification_denied"}
}

# ------------------------------------------------------------------ API theo module

api(subject, module, method, resource) := d if {
	d := authz.decision with input as {
		"subject": subject,
		"request": {"module": module, "method": method},
		"resource": resource,
		"context": {"now": now},
	}
		with data.org as org
}

test_api_method_allowed if {
	d := api(user(["manager"]), "tasks", "DELETE", {"tenant_id": "t1"})
	d.allow
}

test_api_method_denied if {
	d := api(user(["member"]), "tasks", "DELETE", {"tenant_id": "t1"})
	d.deny == {"role_not_allowed"}
}

test_api_unknown_module_denied if {
	d := api(user(["manager"]), "khong-co", "GET", {})
	d.deny == {"role_not_allowed"}
}

test_api_aal2_role_needs_aal2 if {
	d := api(object.union(user(["appraiser"]), {"acr": "aal1"}), "appraisals", "GET", {})
	d.deny == {"step_up_required"}
}

test_api_cross_tenant_denied if {
	d := api(user(["manager"]), "tasks", "GET", {"tenant_id": "t2"})
	d.deny == {"out_of_scope"}
}

test_api_platform_role_crosses_tenant if {
	d := api(user(["platform_admin"]), "registry", "GET", {"tenant_id": "t2"})
	d.allow
}

test_api_inferred_method_flag if {
	d := api(user(["inspector"]), "okr", "GET", {})
	"inferred_grant" in d.flags
}

test_api_without_resource_tenant if {
	d := api(user(["manager"]), "tasks", "GET", {})
	d.allow
}

test_api_c2_classification_checked if {
	d := api(user(["policy_author"]), "policies", "GET", {"tenant_id": "t1", "classification": "C2"})
	d.deny == {"classification_denied"}
}

test_action_and_request_both_required if {
	d := authz.decision with input as {
		"subject": user(["manager"]),
		"action": "SCR-0501.soan",
		"request": {"module": "tasks", "method": "POST"},
		"resource": {"tenant_id": "t1", "owner_unit_id": "u1"},
		"context": {"now": now},
	}
		with data.org as org
	d.allow
	bad := authz.decision with input as {
		"subject": user(["manager"]),
		"action": "SCR-0501.soan",
		"request": {"module": "ledger", "method": "POST"},
		"resource": {"tenant_id": "t1", "owner_unit_id": "u1"},
		"context": {"now": now},
	}
		with data.org as org
	bad.deny == {"role_not_allowed"}
}

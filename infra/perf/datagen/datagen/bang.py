"""Danh mục bảng đích (TL-03) mà bộ sinh ghi, theo thứ tự nạp tôn trọng khoá ngoại."""

from __future__ import annotations

COT: dict[str, tuple[str, ...]] = {
    "keyring.dek": ("id", "tenant_id", "purpose", "valid_month", "wrapped_dek", "kek_ref",
                    "alg_version", "status"),
    "config.period": ("id", "tenant_id", "created_by", "updated_by", "code", "name", "kind",
                      "flow", "start_date", "end_date", "period_bucket", "status", "locked_at"),
    "org.subject_group": ("id", "tenant_id", "code", "name", "flow", "description", "valid_from",
                          "valid_to", "created_by", "updated_by"),
    "org.job_title": ("id", "tenant_id", "code", "name", "level", "is_central_managed",
                      "subject_group_id", "valid_from", "valid_to", "created_by", "updated_by"),
    "org.org_unit": ("version_id", "id", "tenant_id", "code", "name", "short_name", "unit_type",
                     "subject_group_id", "flows", "level", "valid_from", "valid_to",
                     "created_by", "updated_by"),
    "org.org_unit_relation": ("version_id", "id", "tenant_id", "tree", "parent_unit_id",
                              "child_unit_id", "relation_type", "valid_from", "valid_to",
                              "created_by", "updated_by"),
    "org.person": ("version_id", "id", "tenant_id", "full_name", "gender", "birth_date_enc",
                   "national_id_enc", "national_id_bidx", "party_member_no_enc",
                   "party_member_no_bidx", "is_party_member", "cbccvc_code", "employment_status",
                   "dek_id", "valid_from", "valid_to", "created_by", "updated_by"),
    "org.assignment": ("version_id", "id", "tenant_id", "person_id", "org_unit_id",
                       "job_title_id", "kind", "branch", "is_head", "valid_from", "valid_to",
                       "created_by", "updated_by"),
    "task.task": ("id", "tenant_id", "created_by", "updated_by", "period_id", "period_bucket",
                  "code", "title", "task_group", "is_key_task", "source", "assigner_id",
                  "assigned_at", "acceptor_id", "start_date", "due_date", "planned_quantity",
                  "done_quantity", "unit", "progress_pct", "status", "is_overdue",
                  "dup_fingerprint", "state_machine_version", "completed_at"),
    "task.task_assignee": ("id", "tenant_id", "created_by", "updated_by", "period_id",
                           "period_bucket", "task_id", "assignee_type", "person_id", "role",
                           "accepted_at", "valid_from"),
    "task.task_progress": ("id", "tenant_id", "created_by", "updated_by", "period_id",
                           "period_bucket", "task_id", "reported_at", "progress_pct", "channel",
                           "idempotency_key"),
    "product.deliverable": ("id", "tenant_id", "created_by", "updated_by", "period_id",
                            "period_bucket", "task_id", "name", "deliverable_type", "unit",
                            "quantity_planned", "quantity_accepted", "status"),
    "product.evidence": ("id", "tenant_id", "created_by", "updated_by", "period_id",
                         "period_bucket", "deliverable_id", "task_id", "title",
                         "current_version_id", "current_status", "source_type"),
    "product.evidence_version": ("id", "tenant_id", "created_by", "updated_by", "period_id",
                                 "period_bucket", "evidence_id", "task_id", "version_no",
                                 "status", "object_id", "object_version_id", "content_sha256",
                                 "size_bytes", "media_type", "file_name", "source", "scan_status",
                                 "submitted_by", "submitted_at", "accepted_at"),
    "evidence.object_meta": ("id", "tenant_id", "created_by", "updated_by", "owner_module",
                             "owner_ref_id", "file_name_enc", "dek_id", "media_type",
                             "classification", "current_version_id", "retention_class"),
    "evidence.object_version": ("id", "tenant_id", "object_meta_id", "version_no", "sha256",
                                "size_bytes", "bucket", "object_key", "ceph_version_id",
                                "scan_status", "created_by"),
}

THU_TU_NAP = tuple(COT)

# Bảng phân vùng LIST (period_bucket): phân vùng kỳ được tạo bằng fn_create_period_partitions.
PHAN_VUNG = {b for b in COT if b.split(".")[0] in ("task", "product")}

# Cột văn bản tự do được quét theo mẫu dữ liệu cá nhân thật (SEC-52).
COT_VAN_BAN = {
    "org.person": ("full_name", "cbccvc_code"),
    "org.org_unit": ("code", "name", "short_name"),
    "task.task": ("code", "title"),
    "product.deliverable": ("name",),
    "product.evidence": ("title",),
    "product.evidence_version": ("file_name",),
    "evidence.object_version": ("object_key",),
}

# Bảng đích chưa có migration: được ghi vào báo cáo thay vì sinh.
CHUA_CO_MIGRATION = {
    "appraisal.appraisal_profile": "CORE-04-01 chưa làm (schema appraisal chưa có bảng hồ sơ)",
}


def schema(bang: str) -> str:
    return bang.split(".")[0]

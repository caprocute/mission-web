# METADATA
# title: Ứng viên xử lý khiếu nại (CORE-07-03, QT-12 bước 3, BR-12-04)
# description: |
#   Chọn người có vai trò appraiser ở cấp có thẩm quyền, loại người đã nghiệm thu, thẩm định, đề xuất
#   hoặc quyết định hồ sơ bị khiếu nại (PL 4 V.1). Không còn ai thì nâng lên đơn vị cấp trên gần nhất
#   có ứng viên và ghi lý do chuyển cấp. SVC-06 gọi khi giao task; MOD-07 kiểm lại bằng cùng quy tắc
#   khi nhận lệnh ghi (MSG-12-05 khi bị từ chối).
#
#   Đầu vào (service tự dựng, không cần data.org):
#     units        mảng mã đơn vị, phần tử đầu là đơn vị xử lý, sau đó các cấp trên gần trước
#     appraisers   đối tượng đơn vị → mảng user_id giữ vai trò appraiser có hiệu lực tại đơn vị đó
#     participants đối tượng nhóm (acceptor, appraiser, proposer, decider) → mảng user_id
#     subject_id   (tuỳ chọn) người đang gọi lệnh ghi, để trả allowed và vai trò đã tham gia
#   Kết quả decision: unit, candidates (sắp tăng dần), escalated, reason, allowed, conflicts.
package okrkpi.complaint

import rego.v1

groups := ["acceptor", "appraiser", "proposer", "decider"]

units := object.get(input, "units", [])

participants := object.get(input, "participants", {})

excluded contains p if {
	some g in groups
	some p in object.get(participants, g, [])
}

eligible_at(u) := {p |
	some p in object.get(object.get(input, "appraisers", {}), u, [])
	not p in excluded
}

# Vị trí đầu tiên trong chuỗi đơn vị có ít nhất một ứng viên hợp lệ.
hits := {i | some i, u in units; count(eligible_at(u)) > 0}

level := min(hits) if count(hits) > 0

unit := units[level]

unit_or_null := u if {
	u := unit
} else := null

candidates := sort([p | some p in eligible_at(unit)]) if {
	unit
} else := []

escalated if {
	level > 0
} else := false

reason := "KHONG_CO_UNG_VIEN" if {
	count(hits) == 0
} else := "NANG_CAP_TREN" if {
	escalated
} else := null

conflicts := sort([g |
	some g in groups
	input.subject_id in object.get(participants, g, [])
])

allowed if {
	input.subject_id
	input.subject_id in candidates
} else := false

decision := {
	"unit": unit_or_null,
	"candidates": candidates,
	"escalated": escalated,
	"reason": reason,
	"allowed": allowed,
	"conflicts": conflicts,
}

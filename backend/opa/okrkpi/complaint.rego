# METADATA
# title: Ứng viên xử lý khiếu nại (CORE-07-03, QT-12 bước 3, BR-12-04)
# description: |
#   Người có vai trò appraiser ở cấp có thẩm quyền, loại trừ người đã nghiệm thu, thẩm định, đề xuất,
#   quyết định hồ sơ bị khiếu nại (ràng buộc 21.1, PL 4 V.1) và chính người khiếu nại. Cấp của hồ sơ
#   không còn ai thì nâng lên cấp trên trực tiếp theo cây tổ chức. SVC-06 gọi khi giao task của mẫu luồng
#   xu-ly-khieu-nai; MOD-07 kiểm lại cùng logic khi nhận lệnh ghi (ComplaintHandlerRules).
#   Đầu vào: levels (danh sách người appraiser theo cấp, cấp của hồ sơ trước), participants (nhóm
#   acceptor, appraiser, proposer, decider), complainant. Đầu ra: candidates, level, escalated.
package okrkpi.complaint

import rego.v1

groups := ["acceptor", "appraiser", "proposer", "decider"]

excluded contains p if {
	some g in groups
	some p in object.get(object.get(input, "participants", {}), g, [])
}

excluded contains input.complainant if is_string(input.complainant)

levels := object.get(input, "levels", [])

valid_at(i) := [p | some p in levels[i]; not p in excluded]

open_levels := {i | some i, _ in levels; count(valid_at(i)) > 0}

default level := -1

level := min(open_levels) if count(open_levels) > 0

default candidates := []

candidates := valid_at(level) if level >= 0

escalated if level > 0

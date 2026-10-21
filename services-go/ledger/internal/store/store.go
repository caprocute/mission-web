// Package store chứa các cấu trúc dữ liệu và truy vấn nền tảng của schema ledger.
package store

import "github.com/google/uuid"

// SystemActor đại diện tác nhân hệ thống mặc định khi kiểm thử hoặc tác vụ nền.
var SystemActor = uuid.MustParse("00000000-0000-0000-0000-000000000001")

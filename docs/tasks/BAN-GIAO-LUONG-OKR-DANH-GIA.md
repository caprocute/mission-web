# Bàn giao luồng "Màn hình OKR, minh chứng, đánh giá" (2026-10-20)

Luồng này tạm dừng vì hết quota tuần. Người hoặc agent làm tiếp phải đọc `AGENTS.md`, sau đó đọc tệp này. Trạng thái dưới đây đã được đối chiếu với `origin/main` lúc bàn giao. Lưu ý rằng cột trạng thái trong `docs/tasks/README.md` trên main đang chậm so với thực tế.

## Trạng thái từng task

| Task | Màn hình | Trạng thái | Nhánh |
|---|---|---|---|
| WEB-021 | SCR-0201 Cây mục tiêu OKR | Đã gộp vào main | `feat/WEB-021-cay-okr` |
| WEB-022 | SCR-0202 Tạo/sửa O và KR | Đã gộp vào main | `feat/WEB-022-tao-sua-okr` |
| WEB-023 | SCR-0203 Phê duyệt OKR | Đã gộp vào main | `feat/WEB-023-duyet-okr` |
| WEB-024 | SCR-0301 Check-in KR | Đã gộp vào main | `feat/WEB-024-checkin-kr` |
| WEB-035 | SCR-0901 Phiếu tự đánh giá | Đã gộp vào main | `feat/WEB-035-tu-danh-gia` |
| WEB-036 | SCR-0902 Thẩm định | Đã gộp vào main | `feat/WEB-036-tham-dinh` |
| WEB-037 | SCR-0903 Phiếu xếp loại | Đã gộp vào main | `feat/WEB-037-phieu-xep-loai` |
| WEB-038 | SCR-0904 Quyết định hàng loạt, ký số | Đã gộp vào main | `feat/WEB-038-quyet-dinh-hang-loat` |
| WEB-039 | SCR-1001 Xếp loại năm, SCR-1002 Phân nhóm triển vọng | Xong, chờ gộp. Kiểm thử, build, e2e và Playwright đều đạt | `feat/WEB-039-xep-loai-nam` (commit 2cac4ad) |
| WEB-040 | SCR-1101 Hội nghị kiểm điểm | Đang dở, chi tiết ở mục dưới | `feat/WEB-040-hoi-nghi-kiem-diem` |
| WEB-041 | SCR-1102 Gói hồ sơ số và ký số | Chưa làm (phụ thuộc WEB-040) | — |
| WEB-049 | SCR-1701 Trợ lý AI soạn OKR | Chưa làm | — |

Báo cáo cuối của từng task nằm ở `/mnt/project-files/bao-cao-luong-okr/WEB-xxx/BAO-CAO.md` (thư mục dùng chung của project, không nằm trong repo).

## WEB-040: đã làm và còn lại

Nhánh `feat/WEB-040-hoi-nghi-kiem-diem` được tạo từ `origin/main`. Phần đã làm nằm trong `@vt/api-client` và đã qua `tsc`:

- `lib/models/dossier.ts` chứa các bí danh kiểu của hội nghị, góp ý, biên bản, khắc phục và trường hợp đặc thù, lấy từ `generated/dossiers`. Tệp này cũng có bảng nhãn tiếng Việt cho loại hội nghị, trạng thái hội nghị, vai trò, trạng thái thành phần, loại góp ý và trạng thái khắc phục. Mô-đun đã được xuất ra trong `public-api.ts`.
- `lib/dossier-api.ts` có thêm các thao tác `meetings`, `meeting`, `createMeeting`, `updateMeeting`, `sendDraft`, `comments`, `addComment`, `minutes` (202), `close`, `remediationItems`, `updateRemediation` và `specialCases`. Các thao tác ghi gửi `If-Match` bằng `rowVersion`. Tham số truy vấn dùng `period` và `unit` theo `common.yaml`.

Phần còn phải làm, theo thứ tự:

1. **Viết dữ liệu giả lập** `mock/dossiers/meetings.ts` rồi nối vào `DOSSIERS_MOCK` (thêm routes và reset). Phương án đã chọn như sau:
   - Mỗi hội nghị giả lập có `myRole` cố định để thử được mọi vai: M1 người dùng là thư ký và hội nghị đang họp (theo mockup); M2 người dùng là người được kiểm điểm, hội nghị `DU_KIEN`, gửi dự thảo hôm nay sẽ không đạt thời hạn (MSG-11-01); M3 người dùng là chủ trì, đã lập biên bản, chờ "Kết thúc hội nghị"; M4 người dùng là thành viên và góp ý; M5 người dùng là người được kiểm điểm, có bật ẩn danh nên `commenter = null`; M7 người dùng là chủ trì, thời hạn không đạt, cần ghi lý do giữ ngày.
   - Ngày làm việc được đếm là các ngày thứ Hai đến thứ Sáu nằm trọn giữa thời điểm gửi và ngày họp.
   - "Sinh biên bản" cần step-up AAL2: trả 401 với `WWW-Authenticate` chứa `insufficient_user_authentication` khi `mockSession.recentMfa()` sai, theo mẫu ở `plans.mock.ts`.
   - Các mã lỗi lấy từ `dossiers.yaml`: 403 NOT-SUBJECT/NOT-SECRETARY/NOT-CHAIR/NOT-PARTICIPANT, 409 INVALID-STATE/COMMENTS-LOCKED/NO-VOTE/MEETING-EXISTS, 422 VOTE-EXCEEDS-ATTENDEES, 428/409 cho If-Match.
   - Mã biểu mẫu dự thảo Mẫu 1B của hội nghị phải được tuyến `GET /dossiers/forms/:id/file` phục vụ.
2. **Viết `fe-dossier`:**
   - `dossier-permissions.ts`: token `DOSSIER_CAN` với các khoá `SCR-1101.tao`, `gui-du-thao`, `gop-y`, `thu-ky`, `ket-thuc`.
   - `step-up.ts`: sao theo mẫu `fe-admin/src/app/pages/periods/step-up.ts`.
   - Tuyến: `''` chuyển sang `kiem-diem` khi có quyền SCR-1101; `kiem-diem` là danh sách cùng hộp "Lập hội nghị" (AC-07), trong đó hiện trường hợp đặc thù của người được kiểm điểm; `kiem-diem/:id` là màn hình ba cột theo mockup.
   - Màn hình ba cột gồm: thanh năm bước; thẻ kiểm tra thời hạn và thành phần dự; danh sách góp ý cùng ô nhập (thư ký ghi ý kiến tại hội nghị); khắc phục hạn chế kỳ trước; biểu quyết; các nút "Tải dự thảo Mẫu 1B", "Gửi dự thảo", "Sinh biên bản", "Kết thúc hội nghị".
   - Nhãn của 13 trường hợp đặc thù lấy theo `legalRef` (PL 6 §1..§13, bảng trong TL-02b QT-11), không hiện mã.
   - Theo YC-PT-10, mức xếp loại hiện tên đầy đủ, không hiện HTXSNV/HTTNV.
   - CSS mỗi component phải dưới 4 kB.
3. Viết kiểm thử mock và kiểm thử trang. Sau đó chạy lint, api:check, check:ui-text, check:permissions, test, build:all, e2e và Playwright (axe, bàn phím, chế độ cao tuổi, điện thoại). Cuối cùng cập nhật wiki 05b/14 và README task, rồi viết báo cáo.

## Đề nghị chỉnh hợp đồng API còn treo

Các đề nghị này đã gửi luồng điều phối và đã ghi vào `wiki/14` trên nhánh WEB-039. Mã hiện làm theo hợp đồng (YC-PT-09).

1. `PUT /appraisals/prospect-groups/...`: input chưa có `trainingPlanRef` cho từng mục, trong khi TL-02b có trường kế hoạch bồi dưỡng.
2. `ProspectGroupBoard` thiếu `policyPackId`, nên màn hình phải tìm bộ tiêu chí qua `pinnedPolicyPacks` của kỳ.
3. PUT bảng phân nhóm bắt buộc lý do ngay khi lưu nháp. Đề nghị chỉ bắt buộc khi trình.
4. Ví dụ `linkageNote`/`policyNote` có mã BR và chữ "Policy Pack", trái YC-PT-10. Đề nghị đổi ví dụ sang lời nghiệp vụ.
5. SCR-0902 chưa có chế độ thẩm định năm riêng.
6. Phát hiện khi làm WEB-040: `DossierMeetingParticipant` chỉ có `Ref` (id, tên) nên thiếu chức vụ mà mockup hiện dưới tên; `DossierSpecialCase` không có tên trường hợp, chỉ có `caseCode` và `legalRef`; ma trận phân quyền chưa có khoá cho "dời ngày" và "ghi lý do giữ ngày" (đang định dùng `tao` và `ket-thuc`, là suy luận).

## Vấn đề có sẵn trên main

- e2e có 2 lỗi lặp lại ổn định: `/config-studio/goi/E2E-1/E2E-1` của tenant_admin và policy_author bị chuyển hướng sang `/cong-thuc`.
- `check:ui-text` còn 13 chỗ cũ (analytics, policies, config-studio).
- `test:all` chạy gộp bị treo trong môi trường cloud; chạy riêng từng dự án thì xanh.

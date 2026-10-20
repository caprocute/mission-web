# Bàn giao luồng "Màn hình giám sát, điều hành, cấu hình"

Ngày dừng: 20/10/2026, do hết hạn mức sử dụng trong tuần. Luồng này phụ trách hai remote `fe-config-studio` và `fe-admin`.

| Task | Trạng thái | Ghi chú |
|---|---|---|
| WEB-016..019 | Đã gộp vào main | Tổ chức, người dùng và các trang quản trị nền tảng. |
| WEB-045 Công thức (SCR-1501) | Đã gộp vào main | |
| WEB-046 Test case và Luồng duyệt (SCR-1503, SCR-1504) | Xong, chờ gộp | Nhánh `feat/WEB-046-test-luong-duyet`, commit b0e485c, đã chứa main af01312. Build, kiểm thử, check:ui-text và Playwright đều đạt. Các quyết định đã ghi ở wiki/14. |
| WEB-047, WEB-048 | Đã chuyển sang luồng khác | |
| WEB-050 Giao diện tenant trong Studio | Chưa làm | Chưa có dòng mã nào. Bắt đầu từ `docs/tasks/WEB-050-studio-giao-dien.md`. |
| WEB-061 Trình soạn mẫu luồng (SCR-1505) | Chưa làm | Theo phụ lục BS-01. Có thể dùng lại kiểu dữ liệu workflow-template và mock đã thêm ở WEB-046. |
| WEB-062 Giám sát tiến trình (SCR-1506) | Chưa làm | Theo phụ lục BS-01. |

## Lưu ý cho người làm tiếp

- Khi gộp WEB-046, tệp mới `frontend/e2e/noi-dung/cau-hinh.json` nằm ngoài danh sách tệp được phép của task. Tệp này được thêm để kiểm thử nội dung bao phủ các tuyến config-studio.
- `esignSessionId` khi ký phê duyệt chưa được nối với luồng ký số thật; mock bỏ qua trường này.
- Việc đưa client tổ chức của WEB-017/018 về đúng hợp đồng OpenAPI vẫn còn tồn đọng.
- Mọi màn hình mới phải qua `npm run check:ui-text` (wiki/16) và lọc tab, nút theo ma trận wiki/15a.

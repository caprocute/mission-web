# Mission – phân hệ web (SVC-17)

Repo này chứa phân hệ web của nền tảng giao nhiệm vụ, quản lý OKR/KPI và đánh giá cán bộ theo Thông tư 22 và Quyết định 366. Mã nguồn bám theo bộ tài liệu thiết kế TL-01..TL-04 trong `/mnt/project-files/kien-truc-okr-kpi/`.

Người và AI agent mới vào dự án nên đọc theo thứ tự sau:

1. [`AGENTS.md`](AGENTS.md) – quy định chung bắt buộc, gồm 12 quy tắc, quy trình làm task và mẫu báo cáo.
2. [`docs/KE-HOACH-PHAT-TRIEN-PHAN-HE-WEB.md`](docs/KE-HOACH-PHAT-TRIEN-PHAN-HE-WEB.md) – kế hoạch, giai đoạn và mốc.
3. [`wiki/README.md`](wiki/README.md) – mục lục tri thức chi tiết.
4. [`docs/tasks/README.md`](docs/tasks/README.md) – danh mục 53 task cho AI agent, thứ tự và phụ thuộc.
5. [`infra/dev/README.md`](infra/dev/README.md) – hạ tầng dùng chung trên server dev.

Chạy nhanh frontend:

```bash
cd frontend && npm ci
npx ng serve fe-task --port 4201 &
npx ng serve fe-shell --port 4200     # mở http://localhost:4200
```

Giao một task cho agent: `./scripts/agent-task.sh WEB-001` in ra prompt gồm quy định chung và nội dung task.

Bí mật: `.env` (SSH server dev) và `infra/dev/.env` (mật khẩu dịch vụ) không bao giờ được commit; chỉ cập nhật các tệp `.env.example`.

# Manifest component registry SDUI

Mỗi tệp `<registryVersion>.json` là manifest một phiên bản registry (CORE-08-09, ADR-05 lớp 2), sinh từ bản build shell. mission-core nạp manifest khi đặt `mission.config.component-registry.manifest-dir` trỏ tới thư mục này; phiên bản đã nạp không bị ghi đè, muốn đổi danh mục thì phát hành phiên bản mới. Kiểm thử `ComponentRegistrySeedTest` đối chiếu tên component ở đây với registry phía web.

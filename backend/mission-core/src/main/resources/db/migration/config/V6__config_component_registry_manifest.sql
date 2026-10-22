-- CORE-08-09: component registry có phiên bản theo manifest phát hành cùng shell (ADR-05 lớp 2,
-- TL-01 "Component registry"). Bổ sung các trường của manifest mà hợp đồng UiComponent trả về
-- (suy luận: TL-03 chưa liệt kê container, since, mobile).
ALTER TABLE ${flyway:defaultSchema}.component_registry
    ADD COLUMN container boolean NOT NULL DEFAULT false,
    ADD COLUMN since varchar(16),
    ADD COLUMN mobile boolean NOT NULL DEFAULT false,
    ADD COLUMN loaded_at timestamptz NOT NULL DEFAULT now();
COMMENT ON COLUMN ${flyway:defaultSchema}.component_registry.container IS '{"cls": "C0", "desc": "Được chứa thành phần con"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.component_registry.since IS '{"cls": "C0", "desc": "Phiên bản registry phía web đưa component vào"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.component_registry.mobile IS '{"cls": "C0", "desc": "Hỗ trợ trên ứng dụng di động"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.component_registry.loaded_at IS '{"cls": "C0", "desc": "Thời điểm nạp manifest"}';

-- Seed phiên bản đầu tiên (V2) khớp manifest data/seed/component-registry/2026.3.json, tức registry
-- web 2.4.0 (builtin-components.ts, fe-shell shell-widgets.ts).
UPDATE ${flyway:defaultSchema}.component_registry SET container = true, since = '2.0.0'
    WHERE registry_version = '2026.3' AND component_key = 'vt.layout.grid';
UPDATE ${flyway:defaultSchema}.component_registry SET since = '2.0.0', mobile = true
    WHERE registry_version = '2026.3' AND component_key IN ('vt.widget.myTasksToday', 'vt.widget.okrProgress', 'vt.widget.pendingApprovals');
UPDATE ${flyway:defaultSchema}.component_registry SET since = '2.1.0'
    WHERE registry_version = '2026.3' AND component_key = 'vt.widget.announcement';

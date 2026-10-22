import { TokenGroup } from '@vt/design-system';
import type { UiPackPluginRef } from './plugin-contract';

/** Một nút bố cục SDUI: chỉ tham chiếu component có trong registry, không chứa HTML/JS. */
export interface SduiNode {
  component: string;
  props?: Record<string, unknown>;
  children?: SduiNode[];
  /** Biểu thức CEL chỉ đọc ngữ cảnh người dùng. */
  visibleWhen?: string;
}

/** UI Pack theo TL-01 chương 7 (siêu dữ liệu, token, bố cục, biểu mẫu, plugin). */
export interface UiPack {
  $schema?: string;
  pack: string;
  version: string;
  requires: { registry: string; policyPack?: string };
  tokens: TokenGroup;
  modes?: Record<string, TokenGroup & { flags?: Record<string, unknown> }>;
  /** CSS token đã biên dịch sẵn ở máy chủ (MOD-08); thiếu thì shell tự biên dịch. */
  compiledCss?: string;
  layouts: Record<string, SduiNode>;
  forms?: Record<string, { schemaRef: string; ui?: unknown }>;
  /** Plugin chuyên ngành tenant được nạp (WEB-051); xem `UiPackPluginRef` và `VtPlugin`. */
  plugins?: UiPackPluginRef[];
  i18n?: Record<string, Record<string, string>>;
  /** SHA-256 (hex) của nội dung được ký (`bundle_sha256`), máy chủ gắn lúc phát hành. */
  contentHash?: string;
  /** Chữ ký ES256 của nội dung được ký, base64 (WEB-009). */
  signature?: string;
  publishedAt?: string;
}

/** Con trỏ phiên bản hiện hành: `/ui-packs/{tenant}/current.json`. */
export interface UiPackPointer {
  version: string;
  /** Mã khoá nền tảng đã ký gói, dùng để chọn khoá công khai trong `config.json`. */
  keyId?: string;
  contentHash?: string;
  signature?: string;
}

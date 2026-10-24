import { fieldErrors, toProblem } from '@vt/api-client';
import {
  CUSTOM_ROLE_MESSAGES,
  ROLE_ADMIN_MESSAGES,
  USER_ADMIN_MESSAGES,
} from '@vt/api-client/domains';
import { TEXT_FORBIDDEN } from '@vt/auth';

/** Lỗi hiển thị của quản trị người dùng và vai trò: mã nghiệp vụ có văn bản riêng thì dùng văn bản đó. */
export function adminError(
  err: unknown,
  fallback: string,
): { text: string; fields: Record<string, string> } {
  const p = toProblem(err);
  const mapped = p.code
    ? (USER_ADMIN_MESSAGES[p.code] ?? CUSTOM_ROLE_MESSAGES[p.code] ?? ROLE_ADMIN_MESSAGES[p.code])
    : undefined;
  const text =
    mapped ??
    (p.status === 403
      ? TEXT_FORBIDDEN
      : p.status === 409
        ? 'Dữ liệu vừa được người khác cập nhật. Vui lòng tải lại trang rồi thử lại.'
        : p.title || fallback);
  return { text, fields: fieldErrors(p) };
}

/** Gợi ý tên đăng nhập từ họ tên: tên chính không dấu và chữ cái đầu của họ, tên đệm (ví dụ "anhnv"). */
export function suggestUsername(fullName: string): string {
  const parts = fullName
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'D')
    .toLowerCase()
    .replace(/[^a-z\s]/g, ' ')
    .split(/\s+/)
    .filter(Boolean);
  const last = parts.pop();
  return last ? last + parts.map((p) => p[0]).join('') : '';
}

/** Kiểu dùng chung của các trang người dùng và vai trò, bổ sung cho `ACCESS_STYLES`. */
export const PAGE_STYLES = `
  .head {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
    align-items: flex-start;
    margin-bottom: 8px;
  }
  h1 {
    font-size: 1.4rem;
    margin: 0 0 4px;
  }
  .tools {
    display: flex;
    gap: 12px;
    align-items: center;
    flex-wrap: wrap;
  }
  .cols {
    display: grid;
    grid-template-columns: minmax(260px, 1fr) minmax(0, 2.6fr);
    gap: 16px;
    align-items: start;
  }
  @media (max-width: 1100px) {
    .cols {
      grid-template-columns: 1fr;
    }
  }
  .filters {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    margin-bottom: 12px;
  }
  .chip {
    border: 1px solid #cbd5e1;
    background: #fff;
    border-radius: 999px;
    padding: 4px 12px;
    font: inherit;
    cursor: pointer;
  }
  .chip[aria-pressed='true'] {
    background: #b8002a;
    border-color: #b8002a;
    color: #fff;
  }
  .chip .n {
    font-weight: 600;
    margin-left: 4px;
  }
  .spacer {
    flex: 1;
  }
  .inline {
    display: inline-flex;
    gap: 6px;
    align-items: center;
  }
  .block {
    display: block;
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 12px 16px;
  }
  label.f {
    display: block;
    margin-bottom: 4px;
    font-weight: 500;
  }
  .full {
    width: 100%;
  }
`;

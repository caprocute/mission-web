import { paths } from './generated/mission-core';

/** Đường dẫn khai trong hợp đồng OpenAPI của mission-core (không kèm tiền tố `/api/v1`). */
export type ApiPath = keyof paths;

type PathParamNames<P extends string> = P extends `${string}{${infer N}}${infer Rest}`
  ? N | PathParamNames<Rest>
  : never;

/**
 * Dựng URL tương đối `/api/v1/...` từ đường dẫn có trong hợp đồng, kiểm kiểu tham số đường dẫn
 * lúc biên dịch: `apiUrl('/tasks/{id}', { id })`. Giá trị được `encodeURIComponent`.
 */
export function apiUrl<P extends ApiPath>(
  path: P,
  ...[params]: PathParamNames<P> extends never ? [] : [Record<PathParamNames<P>, string>]
): string {
  const values = (params ?? {}) as Record<string, string>;
  return (
    '/api/v1' +
    path.replace(/\{(\w+)\}/g, (_, name: string) => {
      const v = values[name];
      if (v === undefined) throw new Error(`Thiếu tham số đường dẫn ${name} cho ${path}`);
      return encodeURIComponent(v);
    })
  );
}

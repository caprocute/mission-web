import { UiFormVersion } from '../../lib/models/ui-form';
import { MockModule, ok, problem } from '../mock-router';
import fixture from './ui-forms.json';

const forms = fixture.forms as unknown as Record<string, UiFormVersion>;

/** Giả lập biểu mẫu động `/api/v1/ui/forms` (MOD-08): phiên bản đã phát hành theo khoá. */
export const UI_FORMS_MOCK: MockModule = {
  name: 'ui-forms',
  reset: () => undefined,
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/ui/forms/:key/current',
      handle: (req) => {
        const form = forms[req.pathParams['key']];
        if (!form) return problem(404, 'Không tìm thấy biểu mẫu');
        const etag = `"${form.version}"`;
        if (req.headers.get('If-None-Match') === etag) return { status: 304, body: null };
        return ok(form, { ETag: etag });
      },
    },
  ],
};

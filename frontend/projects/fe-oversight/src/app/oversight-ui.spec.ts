import { HttpErrorResponse } from '@angular/common/http';
import { errorText } from './oversight-ui';

const problem = (status: number, body: Record<string, unknown>) =>
  new HttpErrorResponse({
    status,
    error: { type: 'about:blank', status, ...body },
  });

/** YC-PT-26: lỗi hiện lời giải thích tiếng Việt của máy chủ (`detail`), không chỉ tiêu đề chung. */
describe('errorText', () => {
  it('409 nghiệp vụ: hiện `detail` thay cho tiêu đề chung "Xung đột dữ liệu"', () => {
    const e = problem(409, {
      title: 'Xung đột dữ liệu',
      code: 'OVERSIGHT-409-INVALID-STATE',
      detail: 'Đã có yêu cầu mở lại đang xử lý cho cùng phạm vi của kỳ này.',
    });
    expect(errorText(e)).toBe('Đã có yêu cầu mở lại đang xử lý cho cùng phạm vi của kỳ này.');
  });

  it('thông điệp TL-02 (`msg`) dùng `detail`; lỗi theo trường nối các thông điệp', () => {
    expect(
      errorText(
        problem(403, {
          title: 'Không đáp ứng nguyên tắc hai người duyệt.',
          msg: 'MSG-13-01',
          detail: 'Hai người duyệt phải là hai người khác nhau.',
        }),
      ),
    ).toBe('Hai người duyệt phải là hai người khác nhau.');
    expect(
      errorText(
        problem(422, {
          title: 'Dữ liệu không hợp lệ',
          detail: 'Yêu cầu mở lại kỳ chưa hợp lệ.',
          errors: [
            { field: 'basis', message: 'Chọn căn cứ mở lại.' },
            { field: 'reason', message: 'Lý do mở lại cần từ 50 đến 4.000 ký tự.' },
          ],
        }),
      ),
    ).toBe('Chọn căn cứ mở lại. Lý do mở lại cần từ 50 đến 4.000 ký tự.');
  });

  it('không có `detail`: dùng tiêu đề; không có gì thì câu mặc định', () => {
    expect(errorText(problem(409, { title: 'Xung đột dữ liệu', detail: '  ' }))).toBe(
      'Xung đột dữ liệu',
    );
    expect(errorText(problem(409, { title: '' }), 'Không lưu được.')).toBe('Không lưu được.');
    // `detail` kỹ thuật không lên giao diện
    expect(
      errorText(problem(409, { title: 'Xung đột dữ liệu', detail: 'OVERSIGHT-409-INVALID-STATE' })),
    ).toBe('Xung đột dữ liệu');
  });
});

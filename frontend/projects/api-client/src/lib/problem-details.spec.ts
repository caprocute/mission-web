import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';
import { fieldErrors, isConcurrencyConflict, toProblem } from './problem-details';
import { ProblemPresenter, provideProblemMessages } from './problem-presenter';

const httpError = (status: number, error: unknown = null) =>
  new HttpErrorResponse({
    status,
    error,
    headers: new HttpHeaders({ 'Content-Type': 'application/problem+json' }),
  });

describe('toProblem (RFC 9457)', () => {
  it('giữ nguyên Problem Details của máy chủ, kể cả body dạng chuỗi', () => {
    const body = {
      type: 'https://okrkpi.vn/problems/period-locked',
      title: 'Kỳ đánh giá đã khoá',
      status: 409,
      code: 'TASK-409-PERIOD-LOCKED',
      traceId: 'abc',
    };
    expect(toProblem(httpError(409, body))).toEqual(body);
    expect(toProblem(httpError(409, JSON.stringify(body))).code).toBe('TASK-409-PERIOD-LOCKED');
  });

  it('tự dựng tiêu đề tiếng Việt khi máy chủ không trả body đúng dạng', () => {
    expect(toProblem(httpError(412)).title).toMatch(/người khác cập nhật/);
    expect(toProblem(httpError(503, '<html>')).title).toMatch(/quá tải/);
    expect(toProblem(httpError(0)).title).toMatch(/kết nối/);
    const p = toProblem(new Error('x'));
    expect(p).toMatchObject({ status: 0 });
    expect(p.detail).toBeUndefined();
  });
});

describe('isConcurrencyConflict', () => {
  const p = (status: number, extra = {}) => ({ type: 'about:blank', title: 't', status, ...extra });
  it('409 không mã, 409 concurrent-modification và 412 là xung đột ghi', () => {
    expect(isConcurrencyConflict(p(409))).toBe(true);
    expect(isConcurrencyConflict(p(409, { type: 'https://x/concurrent-modification' }))).toBe(true);
    expect(isConcurrencyConflict(p(412))).toBe(true);
  });
  it('409 nghiệp vụ (kỳ đã khoá) và 422 không phải xung đột ghi', () => {
    expect(isConcurrencyConflict(p(409, { code: 'TASK-409-PERIOD-LOCKED' }))).toBe(false);
    expect(isConcurrencyConflict(p(422))).toBe(false);
  });
});

describe('fieldErrors', () => {
  it('gộp lỗi theo trường', () => {
    const map = fieldErrors({
      type: 'about:blank',
      title: 'Dữ liệu không hợp lệ',
      status: 422,
      errors: [
        { field: 'percent', message: 'Phải từ 0 đến 100' },
        { field: 'percent', message: 'Phải là số nguyên' },
        { field: 'note', message: 'Bắt buộc' },
      ],
    });
    expect(map).toEqual({ percent: 'Phải từ 0 đến 100; Phải là số nguyên', note: 'Bắt buộc' });
  });
});

describe('ProblemPresenter', () => {
  function setup() {
    TestBed.configureTestingModule({
      providers: [
        MessageService,
        provideProblemMessages({
          'TASK-409-PERIOD-LOCKED': 'Kỳ 2026-Q4 đang khoá. Dữ liệu của kỳ không thể thay đổi.',
        }),
      ],
    });
    const toast = TestBed.inject(MessageService);
    const add = vi.spyOn(toast, 'add');
    return { presenter: TestBed.inject(ProblemPresenter), add };
  }

  it('ánh xạ code sang MSG và hiện toast, không lộ traceId khi đã có MSG', () => {
    const { presenter, add } = setup();
    const r = presenter.present(
      httpError(409, {
        type: 'about:blank',
        title: 'Kỳ đã khoá',
        status: 409,
        code: 'TASK-409-PERIOD-LOCKED',
        traceId: 't1',
      }),
    );
    expect(r.message).toMatch(/đang khoá/);
    expect(r.conflict).toBe(false);
    expect(r.traceId).toBeUndefined();
    expect(add).toHaveBeenCalledWith(
      expect.objectContaining({ severity: 'warn', summary: r.message }),
    );
  });

  it('lỗi 500 hiện title kèm mã tra cứu', () => {
    const { presenter, add } = setup();
    presenter.present(
      httpError(500, { type: 'about:blank', title: 'Lỗi', status: 500, traceId: 'ff' }),
    );
    expect(add).toHaveBeenCalledWith(
      expect.objectContaining({ severity: 'error', detail: 'Mã tra cứu: ff' }),
    );
  });

  it('lỗi theo trường và xung đột ghi không bật toast mặc định', () => {
    const { presenter, add } = setup();
    const v = presenter.present(
      httpError(422, {
        type: 'about:blank',
        title: 'Dữ liệu không hợp lệ',
        status: 422,
        errors: [{ field: 'percent', message: 'Sai' }],
      }),
    );
    expect(v.fields).toEqual({ percent: 'Sai' });
    expect(presenter.present(httpError(409)).conflict).toBe(true);
    expect(add).not.toHaveBeenCalled();
  });
});

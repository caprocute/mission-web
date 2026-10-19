import { apiUrl } from './api-path';

describe('apiUrl', () => {
  it('dựng đường dẫn /api/v1 từ hợp đồng, mã hoá tham số', () => {
    expect(apiUrl('/tasks/mine')).toBe('/api/v1/tasks/mine');
    expect(apiUrl('/tasks/{id}/progress', { id: 'x y' })).toBe('/api/v1/tasks/x%20y/progress');
    expect(apiUrl('/jobs/{id}', { id: 'J1' })).toBe('/api/v1/jobs/J1');
  });
});

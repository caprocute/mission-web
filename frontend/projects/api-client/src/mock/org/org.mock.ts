import { OrgPerson, OrgUnit } from '../../lib/models/org';
import { MockModule, ok } from '../mock-router';
import fixture from './org.json';

/** Cán bộ của đơn vị kèm khối lượng việc (chỉ máy chủ giả lập dùng cho BR-05-11). */
export type StoredPerson = OrgPerson & {
  workload: { leadCount: number; coCount: number; weeks: number[] };
};

export const ORG_PERSONS = fixture.persons as StoredPerson[];
export const ORG_UNITS = fixture.units as (OrgUnit & { scope: string })[];
/** Người dùng hiện tại trong dữ liệu giả lập (người giao trên SCR-0501). */
export const ORG_ME = fixture.me;
export const WEEK_LABELS = fixture.weekLabels;
export const WEEKLY_THRESHOLD = fixture.weeklyThreshold;

const toPerson = ({ id, name, title, unitId, unitName }: StoredPerson): OrgPerson => ({
  id,
  name,
  title,
  unitId,
  unitName,
});

/** Giả lập `/api/v1/org`: cán bộ và đơn vị trong phạm vi đơn vị người dùng (WEB-028). */
export const ORG_MOCK: MockModule = {
  name: 'org',
  reset: () => undefined,
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/org/persons',
      handle: (r) => {
        const q = r.params.get('q')?.toLowerCase();
        const items = ORG_PERSONS.filter((p) => !q || p.name.toLowerCase().includes(q)).map(
          toPerson,
        );
        return ok({ items, nextCursor: null });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/org/units',
      handle: (r) => {
        const scope = r.params.get('scope') ?? 'unit';
        const items = ORG_UNITS.filter((u) => u.scope === scope).map(({ id, name, parentId }) => ({
          id,
          name,
          parentId,
        }));
        return ok({ items, nextCursor: null });
      },
    },
  ],
};

import { satisfiesRange } from './semver-range';

describe('satisfiesRange (WEB-009)', () => {
  it.each([
    ['2.4.0', '^2.4.0', true],
    ['2.4.0', '^2.0.0', true],
    ['2.4.0', '^2.5.0', false],
    ['2.4.0', '^3.0.0', false],
    ['2.4.0', '^1.9.0', false],
    ['0.3.1', '^0.3.0', true],
    ['0.4.0', '^0.3.0', false],
    ['2.4.0', '~2.4.0', true],
    ['2.5.0', '~2.4.0', false],
    ['2.4.0', '>=2.0.0 <3.0.0', true],
    ['2.4.0', '>= 2.0.0 < 2.4.0', false],
    ['2.4.0', '>2.4.0', false],
    ['2.4.0', '<=2.4.0', true],
    ['2.4.0', '2.x', true],
    ['2.4.0', '2.4', true],
    ['2.4.0', '2.3', false],
    ['2.4.0', '*', true],
    ['2.4.0', '2.4.0', true],
    ['2.4.0', '=2.4.1', false],
    ['2.4.0', '^1.0.0 || ^2.0.0', true],
    ['2.4.0', 'mới nhất', false],
    ['2.4.0', '', false],
  ])('%s khớp "%s" là %s', (version, range, expected) => {
    expect(satisfiesRange(version, range)).toBe(expected);
  });
});

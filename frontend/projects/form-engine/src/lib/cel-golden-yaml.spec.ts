import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  CelRoundingMode,
  CelVariableType,
  countWorkingDays,
  evaluateMissionCel,
  minMucXepLoai,
  MissionCelError,
  roundCelScore,
  sumCelValues,
  WorkingCalendarConfig,
} from './mission-cel';

interface GoldenYamlCase {
  id: string;
  category: string;
  description: string;
  expression: string;
  returnType: CelVariableType;
  scale?: number;
  roundingMode?: CelRoundingMode;
  variableTypes: Record<string, CelVariableType>;
  inputs: Record<string, unknown>;
  expected?: unknown;
  expectedError?: string;
}

interface GoldenYamlSuite {
  calendar: WorkingCalendarConfig;
  cases: GoldenYamlCase[];
}

function parseScalarOrInlineJson(raw: string): unknown {
  const trimmed = raw.trim();
  if (
    trimmed.startsWith('[') ||
    trimmed.startsWith('{') ||
    trimmed.startsWith('"') ||
    trimmed === 'true' ||
    trimmed === 'false' ||
    trimmed === 'null'
  ) {
    return JSON.parse(trimmed);
  }
  if (/^-?\d+(?:\.\d+)?$/.test(trimmed)) {
    return Number(trimmed);
  }
  return trimmed;
}

function parseGoldenYaml(yamlText: string): GoldenYamlSuite {
  const lines = yamlText.split(/\r?\n/);
  const holidays = new Set<string>();
  const compensatoryWorkdays = new Set<string>();
  const cases: GoldenYamlCase[] = [];

  let section: 'root' | 'holidays' | 'compensatory' | 'cases' = 'root';
  let currentCase: GoldenYamlCase | null = null;
  let subMap: 'none' | 'variableTypes' | 'inputs' = 'none';

  for (const rawLine of lines) {
    const trimmed = rawLine.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }

    if (rawLine.startsWith('  holidays:')) {
      section = 'holidays';
      continue;
    }
    if (rawLine.startsWith('  compensatoryWorkingDays:')) {
      section = 'compensatory';
      continue;
    }
    if (rawLine.startsWith('cases:')) {
      section = 'cases';
      continue;
    }

    if (section === 'holidays' && trimmed.startsWith('- ')) {
      holidays.add(JSON.parse(trimmed.slice(2).trim()) as string);
      continue;
    }
    if (section === 'compensatory' && trimmed.startsWith('- ')) {
      compensatoryWorkdays.add(JSON.parse(trimmed.slice(2).trim()) as string);
      continue;
    }

    if (section === 'cases') {
      if (rawLine.startsWith('  - id:')) {
        if (currentCase) {
          cases.push(currentCase);
        }
        currentCase = {
          id: rawLine.split(':')[1].trim(),
          category: '',
          description: '',
          expression: '',
          returnType: 'DYN',
          variableTypes: {},
          inputs: {},
        };
        subMap = 'none';
        continue;
      }

      if (!currentCase) {
        continue;
      }

      if (rawLine.startsWith('      ') && subMap !== 'none') {
        const colonIdx = trimmed.indexOf(':');
        if (colonIdx > 0) {
          const k = trimmed.slice(0, colonIdx).trim();
          const vRaw = trimmed.slice(colonIdx + 1).trim();
          if (subMap === 'variableTypes') {
            currentCase.variableTypes[k] = vRaw as CelVariableType;
          } else if (subMap === 'inputs') {
            currentCase.inputs[k] = parseScalarOrInlineJson(vRaw);
          }
        }
        continue;
      }

      if (rawLine.startsWith('    ')) {
        subMap = 'none';
        const colonIdx = trimmed.indexOf(':');
        if (colonIdx <= 0) {
          continue;
        }
        const key = trimmed.slice(0, colonIdx).trim();
        const valRaw = trimmed.slice(colonIdx + 1).trim();
        switch (key) {
          case 'category':
            currentCase.category = valRaw;
            break;
          case 'description':
            currentCase.description = valRaw;
            break;
          case 'expression':
            currentCase.expression = parseScalarOrInlineJson(valRaw) as string;
            break;
          case 'returnType':
            currentCase.returnType = valRaw as CelVariableType;
            break;
          case 'scale':
            currentCase.scale = Number(valRaw);
            break;
          case 'roundingMode':
            currentCase.roundingMode = valRaw as CelRoundingMode;
            break;
          case 'variableTypes':
            if (valRaw === '{}') {
              currentCase.variableTypes = {};
            } else {
              subMap = 'variableTypes';
            }
            break;
          case 'inputs':
            if (valRaw === '{}') {
              currentCase.inputs = {};
            } else {
              subMap = 'inputs';
            }
            break;
          case 'expected':
            currentCase.expected = parseScalarOrInlineJson(valRaw);
            break;
          case 'expectedError':
            currentCase.expectedError = valRaw;
            break;
        }
      }
    }
  }

  if (currentCase) {
    cases.push(currentCase);
  }

  return {
    calendar: { holidays, compensatoryWorkdays },
    cases,
  };
}

const goldenYamlPath = resolve(process.cwd(), '../data/golden/cel/golden-cases.yaml');
const suite = parseGoldenYaml(readFileSync(goldenYamlPath, 'utf-8'));

describe('NEN-041: đối chiếu 100% bộ Golden Test Suite YAML giữa cel-java và cel-js', () => {
  it('nạp đủ tối thiểu 50 ca kiểm thử chuẩn từ data/golden/cel/golden-cases.yaml', () => {
    expect(suite.cases.length).toBeGreaterThanOrEqual(50);
  });

  for (const testCase of suite.cases) {
    it(`${testCase.id} [${testCase.category}]: ${testCase.description}`, () => {
      if (testCase.expectedError) {
        expect(() =>
          evaluateMissionCel({
            expression: testCase.expression,
            variableTypes: testCase.variableTypes,
            returnType: testCase.returnType,
            inputs: testCase.inputs,
            scale: testCase.scale,
            roundingMode: testCase.roundingMode,
            calendar: suite.calendar,
          }),
        ).toThrowError(
          expect.objectContaining({
            name: 'MissionCelError',
            errorCode: testCase.expectedError,
          }),
        );
      } else {
        const actual = evaluateMissionCel({
          expression: testCase.expression,
          variableTypes: testCase.variableTypes,
          returnType: testCase.returnType,
          inputs: testCase.inputs,
          scale: testCase.scale,
          roundingMode: testCase.roundingMode,
          calendar: suite.calendar,
        });
        expect(actual).toEqual(testCase.expected);
      }
    });
  }

  it('kiểm tra biên các hàm mở rộng sum, min_muc, ngay_lam_viec và roundCelScore', () => {
    expect(sumCelValues([0.1, 0.2, 0.3])).toBe(0.6);
    expect(sumCelValues([])).toBe(0);
    expect(roundCelScore(59.995, 2, 'NUA_LEN')).toBe(60);
    expect(roundCelScore(59.995, 2, 'CAT_BO')).toBe(59.99);
    expect(roundCelScore(89.995, 2, 'HALF_UP')).toBe(90);
    expect(roundCelScore(89.995, 2, 'DOWN')).toBe(89.99);
    expect(minMucXepLoai('HOAN_THANH_XUAT_SAC', 'HOAN_THANH_TOT')).toBe('HTTNV');
    expect(countWorkingDays('2026-03-02', '2026-03-06', suite.calendar)).toBe(4);
    expect(() =>
      evaluateMissionCel({
        expression: '   ',
        variableTypes: {},
        returnType: 'DOUBLE',
      }),
    ).toThrow(MissionCelError);
  });
});

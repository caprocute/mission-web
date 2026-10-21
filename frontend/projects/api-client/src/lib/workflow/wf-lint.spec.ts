import { lintBpmn } from './wf-lint';

/** Dựng tệp BPMN tối giản quanh nội dung của `process`. */
const bpmn = (body: string, procAttrs = '') =>
  `<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">
    <process id="p"${procAttrs}>${body}</process></definitions>`;

const chain = (n: number) => {
  const ids = Array.from({ length: n }, (_, i) => `u${i}`);
  const all = ['s', ...ids, 'e'];
  return [
    '<startEvent id="s"/>',
    ...ids.map((id) => `<userTask id="${id}" name="Bước ${id}"/>`),
    '<endEvent id="e"/>',
    ...all
      .slice(1)
      .map((id, i) => `<sequenceFlow id="f${i}" sourceRef="${all[i]}" targetRef="${id}"/>`),
  ].join('');
};

const rules = (xml: string, opts = {}) => lintBpmn(xml, opts).map((i) => i.rule);

describe('lintBpmn – quy tắc chống mạng nhện (TL-01) và giới hạn tải (BS-01)', () => {
  it('mẫu hợp lệ không có lỗi', () => {
    const ok = bpmn(
      chain(3) +
        '<serviceTask id="k" name="Ký số" flowable:type="external-worker" flowable:topic="ky-so"/>' +
        '<callActivity id="c" calledElement="con"/>',
    );
    expect(lintBpmn(ok)).toEqual([]);
  });

  it('quy tắc 1: cấm gắn tenant', () => {
    expect(rules(bpmn(chain(1), ' flowable:tenantId="t1"'))).toEqual(['khong-gan-tenant']);
  });

  it('quy tắc 2: một tầng call activity (lồng trong tiến trình con hoặc gọi mẫu có call activity)', () => {
    const nested = bpmn(
      '<subProcess id="sp"><callActivity id="c" calledElement="x"/></subProcess>',
    );
    expect(rules(nested)).toEqual(['mot-tang-call-activity']);
    const calls = bpmn('<callActivity id="c" calledElement="me"/>');
    expect(rules(calls, { calledHasCallActivity: (k: string) => k === 'me' })).toEqual([
      'mot-tang-call-activity',
    ]);
  });

  it('quy tắc 3: không phát signal rộng; bắt signal thì được', () => {
    const thrown = bpmn(
      '<intermediateThrowEvent id="t"><signalEventDefinition signalRef="s1"/></intermediateThrowEvent>',
    );
    expect(rules(thrown)).toEqual(['khong-phat-signal']);
    const caught = bpmn(
      '<intermediateCatchEvent id="t"><signalEventDefinition signalRef="s1"/></intermediateCatchEvent>',
    );
    expect(rules(caught)).toEqual([]);
  });

  it('quy tắc 4: service task phải là external worker; phần tử ngoài bảng màu bị chặn', () => {
    expect(rules(bpmn('<serviceTask id="x" flowable:class="a.B"/>'))).toEqual([
      'chi-external-worker',
    ]);
    expect(rules(bpmn('<scriptTask id="x"/><inclusiveGateway id="g"/>'))).toEqual([
      'phan-tu-ngoai-bang-mau',
      'phan-tu-ngoai-bang-mau',
    ]);
  });

  it('tối đa 12 user task trên một nhánh, chỉ ra bước vượt', () => {
    expect(rules(bpmn(chain(12)))).toEqual([]);
    const over = lintBpmn(bpmn(chain(13)));
    expect(over.map((i) => [i.rule, i.elementId])).toEqual([['toi-da-user-task-moi-nhanh', 'u12']]);
  });

  it('đa thể hiện phải có giới hạn: ≤ 50, song song ≤ 20', () => {
    const mi = (inner: string, seq = false) =>
      bpmn(
        `<userTask id="m"><multiInstanceLoopCharacteristics isSequential="${seq}">${inner}</multiInstanceLoopCharacteristics></userTask>`,
      );
    expect(rules(mi('<loopCardinality>20</loopCardinality>'))).toEqual([]);
    expect(rules(mi('<loopCardinality>40</loopCardinality>', true))).toEqual([]);
    expect(rules(mi('<loopCardinality>21</loopCardinality>'))).toEqual(['da-the-hien-co-gioi-han']);
    expect(rules(mi('<loopCardinality>51</loopCardinality>', true))).toEqual([
      'da-the-hien-co-gioi-han',
    ]);
    expect(rules(mi(''))).toEqual(['da-the-hien-co-gioi-han']);
  });

  it('XML hỏng báo một lỗi chung', () => {
    expect(lintBpmn('<definitions').length).toBe(1);
  });
});

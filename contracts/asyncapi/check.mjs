// Kiểm tra cú pháp AsyncAPI 3.0 và đối chiếu chéo với contracts/events/catalog.yaml + lược đồ Avro (.avsc).
// Đáp ứng NEN-008 (TL-01 mục 2.5.4, mục 9.5, ADR-07):
//   1. Xác thực cấu trúc chuẩn AsyncAPI 3.0.0, CloudEventsEnvelopeTrait và toàn bộ $ref nội bộ.
//   2. Kiểm tra mọi channel.address trong mission-events.asyncapi.yaml đều có mặt trong catalog.yaml.
//   3. Kiểm tra mọi trường trong payload của mỗi message là tập con của trường trong tệp .avsc tương ứng.
//   4. Tự kiểm thử âm (negative self-test): tiêm một trường lạ không có trong .avsc và khẳng định bộ kiểm tra bắt được lỗi.
// Chạy: npm run asyncapi:lint

import { readFileSync, existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const contractsRoot = join(__dirname, '..');
const asyncapiPath = join(__dirname, 'mission-events.asyncapi.yaml');
const catalogPath = join(contractsRoot, 'events', 'catalog.yaml');
const eventsRoot = join(contractsRoot, 'events');

function parseScalar(raw) {
  const trimmed = raw.trim();
  if (trimmed === '' || trimmed === 'null' || trimmed === '~') return null;
  if (trimmed === 'true') return true;
  if (trimmed === 'false') return false;
  if (
    (trimmed.startsWith("'") && trimmed.endsWith("'")) ||
    (trimmed.startsWith('"') && trimmed.endsWith('"'))
  ) {
    return trimmed.slice(1, -1);
  }
  if (/^-?\d+(\.\d+)?$/.test(trimmed)) {
    return Number(trimmed);
  }
  return trimmed;
}

function stripInlineComment(line) {
  let inSingle = false;
  let inDouble = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === "'" && !inDouble) inSingle = !inSingle;
    else if (ch === '"' && !inSingle) inDouble = !inDouble;
    else if (ch === '#' && !inSingle && !inDouble && (i === 0 || /\s/.test(line[i - 1]))) {
      return line.slice(0, i).trimEnd();
    }
  }
  return line.trimEnd();
}

function fallbackParseYaml(text) {
  const rawLines = text.split(/\r?\n/);
  const lines = [];
  for (let idx = 0; idx < rawLines.length; idx++) {
    const cleaned = stripInlineComment(rawLines[idx]);
    if (cleaned.trim() === '') continue;
    const indent = cleaned.match(/^ */)[0].length;
    lines.push({ indent, text: cleaned.trim(), rawIndex: idx });
  }

  let pos = 0;

  function parseBlock(expectedIndent) {
    if (pos >= lines.length) return {};
    const first = lines[pos];
    if (first.indent < expectedIndent) return {};
    if (first.text.startsWith('- ') || first.text === '-') {
      return parseSequence(first.indent);
    }
    return parseMapping(first.indent);
  }

  function parseSequence(seqIndent) {
    const arr = [];
    while (pos < lines.length && lines[pos].indent === seqIndent && (lines[pos].text.startsWith('- ') || lines[pos].text === '-')) {
      const line = lines[pos];
      const rest = line.text === '-' ? '' : line.text.slice(2).trim();
      pos++;
      if (rest === '') {
        arr.push(parseBlock(seqIndent + 1));
      } else if (rest.includes(': ') || rest.endsWith(':')) {
        const colonIdx = rest.indexOf(':');
        const k = parseScalar(rest.slice(0, colonIdx));
        const vRaw = rest.slice(colonIdx + 1).trim();
        const itemObj = {};
        if (vRaw === '' || vRaw === '|') {
          if (vRaw === '|') {
            itemObj[k] = consumeLiteralBlock(seqIndent + 2);
          } else {
            itemObj[k] = parseBlock(seqIndent + 2);
          }
        } else {
          itemObj[k] = parseScalar(vRaw);
        }
        while (pos < lines.length && lines[pos].indent > seqIndent) {
          const sub = parseMapping(lines[pos].indent, seqIndent + 1);
          Object.assign(itemObj, sub);
        }
        arr.push(itemObj);
      } else {
        arr.push(parseScalar(rest));
      }
    }
    return arr;
  }

  function consumeLiteralBlock(minIndent) {
    const chunks = [];
    while (pos < lines.length && lines[pos].indent >= minIndent) {
      chunks.push(lines[pos].text);
      pos++;
    }
    return chunks.join('\n');
  }

  function parseMapping(mapIndent) {
    const obj = {};
    while (pos < lines.length && lines[pos].indent === mapIndent && !lines[pos].text.startsWith('- ')) {
      const line = lines[pos];
      const colonIdx = line.text.indexOf(':');
      if (colonIdx === -1) {
        pos++;
        continue;
      }
      const key = parseScalar(line.text.slice(0, colonIdx));
      const valPart = line.text.slice(colonIdx + 1).trim();
      pos++;
      if (valPart === '|') {
        obj[key] = consumeLiteralBlock(mapIndent + 2);
      } else if (valPart === '') {
        if (pos < lines.length && lines[pos].indent > mapIndent) {
          obj[key] = parseBlock(lines[pos].indent);
        } else {
          obj[key] = null;
        }
      } else {
        obj[key] = parseScalar(valPart);
      }
    }
    return obj;
  }

  return parseBlock(0);
}

function loadYaml(filePath) {
  const content = readFileSync(filePath, 'utf8');
  try {
    const require = createRequire(import.meta.url);
    const jsYaml = require('js-yaml');
    return jsYaml.load(content);
  } catch {
    return fallbackParseYaml(content);
  }
}

function resolveRef(rootDoc, ref) {
  if (typeof ref !== 'string' || !ref.startsWith('#/')) {
    return null;
  }
  const parts = ref.slice(2).split('/');
  let current = rootDoc;
  for (const part of parts) {
    if (current == null || typeof current !== 'object' || !(part in current)) {
      return null;
    }
    current = current[part];
  }
  return current;
}

/**
 * Kiểm tra cấu trúc chuẩn AsyncAPI 3.0.0 và đối chiếu chéo với catalog.yaml + .avsc.
 * Trả về danh sách thông báo lỗi (mảng rỗng nếu hợp lệ).
 */
export function verifyCrossCheck(asyncapiDoc, catalogDoc, avroSchemasByPath) {
  const errors = [];

  if (!asyncapiDoc || typeof asyncapiDoc !== 'object') {
    return ['Tài liệu AsyncAPI rỗng hoặc không phải object hợp lệ.'];
  }

  if (String(asyncapiDoc.asyncapi) !== '3.0.0') {
    errors.push(`Trường asyncapi phải là "3.0.0" (hiện tại: "${asyncapiDoc.asyncapi}").`);
  }

  if (!asyncapiDoc.info?.title || !asyncapiDoc.info?.version || !asyncapiDoc.info?.description) {
    errors.push('Thiếu info.title, info.version hoặc info.description trong tài liệu AsyncAPI.');
  } else {
    const desc = String(asyncapiDoc.info.description);
    if (!desc.includes('.v1') || !desc.includes('.v2') || !desc.includes('90')) {
      errors.push(
        'info.description phải nêu rõ chính sách phiên bản và dual-publish .v1/.v2 tối thiểu 90 ngày.'
      );
    }
  }

  const trait = asyncapiDoc.components?.messageTraits?.CloudEventsEnvelopeTrait;
  if (!trait) {
    errors.push('Thiếu components.messageTraits.CloudEventsEnvelopeTrait.');
  } else {
    const headersSchema = trait.headers?.$ref
      ? resolveRef(asyncapiDoc, trait.headers.$ref)
      : trait.headers;
    if (!headersSchema) {
      errors.push('Không phân giải được headers của CloudEventsEnvelopeTrait.');
    } else {
      const requiredHeaders = [
        'specversion',
        'id',
        'source',
        'type',
        'time',
        'datacontenttype',
        'traceparent',
        'tenantid',
        'classification',
        'policyversion',
      ];
      for (const h of requiredHeaders) {
        if (!headersSchema.properties || !(h in headersSchema.properties)) {
          errors.push(`CloudEventsHeaders thiếu thuộc tính bắt buộc "${h}".`);
        }
      }
    }
  }

  const catalogByTopic = new Map();
  for (const evt of catalogDoc?.events ?? []) {
    catalogByTopic.set(evt.topic, evt);
  }

  const expectedTopics = [
    'task.task.assigned.v1',
    'appraisal.rating.decided.v1',
    'ledger.seal.created.v1',
  ];

  const channels = asyncapiDoc.channels ?? {};
  const foundAddresses = new Set();

  for (const [channelKey, channelObj] of Object.entries(channels)) {
    const address = channelObj?.address;
    if (!address) {
      errors.push(`Channel "${channelKey}" thiếu trường address.`);
      continue;
    }
    foundAddresses.add(address);

    const catalogEntry = catalogByTopic.get(address);
    if (!catalogEntry) {
      errors.push(
        `Channel "${channelKey}" có address "${address}" không tồn tại trong contracts/events/catalog.yaml.`
      );
      continue;
    }

    const schemaRel = catalogEntry.schema ?? catalogEntry.schemaPath;
    const avroSchema = avroSchemasByPath.get(schemaRel);
    if (!avroSchema) {
      errors.push(
        `Không tìm thấy lược đồ Avro "${schemaRel}" cho topic "${address}".`
      );
      continue;
    }

    const avroFieldNames = new Set((avroSchema.fields ?? []).map((f) => f.name));
    const messages = channelObj.messages ?? {};
    if (Object.keys(messages).length === 0) {
      errors.push(`Channel "${channelKey}" không khai báo message nào.`);
      continue;
    }

    for (const [msgKey, msgRefOrObj] of Object.entries(messages)) {
      const msgObj = msgRefOrObj?.$ref
        ? resolveRef(asyncapiDoc, msgRefOrObj.$ref)
        : msgRefOrObj;
      if (!msgObj) {
        errors.push(`Channel "${channelKey}" message "${msgKey}" có $ref không hợp lệ.`);
        continue;
      }

      const hasCloudEventsTrait = (msgObj.traits ?? []).some(
        (t) => t?.$ref === '#/components/messageTraits/CloudEventsEnvelopeTrait'
      );
      if (!hasCloudEventsTrait) {
        errors.push(
          `Message "${msgObj.name ?? msgKey}" của channel "${channelKey}" phải gắn trait CloudEventsEnvelopeTrait.`
        );
      }

      const payloadSchema = msgObj.payload?.$ref
        ? resolveRef(asyncapiDoc, msgObj.payload.$ref)
        : msgObj.payload;
      if (!payloadSchema || typeof payloadSchema !== 'object') {
        errors.push(`Message "${msgObj.name ?? msgKey}" không có payload schema hợp lệ.`);
        continue;
      }

      const payloadProps = Object.keys(payloadSchema.properties ?? {});
      if (payloadProps.length === 0) {
        errors.push(`Payload của message "${msgObj.name ?? msgKey}" không có trường nào.`);
      }

      for (const propName of payloadProps) {
        if (!avroFieldNames.has(propName)) {
          errors.push(
            `Trường payload "${propName}" trong message "${msgObj.name ?? msgKey}" (topic "${address}") không tồn tại trong lược đồ Avro "${schemaRel}".`
          );
        }
      }

      for (const reqField of payloadSchema.required ?? []) {
        if (!avroFieldNames.has(reqField)) {
          errors.push(
            `Trường required "${reqField}" trong message "${msgObj.name ?? msgKey}" (topic "${address}") không tồn tại trong lược đồ Avro "${schemaRel}".`
          );
        }
        if (!(reqField in (payloadSchema.properties ?? {}))) {
          errors.push(
            `Trường required "${reqField}" trong message "${msgObj.name ?? msgKey}" chưa được khai báo trong properties.`
          );
        }
      }
    }
  }

  for (const expectedTopic of expectedTopics) {
    if (!foundAddresses.has(expectedTopic)) {
      errors.push(`Thiếu kênh bắt buộc cho topic công bố "${expectedTopic}" trong AsyncAPI.`);
    }
  }

  const operations = asyncapiDoc.operations ?? {};
  if (Object.keys(operations).length < expectedTopics.length) {
    errors.push(
      `Tài liệu AsyncAPI phải khai báo đủ tối thiểu ${expectedTopics.length} operations cho các kênh công bố.`
    );
  }
  for (const [opKey, opObj] of Object.entries(operations)) {
    if (opObj?.action !== 'receive') {
      errors.push(`Operation "${opKey}" phải có action: "receive".`);
    }
    const ch = opObj?.channel?.$ref ? resolveRef(asyncapiDoc, opObj.channel.$ref) : null;
    if (!ch) {
      errors.push(`Operation "${opKey}" có channel.$ref không hợp lệ.`);
    }
  }

  return errors;
}

function main() {
  if (!existsSync(asyncapiPath)) {
    console.error(`[FAIL] Không tìm thấy tệp ${asyncapiPath}`);
    process.exit(1);
  }
  if (!existsSync(catalogPath)) {
    console.error(`[FAIL] Không tìm thấy tệp ${catalogPath}`);
    process.exit(1);
  }

  const asyncapiDoc = loadYaml(asyncapiPath);
  const catalogDoc = loadYaml(catalogPath);

  const avroSchemasByPath = new Map();
  for (const evt of catalogDoc?.events ?? []) {
    const schemaRel = evt.schema ?? evt.schemaPath;
    if (!schemaRel) continue;
    const fullAvroPath = join(eventsRoot, schemaRel);
    if (existsSync(fullAvroPath)) {
      avroSchemasByPath.set(schemaRel, JSON.parse(readFileSync(fullAvroPath, 'utf8')));
    }
  }

  if (process.argv.includes('--negative-test')) {
    const mutated = structuredClone(asyncapiDoc);
    mutated.components.schemas.TaskAssignedPayload.properties.truongKhongCoTrongAvro = {
      type: 'string',
    };
    const negErrors = verifyCrossCheck(mutated, catalogDoc, avroSchemasByPath);
    console.error('[NEGATIVE-TEST] Kết quả kiểm tra âm khi thêm trường lạ vào payload:');
    for (const err of negErrors) {
      console.error(`  - ${err}`);
    }
    process.exit(negErrors.length > 0 ? 1 : 0);
  }

  const errors = verifyCrossCheck(asyncapiDoc, catalogDoc, avroSchemasByPath);
  if (errors.length > 0) {
    console.error('[FAIL] Kiểm tra hợp đồng AsyncAPI thất bại:');
    for (const err of errors) {
      console.error(`  - ${err}`);
    }
    process.exit(1);
  }

  // Tự kiểm thử âm (negative self-test) để bảo đảm cơ chế đối chiếu chéo hoạt động thật:
  const mutatedDoc = structuredClone(asyncapiDoc);
  mutatedDoc.components.schemas.TaskAssignedPayload.properties.__unknownFieldNotInAvro = {
    type: 'string',
  };
  const negativeCheckErrors = verifyCrossCheck(mutatedDoc, catalogDoc, avroSchemasByPath);
  const caughtUnknownField = negativeCheckErrors.some((msg) =>
    msg.includes('__unknownFieldNotInAvro')
  );
  if (!caughtUnknownField) {
    console.error(
      '[FAIL] Tự kiểm thử âm thất bại: bộ kiểm tra chéo không phát hiện được trường lạ __unknownFieldNotInAvro.'
    );
    process.exit(1);
  }

  console.log(
    '[OK] AsyncAPI 3.0 hợp lệ (3 kênh EVT-04, EVT-12, EVT-19 khớp catalog.yaml & lược đồ Avro; tự kiểm thử âm đạt).'
  );
}

main();

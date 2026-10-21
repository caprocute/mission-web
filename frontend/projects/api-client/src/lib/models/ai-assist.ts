import type { components } from '../generated/platform';

type Schemas = components['schemas'];

/**
 * Trợ lý AI soạn OKR của SVC-14 (`POST /api/v1/ai/assist/okr`, QT-17, SCR-1701, WEB-049), bí danh của kiểu sinh
 * từ `contracts/openapi/modules/platform.yaml`. Trợ lý chỉ đọc: gợi ý chỉ được ghi vào OKR bằng API của module okr
 * dưới danh tính người dùng, kèm cờ AI hỗ trợ (BR-02-11, BR-17-08).
 */
export type OkrAssistRequest = Schemas['OkrAssistRequest'];
export type OkrAssistResult = Schemas['OkrAssistResult'];
export type OkrAssistSuggestion = Schemas['OkrAssistSuggestion'];
export type OkrAssistSuggestionKind = OkrAssistSuggestion['kind'];
export type OkrDraftKeyResult = Schemas['OkrDraftKeyResult'];
export type AiGuardrailBlock = Schemas['AiGuardrailBlock'];
export type AiUserAction = Schemas['AiUserAction'];
export type AiSuggestionDecisionRequest = Schemas['AiSuggestionDecisionRequest'];

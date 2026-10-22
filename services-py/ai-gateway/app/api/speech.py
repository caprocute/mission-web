"""transcribeSpeech `POST /api/v1/ai/speech-to-text` (DV-14-07). Thân multipart đọc có giới hạn và tách trong bộ
nhớ bằng thư viện chuẩn `email` (bộ đọc form của Starlette chuyển phần tệp lớn sang tệp tạm trên đĩa, trái yêu
cầu không lưu âm thanh). Khoá chống gửi trùng chỉ lưu băm yêu cầu và mã kết quả, không lưu văn bản: gửi lại cùng
khoá và cùng nội dung thì nhận dạng lại, không ghi nhật ký lần hai (suy luận, wiki/14)."""

from __future__ import annotations

import hashlib
from email.message import Message
from email.parser import BytesParser
from email.policy import HTTP
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from vtplatform import problem as vp

from app.api.assist import CODE_IDEM_MISMATCH, CODE_VALIDATION
from app.api.deps import authorize, state
from app.core import problems
from app.core.auth import Principal
from app.speech import audio, service
from app.speech.recognizer import AsrUnavailable, Unrecognized
from app.store.idempotency import IdempotencyInProgress, IdempotencyMismatch, request_hash

CODE_TOO_LARGE = "AI-413-AUDIO-TOO-LARGE"
MSG_TOO_LARGE = "Đoạn ghi âm quá dài. Đề nghị đồng chí nói ngắn hơn 60 giây."
PURPOSES = ("CHECKIN_VUONG_MAC", "TU_DANH_GIA_GHI_CHU", "KHAC")
_OVERHEAD = 64 * 1024  # phần đầu multipart và các trường chữ

router = APIRouter(prefix="/api/v1/ai")


def _problem(code: str, detail: str, path: str, headers: dict[str, str] | None = None) -> problems.ApiError:
    return problems.ApiError(problems.make(code, detail, path), headers)


def _too_large(path: str) -> problems.ApiError:
    return _problem(CODE_TOO_LARGE, MSG_TOO_LARGE, path)


async def _read(request: Request) -> bytes:
    path = request.url.path
    limit = audio.MAX_BYTES + _OVERHEAD
    try:
        declared = int(request.headers.get("content-length") or 0)
    except ValueError:
        declared = 0
    if declared > limit:
        raise _too_large(path)
    buf = bytearray()
    async for chunk in request.stream():
        buf += chunk
        if len(buf) > limit:
            raise _too_large(path)
    return bytes(buf)


def _fields(request: Request, raw: bytes) -> dict[str, bytes]:
    ctype = request.headers.get("content-type", "")
    if not ctype.lower().startswith("multipart/form-data") or "boundary=" not in ctype:
        raise _problem(CODE_VALIDATION, "Yêu cầu phải gửi kèm đoạn ghi âm.", request.url.path)
    msg = BytesParser(policy=HTTP).parsebytes(b"Content-Type: " + ctype.encode("latin-1") + b"\r\n\r\n" + raw)
    out: dict[str, bytes] = {}
    if msg.is_multipart():
        for part in msg.iter_parts():
            if not isinstance(part, Message):
                continue
            name = part.get_param("name", header="content-disposition")
            payload = part.get_payload(decode=True)
            if isinstance(name, str) and isinstance(payload, bytes) and name not in out:
                out[name] = payload
    return out


def _errors(path: str, e: Exception) -> problems.ApiError:
    if isinstance(e, audio.TooLong):
        return _too_large(path)
    if isinstance(e, audio.BadAudio):
        return _problem(CODE_VALIDATION, "Tệp gửi lên không phải đoạn ghi âm hợp lệ.", path)
    if isinstance(e, Unrecognized):
        return _problem(CODE_VALIDATION, "Chưa nghe rõ nội dung. Đề nghị đồng chí nói lại.", path)
    if isinstance(e, service.Blocked):
        return _problem(CODE_VALIDATION, e.block.message, path)
    if isinstance(e, service.UnsupportedLanguage):
        return _problem(CODE_VALIDATION, "Ngôn ngữ này chưa được hỗ trợ.", path)
    if isinstance(e, service.RateLimited):
        return problems.too_many(path, e.retry_after)
    return problems.unavailable(path, 10)


@router.post("/speech-to-text")
async def transcribe_speech(request: Request, p: Annotated[Principal, Depends(authorize())]) -> JSONResponse:
    path = request.url.path
    key = (request.headers.get("idempotency-key") or "").strip()
    if not key or len(key) > 64:
        raise _problem(vp.CODE_BAD_REQUEST, "Thiếu khoá chống gửi trùng của yêu cầu.", path)
    speech = state(request).speech
    try:
        speech.admit(p)
    except (service.Disabled, service.RateLimited) as e:
        raise _errors(path, e) from e
    f = _fields(request, await _read(request))
    data = f.get("audio") or b""
    language = (f.get("language") or b"vi-VN").decode("utf-8", "replace").strip()
    purpose = (f.get("purpose") or b"KHAC").decode("utf-8", "replace").strip()
    if not data or purpose not in PURPOSES:
        raise _problem(CODE_VALIDATION, "Dữ liệu gửi lên chưa hợp lệ.", path)
    if len(data) > audio.MAX_BYTES:
        raise _too_large(path)
    idem = state(request).idempotency
    digest = request_hash("POST", path, {"audio": hashlib.sha256(data).hexdigest(), "language": language,
                                         "purpose": purpose})
    try:
        stored = await idem.begin(p.tenant_id, p.user_id, key, digest)
    except IdempotencyMismatch as e:
        raise _problem(CODE_IDEM_MISMATCH, "Khoá chống gửi trùng đã dùng cho một yêu cầu khác.", path) from e
    except IdempotencyInProgress as e:
        raise _problem(vp.CODE_CONFLICT, "Yêu cầu trước với cùng khoá đang được xử lý.", path) from e
    try:
        body = await speech.transcribe(p, data, language, purpose)
    except (audio.TooLong, audio.BadAudio, Unrecognized, AsrUnavailable, service.Blocked, service.Busy,
            service.UnsupportedLanguage) as e:
        if stored is None:
            await idem.release(p.tenant_id, p.user_id, key)
        raise _errors(path, e) from e
    except BaseException:
        if stored is None:
            await idem.release(p.tenant_id, p.user_id, key)
        raise
    if stored is None:
        await idem.finish(p.tenant_id, p.user_id, key, 200, {"durationMs": body["durationMs"]}, None)
    return JSONResponse(body)

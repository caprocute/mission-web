"""Các thành phần dùng chung của tiến trình, dựng một lần khi khởi động."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.core.auth import JwtVerifier
from app.core.core_client import CoreClient
from app.core.opa import OpaClient
from app.core.pipeline import Gateway
from app.store.suggestions import SuggestionStore


@dataclass
class State:
    verifier: JwtVerifier
    opa: OpaClient
    core: CoreClient
    gateway: Gateway
    store: SuggestionStore
    pool: Any

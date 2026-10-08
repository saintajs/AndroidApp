"""Unit tests for app/main.py's global sanitized exception handler (design.md
Decisions 5–6 / CA-04, BR-032).

A temporary test-only route that raises is registered on the app instance and
driven via httpx ``ASGITransport``; caplog asserts the full traceback went to
the server log while the client body stays sanitized.
"""

import logging

import httpx
import pytest
from fastapi import FastAPI
from pydantic import BaseModel

SANITIZED_DETAIL = "Error interno del servidor"

# Starlette's ServerErrorMiddleware always re-raises after sending the 500 (so
# real servers can log it); with raise_app_exceptions=False the httpx transport
# hands the test the sanitized response the client would actually receive.
BOOM_TRANSPORT_KWARGS = {"raise_app_exceptions": False}


class _TypedBody(BaseModel):
    name: str


def _register_boom_route(app: FastAPI, message: str) -> str:
    """Register a test-only route that raises RuntimeError(message)."""

    @app.get("/__test__/_boom")
    def boom() -> None:
        raise RuntimeError(message)

    return "/__test__/_boom"


def _register_typed_route(app: FastAPI) -> str:
    """Register a test-only POST route with a typed Pydantic body model."""

    @app.post("/__test__/_typed")
    def typed(body: _TypedBody) -> dict[str, str]:
        return {"name": body.name}

    return "/__test__/_typed"


@pytest.mark.anyio
async def test_unhandled_exception_returns_sanitized_500(api_app: FastAPI) -> None:
    """The client sees exactly the sanitized body — no traceback, no exception
    text (here crafted to look like leaked config), no version details."""
    boom_path = _register_boom_route(api_app, "JWT_SECRET=hunter2 SELECT * FROM users")

    transport = httpx.ASGITransport(app=api_app, **BOOM_TRANSPORT_KWARGS)
    async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.get(boom_path)

    assert response.status_code == 500
    assert response.json() == {"detail": SANITIZED_DETAIL}
    body = response.text
    assert "RuntimeError" not in body
    assert "hunter2" not in body
    assert "JWT_SECRET" not in body
    assert "SELECT" not in body
    assert "Traceback" not in body


@pytest.mark.anyio
async def test_full_traceback_is_logged_server_side(
    api_app: FastAPI, caplog: pytest.LogCaptureFixture
) -> None:
    """While the body stays sanitized, logger.exception records the full
    traceback (with the original message) in the server logs."""
    boom_path = _register_boom_route(api_app, "boom with sensitive context")

    transport = httpx.ASGITransport(app=api_app, **BOOM_TRANSPORT_KWARGS)
    with caplog.at_level(logging.ERROR, logger="app.main"):
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.get(boom_path)

    assert response.status_code == 500
    assert response.json() == {"detail": SANITIZED_DETAIL}

    error_records = [r for r in caplog.records if r.levelno >= logging.ERROR]
    assert error_records, "the unhandled exception was not logged"
    record = error_records[0]
    assert record.exc_info is not None, "logger.exception trace was not attached"
    assert "boom with sensitive context" in caplog.text
    assert "RuntimeError" in caplog.text


@pytest.mark.anyio
async def test_pydantic_422_path_is_untouched(api_app: FastAPI) -> None:
    """Validation failures are never caught: a malformed body on a typed route
    keeps the structured field-level 422 body instead of the sanitized 500."""
    typed_path = _register_typed_route(api_app)

    transport = httpx.ASGITransport(app=api_app)
    async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.post(typed_path, json={"name": 42})

    assert response.status_code == 422
    body = response.json()
    assert isinstance(body["detail"], list)
    assert body["detail"][0]["loc"] == ["body", "name"]
    assert SANITIZED_DETAIL not in response.text

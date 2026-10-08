"""Unit tests for GET /health (app/api/routes/health.py, design.md Decision 7).

The app under test is built with ``create_app()`` and driven via httpx
``ASGITransport`` — no live server, no real network, no PostgreSQL.
"""

from typing import cast

import httpx
import pytest
from fastapi import FastAPI
from fastapi.routing import APIRoute


def _find_api_route(app: FastAPI, path: str, method: str) -> APIRoute:
    """Return the registered APIRoute for (path, method), failing loudly otherwise."""
    for route in app.routes:
        if isinstance(route, APIRoute) and route.path == path and method in route.methods:
            return route
    raise AssertionError(f"route {method} {path} is not registered")


@pytest.mark.anyio
async def test_health_returns_exactly_ok_body(api_app: FastAPI) -> None:
    """GET /health -> 200 with body exactly {\"status\": \"ok\"}."""
    transport = httpx.ASGITransport(app=api_app)
    async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_health_route_has_no_dependencies(api_app: FastAPI) -> None:
    """/health is a public liveness probe: no auth, no rate limit, no dependencies
    at all — route-level and dependant sub-dependency lists must both be empty."""
    route = _find_api_route(api_app, "/health", "GET")

    assert route.dependencies == []
    assert route.dependant.dependencies == []
    assert cast("set[str]", route.methods) == {"GET"}

"""Public liveness probe (design.md Decision 7 / FUNC-008 / BR-032).

Reports process liveness only: no dependencies, no database touch, no auth,
no rate limit. The body is a single sanitized literal — it must never leak
stack traces, configuration, database internals or version details.
"""

from fastapi import APIRouter

router = APIRouter(tags=["health"])


@router.get("/health")
def health() -> dict[str, str]:
    """Return the literal liveness body (sync handler — threadpool)."""
    return {"status": "ok"}

"""FastAPI application factory (design.md Decisions 5–7 / CA-01, CA-03, CA-04).

Exposes :func:`create_app` (builds the app, configures logging once, registers
the global sanitized exception handler and the router registry) plus a
module-level ``app`` so ``uvicorn app.main:app`` works.

Settings are constructed at factory time so a missing mandatory environment
variable fails at startup, never per-request (CA-03). This module does NOT
import ``app.db.session`` — the engine/session wiring is a separate concern.
"""

import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.api.routes import auth, health, incidents, statuses
from app.core.config import Settings

logger = logging.getLogger(__name__)

_LOG_FORMAT = "%(asctime)s %(levelname)s %(name)s %(message)s"
_logging_configured = False


def _configure_logging() -> None:
    """Configure standard-library logging exactly once per process."""
    global _logging_configured
    if _logging_configured:
        return
    logging.basicConfig(level=logging.INFO, format=_LOG_FORMAT)
    _logging_configured = True


def _register_sanitized_exception_handler(app: FastAPI) -> None:
    """Install the global handler for unhandled exceptions (CA-04 / BR-032).

    The client always receives the same sanitized body; the full traceback
    goes to the server log only. Pydantic's 422 handling is left untouched.
    Never log passwords, tokens or Authorization headers — only method + path.
    """

    @app.exception_handler(Exception)
    def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        logger.exception(
            "Unhandled exception while handling request %s %s",
            request.method,
            request.url.path,
            exc_info=exc,
        )
        return JSONResponse(status_code=500, content={"detail": "Error interno del servidor"})


def create_app() -> FastAPI:
    """Build the FastAPI application: settings fail-fast, routers, error handler."""
    Settings()  # Fail fast at factory time naming any missing mandatory variable.
    _configure_logging()

    app = FastAPI(title="MiniSoporte API")

    # Router registry (CA-01): health is live; auth/incidents/statuses are
    # pre-declared placeholders whose endpoints arrive with US-002/003/004.
    app.include_router(health.router)
    app.include_router(auth.router)
    app.include_router(incidents.router)
    app.include_router(statuses.router)

    _register_sanitized_exception_handler(app)
    return app


app = create_app()

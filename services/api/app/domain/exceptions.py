"""Domain-layer exceptions.

Base class for all domain errors. Later stories append subclasses
append-only (e.g. ``InvalidCredentialsError`` in US-002) — never modify or
remove existing exceptions here.
"""


class DomainError(Exception):
    """Base class for all domain errors. Later stories append subclasses append-only."""


class RateLimitExceededError(DomainError):
    """US-002: sign-in failure budget exhausted; ``retry_after_seconds`` is the
    Retry-After value (seconds until the tripped counter's window resets)."""

    def __init__(self, *, retry_after_seconds: int) -> None:
        super().__init__(retry_after_seconds)
        self.retry_after_seconds = retry_after_seconds


class InvalidCredentialsError(DomainError):
    """US-002: uniform sign-in failure — raised identically for an absent email,
    a deactivated account, or a wrong password (BR-002), so the route maps all
    three to the same 401 without revealing which one occurred."""


class IncidentNotFoundError(DomainError):
    """US-003: uniform incident lookup failure — raised identically for a
    non-existent id and for another employee's incident (BR-010), so the route
    maps both to the same 404 without revealing which one occurred."""


class ForbiddenRoleError(DomainError):
    """US-004: the caller's role is not allowed on this operation — raised for
    an employee calling the support-only transition (INSTR-004, BR-022) BEFORE
    any incident lookup, so the route maps it to 403
    ``{"detail": "No autorizado"}`` without leaking id existence."""


class InvalidTransitionError(DomainError):
    """US-004: linear-matrix violation (backwards, skipped, repeated, or out
    of the terminal ``resolved`` state) — raised by
    :class:`~app.domain.services.status_service.StatusService` before any
    write, so the route maps it to 409
    ``{"detail": "Transición de estado no válida"}`` (INSTR-003, BR-023/024)."""

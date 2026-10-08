"""Idempotent demo seed for the internal-test environment (US-010).

A manually invoked CLI module (``python -m app.seed.demo_seed``) — NO endpoint,
NO route (INSTR-010). It inserts rows only, never schema (CONF-008), through the
ORM exclusively (no raw SQL). Configuration is read ONLY from the DI-provided
:class:`app.core.config.Settings` object (CONF-006 — the process environment is
never touched here); the three DEMO_* variables are optional at application
startup and validated by this module, which aborts naming every missing one.
The seed refuses ``ENVIRONMENT=production`` regardless of which demo variables
are set (BR-033).

Idempotency works on natural keys: the two normalized (trimmed, lowercased —
INSTR-006, BR-008) emails for users and fixed demo titles for incidents.
Existing rows are left untouched; the whole run commits once. Passwords are
hashed with the shared bcrypt helper from :mod:`app.core.security` (BR-026) —
no default password exists anywhere (BR-033).
"""

import datetime
from collections.abc import Generator
from typing import cast

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core.config import Settings, get_settings
from app.core.security import hash_password
from app.db.session import get_db
from app.domain.models import Incident, IncidentHistory, User

DEMO_EMPLOYEE_EMAIL = "empleado@minisoporte.test"
"""Email of the seeded demo employee (already normalized — a natural idempotency key)."""

# Fixed demo values (design.md Decision 3). The titles double as the seed's
# incident idempotency key; every value sits inside the CHECK-constrained sets
# (title 5..100, description 10..1000, category/priority/status per the schema).
_DEMO_INCIDENTS: tuple[dict[str, str], ...] = (
    {
        "title": "Impresora no responde",
        "description": (
            "La impresora de recepción no responde desde esta mañana; no enciende "
            "ni acepta trabajos de impresión."
        ),
        "category": "equipment",
        "priority": "medium",
        "status": "pending",
    },
    {
        "title": "Equipo sin conexión de red",
        "description": (
            "El equipo del área de finanzas no se conecta a la red interna desde el "
            "cambio de mobiliario; el cable parece estar en buen estado."
        ),
        "category": "systems",
        "priority": "high",
        "status": "in_progress",
    },
    {
        "title": "Acceso al edificio bloqueado",
        "description": (
            "La tarjeta de acceso del usuario no abre la puerta principal del "
            "edificio; ya fue reregistrada sin éxito y requiere revisión."
        ),
        "category": "access",
        "priority": "high",
        "status": "resolved",
    },
)


def _normalize_email(email: str) -> str:
    """Normalize an email exactly as sign-in does (INSTR-006: trimmed, lowercased)."""
    return email.strip().lower()


def _required_demo_settings(settings: Settings) -> tuple[str, str, str]:
    """Collect the three DEMO_* variables or abort naming every missing one.

    Abort raises :class:`SystemExit` (clear message, non-zero CLI exit, no
    traceback); the variable names appear verbatim in the message.
    """
    required: tuple[tuple[str, str | None], ...] = (
        ("DEMO_EMPLOYEE_PASSWORD", settings.DEMO_EMPLOYEE_PASSWORD),
        ("DEMO_SUPPORT_PASSWORD", settings.DEMO_SUPPORT_PASSWORD),
        ("DEMO_SUPPORT_EMAIL", settings.DEMO_SUPPORT_EMAIL),
    )
    missing = [name for name, value in required if value is None or not value.strip()]
    if missing:
        raise SystemExit("Missing required environment variable(s): " + ", ".join(missing))
    # Validated non-blank above; narrowed for mypy only.
    return cast("tuple[str, str, str]", tuple(value for _, value in required))


def run_demo_seed(session: Session, settings: Settings) -> None:
    """Seed the demo rows idempotently inside a single transaction.

    Order of gates: the production refusal comes FIRST (it must hold regardless
    of which demo variables are set), then the missing-variable abort naming
    every missing variable. Missing rows are inserted; existing rows are left
    untouched; one commit covers the whole run (a no-op run skips it).
    """
    if settings.ENVIRONMENT == "production":
        raise SystemExit(
            "Refusing to run the demo seed: ENVIRONMENT is 'production'. "
            "Demo data must never be created in a production environment."
        )

    employee_password, support_password, support_email_raw = _required_demo_settings(settings)
    support_email = _normalize_email(support_email_raw)
    if support_email == DEMO_EMPLOYEE_EMAIL:
        raise SystemExit(
            f"DEMO_SUPPORT_EMAIL must differ from the demo employee email ({DEMO_EMPLOYEE_EMAIL})."
        )

    employee = session.scalar(select(User).where(User.email == DEMO_EMPLOYEE_EMAIL))
    if employee is None:
        employee = User(
            email=DEMO_EMPLOYEE_EMAIL,
            password_hash=hash_password(employee_password),
            role="employee",
        )
        session.add(employee)
        session.flush()  # assign the server-generated UUID before FK use

    support = session.scalar(select(User).where(User.email == support_email))
    if support is None:
        support = User(
            email=support_email,
            password_hash=hash_password(support_password),
            role="support",
        )
        session.add(support)
        session.flush()

    # Deterministic timeline: creation at ``base``, first transition +1h, second
    # +2h — strictly increasing and aligned with created_at/updated_at.
    base = datetime.datetime.now(datetime.timezone.utc)
    first_transition = base + datetime.timedelta(hours=1)
    second_transition = base + datetime.timedelta(hours=2)
    inserted_any = False

    for spec in _DEMO_INCIDENTS:
        existing = session.scalar(select(Incident).where(Incident.title == spec["title"]))
        if existing is not None:
            continue
        incident = Incident(
            owner_id=employee.id,
            title=spec["title"],
            description=spec["description"],
            category=spec["category"],
            priority=spec["priority"],
            status=spec["status"],
            created_at=base,
            updated_at=base,
        )
        session.add(incident)
        session.flush()  # assign the UUID before the history FK references it
        inserted_any = True

        # Creation entry (previous_status NULL, changed_by the owner) is always
        # present (design.md Decision 4); transition entries mirror what the
        # status service writes at runtime (INSTR-002/INSTR-005).
        session.add(
            IncidentHistory(
                incident_id=incident.id,
                previous_status=None,
                new_status="pending",
                changed_by=employee.id,
                changed_at=base,
            )
        )
        if spec["status"] == "in_progress":
            incident.updated_at = first_transition
            session.add(
                IncidentHistory(
                    incident_id=incident.id,
                    previous_status="pending",
                    new_status="in_progress",
                    changed_by=support.id,
                    changed_at=first_transition,
                )
            )
        elif spec["status"] == "resolved":
            incident.updated_at = second_transition
            session.add(
                IncidentHistory(
                    incident_id=incident.id,
                    previous_status="pending",
                    new_status="in_progress",
                    changed_by=support.id,
                    changed_at=first_transition,
                )
            )
            session.add(
                IncidentHistory(
                    incident_id=incident.id,
                    previous_status="in_progress",
                    new_status="resolved",
                    changed_by=support.id,
                    changed_at=second_transition,
                )
            )

    if inserted_any:
        session.commit()


def _demo_row_count(session: Session, support_email: str) -> int:
    """Count the seed-owned rows (users + demo incidents + their history)."""
    user_count = session.scalar(
        select(func.count())
        .select_from(User)
        .where(User.email.in_([DEMO_EMPLOYEE_EMAIL, support_email]))
    )
    titles = [spec["title"] for spec in _DEMO_INCIDENTS]
    demo_incident_ids = select(Incident.id).where(Incident.title.in_(titles))
    incident_count = session.scalar(
        select(func.count()).select_from(Incident).where(Incident.title.in_(titles))
    )
    history_count = session.scalar(
        select(func.count())
        .select_from(IncidentHistory)
        .where(IncidentHistory.incident_id.in_(demo_incident_ids))
    )
    return int(user_count or 0) + int(incident_count or 0) + int(history_count or 0)


def main() -> None:
    """CLI entry: build settings + session via the existing DI seams and seed."""
    settings = get_settings()
    support_email = _normalize_email(settings.DEMO_SUPPORT_EMAIL or "")
    # get_db is declared as Iterator[Session]; the concrete generator's close()
    # is what runs its finally (session.close) — narrowed for mypy only.
    db = cast("Generator[Session, None, None]", get_db())
    session = next(db)
    try:
        before = _demo_row_count(session, support_email)
        run_demo_seed(session, settings)
        after = _demo_row_count(session, support_email)
    finally:
        db.close()
    created = after - before
    if created:
        print(f"Demo seed complete: created {created} row(s) across the three tables.")
    else:
        print("Demo data already present — nothing to do.")


if __name__ == "__main__":
    main()

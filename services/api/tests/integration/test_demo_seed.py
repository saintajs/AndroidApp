"""Integration tests: ``run_demo_seed`` against the real database (US-010).

Exercises ``app/seed/demo_seed.py::run_demo_seed`` on the same harness as the
other integration suites: the database comes from ``TEST_DATABASE_URL``
(Alembic ``upgrade head`` per session, applied by ``tests/conftest.py``) and
every test runs inside the per-test rolled-back transaction of the ``db``
fixture — ``run_demo_seed`` calls ``session.commit()``, which under
``join_transaction_mode="create_savepoint"`` releases an inner savepoint and
keeps the rows visible for the test while the outer transaction is rolled back
afterwards, so seed runs are isolated and repeatable.

Settings are built from ``monkeypatch.setenv`` DEMO_* variables on top of the
mandatory trio (testing.yaml: "settings built with monkeypatched DEMO_* per
scenario"); the support email is passed padded and mixed-case ON PURPOSE so
the stored row proves the seed normalizes (trims + lowercases) emails.

The shared sandbox database can carry LEFTOVER demo rows from an earlier live
proof (same fixed titles, same employee email). The suite is therefore
delta-aware: the pre-test state is captured first, and every count delta and
strict-actor assertion accounts for rows the run reused instead of re-created
(idempotency by natural key is exactly the behaviour under test):

- users delta: +1 support (a throwaway email can never pre-exist) plus +1
  employee only when ``empleado@minisoporte.test`` was absent.
- incidents/history delta: +3/+6 only when the demo titles were absent. When
  the run REUSED leftover incidents, transition ``changed_by`` is asserted
  against a support-role user (not this run's throwaway support id).

Asserted behaviour (spec, testing.yaml critical_cases):
- CA-01/CA-02 single full run: both users present with normalized emails and
  bcrypt-verifiable hashes (employee hash only when this run created the row),
  exactly 3 demo incidents all owned by the employee covering
  pending/in_progress/resolved, per-incident NULL-previous creation entries
  plus transition timelines matching each status with consistent
  changed_by/changed_at ordering aligned to created_at/updated_at.
- CA-05 idempotency: a second run with the same settings leaves the
  users/incidents/incident_history counts and the demo rows'
  created_at/updated_at untouched.
- CA-04 integration proof: ``ENVIRONMENT=production`` raises ``SystemExit``
  and writes nothing.
"""

import uuid

import pytest
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core.config import Settings
from app.core.security import verify_password
from app.domain.models import Incident, IncidentHistory, User
from app.seed.demo_seed import _DEMO_INCIDENTS, DEMO_EMPLOYEE_EMAIL, run_demo_seed

pytestmark = pytest.mark.integration

# The SAME 32+ byte secret the integration run environment exports as
# JWT_SECRET (Settings construction requires the mandatory trio).
JWT_SECRET = "integration-harness-only-secret-please-32b"

# Throwaway fixture passwords — never real credentials (testing.yaml rules).
DEMO_EMPLOYEE_PASSWORD = "it-demo-pass-emp"
DEMO_SUPPORT_PASSWORD = "it-demo-pass-sup"

# Padded + mixed-case ON PURPOSE: the stored row must be the NORMALIZED form
# (trimmed, lowercased) per the spec's "normalized emails" requirement.
DEMO_SUPPORT_EMAIL_RAW = "  Seed-IT-Support@minisoporte.test "
DEMO_SUPPORT_EMAIL = "seed-it-support@minisoporte.test"

_DEMO_TITLES = [spec["title"] for spec in _DEMO_INCIDENTS]
#: Per status: transition count and the exact (previous_status, new_status) pairs.
_TIMELINE = {
    "pending": [],
    "in_progress": [("pending", "in_progress")],
    "resolved": [("pending", "in_progress"), ("in_progress", "resolved")],
}


def _seed_settings(
    monkeypatch: pytest.MonkeyPatch, test_database_url: str, *, environment: str = "test"
) -> Settings:
    """Settings for a seed run: mandatory trio + DEMO_* via ``monkeypatch``."""
    monkeypatch.setenv("DEMO_EMPLOYEE_PASSWORD", DEMO_EMPLOYEE_PASSWORD)
    monkeypatch.setenv("DEMO_SUPPORT_PASSWORD", DEMO_SUPPORT_PASSWORD)
    monkeypatch.setenv("DEMO_SUPPORT_EMAIL", DEMO_SUPPORT_EMAIL_RAW)
    return Settings(DATABASE_URL=test_database_url, JWT_SECRET=JWT_SECRET, ENVIRONMENT=environment)


def _table_counts(session: Session) -> dict[str, int]:
    """Global record counts of the three seeded tables."""
    users = int(session.scalar(select(func.count()).select_from(User)) or 0)
    incidents = int(session.scalar(select(func.count()).select_from(Incident)) or 0)
    history = int(session.scalar(select(func.count()).select_from(IncidentHistory)) or 0)
    return {"users": users, "incidents": incidents, "incident_history": history}


def _demo_incidents(session: Session) -> list[Incident]:
    """Every incident whose title is one of the seed's fixed demo titles."""
    return list(session.scalars(select(Incident).where(Incident.title.in_(_DEMO_TITLES))))


def _history_for(session: Session, incident: Incident) -> list[IncidentHistory]:
    """The incident's history ordered by ``changed_at`` (the timeline order)."""
    return list(
        session.scalars(
            select(IncidentHistory)
            .where(IncidentHistory.incident_id == incident.id)
            .order_by(IncidentHistory.changed_at)
        )
    )


def _assert_timeline(
    session: Session,
    incident: Incident,
    *,
    employee_id: uuid.UUID,
    support_id: uuid.UUID,
    created_by_this_run: bool,
) -> None:
    """CA-02: creation entry + status-matching transitions, ordered, aligned.

    When the run CREATED the incident, transition actors are asserted by exact
    id (this run's support agent). When the run reused pre-existing demo rows,
    the actor is asserted by role — the leftover rows were written by another
    support user, and re-creation would violate the idempotency under test.
    """
    entries = _history_for(session, incident)
    expected_transitions = _TIMELINE[incident.status]
    assert len(entries) == len(expected_transitions) + 1

    creation = entries[0]
    assert creation.previous_status is None
    assert creation.new_status == "pending"
    assert creation.changed_by == employee_id
    assert creation.changed_at == incident.created_at

    transitions = entries[1:]
    for entry, (previous_status, new_status) in zip(transitions, expected_transitions, strict=True):
        assert entry.previous_status == previous_status
        assert entry.new_status == new_status
        if created_by_this_run:
            assert entry.changed_by == support_id
        else:
            actor = session.get(User, entry.changed_by)
            assert actor is not None
            assert actor.role == "support"
        assert entry.changed_at >= incident.created_at

    changed_ats = [entry.changed_at for entry in entries]
    assert changed_ats == sorted(changed_ats)  # ordered...
    assert len(set(changed_ats)) == len(changed_ats)  # ...STRICTLY increasing
    if transitions:
        assert transitions[-1].changed_at == incident.updated_at


# ---------------------------------------------------------------------------
# CA-01/CA-02 — a single run with full configuration
# ---------------------------------------------------------------------------


def test_single_full_run_seeds_normalized_users_and_consistent_history(
    db: Session, test_database_url: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """One ``run_demo_seed`` call: 2 normalized + bcrypt-hashed users, exactly
    3 owned incidents (one per status) and per-incident consistent history."""
    settings = _seed_settings(monkeypatch, test_database_url)
    baseline = _table_counts(db)
    employee_pre_existed = (
        db.scalar(select(User).where(User.email == DEMO_EMPLOYEE_EMAIL)) is not None
    )
    pre_existing_titles = {incident.title for incident in _demo_incidents(db)}
    # Exact per-spec deltas, valid against a clean DB AND against leftover rows.
    created_specs = [spec for spec in _DEMO_INCIDENTS if spec["title"] not in pre_existing_titles]
    expected_history_delta = sum(1 + len(_TIMELINE[spec["status"]]) for spec in created_specs)

    run_demo_seed(db, settings)
    db.expire_all()

    # Count deltas: the throwaway support email is new every time; the
    # employee/incidents/history only when the natural keys were absent.
    after = _table_counts(db)
    assert after["users"] - baseline["users"] == 1 + (0 if employee_pre_existed else 1)
    assert after["incidents"] - baseline["incidents"] == len(created_specs)
    assert after["incident_history"] - baseline["incident_history"] == expected_history_delta

    # Users: normalized emails, correct roles, bcrypt-verifiable hashes.
    employee = db.scalar(select(User).where(User.email == DEMO_EMPLOYEE_EMAIL))
    assert employee is not None
    assert employee.role == "employee"
    assert employee.email == DEMO_EMPLOYEE_EMAIL  # already normalized
    support = db.scalar(select(User).where(User.email == DEMO_SUPPORT_EMAIL))
    assert support is not None
    assert support.role == "support"
    assert support.email == DEMO_SUPPORT_EMAIL  # trimmed + lowercased from the padded env
    assert verify_password(DEMO_SUPPORT_PASSWORD, support.password_hash)
    if not employee_pre_existed:  # a reused row's hash is none of this run's business
        assert verify_password(DEMO_EMPLOYEE_PASSWORD, employee.password_hash)

    # Incidents: exactly 3 demo titles, ALL owned by the employee, one per status.
    demo = _demo_incidents(db)
    assert len(demo) == len(_DEMO_INCIDENTS)
    assert {incident.title for incident in demo} == set(_DEMO_TITLES)
    assert all(incident.owner_id == employee.id for incident in demo)
    assert sorted(incident.status for incident in demo) == [
        "in_progress",
        "pending",
        "resolved",
    ]

    created_by_this_run = not pre_existing_titles
    by_title = {incident.title: incident for incident in demo}
    for incident in demo:
        _assert_timeline(
            db,
            incident,
            employee_id=employee.id,
            support_id=support.id,
            created_by_this_run=created_by_this_run,
        )
    # Spot-check the mapping: the resolved incident carries BOTH transitions.
    resolved = by_title["Acceso al edificio bloqueado"]
    pairs = [(e.previous_status, e.new_status) for e in _history_for(db, resolved)[1:]]
    assert pairs == [("pending", "in_progress"), ("in_progress", "resolved")]


# ---------------------------------------------------------------------------
# CA-05 — a second run changes nothing
# ---------------------------------------------------------------------------


def test_second_run_is_idempotent_and_touches_no_existing_row(
    db: Session, test_database_url: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """CA-05: re-running with the SAME settings keeps every table's count AND
    every demo row's timestamps/password hash byte-identical."""
    settings = _seed_settings(monkeypatch, test_database_url)

    run_demo_seed(db, settings)
    counts_after_first = _table_counts(db)
    snapshot = {
        incident.title: (incident.created_at, incident.updated_at)
        for incident in _demo_incidents(db)
    }
    support_hash = db.scalar(select(User.password_hash).where(User.email == DEMO_SUPPORT_EMAIL))

    run_demo_seed(db, settings)  # the second, no-op run

    assert _table_counts(db) == counts_after_first
    db.expire_all()  # force a real reload — never trust the identity map
    assert {
        incident.title: (incident.created_at, incident.updated_at)
        for incident in _demo_incidents(db)
    } == snapshot
    assert (
        db.scalar(select(User.password_hash).where(User.email == DEMO_SUPPORT_EMAIL))
        == support_hash
    )


# ---------------------------------------------------------------------------
# CA-04 — production refusal writes nothing (integration proof)
# ---------------------------------------------------------------------------


def test_production_environment_refuses_and_writes_nothing(
    db: Session, test_database_url: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """``ENVIRONMENT=production``: SystemExit with a clear refusal message and
    zero writes to any of the three tables."""
    settings = _seed_settings(monkeypatch, test_database_url, environment="production")
    baseline = _table_counts(db)

    with pytest.raises(SystemExit) as excinfo:
        run_demo_seed(db, settings)

    assert "production" in str(excinfo.value)
    assert _table_counts(db) == baseline

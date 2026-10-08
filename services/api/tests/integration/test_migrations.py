"""Integration tests: migration ``0001_initial_schema`` against real PostgreSQL.

Asserts the behaviour required by the spec's entity-model requirements
(users / incidents / incident_history) on the schema that ``alembic upgrade
head`` actually creates: UUID PK server defaults, ``ON DELETE RESTRICT``
foreign keys, every CHECK constraint, ``uq_users_email``, the two composite
FK indexes and per-test rollback isolation.

All database access is parameterized SQLAlchemy Core against the ORM models —
no string-interpolated SQL. Every test runs inside the per-test transaction
from the ``db`` fixture, so nothing is ever committed.
"""

import uuid

import pytest
from sqlalchemy import (
    Executable,
    delete,
    func,
    insert,
    select,
    text,
)
from sqlalchemy.engine import Inspector
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.domain.models import Incident, IncidentHistory, User

pytestmark = pytest.mark.integration

# Fixed email for the rollback-isolation sentinel: one test inserts a user with
# it, another asserts it is absent. Both assertions hold whichever order the
# tests run in, because the insertion transaction is always rolled back.
SENTINEL_EMAIL = "rollback-sentinel@example.com"

# bcrypt-shaped 60-char placeholder; the hash itself is out of scope here.
_PASSWORD_HASH = "$2b$12$" + "a" * 53


def _user_values(**overrides: object) -> dict[str, object]:
    """Column values for a valid ``users`` row, with per-case overrides."""
    values: dict[str, object] = {
        "email": f"{uuid.uuid4().hex}@example.com",
        "password_hash": _PASSWORD_HASH,
        "role": "employee",
    }
    values.update(overrides)
    return values


def _incident_values(owner_id: uuid.UUID, **overrides: object) -> dict[str, object]:
    """Column values for a valid ``incidents`` row, with per-case overrides."""
    values: dict[str, object] = {
        "owner_id": owner_id,
        "title": "Laptop will not start",
        "description": "The laptop shows no signs of power at all",
        "category": "equipment",
        "priority": "medium",
    }
    values.update(overrides)
    return values


def _history_values(
    incident_id: uuid.UUID, changed_by: uuid.UUID, **overrides: object
) -> dict[str, object]:
    """Column values for a valid ``incident_history`` row (NULL previous)."""
    values: dict[str, object] = {
        "incident_id": incident_id,
        "previous_status": None,
        "new_status": "pending",
        "changed_by": changed_by,
    }
    values.update(overrides)
    return values


def _seed_user(db: Session, user_id: uuid.UUID) -> None:
    db.execute(insert(User).values(**_user_values(id=user_id)))


def _seed_incident(db: Session, user_id: uuid.UUID, incident_id: uuid.UUID) -> None:
    db.execute(insert(Incident).values(**_incident_values(user_id, id=incident_id)))


def _assert_rejected(db: Session, statement: Executable, dbapi_error: str) -> None:
    """Run a statement inside a savepoint and assert the database rejects it.

    ``dbapi_error`` names the psycopg exception class the violation must raise
    (e.g. ``CheckViolation``), so a rejection cannot be confused with another
    kind of integrity failure.
    """
    with (
        pytest.raises(IntegrityError) as exc_info,
        db.begin_nested(),
    ):
        db.execute(statement)
    violation = exc_info.value.orig
    assert violation is not None
    assert violation.__class__.__name__ == dbapi_error


def test_expected_tables_exist(introspector: Inspector) -> None:
    table_names = set(introspector.get_table_names(schema="public"))
    assert {"users", "incidents", "incident_history", "alembic_version"} <= table_names


def test_uuid_primary_keys_default_to_gen_random_uuid(db: Session) -> None:
    statement = text(
        "SELECT column_default FROM information_schema.columns "
        "WHERE table_schema = 'public' AND table_name = :table_name "
        "AND column_name = 'id'"
    )
    for table_name in ("users", "incidents", "incident_history"):
        column_default = db.execute(statement, {"table_name": table_name}).scalar_one()
        assert column_default == "gen_random_uuid()", table_name


def test_on_delete_restrict_rejects_deleting_referenced_rows(db: Session) -> None:
    user_id, incident_id = uuid.uuid4(), uuid.uuid4()
    _seed_user(db, user_id)
    _seed_incident(db, user_id, incident_id)
    db.execute(insert(IncidentHistory).values(**_history_values(incident_id, changed_by=user_id)))

    # incidents.owner_id and incident_history.changed_by both reference the user:
    # deleting it must be rejected with a foreign-key violation.
    with (
        pytest.raises(IntegrityError) as user_exc,
        db.begin_nested(),
    ):
        db.execute(delete(User).where(User.id == user_id))
    assert user_exc.value.orig is not None
    assert user_exc.value.orig.__class__.__name__ == "ForeignKeyViolation"
    # …and incident_history.incident_id references the incident.
    with (
        pytest.raises(IntegrityError) as incident_exc,
        db.begin_nested(),
    ):
        db.execute(delete(Incident).where(Incident.id == incident_id))
    assert incident_exc.value.orig is not None
    assert incident_exc.value.orig.__class__.__name__ == "ForeignKeyViolation"


@pytest.mark.parametrize(
    ("target", "overrides", "case"),
    [
        (User, {"role": "admin"}, "role outside employee/support"),
        (Incident, {"title": "abcd"}, "title length 4 (below 5)"),
        (Incident, {"title": "x" * 101}, "title length 101 (above 100)"),
        (Incident, {"description": "d" * 9}, "description length 9 (below 10)"),
        (Incident, {"description": "d" * 1001}, "description length 1001 (above 1000)"),
        (Incident, {"category": "network"}, "category outside the allowed list"),
        (Incident, {"priority": "urgent"}, "priority outside the allowed list"),
        (Incident, {"status": "closed"}, "status outside the allowed list"),
        (IncidentHistory, {"previous_status": "cancelled"}, "invalid previous_status"),
        (IncidentHistory, {"new_status": "cancelled"}, "invalid new_status"),
    ],
)
def test_check_constraints_reject_invalid_values(
    db: Session, target: type, overrides: dict[str, object], case: str
) -> None:
    user_id, incident_id = uuid.uuid4(), uuid.uuid4()
    _seed_user(db, user_id)
    _seed_incident(db, user_id, incident_id)

    if target is User:
        statement = insert(User).values(**_user_values(**overrides))
    elif target is Incident:
        statement = insert(Incident).values(**_incident_values(user_id, **overrides))
    else:
        statement = insert(IncidentHistory).values(
            **_history_values(incident_id, user_id, **overrides)
        )

    _assert_rejected(db, statement, "CheckViolation")


@pytest.mark.parametrize("role", ["employee", "support"])
def test_users_role_accepts_valid_values(db: Session, role: str) -> None:
    db.execute(insert(User).values(**_user_values(role=role)))


def test_incident_boundary_lengths_are_accepted(db: Session) -> None:
    user_id = uuid.uuid4()
    _seed_user(db, user_id)
    db.execute(
        insert(Incident).values(
            **_incident_values(
                user_id,
                title="a" * 5,
                description="d" * 10,
            )
        )
    )
    db.execute(
        insert(Incident).values(
            **_incident_values(
                user_id,
                title="b" * 100,
                description="e" * 1000,
            )
        )
    )


def test_incident_status_defaults_to_pending_at_db_level(db: Session) -> None:
    user_id, incident_id = uuid.uuid4(), uuid.uuid4()
    _seed_user(db, user_id)
    _seed_incident(db, user_id, incident_id)
    status = db.execute(select(Incident.status).where(Incident.id == incident_id)).scalar_one()
    assert status == "pending"


def test_previous_status_null_is_accepted_as_initial_entry(db: Session) -> None:
    user_id, incident_id = uuid.uuid4(), uuid.uuid4()
    _seed_user(db, user_id)
    _seed_incident(db, user_id, incident_id)
    db.execute(
        insert(IncidentHistory).values(
            **_history_values(incident_id, user_id, previous_status=None)
        )
    )


def test_uq_users_email_rejects_duplicate_email(db: Session) -> None:
    duplicated_email = f"{uuid.uuid4().hex}@example.com"
    db.execute(insert(User).values(**_user_values(email=duplicated_email)))
    _assert_rejected(
        db, insert(User).values(**_user_values(email=duplicated_email)), "UniqueViolation"
    )


def test_declared_composite_indexes_exist(introspector: Inspector) -> None:
    incident_indexes = {
        index["name"]: index["column_names"]
        for index in introspector.get_indexes("incidents", schema="public")
    }
    history_indexes = {
        index["name"]: index["column_names"]
        for index in introspector.get_indexes("incident_history", schema="public")
    }
    assert incident_indexes["ix_incidents_owner_created"] == ["owner_id", "created_at"]
    assert history_indexes["ix_incident_history_incident_changed"] == [
        "incident_id",
        "changed_at",
    ]


def test_sentinel_row_is_visible_within_its_own_transaction(db: Session) -> None:
    db.execute(insert(User).values(**_user_values(email=SENTINEL_EMAIL)))
    sentinel_count = db.execute(
        select(func.count()).select_from(User).where(User.email == SENTINEL_EMAIL)
    ).scalar_one()
    assert sentinel_count == 1


def test_sentinel_row_is_invisible_after_rollback(db: Session) -> None:
    # A row inserted by test_sentinel_row_is_visible_within_its_own_transaction
    # must never leak into this test — and this assertion also holds when this
    # test runs first — because every test's transaction is rolled back.
    sentinel_count = db.execute(
        select(func.count()).select_from(User).where(User.email == SENTINEL_EMAIL)
    ).scalar_one()
    assert sentinel_count == 0

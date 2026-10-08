"""Unit tests for app/db/repositories/history_repository.py (US-003, testing.yaml
"IncidentService / HistoryRepository unit level").

``HistoryRepository`` is exercised against an in-memory fake session double —
no database, no network. The repository is append-only by contract (INSTR-005,
BR-012): ``append`` only stages on the session (the SERVICE commits), and the
class exposes no update or delete method at all. ``list_for_incident`` is the
single read, ordered ``changed_at`` ASC inside the query itself.
"""

import uuid

from sqlalchemy import Select

from app.db.repositories.history_repository import HistoryRepository
from app.domain.models import IncidentHistory

_OWNER_ID = uuid.uuid4()
_INCIDENT_ID = uuid.uuid4()


class FakeHistorySession:
    """In-memory session double recording ``add``/``commit``/``scalars`` calls.

    ``scalars`` captures the executed statement so tests can assert the
    ordering is enforced BY THE QUERY, and returns a canned result whose order
    the repository must pass through untouched.
    """

    def __init__(self) -> None:
        self.added: list[IncidentHistory] = []
        self.commit_calls = 0
        self.scalars_statements: list[Select[tuple[IncidentHistory]]] = []
        self.scalars_result: list[IncidentHistory] = []

    def add(self, entry: IncidentHistory) -> None:
        self.added.append(entry)

    def commit(self) -> None:
        self.commit_calls += 1

    def scalars(self, statement: Select[tuple[IncidentHistory]]) -> list[IncidentHistory]:
        self.scalars_statements.append(statement)
        return list(self.scalars_result)


def _make_entry(*, previous_status: str | None, new_status: str) -> IncidentHistory:
    """Build an in-memory ``IncidentHistory`` ORM instance."""
    return IncidentHistory(
        incident_id=_INCIDENT_ID,
        previous_status=previous_status,
        new_status=new_status,
        changed_by=_OWNER_ID,
    )


def test_append_stages_the_entry_on_the_session_and_never_commits() -> None:
    """Append-only staging: ``append`` hands the entry to ``session.add`` and
    the transaction boundary stays with the service (no commit/rollback here)."""
    session = FakeHistorySession()
    repo = HistoryRepository(session)
    entry = _make_entry(previous_status=None, new_status="pending")

    repo.append(entry)

    assert session.added == [entry]
    assert session.commit_calls == 0


def test_history_repository_is_append_only_with_no_update_or_delete() -> None:
    """INSTR-005 / BR-012 (structural guard): the class's entire public API is
    exactly ``append`` + the read-only queries (``list_for_incident`` from
    US-003, ``get_latest`` added by US-004) — no update or delete method
    exists, so no code path CAN rewrite or erase history rows."""
    public_api = {name for name in vars(HistoryRepository) if not name.startswith("_")}

    assert public_api == {"append", "list_for_incident", "get_latest"}
    assert not any("update" in name or "delete" in name or "remove" in name for name in public_api)


def test_list_for_incident_queries_ascending_by_changed_at_and_passes_rows_through() -> None:
    """INSTR-005 / BR-021: the read filters by the incident id and orders
    ``changed_at`` ASC inside the query itself; the rows come back in exactly
    the order the session produced — no client-side reordering, no read-modify
    access pattern."""
    session = FakeHistorySession()
    # Ascending as a real database would return them (initial entry first).
    initial = _make_entry(previous_status=None, new_status="pending")
    promoted = _make_entry(previous_status="pending", new_status="in_progress")
    session.scalars_result = [initial, promoted]
    repo = HistoryRepository(session)

    history = repo.list_for_incident(_INCIDENT_ID)

    (statement,) = session.scalars_statements
    sql = " ".join(str(statement.compile()).split())
    assert "WHERE incident_history.incident_id = :incident_id_1" in sql
    assert "ORDER BY incident_history.changed_at ASC" in sql
    assert "FOR UPDATE" not in sql  # read-only query — nothing is locked
    assert history == [initial, promoted]

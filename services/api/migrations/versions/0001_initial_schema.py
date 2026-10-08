"""Initial MiniSoporte schema: users, incidents, incident_history.

Revision ID: 0001_initial_schema
Revises:
Create Date: 2026-10-06

Creates the three foundation tables in dependency order (users -> incidents ->
incident_history) with UUID PKs (server_default ``gen_random_uuid()``),
ON DELETE RESTRICT foreign keys, all named CHECK constraints, the
``uq_users_email`` unique constraint and the two composite FK indexes
``ix_incidents_owner_created`` / ``ix_incident_history_incident_changed``.
Constraint and index names are identical to ``app/domain/models.py`` so the
Alembic metadata and the ORM metadata agree (backend.yaml
``constraints_and_indexes``).

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0001_initial_schema"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.Uuid(), nullable=False, server_default=sa.text("gen_random_uuid()")),
        sa.Column("email", sa.Text(), nullable=False),
        sa.Column("password_hash", sa.Text(), nullable=False),
        sa.Column("role", sa.Text(), nullable=False),
        sa.Column("active", sa.Boolean(), nullable=False, server_default=sa.text("true")),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("email", name="uq_users_email"),
        sa.CheckConstraint("role IN ('employee', 'support')", name="ck_users_role"),
    )
    op.create_table(
        "incidents",
        sa.Column("id", sa.Uuid(), nullable=False, server_default=sa.text("gen_random_uuid()")),
        sa.Column("owner_id", sa.Uuid(), nullable=False),
        sa.Column("title", sa.Text(), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("category", sa.Text(), nullable=False),
        sa.Column("priority", sa.Text(), nullable=False),
        sa.Column("status", sa.Text(), nullable=False, server_default=sa.text("'pending'")),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["owner_id"], ["users.id"], ondelete="RESTRICT"),
        sa.CheckConstraint("char_length(title) BETWEEN 5 AND 100", name="ck_incidents_title"),
        sa.CheckConstraint(
            "char_length(description) BETWEEN 10 AND 1000",
            name="ck_incidents_description",
        ),
        sa.CheckConstraint(
            "category IN ('systems', 'equipment', 'access', 'other')",
            name="ck_incidents_category",
        ),
        sa.CheckConstraint("priority IN ('low', 'medium', 'high')", name="ck_incidents_priority"),
        sa.CheckConstraint(
            "status IN ('pending', 'in_progress', 'resolved')",
            name="ck_incidents_status",
        ),
    )
    # PostgreSQL does not auto-index FK columns — index (owner_id, created_at)
    # for the owner's incident listing sorted by recency.
    op.create_index("ix_incidents_owner_created", "incidents", ["owner_id", "created_at"])
    op.create_table(
        "incident_history",
        sa.Column("id", sa.Uuid(), nullable=False, server_default=sa.text("gen_random_uuid()")),
        sa.Column("incident_id", sa.Uuid(), nullable=False),
        sa.Column("previous_status", sa.Text(), nullable=True, server_default=sa.text("NULL")),
        sa.Column("new_status", sa.Text(), nullable=False),
        sa.Column("changed_by", sa.Uuid(), nullable=False),
        sa.Column(
            "changed_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["incident_id"], ["incidents.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["changed_by"], ["users.id"], ondelete="RESTRICT"),
        sa.CheckConstraint(
            "previous_status IS NULL OR previous_status IN ('pending', 'in_progress', 'resolved')",
            name="ck_incident_history_previous_status",
        ),
        sa.CheckConstraint(
            "new_status IN ('pending', 'in_progress', 'resolved')",
            name="ck_incident_history_new_status",
        ),
    )
    # PostgreSQL does not auto-index FK columns — index (incident_id,
    # changed_at) for the per-incident status timeline.
    op.create_index(
        "ix_incident_history_incident_changed",
        "incident_history",
        ["incident_id", "changed_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_incident_history_incident_changed", table_name="incident_history")
    op.drop_table("incident_history")
    op.drop_index("ix_incidents_owner_created", table_name="incidents")
    op.drop_table("incidents")
    op.drop_table("users")

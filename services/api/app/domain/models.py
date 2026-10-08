"""SQLAlchemy 2.0 declarative ORM models for the MiniSoporte schema.

The three tables mirror ``migrations/versions/0001_initial_schema.py`` exactly;
the ORM is the only database access path (CA-05 — no ad-hoc SQL anywhere).
Constraint and index names follow the ``constraints_and_indexes`` contract in
the change's ``backend.yaml`` so the migration and the metadata agree.
"""

import datetime
import uuid

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Text,
    UniqueConstraint,
    Uuid,
    func,
    text,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    """Declarative base for all ORM models."""


class User(Base):
    __tablename__ = "users"
    __table_args__ = (
        UniqueConstraint("email", name="uq_users_email"),
        CheckConstraint("role IN ('employee', 'support')", name="ck_users_role"),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, server_default=text("gen_random_uuid()")
    )
    email: Mapped[str] = mapped_column(Text, nullable=False)
    password_hash: Mapped[str] = mapped_column(Text, nullable=False)
    role: Mapped[str] = mapped_column(Text, nullable=False)
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("true"))
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class Incident(Base):
    __tablename__ = "incidents"
    __table_args__ = (
        CheckConstraint("char_length(title) BETWEEN 5 AND 100", name="ck_incidents_title"),
        CheckConstraint(
            "char_length(description) BETWEEN 10 AND 1000",
            name="ck_incidents_description",
        ),
        CheckConstraint(
            "category IN ('systems', 'equipment', 'access', 'other')",
            name="ck_incidents_category",
        ),
        CheckConstraint("priority IN ('low', 'medium', 'high')", name="ck_incidents_priority"),
        CheckConstraint(
            "status IN ('pending', 'in_progress', 'resolved')",
            name="ck_incidents_status",
        ),
        # PostgreSQL does not auto-index FK columns — index (owner_id, created_at)
        # for the owner's incident listing sorted by recency.
        Index("ix_incidents_owner_created", "owner_id", "created_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, server_default=text("gen_random_uuid()")
    )
    owner_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    title: Mapped[str] = mapped_column(Text, nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False)
    category: Mapped[str] = mapped_column(Text, nullable=False)
    priority: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(Text, nullable=False, server_default=text("'pending'"))
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    # ``onupdate`` is applied by the service layer, not by the ORM.
    updated_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class IncidentHistory(Base):
    __tablename__ = "incident_history"
    __table_args__ = (
        CheckConstraint(
            "previous_status IS NULL OR previous_status IN ('pending', 'in_progress', 'resolved')",
            name="ck_incident_history_previous_status",
        ),
        CheckConstraint(
            "new_status IN ('pending', 'in_progress', 'resolved')",
            name="ck_incident_history_new_status",
        ),
        # PostgreSQL does not auto-index FK columns — index (incident_id,
        # changed_at) for the per-incident status timeline.
        Index("ix_incident_history_incident_changed", "incident_id", "changed_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, server_default=text("gen_random_uuid()")
    )
    incident_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("incidents.id", ondelete="RESTRICT"), nullable=False
    )
    # NULL marks the initial creation entry.
    previous_status: Mapped[str | None] = mapped_column(
        Text, nullable=True, server_default=text("NULL")
    )
    new_status: Mapped[str] = mapped_column(Text, nullable=False)
    changed_by: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    changed_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

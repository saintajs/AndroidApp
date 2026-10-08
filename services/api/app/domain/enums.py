"""Shared domain enumerations.

Every enum's values are the lowercase strings stored in the database (TEXT
columns guarded by CHECK constraints), so the same classes are reused verbatim
by the Pydantic schemas and the service layer.
"""

from enum import Enum, unique


@unique
class UserRole(str, Enum):
    """Role of an account; stored as the lowercase value in ``users.role``."""

    EMPLOYEE = "employee"
    SUPPORT = "support"


@unique
class IncidentCategory(str, Enum):
    """Classification of an incident; stored in ``incidents.category``."""

    SYSTEMS = "systems"
    EQUIPMENT = "equipment"
    ACCESS = "access"
    OTHER = "other"


@unique
class IncidentPriority(str, Enum):
    """Priority of an incident; stored in ``incidents.priority``."""

    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


@unique
class IncidentStatus(str, Enum):
    """Lifecycle status of an incident; stored in ``incidents.status`` and in
    ``incident_history.previous_status`` / ``incident_history.new_status``."""

    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    RESOLVED = "resolved"

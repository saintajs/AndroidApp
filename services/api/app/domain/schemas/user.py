"""User profile response schema (backend.yaml `endpoints.get-me`).

Exposing EXACTLY the five public fields is what structurally keeps
``users.password_hash`` out of every response (BR-026, INSTR-008): the ORM
``User`` may carry the hash, but a ``UserResponse`` declared as the route's
``response_model`` filters the serialized output to the fields below.
"""

import datetime
import uuid

from pydantic import BaseModel, ConfigDict


class UserResponse(BaseModel):
    """Public view of a user — the GET /api/me payload, no more."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    email: str
    role: str
    active: bool
    created_at: datetime.datetime

"""Pydantic v2 request/response schemas for the domain layer (backend.yaml `services`).

Typed response models are the structural guarantee that sensitive columns such as
``users.password_hash`` never reach an API response (BR-026): a route declares one
of these classes as its ``response_model`` and FastAPI filters the output to the
declared fields.
"""

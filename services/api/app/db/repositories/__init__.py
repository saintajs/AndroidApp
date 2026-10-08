"""Repository adapters over the SQLAlchemy session gateway (``app.db.session``).

Each repository is the single place its table is queried with ``select()``
(ports-and-adapters over the ORM); services and dependencies consume them and
never write queries themselves.
"""

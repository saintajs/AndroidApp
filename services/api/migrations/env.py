"""Alembic migration environment (design.md Decision 4).

The connection URL is never stored in ``alembic.ini``: it is built here from the
environment-sourced :class:`~app.core.config.Settings` (a sync
``postgresql+psycopg://`` URL), keeping credentials out of Git-tracked config.
``Base.metadata`` from ``app.domain.models`` is wired as ``target_metadata`` so
future ``alembic revision --autogenerate`` runs compare against the ORM models.
"""

from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool

from app.core.config import Settings
from app.domain.models import Base

# The Alembic Config object, which provides access to values within alembic.ini.
config = context.config

# Interpret the config file for Python logging (alembic.ini [logger_*] sections).
# disable_existing_loggers=False — fileConfig's default (True) disables every logger
# created before this call, which breaks logging capture in any process that runs
# migrations in-process (e.g. the integration test tier) after other app modules
# have already been imported.
if config.config_file_name is not None:
    fileConfig(config.config_file_name, disable_existing_loggers=False)

# ORM metadata for autogenerate support — the migration-defined schema and the
# models must stay in agreement (constraint/index names are identical).
target_metadata = Base.metadata

# Sync URL from environment settings — no URL/credentials baked into alembic.ini.
config.set_main_option("sqlalchemy.url", Settings().DATABASE_URL)


def run_migrations_offline() -> None:
    """Run migrations in 'offline' mode: emit SQL to stdout, no DBAPI needed."""
    url = config.get_main_option("sqlalchemy.url")
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )

    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    """Run migrations in 'online' mode: connect through an Engine and migrate."""
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

    with connectable.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)

        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()

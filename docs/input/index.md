# MiniSoporte — Input Index

Every uploaded file, normalized to **markdown** here under `docs/input/<category>/`.
The untouched originals are mirrored in `inputs_raw/` (same structure). Images pulled
out of documents live in `docs/input/images/` — see its `index.md`. When a row's status
is `raw_only`, conversion was not possible; read the raw file listed under *Raw*. A row
marked `withheld` is a secrets file (.env, a key): it is deliberately not in the workspace.
*Category* is which wizard section the file was uploaded into (requirements, source
code, rules, mockups, ...). *Tag* is the file's "Applies to" scope: either the name of
one specific Source code/Repositories upload this file describes (that upload's OWN row
has the identical Tag), or — for a Functional increment change input — the requested
functionality id/name it supports. "All project" means the file applies globally.
*Note* is a free-text detail, if given.

| Category | File | Markdown | Raw | Images | Status | Tag | Note |
|----------|------|----------|-----|--------|--------|-----|------|
| requirements | 01-minisoporte-requirements.pdf | [74cc5831-0acd-473b-b54f-f2b6d08934c5-01-minisoporte-requirements-pdf.md](docs/input/requirements/74cc5831-0acd-473b-b54f-f2b6d08934c5-01-minisoporte-requirements-pdf.md) | `inputs_raw/requirements/74cc5831-0acd-473b-b54f-f2b6d08934c5-01-minisoporte-requirements-pdf.pdf` | 0 | converted | All project | Functional requirements, business rules, security constraints, and acceptance criteria for MiniSoporte. |
| requirements | Scope description (wizard) | [_scope.md](docs/input/requirements/_scope.md) | `—` | 0 | converted |  |  |

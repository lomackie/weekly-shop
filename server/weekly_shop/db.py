import sqlite3

SCHEMA = """
CREATE TABLE IF NOT EXISTS items (
    id         INTEGER PRIMARY KEY,
    name       TEXT NOT NULL,
    ocado_id   TEXT NOT NULL UNIQUE,
    ocado_uuid TEXT  -- cart-API product id; resolved lazily from search by sku
);

CREATE TABLE IF NOT EXISTS aliases (
    id      INTEGER PRIMARY KEY,
    alias   TEXT NOT NULL UNIQUE COLLATE NOCASE,
    item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS basket_entries (
    id           INTEGER PRIMARY KEY,
    raw_text     TEXT NOT NULL,
    item_id      INTEGER REFERENCES items(id),
    status       TEXT NOT NULL,  -- matched | ambiguous | unmatched
    created_at   TEXT NOT NULL DEFAULT (datetime('now')),
    submitted_at TEXT  -- when this entry was pushed to the real Ocado trolley
);
"""

# Columns added after the first deploy; applied to pre-existing databases.
MIGRATIONS = [
    ("items", "ocado_uuid", "ALTER TABLE items ADD COLUMN ocado_uuid TEXT"),
    (
        "basket_entries",
        "submitted_at",
        "ALTER TABLE basket_entries ADD COLUMN submitted_at TEXT",
    ),
]


def connect(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.executescript(SCHEMA)
    for table, column, ddl in MIGRATIONS:
        cols = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})")}
        if column not in cols:
            conn.execute(ddl)
    conn.commit()
    return conn


def add_item(
    conn: sqlite3.Connection,
    name: str,
    ocado_id: str,
    aliases: list[str] = (),
    ocado_uuid: str | None = None,
) -> int:
    cur = conn.execute(
        "INSERT INTO items (name, ocado_id, ocado_uuid) VALUES (?, ?, ?)",
        (name, ocado_id, ocado_uuid),
    )
    item_id = cur.lastrowid
    for alias in aliases:
        add_alias(conn, alias, item_id)
    conn.commit()
    return item_id


def update_item(
    conn: sqlite3.Connection,
    item_id: int,
    name: str | None = None,
    ocado_id: str | None = None,
    ocado_uuid: str | None = None,
) -> bool:
    sets, params = [], []
    for column, value in (
        ("name", name),
        ("ocado_id", ocado_id),
        ("ocado_uuid", ocado_uuid),
    ):
        if value is not None:
            sets.append(f"{column} = ?")
            params.append(value)
    if not sets:
        return get_item(conn, item_id) is not None
    cur = conn.execute(
        f"UPDATE items SET {', '.join(sets)} WHERE id = ?", (*params, item_id)
    )
    conn.commit()
    return cur.rowcount > 0


def delete_item(conn: sqlite3.Connection, item_id: int) -> bool:
    # Basket entries pointing here go back to unmatched rather than blocking
    # the delete; their ink is still on the tablet page.
    conn.execute(
        "UPDATE basket_entries SET item_id = NULL, status = 'unmatched' "
        "WHERE item_id = ?",
        (item_id,),
    )
    cur = conn.execute("DELETE FROM items WHERE id = ?", (item_id,))
    conn.commit()
    return cur.rowcount > 0


def add_alias(conn: sqlite3.Connection, alias: str, item_id: int) -> None:
    conn.execute(
        "INSERT OR IGNORE INTO aliases (alias, item_id) VALUES (?, ?)",
        (alias.strip(), item_id),
    )
    conn.commit()


def delete_alias(conn: sqlite3.Connection, alias_id: int) -> bool:
    cur = conn.execute("DELETE FROM aliases WHERE id = ?", (alias_id,))
    conn.commit()
    return cur.rowcount > 0


def list_items(conn: sqlite3.Connection) -> list[dict]:
    items = {
        row["id"]: dict(row) | {"aliases": []}
        for row in conn.execute("SELECT * FROM items ORDER BY name")
    }
    for row in conn.execute("SELECT id, alias, item_id FROM aliases ORDER BY alias"):
        if row["item_id"] in items:
            items[row["item_id"]]["aliases"].append(
                {"id": row["id"], "alias": row["alias"]}
            )
    return list(items.values())


def alias_candidates(conn: sqlite3.Connection) -> list[tuple[str, int]]:
    """All matchable strings: item names plus learned aliases."""
    rows = conn.execute(
        "SELECT name AS text, id AS item_id FROM items "
        "UNION ALL SELECT alias, item_id FROM aliases"
    ).fetchall()
    return [(row["text"], row["item_id"]) for row in rows]


def get_item(conn: sqlite3.Connection, item_id: int) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM items WHERE id = ?", (item_id,)).fetchone()


def add_basket_entry(
    conn: sqlite3.Connection, raw_text: str, item_id: int | None, status: str
) -> int:
    cur = conn.execute(
        "INSERT INTO basket_entries (raw_text, item_id, status) VALUES (?, ?, ?)",
        (raw_text, item_id, status),
    )
    conn.commit()
    return cur.lastrowid


def list_basket(conn: sqlite3.Connection) -> list[sqlite3.Row]:
    return conn.execute(
        "SELECT b.id, b.raw_text, b.status, b.created_at, b.submitted_at, "
        "       i.id AS item_id, i.name AS item_name, i.ocado_id "
        "FROM basket_entries b LEFT JOIN items i ON i.id = b.item_id "
        "ORDER BY b.created_at"
    ).fetchall()


def unsubmitted_matched(conn: sqlite3.Connection) -> list[sqlite3.Row]:
    """Matched entries not yet pushed to the real Ocado trolley."""
    return conn.execute(
        "SELECT b.id, b.raw_text, b.item_id, "
        "       i.name, i.ocado_id, i.ocado_uuid "
        "FROM basket_entries b JOIN items i ON i.id = b.item_id "
        "WHERE b.status = 'matched' AND b.submitted_at IS NULL "
        "ORDER BY b.created_at"
    ).fetchall()


def mark_submitted(conn: sqlite3.Connection, entry_ids: list[int]) -> None:
    conn.executemany(
        "UPDATE basket_entries SET submitted_at = datetime('now') WHERE id = ?",
        [(entry_id,) for entry_id in entry_ids],
    )
    conn.commit()


def resolve_basket_entry(
    conn: sqlite3.Connection, entry_id: int, item_id: int
) -> bool:
    cur = conn.execute(
        "UPDATE basket_entries SET item_id = ?, status = 'matched' WHERE id = ?",
        (item_id, entry_id),
    )
    conn.commit()
    return cur.rowcount > 0


def delete_basket_entry(conn: sqlite3.Connection, entry_id: int) -> bool:
    cur = conn.execute("DELETE FROM basket_entries WHERE id = ?", (entry_id,))
    conn.commit()
    return cur.rowcount > 0

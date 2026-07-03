import sqlite3

SCHEMA = """
CREATE TABLE IF NOT EXISTS items (
    id       INTEGER PRIMARY KEY,
    name     TEXT NOT NULL,
    ocado_id TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS aliases (
    id      INTEGER PRIMARY KEY,
    alias   TEXT NOT NULL UNIQUE COLLATE NOCASE,
    item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS basket_entries (
    id         INTEGER PRIMARY KEY,
    raw_text   TEXT NOT NULL,
    item_id    INTEGER REFERENCES items(id),
    status     TEXT NOT NULL,  -- matched | ambiguous | unmatched
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
"""


def connect(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.executescript(SCHEMA)
    return conn


def add_item(
    conn: sqlite3.Connection, name: str, ocado_id: str, aliases: list[str] = ()
) -> int:
    cur = conn.execute(
        "INSERT INTO items (name, ocado_id) VALUES (?, ?)", (name, ocado_id)
    )
    item_id = cur.lastrowid
    for alias in aliases:
        add_alias(conn, alias, item_id)
    conn.commit()
    return item_id


def add_alias(conn: sqlite3.Connection, alias: str, item_id: int) -> None:
    conn.execute(
        "INSERT OR IGNORE INTO aliases (alias, item_id) VALUES (?, ?)",
        (alias.strip(), item_id),
    )
    conn.commit()


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
        "SELECT b.id, b.raw_text, b.status, b.created_at, "
        "       i.id AS item_id, i.name AS item_name, i.ocado_id "
        "FROM basket_entries b LEFT JOIN items i ON i.id = b.item_id "
        "ORDER BY b.created_at"
    ).fetchall()


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

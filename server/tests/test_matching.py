import pytest

from weekly_shop import db, matching

MATCH = 87.0
CANDIDATE = 55.0


@pytest.fixture
def conn():
    conn = db.connect(":memory:")
    db.add_item(conn, "Ocado Semi Skimmed Milk 2.272L", "13175011", ["milk"])
    db.add_item(conn, "Hovis Soft White Medium Bread 800g", "23476011", ["bread"])
    db.add_item(conn, "Fairtrade Bananas x5", "78945011", ["bananas"])
    yield conn
    conn.close()


def test_exact_alias_matches(conn):
    result = matching.match(conn, "milk", MATCH, CANDIDATE)
    assert result.status == "matched"
    assert result.best.ocado_id == "13175011"


def test_close_misspelling_matches(conn):
    result = matching.match(conn, "banannas", MATCH, CANDIDATE)
    assert result.status == "matched"
    assert result.best.ocado_id == "78945011"


def test_unknown_text_is_unmatched(conn):
    result = matching.match(conn, "flux capacitor", MATCH, CANDIDATE)
    assert result.status == "unmatched"
    assert result.best is None


def test_empty_text_is_unmatched(conn):
    assert matching.match(conn, "  ", MATCH, CANDIDATE).status == "unmatched"


def test_candidates_deduped_by_item(conn):
    db.add_alias(conn, "semi skimmed milk", 1)
    result = matching.match(conn, "milk", MATCH, CANDIDATE)
    assert len([c for c in result.candidates if c.item_id == 1]) == 1

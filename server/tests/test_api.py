import pytest
from fastapi.testclient import TestClient

from weekly_shop.config import Settings, get_settings
from weekly_shop.main import app

STROKES = {
    "strokes": [
        [{"x": 0, "y": 0, "t": 0}, {"x": 10, "y": 5, "t": 20}],
        [{"x": 12, "y": 0, "t": 100}, {"x": 12, "y": 10, "t": 120}],
    ]
}


@pytest.fixture
def client(monkeypatch):
    get_settings.cache_clear()
    monkeypatch.setenv("WEEKLY_SHOP_DB_PATH", ":memory:")
    monkeypatch.setenv("WEEKLY_SHOP_RECOGNIZER", "stub")
    monkeypatch.setenv("WEEKLY_SHOP_STUB_TEXT", "milk")
    with TestClient(app) as client:
        yield client
    get_settings.cache_clear()


@pytest.fixture
def picky_client(monkeypatch):
    """A client whose match threshold nothing can reach: every recognisable
    text comes back ambiguous, which is how ambiguous entries are minted."""
    monkeypatch.setenv("WEEKLY_SHOP_MATCH_THRESHOLD", "101")
    get_settings.cache_clear()
    monkeypatch.setenv("WEEKLY_SHOP_DB_PATH", ":memory:")
    monkeypatch.setenv("WEEKLY_SHOP_RECOGNIZER", "stub")
    monkeypatch.setenv("WEEKLY_SHOP_STUB_TEXT", "milk")
    with TestClient(app) as client:
        yield client
    get_settings.cache_clear()


def seed_milk(client) -> int:
    resp = client.post(
        "/items",
        json={"name": "Semi Skimmed Milk", "ocado_id": "13175011", "aliases": ["milk"]},
    )
    assert resp.status_code == 201
    return resp.json()["id"]


def test_ink_to_basket_round_trip(client):
    item_id = seed_milk(client)

    resp = client.post("/ink", json=STROKES)
    assert resp.status_code == 200
    lines = resp.json()["lines"]
    assert len(lines) == 1
    assert lines[0]["status"] == "matched"
    assert lines[0]["raw_text"] == "milk"
    assert lines[0]["item"]["id"] == item_id
    assert lines[0]["stroke_indices"] == [0, 1]

    basket = client.get("/basket").json()
    assert len(basket) == 1
    assert basket[0]["item_id"] == item_id


def test_multi_line_ink_baskets_each_line(client):
    seed_milk(client)

    # Two vertically separated lines: each is recognised and basketed on its
    # own, with the strokes that produced it identified.
    resp = client.post(
        "/ink",
        json={
            "strokes": [
                [{"x": 0, "y": 0, "t": 0}, {"x": 40, "y": 10, "t": 20}],
                [{"x": 0, "y": 100, "t": 900}, {"x": 40, "y": 112, "t": 920}],
            ]
        },
    )
    lines = resp.json()["lines"]
    assert [line["status"] for line in lines] == ["matched", "matched"]
    assert [line["stroke_indices"] for line in lines] == [[0], [1]]
    assert len(client.get("/basket").json()) == 2


def test_unmatched_ink_flags_line_and_skips_basket(client):
    resp = client.post("/ink", json=STROKES)  # no items seeded
    line = resp.json()["lines"][0]
    assert line["status"] == "unmatched"
    assert line["item"] is None
    assert line["basket_entry_id"] is None
    # The tablet highlights these strokes for a rub-out-and-retry.
    assert line["stroke_indices"] == [0, 1]

    assert client.get("/basket").json() == []


def test_resolve_ambiguous_entry_learns_alias(picky_client):
    item_id = seed_milk(picky_client)

    line = picky_client.post("/ink", json=STROKES).json()["lines"][0]
    assert line["status"] == "ambiguous"
    assert [c["id"] for c in line["candidates"]] == [item_id]
    entry_id = line["basket_entry_id"]

    resp = picky_client.post(f"/basket/{entry_id}/resolve", json={"item_id": item_id})
    assert resp.status_code == 200

    basket = picky_client.get("/basket").json()
    assert basket[0]["status"] == "matched"
    assert basket[0]["item_id"] == item_id


def test_empty_request_rejected(client):
    assert client.post("/ink", json={}).status_code == 422


def test_delete_basket_entry(client):
    seed_milk(client)
    entry_id = client.post("/ink", json=STROKES).json()["lines"][0]["basket_entry_id"]
    assert client.delete(f"/basket/{entry_id}").status_code == 200
    assert client.get("/basket").json() == []

import pytest
from fastapi.testclient import TestClient

from weekly_shop.config import get_settings
from weekly_shop.main import app
from weekly_shop.ocado import Product

STROKES = {
    "strokes": [
        [{"x": 0, "y": 0, "t": 0}, {"x": 10, "y": 5, "t": 20}],
    ]
}


def product(**overrides) -> Product:
    defaults = dict(
        product_id="uuid-milk",
        sku="13175011",
        name="Semi Skimmed Milk 2.272L",
        brand="Ocado",
        pack_size="2.272L",
        price="1.65",
        available=True,
        sponsored=False,
        quantity_in_basket=0,
    )
    return Product(**{**defaults, **overrides})


class FakeOcado:
    def __init__(self, products=()):
        self.products = list(products)
        self.quantity_calls = []

    def search(self, term):
        return self.products

    def change_quantity(self, product_id, delta):
        self.quantity_calls.append((product_id, delta))

    def close(self):
        pass


class BrokenOcado(FakeOcado):
    def search(self, term):
        raise FileNotFoundError("no session state")


@pytest.fixture
def client(monkeypatch):
    get_settings.cache_clear()
    monkeypatch.setenv("WEEKLY_SHOP_DB_PATH", ":memory:")
    monkeypatch.setenv("WEEKLY_SHOP_RECOGNIZER", "stub")
    monkeypatch.setenv("WEEKLY_SHOP_STUB_TEXT", "milk")
    with TestClient(app) as client:
        yield client
    get_settings.cache_clear()


def seed_milk(client, ocado_uuid=None) -> int:
    resp = client.post(
        "/items",
        json={
            "name": "Semi Skimmed Milk 2.272L",
            "ocado_id": "13175011",
            "ocado_uuid": ocado_uuid,
            "aliases": ["milk"],
        },
    )
    assert resp.status_code == 201
    return resp.json()["id"]


def test_search_dedupes_sponsored_copies(client):
    client.app.state.ocado = FakeOcado(
        [product(sponsored=True), product(sponsored=False), product(
            product_id="uuid-other", sku="99", name="Oat Milk")]
    )
    results = client.get("/ocado/search", params={"q": "milk"}).json()
    assert [r["product_id"] for r in results] == ["uuid-milk", "uuid-other"]
    assert results[0]["sponsored"] is False


def test_search_without_session_is_503(client):
    client.app.state.ocado = BrokenOcado()
    assert client.get("/ocado/search", params={"q": "milk"}).status_code == 503


def test_submit_pushes_matched_entries_once(client):
    fake = FakeOcado()
    client.app.state.ocado = fake
    seed_milk(client, ocado_uuid="uuid-milk")

    # Two entries of the same item -> one delta of 2.
    client.post("/ink", json=STROKES)
    client.post("/ink", json=STROKES)

    result = client.post("/ocado/submit").json()
    assert result["submitted_entries"] == 2
    assert result["items"] == [{"name": "Semi Skimmed Milk 2.272L", "quantity": 2}]
    assert result["failed"] == []
    assert fake.quantity_calls == [("uuid-milk", 2)]

    for entry in client.get("/basket").json():
        assert entry["submitted_at"] is not None

    # Already-submitted entries never go twice.
    again = client.post("/ocado/submit").json()
    assert again["submitted_entries"] == 0
    assert fake.quantity_calls == [("uuid-milk", 2)]


def test_submit_resolves_and_stores_missing_uuid(client):
    fake = FakeOcado([product()])
    client.app.state.ocado = fake
    item_id = seed_milk(client, ocado_uuid=None)

    client.post("/ink", json=STROKES)
    result = client.post("/ocado/submit").json()
    assert result["submitted_entries"] == 1
    assert fake.quantity_calls == [("uuid-milk", 1)]

    items = client.get("/items").json()
    assert items[0]["id"] == item_id
    assert items[0]["ocado_uuid"] == "uuid-milk"


def test_submit_reports_unresolvable_item_and_keeps_entry(client):
    client.app.state.ocado = FakeOcado([product(sku="different-sku")])
    seed_milk(client, ocado_uuid=None)

    client.post("/ink", json=STROKES)
    result = client.post("/ocado/submit").json()
    assert result["submitted_entries"] == 0
    assert len(result["failed"]) == 1
    assert "re-pick" in result["failed"][0]["error"]

    # Entry stays unsubmitted so a later submit can retry it.
    assert client.get("/basket").json()[0]["submitted_at"] is None


def test_item_alias_crud(client):
    item_id = seed_milk(client)
    assert client.post(
        f"/items/{item_id}/aliases", json={"alias": "semi"}
    ).status_code == 201

    items = client.get("/items").json()
    aliases = {a["alias"]: a["id"] for a in items[0]["aliases"]}
    assert set(aliases) == {"milk", "semi"}

    assert client.delete(f"/aliases/{aliases['semi']}").status_code == 200
    assert client.put(
        f"/items/{item_id}", json={"ocado_id": "999", "ocado_uuid": "uuid-new"}
    ).status_code == 200
    items = client.get("/items").json()
    assert items[0]["ocado_id"] == "999"
    assert items[0]["ocado_uuid"] == "uuid-new"

    assert client.delete(f"/items/{item_id}").status_code == 200
    assert client.get("/items").json() == []

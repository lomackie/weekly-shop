"""Seed the item table from a JSON file: python -m weekly_shop.seed items.json"""

import json
import sys

from . import db
from .config import get_settings


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: python -m weekly_shop.seed <items.json>")

    with open(sys.argv[1]) as f:
        items = json.load(f)

    conn = db.connect(get_settings().db_path)
    existing = {
        row["ocado_id"] for row in conn.execute("SELECT ocado_id FROM items")
    }
    added = 0
    for item in items:
        if item["ocado_id"] in existing:
            continue
        db.add_item(conn, item["name"], item["ocado_id"], item.get("aliases", []))
        added += 1
    print(f"added {added} items ({len(items) - added} already present)")


if __name__ == "__main__":
    main()

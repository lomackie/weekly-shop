"""Ocado access via Playwright.

Ocado has no official API, so we drive the real website. The logged-in
session lives in a Playwright storage_state JSON (cookies + localStorage),
which is portable across machines — unlike a raw Chromium profile, whose
cookies macOS encrypts with the Keychain. So the flow is: `login` headful
on a desktop machine, copy the JSON to the beelink, run everything else
headless against it.

Once a page is open with the session loaded, we talk to the site's own
JSON APIs by fetch()ing from inside the page, so session cookies, the AWS
WAF token, and the CSRF token (server-rendered into
window.__INITIAL_STATE__.session.csrf.token) all apply:

    GET  /api/webproductpagews/v6/product-pages/search?q=...   product search
    GET  /api/cart/v1/carts/active                             read trolley
    POST /api/cart/v1/carts/active/apply-quantity              change quantity
         body [{productId, quantity}] — quantity is a DELTA, not absolute

Usage:
    uv run python -m weekly_shop.ocado login           # headful; log in, close window
    uv run python -m weekly_shop.ocado check           # headless; verify the session
    uv run python -m weekly_shop.ocado search "milk"   # product search
    uv run python -m weekly_shop.ocado cart            # show trolley
    uv run python -m weekly_shop.ocado add <productId> [--qty N]
    uv run python -m weekly_shop.ocado remove <productId>
"""

import argparse
import json
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from playwright.sync_api import Browser, BrowserContext, Error, Page, sync_playwright

OCADO_URL = "https://www.ocado.com/"
DEFAULT_STATE_PATH = "ocado_state.json"

# Frozen-format Chrome UA; headless Chromium otherwise advertises
# "HeadlessChrome", which is an instant bot tell.
UA_PLATFORM = (
    "Macintosh; Intel Mac OS X 10_15_7"
    if sys.platform == "darwin"
    else "X11; Linux x86_64"
)


def _user_agent(browser: Browser) -> str:
    major = browser.version.split(".")[0]
    return (
        f"Mozilla/5.0 ({UA_PLATFORM}) AppleWebKit/537.36 "
        f"(KHTML, like Gecko) Chrome/{major}.0.0.0 Safari/537.36"
    )


def _new_context(browser: Browser, state_path: Path) -> BrowserContext:
    return browser.new_context(
        storage_state=state_path if state_path.exists() else None,
        user_agent=_user_agent(browser),
        viewport={"width": 1440, "height": 900},
        locale="en-GB",
        timezone_id="Europe/London",
    )


@dataclass
class Product:
    product_id: str  # UUID the cart API wants
    sku: str  # classic numeric Ocado id (retailerProductId), stable across sessions
    name: str
    brand: str
    pack_size: str
    price: str  # GBP amount as string, e.g. "1.65"
    available: bool
    sponsored: bool
    quantity_in_basket: int


@dataclass
class CartLine:
    product_id: str
    quantity: int
    total_price: str


class OcadoClient:
    """A logged-in headless browser on ocado.com, exposing the site's JSON APIs.

    Keep one instance around per batch of operations — startup costs a full
    page load. Always call close() (or use as a context manager) so refreshed
    session tokens are written back to the state file.
    """

    def __init__(self, state_path: Path, headless: bool = True):
        if not state_path.exists():
            raise FileNotFoundError(f"no session state at {state_path}; run login")
        self._state_path = state_path
        self._pw = sync_playwright().start()
        self._browser = self._pw.chromium.launch(headless=headless, channel="chromium")
        self._context = _new_context(self._browser, state_path)
        self.page: Page = self._context.new_page()
        self.page.goto(OCADO_URL, wait_until="domcontentloaded")
        self.page.wait_for_timeout(3000)  # let the SPA boot and WAF token settle
        self._csrf = self.page.evaluate(
            "() => window.__INITIAL_STATE__?.session?.csrf?.token"
        )
        if not self._csrf:
            raise RuntimeError(
                "no CSRF token in page state — session is probably logged out; "
                "re-run login"
            )

    def __enter__(self) -> "OcadoClient":
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def close(self) -> None:
        self._context.storage_state(path=self._state_path)
        self._browser.close()
        self._pw.stop()

    def _fetch(self, path: str, method: str = "GET", body: object = None) -> dict:
        result = self.page.evaluate(
            """async ([path, method, body, csrf]) => {
                const r = await fetch(path, {
                    method,
                    headers: {
                        accept: 'application/json; charset=utf-8',
                        'content-type': 'application/json; charset=utf-8',
                        'x-csrf-token': csrf,
                    },
                    body: body === null ? undefined : JSON.stringify(body),
                });
                return {status: r.status, text: await r.text()};
            }""",
            [path, method, body, self._csrf],
        )
        if result["status"] >= 400:
            raise RuntimeError(
                f"{method} {path} -> {result['status']}: {result['text'][:300]}"
            )
        return json.loads(result["text"]) if result["text"] else {}

    def search(self, term: str, limit: int = 20) -> list[Product]:
        from urllib.parse import quote

        data = self._fetch(
            "/api/webproductpagews/v6/product-pages/search"
            f"?q={quote(term)}&maxPageSize=50&maxProductsToDecorate=50"
            "&includeAdditionalPageInfo=true&tag=web"
        )
        products = []
        for group in data.get("productGroups", []):
            sponsored = group.get("type") == "featured"
            for p in group.get("decoratedProducts", []):
                products.append(
                    Product(
                        product_id=p["productId"],
                        sku=p.get("retailerProductId", ""),
                        name=p.get("name", ""),
                        brand=p.get("brand", ""),
                        pack_size=p.get("packSizeDescription", ""),
                        price=p.get("price", {}).get("amount", ""),
                        available=p.get("available", False),
                        sponsored=sponsored,
                        quantity_in_basket=p.get("quantityInBasket", 0),
                    )
                )
        return products[:limit]

    def cart(self) -> list[CartLine]:
        data = self._fetch("/api/cart/v1/carts/active")
        return [
            CartLine(
                product_id=item["productId"],
                quantity=item["quantity"],
                total_price=item.get("totalPrices", {})
                .get("finalPrice", {})
                .get("amount", ""),
            )
            for item in data.get("items", [])
        ]

    def change_quantity(self, product_id: str, delta: int) -> None:
        if delta == 0:
            return
        self._fetch(
            "/api/cart/v1/carts/active/apply-quantity?cartProductSorting=CATEGORIES",
            method="POST",
            body=[{"productId": product_id, "quantity": delta}],
        )

    def set_quantity(self, product_id: str, quantity: int) -> None:
        current = {line.product_id: line.quantity for line in self.cart()}
        self.change_quantity(product_id, quantity - current.get(product_id, 0))


def login(state_path: Path) -> None:
    """Open a visible browser on Ocado; keep saving session state until closed."""
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=False, channel="chromium")
        context = _new_context(browser, state_path)
        page = context.new_page()
        page.goto(OCADO_URL)
        print("Log in to Ocado in the browser window, then close the window.")
        print(f"Session state is saved to {state_path} every few seconds.")
        try:
            while context.pages:
                context.storage_state(path=state_path)
                time.sleep(3)
        except Error:
            pass  # browser closed mid-save; the last completed save stands
        print(f"Done. Session state saved to {state_path}.")


def check(state_path: Path, screenshot: Path | None) -> bool:
    """Load the saved session headless and report whether Ocado sees us as logged in."""
    if not state_path.exists():
        print(f"No session state at {state_path} — run `login` first.")
        return False
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, channel="chromium")
        context = _new_context(browser, state_path)
        page = context.new_page()
        page.goto(OCADO_URL, wait_until="domcontentloaded")
        page.wait_for_timeout(4000)  # let the SPA header hydrate
        body = page.inner_text("body").lower()
        # Logged in, the header shows "My Ocado" and a "Welcome, <name>" banner;
        # logged out it shows a "Sign in" button instead.
        signed_in = "my ocado" in body or "welcome," in body
        signed_out = "sign in" in body or "log in" in body
        print(f"URL:   {page.url}")
        print(f"Title: {page.title()}")
        print(f"Signed-in markers: {signed_in}, signed-out markers: {signed_out}")
        if screenshot:
            page.screenshot(path=screenshot, full_page=False)
            print(f"Screenshot: {screenshot}")
        context.storage_state(path=state_path)  # persist refreshed tokens
        return signed_in


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command", choices=["login", "check", "search", "cart", "add", "remove"]
    )
    parser.add_argument("arg", nargs="?", help="search term or productId")
    parser.add_argument("--qty", type=int, default=1)
    parser.add_argument("--state", default=DEFAULT_STATE_PATH, type=Path)
    parser.add_argument("--screenshot", type=Path, help="check: save a screenshot here")
    args = parser.parse_args()

    if args.command == "login":
        login(args.state)
        return
    if args.command == "check":
        sys.exit(0 if check(args.state, args.screenshot) else 1)

    with OcadoClient(args.state) as client:
        if args.command == "search":
            for p in client.search(args.arg):
                tag = " [sponsored]" if p.sponsored else ""
                stock = "" if p.available else " [unavailable]"
                print(
                    f"{p.product_id}  sku={p.sku:<10} £{p.price:<6} "
                    f"{p.name} ({p.pack_size}){tag}{stock}"
                )
        elif args.command == "cart":
            lines = client.cart()
            for line in lines:
                print(f"{line.product_id}  x{line.quantity}  £{line.total_price}")
            print(f"{len(lines)} line(s)")
        elif args.command == "add":
            client.change_quantity(args.arg, args.qty)
            print("added")
        elif args.command == "remove":
            client.set_quantity(args.arg, 0)
            print("removed")


if __name__ == "__main__":
    main()

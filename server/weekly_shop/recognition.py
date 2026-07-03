"""Handwriting recognition backends.

A recogniser takes the rendered ink (PNG bytes) plus the raw strokes and
returns the transcribed text. Strokes are accepted so a future backend can do
online (stroke-based) recognition without an API change.
"""

import base64
from typing import Protocol

from .config import Settings


class Recognizer(Protocol):
    def recognize(self, image_png: bytes, strokes: list | None = None) -> str: ...


class StubRecognizer:
    """Returns a fixed string; lets the whole pipeline run without credentials."""

    def __init__(self, text: str):
        self.text = text

    def recognize(self, image_png: bytes, strokes: list | None = None) -> str:
        return self.text


class ClaudeRecognizer:
    def __init__(self, api_key: str, model: str):
        import anthropic

        self.client = anthropic.Anthropic(api_key=api_key)
        self.model = model

    def recognize(self, image_png: bytes, strokes: list | None = None) -> str:
        message = self.client.messages.create(
            model=self.model,
            max_tokens=100,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image",
                            "source": {
                                "type": "base64",
                                "media_type": "image/png",
                                "data": base64.b64encode(image_png).decode(),
                            },
                        },
                        {
                            "type": "text",
                            "text": (
                                "This is a handwritten grocery item. Reply with "
                                "the transcribed text only — no explanation, no "
                                "punctuation, no quotes."
                            ),
                        },
                    ],
                }
            ],
        )
        return message.content[0].text.strip()


def build_recognizer(settings: Settings) -> Recognizer:
    if settings.recognizer == "claude":
        if not settings.anthropic_api_key:
            raise RuntimeError(
                "WEEKLY_SHOP_RECOGNIZER=claude requires WEEKLY_SHOP_ANTHROPIC_API_KEY"
            )
        return ClaudeRecognizer(settings.anthropic_api_key, settings.anthropic_model)
    if settings.recognizer == "stub":
        return StubRecognizer(settings.stub_text)
    raise RuntimeError(f"unknown recognizer: {settings.recognizer!r}")

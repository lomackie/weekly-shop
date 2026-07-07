"""Handwriting recognition backends.

A recogniser takes the rendered ink (PNG bytes) plus the raw strokes and
returns the transcribed text. Strokes are accepted so a future backend can do
online (stroke-based) recognition without an API change.
"""

import base64
import logging
from typing import Protocol

from .config import Settings

log = logging.getLogger(__name__)


class Recognizer(Protocol):
    def recognize(
        self,
        image_png: bytes,
        strokes: list | None = None,
        vocabulary: list[str] | None = None,
    ) -> str: ...


class StubRecognizer:
    """Returns a fixed string; lets the whole pipeline run without credentials."""

    def __init__(self, text: str):
        self.text = text

    def recognize(
        self,
        image_png: bytes,
        strokes: list | None = None,
        vocabulary: list[str] | None = None,
    ) -> str:
        return self.text


def _build_prompt(vocabulary: list[str] | None) -> str:
    if not vocabulary:
        return (
            "This is one handwritten item from a household grocery list. "
            "Reply with the transcribed text only — no explanation, no "
            "punctuation, no quotes."
        )
    # Nudging towards the household's actual items fixes most near-miss
    # transcriptions ("dill" for "Milk") without stopping genuinely new
    # words from coming through.
    known = ", ".join(vocabulary[:80])
    return (
        "This is one handwritten item from a household grocery list. "
        f"Items this household buys: {known}. If the writing plausibly "
        "matches one of those (allowing for messy handwriting), reply with "
        "that item text; otherwise transcribe exactly what you see. Reply "
        "with the transcription only — no explanation, no punctuation, "
        "no quotes."
    )


class ClaudeRecognizer:
    def __init__(self, api_key: str, model: str):
        import anthropic

        self.client = anthropic.Anthropic(api_key=api_key)
        self.model = model

    def recognize(
        self,
        image_png: bytes,
        strokes: list | None = None,
        vocabulary: list[str] | None = None,
    ) -> str:
        import anthropic

        prompt = _build_prompt(vocabulary)
        try:
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
                            {"type": "text", "text": prompt},
                        ],
                    }
                ],
            )
        except anthropic.APIError:
            # An empty transcription comes back "unmatched", so the tablet
            # highlights the ink for a rub-out-and-retry instead of erroring.
            log.exception("recognition request failed")
            return ""
        return "".join(
            block.text for block in message.content if block.type == "text"
        ).strip()


class OpenAIRecognizer:
    def __init__(self, api_key: str, model: str):
        import openai

        self.client = openai.OpenAI(api_key=api_key)
        self.model = model

    def recognize(
        self,
        image_png: bytes,
        strokes: list | None = None,
        vocabulary: list[str] | None = None,
    ) -> str:
        import openai

        data_url = "data:image/png;base64," + base64.b64encode(image_png).decode()
        try:
            response = self.client.responses.create(
                model=self.model,
                max_output_tokens=500,
                reasoning={"effort": "none"},
                input=[
                    {
                        "role": "user",
                        "content": [
                            {"type": "input_image", "image_url": data_url},
                            {
                                "type": "input_text",
                                "text": _build_prompt(vocabulary),
                            },
                        ],
                    }
                ],
            )
        except openai.OpenAIError:
            # Same graceful degradation as ClaudeRecognizer: unmatched ink
            # gets highlighted on the tablet instead of a hard error.
            log.exception("recognition request failed")
            return ""
        return response.output_text.strip()


def build_recognizer(settings: Settings) -> Recognizer:
    kind = settings.recognizer
    if kind == "auto":
        if settings.openai_api_key:
            kind = "openai"
        elif settings.anthropic_api_key:
            kind = "claude"
        else:
            kind = "stub"
        log.info("recognizer=auto resolved to %s", kind)
    if kind == "openai":
        if not settings.openai_api_key:
            raise RuntimeError(
                "WEEKLY_SHOP_RECOGNIZER=openai requires WEEKLY_SHOP_OPENAI_API_KEY"
            )
        return OpenAIRecognizer(settings.openai_api_key, settings.openai_model)
    if kind == "claude":
        if not settings.anthropic_api_key:
            raise RuntimeError(
                "WEEKLY_SHOP_RECOGNIZER=claude requires WEEKLY_SHOP_ANTHROPIC_API_KEY"
            )
        return ClaudeRecognizer(settings.anthropic_api_key, settings.anthropic_model)
    if kind == "stub":
        return StubRecognizer(settings.stub_text)
    raise RuntimeError(f"unknown recognizer: {settings.recognizer!r}")

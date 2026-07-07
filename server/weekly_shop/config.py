from functools import lru_cache

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # ../.env covers running from server/ with the .env at the repo root.
    model_config = SettingsConfigDict(
        env_prefix="WEEKLY_SHOP_", env_file=(".env", "../.env")
    )

    db_path: str = "weekly_shop.sqlite3"

    # "stub" needs no credentials and always recognises `stub_text`;
    # "claude" / "openai" send the rendered ink image to that provider's API;
    # "auto" picks openai, then claude, by whichever API key is configured,
    # falling back to stub.
    recognizer: str = "auto"
    stub_text: str = "milk"
    anthropic_api_key: str = Field(
        default="",
        validation_alias=AliasChoices(
            "WEEKLY_SHOP_ANTHROPIC_API_KEY", "ANTHROPIC_API_KEY"
        ),
    )
    anthropic_model: str = "claude-haiku-4-5-20251001"
    openai_api_key: str = Field(
        default="",
        validation_alias=AliasChoices(
            "WEEKLY_SHOP_OPENAI_API_KEY", "OPENAI_API_KEY"
        ),
    )
    openai_model: str = "gpt-5.4-mini"

    # rapidfuzz scores are 0-100. At or above `match_threshold` we commit to
    # the best item; between the two we return candidates for the tablet to
    # disambiguate; below `candidate_threshold` the text is unmatched.
    match_threshold: float = 87.0
    candidate_threshold: float = 55.0


@lru_cache
def get_settings() -> Settings:
    return Settings()

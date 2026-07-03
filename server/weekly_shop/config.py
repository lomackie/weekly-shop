from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="WEEKLY_SHOP_", env_file=".env")

    db_path: str = "weekly_shop.sqlite3"

    # "stub" needs no credentials and always recognises `stub_text`;
    # "claude" sends the rendered ink image to the Anthropic API.
    recognizer: str = "stub"
    stub_text: str = "milk"
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-haiku-4-5-20251001"

    # rapidfuzz scores are 0-100. At or above `match_threshold` we commit to
    # the best item; between the two we return candidates for the tablet to
    # disambiguate; below `candidate_threshold` the text is unmatched.
    match_threshold: float = 87.0
    candidate_threshold: float = 55.0


@lru_cache
def get_settings() -> Settings:
    return Settings()

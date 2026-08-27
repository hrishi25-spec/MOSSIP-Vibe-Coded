"""
Application configuration, loaded from environment variables / .env file.
"""
from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    APP_NAME: str = "MOSIP Face Liveness & PAD Service"
    APP_ENV: str = "development"
    LOG_LEVEL: str = "INFO"

    POSTGRES_USER: str = "mosip"
    POSTGRES_PASSWORD: str = "change_me"
    POSTGRES_DB: str = "pad_liveness"
    POSTGRES_HOST: str = "localhost"
    POSTGRES_PORT: int = 5432

    # ---- Default liveness/PAD policy (used to seed config table) ----
    DEFAULT_PASSIVE_THRESHOLD: float = 0.75
    DEFAULT_ACTIVE_LIVENESS_ENABLED: bool = True
    DEFAULT_MIN_CHALLENGE_COUNT: int = 1
    DEFAULT_CHALLENGE_TIMEOUT_MS: int = 8000
    DEFAULT_MAX_RETRY_COUNT: int = 3
    DEFAULT_CHALLENGE_TYPES: list[str] = ["blink", "smile", "turn_left", "turn_right"]

    @property
    def SQLALCHEMY_DATABASE_URI(self) -> str:
        return (
            f"postgresql+psycopg2://{self.POSTGRES_USER}:{self.POSTGRES_PASSWORD}"
            f"@{self.POSTGRES_HOST}:{self.POSTGRES_PORT}/{self.POSTGRES_DB}"
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()

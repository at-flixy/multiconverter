# backend/config.py
import os
from datetime import timedelta


class Config:
    # --- Secrets ---
    SECRET_KEY = os.getenv("SECRET_KEY", "dev")
    JWT_SECRET_KEY = os.getenv("JWT_SECRET_KEY", "devjwt")

    # --- Database ---
    # Фактический путь к БД принудительно задаётся в app.py (instance/app.db).
    # Здесь даём env-переменную на случай локального запуска без app.py-переопределения.
    SQLALCHEMY_DATABASE_URI = os.getenv(
        "DATABASE_URL", "sqlite:///instance/app.db")
    SQLALCHEMY_TRACK_MODIFICATIONS = False

    # --- Storage paths ---
    # Пути лучше задавать через .env. Если не заданы — app.py создаст instance/uploads и instance/results.
    UPLOAD_DIR = os.getenv("UPLOAD_DIR")      # e.g. /app/uploads
    RESULT_DIR = os.getenv("RESULT_DIR")      # e.g. /app/results

    # --- Redis / Celery ---
    REDIS_URL = os.getenv("REDIS_URL", "redis://redis:6379/0")
    CELERY_BROKER_URL = os.getenv("CELERY_BROKER_URL", REDIS_URL)
    CELERY_RESULT_BACKEND = os.getenv("CELERY_RESULT_BACKEND", REDIS_URL)

    # --- External APIs ---
    OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")

    # --- Limits & housekeeping ---
    RATELIMIT_PER_MINUTE = int(os.getenv("RATELIMIT_PER_MINUTE", "120"))
    CLEAN_DAYS = int(os.getenv("CLEAN_DAYS", "7"))

    # --- JWT ---
    JWT_ACCESS_TOKEN_EXPIRES = timedelta(
        hours=int(os.getenv("JWT_EXPIRES_HOURS", "12")))
    # Разрешаем заголовок и query (?token=) — для прямых ссылок скачивания
    JWT_TOKEN_LOCATION = ["headers", "query_string"]
    JWT_QUERY_STRING_NAME = "token"
    JWT_HEADER_NAME = "Authorization"
    JWT_HEADER_TYPE = "Bearer"

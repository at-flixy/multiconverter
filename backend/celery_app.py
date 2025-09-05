import os
import pathlib
from celery import Celery
from flask import Flask

from config import Config
from database import db


def create_flask_app():
    app = Flask(__name__, instance_relative_config=True)
    app.config.from_object(Config)

    # БД в instance/app.db
    os.makedirs(app.instance_path, exist_ok=True)
    db_file = pathlib.Path(app.instance_path) / "app.db"
    app.config["SQLALCHEMY_DATABASE_URI"] = "sqlite:///" + db_file.as_posix()

    # SQLite
    app.config.setdefault("SQLALCHEMY_ENGINE_OPTIONS", {})
    connect_args = app.config["SQLALCHEMY_ENGINE_OPTIONS"].get(
        "connect_args", {})
    connect_args.update({"check_same_thread": False})
    app.config["SQLALCHEMY_ENGINE_OPTIONS"]["connect_args"] = connect_args

    # Подстраховка путей
    if not app.config.get("UPLOAD_DIR"):
        app.config["UPLOAD_DIR"] = os.path.join(app.instance_path, "uploads")
    if not app.config.get("RESULT_DIR"):
        app.config["RESULT_DIR"] = os.path.join(app.instance_path, "results")
    os.makedirs(app.config["UPLOAD_DIR"], exist_ok=True)
    os.makedirs(app.config["RESULT_DIR"], exist_ok=True)

    db.init_app(app)
    return app


flask_app = create_flask_app()

# Не передаём старые CELERY_* в конфиг!
# Явно задаём новые ключи (Celery 5+)
broker_url = (
    os.getenv("CELERY_BROKER_URL")
    or flask_app.config.get("CELERY_BROKER_URL")
    or "redis://localhost:6379/0"
)
result_backend = (
    os.getenv("CELERY_RESULT_BACKEND")
    or flask_app.config.get("CELERY_RESULT_BACKEND")
    or "redis://localhost:6379/0"
)

celery = Celery(flask_app.import_name, include=["tasks"])
celery.conf.update(
    broker_url=broker_url,
    result_backend=result_backend,
    accept_content=["json"],
    task_serializer="json",
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
)

# Опционально: синхронный режим для локальной отладки (без Redis/воркера)
if os.getenv("MC_EAGER", "0") == "1":
    celery.conf.task_always_eager = True


class AppContextTask(celery.Task):
    abstract = True

    def __call__(self, *args, **kwargs):
        with flask_app.app_context():
            return super().__call__(*args, **kwargs)


celery.Task = AppContextTask

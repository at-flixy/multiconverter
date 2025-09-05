import os
import json
import hashlib
import mimetypes
import pathlib
from datetime import timedelta, datetime

from flask import Flask, request, jsonify, send_file
from flask_cors import CORS
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from flask_jwt_extended import (
    JWTManager, create_access_token, jwt_required, get_jwt_identity
)
from werkzeug.security import generate_password_hash, check_password_hash

from config import Config
from database import db
from models import User, File, Task, TaskFile, Notification
from tasks import run_task

# ----------------------------------------------------------------------------
# Flask app & Config
# ----------------------------------------------------------------------------
app = Flask(
    __name__,
    instance_relative_config=True,
    static_folder="web",
    static_url_path=""
)
app.config.from_object(Config)

# JWT: разрешаем заголовок и ?token=
app.config.setdefault("JWT_TOKEN_LOCATION", ["headers", "query_string"])
app.config.setdefault("JWT_QUERY_STRING_NAME", "token")

# БД в instance/app.db
os.makedirs(app.instance_path, exist_ok=True)
db_file = pathlib.Path(app.instance_path) / "app.db"
app.config["SQLALCHEMY_DATABASE_URI"] = "sqlite:///" + db_file.as_posix()

# SQLite в многопоточном окружении
app.config.setdefault("SQLALCHEMY_ENGINE_OPTIONS", {})
_ce = app.config["SQLALCHEMY_ENGINE_OPTIONS"].get("connect_args", {})
_ce.update({"check_same_thread": False})
app.config["SQLALCHEMY_ENGINE_OPTIONS"]["connect_args"] = _ce

# Лимиты
app.config.setdefault("RATELIMIT_PER_MINUTE", 120)

# ---- КЛЮЧЕВАЯ ПОДСТРАХОВКА ПУТЕЙ ----
# если в Config пришло None — подставляем instance/*
if not app.config.get("UPLOAD_DIR"):
    app.config["UPLOAD_DIR"] = os.path.join(app.instance_path, "uploads")
if not app.config.get("RESULT_DIR"):
    app.config["RESULT_DIR"] = os.path.join(app.instance_path, "results")

UPLOAD_DIR = app.config["UPLOAD_DIR"]
RESULT_DIR = app.config["RESULT_DIR"]
os.makedirs(UPLOAD_DIR, exist_ok=True)
os.makedirs(RESULT_DIR, exist_ok=True)

# Extensions
CORS(app, supports_credentials=True)
limiter = Limiter(get_remote_address, app=app,
                  default_limits=[f"{app.config['RATELIMIT_PER_MINUTE']}/minute"])
jwt = JWTManager(app)
db.init_app(app)

with app.app_context():
    db.create_all()

print("DB URI ->", app.config["SQLALCHEMY_DATABASE_URI"])
print("Instance ->", app.instance_path)

# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------


def _current_user():
    uid = get_jwt_identity()
    return User.query.get(uid) if uid else None


def _send_file_response(f: File):
    p = pathlib.Path(f.path)
    if not p.exists():
        return {"error": "file_missing_on_disk", "path": str(p)}, 410
    return send_file(str(p), as_attachment=True, download_name=p.name, max_age=0)

# ----------------------------------------------------------------------------
# Options (для UI)
# ----------------------------------------------------------------------------


@app.get("/api/options/<type_name>")
def options_type(type_name):
    schemas = {
        "image": {"to": ["png", "jpeg", "webp"], "width": "int?", "height": "int?", "quality": "10..100"},
        "audio": {"to": ["mp3", "m4a", "ogg", "wav"], "bitrate": "e.g. 192k", "channels": [1, 2]},
        "video": {"to": ["mp4", "webm", "mkv"], "resolution": "e.g. 1280x720", "bitrate": "e.g. 1500k"},
        "document": {"action": ["docx2pdf", "pdf2txt", "xlsx2csv"]},
        "archive": {"action": ["pack", "unpack"], "pack_format": ["zip"]},
        "ocr": {"lang": "e.g. rus+eng"},
        "translation": {"to": "lang code", "text": "string"},
        "generation": {
            "model": ["gpt-image-1", "dall-e-3"],
            "prompt": "string",
            "size": ["1024x1024", "512x512", "256x256"]
        },
        "download": {"url": "string", "audioOnly": "bool"},
    }
    return jsonify({"options": schemas.get(type_name, {})})

# ----------------------------------------------------------------------------
# Health & index
# ----------------------------------------------------------------------------


@app.get("/api/health")
def health():
    return {"ok": True, "time": datetime.utcnow().isoformat()}


@app.get("/")
def root_index():
    return app.send_static_file("index.html")

# ----------------------------------------------------------------------------
# Auth
# ----------------------------------------------------------------------------


@app.post("/api/auth/register")
def register():
    data = request.get_json(force=True) or {}
    email = data.get("email")
    password = data.get("password")
    if not email or not password:
        return {"error": "email and password required"}, 400
    if User.query.filter_by(email=email).first():
        return {"error": "email exists"}, 409
    u = User(email=email, password_hash=generate_password_hash(
        password), name=data.get("name"))
    db.session.add(u)
    db.session.commit()
    return {"ok": True}


@app.post("/api/auth/login")
def login():
    data = request.get_json(force=True) or {}
    email = data.get("email")
    password = data.get("password")
    u = User.query.filter_by(email=email).first()
    if not u or not check_password_hash(u.password_hash, password):
        return {"error": "invalid credentials"}, 401
    token = create_access_token(
        identity=u.id, expires_delta=timedelta(hours=12))
    return {"access_token": token}


@app.get("/api/me")
@jwt_required()
def me():
    u = _current_user()
    return {"id": u.id, "email": u.email, "name": u.name, "created_at": u.created_at.isoformat()}


@app.patch("/api/me")
@jwt_required()
def me_update():
    u = _current_user()
    data = request.get_json(force=True) or {}
    if "name" in data:
        u.name = data["name"]
    if "password" in data and data["password"]:
        u.password_hash = generate_password_hash(data["password"])
    db.session.commit()
    return {"ok": True}

# ----------------------------------------------------------------------------
# Files
# ----------------------------------------------------------------------------


@app.post("/api/files/upload")
@jwt_required()
def upload_file():
    user = _current_user()
    if "file" not in request.files:
        return {"error": "no file"}, 400
    f = request.files["file"]
    if f.filename == "":
        return {"error": "empty filename"}, 400
    path = os.path.join(
        UPLOAD_DIR, f"{datetime.utcnow().timestamp()}_{f.filename}")
    f.save(path)
    mime = mimetypes.guess_type(path)[0] or "application/octet-stream"
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(8192), b""):
            h.update(chunk)
    checksum = h.hexdigest()
    size = os.path.getsize(path)
    fobj = File(owner_id=user.id, kind="uploaded", path=path,
                mime=mime, size=size, checksum=checksum)
    db.session.add(fobj)
    db.session.commit()
    return {"file_id": fobj.id, "mime": fobj.mime, "size": fobj.size}

# новый путь скачивания


@app.get("/api/files/<string:file_id>/download")
@jwt_required()
def download_file(file_id: str):
    user = _current_user()
    f = File.query.get(file_id)
    if not f or f.owner_id != user.id:
        return {"error": "not found"}, 404
    return _send_file_response(f)

# совместимость со старым путём


@app.get("/api/files/<string:file_id>")
@jwt_required()
def download_file_legacy(file_id: str):
    user = _current_user()
    f = File.query.get(file_id)
    if not f or f.owner_id != user.id:
        return {"error": "not found"}, 404
    return _send_file_response(f)


@app.get("/api/files")
@jwt_required()
def list_files():
    user = _current_user()
    q = File.query.filter_by(owner_id=user.id).order_by(
        File.created_at.desc()).limit(100).all()
    return {"items": [{"id": i.id, "mime": i.mime, "size": i.size, "kind": i.kind,
                       "created_at": i.created_at.isoformat()} for i in q]}

# ----------------------------------------------------------------------------
# Tasks
# ----------------------------------------------------------------------------


@app.post("/api/tasks")
@jwt_required()
def create_task():
    user = _current_user()
    data = request.get_json(force=True) or {}
    task_type = data.get("type")
    params = data.get("params", {})
    file_ids = data.get("fileIds", [])

    t = Task(owner_id=user.id, type=task_type, params_json=json.dumps(params))
    db.session.add(t)
    db.session.commit()

    for fid in file_ids:
        db.session.add(TaskFile(task_id=t.id, file_id=fid, role="input"))
    db.session.commit()

    run_task.delay(t.id)
    return {"task_id": t.id, "status": t.status}


@app.get("/api/tasks/<string:task_id>")
@jwt_required()
def get_task(task_id: str):
    user = _current_user()
    t = Task.query.get(task_id)
    if not t or t.owner_id != user.id:
        return {"error": "not found"}, 404
    outputs = []
    for tf in TaskFile.query.filter_by(task_id=t.id, role="output").all():
        f = File.query.get(tf.file_id)
        if f:
            outputs.append({"file_id": f.id, "mime": f.mime, "size": f.size})
    return {
        "id": t.id, "type": t.type, "status": t.status, "progress": t.progress, "error": t.error,
        "created_at": t.created_at.isoformat() if t.created_at else None,
        "started_at": t.started_at.isoformat() if t.started_at else None,
        "finished_at": t.finished_at.isoformat() if t.finished_at else None,
        "outputs": outputs
    }


@app.get("/api/tasks")
@jwt_required()
def list_tasks():
    user = _current_user()
    q = Task.query.filter_by(owner_id=user.id).order_by(
        Task.created_at.desc()).limit(100).all()
    items = [{"id": t.id, "type": t.type, "status": t.status,
              "progress": t.progress, "created_at": t.created_at.isoformat()} for t in q]
    return {"items": items}

# ----------------------------------------------------------------------------
# Notifications
# ----------------------------------------------------------------------------


@app.get("/api/notifications")
@jwt_required()
def notifications():
    user = _current_user()
    q = Notification.query.filter_by(user_id=user.id).order_by(
        Notification.created_at.desc()).limit(50).all()
    return {"items": [{
        "id": n.id, "type": n.type, "message": n.message,
        "created_at": n.created_at.isoformat(),
        "read_at": n.read_at.isoformat() if n.read_at else None
    } for n in q]}

# ----------------------------------------------------------------------------
# Cleanup
# ----------------------------------------------------------------------------


@app.post("/api/cleanup")
@jwt_required()
def cleanup_user():
    user = _current_user()
    action = (request.json or {}).get("action", "")
    if not action:
        return {"error": "action required"}, 400

    if action in ("delete_inputs", "delete_all"):
        files = File.query.filter_by(owner_id=user.id, kind="uploaded").all()
        for f in files:
            try:
                if os.path.exists(f.path):
                    os.remove(f.path)
            except Exception:
                pass
            db.session.delete(f)

    if action in ("delete_outputs", "delete_all"):
        files = File.query.filter_by(owner_id=user.id, kind="converted").all()
        for f in files:
            try:
                if os.path.exists(f.path):
                    os.remove(f.path)
            except Exception:
                pass
            db.session.delete(f)

    if action in ("delete_tasks", "delete_all"):
        TaskFile.query.filter(
            TaskFile.task_id.in_(
                [t.id for t in Task.query.filter_by(owner_id=user.id).all()])
        ).delete(synchronize_session=False)
        Task.query.filter_by(owner_id=user.id).delete(
            synchronize_session=False)

    db.session.commit()
    return {"ok": True}

# ----------------------------------------------------------------------------
# Bulk deletes
# ----------------------------------------------------------------------------


@app.post("/api/files/delete")
@jwt_required()
def delete_files_bulk():
    user = _current_user()
    ids = (request.json or {}).get("ids", [])
    if not isinstance(ids, list):
        return {"error": "ids must be list"}, 400
    items = File.query.filter(File.id.in_(ids), File.owner_id == user.id).all()
    cnt = 0
    for f in items:
        try:
            if os.path.exists(f.path):
                os.remove(f.path)
        except Exception:
            pass
        db.session.delete(f)
        cnt += 1
    db.session.commit()
    return {"deleted": cnt}


@app.post("/api/tasks/delete")
@jwt_required()
def delete_tasks_bulk():
    user = _current_user()
    ids = (request.json or {}).get("ids", [])
    if not isinstance(ids, list):
        return {"error": "ids must be list"}, 400
    links = TaskFile.query.filter(TaskFile.task_id.in_(ids)).all()
    for l in links:
        db.session.delete(l)
    tasks = Task.query.filter(Task.id.in_(ids), Task.owner_id == user.id).all()
    cnt = 0
    for t in tasks:
        db.session.delete(t)
        cnt += 1
    db.session.commit()
    return {"deleted": cnt}


# ----------------------------------------------------------------------------
# Entrypoint
# ----------------------------------------------------------------------------
if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)

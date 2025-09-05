import uuid
from datetime import datetime
from database import db


def gen_uuid():
    return str(uuid.uuid4())


class User(db.Model):
    __tablename__ = "users"
    id = db.Column(db.String, primary_key=True, default=gen_uuid)
    email = db.Column(db.String, unique=True, nullable=False, index=True)
    password_hash = db.Column(db.String, nullable=False)
    name = db.Column(db.String, nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, index=True)


class File(db.Model):
    __tablename__ = "files"
    id = db.Column(db.String, primary_key=True, default=gen_uuid)
    owner_id = db.Column(db.String, db.ForeignKey(
        "users.id"), nullable=False, index=True)
    kind = db.Column(db.String, nullable=False)  # uploaded|converted
    path = db.Column(db.String, nullable=False)
    mime = db.Column(db.String, nullable=False)
    size = db.Column(db.Integer, nullable=False)
    checksum = db.Column(db.String, nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, index=True)
    expires_at = db.Column(db.DateTime, nullable=True)

    __table_args__ = (
        db.Index("ix_files_owner_created", "owner_id", "created_at"),
    )


class Task(db.Model):
    __tablename__ = "tasks"
    id = db.Column(db.String, primary_key=True, default=gen_uuid)
    owner_id = db.Column(db.String, db.ForeignKey(
        "users.id"), nullable=False, index=True)
    type = db.Column(db.String, nullable=False)
    status = db.Column(db.String, default="QUEUED")
    progress = db.Column(db.Integer, default=0)
    params_json = db.Column(db.Text, nullable=True)
    error = db.Column(db.Text, nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, index=True)
    started_at = db.Column(db.DateTime, nullable=True)
    finished_at = db.Column(db.DateTime, nullable=True)

    __table_args__ = (
        db.Index("ix_tasks_owner_created", "owner_id", "created_at"),
    )


class TaskFile(db.Model):
    __tablename__ = "task_files"
    task_id = db.Column(db.String, db.ForeignKey("tasks.id"), primary_key=True)
    file_id = db.Column(db.String, db.ForeignKey("files.id"), primary_key=True)
    role = db.Column(db.String, nullable=False)  # input|output


class Notification(db.Model):
    __tablename__ = "notifications"
    id = db.Column(db.String, primary_key=True, default=gen_uuid)
    user_id = db.Column(db.String, db.ForeignKey(
        "users.id"), nullable=False, index=True)
    type = db.Column(db.String, nullable=False)  # task_done|task_failed
    message = db.Column(db.String, nullable=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, index=True)
    read_at = db.Column(db.DateTime, nullable=True)

# backend/tasks.py
import json
import os
import base64
import glob
import mimetypes
import shutil
import subprocess
import hashlib
from datetime import datetime, timedelta

from celery_app import celery, flask_app
from database import db
from models import Task, File, TaskFile, Notification

from PIL import Image
import ffmpeg
from pydub import AudioSegment
import pytesseract
from pdf2image import convert_from_path

# --- OpenAI (опционально) ---
OPENAI_API_KEY = flask_app.config.get("OPENAI_API_KEY", "")
OPENAI_API_BASE = flask_app.config.get(
    "OPENAI_API_BASE")  # опционально из .env

try:
    if OPENAI_API_KEY:
        from openai import OpenAI
        openai_client = (
            OpenAI(api_key=OPENAI_API_KEY, base_url=OPENAI_API_BASE)
            if OPENAI_API_BASE else
            OpenAI(api_key=OPENAI_API_KEY)
        )
    else:
        openai_client = None
except Exception:
    openai_client = None

# --- Paths / settings ---
UPLOAD_DIR = flask_app.config["UPLOAD_DIR"]
RESULT_DIR = flask_app.config["RESULT_DIR"]
CLEAN_DAYS = int(flask_app.config.get("CLEAN_DAYS", 7))
os.makedirs(UPLOAD_DIR, exist_ok=True)
os.makedirs(RESULT_DIR, exist_ok=True)


# ---------------- helpers ----------------
def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def _update_task(t: Task, **kwargs):
    for k, v in kwargs.items():
        setattr(t, k, v)
    db.session.commit()


def _notify(user_id, type_, message):
    n = Notification(user_id=user_id, type=type_, message=message)
    db.session.add(n)
    db.session.commit()


def _attach_output(task_id, file_id):
    tf = TaskFile(task_id=task_id, file_id=file_id, role="output")
    db.session.add(tf)
    db.session.commit()


def _new_converted_file(owner_id, path, mime=None):
    if not mime:
        mime = mimetypes.guess_type(path)[0] or "application/octet-stream"
    size = os.path.getsize(path)
    checksum = _sha256(path)
    f = File(owner_id=owner_id, kind="converted", path=path,
             mime=mime, size=size, checksum=checksum)
    db.session.add(f)
    db.session.commit()
    return f


def _soffice_convert(infile, target_ext, outdir):
    cmd = ["soffice", "--headless", "--convert-to",
           target_ext, "--outdir", outdir, infile]
    subprocess.run(cmd, check=True)


# ---------------- main task ----------------
@celery.task(name="tasks.run_task")
def run_task(task_id: str):
    t: Task = Task.query.get(task_id)
    if not t:
        return
    _update_task(t, status="RUNNING", progress=5, started_at=datetime.utcnow())

    try:
        params = json.loads(t.params_json or "{}")
        input_tfs = TaskFile.query.filter_by(task_id=t.id, role="input").all()
        inputs = [File.query.get(itf.file_id)
                  for itf in input_tfs if itf.file_id]

        if t.type == "image":
            infile = inputs[0]
            target = (params.get("to", "png") or "png").lower()
            out_path = os.path.join(RESULT_DIR, f"{t.id}.{target}")
            with Image.open(infile.path) as im:
                w = params.get("width")
                h = params.get("height")
                if w or h:
                    w = int(w) if w else im.width
                    h = int(h) if h else im.height
                    im = im.resize((w, h))
                if target in ("jpg", "jpeg"):
                    im.convert("RGB").save(out_path, format="JPEG",
                                           quality=int(params.get("quality", 90)))
                elif target == "webp":
                    im.save(out_path, format="WEBP", quality=int(
                        params.get("quality", 90)))
                else:
                    im.save(out_path, format=target.upper())
            f = _new_converted_file(t.owner_id, out_path)

        elif t.type == "audio":
            infile = inputs[0]
            target = (params.get("to", "mp3") or "mp3").lower()
            out_path = os.path.join(RESULT_DIR, f"{t.id}.{target}")
            audio = AudioSegment.from_file(infile.path)
            if "channels" in params:
                audio = audio.set_channels(int(params["channels"]))
            audio.export(out_path, format=target,
                         bitrate=params.get("bitrate", "192k"))
            f = _new_converted_file(t.owner_id, out_path)

        elif t.type == "video":
            infile = inputs[0]
            target = (params.get("to", "mp4") or "mp4").lower()
            out_path = os.path.join(RESULT_DIR, f"{t.id}.{target}")
            stream = ffmpeg.input(infile.path)
            kwargs = {}
            if "resolution" in params:
                kwargs["s"] = params["resolution"]
            if "bitrate" in params:
                kwargs["b:v"] = params["bitrate"]
            stream = ffmpeg.output(stream, out_path, **kwargs)
            ffmpeg.run(stream, overwrite_output=True, quiet=True)
            f = _new_converted_file(t.owner_id, out_path)

        elif t.type == "archive":
            action = params.get("action", "pack")
            if action == "pack":
                out_path = os.path.join(RESULT_DIR, f"{t.id}.zip")
                import zipfile
                with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zf:
                    for inf in inputs:
                        zf.write(inf.path, arcname=os.path.basename(inf.path))
                f = _new_converted_file(
                    t.owner_id, out_path, "application/zip")
            elif action == "unpack":
                unpack_dir = os.path.join(RESULT_DIR, f"{t.id}_unpacked")
                os.makedirs(unpack_dir, exist_ok=True)
                arc = inputs[0].path
                try:
                    import py7zr
                    if arc.lower().endswith(".7z"):
                        with py7zr.SevenZipFile(arc, "r") as z:
                            z.extractall(unpack_dir)
                    elif arc.lower().endswith(".zip"):
                        import zipfile
                        with zipfile.ZipFile(arc, "r") as z:
                            z.extractall(unpack_dir)
                    elif arc.lower().endswith(".rar"):
                        import patoolib
                        patoolib.extract_archive(arc, outdir=unpack_dir)
                    else:
                        import patoolib
                        patoolib.extract_archive(arc, outdir=unpack_dir)
                except Exception:
                    import patoolib
                    patoolib.extract_archive(arc, outdir=unpack_dir)
                out_path = os.path.join(RESULT_DIR, f"{t.id}.zip")
                shutil.make_archive(out_path[:-4], "zip", unpack_dir)
                f = _new_converted_file(
                    t.owner_id, out_path, "application/zip")
            else:
                raise RuntimeError("archive.action must be pack or unpack")

        elif t.type == "document":
            infile = inputs[0]
            action = params.get("action", "pdf2txt")
            if action == "docx2pdf":
                _soffice_convert(infile.path, "pdf", RESULT_DIR)
                tmp = os.path.join(RESULT_DIR, os.path.splitext(
                    os.path.basename(infile.path))[0] + ".pdf")
                final_path = os.path.join(RESULT_DIR, f"{t.id}.pdf")
                shutil.move(tmp, final_path)
                f = _new_converted_file(
                    t.owner_id, final_path, "application/pdf")
            elif action == "pdf2txt":
                from pdfminer.high_level import extract_text
                text = extract_text(infile.path)
                out_path = os.path.join(RESULT_DIR, f"{t.id}.txt")
                with open(out_path, "w", encoding="utf-8") as fh:
                    fh.write(text or "")
                f = _new_converted_file(t.owner_id, out_path, "text/plain")
            elif action == "xlsx2csv":
                out_path = os.path.join(RESULT_DIR, f"{t.id}.csv")
                import sys
                import subprocess as sp
                sp.run([sys.executable, "-m", "xlsx2csv",
                       infile.path, out_path], check=True)
                f = _new_converted_file(t.owner_id, out_path, "text/csv")
            else:
                raise RuntimeError(
                    "document.action must be one of: docx2pdf|pdf2txt|xlsx2csv")

        elif t.type == "ocr":
            infile = inputs[0]
            ext = os.path.splitext(infile.path)[1].lower()
            if ext == ".pdf":
                pages = convert_from_path(infile.path, 200)
                img_path = os.path.join(RESULT_DIR, f"{t.id}_p1.png")
                pages[0].save(img_path, "PNG")
                text = pytesseract.image_to_string(Image.open(
                    img_path), lang=params.get("lang", "rus+eng"))
            else:
                text = pytesseract.image_to_string(Image.open(
                    infile.path), lang=params.get("lang", "rus+eng"))
            out_path = os.path.join(RESULT_DIR, f"{t.id}.txt")
            with open(out_path, "w", encoding="utf-8") as fh:
                fh.write(text or "")
            f = _new_converted_file(t.owner_id, out_path, "text/plain")

        elif t.type == "translation":
            src_text = params.get("text", "") or ""
            target = params.get("to", "en") or "en"
            translated = None
            if openai_client:
                try:
                    prompt = f"Translate the following text to {target}:\n\n{src_text}"
                    completion = openai_client.chat.completions.create(
                        model="gpt-4o-mini",
                        messages=[{"role": "user", "content": prompt}],
                        temperature=0.2,
                    )
                    translated = completion.choices[0].message.content or ""
                except Exception:
                    translated = None
            if translated is None:
                translated = f"[stub translation to {target}]\n{src_text}"
            out_path = os.path.join(RESULT_DIR, f"{t.id}.txt")
            with open(out_path, "w", encoding="utf-8") as fh:
                fh.write(translated)
            f = _new_converted_file(t.owner_id, out_path, "text/plain")

        elif t.type == "generation":
            prompt = params.get("prompt", "A cute cat") or "A cute cat"
            size = params.get("size", "1024x1024") or "1024x1024"
            # по умолчанию gpt-image-1
            model = (params.get("model") or "gpt-image-1").strip()
            out_path = os.path.join(RESULT_DIR, f"{t.id}.png")

            did_openai = False
            if openai_client:
                try:
                    # Актуальный Images API
                    img = openai_client.images.generate(
                        model=model, prompt=prompt, size=size)
                    b64 = img.data[0].b64_json
                    with open(out_path, "wb") as fimg:
                        fimg.write(base64.b64decode(b64))
                    did_openai = True
                except Exception:
                    did_openai = False

            if not did_openai:
                # fallback на случай отсутствия ключа/сети/квоты
                from PIL import Image as _Image, ImageDraw as _ImageDraw
                w, h = [int(x) for x in size.split(
                    "x")] if "x" in size else (1024, 1024)
                image = _Image.new("RGB", (w, h), color=(30, 30, 30))
                draw = _ImageDraw.Draw(image)
                draw.text(
                    (20, 20), f"Placeholder\n{prompt[:60]}", fill=(200, 200, 200))
                image.save(out_path, "PNG")

            f = _new_converted_file(t.owner_id, out_path, "image/png")

        elif t.type == "download":
            import yt_dlp
            url = params.get("url")
            if not url:
                raise RuntimeError("download.url is required")
            tmpl = os.path.join(RESULT_DIR, f"{t.id}.%(ext)s")
            ydl_opts = {"outtmpl": tmpl}
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                ydl.download([url])
            produced = None
            base = os.path.join(RESULT_DIR, f"{t.id}")
            for ext in (".mp4", ".mkv", ".webm", ".mp3", ".m4a"):
                p = base + ext
                if os.path.exists(p):
                    produced = p
                    break
            if not produced:
                cand = glob.glob(os.path.join(RESULT_DIR, f"{t.id}.*"))
                if cand:
                    produced = cand[0]
            if not produced:
                raise RuntimeError("No output produced by yt-dlp")
            f = _new_converted_file(t.owner_id, produced)

        else:
            raise RuntimeError(f"Unknown task type: {t.type}")

        _attach_output(t.id, f.id)
        _update_task(t, progress=100, status="COMPLETED",
                     finished_at=datetime.utcnow())
        _notify(t.owner_id, "task_done",
                f"Задача {t.type} завершена, файл готов")

    except Exception as e:
        _update_task(t, status="FAILED", error=str(e),
                     finished_at=datetime.utcnow())
        _notify(t.owner_id, "task_failed",
                f"Задача {t.type} не выполнена: {e}")


@celery.task(name="tasks.cleanup_results")
def cleanup_results():
    limit = datetime.utcnow() - timedelta(days=int(flask_app.config.get("CLEAN_DAYS", 7)))
    q = File.query.filter(File.kind == "converted",
                          File.created_at < limit).all()
    for f in q:
        try:
            if os.path.exists(f.path):
                os.remove(f.path)
        except Exception:
            pass
        db.session.delete(f)
    db.session.commit()

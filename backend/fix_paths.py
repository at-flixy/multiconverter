# backend/fix_paths.py
import os
import pathlib
import hashlib

from app import app, db
from models import File


def _sha256(p: pathlib.Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as fh:
        for chunk in iter(lambda: fh.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def _candidate_bases() -> list[pathlib.Path]:
    bases = set()

    # из конфигов приложения
    for d in (app.config.get("UPLOAD_DIR"), app.config.get("RESULT_DIR")):
        if d:
            bases.add(pathlib.Path(d))

    # стандартные места
    inst = pathlib.Path(app.instance_path)
    bases.update({
        inst,
        inst / "uploads",
        inst / "results",
        pathlib.Path("/app/uploads"),
        pathlib.Path("/app/results"),
        pathlib.Path(r"C:\multiconverter_project_full\backend\instance"),
        pathlib.Path(
            r"C:\multiconverter_project_full\backend\instance\uploads"),
        pathlib.Path(
            r"C:\multiconverter_project_full\backend\instance\results"),
    })

    # доп. пути из переменной окружения (через ; )
    extra = os.getenv("FIXPATHS_EXTRA_BASES", "")
    for chunk in [c.strip() for c in extra.split(";") if c.strip()]:
        bases.add(pathlib.Path(chunk))

    # только существующие директории
    return [b for b in bases if b.exists()]


def _candidates_for(file_path: str):
    """Генерим возможные новые пути по basename."""
    name = pathlib.Path(file_path).name
    for base in _candidate_bases():
        yield base / name


def relink_missing_files(verify_checksum: bool = True, dry_run: bool = False):
    fixed = 0
    missing = 0
    skipped = 0

    with app.app_context():
        items = File.query.all()
        for f in items:
            # если текущий путь валиден — пропускаем
            if f.path and os.path.exists(f.path):
                skipped += 1
                continue

            new_path = None
            for cand in _candidates_for(f.path or ""):
                if cand.exists():
                    if verify_checksum and getattr(f, "checksum", None):
                        try:
                            if _sha256(cand) != f.checksum:
                                continue
                        except Exception:
                            # не смогли прочитать файл — пробуем дальше
                            continue
                    new_path = str(cand)
                    break

            if new_path:
                f.path = new_path
                fixed += 1
            else:
                missing += 1

        if not dry_run:
            db.session.commit()

    print(
        f"Relinked: {fixed} | Still missing: {missing} | Valid (skipped): {skipped} | Total: {fixed+missing+skipped}")


if __name__ == "__main__":
    # ENV-переключатели:
    #   DRY_RUN=1 — только показать результат без сохранения
    #   VERIFY_CHECKSUM=0 — не проверять checksum при сопоставлении
    dry_run = os.getenv("DRY_RUN", "0") == "1"
    verify = os.getenv("VERIFY_CHECKSUM", "1") == "1"
    relink_missing_files(verify_checksum=verify, dry_run=dry_run)

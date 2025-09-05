FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

# Системные утилиты для конвертаций/OCR/архивов
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    tesseract-ocr tesseract-ocr-eng tesseract-ocr-rus \
    poppler-utils \
    p7zip-full unrar-free \
    libreoffice \
    fonts-dejavu-core \
    build-essential \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Пакеты Python
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

# Код приложения
COPY . /app/

# Директории хранилища (совпадает с .env)
RUN mkdir -p /app/uploads /app/results /app/instance

EXPOSE 5000

# По умолчанию — бэкенд (compose переопределяет для worker/beat)
CMD ["gunicorn", "-w", "2", "-k", "gthread", "-b", "0.0.0.0:5000", "app:app"]

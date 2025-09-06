# Multiconverter — FULL (Vert.x + Flask + Celery + SQLite)

```
   _   _           _                 _             _      _                 
  /_\ | |_ __ ___ (_)_ __  __  __   /_\  _ __ ___ (_)_ __| |__   __ _ _ __  
 //_\\| | '_ ` _ \| | '__| \ \/ /  //_\\| '_ ` _ \| | '__| '_ \ / _` | '_ \ 
/  _  \ | | | | | | | |     >  <  /  _  \ | | | | | | |  | | | | (_| | | | |
\_/ \_/_|_| |_| |_|_|_|    /_/\_\ \_/ \_/_| |_| |_|_|_|  |_| |_|\__,_|_| |_|

```
                                              
## Состав
- **gateway/** — Kotlin Vert.x: раздаёт `web/`, проксирует `/api/*` → Flask
- **backend/** — Flask API, Celery воркеры/beat, SQLite, файлы в `static/`
- **web/** — адаптивный UI (Drag&Drop, история, скачивание результатов)
- **redis** — брокер Celery

## Запуск (Docker)
```bash
cp .env.example .env
docker compose up --build
# UI: http://localhost:8080
# API: http://localhost:5000
```
Переменные в `.env`:
```
SECRET_KEY=change-me
JWT_SECRET_KEY=change-me-too
OPENAI_API_KEY=   # опционально для перевода/генерации
```

## Возможности по ТЗ
- Регистрация/авторизация (JWT)
- Drag&Drop загрузка
- Конвертация: изображения, аудио, видео, архивы (zip pack/unpack), документы (DOCX→PDF, PDF→TXT, XLSX→CSV), OCR (ru+en), перевод (GPT), генерация изображений (DALL·E 3 1024×1024), загрузка видео (YouTube/Instagram/TikTok)
- История задач/файлов, уведомления о результатах
- Лимиты запросов, очистка старых результатов (Celery beat, по умолчанию 7 дней)

## Примеры задач
- Image: `{"to":"webp","quality":90,"width":1280}`
- Audio: `{"to":"mp3","bitrate":"192k","channels":2}`
- Video: `{"to":"mp4","resolution":"1280x720","bitrate":"1500k"}`
- Archive pack: `{"action":"pack","format":"zip"}` (передайте несколько fileIds)
- Archive unpack: `{"action":"unpack"}` (один архив)
- Document: `{"action":"docx2pdf"}` или `{"action":"pdf2txt"}` или `{"action":"xlsx2csv"}`
- OCR: `{"lang":"rus+eng"}`
- Translation: `{"text":"Привет мир","to":"en"}`
- Generation: `{"prompt":"mountains at sunrise","size":"1024x1024"}`
- Download: `{"url":"https://www.youtube.com/watch?v=..."}"

## Маршруты API (основные)
- `POST /api/auth/register`, `POST /api/auth/login`
- `GET /api/me`, `PATCH /api/me`
- `POST /api/files/upload`, `GET /api/files/:id`
- `POST /api/tasks`, `GET /api/tasks/:id`, `GET /api/tasks`
- `GET /api/notifications`

## Основной граф платформы
          [ Web UI ]  
              │  
              ▼  
        [ Gateway (Vert.x) ]  
              │  
   ┌──────────┴───────────┐  
   ▼                      ▼  
[ Flask API ]       [ Static Files ]  
      │  
      ▼  
 [ Celery Workers ] <──> [ Redis Broker ]  
      │  
      ▼  
   [ SQLite DB ]  


                                                                            

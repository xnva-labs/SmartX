FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 XNVA_DATA_DIR=/data
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY xnva ./xnva
RUN useradd --system --uid 10001 --home /app xnva && mkdir /data && chown xnva /data
USER xnva
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD python -c "import urllib.request as u; u.urlopen('http://127.0.0.1:8080/healthz', timeout=3)"
# One worker keeps the in-process rate limiter and key cache coherent; threads give concurrency.
# SQLite serializes writes, so scale vertically or move the rate limit to the reverse proxy (see docs/OPERATIONS.md).
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--workers", "1", "--threads", "8", "--timeout", "120", "--access-logfile", "-", "xnva.wsgi:app"]

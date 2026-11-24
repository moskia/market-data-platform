# One image for both: the monitor (default) and the dashboard.
FROM python:3.10.18-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY monitor ./monitor
COPY dashboard ./dashboard

ENV MONITOR_DB=/data/monitor.db
# Print straight away instead of keeping output in memory, so `docker compose logs` shows it.
ENV PYTHONUNBUFFERED=1
CMD ["python", "-m", "monitor.main"]

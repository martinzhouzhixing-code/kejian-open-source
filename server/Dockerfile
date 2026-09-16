FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home kejian && mkdir /data && chown kejian /data
COPY *.py ./
USER kejian
EXPOSE 8401
CMD ["uvicorn", "gateway:app", "--host", "0.0.0.0", "--port", "8401", "--workers", "1", "--no-access-log"]

# 静态编译的 ffmpeg/ffprobe（含 libx264），直接拷进 Python 镜像，不依赖 apt
FROM mwader/static-ffmpeg:7.1 AS ffmpeg

FROM python:3.12-slim
COPY --from=ffmpeg /ffmpeg /ffprobe /usr/local/bin/
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY common.py worker.py ./
CMD ["python", "-u", "worker.py"]

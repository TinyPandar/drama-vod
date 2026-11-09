# 静态编译的 ffmpeg/ffprobe（含 libx264），直接拷进 Python 镜像，不依赖 apt
FROM mwader/static-ffmpeg:7.1 AS ffmpeg

FROM python:3.12-slim
COPY --from=ffmpeg /ffmpeg /ffprobe /usr/local/bin/
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
# 复制全部模块（fencing.py、retry_policy.py 等），避免新增模块漏拷导致启动即 ImportError
COPY *.py ./
CMD ["python", "-u", "worker.py"]

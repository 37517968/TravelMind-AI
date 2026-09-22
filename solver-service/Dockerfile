FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
# PyPI 走清华源，国内服务器直连可用
ENV PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
EXPOSE 8091
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8091"]

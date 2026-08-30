FROM python:3.12-slim

WORKDIR /app
COPY apps/agent/requirements.txt requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY apps/agent/agent_runtime ./agent_runtime
COPY contracts ./contracts

CMD ["python", "-m", "agent_runtime.main"]

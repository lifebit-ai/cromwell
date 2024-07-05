FROM python:3.12.4-alpine3.20

WORKDIR /opt

ADD requirements.txt ./

RUN apk add --no-cache \
      build-base \
      libstdc++ \
      linux-headers \
    && \
    pip install -r requirements.txt && \
    apk del \
      build-base \
      linux-headers

ADD monitor.py ./

ENTRYPOINT ["./monitor.py"]

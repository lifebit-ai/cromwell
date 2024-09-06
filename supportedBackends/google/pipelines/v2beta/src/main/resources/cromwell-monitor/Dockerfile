FROM python:3.13.0rc1-alpine3.20

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

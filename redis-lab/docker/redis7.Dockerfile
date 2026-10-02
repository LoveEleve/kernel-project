# redis-lab:7 —— 本地构建（对齐 xhs 生产的 7.x；走 v3.21 仓库取 7.2.11）
# 构建：docker build --build-arg http_proxy=$http_proxy --build-arg https_proxy=$https_proxy -t redis-lab:7 -f docker/redis7.Dockerfile .
FROM alpine:latest
RUN printf "https://dl-cdn.alpinelinux.org/alpine/v3.21/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.21/community\n" > /etc/apk/repositories \
    && apk add --no-cache redis \
    && rm -rf /var/cache/apk/*
EXPOSE 6379
CMD ["redis-server"]

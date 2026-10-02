# redis-lab:8 —— 本地构建（通道受限时绕过 registry 拉取）
# 构建：docker build --build-arg http_proxy=$http_proxy --build-arg https_proxy=$https_proxy -t redis-lab:8 -f docker/redis8.Dockerfile .
FROM alpine:latest
RUN apk add --no-cache redis
EXPOSE 6379
CMD ["redis-server"]

# syntax=docker/dockerfile:1
# =====================================================================
# 前端镜像（Vite 构建产物 + Nginx 静态托管与反代）
# 构建上下文必须是仓库根目录，见 docker-compose.yml 的 build.context
# =====================================================================

# ---------- 阶段 1：构建 ----------
FROM node:22-alpine AS build
WORKDIR /src

# 锁定依赖层：只改页面代码时不会重装 node_modules
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY frontend/ ./
# package.json 的 build = vue-tsc -b && vite build，类型不过就构建失败
RUN npm run build

# ---------- 阶段 2：托管 ----------
FROM nginx:1.27-alpine

RUN rm -f /etc/nginx/conf.d/default.conf
COPY docker/web/nginx.conf /etc/nginx/conf.d/campus-ai.conf
COPY --from=build /src/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1

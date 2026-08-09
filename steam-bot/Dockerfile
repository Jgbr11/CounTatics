# ═══════════════════════════════════════════════════════
#  CounTatic Steam Bot — Multi-stage Dockerfile
# ═══════════════════════════════════════════════════════

# Stage 1: Build
FROM node:20-alpine AS builder

WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY tsconfig.json ./
COPY src ./src
RUN npm run build

# Stage 2: Runtime
FROM node:20-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production

COPY package*.json ./
RUN npm ci --only=production

COPY --from=builder /app/dist ./dist

# Diretório persistente para o refresh token da Steam.
# Precisa ser gravável: o steam-user renova o token periodicamente e,
# se não conseguir persistir a renovação, o login volta a exigir o
# código por e-mail a cada restart.
RUN mkdir -p /app/data
VOLUME ["/app/data"]
ENV TOKEN_FILE=/app/data/refreshToken.txt

ENV BOT_PORT=3000
EXPOSE 3000

CMD ["node", "dist/index.js"]

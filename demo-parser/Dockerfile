# ═══════════════════════════════════════════════════════
#  CounTatic Demo Parser — Multi-stage Dockerfile
# ═══════════════════════════════════════════════════════

# Stage 1: Build
FROM golang:1.22-alpine AS builder

RUN apk add --no-cache git

WORKDIR /app

# Copiar módulos primeiro para cache de dependências
COPY go.mod ./
COPY go.sum* ./
RUN go mod download

# Copiar código fonte, atualizar dependências e compilar
COPY . .
RUN go mod tidy
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /demo-parser ./cmd/server

# Stage 2: Runtime (imagem mínima)
FROM alpine:3.19

RUN apk add --no-cache ca-certificates

WORKDIR /app

COPY --from=builder /demo-parser .

# Porta padrão do serviço
ENV PARSER_PORT=8081
EXPOSE 8081

# Diretório para arquivos temporários de demo
RUN mkdir -p /tmp/demos

ENTRYPOINT ["./demo-parser"]

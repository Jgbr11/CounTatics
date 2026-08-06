package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
	"context"

	"github.com/countatic/demo-parser/internal/handler"
	"github.com/countatic/demo-parser/internal/parser"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)
	log.Println("═══════════════════════════════════════════════")
	log.Println("  CounTatic Demo Parser - CS2 Analysis Worker")
	log.Println("═══════════════════════════════════════════════")

	// ─── Configuração ─────────────────────────────────────────────────
	port := os.Getenv("PARSER_PORT")
	if port == "" {
		port = "8081"
	}

	// ─── Inicializar componentes ──────────────────────────────────────
	demoParser := parser.NewDemoParser()
	parseHandler := handler.NewParseHandler(demoParser)

	// ─── Rotas ────────────────────────────────────────────────────────
	mux := http.NewServeMux()
	mux.HandleFunc("/parse", parseHandler.HandleParse)
	mux.HandleFunc("/health", parseHandler.HandleHealth)

	// ─── Middleware: Logging & CORS ───────────────────────────────────
	wrappedMux := loggingMiddleware(corsMiddleware(mux))

	// ─── Servidor HTTP ────────────────────────────────────────────────
	server := &http.Server{
		Addr:         fmt.Sprintf(":%s", port),
		Handler:      wrappedMux,
		ReadTimeout:  10 * time.Minute,  // Demos pesadas podem demorar para upload
		WriteTimeout: 10 * time.Minute,  // Parsing pode demorar para demos grandes
		IdleTimeout:  120 * time.Second,
	}

	// ─── Graceful Shutdown ────────────────────────────────────────────
	go func() {
		log.Printf("Servidor HTTP iniciado na porta %s", port)
		log.Printf("Endpoints disponíveis:")
		log.Printf("  POST /parse   — Upload e parsing de arquivo .dem")
		log.Printf("  GET  /health  — Health check")

		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Erro ao iniciar servidor: %v", err)
		}
	}()

	// Esperar sinal de encerramento (SIGINT/SIGTERM)
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("Encerrando servidor graciosamente...")
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		log.Fatalf("Erro no shutdown do servidor: %v", err)
	}

	log.Println("Servidor encerrado com sucesso.")
}

// loggingMiddleware registra cada request recebido.
func loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		log.Printf("→ %s %s (from %s)", r.Method, r.URL.Path, r.RemoteAddr)
		next.ServeHTTP(w, r)
		log.Printf("← %s %s (%s)", r.Method, r.URL.Path, time.Since(start))
	})
}

// corsMiddleware adiciona headers CORS para comunicação com o Core Backend.
func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}

		next.ServeHTTP(w, r)
	})
}

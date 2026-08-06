package handler

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"time"

	"github.com/countatic/demo-parser/internal/parser"
)

// ParseHandler gerencia o endpoint POST /parse.
// Recebe um arquivo .dem via multipart/form-data, parseia assincronamente
// e retorna o JSON com os eventos extraídos.
type ParseHandler struct {
	parser *parser.DemoParser
}

// NewParseHandler cria uma nova instância do handler.
func NewParseHandler(p *parser.DemoParser) *ParseHandler {
	return &ParseHandler{parser: p}
}

// parseResponse é o envelope de resposta da API.
type parseResponse struct {
	Success  bool        `json:"success"`
	Message  string      `json:"message,omitempty"`
	Duration string      `json:"duration,omitempty"`
	Data     interface{} `json:"data,omitempty"`
}

// errorResponse é o envelope de resposta para erros.
type errorResponse struct {
	Success bool   `json:"success"`
	Error   string `json:"error"`
}

// HandleParse é o handler HTTP para POST /parse.
//
// Espera receber um multipart/form-data com o campo "demo" contendo o arquivo .dem.
// O parsing é executado na goroutine do request, mas o design do parser (io.Reader)
// permite que múltiplos requests sejam processados concorrentemente, cada um com
// seu próprio parser isolado.
//
// Para demos muito pesadas, o Core Backend pode usar um modelo de polling:
// 1. POST /parse → retorna um job ID
// 2. GET /parse/:id → verifica o status
// Esta versão implementa o modelo síncrono (request-response) por simplicidade.
func (h *ParseHandler) HandleParse(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "Apenas POST é permitido")
		return
	}

	// Limitar tamanho do upload (demos CS2 podem ter até ~300MB)
	const maxUploadSize = 500 << 20 // 500 MB
	r.Body = http.MaxBytesReader(w, r.Body, maxUploadSize)

	// Parsear o multipart form
	err := r.ParseMultipartForm(maxUploadSize)
	if err != nil {
		writeError(w, http.StatusBadRequest,
			fmt.Sprintf("Erro ao parsear multipart form: %v", err))
		return
	}
	defer r.MultipartForm.RemoveAll()

	// Extrair o arquivo .dem do campo "demo"
	file, header, err := r.FormFile("demo")
	if err != nil {
		writeError(w, http.StatusBadRequest,
			fmt.Sprintf("Campo 'demo' não encontrado ou inválido: %v", err))
		return
	}
	defer file.Close()

	log.Printf("Recebido arquivo: %s (tamanho: %d bytes)", header.Filename, header.Size)

	// ─── Parsear a demo ──────────────────────────────────────────────
	startTime := time.Now()

	result, err := h.parser.Parse(file)
	if err != nil {
		writeError(w, http.StatusInternalServerError,
			fmt.Sprintf("Erro ao parsear demo: %v", err))
		return
	}

	duration := time.Since(startTime)
	log.Printf("Demo parseada com sucesso em %s: mapa=%s, rounds=%d",
		duration, result.MapName, result.TotalRounds)

	// ─── Retornar JSON ───────────────────────────────────────────────
	writeJSON(w, http.StatusOK, parseResponse{
		Success:  true,
		Message:  fmt.Sprintf("Demo '%s' parseada com sucesso", header.Filename),
		Duration: duration.String(),
		Data:     result,
	})
}

// HandleHealth é o handler para GET /health (health check).
func (h *ParseHandler) HandleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{
		"status":  "ok",
		"service": "countatic-demo-parser",
	})
}

// ─── Utilitários HTTP ────────────────────────────────────────────────

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)

	encoder := json.NewEncoder(w)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(data); err != nil {
		log.Printf("Erro ao serializar resposta JSON: %v", err)
	}
}

func writeError(w http.ResponseWriter, status int, message string) {
	log.Printf("Erro [%d]: %s", status, message)
	writeJSON(w, status, errorResponse{
		Success: false,
		Error:   message,
	})
}

package handler

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/countatic/demo-parser/internal/parser"
)

func TestHealthEndpoint(t *testing.T) {
	dp := parser.NewDemoParser()
	h := NewParseHandler(dp)

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()

	h.HandleHealth(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("Esperado código HTTP 200, recebido: %d", rec.Code)
	}
}

func TestParseMethodNotAllowed(t *testing.T) {
	dp := parser.NewDemoParser()
	h := NewParseHandler(dp)

	req := httptest.NewRequest(http.MethodGet, "/parse", nil)
	rec := httptest.NewRecorder()

	h.HandleParse(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("Esperado código HTTP 405 para requisições GET em /parse, recebido: %d", rec.Code)
	}
}

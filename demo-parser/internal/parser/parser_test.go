package parser

import (
	"bytes"
	"testing"
)

func TestNewDemoParser(t *testing.T) {
	dp := NewDemoParser()
	if dp == nil {
		t.Fatal("Esperado instância de DemoParser não nula")
	}
}

func TestParseEmptyReader(t *testing.T) {
	dp := NewDemoParser()
	emptyReader := bytes.NewReader([]byte{})

	_, err := dp.Parse(emptyReader)
	if err == nil {
		t.Error("Esperado erro ao tentar parsear stream vazia")
	}
}

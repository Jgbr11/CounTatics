# Roadmap do CounTatic

Atualizado em 2026-08-20.

---

## ⏳ Antes de tudo: o único prazo real

**Re-parsear as 3 partidas até ~22/08/2026.** As demos saem do CDN da Valve
cerca de duas semanas depois da partida; depois disso o dado não volta de jeito
nenhum. Nenhum item deste roadmap é mais urgente, porque todos os outros
continuam possíveis amanhã e este não.

O passo a passo está em `ACOES-PENDENTES-DO-USUARIO.md`, item 4 — **uma partida
por vez**, conferindo a primeira antes de disparar a segunda.

As demais pendências operacionais do mesmo documento (rotação das credenciais,
limpeza dos `0.0` gravados) não têm prazo, mas continuam abertas.

---

## Estado atual

Cinco telas, 213 testes, e o pipeline completo de análise:

| Camada | O que existe |
|---|---|
| Parser (Go) | Kills, dano, granadas, disparos, posições X/Y/Z, ângulos de mira, lados |
| Métricas | 4 Strategies — Aim, Utility, Impacto, Posicionamento |
| Comparação | Percentil por faixa de rank, com guarda de amostra mínima |
| Histórico | Série temporal por métrica, médias das últimas N partidas |
| Telas | Partida, painel do jogador, lista de partidas, 404/500 |
| Extras | Títulos da partida, recorte CT/TR, coaching por gravidade, recordes pessoais, armas, desempenho por mapa, destaques de rodada |

**Pendente de commit:** os destaques de rodada (item 4).

---

## O que falta, e em que ordem

A ordem abaixo não é a de valor puro — é a que evita retrabalho. Itens que
mudam contrato de dado vêm antes dos que consomem esse contrato.

### ✅ 1 a 4 — concluídos

| # | Item | Onde ficou |
|---|---|---|
| 1 | Recordes Pessoais | `PersonalRecordService` — recorde por mapa, calculado na leitura, com mínimo de histórico |
| 2 | Estatísticas por arma | `WeaponStatsService` — kills, HS% e acerto por disparo, no painel do jogador selecionado |
| 3 | Desempenho por mapa | `PlayerDashboardService.agregarPorMapa` — V-D absoluto, na mesma janela das médias |
| 4 | Highlights da Rodada | `RoundHighlightService` — pontuação por round, três destaques, piso para não inventar destaque |

A decisão que estava em aberto no item 4 foi tomada: **a unidade é a kill**, e
um 1v3 vencido vale **quatro kills** além das próprias kills do clutch. Os pesos
estão em constantes no serviço, para ajuste depois de ver partidas reais.

Junto com o item 4, a regra de clutch saiu de dentro da Strategy de Impacto para
`analysis/ClutchDetector` — dois leitores precisavam dela, e o destaque precisa
do "contra quantos" que a métrica agregada descarta.

### 5. Login com Steam · custo médio-alto · **destrava as Metas**

Hoje o acesso é por token secreto na URL. Funciona, mas não é "minha conta", e
é o que ainda separa o sistema de uma plataforma.

- Steam usa **OpenID 2.0**, não OAuth — mais simples do que parece
- Resolve identidade, multi-usuário de verdade e a pergunta "de quem é isso"
- O `publicToken` continua útil para compartilhar sem expor o perfil

### 6. Metas · custo alto · **depende do login**

Definir alvo ("HS% para 50% este mês") e acompanhar.

Depende de identidade porque uma meta pertence a alguém. Sem login, seria uma
tabela solta apontando para um `Player` que qualquer um pode consultar.

### 7. Filtros no painel · custo baixo · agora desbloqueado

Recortar por período e por mapa. Multiplica o valor de tudo que já existe, e o
backend já sabe recortar por lado.

### 8. Comparação com o time · custo médio · sem dependência

Os 10 jogadores de cada partida estão no banco. Responde "fui o melhor ou o
pior do meu time nisso?" — que é a comparação que o jogador realmente faz.

---

## O que foi descartado, e por quê

**Heatmap com radar do mapa** — exigiria extrair as imagens do CS2 e as
constantes de conversão de coordenada por mapa. Decisão de não seguir esse
caminho. A Strategy de Posicionamento entrega a mesma pergunta respondida
("onde e quando você morre") sem asset externo.

**Exportar imagem da partida** — implementado e removido: o resultado não
ficou bom.

**Economia (eco vs full buy)** — o parser não extrai dinheiro nem equipamento.
Exigiria mexer no Go antes de qualquer coisa no Java.

**Qualidade de smoke e lineup** — depende de geometria do mapa, mesmo bloqueio
do heatmap.

**Comparação com profissionais** — exigiria uma base externa de demos.

---

## Regras que o código já segue

Valem para qualquer implementação nova — foram aprendidas resolvendo bugs
reais neste projeto:

**Ausente não é zero.** Métrica sem denominador é omitida, não zerada. Publicar
`0.0` sem medição envenenou os percentis uma vez e deu percentil 100 para todo
mundo. Nas regras de título, o mesmo erro reapareceu do lado oposto — condições
de teto disparavam para quem não tinha a métrica.

**Cor nunca é a única portadora.** Verde e vermelho é o par que o daltonismo
mais comum não separa, então todo indicador colorido carrega junto um sinal de
forma.

**Direção antes de comparar.** Toda métrica declara se maior é melhor. Sem
isso, o percentil premia quem mais morre.

**Amostra mínima antes de afirmar.** Taxa vinda de dois duelos não é
estatística. O baseline exige 30 desempenhos; as faixas de distância exigem 3
duelos.

**Filtrar a entrada, não o algoritmo.** O recorte CT/TR não mudou nenhuma
Strategy — montou uma visão da partida com os rounds daquele lado. Vale para
qualquer recorte futuro (por mapa, por período).

**Sem framework de frontend.** As telas são HTML autocontido gerado em Java,
sem build, sem CORS, um container só. Os componentes vivem em `HudComponents`
e o vocabulário visual em `HudTheme`.

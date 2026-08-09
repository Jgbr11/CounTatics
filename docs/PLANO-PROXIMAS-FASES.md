# CounTatic — Plano das próximas fases

> **Status: Bloco A implementado em 2026-08-09.** Ver `## Bloco A` abaixo — as
> seções seguem como registro do que foi feito. Blocos B, C e D pendentes.
>
> Mudança de escopo aprovada durante a implementação: em vez de várias
> mensagens longas no chat, o bot envia **uma mensagem curta com link** para
> uma página web de detalhes (`/m/{token}`). Resolve o rate limit da Steam de
> forma definitiva e cabe muito mais informação do que num chat.

> Situação em 2026-08-08: o pipeline funciona ponta a ponta e foi validado com
> dados reais (partida `CSGO-Pf6Xz-...`, `de_inferno`, 21 rounds). O que segue
> trata de **confiabilidade, gatilho instantâneo e qualidade das métricas**.

---

## Onde estamos

| Componente | Estado |
|---|---|
| Conexão com o Game Coordinator | ✅ funcionando (`gcReady: true`) |
| Relatório básico no chat da Steam | ✅ K/D/A, HS%, MVPs, placar orientado, nomes reais |
| Download + descompactação da demo | ✅ streaming pra disco, SHA-256 em passada única |
| Parsing da demo (`demoinfocs v5.2.0`) | ✅ `de_inferno`, tickRate 64, 3854 eventos |
| Persistência + Strategies | ✅ 20 métricas, 10 jogadores |
| Eventos de dano e granadas | ✅ saíram do zero: DAMAGE 684, MOLOTOV 93, HE 87, SMOKE 68 |
| Gatilho | ⚠️ polling de 5 min (não instantâneo) |
| Recuperação de falhas | ❌ **partida falhada é perdida para sempre** |
| Qualidade do crosshair placement | ❌ fórmula ainda é o placeholder de ±5° |
| Visibilidade fora do chat | ❌ nenhum endpoint de leitura |

---

## Bloco A — Confiabilidade ✅ IMPLEMENTADO

### Resultado da implementação (2026-08-09)

| Item | Estado |
|---|---|
| `MatchFetchJob` + enums + repository | ✅ tabela criada, constraint única ativa |
| `MatchFetchJobService` (enqueue idempotente, backoff) | ✅ |
| `MatchDiscoveryScheduler` + `MatchJobWorker` | ✅ job commitado **antes** do ponteiro avançar |
| `fetch-now` assíncrono | ✅ 202 em ~4,7 s (antes travava minutos na thread HTTP) |
| Notificação única com link | ✅ substitui as 2-3 mensagens longas; elimina o rate limit |
| `Match.publicToken` (UUID) | ✅ |
| Página `/m/{token}` | ✅ scoreboard + métricas + dicas |
| API de leitura + upload manual | ✅ |
| Flyway | ⏸️ adiado — `ddl-auto: update` criou a tabela e a constraint única corretamente por ser tabela nova; introduzir Flyway agora exigiria baseline do schema existente com risco de travar a subida |

### 🔴 Bug crítico encontrado na verificação: estatísticas do jogador errado

Ao conferir os números do Game Coordinator contra a demo, descobriu-se que
**o relatório básico atribuía as estatísticas ao jogador errado**.

Exemplo real (partida `CSGO-Pf6Xz-...`, `de_inferno`):

| | GC dizia | Realidade (demo) |
|---|---|---|
| JGBR11 | 18/17 | **13/15** |
| FRANCESCO XANTARES | 13/16 | **18/17** |

Os dois estavam trocados, e havia ainda um ciclo entre outros quatro jogadores.
Só 4 dos 10 estavam corretos — e por coincidência.

**Causa:** os arrays `kills`/`deaths`/... são indexados por
`reservation.account_ids` **da mesma entrada** de `roundstatsall`. O código lia
`account_ids` da *primeira* entrada com reserva e as estatísticas da *última*.
Quando a ordem dos jogadores diferia entre as duas, tudo saía trocado.

**Correção:** ler a reserva da mesma entrada que fornece as estatísticas;
retroceder só se ela não tiver reserva, e nesse caso registrar aviso de
alinhamento não garantido. Resultado: **10/10 alinhados**.

> Este bug **não** afetou a página web nem o relatório profundo — esses usam os
> dados da demo, que sempre estiveram corretos. Afetou apenas as mensagens de
> relatório básico enviadas antes desta correção.

**Efeito colateral da investigação:** o mesmo teste validou o fix de fronteira
de round no parser Go. Com ele, a demo passou a dar 13/**16** para o
solicitante — exatamente o que o GC corrigido reporta. Antes dava 13/15.

**Validação em produção local:** durante o teste apareceu um bug real
(`could not initialize proxy - no Session`: o `Match` chegava LAZY e detached
ao worker). O sistema **capturou, registrou em `lastError`, reagendou e, após a
correção, o job se recuperou sozinho e notificou**. Antes essa mesma falha
apagaria a partida em definitivo. Corrigido com `LEFT JOIN FETCH j.match` na
query do worker + recarga por id no `notifyPlayer`.

### Por que era a prioridade

Não é hipótese: **aconteceu durante a implementação**. Na primeira execução do
pipeline completo, o envio ao parser falhou com
`NoClassDefFoundError: reactive-streams`. Como o `latestShareCode` já tinha sido
avançado e commitado *antes* do processamento, a partida foi perdida — precisei
rebobinar o share code manualmente via SQL para recuperá-la.

Hoje, qualquer uma destas situações apaga uma partida permanentemente:
- o Steam Bot estar reiniciando na hora do ciclo
- o GC não responder dentro dos 15 s
- o CDN da Valve devolver erro transitório
- o parser Go ficar sem memória ou dar timeout
- o MySQL recusar o insert

### A.1 — Entidade `MatchFetchJob`

**Novo:** `core-backend/src/main/java/com/countatic/core/entity/MatchFetchJob.java`

```
id, steamId64, shareCode (nullable), matchId (FK nullable),
status  : AWAITING_SHARECODE | PENDING | GC_DONE | DEMO_DONE
        | NOTIFIED | FAILED | ABANDONED | DEMO_EXPIRED
source  : POLL | GSI | MANUAL
attempts, lastError, nextAttemptAt,
demoUrl, matchTimeUnix,
preliminarySentAt, notifiedBasicAt, notifiedDeepAt,
gsiMapName, gsiScoreSelf, gsiScoreEnemy, gsiStatsJson,
createdAt, updatedAt

UNIQUE (steamId64, shareCode)
```

Uma tabela cumprindo quatro papéis: marcador de falha, fila de retry, chave de
deduplicação entre GSI e polling, e read model para o endpoint de status.

**Novo:** `repository/MatchFetchJobRepository.java`
`findTop50ByStatusInAndNextAttemptAtBefore(...)`, `findBySteamId64AndShareCode(...)`

### A.2 — Dividir o scheduler em dois

**Modificar:** `service/ValveDemoFetcherScheduler.java` → `MatchDiscoveryScheduler` + `MatchJobWorker`

**Discovery** (a cada 5 min), por jogador elegível:
1. `fetchNextMatchShareCode(...)`
2. `enqueueIfAbsent(steamId, code, POLL)` em transação `REQUIRES_NEW`
3. **só depois do job commitado**, avança `player.latestShareCode`

> O ponteiro continua avançando de propósito. `GetNextMatchSharingCode` é uma
> lista encadeada por `knowncode`: não avançar significa nunca descobrir a
> partida N+2. O job durável é o que torna o avanço seguro.

**Worker** (a cada 30 s), sobre `status IN (PENDING, FAILED, GC_DONE)` e
`nextAttemptAt <= now`:
- backoff `1m → 3m → 10m → 30m → 2h → 6h`, teto de 6 tentativas
- `GcUnavailableException` → `FAILED` + reagenda
- `null` do GC (404) → `ABANDONED` (terminal, partida fora da retenção)
- `DemoExpiredException` → `DEMO_EXPIRED` (terminal, sem retry)
- esgotou tentativas → `ABANDONED` + mensagem final explicando a causa

### A.3 — `fetch-now` assíncrono

**Problema observado:** durante o teste, o `Invoke-RestMethod` caiu com
"conexão fechada de modo inesperado" — o `fetch-now` roda o pipeline inteiro
(download 45 s + parse 40 s + persistência) na thread HTTP.

**Modificar:** `controller/PlayerAuthController.java` — devolver **202 Accepted**
com o `jobId` e deixar o worker executar.

### A.4 — Endpoints de leitura

**Novo:** `controller/MatchController.java`

| Endpoint | Para quê |
|---|---|
| `GET /api/players/{steamId}/jobs` | superfície de debug de todo o Bloco A |
| `GET /api/matches?steamId=` | histórico de partidas |
| `GET /api/matches/{id}` | métricas completas de uma partida |
| `POST /api/matches/upload` | **enviar um `.dem` manualmente** |

O upload manual vale por si: permite validar mudanças no parser em segundos, sem
esperar você jogar nem depender do CDN da Valve.

### A.5 — Flyway

`ddl-auto: update` cria a tabela nova mas **não reconcilia o índice único** e
nunca remove nada. Introduzir Flyway agora, enquanto o delta é pequeno:
`V1__baseline.sql` (schema atual) + `V2__match_fetch_jobs.sql`.

**Esforço estimado do Bloco A:** ~8 arquivos novos, 4 modificados.

---

## Bloco B — Gatilho instantâneo (GSI)

> **Depende do Bloco A**: a chave `UNIQUE (steamId64, shareCode)` é o árbitro
> entre o GSI e o polling. Sem ela, os dois disparam e você recebe relatório
> duplicado.

### A restrição que define o desenho

**O GSI não fornece share code, match id nem demo URL.** Ele entrega apenas
`map.phase`, o placar dos times e `player.match_stats` (kills/assists/deaths/
mvps/score do jogador local). Não há contorno.

Duas consequências:

1. **O ganho instantâneo é o `player.match_stats`, não o gatilho.** No
   `phase: "gameover"` já dá para montar um relatório do seu desempenho em ~2 s,
   sem GC, sem Valve API, sem CDN. Esse é o destaque real da fase.
2. **O share code só é publicado minutos depois.** O gatilho agenda uma
   *sondagem com backoff* (45 s → 2 m → 5 m → 10 m → 20 m → 40 m), não uma
   chamada única.

### Receptor: core-backend, não o bot

`POST /api/gsi` no core-backend porque ele tem o MySQL (idempotência que
sobrevive a restart), a entidade `Player` e o pipeline. O bot não tem banco e só
faria proxy — e jogar uma torrente de 2 Hz no event loop dele arrisca justamente
a sessão de GC que acabamos de destravar.

### Arquivos

| Arquivo | Conteúdo |
|---|---|
| `docs/gamestate_integration_countatic.cfg` | vai em `...\Counter-Strike Global Offensive\game\csgo\cfg\`. **CS2 precisa ser reiniciado.** |
| `dto/gsi/GsiPayloadDTO.java` | `@JsonIgnoreProperties(ignoreUnknown = true)` em **toda** classe aninhada — o payload muda por modo de jogo e versão do CS2 |
| `controller/GsiController.java` | valida token com `MessageDigest.isEqual`; token vazio → recusa tudo. Responde 200 imediatamente (o CS2 desiste em 5 s) |
| `service/GsiEventService.java` | age **só na borda** `live\|warmup\|intermission → gameover`. O `gameover` se repete a cada 2 s; só a transição dispara |

### Três mensagens por partida (proposital)

**preliminar** (GSI, ~2 s, só você) → **básica** (GC, scoreboard + demoUrl) →
**profunda** (demo, métricas avançadas).

Se preferir menos, a flag `countatic.notify.suppress-basic-after-preliminary`
funde as duas primeiras. **Vale considerar seriamente:** a Steam já aplicou
`RateLimitExceeded` com apenas duas mensagens em sequência — três aumenta o
risco, mesmo com o backoff que implementei.

### Riscos

- Depende da porta 8080 publicada no host. Está hoje; se mudar, o GSI para em
  silêncio, sem erro em lugar nenhum.
- Firewall do Windows pode pedir permissão no primeiro bind.
- `map.phase` varia entre Premier/Wingman/casual. Por isso o polling continua
  como rede de segurança — é exatamente o que justifica o desenho híbrido.

---

## Bloco C — Qualidade das métricas (independente, alto valor)

Este é o bloco que separa o CounTatic de um placar comum.

### C.1 — Crosshair placement de verdade

**O problema:** `AimStatStrategy.calculateCrosshairPlacement` usa um limiar
absoluto de ±5° de pitch que **ignora completamente a posição do inimigo**. Ele
mede "o jogador estava mirando perto do horizonte", não crosshair placement. O
próprio código admite (`TODO: Refinar esta fórmula`).

**A dificuldade:** o cálculo correto precisa da posição do inimigo no instante
do disparo — e o evento `WEAPON_FIRE` carrega só o atirador.

**Solução:** calcular no **Go**, que tem o game state completo. Em Java é
impossível.

```
No handler de WeaponFire (parser.go):
  1. varrer p.GameState().Participants() por inimigos vivos
  2. escolher o mais próximo dentro de um cone de ~30° da direção de visão
  3. emitir victimPositionX/Y/Z com a cabeça dele (Z + 64)

No Java (AimStatStrategy):
  erro angular = ângulo entre o vetor de visão e o vetor até a cabeça
  score = mediana dos erros (menor = melhor)
```

### C.2 — `flashEfficiency` acima de 100%

Hoje o numerador conta *cegamentos* e o denominador conta *flashes*: uma flash
que cega 3 inimigos dá 300%. Separar em duas métricas honestas:
- `flashEfficiency` = flashes que cegaram ≥1 inimigo ÷ flashes lançadas
  (correlacionar `FLASH_BLINDED` com `FLASH_THROWN` por proximidade de tick, ±1 s)
- `enemyBlindsPerFlash` = a razão atual, que legitimamente passa de 1

### C.3 — Métricas novas agora possíveis

O parser já emite os eventos; falta só calcular. **Nova:**
`strategy/impl/ImpactStatStrategy.java`

| Métrica | Como |
|---|---|
| **ADR** | soma de `DAMAGE.damageAmount` ÷ rounds — a métrica mais pedida e hoje ausente |
| **Trade kills** | KILL em até 5 s (convertido via `match.tickRate`) após a morte de um aliado |
| **Opening duels** | primeira KILL do round: taxa de vitória e impacto no resultado |
| **Clutches** | último vivo do seu lado no momento de uma KILL que decide o round |
| **Utility damage/round** | já calculável — os eventos DAMAGE com armas `hegrenade`/`molotov`/`incgrenade` existem |

Reaproveita `MatchEventRepository.findByMatchIdAndEventType`, que hoje é código
morto, e o Strategy Pattern já montado (basta um `@Component` novo).

### C.4 — MVP não está sendo emitido

Registrei o handler de `RoundMVPAnnouncement`, mas o banco tem **0 eventos MVP**.
Investigar se o CS2 emite esse evento em demos de Premier. Baixa prioridade: o
número de MVPs já vem do Game Coordinator.

---

## Bloco D — Testes ✅ IMPLEMENTADO

### Cobertura entregue (2026-08-09)

**69 testes**, todos passando: 35 Java, 27 TypeScript, 7 Go.

| Suíte | Testes | O que protege |
|---|---|---|
| `steam-bot/src/steam/matchInfoParser.test.ts` | 14 | **O bug de alinhamento de índices** (stats do jogador errado), orientação do placar, URL da demo, `matchtime` ≠ duração |
| `steam-bot/src/routes/notify.test.ts` | 13 | **A armadilha `isReady`** (200 com o GC conectado), 503-vs-404-vs-504 no `/match-info` |
| `core-backend/.../MatchFetchJobServiceTest` | 9 | Idempotência, corrida GSI×polling, backoff crescente, estados terminais |
| `core-backend/.../MatchDiscoverySchedulerTest` | 8 | **Ordem job-antes-do-ponteiro**, auto-desabilitar em 403, transitório não desabilita |
| `demo-parser/.../weapon_test.go` | 7 | **Contrato Go↔Java dos ids de arma** — sem fixture, sem demo |
| Suítes pré-existentes | 18 | Strategies, share code decoder, controller |

**Prova de que a regressão principal é pega:** o fix de alinhamento foi
temporariamente revertido e a suíte falhou em 2 testes; restaurado, voltou a
14/14. Um teste de regressão que não falha quando o bug volta não vale nada.

### Refactor necessário para testar

`parseMatchInfo` era um método privado de `SteamClientManager`, que instancia
sessão da Steam — impossível de testar isoladamente. Extraído para
`src/steam/matchInfoParser.ts` como **funções puras** (sem rede, sem estado),
com os contratos em `src/steam/types.ts`. O `client.ts` passou a delegar.

### Bug encontrado pelos testes

Um teste pré-existente (`TestParseEmptyReader`) revelou que
`demoinfocs.NewParser` **entra em panic** com entrada inválida na v5 — na v4
devolvia erro. Não havia `recover()` em lugar nenhum do serviço Go: **uma única
demo corrompida derrubaria o processo inteiro**, junto com qualquer outra demo
sendo parseada em paralelo. Corrigido com `defer recover()` em `Parse()`,
convertendo panic em erro HTTP.

### D.1 — Testes de maior valor por linha (referência original)

| Teste | Por que este |
|---|---|
| **Go — `weaponToID`** | tabela cobrindo cada `common.Eq*`. Sem fixture, pega a quebra de contrato Go↔Java que zerava o dano de utilitária |
| **TS — `/notify` com GC conectado** | a regressão da armadilha. Se voltar, todo envio quebra silenciosamente |
| **Java — ordem no scheduler** | job criado *antes* do ponteiro avançar; `ValveAuthException` desabilita o jogador |
| **Java — corrida GSI vs polling** | `enqueueIfAbsent` concorrente deve gerar exatamente 1 linha |
| **Go — `parser_test.go`** | com um `.dem` pequeno em `testdata/`: `DAMAGE > 0`, `tickRate == 64`, ticks monotônicos |

Hoje `steam-bot/src/routes/notify.test.ts` é um stub que não faz nenhuma
requisição HTTP — precisa ser reescrito com supertest, não só ampliado.

### D.2 — Pendências operacionais

- ⚠️ **Rotacionar a Steam Web API Key.** A antiga está no histórico do git desde
  o commit `6742558`; tirar do working tree não resolve. **Ainda pendente.**
- A tabela `players` ganha ~10 linhas por partida analisada (todos os jogadores
  da partida, criados pelo upsert). São inertes (`autoFetchEnabled = false`),
  mas crescem sem limite. Decidir se vale purgar quem nunca se cadastrou.
- Adicionar CS2 na biblioteca da conta do bot — não era o bloqueio, mas elimina
  uma variável e é grátis.

---

## Restrições que não mudam

1. **Demos expiram no CDN da Valve em ~2 semanas.** Partida mais antiga → 404, e
   só o relatório básico do GC é possível. Consequência prática: o
   `skip-to-latest` que você rodou abriu mão da análise profunda de tudo que
   pulou.
2. **O GC também tem janela de retenção.** Partida antiga devolve `matchList`
   vazio — indistinguível de "share code errado" no protocolo. Por isso 404 é
   tratado como terminal.
3. **O GC do CS2 não informa o mapa.** `game_mapgroup`, `game_map` e `game_type`
   chegam todos `null` (verificado). O mapa só sai do parsing da demo.
4. **CT/TR por jogador não é recuperável do GC** — os lados trocam no intervalo.
   Por isso reportamos Time A/Time B orientado por quem pediu.
5. **Rate limit do GC e do chat da Steam é real e não documentado.** Rajadas
   somem em silêncio e parecem timeout.

---

## Ordem recomendada

```
A (confiabilidade)  ──►  B (GSI)
                    │
C (métricas) ───────┘  independente de A e B, pode ir em paralelo

D (testes) — contínuo, junto de cada bloco
```

**Minha recomendação: começar pelo Bloco A.** Sem ele, toda partida que falhar
por um motivo transitório é perdida em definitivo — e isso já aconteceu uma vez
durante a implementação. O Bloco C é o que mais agrega ao produto, mas rende
mais depois que a base de dados for confiável.

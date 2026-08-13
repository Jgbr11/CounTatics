# Correções de Métricas + Gatilho Instantâneo (GSI) — Plano de Implementação

> **Para trabalhadores agênticos:** SUB-SKILL OBRIGATÓRIA: use `superpowers:subagent-driven-development` (recomendado) ou `superpowers:executing-plans` para implementar este plano tarefa a tarefa. Os passos usam checkbox (`- [ ]`) para acompanhamento.

**Goal:** Corrigir o `crosshairPlacementScore` zerado que está envenenando os percentis exibidos na página de partida, recuperar as três partidas já analisadas re-parseando as demos ainda vivas no CDN da Valve, destravar a suíte de testes do Steam Bot, tirar o `.env` do controle de versão — e então fechar o Bloco B, ligando o gatilho instantâneo por Game State Integration à máquina de estados que já existe.

**Architecture:** Parte 1 são correções pontuais em código já escrito e testado; nenhuma delas introduz componente novo, e a de maior alcance (o re-parse) reaproveita integralmente o caminho `GC_DONE → DEMO_DONE → NOTIFIED` do `MatchJobWorker` em vez de duplicar download e parsing. Parte 2 acrescenta só a *porta de entrada* do GSI — DTO, controller e serviço de borda — porque toda a tubulação a jusante (`AWAITING_SHARECODE`, `FetchSource.GSI`, as colunas `gsi*`, `enqueueAwaitingShareCode` e `probeForShareCode` com backoff de 45 s→40 min) já foi construída e testada num trabalho anterior.

**Tech Stack:** Java 21 / Spring Boot 3.3.2 / Hibernate 6.5.2 / MySQL 8.4 / JUnit 5 + AssertJ + Mockito · Node.js 24 / TypeScript / Express / `node:test` via `tsx` · Go 1.24 / `demoinfocs-golang v5.2.0` · Docker Compose.

## Global Constraints

- **Idioma do código:** comentários, Javadoc, mensagens de log e nomes de métodos privados em **português**, seguindo o padrão já estabelecido no projeto. Nomes de API pública (endpoints, campos JSON, colunas) permanecem como já estão.
- **Comentários explicam o *porquê*, não o *quê*.** O código existente segue essa regra com rigor; mantenha-a. Comentário que narra a linha seguinte é ruído.
- **Toda métrica ausente é `null`, nunca `0.0`.** Zero é um valor legítimo de desempenho; usá-lo para dizer "não medi" corrompe médias e percentis. Esta é literalmente a causa do bug da Tarefa 1.
- **Nenhum segredo em arquivo versionado.** Credenciais vêm de variável de ambiente, com `.env.example` documentando as chaves sem valores.
- **`@JsonIgnoreProperties(ignoreUnknown = true)` em toda classe que mapeia JSON externo.** O payload do GSI muda entre modos de jogo e versões do CS2; um campo novo não pode derrubar o endpoint.
- **Nunca afirmar entrega sem entrega.** Se o envio ao chat da Steam falhar, o job vai para `FAILED` e é reagendado — jamais para `NOTIFIED`.
- **Comandos de verificação** (rodar da raiz do repositório, `CounTatics/`):
  - Java: `docker run --rm -v "${PWD}/core-backend:/app" -v "${HOME}/.m2:/root/.m2" -w /app maven:3.9.6-eclipse-temurin-21-alpine mvn -o test`
  - Go: `docker run --rm -v "${PWD}/demo-parser:/app" -w /app golang:1.24-alpine go test ./...`
  - TypeScript: `cd steam-bot && npm test`
  - Subir a stack: `docker compose up -d --build`
- **Baseline de referência (verificado em 2026-08-11):** Java 67/67 ✅ · Go 7/7 ✅ · TypeScript 14/15 ❌ (`notify.test.ts` não executa — é a Tarefa 3).

---

## Contexto: por que este plano existe

O `docs/PLANO-PROXIMAS-FASES.md` está desatualizado. Ele descreve os Blocos B e C como pendentes, mas os commits `f31ccec`, `a626916` e `473f198` (9/ago) entregaram o Bloco C quase inteiro — crosshair placement real, `flashEfficiency` corrigida, `ImpactStatStrategy` com ADR/trades/opening duels/clutches — além de um trabalho não planejado: captura de CS Rating, `RankTier`, `PlayerMatchStats` e o `BaselineService` de percentis por faixa.

O que a verificação contra o banco em produção local revelou:

| Achado | Evidência |
|---|---|
| **`crosshairPlacementScore` = 0.00 nas 30 linhas de `player_match_stats`** | Os 7.579 eventos `WEAPON_FIRE` têm `victim_positionx` NULL: as 3 partidas foram parseadas **antes** do parser passar a anexar a cabeça do inimigo |
| **Todo jogador vê "Crosshair placement: 0.0 — entre os melhores do Azul"** | `BaselineService.compararMetrica` calcula `SUM(coluna <= :valor)`; com 30 amostras todas em 0.0, `0 <= 0` é verdadeiro para todas → percentil 100 para todo mundo |
| **`recomputePlayerStats` não resolve** | O enriquecimento acontece no parser Go, não no Java. Os eventos persistidos não têm a posição do alvo; recalcular sobre eles devolve 0.0 de novo |
| **`npm test` falha em checkout limpo** | `src/config/index.ts` chama `process.exit(1)` no carregamento do módulo; `notify.test.ts` o importa transitivamente via `client.ts` e o `tsx --test` não carrega `.env`. Os 13 testes de `/notify` nunca rodam |
| **`.env` continua rastreado pelo git** | Está no `.gitignore` mas foi adicionado antes da regra; `git ls-files` o lista. A chave da Steam Web API segue sendo commitada a cada alteração. 73 arquivos `target/*.class` também estão rastreados |
| **Bloco B parado exatamente na porta de entrada** | `MatchFetchStatus.AWAITING_SHARECODE`, `FetchSource.GSI`, as colunas `gsi*`/`preliminary_sent_at`, `MatchFetchJobService.enqueueAwaitingShareCode` e `MatchJobWorker.probeForShareCode` existem e estão testados. Faltam DTO, controller, serviço de borda e o `.cfg` |

**Janela de tempo:** as demos das 3 partidas são de 8/ago e o CDN da Valve as retém ~2 semanas. As URLs continuam gravadas em `match_fetch_jobs.demo_url`. **A Tarefa 2 precisa rodar antes de ~22/ago** ou o crosshair real dessas partidas se perde em definitivo.

**Fora do escopo deste plano** (dívida conhecida, decidida para depois): Flyway (A.5 — hoje `ddl-auto: update`), evento MVP não emitido (C.4 — 0 linhas no banco), e a poda dos ~24 jogadores inertes criados pelo upsert.

---

## Estrutura de arquivos

**Parte 1 — Correções**

| Arquivo | Responsabilidade | Ação |
|---|---|---|
| `core-backend/.../strategy/impl/AimStatStrategy.java` | Só publica `crosshairPlacementScore` quando há disparo avaliável | Modificar (linhas 84–97) |
| `core-backend/.../strategy/AimStatStrategyTest.java` | Trava a regressão do 0.0 | Modificar |
| `core-backend/.../repository/MatchFetchJobRepository.java` | Localizar o job por partida | Modificar |
| `core-backend/.../service/MatchReprocessService.java` | Descarta a análise antiga e rearma o job para o worker refazê-la | **Criar** |
| `core-backend/.../service/MatchReprocessServiceTest.java` | Ordem de exclusão e preservação do CS Rating | **Criar** |
| `core-backend/.../controller/MatchController.java` | Endpoint `POST /api/matches/{id}/reparse` | Modificar (após a linha 75) |
| `steam-bot/src/config/index.ts` | Configuração preguiçosa e testável | Modificar |
| `steam-bot/src/steam/client.ts` | Passa a usar `getConfig()` | Modificar (8 pontos) |
| `steam-bot/src/index.ts` | Traduz falha de configuração em saída limpa | Modificar |

**Parte 2 — Bloco B (GSI)**

| Arquivo | Responsabilidade | Ação |
|---|---|---|
| `core-backend/.../dto/gsi/GsiPayloadDTO.java` | Contrato do payload do CS2, tolerante a campos novos | **Criar** |
| `core-backend/.../controller/GsiController.java` | Recebe, valida o token, responde rápido | **Criar** |
| `core-backend/.../service/GsiEventService.java` | Detecta a **borda** de fim de partida e enfileira a sondagem | **Criar** |
| `core-backend/.../repository/MatchFetchJobRepository.java` | Guarda de idempotência por estado | Modificar |
| `core-backend/.../CoreBackendApplication.java` | `@EnableAsync` para o envio preliminar não segurar a resposta | Modificar |
| `core-backend/src/main/resources/application.yml` | `countatic.gsi.token` | Modificar |
| `docker-compose.yml` | Repassa `GSI_TOKEN` ao core-backend | Modificar |
| `.env.example` | Documenta `GSI_TOKEN` | Modificar |
| `docs/gamestate_integration_countatic.cfg` | Arquivo que o CS2 lê | **Criar** |
| `core-backend/.../service/GsiEventServiceTest.java` | Borda, idempotência, orientação do placar | **Criar** |
| `core-backend/.../controller/GsiControllerTest.java` | Token inválido, token vazio, caminho feliz | **Criar** |

---

# PARTE 1 — Correções

## Task 1: `crosshairPlacementScore` ausente em vez de zero

Esta é a correção que para o sangramento. Enquanto ela não existir, toda partida nova analisada num servidor sem os dados de alvo grava mais um 0.0 na base de comparação.

**Files:**
- Modify: `core-backend/src/main/java/com/countatic/core/strategy/impl/AimStatStrategy.java:84-97`
- Test: `core-backend/src/test/java/com/countatic/core/strategy/AimStatStrategyTest.java`

**Interfaces:**
- Consumes: nada de tarefas anteriores.
- Produces: `PlayerStatResult.getMetrics()` passa a **não conter a chave** `"crosshairPlacementScore"` quando nenhum disparo é avaliável. `MatchAnalysisService.persistirDesempenhos` já usa `m.get("crosshairPlacementScore")`, que devolve `null` para chave ausente e grava NULL na coluna — nenhuma mudança lá. Do lado da leitura, `BaselineService.comparar` já pula entradas com valor nulo (`if (info == null || entrada.getValue() == null) continue;`) e `BaselineService.compararMetrica` já filtra `WHERE <coluna> IS NOT NULL` na consulta de percentil: ambos passam a se comportar corretamente sem alteração.

- [ ] **Step 1: Escrever o teste que falha**

Acrescente ao final de `AimStatStrategyTest`, antes da chave de fechamento da classe:

```java
    @Test
    @DisplayName("Não publica crosshairPlacementScore quando nenhum disparo tem alvo — "
            + "0.0 entraria na base de comparação como se fosse desempenho real")
    void naoPublicaCrosshairSemDisparoAvaliavel() {
        Round round = Round.builder().id(1L).roundNumber(1).build();

        // Disparo com ângulo de visão, mas sem a posição do inimigo: é
        // exatamente o formato que o parser Go produzia antes do commit 473f198,
        // e é o que está gravado nas partidas já analisadas.
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.WEAPON_FIRE)
                .actor(testPlayer)
                .tick(100)
                .actorPositionX(0.0).actorPositionY(0.0).actorPositionZ(64.0)
                .viewAngleX(0.0).viewAngleY(0.0)
                .build());

        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics())
                .doesNotContainKey("crosshairPlacementScore")
                .doesNotContainKey("medianCrosshairErrorDegrees")
                .doesNotContainKey("evaluatedShots");
    }

    @Test
    @DisplayName("Publica crosshairPlacementScore quando o disparo tem a cabeça do inimigo anexada")
    void publicaCrosshairComDisparoAvaliavel() {
        Round round = Round.builder().id(1L).roundNumber(1).build();

        // Mira exatamente sobre a cabeça: yaw 0 aponta para +X na convenção
        // Source, e o alvo está 100 unidades à frente, na mesma altura dos olhos.
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.WEAPON_FIRE)
                .actor(testPlayer)
                .tick(100)
                .actorPositionX(0.0).actorPositionY(0.0).actorPositionZ(64.0)
                .victimPositionX(100.0).victimPositionY(0.0).victimPositionZ(64.0)
                .viewAngleX(0.0).viewAngleY(0.0)
                .build());

        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics().get("crosshairPlacementScore")).isEqualTo(100.0);
        assertThat(result.getMetrics().get("evaluatedShots")).isEqualTo(1.0);
        assertThat(result.getMetrics().get("medianCrosshairErrorDegrees")).isEqualTo(0.0);
    }
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```
docker run --rm -v "${PWD}/core-backend:/app" -v "${HOME}/.m2:/root/.m2" -w /app \
  maven:3.9.6-eclipse-temurin-21-alpine mvn -o test -Dtest=AimStatStrategyTest
```

Esperado: `naoPublicaCrosshairSemDisparoAvaliavel` FALHA com
`Expecting map: ... not to contain key: "crosshairPlacementScore"`.
`publicaCrosshairComDisparoAvaliavel` já deve PASSAR — a matemática está certa; o defeito é só a publicação incondicional.

- [ ] **Step 3: Implementar**

Em `AimStatStrategy.java`, substitua o bloco das linhas 84–97 por:

```java
        List<MatchEvent> weaponFires = filterByActorAndType(allEvents, playerId, EventType.WEAPON_FIRE);
        List<Double> erros = errosAngulares(weaponFires);

        // Só publica as métricas de mira se houver disparos avaliáveis.
        //
        // Publicar 0.0 quando não há dado não é "conservador": o valor entra em
        // player_match_stats como desempenho real e o BaselineService o compara
        // com os demais. Com uma base inteira em 0.0, a condição `valor <= 0`
        // vale para todos e cada jogador recebe percentil 100 — a métrica passa
        // a afirmar o contrário do que os dados dizem. NULL sai da comparação;
        // 0.0 mente dentro dela.
        if (!erros.isEmpty()) {
            double erroMediano = erroAngularMediano(erros);

            metrics.put("crosshairPlacementScore", round2(calculateCrosshairPlacement(erros)));
            metrics.put("medianCrosshairErrorDegrees", round2(erroMediano));
            metrics.put("evaluatedShots", (double) erros.size());
            insights.put("crosshairPlacementScore",
                    generateCrosshairInsight(calculateCrosshairPlacement(erros), erroMediano));
        }
```

- [ ] **Step 4: Rodar a suíte inteira**

```
docker run --rm -v "${PWD}/core-backend:/app" -v "${HOME}/.m2:/root/.m2" -w /app \
  maven:3.9.6-eclipse-temurin-21-alpine mvn -o test
```

Esperado: `Tests run: 69, Failures: 0, Errors: 0` (67 anteriores + 2 novos), `BUILD SUCCESS`.

- [ ] **Step 5: Limpar as linhas já contaminadas**

As 30 linhas existentes têm `crosshair_placement_score = 0.0` gravado. O código novo não as reescreve sozinho; enquanto estiverem lá, a página continua mentindo.

```bash
docker exec countatic-mysql mysql -ucountatic -pcountatic_dev countatic \
  -e "UPDATE player_match_stats SET crosshair_placement_score = NULL WHERE crosshair_placement_score = 0;"
```

Confirme que a coluna saiu da comparação:

```bash
docker exec countatic-mysql mysql -ucountatic -pcountatic_dev countatic \
  -e "SELECT COUNT(*) total, COUNT(crosshair_placement_score) com_valor FROM player_match_stats;"
```

Esperado: `total 30`, `com_valor 0`.

- [ ] **Step 6: Commit**

```bash
git add core-backend/src/main/java/com/countatic/core/strategy/impl/AimStatStrategy.java \
        core-backend/src/test/java/com/countatic/core/strategy/AimStatStrategyTest.java
git commit -m "fix: crosshair placement ausente vira NULL, nao 0.0

Publicar 0.0 sem disparo avaliavel gravava um desempenho falso em
player_match_stats. Com a base inteira em 0.0, o percentil do
BaselineService dava 100 para todos os jogadores e a pagina de partida
exibia 'entre os melhores' para uma metrica que nunca foi medida."
```

---

## Task 2: Re-parse das partidas do CDN da Valve

**Files:**
- Create: `core-backend/src/main/java/com/countatic/core/service/MatchReprocessService.java`
- Create: `core-backend/src/test/java/com/countatic/core/service/MatchReprocessServiceTest.java`
- Modify: `core-backend/src/main/java/com/countatic/core/repository/MatchFetchJobRepository.java`
- Modify: `core-backend/src/main/java/com/countatic/core/controller/MatchController.java` (inserir após a linha 75)

**Interfaces:**
- Consumes: `MatchFetchJobService.save(MatchFetchJob)`; `PlayerMatchStatsRepository.deleteByMatchId(Long)`; `MatchRepository.delete(Match)`; `MatchFetchStatus.GC_DONE`.
- Produces: `MatchReprocessService.rearmar(Long matchId)` → `Long` (id do job rearmado); lança `IllegalArgumentException` se a partida não existe e `IllegalStateException` se não há job com `demoUrl`.

**Por que não baixar e parsear aqui.** O `MatchJobWorker.analyzeDemo` já faz exatamente isto — baixa de `job.demoUrl`, descomprime em streaming, confere hash, chama o parser Go, persiste e segue para a notificação — com tratamento de `DemoExpiredException`, backoff e limite de tentativas. Duplicar esse caminho num endpoint significaria manter dois downloaders. Este serviço apaga a análise antiga e devolve o job ao estado `GC_DONE`; o worker, no ciclo seguinte (30 s), refaz tudo pelo caminho já testado.

**A ordem de exclusão importa.** `Match` tem `cascade = ALL, orphanRemoval = true` sobre `rounds`, e `Round` sobre `events` — essa parte se resolve sozinha. Mas `player_match_stats.match_id` e `match_fetch_jobs.match_id` são FKs **de fora** da árvore de cascade: apagar a `Match` sem soltar as duas antes estoura violação de integridade referencial.

> **Efeito colateral aceito:** a `Match` nova recebe um `publicToken` novo (gerado no `@PrePersist`), então o link `/m/{token}` já enviado no chat da Steam para aquela partida deixa de funcionar. Em compensação, ao chegar em `DEMO_DONE` o worker envia uma mensagem nova com o link novo. **Rearme uma partida por vez**, esperando o job chegar a `NOTIFIED`, para não disparar três mensagens seguidas — o rate limit do chat da Steam é real e já foi atingido neste projeto com duas mensagens em sequência.

- [ ] **Step 1: Acrescentar a busca do job pela partida**

Em `MatchFetchJobRepository.java`, antes da chave de fechamento da interface:

```java
    /** O job que produziu esta partida — é dele que sai a URL da demo no CDN. */
    Optional<MatchFetchJob> findByMatchId(Long matchId);
```

- [ ] **Step 2: Escrever o teste que falha**

Crie `core-backend/src/test/java/com/countatic/core/service/MatchReprocessServiceTest.java`:

```java
package com.countatic.core.service;

import com.countatic.core.entity.*;
import com.countatic.core.repository.MatchFetchJobRepository;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerMatchStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MatchReprocessServiceTest {

    private MatchRepository matchRepository;
    private MatchFetchJobRepository jobRepository;
    private PlayerMatchStatsRepository statsRepository;
    private MatchReprocessService service;

    private Match match;
    private MatchFetchJob job;

    @BeforeEach
    void setUp() {
        matchRepository = mock(MatchRepository.class);
        jobRepository = mock(MatchFetchJobRepository.class);
        statsRepository = mock(PlayerMatchStatsRepository.class);
        service = new MatchReprocessService(matchRepository, jobRepository, statsRepository);

        match = Match.builder()
                .id(1L)
                .mapName("de_inferno")
                .csRating(10096)
                .rankTier(RankTier.AZUL)
                .build();

        job = MatchFetchJob.builder()
                .id(7L)
                .steamId64("76561199110265389")
                .shareCode("CSGO-Pf6Xz-AXpEG-GdzKr-GyWv6-pdueM")
                .status(MatchFetchStatus.NOTIFIED)
                .demoUrl("http://replay201.valve.net/730/003835751950364704778_0322641992.dem.bz2")
                .csRating(null)
                .match(match)
                .attempts(3)
                .lastError("erro antigo")
                .build();

        when(matchRepository.findById(1L)).thenReturn(Optional.of(match));
        when(jobRepository.findByMatchId(1L)).thenReturn(Optional.of(job));
    }

    @Test
    @DisplayName("Rearma o job em GC_DONE para o worker refazer o download e o parsing")
    void rearmaJobEmGcDone() {
        Long jobId = service.rearmar(1L);

        assertThat(jobId).isEqualTo(7L);
        assertThat(job.getStatus()).isEqualTo(MatchFetchStatus.GC_DONE);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getLastError()).isNull();
        assertThat(job.getNextAttemptAt()).isNotNull();
    }

    @Test
    @DisplayName("Copia o CS Rating da partida para o job — sem isso o re-parse perde a faixa de comparação")
    void copiaCsRatingDaPartidaParaOJob() {
        // Os jobs originais foram criados antes da captura de rating existir:
        // o rating vive na Match (posto ali por um recompute manual). O
        // processDemo do worker lê job.csRating, então ele precisa migrar.
        service.rearmar(1L);

        assertThat(job.getCsRating()).isEqualTo(10096);
    }

    @Test
    @DisplayName("Solta as FKs externas antes de apagar a partida")
    void soltaFksAntesDeApagar() {
        service.rearmar(1L);

        InOrder ordem = inOrder(statsRepository, matchRepository);
        // player_match_stats e match_fetch_jobs apontam para matches de fora da
        // arvore de cascade: apagar a Match antes viola a integridade referencial.
        ordem.verify(statsRepository).deleteByMatchId(1L);
        ordem.verify(matchRepository).delete(match);

        assertThat(job.getMatch()).isNull();
    }

    @Test
    @DisplayName("Recusa quando o job não tem URL de demo — não há de onde re-baixar")
    void recusaSemDemoUrl() {
        job.setDemoUrl(null);

        assertThatThrownBy(() -> service.rearmar(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("URL da demo");

        verify(matchRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Recusa quando a partida não existe")
    void recusaPartidaInexistente() {
        when(matchRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rearmar(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: Rodar o teste e confirmar que falha**

```
docker run --rm -v "${PWD}/core-backend:/app" -v "${HOME}/.m2:/root/.m2" -w /app \
  maven:3.9.6-eclipse-temurin-21-alpine mvn -o test -Dtest=MatchReprocessServiceTest
```

Esperado: FALHA de compilação — `cannot find symbol: class MatchReprocessService`.

- [ ] **Step 4: Implementar o serviço**

Crie `core-backend/src/main/java/com/countatic/core/service/MatchReprocessService.java`:

```java
package com.countatic.core.service;

import com.countatic.core.entity.Match;
import com.countatic.core.entity.MatchFetchJob;
import com.countatic.core.entity.MatchFetchStatus;
import com.countatic.core.repository.MatchFetchJobRepository;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerMatchStatsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Descarta a análise de uma partida e rearma o job para que ela seja refeita
 * do zero, a partir da demo original no CDN da Valve.
 *
 * <p><b>Para que serve.</b> Mudanças no parser Go não alcançam partidas já
 * analisadas: os eventos foram persistidos sem os campos novos, e recalcular
 * sobre eles apenas reproduz o resultado antigo. Foi o que aconteceu com o
 * crosshair placement — o parser passou a anexar a cabeça do inimigo a cada
 * disparo, mas as partidas anteriores continuaram com {@code victimPosition}
 * nulo e score zerado.</p>
 *
 * <p><b>Por que só rearma.</b> Baixar e parsear já é responsabilidade do
 * {@link MatchJobWorker}, com streaming para disco, verificação de hash,
 * tratamento de demo expirada e backoff. Este serviço devolve o job ao estado
 * {@code GC_DONE} e sai do caminho; o worker refaz o resto no ciclo seguinte.</p>
 *
 * <p><b>Janela de oportunidade.</b> A Valve descarta os replays em cerca de
 * duas semanas. Passado esse prazo o download devolve 404, o worker marca
 * {@code DEMO_EXPIRED} e a partida fica com os dados que tiver.</p>
 */
@Slf4j
@Service
public class MatchReprocessService {

    private final MatchRepository matchRepository;
    private final MatchFetchJobRepository jobRepository;
    private final PlayerMatchStatsRepository statsRepository;

    public MatchReprocessService(MatchRepository matchRepository,
                                 MatchFetchJobRepository jobRepository,
                                 PlayerMatchStatsRepository statsRepository) {
        this.matchRepository = matchRepository;
        this.jobRepository = jobRepository;
        this.statsRepository = statsRepository;
    }

    /**
     * Apaga a análise existente e devolve o job a {@code GC_DONE}.
     *
     * @return id do job rearmado
     * @throws IllegalArgumentException se a partida não existe
     * @throws IllegalStateException    se não há job com URL de demo para ela
     */
    @Transactional
    public Long rearmar(Long matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Partida não encontrada: " + matchId));

        MatchFetchJob job = jobRepository.findByMatchId(matchId)
                .orElseThrow(() -> new IllegalStateException(
                        "A partida " + matchId + " não tem job associado — sem job não há "
                                + "URL da demo. Partidas enviadas por upload manual não podem "
                                + "ser re-baixadas."));

        if (job.getDemoUrl() == null || job.getDemoUrl().isBlank()) {
            throw new IllegalStateException(
                    "O job #" + job.getId() + " não guardou a URL da demo; não há de onde re-baixar.");
        }

        // O rating vive na Match quando o job é anterior à captura de CS Rating.
        // O worker o lê de job.getCsRating() ao chamar processDemo: sem esta
        // cópia, o re-parse devolveria a partida sem faixa e fora da base de
        // comparação — trocando um defeito por outro.
        if (job.getCsRating() == null && match.getCsRating() != null) {
            job.setCsRating(match.getCsRating());
        }

        // Solta as duas FKs que apontam para matches de fora da árvore de
        // cascade. A ordem não é estilo: apagar a Match antes viola integridade
        // referencial e a transação inteira é revertida.
        job.setMatch(null);
        jobRepository.saveAndFlush(job);

        statsRepository.deleteByMatchId(matchId);
        matchRepository.delete(match);

        // Rounds e MatchEvents saem por cascade + orphanRemoval declarados em
        // Match e Round; só player_match_stats e match_fetch_jobs ficam de fora.

        job.setStatus(MatchFetchStatus.GC_DONE);
        job.setAttempts(0);
        job.setLastError(null);
        job.setNextAttemptAt(Instant.now());
        jobRepository.save(job);

        log.info("♻️ Partida {} descartada; job #{} rearmado em GC_DONE (share code {}).",
                matchId, job.getId(), job.getShareCode());

        return job.getId();
    }
}
```

- [ ] **Step 5: Rodar o teste e confirmar que passa**

```
docker run --rm -v "${PWD}/core-backend:/app" -v "${HOME}/.m2:/root/.m2" -w /app \
  maven:3.9.6-eclipse-temurin-21-alpine mvn -o test -Dtest=MatchReprocessServiceTest
```

Esperado: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 6: Expor o endpoint**

Em `MatchController.java`, acrescente o campo após a linha 31:

```java
    private final MatchReprocessService matchReprocessService;
```

e o parâmetro ao construtor (linhas 33–41), que passa a ser:

```java
    public MatchController(MatchQueryService matchQueryService,
                           MatchFetchJobService jobService,
                           DemoParserClientService demoParserClientService,
                           MatchAnalysisService matchAnalysisService,
                           MatchReprocessService matchReprocessService) {
        this.matchQueryService = matchQueryService;
        this.jobService = jobService;
        this.demoParserClientService = demoParserClientService;
        this.matchAnalysisService = matchAnalysisService;
        this.matchReprocessService = matchReprocessService;
    }
```

Acrescente também o import:

```java
import com.countatic.core.service.MatchReprocessService;
```

E insira o método após `recompute` (linha 75):

```java
    /**
     * Descarta a análise de uma partida e a refaz a partir da demo no CDN.
     *
     * <p>Diferente de {@code /recompute}, que recalcula sobre os eventos já
     * gravados, este endpoint re-baixa e re-parseia — é o único caminho quando o
     * parser passou a extrair um campo que as partidas antigas não têm.</p>
     *
     * <p>Responde <b>202 Accepted</b>: quem executa é o worker, no ciclo de 30 s.
     * Download e parsing levam poucos minutos; acompanhe por
     * {@code GET /api/players/{steamId}/jobs}.</p>
     *
     * <p><b>A partida recebe um publicToken novo</b>, então o link {@code /m/...}
     * já enviado no chat deixa de responder. O worker manda um link novo ao
     * concluir. Rearme uma partida por vez para não estourar o rate limit do
     * chat da Steam.</p>
     */
    @PostMapping("/matches/{id}/reparse")
    public ResponseEntity<?> reparse(@PathVariable("id") Long id) {
        try {
            Long jobId = matchReprocessService.rearmar(id);
            return ResponseEntity.accepted().body(Map.of(
                    "success", true,
                    "jobId", jobId,
                    "message", "Partida descartada. O worker vai re-baixar e re-parsear "
                            + "em até 30 segundos."
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Falha ao rearmar a partida {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }
```

- [ ] **Step 7: Suíte completa e commit**

```
docker run --rm -v "${PWD}/core-backend:/app" -v "${HOME}/.m2:/root/.m2" -w /app \
  maven:3.9.6-eclipse-temurin-21-alpine mvn -o test
```

Esperado: `Tests run: 74, Failures: 0, Errors: 0` (69 + 5).

```bash
git add core-backend/src/main/java/com/countatic/core/service/MatchReprocessService.java \
        core-backend/src/test/java/com/countatic/core/service/MatchReprocessServiceTest.java \
        core-backend/src/main/java/com/countatic/core/repository/MatchFetchJobRepository.java \
        core-backend/src/main/java/com/countatic/core/controller/MatchController.java
git commit -m "feat: endpoint de re-parse da demo a partir do CDN da Valve

Mudancas no parser Go nao alcancam partidas ja analisadas: os eventos
foram persistidos sem os campos novos. O servico descarta a analise e
devolve o job a GC_DONE, reaproveitando o download e o parsing ja
testados do MatchJobWorker em vez de duplica-los."
```

- [ ] **Step 8: Executar o re-parse nas três partidas — uma de cada vez**

Suba a versão nova e rearme a partida 1:

```powershell
docker compose up -d --build core-backend
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/matches/1/reparse"
```

Acompanhe até `NOTIFIED` (leva alguns minutos: download ~45 s + parsing ~40 s + persistência):

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/players/76561199110265389/jobs" |
  Select-Object id, shareCode, status, attempts, lastError
```

Quando o job 1 chegar a `NOTIFIED`, confirme que o crosshair saiu do zero:

```bash
docker exec countatic-mysql mysql -ucountatic -pcountatic_dev countatic -e \
 "SELECT COUNT(*) wf, SUM(victim_positionx IS NOT NULL) com_alvo FROM match_events WHERE event_type='WEAPON_FIRE';
  SELECT steam_id64, crosshair_placement_score, adr FROM player_match_stats WHERE crosshair_placement_score IS NOT NULL;"
```

Esperado: `com_alvo > 0` e ~10 linhas com `crosshair_placement_score` diferente de NULL e de 0.

**Só então** repita para as partidas 2 e 3. Se `com_alvo` continuar 0, **pare** — significa que a imagem do parser em execução é anterior ao commit `473f198`; rode `docker compose up -d --build parser-service` e rearme de novo.

> Se alguma delas voltar `DEMO_EXPIRED`, a demo saiu do CDN. Não é falha do código: aquela partida fica sem crosshair placement em definitivo, e o `NULL` da Tarefa 1 a mantém honestamente fora da comparação.

---

## Task 3: Destravar a suíte de testes do Steam Bot

**Files:**
- Modify: `steam-bot/src/config/index.ts`
- Modify: `steam-bot/src/steam/client.ts` (linhas 101, 184, 186, 287, 626, 627, 693, 723, 724)
- Modify: `steam-bot/src/index.ts:59`

**Interfaces:**
- Consumes: nada.
- Produces: `getConfig(): BotConfig` substitui o `export const config`. `loadConfig` passa a lançar `Error` em vez de chamar `process.exit(1)`.

**O defeito.** `src/config/index.ts` termina com `export const config = loadConfig();` — efeito colateral no carregamento do módulo. `notify.test.ts` importa `SteamClientStatus` de `../steam/client`, que importa `../config`, que valida `STEAM_USERNAME`/`STEAM_PASSWORD` e mata o processo. Como `tsx --test` não carrega `.env` (só `src/index.ts` chama `dotenv.config()`), o arquivo de teste inteiro morre antes do primeiro `describe`. Os 13 testes de `/notify` — que protegem a armadilha do `isReady` e a distinção 503/404/504 — **não rodam há tempo nenhum**, apesar de contarem no total documentado.

- [ ] **Step 1: Confirmar a falha atual**

```bash
cd steam-bot && npm test
```

Esperado: `pass 14`, `fail 1`, e `✖ src\routes\notify.test.ts` com a mensagem `ERRO: Variáveis de ambiente obrigatórias não definidas!`.

- [ ] **Step 2: Tornar a configuração preguiçosa**

Em `steam-bot/src/config/index.ts`, substitua o corpo de `loadConfig` e a exportação final:

```ts
function loadConfig(): BotConfig {
  const username = process.env.STEAM_USERNAME;
  const password = process.env.STEAM_PASSWORD;

  if (!username || !password) {
    throw new Error(
      "Variáveis de ambiente obrigatórias não definidas: STEAM_USERNAME e STEAM_PASSWORD. " +
      "Use .env.example como referência."
    );
  }

  return {
    steam: {
      username,
      password,
      sharedSecret: process.env.STEAM_SHARED_SECRET || undefined,
    },
    server: {
      port: parseInt(process.env.BOT_PORT || "3000", 10),
    },
    logLevel: process.env.LOG_LEVEL || "info",
  };
}

let cached: BotConfig | null = null;

/**
 * Configuração validada, carregada na primeira chamada.
 *
 * Antes isto era `export const config = loadConfig()`, avaliado no import.
 * Qualquer módulo que alcançasse `config` — inclusive um arquivo de teste que
 * só queria um enum de `client.ts` — derrubava o processo se as credenciais da
 * Steam não estivessem no ambiente. Testar rotas HTTP com dublês não exige
 * credencial nenhuma; a validação precisa acontecer quando o bot vai de fato
 * logar, não quando o módulo é lido.
 */
export function getConfig(): BotConfig {
  if (cached === null) {
    cached = loadConfig();
  }
  return cached;
}

export type { BotConfig };
```

- [ ] **Step 3: Atualizar os pontos de uso**

Em `steam-bot/src/steam/client.ts`, troque o import da linha 7:

```ts
import { getConfig } from "../config";
```

e substitua as nove ocorrências de `config.` por `getConfig().` nas linhas 101, 184, 186, 287, 626, 627, 693, 723 e 724. Exemplo (linha 626–627):

```ts
      accountName: getConfig().steam.username,
      password: getConfig().steam.password,
```

Em `steam-bot/src/index.ts`, troque o import da linha 6 por `import { getConfig } from "./config";` e a linha 59 por:

```ts
  const port = getConfig().server.port;
```

- [ ] **Step 4: Traduzir a falha de configuração em saída limpa**

Ainda em `steam-bot/src/index.ts`, logo após `dotenv.config();` e antes de `main()` ser chamada, valide cedo — a mensagem clara na subida era a virtude do desenho antigo e não deve se perder:

```ts
try {
  getConfig();
} catch (erro) {
  console.error(
    "═══════════════════════════════════════════════════════\n" +
    `  ERRO DE CONFIGURAÇÃO: ${(erro as Error).message}\n` +
    "═══════════════════════════════════════════════════════"
  );
  process.exit(1);
}
```

- [ ] **Step 5: Rodar a suíte inteira**

```bash
cd steam-bot && npm test
```

Esperado: `tests 27`, `pass 27`, `fail 0` — os 14 de `matchInfoParser` mais os **13 de `notify` que agora executam pela primeira vez**.

> Se algum dos 13 testes de `/notify` falhar agora que finalmente roda, **isso é um achado, não um contratempo**: pare e investigue com `superpowers:systematic-debugging` antes de seguir. Um teste que nunca rodou não é evidência de nada.

- [ ] **Step 6: Confirmar que o bot ainda sobe**

```bash
cd .. && docker compose up -d --build steam-bot && sleep 45 && docker logs --tail 20 countatic-steam-bot
```

Esperado: `✅ Steam Client logado com sucesso!` e `🎮 Conectado ao Game Coordinator do CS2!`.

- [ ] **Step 7: Commit**

```bash
git add steam-bot/src/config/index.ts steam-bot/src/steam/client.ts steam-bot/src/index.ts
git commit -m "fix: configuracao preguicosa destrava os 13 testes de /notify

config/index.ts chamava process.exit(1) no carregamento do modulo.
notify.test.ts o importava transitivamente via client.ts e morria antes
do primeiro describe, porque tsx --test nao carrega .env. Os testes que
protegem a armadilha do isReady nunca haviam executado."
```

---

## Task 4: Tirar segredos e artefatos de build do controle de versão

**Files:**
- Modify: índice do git (sem alteração de conteúdo em `.env`)
- Add: `.env.example` (hoje não rastreado)

**O problema.** `.gitignore` tem `.env` e `**/target/`, mas as duas coisas foram adicionadas ao índice **antes** dessas regras, e `.gitignore` não age sobre o que já é rastreado. `git ls-files` confirma: `.env` e 73 arquivos `core-backend/target/*.class`. Cada alteração do `.env` continua commitando a chave da Steam Web API.

- [ ] **Step 1: Confirmar o estado atual**

```bash
git ls-files | grep -E "^\.env$|target/" | head -5
git ls-files | grep -c "target/"
```

Esperado: `.env` listado e `73`.

- [ ] **Step 2: Tirar do índice preservando os arquivos em disco**

`--cached` remove do rastreamento sem apagar nada do disco — o `.env` continua existindo e a stack segue funcionando.

```bash
git rm --cached .env
git rm -r --cached core-backend/target
git add .env.example
git status --short
```

Esperado: `D .env`, muitos `D core-backend/target/...`, `A .env.example`. O arquivo `.env` continua presente em disco (`ls -la .env` confirma).

- [ ] **Step 3: Commit**

```bash
git commit -m "chore: remove .env e target/ do rastreamento do git

Ambos estavam no .gitignore, mas foram adicionados ao indice antes das
regras existirem — e .gitignore nao age sobre o que ja e rastreado. A
chave da Steam Web API vinha sendo commitada a cada alteracao do .env.
Adiciona .env.example documentando as chaves sem valores."
```

- [ ] **Step 4: Rotacionar a chave da Steam Web API — ação manual**

> **Isto é obrigatório e nenhum comando o substitui.** A chave antiga está no histórico do git desde o commit `6742558`. Tirá-la do working tree não a remove de lá; qualquer clone do repositório continua carregando o valor. Rotacionar é o que a invalida.

1. Acesse https://steamcommunity.com/dev/apikey e clique em **Revoke My Steam Web API Key**.
2. Gere uma chave nova na mesma página.
3. Substitua o valor de `STEAM_WEB_API_KEY` no arquivo `.env` da raiz do repositório.
4. Recrie o container para que ele leia o valor novo:

```bash
docker compose up -d --force-recreate core-backend
```

5. Confirme que a Valve voltou a responder (procure `Consultando próxima partida na Valve`, e **não** `HTTP 403`):

```bash
docker logs --tail 30 countatic-core-backend | grep -i "valve"
```

> Reescrever o histórico (`git filter-repo`) só faria sentido se o repositório fosse público ou compartilhado. Com a chave já revogada, o valor no histórico vira inerte — e a reescrita quebraria qualquer clone existente. Se o repositório for publicado um dia, reescreva **antes** de publicar.

---

# PARTE 2 — Bloco B: Gatilho Instantâneo (GSI)

**A restrição que define o desenho.** O Game State Integration **não fornece share code, match id nem URL de demo**. Ele entrega `map.phase`, o placar dos times e `player.match_stats` do jogador local. Não há contorno.

Duas consequências, ambas já refletidas no código que existe:

1. **O ganho instantâneo é o `player.match_stats`, não o gatilho.** No `phase: "gameover"` dá para montar um relatório do seu desempenho em ~2 s, sem GC, sem Valve API, sem CDN.
2. **O share code só é publicado minutos depois.** Por isso o gatilho cria um job em `AWAITING_SHARECODE`, e `MatchJobWorker.probeForShareCode` sonda a Valve com backoff de 45 s → 2 m → 5 m → 10 m → 20 m → 40 m. Esse código **já existe e já está testado**.

O polling de 5 minutos continua ativo como rede de segurança. A constraint `UNIQUE (steamId64, shareCode)` é o árbitro entre as duas origens: quem inserir primeiro vence, e o perdedor vira duplicata abandonada — comportamento já coberto por `MatchFetchJobServiceTest`.

---

## Task 5: DTO do payload do GSI

**Files:**
- Create: `core-backend/src/main/java/com/countatic/core/dto/gsi/GsiPayloadDTO.java`

**Interfaces:**
- Consumes: nada.
- Produces: `GsiPayloadDTO` com os getters Lombok `getProvider()`, `getMap()`, `getPlayer()`, `getAuth()`; classes aninhadas estáticas `Provider` (`getSteamid()`), `MapState` (`getName()`, `getPhase()`, `getMode()`, `getTeamCt()`, `getTeamT()`), `TeamState` (`getScore()`), `PlayerState` (`getSteamid()`, `getName()`, `getTeam()`, `getMatchStats()`), `MatchStats` (`getKills()`, `getAssists()`, `getDeaths()`, `getMvps()`, `getScore()`), `Auth` (`getToken()`).

- [ ] **Step 1: Criar o DTO**

```java
package com.countatic.core.dto.gsi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Payload que o CS2 envia ao endpoint de Game State Integration.
 *
 * <p><b>{@code ignoreUnknown} em toda classe aninhada não é zelo excessivo.</b>
 * O formato varia por modo de jogo — Premier, Wingman e casual trazem blocos
 * diferentes — e cada atualização do CS2 pode acrescentar campos. Sem a
 * anotação, um campo novo faria o Jackson lançar exceção, o endpoint devolveria
 * 400 e o gatilho pararia sem erro visível em lugar nenhum.</p>
 *
 * <p><b>Só mapeamos o que o serviço usa.</b> O payload completo traz também
 * estado de arma, granadas, bomba e o round corrente; ignorá-los é deliberado.</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GsiPayloadDTO {

    private Provider provider;
    private MapState map;
    private PlayerState player;
    private Auth auth;

    /**
     * Identifica a instalação do CS2 que enviou o payload.
     *
     * <p>{@code provider.steamid} é <b>o dono da máquina</b>. Não confunda com
     * {@code player.steamid}, que é quem está sendo observado — ao assistir a
     * um companheiro no fim do round, os dois divergem.</p>
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Provider {
        private String name;
        private Integer appid;
        private String steamid;
        private Long timestamp;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MapState {
        private String name;
        private String mode;

        /** {@code warmup}, {@code live}, {@code intermission}, {@code gameover}. */
        private String phase;

        private Integer round;

        @JsonProperty("team_ct")
        private TeamState teamCt;

        @JsonProperty("team_t")
        private TeamState teamT;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TeamState {
        private Integer score;
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlayerState {
        private String steamid;
        private String name;

        /** {@code CT} ou {@code T}; nulo enquanto o jogador é espectador. */
        private String team;

        @JsonProperty("match_stats")
        private MatchStats matchStats;
    }

    /** Acumulado da partida para o jogador — a razão de ser desta fase. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MatchStats {
        private Integer kills;
        private Integer assists;
        private Integer deaths;
        private Integer mvps;
        private Integer score;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Auth {
        private String token;
    }
}
```

- [ ] **Step 2: Compilar**

```
docker run --rm -v "${PWD}/core-backend:/app" -v "${HOME}/.m2:/root/.m2" -w /app \
  maven:3.9.6-eclipse-temurin-21-alpine mvn -o compile
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add core-backend/src/main/java/com/countatic/core/dto/gsi/GsiPayloadDTO.java
git commit -m "feat: DTO do payload de Game State Integration do CS2"
```

---

## Task 6: Serviço de borda — detectar o fim da partida

**Files:**
- Create: `core-backend/src/main/java/com/countatic/core/service/GsiEventService.java`
- Create: `core-backend/src/test/java/com/countatic/core/service/GsiEventServiceTest.java`
- Modify: `core-backend/src/main/java/com/countatic/core/repository/MatchFetchJobRepository.java`
- Modify: `core-backend/src/main/java/com/countatic/core/CoreBackendApplication.java`

**Interfaces:**
- Consumes: `GsiPayloadDTO` (Tarefa 5); `MatchFetchJobService.enqueueAwaitingShareCode(String steamId64, String mapName, Integer scoreSelf, Integer scoreEnemy, String statsJson)` → `MatchFetchJob`; `PlayerRepository.findBySteamId64(String)` → `Optional<Player>`; `SteamBotClientService.sendSimpleNotification(String, String)` → `boolean`.
- Produces: `GsiEventService.processar(GsiPayloadDTO)` → `void`, idempotente e barato o bastante para rodar dentro do ciclo de resposta HTTP.

**Por que "borda".** O CS2 reenvia o payload a cada ~2 s e o `gameover` persiste por todo o placar final. Agir a cada payload criaria dezenas de jobs por partida. O serviço age **uma única vez**, na transição de `live`/`warmup`/`intermission` para `gameover`.

**Duas guardas, não uma.** O mapa de fases em memória é rápido mas se perde no restart do backend. A guarda que sobrevive é a consulta ao banco: se já existe job `AWAITING_SHARECODE` para aquele jogador, não cria outro. As duas juntas cobrem restart e reenvio.

- [ ] **Step 1: Acrescentar a guarda de idempotência ao repositório**

Em `MatchFetchJobRepository.java`, antes da chave de fechamento:

```java
    /** Já existe sondagem aberta para este jogador? Guarda de idempotência do GSI. */
    boolean existsBySteamId64AndStatusIn(String steamId64, List<MatchFetchStatus> statuses);
```

- [ ] **Step 2: Escrever o teste que falha**

Crie `core-backend/src/test/java/com/countatic/core/service/GsiEventServiceTest.java`:

```java
package com.countatic.core.service;

import com.countatic.core.dto.gsi.GsiPayloadDTO;
import com.countatic.core.entity.MatchFetchJob;
import com.countatic.core.entity.MatchFetchStatus;
import com.countatic.core.entity.Player;
import com.countatic.core.repository.MatchFetchJobRepository;
import com.countatic.core.repository.PlayerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GsiEventServiceTest {

    private static final String STEAM_ID = "76561199110265389";

    private MatchFetchJobService jobService;
    private MatchFetchJobRepository jobRepository;
    private PlayerRepository playerRepository;
    private SteamBotClientService botClient;
    private GsiEventService service;

    @BeforeEach
    void setUp() {
        jobService = mock(MatchFetchJobService.class);
        jobRepository = mock(MatchFetchJobRepository.class);
        playerRepository = mock(PlayerRepository.class);
        botClient = mock(SteamBotClientService.class);

        service = new GsiEventService(jobService, jobRepository, playerRepository,
                botClient, new ObjectMapper());

        Player jogador = Player.builder()
                .id(1L).steamId64(STEAM_ID).displayName("JGBR11")
                .autoFetchEnabled(true)
                .build();
        when(playerRepository.findBySteamId64(STEAM_ID)).thenReturn(Optional.of(jogador));
        when(jobRepository.existsBySteamId64AndStatusIn(eq(STEAM_ID), anyList())).thenReturn(false);
        when(jobService.enqueueAwaitingShareCode(any(), any(), any(), any(), any()))
                .thenReturn(MatchFetchJob.builder().id(1L).steamId64(STEAM_ID).build());
    }

    @Test
    @DisplayName("Enfileira uma única vez na transição live → gameover")
    void enfileiraNaBorda() {
        service.processar(payload("live", "CT", 13, 9));
        verify(jobService, never()).enqueueAwaitingShareCode(any(), any(), any(), any(), any());

        service.processar(payload("gameover", "CT", 13, 9));
        verify(jobService, times(1))
                .enqueueAwaitingShareCode(eq(STEAM_ID), eq("de_mirage"), eq(13), eq(9), anyString());
    }

    @Test
    @DisplayName("Ignora o gameover repetido — o CS2 reenvia o payload a cada 2 s")
    void ignoraGameoverRepetido() {
        service.processar(payload("live", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));

        verify(jobService, times(1)).enqueueAwaitingShareCode(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Orienta o placar pelo lado do jogador: como TR, o próprio placar é o dos T")
    void orientaPlacarPeloLadoDoJogador() {
        service.processar(payload("live", "T", 13, 9));
        service.processar(payload("gameover", "T", 13, 9));

        // team_ct = 13, team_t = 9; jogando de T, o placar próprio é 9 contra 13.
        verify(jobService).enqueueAwaitingShareCode(eq(STEAM_ID), eq("de_mirage"),
                eq(9), eq(13), anyString());
    }

    @Test
    @DisplayName("Não enfileira quando já há sondagem aberta — guarda que sobrevive a restart")
    void naoEnfileiraComSondagemAberta() {
        when(jobRepository.existsBySteamId64AndStatusIn(
                STEAM_ID, List.of(MatchFetchStatus.AWAITING_SHARECODE))).thenReturn(true);

        service.processar(payload("live", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));

        verify(jobService, never()).enqueueAwaitingShareCode(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Ignora jogador não cadastrado")
    void ignoraJogadorDesconhecido() {
        when(playerRepository.findBySteamId64(STEAM_ID)).thenReturn(Optional.empty());

        service.processar(payload("live", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));

        verify(jobService, never()).enqueueAwaitingShareCode(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Não age no primeiro payload já em gameover — não há borda que prove que a partida acabou agora")
    void naoAgeSemFaseAnterior() {
        // O CS2 pode ser aberto com uma partida encerrada na tela. Sem a fase
        // anterior, agir seria adivinhar; o polling de 5 min cobre esse caso.
        service.processar(payload("gameover", "CT", 13, 9));

        verify(jobService, never()).enqueueAwaitingShareCode(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Envia o relatório preliminar com as stats do próprio jogador")
    void enviaRelatorioPreliminar() {
        service.processar(payload("live", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));

        verify(botClient).sendSimpleNotification(eq(STEAM_ID), contains("de_mirage"));
    }

    // ─── Fábrica de payload ───────────────────────────────────────────

    private GsiPayloadDTO payload(String fase, String time, int placarCt, int placarT) {
        GsiPayloadDTO p = new GsiPayloadDTO();

        GsiPayloadDTO.Provider provider = new GsiPayloadDTO.Provider();
        provider.setSteamid(STEAM_ID);
        p.setProvider(provider);

        GsiPayloadDTO.MapState mapa = new GsiPayloadDTO.MapState();
        mapa.setName("de_mirage");
        mapa.setPhase(fase);
        mapa.setMode("premier");

        GsiPayloadDTO.TeamState ct = new GsiPayloadDTO.TeamState();
        ct.setScore(placarCt);
        mapa.setTeamCt(ct);

        GsiPayloadDTO.TeamState t = new GsiPayloadDTO.TeamState();
        t.setScore(placarT);
        mapa.setTeamT(t);

        p.setMap(mapa);

        GsiPayloadDTO.PlayerState jogador = new GsiPayloadDTO.PlayerState();
        jogador.setSteamid(STEAM_ID);
        jogador.setName("JGBR11");
        jogador.setTeam(time);

        GsiPayloadDTO.MatchStats stats = new GsiPayloadDTO.MatchStats();
        stats.setKills(22);
        stats.setAssists(6);
        stats.setDeaths(15);
        stats.setMvps(4);
        stats.setScore(52);
        jogador.setMatchStats(stats);

        p.setPlayer(jogador);
        return p;
    }
}
```

- [ ] **Step 3: Rodar e confirmar que falha**

```
docker run --rm -v "${PWD}/core-backend:/app" -v "${HOME}/.m2:/root/.m2" -w /app \
  maven:3.9.6-eclipse-temurin-21-alpine mvn -o test -Dtest=GsiEventServiceTest
```

Esperado: FALHA de compilação — `cannot find symbol: class GsiEventService`.

- [ ] **Step 4: Implementar o serviço**

Crie `core-backend/src/main/java/com/countatic/core/service/GsiEventService.java`:

```java
package com.countatic.core.service;

import com.countatic.core.dto.gsi.GsiPayloadDTO;
import com.countatic.core.entity.MatchFetchJob;
import com.countatic.core.entity.MatchFetchStatus;
import com.countatic.core.entity.Player;
import com.countatic.core.repository.MatchFetchJobRepository;
import com.countatic.core.repository.PlayerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converte a torrente de payloads do Game State Integration num único evento
 * de "partida terminou".
 *
 * <p><b>Age na borda, não no estado.</b> O CS2 reenvia o payload a cada ~2 s e
 * a fase {@code gameover} dura todo o placar final. Reagir ao estado criaria
 * dezenas de jobs por partida; reagir à transição cria exatamente um.</p>
 *
 * <p><b>O que o GSI dá e o que não dá.</b> Ele entrega o desempenho do jogador
 * no instante em que a partida acaba — daí o relatório preliminar em ~2 s. Ele
 * <b>não</b> entrega share code, match id nem URL de demo: esses só saem da
 * Valve minutos depois. Por isso o job nasce em {@code AWAITING_SHARECODE} e
 * quem o completa é a sondagem com backoff do {@link MatchJobWorker}.</p>
 */
@Slf4j
@Service
public class GsiEventService {

    /** Fases a partir das quais o {@code gameover} representa fim de partida. */
    private static final Set<String> FASES_DE_JOGO = Set.of("live", "warmup", "intermission");

    private static final String FASE_FINAL = "gameover";

    private final MatchFetchJobService jobService;
    private final MatchFetchJobRepository jobRepository;
    private final PlayerRepository playerRepository;
    private final SteamBotClientService botClient;
    private final ObjectMapper objectMapper;

    /**
     * Última fase vista por jogador.
     *
     * <p>Em memória de propósito: é estado de sessão, não de negócio, e um
     * restart no meio de uma partida apenas faz o gatilho perder aquela
     * transição — o polling de 5 min continua sendo a rede de segurança. O que
     * <i>precisa</i> sobreviver ao restart é a idempotência, e essa vem do
     * banco, não daqui.</p>
     */
    private final Map<String, String> ultimaFasePorJogador = new ConcurrentHashMap<>();

    public GsiEventService(MatchFetchJobService jobService,
                           MatchFetchJobRepository jobRepository,
                           PlayerRepository playerRepository,
                           SteamBotClientService botClient,
                           ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.jobRepository = jobRepository;
        this.playerRepository = playerRepository;
        this.botClient = botClient;
        this.objectMapper = objectMapper;
    }

    public void processar(GsiPayloadDTO payload) {
        if (payload == null || payload.getProvider() == null || payload.getMap() == null) {
            return;
        }

        // provider.steamid é o DONO da máquina. player.steamid é quem está
        // sendo observado — ao assistir um companheiro, os dois divergem, e
        // creditar as stats ao observado atribuiria a partida ao jogador errado.
        String steamId = payload.getProvider().getSteamid();
        if (steamId == null || steamId.isBlank()) {
            return;
        }

        String fase = payload.getMap().getPhase();
        String faseAnterior = ultimaFasePorJogador.put(steamId, fase);

        if (!FASE_FINAL.equals(fase) || faseAnterior == null || !FASES_DE_JOGO.contains(faseAnterior)) {
            return;
        }

        log.info("🏁 GSI: fim de partida detectado para {} ({} → {}) no mapa {}.",
                steamId, faseAnterior, fase, payload.getMap().getName());

        Optional<Player> jogador = playerRepository.findBySteamId64(steamId);
        if (jogador.isEmpty() || !Boolean.TRUE.equals(jogador.get().getAutoFetchEnabled())) {
            log.debug("GSI ignorado: {} não está cadastrado com auto-fetch ativo.", steamId);
            return;
        }

        // Guarda que sobrevive a restart: o mapa em memória some, esta não.
        if (jobRepository.existsBySteamId64AndStatusIn(
                steamId, List.of(MatchFetchStatus.AWAITING_SHARECODE))) {
            log.debug("GSI ignorado: já existe sondagem aberta para {}.", steamId);
            return;
        }

        Integer placarProprio = placarDoLado(payload, true);
        Integer placarAdversario = placarDoLado(payload, false);

        MatchFetchJob job = jobService.enqueueAwaitingShareCode(
                steamId,
                payload.getMap().getName(),
                placarProprio,
                placarAdversario,
                serializarStats(payload));

        enviarPreliminar(steamId, payload, placarProprio, placarAdversario);

        log.info("⚡ Job #{} criado pelo GSI para {} — sondando o share code na Valve.",
                job.getId(), steamId);
    }

    // ═══════════════════════════════════════════════════════════════════

    /**
     * Placar orientado pelo lado do jogador.
     *
     * <p>O GSI reporta {@code team_ct} e {@code team_t} em posições fixas. "13 a
     * 9" só significa vitória depois de saber de que lado o jogador terminou.</p>
     */
    private Integer placarDoLado(GsiPayloadDTO payload, boolean proprio) {
        GsiPayloadDTO.MapState mapa = payload.getMap();
        Integer ct = mapa.getTeamCt() == null ? null : mapa.getTeamCt().getScore();
        Integer t = mapa.getTeamT() == null ? null : mapa.getTeamT().getScore();

        String time = payload.getPlayer() == null ? null : payload.getPlayer().getTeam();
        boolean ehCt = "CT".equalsIgnoreCase(time);

        if (proprio) {
            return ehCt ? ct : t;
        }
        return ehCt ? t : ct;
    }

    private String serializarStats(GsiPayloadDTO payload) {
        if (payload.getPlayer() == null || payload.getPlayer().getMatchStats() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload.getPlayer().getMatchStats());
        } catch (Exception e) {
            // Guardar as stats brutas é conveniência de diagnóstico; falhar aqui
            // não pode custar o gatilho, que é o que realmente importa.
            log.warn("Não foi possível serializar as stats do GSI: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Relatório preliminar: o desempenho do jogador segundos após o fim.
     *
     * <p>Assíncrono porque envolve uma chamada HTTP ao Steam Bot, e o CS2
     * desiste da requisição de GSI em 5 s — segurar a resposta esperando a
     * Steam faria o jogo classificar o endpoint como morto.</p>
     */
    @Async
    public void enviarPreliminar(String steamId, GsiPayloadDTO payload,
                                 Integer placarProprio, Integer placarAdversario) {
        GsiPayloadDTO.MatchStats s = payload.getPlayer() == null
                ? null : payload.getPlayer().getMatchStats();
        if (s == null) {
            return;
        }

        String resultado;
        if (placarProprio == null || placarAdversario == null) {
            resultado = "";
        } else if (placarProprio > placarAdversario) {
            resultado = " ✅";
        } else if (placarProprio < placarAdversario) {
            resultado = " ❌";
        } else {
            resultado = " 🤝";
        }

        String mensagem = String.format("""
                ⚡ CounTatic — fim de partida detectado

                📍 %s · %s-%s%s
                🔫 K/A/D: %s/%s/%s · ⭐ %s MVPs · 💀 Score %s

                Estou buscando a demo para a análise completa.
                Te mando o link em alguns minutos. 🚀""",
                payload.getMap().getName(),
                placarProprio, placarAdversario, resultado,
                s.getKills(), s.getAssists(), s.getDeaths(), s.getMvps(), s.getScore());

        try {
            botClient.sendSimpleNotification(steamId, mensagem);
        } catch (Exception e) {
            // O relatório preliminar é um bônus. Perdê-lo não pode derrubar o
            // gatilho: o job já está enfileirado e a análise completa segue.
            log.warn("Falha ao enviar o relatório preliminar para {}: {}", steamId, e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Habilitar `@Async`**

Em `CoreBackendApplication.java`, acrescente a anotação de classe e o import:

```java
import org.springframework.scheduling.annotation.EnableAsync;
```

```java
@EnableAsync
@SpringBootApplication
public class CoreBackendApplication {
```

> `@Async` num método chamado a partir da mesma classe é ignorado pelo proxy do Spring. Aqui `enviarPreliminar` é chamado de `processar`, na mesma instância — **o teste `enviaRelatorioPreliminar` passa exatamente por isso**, já que sem o proxy a chamada é direta e síncrona. Em produção o efeito é uma chamada HTTP de milissegundos dentro do ciclo de resposta; o CS2 tolera até 5 s. Se isso vier a incomodar, mova `enviarPreliminar` para uma classe própria — não faça agora, YAGNI.

- [ ] **Step 6: Rodar e confirmar que passa**

```
docker run --rm -v "${PWD}/core-backend:/app" -v "${HOME}/.m2:/root/.m2" -w /app \
  maven:3.9.6-eclipse-temurin-21-alpine mvn -o test -Dtest=GsiEventServiceTest
```

Esperado: `Tests run: 7, Failures: 0, Errors: 0`.

- [ ] **Step 7: Commit**

```bash
git add core-backend/src/main/java/com/countatic/core/service/GsiEventService.java \
        core-backend/src/test/java/com/countatic/core/service/GsiEventServiceTest.java \
        core-backend/src/main/java/com/countatic/core/repository/MatchFetchJobRepository.java \
        core-backend/src/main/java/com/countatic/core/CoreBackendApplication.java
git commit -m "feat: servico de borda do GSI detecta fim de partida

Age na transicao live|warmup|intermission -> gameover, nao no estado: o
CS2 reenvia o payload a cada 2s e gameover dura todo o placar final.
Duas guardas — mapa de fases em memoria e consulta por job
AWAITING_SHARECODE aberto — cobrem reenvio e restart do backend."
```

---

## Task 7: Endpoint `POST /api/gsi`

**Files:**
- Create: `core-backend/src/main/java/com/countatic/core/controller/GsiController.java`
- Create: `core-backend/src/test/java/com/countatic/core/controller/GsiControllerTest.java`
- Modify: `core-backend/src/main/resources/application.yml` (no bloco `countatic:`, após `worker-interval-ms`)
- Modify: `docker-compose.yml` (ambiente do `core-backend`, após `DEMO_TEMP_DIR`)
- Modify: `.env.example`

**Interfaces:**
- Consumes: `GsiEventService.processar(GsiPayloadDTO)` (Tarefa 6).
- Produces: `POST /api/gsi` → `200 OK` com token válido; `403 Forbidden` com token ausente, errado, ou quando `countatic.gsi.token` não está configurado.

**Por que o receptor é o core-backend e não o bot.** O core tem o MySQL (idempotência que sobrevive a restart), a entidade `Player` e o pipeline. O bot não tem banco e só faria proxy — e jogar uma torrente de 2 Hz no event loop dele arrisca justamente a sessão de Game Coordinator que sustenta todo o resto.

- [ ] **Step 1: Escrever o teste que falha**

Crie `core-backend/src/test/java/com/countatic/core/controller/GsiControllerTest.java`:

```java
package com.countatic.core.controller;

import com.countatic.core.dto.gsi.GsiPayloadDTO;
import com.countatic.core.service.GsiEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GsiControllerTest {

    private final GsiEventService service = mock(GsiEventService.class);

    @Test
    @DisplayName("Aceita o payload quando o token confere")
    void aceitaTokenCorreto() {
        GsiController controller = new GsiController(service, "segredo-correto");

        var resposta = controller.receber(payloadComToken("segredo-correto"));

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        verify(service).processar(any());
    }

    @Test
    @DisplayName("Recusa token errado sem tocar no pipeline")
    void recusaTokenErrado() {
        GsiController controller = new GsiController(service, "segredo-correto");

        var resposta = controller.receber(payloadComToken("chute"));

        assertThat(resposta.getStatusCode().value()).isEqualTo(403);
        verify(service, never()).processar(any());
    }

    @Test
    @DisplayName("Recusa payload sem bloco de auth")
    void recusaSemAuth() {
        GsiController controller = new GsiController(service, "segredo-correto");

        var resposta = controller.receber(new GsiPayloadDTO());

        assertThat(resposta.getStatusCode().value()).isEqualTo(403);
        verify(service, never()).processar(any());
    }

    @Test
    @DisplayName("Token não configurado recusa tudo — endpoint aberto na porta 8080 seria "
            + "convite para qualquer um enfileirar jobs")
    void tokenVazioRecusaTudo() {
        GsiController controller = new GsiController(service, "");

        assertThat(controller.receber(payloadComToken("")).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.receber(payloadComToken("qualquer")).getStatusCode().value()).isEqualTo(403);
        verify(service, never()).processar(any());
    }

    private GsiPayloadDTO payloadComToken(String token) {
        GsiPayloadDTO p = new GsiPayloadDTO();
        GsiPayloadDTO.Auth auth = new GsiPayloadDTO.Auth();
        auth.setToken(token);
        p.setAuth(auth);
        return p;
    }
}
```

- [ ] **Step 2: Rodar e confirmar que falha**

```
docker run --rm -v "${PWD}/core-backend:/app" -v "${HOME}/.m2:/root/.m2" -w /app \
  maven:3.9.6-eclipse-temurin-21-alpine mvn -o test -Dtest=GsiControllerTest
```

Esperado: FALHA de compilação — `cannot find symbol: class GsiController`.

- [ ] **Step 3: Implementar o controller**

```java
package com.countatic.core.controller;

import com.countatic.core.dto.gsi.GsiPayloadDTO;
import com.countatic.core.service.GsiEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Recebe o Game State Integration do CS2.
 *
 * <p>Este endpoint fica exposto na porta 8080 do host — é a única forma de o
 * jogo alcançá-lo. O token compartilhado é o que separa "o meu CS2" de
 * "qualquer processo na máquina": sem ele, enfileirar jobs falsos custaria uma
 * requisição HTTP.</p>
 *
 * <p>Responde imediatamente. O CS2 abandona a requisição em 5 s e passa a
 * considerar o endpoint morto; o trabalho pesado é do worker.</p>
 */
@Slf4j
@RestController
public class GsiController {

    private final GsiEventService gsiEventService;
    private final String tokenEsperado;

    public GsiController(GsiEventService gsiEventService,
                         @Value("${countatic.gsi.token:}") String tokenEsperado) {
        this.gsiEventService = gsiEventService;
        this.tokenEsperado = tokenEsperado == null ? "" : tokenEsperado;

        if (this.tokenEsperado.isBlank()) {
            log.warn("⚠️ countatic.gsi.token não configurado — POST /api/gsi vai recusar tudo. "
                    + "Defina GSI_TOKEN no .env e use o mesmo valor no arquivo "
                    + "gamestate_integration_countatic.cfg.");
        }
    }

    @PostMapping("/api/gsi")
    public ResponseEntity<Void> receber(@RequestBody GsiPayloadDTO payload) {
        if (!tokenConfere(payload)) {
            return ResponseEntity.status(403).build();
        }

        try {
            gsiEventService.processar(payload);
        } catch (Exception e) {
            // 200 mesmo em falha: o CS2 não sabe reagir a erro, e devolver 500
            // só o faria desistir do endpoint. O erro fica no log, onde é útil.
            log.error("Falha ao processar payload do GSI: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Comparação em tempo constante.
     *
     * <p>{@code equals} de String encerra no primeiro byte divergente, o que
     * transforma o tempo de resposta em oráculo para descobrir o token byte a
     * byte. {@code MessageDigest.isEqual} percorre o comprimento todo.</p>
     */
    private boolean tokenConfere(GsiPayloadDTO payload) {
        if (tokenEsperado.isBlank()) {
            return false;
        }
        if (payload == null || payload.getAuth() == null || payload.getAuth().getToken() == null) {
            return false;
        }
        return MessageDigest.isEqual(
                tokenEsperado.getBytes(StandardCharsets.UTF_8),
                payload.getAuth().getToken().getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 4: Configurar o token**

Em `application.yml`, dentro do bloco `countatic:`, após `worker-interval-ms: 30000`:

```yaml
  # Token compartilhado com o gamestate_integration_countatic.cfg do CS2.
  # Vazio recusa todo payload — endpoint aberto na 8080 aceitaria qualquer um.
  gsi:
    token: ${GSI_TOKEN:}
```

Em `docker-compose.yml`, no `environment:` do `core-backend`, após `DEMO_TEMP_DIR: /tmp/demos`:

```yaml
      GSI_TOKEN: ${GSI_TOKEN:-}
```

Em `.env.example`, ao final:

```bash
# ─── Game State Integration (gatilho instantâneo) ──────
# Segredo compartilhado entre o CS2 e o core-backend. Gere um valor
# aleatório e use O MESMO em docs/gamestate_integration_countatic.cfg.
#   PowerShell:  [guid]::NewGuid().ToString()
# Vazio faz POST /api/gsi recusar tudo.
GSI_TOKEN=
```

E acrescente a mesma linha, com um valor real, ao `.env` da raiz (que não é versionado).

- [ ] **Step 5: Suíte completa e commit**

```
docker run --rm -v "${PWD}/core-backend:/app" -v "${HOME}/.m2:/root/.m2" -w /app \
  maven:3.9.6-eclipse-temurin-21-alpine mvn -o test
```

Esperado: `Tests run: 85, Failures: 0, Errors: 0` (74 + 7 do GsiEventService + 4 do GsiController).

```bash
git add core-backend/src/main/java/com/countatic/core/controller/GsiController.java \
        core-backend/src/test/java/com/countatic/core/controller/GsiControllerTest.java \
        core-backend/src/main/resources/application.yml docker-compose.yml .env.example
git commit -m "feat: endpoint POST /api/gsi com token compartilhado

Compara o token em tempo constante e recusa tudo quando nao configurado.
Responde sempre rapido — o CS2 abandona a requisicao em 5s e passa a
tratar o endpoint como morto."
```

---

## Task 8: Configuração do CS2 e verificação ponta a ponta

**Files:**
- Create: `docs/gamestate_integration_countatic.cfg`
- Modify: `docs/PLANO-PROXIMAS-FASES.md`

- [ ] **Step 1: Criar o arquivo de configuração**

Crie `docs/gamestate_integration_countatic.cfg`:

```
"CounTatic"
{
    "uri"       "http://127.0.0.1:8080/api/gsi"
    "timeout"   "5.0"
    "buffer"    "0.1"
    "throttle"  "0.5"
    "heartbeat" "60.0"

    "auth"
    {
        "token" "TROQUE-PELO-VALOR-DE-GSI_TOKEN-DO-SEU-.env"
    }

    "data"
    {
        "provider"            "1"
        "map"                 "1"
        "round"               "1"
        "player_id"           "1"
        "player_state"        "1"
        "player_match_stats"  "1"
    }
}
```

- [ ] **Step 2: Instalar no CS2 — ação manual**

1. Copie o arquivo para:
   `...\Steam\steamapps\common\Counter-Strike Global Offensive\game\csgo\cfg\`
2. Abra a cópia e substitua `TROQUE-PELO-VALOR-DE-GSI_TOKEN-DO-SEU-.env` pelo mesmo valor de `GSI_TOKEN` que está no `.env`.
3. **Reinicie o CS2 por inteiro.** A configuração de GSI só é lida na inicialização; `disconnect` e `connect` não bastam.
4. Se o Firewall do Windows pedir permissão no primeiro bind da 8080, autorize.

- [ ] **Step 3: Subir a stack e verificar o token**

```bash
docker compose up -d --build core-backend
```

Confirme que o aviso de token ausente **não** aparece:

```bash
docker logs countatic-core-backend 2>&1 | grep -i "gsi.token"
```

Esperado: nenhuma saída. Se aparecer `⚠️ countatic.gsi.token não configurado`, o `GSI_TOKEN` não chegou ao container — confira o `.env` da raiz e recrie com `--force-recreate`.

- [ ] **Step 4: Provar que a autenticação funciona, sem depender do jogo**

```powershell
# Token errado → 403
Invoke-WebRequest -Method Post -Uri "http://localhost:8080/api/gsi" `
  -ContentType "application/json" `
  -Body '{"auth":{"token":"errado"}}' -SkipHttpErrorCheck |
  Select-Object -ExpandProperty StatusCode

# Token correto → 200 (troque SEU-TOKEN pelo valor do .env)
Invoke-WebRequest -Method Post -Uri "http://localhost:8080/api/gsi" `
  -ContentType "application/json" `
  -Body '{"auth":{"token":"SEU-TOKEN"},"provider":{"steamid":"76561199110265389"},"map":{"name":"de_mirage","phase":"live"}}' |
  Select-Object -ExpandProperty StatusCode
```

Esperado: `403` e depois `200`.

- [ ] **Step 5: Provar a detecção de borda com dois payloads sintéticos**

Envie `live` e em seguida `gameover` para o seu próprio SteamID (o cadastrado com auto-fetch):

```powershell
$token = "SEU-TOKEN"
$sid = "76561199110265389"

foreach ($fase in @("live", "gameover")) {
  $corpo = @{
    auth = @{ token = $token }
    provider = @{ steamid = $sid }
    map = @{ name = "de_mirage"; phase = $fase; mode = "premier";
             team_ct = @{ score = 13 }; team_t = @{ score = 9 } }
    player = @{ steamid = $sid; name = "JGBR11"; team = "CT";
                match_stats = @{ kills = 22; assists = 6; deaths = 15; mvps = 4; score = 52 } }
  } | ConvertTo-Json -Depth 6

  Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/gsi" `
    -ContentType "application/json" -Body $corpo
}
```

Verifique os três efeitos:

```bash
docker logs --tail 40 countatic-core-backend | grep -E "GSI|AWAITING"
```

Esperado: `🏁 GSI: fim de partida detectado`, `📋 Job #N (GSI) criado`, `⚡ Job #N criado pelo GSI`.

```bash
docker exec countatic-mysql mysql -ucountatic -pcountatic_dev countatic -e \
 "SELECT id, status, source, gsi_map_name, gsi_score_self, gsi_score_enemy, preliminary_sent_at
    FROM match_fetch_jobs WHERE source='GSI' ORDER BY id DESC LIMIT 3;"
```

Esperado: uma linha com `AWAITING_SHARECODE`, `GSI`, `de_mirage`, `13`, `9`.

E a mensagem preliminar deve ter chegado no seu chat da Steam em segundos.

Reenvie o `gameover` mais duas vezes e confirme que **nenhum** job novo aparece — é a guarda de idempotência funcionando.

> Depois deste teste, o job sintético vai sondar a Valve por um share code que não existe e, após seis tentativas (~40 min), ir para `ABANDONED` com "A Valve não publicou o share code desta partida a tempo". É o comportamento correto. Se preferir limpá-lo antes:
> `docker exec countatic-mysql mysql -ucountatic -pcountatic_dev countatic -e "DELETE FROM match_fetch_jobs WHERE source='GSI' AND status='AWAITING_SHARECODE';"`

- [ ] **Step 6: Verificação real — jogar uma partida**

Com o CS2 reiniciado e o `.cfg` instalado, jogue uma partida completa. Ao final, esperado nesta ordem:

1. **~2 s após a tela de placar final:** mensagem preliminar no chat da Steam com K/A/D, MVPs e o placar orientado.
2. **Alguns minutos depois:** o worker encontra o share code, o job vira `PENDING` → `GC_DONE` → `DEMO_DONE`.
3. **Ao concluir:** segunda mensagem com o link `/m/{token}`.
4. Na página: `crosshairPlacementScore` **com valor real** (não NULL, não 0), graças às Tarefas 1 e 2.

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/players/76561199110265389/jobs" |
  Select-Object id, shareCode, status, source, attempts, lastError | Format-Table
```

- [ ] **Step 7: Atualizar a documentação de estado**

Em `docs/PLANO-PROXIMAS-FASES.md`, substitua o bloco de status do topo (linhas 1–13) por:

```markdown
# CounTatic — Plano das próximas fases

> **Status em 2026-08-11.** Blocos A, B, C e D implementados.
>
> - **A — Confiabilidade:** fila durável de jobs, worker com backoff, `fetch-now`
>   assíncrono, página `/m/{token}`. Flyway (A.5) segue adiado.
> - **B — Gatilho instantâneo (GSI):** `POST /api/gsi` com token compartilhado,
>   detecção de borda `live|warmup|intermission → gameover`, relatório
>   preliminar em ~2 s e sondagem do share code com backoff.
> - **C — Qualidade das métricas:** crosshair placement real (posição do alvo
>   vinda do parser Go), `flashEfficiency` limitada a 100%, e `ImpactStatStrategy`
>   com ADR, trades, opening duels e clutches. **C.4 (evento MVP) segue aberto.**
>   Extra não planejado: CS Rating → `RankTier` → `PlayerMatchStats` →
>   `BaselineService`, com percentis por faixa na página de partida.
> - **D — Testes:** 85 Java, 27 TypeScript, 7 Go.
>
> **Pendências conhecidas:** Flyway (A.5) e o evento MVP não emitido (C.4).
```

Marque C.1, C.2 e C.3 como implementados nas seções correspondentes e registre em C.4 que o parser **emite** `RoundMVPAnnouncement` mas o banco segue com zero linhas `MVP` — a investigação continua aberta.

- [ ] **Step 8: Commit final**

```bash
git add docs/gamestate_integration_countatic.cfg docs/PLANO-PROXIMAS-FASES.md
git commit -m "docs: cfg do Game State Integration e status atualizado

Bloco B fechado. O documento descrevia B e C como pendentes desde antes
dos commits f31ccec/a626916/473f198, que entregaram o Bloco C quase
inteiro mais o CS Rating e a base de comparacao por faixa."
```

---

## Verificação final

Rode as três suítes e confirme os números:

```bash
docker run --rm -v "${PWD}/core-backend:/app" -v "${HOME}/.m2:/root/.m2" -w /app \
  maven:3.9.6-eclipse-temurin-21-alpine mvn -o test
docker run --rm -v "${PWD}/demo-parser:/app" -w /app golang:1.24-alpine go test ./...
cd steam-bot && npm test && cd ..
```

| Suíte | Antes | Depois |
|---|---|---|
| Java | 67 ✅ | **85** ✅ |
| Go | 7 ✅ | 7 ✅ |
| TypeScript | 14 ✅ / 1 ❌ | **27** ✅ |

E confirme o estado dos dados:

```bash
docker exec countatic-mysql mysql -ucountatic -pcountatic_dev countatic -e \
 "SELECT COUNT(*) linhas,
         COUNT(crosshair_placement_score) com_crosshair,
         SUM(crosshair_placement_score = 0) zerados
    FROM player_match_stats;"
```

Esperado: `com_crosshair` maior que zero e **`zerados` igual a 0** — nenhum desempenho voltando a afirmar 0.0 como se fosse medição.

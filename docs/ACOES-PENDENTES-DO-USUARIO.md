# Ações pendentes — só você pode executar

Gerado em 2026-08-12, ao fim da branch `fix/metricas-e-gsi`
(14 commits, `473f198..82a10ad`).

Estas etapas ficaram deliberadamente fora da automação: mexem em credenciais
reais, no MySQL de produção, disparam mensagens na Steam ou dependem do CS2
instalado. A decisão de executá-las é sua.

A ordem importa. Os itens 1 e 4 têm consequência real se atrasarem.

---

## 1. 🔴 Rotacionar credenciais vazadas — **faça primeiro**

Três credenciais estiveram em arquivos versionados. **Nenhum commit desta
branch as invalida** — elas continuam no histórico do git, acessíveis a
qualquer `git clone`. Só a rotação na origem resolve.

Em ordem de gravidade:

### 1.1 Senha da conta Steam do bot (`countatic_bot_01`)

Estava em claro em `steam-bot/.env.example`, rastreada desde `10f958b`.
Senha de conta é tomada de conta completa: quem a tem loga, troca o e-mail e
leva os itens.

→ Troque a senha na Steam. Depois atualize `steam-bot/.env` e recrie o container:

```powershell
docker compose up -d --force-recreate steam-bot
```

### 1.2 Refresh token da sessão Steam (`steam-bot/refreshToken.txt`)

Rastreado desde `90c2f98`. Um refresh token da Steam autentica **sem senha e
sem 2FA** — por isso vem depois da senha, mas antes de tudo o mais.

→ A troca de senha do item 1.1 deve derrubar a sessão. **Confirme que derrubou,
não presuma.** Se o bot voltar a pedir código por e-mail no próximo start, é
sinal de que a sessão antiga caiu — que é o resultado desejado.

### 1.3 Steam Web API Key

Estava no `.env` da raiz, rastreado desde `6742558`.

→ Gere uma nova em <https://steamcommunity.com/dev/apikey>, coloque no `.env`
da raiz e recrie o backend:

```powershell
docker compose up -d --force-recreate core-backend
```

### 1.4 Game Authentication Code — **confirme se era real**

`docs/SETUP-BOT-ACCOUNT.md` trazia `8TD6-UWWHY-F7MJ` emparelhado com o seu
SteamID64 e o seu nick reais, enquanto o `initialShareCode` do mesmo bloco era
placeholder. Não dá para distinguir por leitura se aquele valor era o seu de
verdade. Ele já foi substituído por um exemplo sintético no repositório.

Esse código, somado ao SteamID64 (que é público), dá acesso ao seu histórico de
partidas pela API da Valve.

→ Se aquele era o seu código real, gere um novo em
`CS2 → Configurações → Game → Game Authentication Code` e recadastre com
`POST /api/players/auth`. Se era fictício, ignore este item.

---

## 2. Adicionar o `GSI_TOKEN` ao `.env` da raiz

O gatilho instantâneo não funciona sem ele. Token vazio faz `POST /api/gsi`
recusar tudo — de propósito, para não aceitar payload de qualquer processo da
máquina.

```powershell
[guid]::NewGuid().ToString()   # gera um valor
```

Coloque em `GSI_TOKEN=` no `.env` da raiz. **O mesmo valor** vai no `.cfg` do
item 5.

---

## 3. Limpar os `0.0` fabricados em `player_match_stats`

As correções de métrica só evitam contaminação **nova**. As linhas já gravadas
continuam distorcendo os percentis.

⚠️ **Antes de rodar qualquer coisa**, confira os nomes reais das colunas — este
ponto **não foi verificado** (a consulta ao banco foi bloqueada pela política de
permissões da sessão que gerou este documento). Numa investigação anterior deste
projeto, `victim_position_x` falhou e o nome real era `victim_positionx`:

```sql
SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = 'countatic' AND TABLE_NAME = 'match_events'
   AND COLUMN_NAME LIKE '%position%';
```

Os comandos completos, com a condição `WHERE` correta por coluna e a
justificativa de cada uma, estão em:

`.superpowers/sdd/2026-08-11-correcoes-metricas-e-gatilho-gsi/task-9-report.md`,
seção 6.

Resumo do que é seguro e do que não é:

| Coluna | Limpável por SQL? |
|---|---|
| `adr`, `kills_per_round`, `deaths_per_round`, `utility_damage_per_round` | Sim — `WHERE rounds_played = 0` |
| `headshot_percentage` | Sim — `WHERE = 0 AND kills = 0` |
| `kd_ratio` | Sim — `WHERE = 0 AND kills = 0 AND deaths = 0` |
| `flash_efficiency` | **Não por valor** — `WHERE = 0` apagaria quem lançou flashes e não cegou ninguém, que é desempenho real. Precisa de join em `match_events`, com a consulta de pré-requisito da seção 6.3 |
| `crosshair_placement_score` | **Não por valor** — mesma situação; corte temporal ou join em `match_events` |

Rode primeiro a consulta de contagem da seção 6.1: ela mostra quantas linhas
cada condição pegaria, **sem alterar nada**.

---

## 4. ⏳ Re-parsear as 3 partidas — **prazo real: ~22 de agosto de 2026**

As demos saem do CDN da Valve cerca de 2 semanas depois da partida. Depois
disso o dado não volta de jeito nenhum.

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/matches/1/reparse" -Method Post
```

⚠️ **Uma partida por vez.** O endpoint apaga a partida, seus rounds, seus
eventos e as estatísticas antes de rearmar o job — é irreversível se a demo já
tiver expirado. Ele também dispara mensagem na Steam ao concluir.

Depois da primeira, confirme que a análise voltou com dado real (por exemplo,
que `crosshair_placement_score` não veio nulo por falta de disparo com alvo)
**antes** de disparar a segunda.

Nota: a ordem de liberação da FK e o delete foram provados contra mocks e
auditados no código, mas nunca executados contra o MySQL. A primeira execução é
o teste real — daí a insistência em ir uma de cada vez.

---

## 5. Instalar o Game State Integration no CS2

Copie `docs/gamestate_integration_countatic.cfg` para:

```
...\Steam\steamapps\common\Counter-Strike Global Offensive\game\csgo\cfg\
```

Troque o placeholder `TROQUE-PELO-VALOR-DE-GSI_TOKEN-DO-SEU-.env` pelo valor
que você gerou no item 2. **Reinicie o CS2 por completo** — ele só lê os `.cfg`
de GSI no boot.

Para conferir que funcionou: jogue uma partida e veja o log do backend. Se o
token estiver divergente, você verá agora um aviso explícito
(`POST /api/gsi rejeitado por token divergente`) — antes desta branch, o
sintoma era silêncio total.

---

## 6. Recriar os containers

As portas publicadas passaram a ligar em `127.0.0.1` em vez de `0.0.0.0`.
Isso só vale depois de recriar:

```powershell
docker compose up -d --force-recreate
```

Nada quebra: os serviços conversam entre si por nome dentro da rede
`countatic-net`, não pelas portas publicadas. O que muda é que outra máquina da
sua rede local deixa de alcançar a API — o que importa porque nenhum serviço
tem autenticação e `/reparse` é destrutivo.

Se um dia precisar acessar de outra máquina, **o passo que vem antes de reabrir
a porta é colocar autenticação nela.**

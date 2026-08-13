# Configuração da conta do bot — passos manuais obrigatórios

Estes passos não podem ser feitos por código. Sem eles o sistema sobe, loga na
Steam e envia mensagens, mas **nunca consegue estatística nenhuma de partida**.

---

## 1. Adicionar CS2 na conta do bot (recomendado, mas NÃO era o bloqueio)

### O que eu diagnostiquei errado

A investigação inicial concluiu que a conta do bot não tinha CS2, porque
`IPlayerService/GetOwnedGames` devolvia `game_count = 0`.

**Essa conclusão estava errada.** Aquela API não lista jogos free-to-play que a
conta nunca jogou — `game_count = 0` não prova ausência de licença. O contador
continua zerado até hoje e o Game Coordinator **conecta normalmente**.

### A causa real

O problema estava na chamada `gamesPlayed([730])`. A biblioteca
`globaloffensive` só reage à **transição** do evento `appLaunched`
(`globaloffensive/index.js:57`). Quando o `steam-user` já considerava o appid
730 "em execução" — o que acontece depois de um relogin automático ou de uma
sessão anterior — reenviar `gamesPlayed([730])` não emitia `appLaunched`,
`_connect()` nunca era chamado e o `ClientHello` nunca chegava ao GC.

O sintoma parecia idêntico ao de uma conta sem licença: o GC simplesmente
**não responde**, sem erro, sem timeout explícito, sem nada no log além da
ausência do evento `connectedToGC`.

A correção foi forçar a transição em `ensureGamePlayed()`:

```
gamesPlayed([])   →  espera 500 ms  →  gamesPlayed([730])
```

Mais um watchdog de 60 s que repete o processo enquanto `LOGGED_IN && !gcReady`.

### Ainda vale adicionar o CS2?

Sim — elimina uma variável e é grátis. Mas não é urgente.

1. Abra o cliente Steam e faça login como **`countersbot01`**.
2. Vá em `Loja → Counter-Strike 2` (ou busque "Counter-Strike 2").
3. Clique em **Jogar** / **Adicionar à biblioteca** — o CS2 é **gratuito**.
4. **Não precisa instalar o jogo nem abrir.** Basta a licença estar na conta.
5. Deslogue do cliente Steam (duas sessões simultâneas na mesma conta derrubam
   uma à outra, e a do bot é a que perde).

### Verificar que funcionou

```powershell
$key = "<sua STEAM_WEB_API_KEY>"
$bot = "76561198659888668"
Invoke-RestMethod "https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?key=$key&steamid=$bot&include_appinfo=1&include_played_free_games=1" |
  Select-Object -ExpandProperty response |
  Select-Object game_count, @{n='temCS2';e={ [bool]($_.games | Where-Object appid -eq 730) }}
```

Esperado: `game_count` ≥ 1 e `temCS2 = True`.

Depois, suba os containers e confirme a sessão do GC:

```powershell
Invoke-RestMethod http://localhost:3000/status | ConvertTo-Json
```

Esperado: `steamStatus = "LOGGED_IN"` **e** `gcReady = true`.

> `steamStatus` e `gcReady` agora são campos **independentes**. Antes o estado do
> GC era escrito por cima do estado de login, e no instante em que o GC conectava
> o `/notify` passava a devolver 503 — ou seja, consertar o GC quebraria o envio
> de mensagens. Se você vir `gcReady: true` e o chat funcionando, os dois estão
> corretamente desacoplados.

Se após ~20 minutos o `gcReady` continuar `false`, os logs do bot mostram o
diagnóstico (`docker compose logs steam-bot | Select-String "GC"`), incluindo o
`connectionStatus` cru do Game Coordinator.

---

## 2. 🔑 Rotacionar a Steam Web API Key

A chave anterior estava em texto puro no `docker-compose.yml` e **está no
histórico do git desde o commit `6742558`**. Removê-la do working tree não a
remove do histórico — qualquer pessoa com acesso ao repositório ainda a lê.

1. Acesse https://steamcommunity.com/dev/apikey
2. **Revogue** a chave atual e gere uma nova.
3. Coloque a nova em `CounTatics/.env`:
   ```
   STEAM_WEB_API_KEY=<nova_chave>
   ```
4. O `.env` está no `.gitignore`. O `docker-compose.yml` agora lê a variável e
   **falha na subida** se ela não existir, em vez de rodar com chave vazia e dar
   403 silencioso.

---

## 3. 👥 Amizade na Steam

`chat.sendFriendMessage` só entrega mensagem para amigos aceitos.

O bot agora **aceita convites automaticamente** (handler `friendRelationship`),
então basta o jogador adicionar `countersbot01` e aguardar alguns segundos.

Status atual: `countersbot01` já é amigo de `76561199110265389` (JGBR11). ✅

---

## 4. 🧹 Limpar o cadastro de teste

Existe uma linha `TestPlayer` no banco cujo SteamID é o **do próprio bot**, com
um auth code falso. Ela produz um `403 Forbidden` no log a cada 5 minutos.

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/players/76561198659888668" -Method Delete
```

Isso não deve mais acontecer: o `POST /api/players/auth` agora rejeita o SteamID
do bot e valida o formato do auth code, e um `403` da Valve passa a **desabilitar
o auto-fetch daquele jogador** em vez de repetir o erro indefinidamente.

---

## 5. 🎮 Game Authentication Code do jogador

Diferente da Web API Key. Cada jogador gera o seu:

`CS2 → Configurações → Game → Game Authentication Code`

Formato: `XXXX-XXXXX-XXXX` (ex: `ABCD-EFGHI-JKLM`)

> ⚠️ O Game Authentication Code **é segredo**. Junto com o seu SteamID64 — que
> é público — ele dá a qualquer pessoa acesso ao seu histórico de partidas pela
> API da Valve. Nunca commite o seu, nem em exemplo: o valor abaixo é
> sintético. Se precisar trocar o seu, gere um novo em
> `CS2 → Configurações → Game → Game Authentication Code`; o anterior deixa de
> valer.

Cadastro:

```powershell
$body = @{
  steamId64        = "seu_steamid64"
  authCode         = "ABCD-EFGHI-JKLM"
  initialShareCode = "CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx"
  displayName      = "SeuNick"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8080/api/players/auth" -Method Post `
  -ContentType "application/json" -Body $body
```

> ⚠️ Replays de CS2 ficam no CDN da Valve por **cerca de 2 semanas**. Partidas
> mais antigas que isso só rendem estatísticas básicas do Game Coordinator —
> a análise profunda da demo não é mais possível. Por isso o `skip-to-latest`
> abre mão da análise detalhada de tudo que ele pula.

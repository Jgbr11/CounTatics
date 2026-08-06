import SteamUser from "steam-user";
import fs from "fs";
import path from "path";
import readline from "readline";
import dotenv from "dotenv";

dotenv.config();

const username = process.env.STEAM_USERNAME;
const password = process.env.STEAM_PASSWORD;

if (!username || !password) {
  console.error("ERRO: Defina STEAM_USERNAME e STEAM_PASSWORD no .env");
  process.exit(1);
}

console.log("=================================================");
console.log(`  Gerador de Refresh Token — CounTatic Steam Bot`);
console.log("=================================================");
console.log(`Tentando autenticar como: ${username}...`);

const client = new SteamUser();

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
});

client.logOn({
  accountName: username,
  password: password,
});

client.on("steamGuard", (domain, callback) => {
  const domainStr = domain ? `*@${domain}` : "e-mail";
  console.log(`\n🔐 Código Steam Guard enviado para o seu ${domainStr}!`);
  rl.question("👉 Digite o código de 5 caracteres recebido no e-mail: ", (code) => {
    callback(code.trim().toUpperCase());
  });
});

client.on("refreshToken", (token) => {
  console.log("\n✅ REFRESH TOKEN GERADO COM SUCESSO!");
  const tokenPath = path.join(process.cwd(), "refreshToken.txt");
  fs.writeFileSync(tokenPath, token, "utf-8");
  console.log(`💾 Token salvo em: ${tokenPath}`);
  console.log("\nO bot agora pode rodar no Docker para sempre sem pedir códigos!");
  rl.close();
  client.logOff();
  process.exit(0);
});

client.on("loggedOn", () => {
  console.log("✅ Logado na Steam!");
});

client.on("error", (err) => {
  console.error(`❌ Erro no login: ${err.message}`);
  if (err.message.includes("AccountLoginDeniedThrottle")) {
    console.error("   A Steam está bloqueando tentativas no momento por muitas tentativas recentes.");
    console.error("   Aguarde cerca de 10 minutos antes de tentar rodar o script novamente.");
  }
  rl.close();
  process.exit(1);
});

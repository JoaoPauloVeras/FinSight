# FinSight — Personal Finance Dashboard

Dashboard financeiro com cotações da B3 (Brapi), câmbio (AwesomeAPI), simulador de renda fixa e
análise por IA (Google Gemini). Backend em Java puro (sem dependências) que também entrega o site.

```
backend/    código Java (ServidorBackend, clientes das APIs, simuladores)
frontend/   index.html + logo
Dockerfile  receita para o Render compilar e rodar o app
render.yaml configuração opcional do serviço no Render
```

## Rodar localmente

1. Variáveis de ambiente (no IntelliJ: Run > Edit Configurations > Environment variables):
   `GEMINI_API_KEY=sua_chave` (e, opcional, `BRAPI_TOKEN=seu_token`).
2. Rode `Main.java` com a pasta do projeto como diretório de trabalho.
3. Abra http://localhost:8080 (o próprio servidor entrega o site).

## Variáveis de ambiente

| Variável | Para quê | Obrigatória |
|---|---|---|
| `GEMINI_API_KEY` | Chave da API do Gemini (rota `/api/ia`) | Para usar a IA |
| `BRAPI_TOKEN` | Token da Brapi (sem ele só PETR4, VALE3, ITUB4, MGLU3) | Não |
| `CDI_ANUAL` | CDI de referência, ex.: `10.75` | Não |
| `IA_MAX_POR_MINUTO` | Teto global de chamadas à IA por minuto (padrão 10) | Não |
| `GEMINI_MODELS` | Modelos do Gemini separados por vírgula (ordem de fallback) | Não |
| `CORS_ORIGIN` | Só para desenvolvimento com o front em outra origem. **Deixe vazia em produção** | Não |

> **Nunca** coloque chaves no código nem no GitHub.

## Publicar no GitHub

```bash
git init
git add .
git status            # confira: NÃO pode aparecer .env, chaves ou arquivos .jar/.class
git commit -m "FinSight: primeira versão"
git branch -M main
git remote add origin https://github.com/SEU_USUARIO/finsight.git
git push -u origin main
```

(Crie antes o repositório vazio em github.com → New repository, sem README/.gitignore.)
Se uma chave já foi para o GitHub por engano, **revogue-a** e crie outra: apagar o arquivo não basta,
ela continua no histórico.

## Deploy no Render

1. Em render.com, entre com sua conta do GitHub.
2. **New → Blueprint** e escolha o repositório (usa o `render.yaml`).
   Alternativa manual: **New → Web Service → repositório → Language: Docker → Instance type: Free**.
3. Preencha `GEMINI_API_KEY` (e `BRAPI_TOKEN`, se tiver) nas variáveis de ambiente.
4. Aguarde o build. O endereço fica `https://finsight-xxxx.onrender.com` (HTTPS já incluso).

No plano gratuito o serviço "dorme" após 15 minutos sem acessos; o primeiro acesso depois disso
leva cerca de 1 minuto para acordar.
Cada `git push` na branch `main` publica uma nova versão automaticamente.

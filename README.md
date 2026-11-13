# ledger

Serviço de contas e transferências com partidas dobradas, em Java 25 + Spring Boot 4 + PostgreSQL.

Fiz pra estudar o lado "chato" de sistema de dinheiro: o que acontece quando duas transferências chegam ao mesmo tempo, quando o cliente repete a requisição porque a rede caiu, ou quando alguém tenta mexer direto no banco.

## Como funciona

Toda movimentação vira um lançamento com partidas que somam zero. Uma transferência de R$ 25,50 da Alice pro Bob gera duas partidas: `-2550` na Alice e `+2550` no Bob. Depósito e saque usam uma conta de sistema por moeda ("caixa externo"), que é a única que pode ficar negativa.

Nada é editado nem apagado. Errou? Estorna, e o estorno é um lançamento novo com as partidas invertidas.

Valores são `long` em centavos.

## Decisões

**Trava pessimista em vez de otimista.** As contas da operação são travadas com `SELECT ... FOR UPDATE`, sempre em ordem de id. Com `@Version` (otimista), uma conta muito disputada gera um monte de retry; aqui a segunda transação só espera. A ordem fixa é o que evita deadlock quando A→B e B→A rodam juntas: tem teste pra isso.

**O saldo fica guardado na conta.** Dava pra calcular somando as partidas, mas o extrato e o saldo ficariam lentos com o tempo. Em troca, preciso garantir que os dois nunca divergem, por isso existe `/audit/trial-balance`.

**Regra no código e no banco.** O serviço valida saldo e soma zero, mas o Postgres também: `CHECK` de saldo não negativo, um *constraint trigger* `DEFERRABLE` que confere a soma das partidas no commit, e triggers que bloqueiam `UPDATE`/`DELETE`. Se algum dia alguém escrever direto no banco ou eu introduzir um bug, o banco recusa.

**Idempotência na mesma transação.** A `Idempotency-Key` é inserida junto com o lançamento. Se duas requisições com a mesma chave chegam juntas, a segunda espera no `INSERT` e depois devolve a resposta guardada. Se a operação falha, o rollback leva a chave junto. Mesma chave com outro corpo retorna `422`. As chaves expiram em 24h.

**Extrato com cursor**, não com `offset`, pra página não pular nem repetir item quando entram lançamentos novos.

## Limitações conhecidas

- Uma conta muito movimentada vira gargalo, porque toda operação nela espera na mesma trava. A saída clássica é dividir o saldo em sub-contas, e ainda não fiz isso.
- Não tem autenticação. O `/audit` deveria ser só de admin.
- Uma moeda por conta e sem câmbio.
- A conta de sistema também é travada em todo depósito e saque, o que limita a vazão dessas operações.

## API

```
POST /accounts                        { ownerName, currency }        BRL | USD | EUR
GET  /accounts/{id}
GET  /accounts/{id}/entries?before=&limit=
POST /accounts/{id}/deposits          { amount, description }        Idempotency-Key
POST /accounts/{id}/withdrawals       { amount, description }        Idempotency-Key
POST /transfers                       { fromAccountId, toAccountId, amount, description }  Idempotency-Key
POST /transactions/{id}/reversal      { description }                Idempotency-Key
GET  /audit/trial-balance
GET  /actuator/prometheus
```

Erros vêm em Problem Details (RFC 9457), por exemplo:

```json
{ "type": ".../errors/insufficient-funds", "status": 422, "title": "insufficient-funds",
  "detail": "Saldo insuficiente na conta ...", "balance": 2550, "requested": 999999 }
```

Tem um [`requests.http`](requests.http) pra testar pelo IntelliJ ou pelo VS Code (REST Client).

## Rodando

Precisa de Java 25 e Docker.

```bash
./mvnw spring-boot:run   # sobe o Postgres do compose.yaml e a API na 8080
./mvnw test
```

Os testes de integração usam Testcontainers, com um Postgres de verdade. Os que mais importam:

- 50 transferências de 30 em paralelo, numa conta com 1000: passam exatamente 33, e o balancete fecha.
- 100 transferências cruzando A↔B em paralelo, sem nenhum deadlock.
- Repetição com a mesma chave, chave reaproveitada com outro corpo, estorno duplo, `UPDATE` direto no banco.

## Próximos passos

- Publicar os lançamentos num tópico (Kafka) usando outbox, pra outros serviços reagirem sem consultar o ledger.
- Autenticação e conta por cliente.
- Dividir contas quentes em sub-contas.

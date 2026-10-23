# 2. API de estoque e pedidos

API para cadastrar produtos, reservar estoque ao criar pedidos, confirmar ou cancelar as reservas e consultar o estado do pedido.

**Nome planejado do repositório GitHub:** `controle-estoque-pedidos`.

## Requisitos e execução

- Java 17 ou superior, Maven 3.6.3+ e Docker Compose.
- Na pasta deste projeto: `docker compose up -d` e depois `mvn spring-boot:run`.
- API: `http://localhost:8080`. PostgreSQL: porta `5434`.
- Flyway cria o esquema a partir de `src/main/resources/db/migration/V1__schema_inicial.sql`.
- Para parar: `docker compose down`; para apagar também o volume local: `docker compose down -v`.

Leia `docs/GUIA_DE_ESTUDO.md` e `docs/API.md` para entender as regras antes de alterar o código.

## Regras implementadas

- Criar pedidos reserva o estoque; `available = on_hand - reserved`.
- A reserva só é aceita se houver unidades disponíveis.
- Confirmar consome as unidades físicas e libera a quantidade reservada.
- Cancelar um pedido reservado libera a reserva; pedidos confirmados não podem ser cancelados nesta versão.
- O preço e o nome do produto são copiados para cada linha no momento do pedido.
- Os valores assumem uma moeda única (BRL); conversão entre moedas não faz parte do MVP.
- `Idempotency-Key` impede que uma repetição com o mesmo conteúdo crie um segundo pedido.
- O banco protege estoque não negativo, reserva dentro do estoque físico e quantidades positivas.

## Limites

Este é um MVP didático, sem autenticação, pagamento, cliente, expiração automática de reserva, entrada de novas unidades ou implantação pública. Use apenas localmente. As regras de bloqueio e transação ilustram concorrência, mas devem ser acompanhadas por testes de integração antes de qualquer uso real.

## Organização de pastas

```text
02-controle-estoque-pedidos/
├── docs/
│   ├── API.md                 # rotas, payloads e respostas
│   └── GUIA_DE_ESTUDO.md      # transações e concorrência
├── src/main/java/br/com/portfolio/estoque/
│   ├── EstoqueApplication.java
│   ├── OrderController.java   # endpoints HTTP
│   ├── OrderDtos.java         # contratos de entrada/saída
│   ├── OrderService.java      # SQL, locks e estados
│   └── ApiExceptionHandler.java
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/V1__schema_inicial.sql
├── compose.yaml
├── pom.xml
└── README.md
```

## Modelo e fluxo de um pedido

`products` guarda estoque físico (`on_hand`) e reservado (`reserved`); a disponibilidade é a diferença. `orders` guarda o estado; `order_lines` congela nome e preço; `order_idempotency` vincula uma chave única ao hash do conteúdo e ao pedido criado.

Na criação, os produtos são bloqueados em UUID crescente. Depois de verificar disponibilidade, o serviço insere pedido/linhas e atualiza reservas na mesma transação. Qualquer erro reverte o conjunto inteiro. Confirmar consome o estoque físico; cancelar libera apenas a reserva.

## Endpoints e cabeçalhos

| Método | Rota | Uso |
|---|---|---|
| `POST` | `/api/products` | Cadastrar produto e estoque inicial |
| `GET` | `/api/products` | Consultar estoque e disponibilidade |
| `POST` | `/api/orders` | Criar pedido; exige `Idempotency-Key` |
| `GET` | `/api/orders/{id}` | Consultar linhas, preço congelado e total |
| `POST` | `/api/orders/{id}/confirm` | Confirmar pedido reservado |
| `POST` | `/api/orders/{id}/cancel` | Cancelar pedido reservado |

Os exemplos `curl` estão em [`docs/API.md`](docs/API.md). O guia descreve por que o bloqueio precisa ficar dentro da transação e como a chave idempotente funciona.

## Como estudar este projeto

1. Comece pelo `CHECK` de estoque na migração.
2. Acompanhe `createOrder` e marque mentalmente o início e o fim da transação.
3. Compare o estado `RESERVED` antes/depois de confirmar ou cancelar.
4. Reenvie uma criação com a mesma chave e depois com corpo diferente.
5. Escreva testes concorrentes antes de tratar o comportamento como adequado para produção.

# API de estoque e pedidos

Base local: `http://localhost:8080`.

## 1. Cadastrar produto

```bash
curl -i -X POST http://localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Teclado","unitPrice":180.00,"initialStock":5}'
```

Guarde o campo `id` da resposta `201 Created`.

## 2. Criar pedido e reservar estoque

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: pedido-demo-001' \
  -d '{"items":[{"productId":"<ID_DO_PRODUTO>","quantity":2}]}'
```

O retorno inclui `id`, estado `RESERVED`, linhas com preço congelado e total. Reenvie a chamada com a mesma chave e corpo para receber o mesmo pedido. Reutilize a chave com outro corpo para ver o conflito `409`.

## 3. Consultar e alterar estado

```bash
curl http://localhost:8080/api/orders/<ID_DO_PEDIDO>

curl -i -X POST http://localhost:8080/api/orders/<ID_DO_PEDIDO>/confirm

curl -i -X POST http://localhost:8080/api/orders/<ID_DO_PEDIDO>/cancel

curl http://localhost:8080/api/products
```

Confirme **ou** cancele um pedido reservado. A confirmação baixa `on_hand` e `reserved`; o cancelamento só baixa `reserved`. Depois de confirmado, o pedido não pode ser cancelado nesta versão.

## Estados e erros

- `201 Created`: produto ou pedido criado.
- `400 Bad Request`: campos inválidos, `Idempotency-Key` ausente ou produto repetido no mesmo pedido.
- `404 Not Found`: produto ou pedido desconhecido.
- `409 Conflict`: estoque insuficiente, chave idempotente usada com corpo diferente ou transição de estado não permitida.

O serviço usa respostas de erro Problem Details do Spring; a mensagem explica a regra violada.

# Integração Open Finance via Pluggy

## Objetivo

Preparar a integração Pluggy sem misturar prematuramente dados externos com o banco local atual e sem expor credenciais no APK.

## Comportamentos validados na exploração

Os pontos abaixo foram confirmados em uma conexão real Santander via Meu Pluggy, sem registrar no repositório IDs, saldos ou dados pessoais:

- contas `BANK` e `CREDIT` são retornadas separadamente;
- `BANK.balance` representa o saldo disponível observado;
- `bankData` pode trazer `closingBalance`, limite de cheque especial contratado e utilizado;
- em contas `CREDIT`, `creditLimit` e `availableCreditLimit` são utilizáveis diretamente;
- o `balance` de `CREDIT` observado correspondeu ao crédito utilizado e não deve ser tratado como total oficial da fatura;
- faturas fechadas são obtidas pela entidade `Bill`, com `dueDate`, `billClosingDate`, `totalAmount`, `minimumPaymentAmount` e `allowsInstallments`;
- compras parceladas trazem `installmentNumber`, `totalInstallments`, `purchaseDate` e `billForecastDate`;
- `purchaseDate` preserva a data/hora original da compra nas parcelas observadas;
- `date` não é uma fonte segura para a data original de parcelas posteriores;
- `amount` representa o valor da parcela observada;
- `totalAmount` de uma compra parcelada pode estar ausente e deve ser opcional;
- transações `POSTED` observadas de faturas fechadas vieram com vínculo de fatura, enquanto transações ainda `PENDING` podiam ter apenas `billForecastDate`;
- transações bancárias PIX trazem `operationType` e `paymentData.paymentMethod` como `PIX`;
- PIX de entrada e saída são diferenciados por direção/sinal;
- `category` e `categoryId` vieram preenchidos no conjunto observado;
- `category`, `operationType` e `paymentMethod` devem ser preservados como conceitos distintos;
- dados de `payer`/`receiver` podem conter nome, documento, conta, agência e identificadores bancários e devem ser considerados sensíveis.

## Regra de competência para cartão

Para o Assistente Financeiro, a ordem de preferência deve ser:

1. `billId` -> fatura oficial, quando disponível;
2. `billForecastDate` -> competência prevista para transações ainda sem fatura fechada;
3. nunca usar `transaction.date` como substituto automático de `purchaseDate` em parcelas futuras.

A data original da compra deve usar `purchaseDate` quando disponível. Para transações sem `purchaseDate`, pode-se usar `date` como fallback explícito.

## Convivência com notificações bancárias

A integração não deve simplesmente substituir o parser de notificações.

Proposta:

- notificação bancária: captura rápida/provisória do evento;
- Pluggy: fonte estruturada para sincronização, enriquecimento e conciliação;
- após a sincronização, uma transação Pluggy deve ser conciliada com uma notificação existente quando houver evidência suficiente, evitando duplicidade;
- o identificador externo Pluggy deve ser persistido para tornar sincronizações posteriores idempotentes.

## Segurança

- nunca embutir `clientSecret` no APK;
- não registrar `apiKey`, `itemId`, `accountId`, `billId` ou IDs de transação em logs de produção;
- não registrar payloads completos de `paymentData`;
- persistir apenas os dados necessários ao produto;
- reduzir número de cartão para últimos quatro dígitos antes de entrar no domínio do app;
- o build `pluggysandbox` é o ambiente apropriado para testes manuais da integração;
- para testes iniciais, uma `apiKey` temporária pode ser fornecida manualmente ao build de teste e mantida apenas em memória; isso não é solução de produção;
- para produção, autenticação com `clientSecret` deve ocorrer fora do aplicativo Android, em um serviço controlado pelo projeto.

## Fases propostas

### Fase 1 - Fundação de domínio

- modelos independentes do JSON Pluggy;
- origem `TransactionOrigin.PLUGGY`;
- normalização de cartão para últimos quatro dígitos;
- testes unitários das regras de parcelamento e datas;
- sem rede e sem escrita no SQLite.

### Fase 2 - Transporte somente leitura no build de teste

- cliente HTTP Pluggy restrito ao build `pluggysandbox`;
- `apiKey` e `itemId` fornecidos em runtime, sem `clientSecret` no APK;
- endpoints: Accounts, Bills e `/v2/transactions`;
- paginação por cursor;
- DTOs separados dos modelos de domínio;
- nenhuma gravação automática no banco financeiro.

### Fase 3 - Preview de sincronização

Antes de importar, mostrar:

- contas encontradas;
- contas locais candidatas ao vínculo;
- novas transações;
- transações já existentes;
- possíveis correspondências com notificações;
- conflitos de categoria/status/data;
- faturas novas ou alteradas.

O usuário confirma antes da primeira importação.

### Fase 4 - Persistência idempotente

Adicionar tabelas/colunas de vínculo externo sem substituir os IDs internos atuais. Uma sincronização repetida do mesmo payload não pode criar duplicatas.

### Fase 5 - Reconciliação com notificações

Critérios iniciais para cartão:

- mesma conta/cartão ou últimos quatro dígitos compatíveis;
- mesmo valor;
- `purchaseDate` convertido corretamente para o fuso local;
- janela temporal curta;
- descrição/estabelecimento como evidência adicional, não como chave única.

Correspondências ambíguas devem ir para revisão em vez de serem unidas automaticamente.

## Fora do domínio inicial

Não persistir inicialmente:

- CPF/CNPJ de pagador/recebedor;
- número completo de conta de contraparte;
- agência de contraparte;
- códigos de autenticação/referência PIX;
- payload JSON bruto.

Esses campos só devem entrar no produto se houver uma funcionalidade clara que justifique armazenamento e proteção apropriada.

-- Contas. O saldo é guardado na conta para leitura rápida e sempre confere
-- com a soma das partidas (verificado em /audit/trial-balance).
create table account (
    id          uuid primary key,
    owner_name  varchar(120) not null,
    currency    varchar(3)   not null check (currency ~ '^[A-Z]{3}$'),
    kind        varchar(16)  not null check (kind in ('CUSTOMER', 'SYSTEM')),
    balance     bigint       not null default 0,
    created_at  timestamptz  not null default now(),
    -- rede de segurança: mesmo que o código erre, conta de cliente não fica negativa
    constraint customer_balance_non_negative check (kind = 'SYSTEM' or balance >= 0)
);

-- Uma conta de sistema por moeda representa o dinheiro que entra e sai do ledger.
create unique index one_system_account_per_currency on account (currency) where kind = 'SYSTEM';

-- Lançamento: agrupa as partidas de uma operação.
create table ledger_transaction (
    id           uuid primary key,
    type         varchar(16)  not null check (type in ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL')),
    description  varchar(200),
    reverses_id  uuid unique references ledger_transaction (id),
    created_at   timestamptz  not null default now()
);

-- Partida: débito (negativo) ou crédito (positivo) em uma conta.
create table posting (
    id              bigint generated always as identity primary key,
    transaction_id  uuid        not null references ledger_transaction (id),
    account_id      uuid        not null references account (id),
    amount          bigint      not null check (amount <> 0),
    currency        varchar(3)  not null,
    balance_after   bigint      not null,
    created_at      timestamptz not null default now()
);

create index posting_by_account on posting (account_id, id desc);
create index posting_by_transaction on posting (transaction_id);

-- O ledger é só de inserção: nada é editado nem apagado.
create function ledger_forbid_change() returns trigger language plpgsql as $$
begin
    raise exception 'ledger is append-only: % on % is not allowed', tg_op, tg_table_name;
end;
$$;

create trigger posting_append_only before update or delete on posting
    for each row execute function ledger_forbid_change();

create trigger transaction_append_only before update or delete on ledger_transaction
    for each row execute function ledger_forbid_change();

-- As partidas de cada lançamento somam zero. Verificado no commit (deferred),
-- depois que todas as partidas do lançamento foram inseridas.
create function ledger_check_balanced() returns trigger language plpgsql as $$
declare
    total bigint;
begin
    select coalesce(sum(amount), 0) into total from posting where transaction_id = new.transaction_id;
    if total <> 0 then
        raise exception 'transaction % is unbalanced: postings sum to %', new.transaction_id, total;
    end if;
    return null;
end;
$$;

create constraint trigger posting_balanced after insert on posting
    deferrable initially deferred
    for each row execute function ledger_check_balanced();

-- Chaves de idempotência: a resposta da primeira requisição é guardada e devolvida nas repetições.
create table idempotency_key (
    key            varchar(100) primary key,
    request_hash   varchar(64)  not null,
    status         int,
    response_body  text,
    created_at     timestamptz  not null default now()
);

insert into account (id, owner_name, currency, kind) values
    ('00000000-0000-0000-0000-000000000986', 'Caixa externo BRL', 'BRL', 'SYSTEM'),
    ('00000000-0000-0000-0000-000000000840', 'Caixa externo USD', 'USD', 'SYSTEM'),
    ('00000000-0000-0000-0000-000000000978', 'Caixa externo EUR', 'EUR', 'SYSTEM');

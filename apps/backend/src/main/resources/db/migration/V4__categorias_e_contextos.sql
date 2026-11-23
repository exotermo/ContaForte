-- V4: categorias (2 níveis) e contextos (Milestone 2). user_id nulo = padrão do sistema (somente leitura na API).
-- Categoria e contexto são dimensões independentes (ADR-009, ADR-014).

CREATE TABLE transaction_categories (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        REFERENCES users (id) ON DELETE CASCADE,
    parent_id  uuid        REFERENCES transaction_categories (id),
    code       varchar(60),
    name       varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- só as categorias padrão têm código estável
    CONSTRAINT ck_categories_code_default CHECK ((user_id IS NULL) = (code IS NOT NULL))
);

CREATE UNIQUE INDEX uk_categories_code ON transaction_categories (code) WHERE user_id IS NULL;
-- nome único dentro do mesmo dono e do mesmo pai
CREATE UNIQUE INDEX uk_categories_scope_name ON transaction_categories (
    COALESCE(user_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid),
    lower(name));
CREATE INDEX ix_categories_user ON transaction_categories (user_id);
CREATE INDEX ix_categories_parent ON transaction_categories (parent_id);

CREATE TABLE transaction_contexts (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        REFERENCES users (id) ON DELETE CASCADE,
    code       varchar(60),
    name       varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_contexts_code_default CHECK ((user_id IS NULL) = (code IS NOT NULL))
);

CREATE UNIQUE INDEX uk_contexts_code ON transaction_contexts (code) WHERE user_id IS NULL;
CREATE UNIQUE INDEX uk_contexts_scope_name ON transaction_contexts (
    COALESCE(user_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
CREATE INDEX ix_contexts_user ON transaction_contexts (user_id);

-- Hierarquia: no máximo 2 níveis e o pai precisa ser padrão ou do mesmo dono.
CREATE FUNCTION check_category_hierarchy() RETURNS trigger AS $$
DECLARE
    pai transaction_categories%ROWTYPE;
BEGIN
    IF NEW.parent_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO pai FROM transaction_categories WHERE id = NEW.parent_id;
    IF pai.parent_id IS NOT NULL THEN
        RAISE EXCEPTION 'categoria admite no máximo 2 níveis' USING ERRCODE = '23514';
    END IF;
    IF pai.user_id IS NOT NULL AND pai.user_id IS DISTINCT FROM NEW.user_id THEN
        RAISE EXCEPTION 'categoria-pai pertence a outro usuário' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_category_hierarchy
    BEFORE INSERT OR UPDATE OF parent_id, user_id ON transaction_categories
    FOR EACH ROW EXECUTE FUNCTION check_category_hierarchy();

-- Seed: categorias padrão (pt-BR). Pais primeiro, depois as subcategorias.
INSERT INTO transaction_categories (code, name) VALUES
    ('FOOD',        'Alimentação'),
    ('HOUSING',     'Moradia'),
    ('TRANSPORT',   'Transporte'),
    ('HEALTH',      'Saúde'),
    ('LEISURE',     'Lazer'),
    ('EDUCATION',   'Educação'),
    ('SHOPPING',    'Compras'),
    ('FEES_TAXES',  'Taxas e impostos'),
    ('INCOME',      'Renda'),
    ('INVESTMENTS', 'Investimentos'),
    ('TRANSFERS',   'Transferências'),
    ('OTHER',       'Outros');

INSERT INTO transaction_categories (code, parent_id, name)
SELECT v.code, p.id, v.name
FROM (VALUES
    ('FOOD_RESTAURANT',     'FOOD',        'Restaurante'),
    ('FOOD_GROCERIES',      'FOOD',        'Mercado'),
    ('FOOD_DELIVERY',       'FOOD',        'Delivery'),
    ('FOOD_SNACKS',         'FOOD',        'Lanches e padaria'),
    ('HOUSING_RENT',        'HOUSING',     'Aluguel'),
    ('HOUSING_CONDO',       'HOUSING',     'Condomínio'),
    ('HOUSING_ENERGY',      'HOUSING',     'Energia'),
    ('HOUSING_WATER',       'HOUSING',     'Água'),
    ('HOUSING_INTERNET',    'HOUSING',     'Internet e telefone'),
    ('HOUSING_MAINTENANCE', 'HOUSING',     'Manutenção'),
    ('TRANSPORT_FUEL',      'TRANSPORT',   'Combustível'),
    ('TRANSPORT_PUBLIC',    'TRANSPORT',   'Transporte público'),
    ('TRANSPORT_RIDE',      'TRANSPORT',   'Aplicativo de transporte'),
    ('TRANSPORT_VEHICLE',   'TRANSPORT',   'Manutenção do veículo'),
    ('HEALTH_APPOINTMENT',  'HEALTH',      'Consultas e exames'),
    ('HEALTH_PHARMACY',     'HEALTH',      'Farmácia'),
    ('HEALTH_INSURANCE',    'HEALTH',      'Plano de saúde'),
    ('HEALTH_WELLNESS',     'HEALTH',      'Bem-estar e academia'),
    ('LEISURE_STREAMING',   'LEISURE',     'Streaming e assinaturas'),
    ('LEISURE_TRAVEL',      'LEISURE',     'Viagens'),
    ('LEISURE_EVENTS',      'LEISURE',     'Eventos e passeios'),
    ('EDUCATION_COURSES',   'EDUCATION',   'Cursos'),
    ('EDUCATION_BOOKS',     'EDUCATION',   'Livros e materiais'),
    ('EDUCATION_TUITION',   'EDUCATION',   'Mensalidade'),
    ('SHOPPING_CLOTHES',    'SHOPPING',    'Vestuário'),
    ('SHOPPING_ELECTRONICS','SHOPPING',    'Eletrônicos'),
    ('SHOPPING_HOME',       'SHOPPING',    'Casa e decoração'),
    ('FEES_BANK',           'FEES_TAXES',  'Tarifas bancárias'),
    ('FEES_TAXES_GOV',      'FEES_TAXES',  'Impostos'),
    ('FEES_INTEREST',       'FEES_TAXES',  'Juros e multas'),
    ('INCOME_SALARY',       'INCOME',      'Salário'),
    ('INCOME_FREELANCE',    'INCOME',      'Trabalho autônomo'),
    ('INCOME_YIELD',        'INCOME',      'Rendimentos'),
    ('INCOME_OTHER',        'INCOME',      'Outras receitas'),
    ('INVEST_FIXED',        'INVESTMENTS', 'Renda fixa'),
    ('INVEST_VARIABLE',     'INVESTMENTS', 'Renda variável'),
    ('INVEST_PENSION',      'INVESTMENTS', 'Previdência'),
    ('TRANSFERS_OWN',       'TRANSFERS',   'Entre contas próprias'),
    ('TRANSFERS_CARD_BILL', 'TRANSFERS',   'Pagamento de fatura')
) AS v (code, parent_code, name)
JOIN transaction_categories p ON p.code = v.parent_code AND p.user_id IS NULL;

INSERT INTO transaction_contexts (code, name) VALUES
    ('INDIVIDUAL',   'Individual'),
    ('FAMILY',       'Família'),
    ('RELATIONSHIP', 'Relacionamento'),
    ('PET',          'Pet'),
    ('WORK',         'Trabalho'),
    ('OTHER',        'Outro');

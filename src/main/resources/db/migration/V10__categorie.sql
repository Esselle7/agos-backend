-- ============================================================
-- V10 - CATEGORIE
-- Categorizzazione semplificata ENTRATA/USCITA per BU,
-- con supporto gerarchia padre-figlio (sottocategorie).
-- Usata dall'anagrafica per classificare i movimenti
-- in modo più user-friendly rispetto al piano dei conti COGE.
-- ============================================================

CREATE TABLE categorie (
    id          BIGSERIAL    PRIMARY KEY,
    nome        VARCHAR(100) NOT NULL,
    tipo        VARCHAR(50)  NOT NULL REFERENCES lk_tipi_movimento(codice),
    parent_id   BIGINT       REFERENCES categorie(id),
    bu_id       SMALLINT     NOT NULL REFERENCES business_units(id),
    ordinamento INT          NOT NULL DEFAULT 0,
    is_active   BOOLEAN      NOT NULL DEFAULT true
);

CREATE INDEX idx_categorie_tipo_bu ON categorie (tipo, bu_id) WHERE is_active = true;
CREATE INDEX idx_categorie_parent  ON categorie (parent_id) WHERE parent_id IS NOT NULL;

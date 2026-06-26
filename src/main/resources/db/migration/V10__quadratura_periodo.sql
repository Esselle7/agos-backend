-- ============================================================
-- V10 — Quadratura di periodo POS (Billy = verità)
-- ============================================================
-- PROMPT-RICONCILIAZIONE-PERIODO §5: la riconciliazione POS passa da per-scontrino a PERIODO.
-- I ricavi elettronici nascono da Billy (un movimento per scontrino, categoria da Billy); le
-- banche servono solo a (i) ripartire l'incasso sui conti (BPM/CA) e (ii) fare da controllo di
-- quadratura. La quadratura è INFORMATIVA (non un cancello): qui si persiste, per ogni import
-- congiunto, il confronto Σ Billy ↔ Σ POS banca scomposto per causa (coda testa, coda fondo,
-- residuo core) così che il pannello di quadratura lo mostri senza ricalcolare.
--
-- ON DELETE CASCADE su import_log: il rollback di un import rimuove anche la sua quadratura.

CREATE TABLE quadratura_periodo (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    import_log_id               uuid NOT NULL REFERENCES import_log(id) ON DELETE CASCADE,
    anno                        smallint NOT NULL,
    billy_elettronico_non_agri  numeric(14,2) NOT NULL,  -- Σ scontrini elettronici non-agri (tutto il file)
    billy_contabilizzato        numeric(14,2) NOT NULL,  -- Σ ricavi contabilizzati (= non-agri − coda fondo)
    pos_banca_totale            numeric(14,2) NOT NULL,  -- Σ righe POS banca (BPM+CA, tutte)
    pos_banca_core              numeric(14,2) NOT NULL,  -- Σ righe POS banca del periodo (testa esclusa)
    sigma_bpm                   numeric(14,2) NOT NULL,  -- Σ POS BPM core (target ripartizione)
    sigma_ca                    numeric(14,2) NOT NULL,  -- Σ POS CA core (target ripartizione)
    assegnato_bpm               numeric(14,2) NOT NULL,  -- Σ ricavi assegnati al conto BPM
    assegnato_ca                numeric(14,2) NOT NULL,  -- Σ ricavi assegnati al conto CA
    coda_testa                  numeric(14,2) NOT NULL,  -- POS con DEL anno precedente → escluso
    coda_fondo                  numeric(14,2) NOT NULL,  -- vendite dopo l'ultima DEL → in attesa accredito
    residuo_core                numeric(14,2) NOT NULL,  -- Δ core residuo (agri-a-POS, Satispay netto/lordo, storni)
    max_del_banca               date,                    -- ultima DEL banca (soglia coda fondo)
    note                        jsonb NOT NULL DEFAULT '[]',  -- cause leggibili del residuo
    in_attesa                   jsonb NOT NULL DEFAULT '[]',  -- coda fondo: scontrini segnalati (display)
    created_at                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_quadratura_import ON quadratura_periodo(import_log_id);
CREATE INDEX idx_quadratura_created ON quadratura_periodo(created_at DESC);

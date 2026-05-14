-- V20 removed RIMBORSO from lk_tipi_evento_mov; restore it since the business
-- still needs to record partial refunds as negative-importo ENTRATA movements.
INSERT INTO lk_tipi_evento_mov (codice, descrizione)
VALUES ('RIMBORSO', 'Rimborso al cliente')
ON CONFLICT (codice) DO NOTHING;

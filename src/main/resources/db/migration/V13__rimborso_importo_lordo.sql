-- Rimuove il vincolo CHECK (importo_lordo > 0) sulla tabella movimenti.
-- I RIMBORSO vengono registrati come ENTRATA con importo_lordo negativo:
-- il trigger fn_ricalcola_evento somma tutti gli ENTRATA e il negativo
-- riduce correttamente importo_incassato sull'evento collegato.
ALTER TABLE movimenti DROP CONSTRAINT IF EXISTS movimenti_importo_lordo_check;

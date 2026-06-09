-- ============================================================
-- V4 - SEED DATI CORRENTI (config / anagrafica)
--
-- Solo tabelle da MANTENERE. Le tabelle operative nascono vuote.
-- INSERT ordinati per rispettare le FK; sequenze riallineate in coda.
-- Estratto dallo stato corrente di agosdb.
-- ============================================================

-- ---------- LOOKUP ----------
INSERT INTO lk_ruoli_utente (codice, descrizione) VALUES ('ADMIN', 'Amministratore – accesso completo');
INSERT INTO lk_ruoli_utente (codice, descrizione) VALUES ('DIPENDENTE', 'Dipendente – accesso limitato (solo eventi)');

INSERT INTO lk_tipi_coge (codice, descrizione) VALUES ('ATTIVITA', 'Attività patrimoniale');
INSERT INTO lk_tipi_coge (codice, descrizione) VALUES ('PASSIVITA', 'Passività patrimoniale / debito finanziario');
INSERT INTO lk_tipi_coge (codice, descrizione) VALUES ('COSTO', 'Costo operativo (Opex)');
INSERT INTO lk_tipi_coge (codice, descrizione) VALUES ('RICAVO', 'Ricavo');
INSERT INTO lk_tipi_coge (codice, descrizione) VALUES ('ONERE_FINANZIARIO', 'Onere finanziario – interessi passivi su finanziamenti');
INSERT INTO lk_tipi_coge (codice, descrizione) VALUES ('IMPOSTA', 'Imposta / tributo (IRAP, IRPEF, IRES)');

INSERT INTO lk_tipi_movimento (codice, descrizione) VALUES ('ENTRATA', 'Entrata di cassa / incasso');
INSERT INTO lk_tipi_movimento (codice, descrizione) VALUES ('USCITA', 'Uscita di cassa / pagamento');

INSERT INTO lk_tipi_cassa_mov (codice, descrizione) VALUES ('ENTRATA', 'Entrata in cassa contanti');
INSERT INTO lk_tipi_cassa_mov (codice, descrizione) VALUES ('USCITA', 'Uscita dalla cassa contanti');
INSERT INTO lk_tipi_cassa_mov (codice, descrizione) VALUES ('PRELIEVO_DA_BANCA', 'Prelievo bancomat / sportello portato in cassa');
INSERT INTO lk_tipi_cassa_mov (codice, descrizione) VALUES ('VERSAMENTO_IN_BANCA', 'Versamento contanti in banca');

INSERT INTO lk_tipi_conto (codice, descrizione) VALUES ('BANCARIO', 'Conto corrente bancario');
INSERT INTO lk_tipi_conto (codice, descrizione) VALUES ('CASSA', 'Cassa contanti fisica');
INSERT INTO lk_tipi_conto (codice, descrizione) VALUES ('DIGITALE', 'Portafoglio digitale (Satispay, Stripe, Alveare, ecc.)');

INSERT INTO lk_tipi_evento (codice, descrizione) VALUES ('MATRIMONIO', 'Matrimonio e ricevimento nuziale');
INSERT INTO lk_tipi_evento (codice, descrizione) VALUES ('BANCHETTO_PRIVATO', 'Banchetto privato / compleanno / anniversario');
INSERT INTO lk_tipi_evento (codice, descrizione) VALUES ('AZIENDALE', 'Evento aziendale / team building');
INSERT INTO lk_tipi_evento (codice, descrizione) VALUES ('RISTORAZIONE_GRUPPO', 'Ristorazione per gruppi organizzati');
INSERT INTO lk_tipi_evento (codice, descrizione) VALUES ('ALTRO', 'Altro tipo di evento');

INSERT INTO lk_stati_evento (codice, descrizione) VALUES ('CONFERMATO', 'Evento confermato con caparra');
INSERT INTO lk_stati_evento (codice, descrizione) VALUES ('ANNULLATO', 'Evento annullato');
INSERT INTO lk_stati_evento (codice, descrizione) VALUES ('PREVENTIVATO', 'Preventivo in attesa di conferma');
INSERT INTO lk_stati_evento (codice, descrizione) VALUES ('SALDATO', 'Evento completamente saldato');

INSERT INTO lk_tipi_evento_mov (codice, descrizione) VALUES ('CAPARRA', 'Caparra confirmatoria / deposito iniziale');
INSERT INTO lk_tipi_evento_mov (codice, descrizione) VALUES ('ACCONTO', 'Acconto intermedio');
INSERT INTO lk_tipi_evento_mov (codice, descrizione) VALUES ('SALDO', 'Saldo finale a evento completato');
INSERT INTO lk_tipi_evento_mov (codice, descrizione) VALUES ('PENALE', 'Penale per annullamento evento');
INSERT INTO lk_tipi_evento_mov (codice, descrizione) VALUES ('RIMBORSO', 'Rimborso al cliente');

INSERT INTO lk_stati_movimento (codice, descrizione) VALUES ('REGISTRATO', 'Registrato (manuale o importato, da verificare)');
INSERT INTO lk_stati_movimento (codice, descrizione) VALUES ('ANNULLATO', 'Annullato / stornato');
INSERT INTO lk_stati_movimento (codice, descrizione) VALUES ('DA_LIQUIDARE', 'Movimento non ancora liquidato');
INSERT INTO lk_stati_movimento (codice, descrizione) VALUES ('ATTIVO', 'Attivo — movimento valido e liquidato');

INSERT INTO lk_fonti_movimento (codice, descrizione) VALUES ('MANUALE', 'Inserimento manuale da interfaccia');
INSERT INTO lk_fonti_movimento (codice, descrizione) VALUES ('IMPORT_BILLY', 'Importazione CSV da Billy (registratore cassa)');
INSERT INTO lk_fonti_movimento (codice, descrizione) VALUES ('IMPORT_BANCA', 'Importazione estratto conto bancario (BPM / CA)');
INSERT INTO lk_fonti_movimento (codice, descrizione) VALUES ('IMPORT_ALVEARE', 'Importazione da L''Alveare / Stripe (bonifico mensile)');
INSERT INTO lk_fonti_movimento (codice, descrizione) VALUES ('IMPORT_FATTURA', 'Importazione fattura da Sportello Cloud (SDI)');
INSERT INTO lk_fonti_movimento (codice, descrizione) VALUES ('RICORRENTE', 'Movimento generato da piano di spesa ricorrente');

INSERT INTO lk_stati_import (codice, descrizione) VALUES ('IN_CORSO', 'Importazione in elaborazione');
INSERT INTO lk_stati_import (codice, descrizione) VALUES ('COMPLETATO', 'Importazione completata con successo');
INSERT INTO lk_stati_import (codice, descrizione) VALUES ('ERRORE', 'Importazione terminata con errori critici');
INSERT INTO lk_stati_import (codice, descrizione) VALUES ('COMPLETATO_CON_AMBIGUITA', 'Importazione completata con movimenti ambigui da classificare');

INSERT INTO lk_match_types (codice, descrizione) VALUES ('CONTAINS', 'Il pattern è contenuto nella causale');
INSERT INTO lk_match_types (codice, descrizione) VALUES ('STARTS_WITH', 'La causale inizia con il pattern');
INSERT INTO lk_match_types (codice, descrizione) VALUES ('REGEX', 'Espressione regolare su causale');

-- ---------- BUSINESS UNITS ----------
INSERT INTO business_units (id, codice, nome, descrizione, colore_hex, is_active) VALUES (1, 'BU1', 'Ristorazione e Agriturismo', 'Pranzi, cene, ospitalità, B&B agriturismo', '#4CAF50', true);
INSERT INTO business_units (id, codice, nome, descrizione, colore_hex, is_active) VALUES (2, 'BU2', 'Cerimonie ed Eventi', 'Matrimoni, banchetti privati e aziendali – logica caparra/saldo', '#2196F3', true);
INSERT INTO business_units (id, codice, nome, descrizione, colore_hex, is_active) VALUES (3, 'BU3', 'Vendita Prodotti e Spaccio', 'Carne, salumi, ortofrutta, trasformati – Spaccio, Alveare, Shopify', '#FF9800', true);
INSERT INTO business_units (id, codice, nome, descrizione, colore_hex, is_active) VALUES (4, 'BU4', 'Manutenzione Verde', 'Manutenzione verde per privati, aziende e condomini', '#795548', true);
INSERT INTO business_units (id, codice, nome, descrizione, colore_hex, is_active) VALUES (5, 'BU5', 'Overhead', 'Costi generali: mutui, assicurazioni, ammortamenti', '#9E9E9E', true);

-- ---------- ALIQUOTE IVA ----------
INSERT INTO aliquote_iva (id, aliquota, descrizione, perc_indetraibilita, is_active) VALUES (1, 0.0, 'Esente / Fuori campo IVA (es. Satispay netto, Alveare netto)', 0.00, true);
INSERT INTO aliquote_iva (id, aliquota, descrizione, perc_indetraibilita, is_active) VALUES (2, 4.0, 'IVA 4% – Prodotti agricoli di base, ortofrutta', 0.00, true);
INSERT INTO aliquote_iva (id, aliquota, descrizione, perc_indetraibilita, is_active) VALUES (3, 10.0, 'IVA 10% – Carni, salumi, alimenti trasformati, ristorazione', 0.00, true);
INSERT INTO aliquote_iva (id, aliquota, descrizione, perc_indetraibilita, is_active) VALUES (4, 22.0, 'IVA 22% – Aliquota ordinaria (servizi, beni non food)', 0.00, true);
INSERT INTO aliquote_iva (id, aliquota, descrizione, perc_indetraibilita, is_active) VALUES (5, 5.0, 'IVA 5%', 0.00, true);

-- ---------- PIANO DEI CONTI COGE (ordinato per id: parent prima dei figli) ----------
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (1, '10', 'ATTIVITÀ PATRIMONIALI', 'ATTIVITA', false, NULL, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (2, '20', 'PASSIVITÀ E DEBITI', 'PASSIVITA', false, NULL, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (3, '30', 'RICAVI', 'RICAVO', false, NULL, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (4, '40', 'COSTI OPERATIVI', 'COSTO', false, NULL, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (5, '50', 'INVESTIMENTI (CAPEX)', 'COSTO', true, NULL, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (6, '10.01', 'Liquidità e disponibilità', 'ATTIVITA', false, 1, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (7, '10.01.001', 'Banca BPM – c/c operativo', 'ATTIVITA', false, 6, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (8, '10.01.002', 'Crédit Agricole – c/c operativo', 'ATTIVITA', false, 6, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (9, '10.01.003', 'Cassa contanti', 'ATTIVITA', false, 6, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (10, '10.01.004', 'Satispay – portafoglio digitale', 'ATTIVITA', false, 6, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (11, '10.01.005', 'Stripe / Alveare – portafoglio digitale', 'ATTIVITA', false, 6, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (12, '10.02', 'Crediti', 'ATTIVITA', false, 1, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (13, '10.02.001', 'Crediti verso clienti (fatture attive)', 'ATTIVITA', false, 12, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (14, '10.02.002', 'Crediti IVA a credito', 'ATTIVITA', false, 12, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (15, '20.01', 'Debiti finanziari – Mutui e finanziamenti', 'PASSIVITA', false, 2, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (16, '20.01.001', 'Rata mutuo ipotecario', 'PASSIVITA', false, 15, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (17, '20.01.002', 'Rata finanziamento Regione Lombardia', 'PASSIVITA', false, 15, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (18, '20.01.003', 'Rata ISMEA (dal 2027)', 'PASSIVITA', false, 15, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (19, '20.01.004', 'Rata Fidicomptur', 'PASSIVITA', false, 15, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (20, '20.01.005', 'Rata Merlo (leasing / finanziamento)', 'PASSIVITA', false, 15, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (21, '20.01.006', 'Rata Asconfidi (40k)', 'PASSIVITA', false, 15, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (22, '20.02', 'Debiti verso fornitori', 'PASSIVITA', false, 2, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (23, '20.02.001', 'Debiti verso fornitori materie prime', 'PASSIVITA', false, 22, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (24, '20.02.002', 'IVA a debito', 'PASSIVITA', false, 22, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (25, '20.02.003', 'F24 – imposte e contributi', 'PASSIVITA', false, 22, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (26, '30.01', 'Ricavi Ristorazione e Agriturismo', 'RICAVO', false, 3, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (27, '30.02', 'Ricavi Cerimonie ed Eventi', 'RICAVO', false, 3, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (28, '30.03', 'Ricavi Vendita Prodotti Spaccio', 'RICAVO', false, 3, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (29, '30.04', 'Ricavi Manutenzione Verde', 'RICAVO', false, 3, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (30, '30.01.001', 'Incassi ristorazione (Billy – cassa)', 'RICAVO', false, 26, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (31, '30.01.002', 'Incassi B&B / ospitalità', 'RICAVO', false, 26, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (32, '30.02.001', 'Caparre eventi', 'RICAVO', false, 27, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (33, '30.02.002', 'Saldi eventi', 'RICAVO', false, 27, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (34, '30.03.001', 'Vendita carni e salumi (IVA 10%)', 'RICAVO', false, 28, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (35, '30.03.002', 'Vendita ortofrutta e trasformati (IVA 4%)', 'RICAVO', false, 28, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (36, '30.03.003', 'Vendita Alveare / Shopify (netto commissioni)', 'RICAVO', false, 28, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (37, '30.04.001', 'Ricavi manutenzione verde', 'RICAVO', false, 29, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (38, '40.01', 'Manodopera e personale', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (39, '40.01.001', 'Costo aziendale – Carlo', 'COSTO', false, 38, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (40, '40.01.002', 'Costo aziendale – Max', 'COSTO', false, 38, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (41, '40.01.003', 'Costo aziendale – Alina', 'COSTO', false, 38, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (42, '40.01.004', 'Costo aziendale – Noemi', 'COSTO', false, 38, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (43, '40.01.005', 'Costo aziendale – Altri', 'COSTO', false, 38, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (44, '40.02', 'Costi bancari e commissioni POS', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (45, '40.02.001', 'Commissioni Nexi (POS Crédit Agricole)', 'COSTO', false, 44, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (46, '40.02.002', 'Spese tenuta conto bancario', 'COSTO', false, 44, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (47, '40.02.003', 'Commissioni POS Banco BPM (Numia)', 'COSTO', false, 44, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (48, '40.03', 'Utenze', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (49, '40.03.001', 'Utenza acqua', 'COSTO', false, 48, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (50, '40.03.002', 'Utenza GPL (gas propano liquido)', 'COSTO', false, 48, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (51, '40.04', 'Materie prime – Ristorazione e Spaccio', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (52, '40.04.001', 'Acquisti Pasticceria RM (dolci, dessert)', 'COSTO', false, 51, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (53, '40.04.002', 'Acquisti Pasini (verdure e ortaggi)', 'COSTO', false, 51, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (54, '40.04.003', 'Acquisti Orma (birra)', 'COSTO', false, 51, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (55, '40.04.004', 'Acquisti Gruppo Italiano Vini', 'COSTO', false, 51, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (56, '40.04.005', 'Acquisti Nicellini (vino)', 'COSTO', false, 51, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (57, '40.04.006', 'Acquisti Zeus (acqua minerale)', 'COSTO', false, 51, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (58, '40.04.007', 'Acquisti Ciocca (formaggi)', 'COSTO', false, 51, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (59, '40.04.008', 'Acquisti Sogegross (ingrosso generi alim.)', 'COSTO', false, 51, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (60, '40.04.009', 'Acquisti Val Mulini (farine, cerali)', 'COSTO', false, 51, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (61, '40.05', 'Assicurazioni', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (62, '40.05.001', 'Assicurazione immobile / fabbricato', 'COSTO', false, 61, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (63, '40.05.002', 'Assicurazione vita', 'COSTO', false, 61, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (64, '40.05.003', 'Assicurazione incendio', 'COSTO', false, 61, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (65, '40.05.004', 'Assicurazione Dacia (autoveicolo)', 'COSTO', false, 61, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (66, '40.05.005', 'Assicurazione sollevatore telescopico', 'COSTO', false, 61, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (67, '40.06', 'Carburanti, pedaggi e veicoli', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (68, '40.06.001', 'Telepass – pedaggi autostradali', 'COSTO', false, 67, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (69, '40.06.002', 'Carburante benzina', 'COSTO', false, 67, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (70, '40.06.003', 'Bollo auto – Dacia', 'COSTO', false, 67, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (71, '50.01', 'Attrezzature e macchinari', 'COSTO', true, 5, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (72, '50.01.001', 'Lavastoviglie professionale', 'COSTO', true, 71, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (73, '50.01.002', 'Lavapavimenti industriale', 'COSTO', true, 71, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (74, '50.01.003', 'Sedie e tavoli (arredo sala)', 'COSTO', true, 71, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (75, '50.01.004', 'Gerosa – macchine agricole', 'COSTO', true, 71, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (76, '50.01.005', 'Aiani – lavori idraulici (impianti)', 'COSTO', true, 71, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (77, '50.01.006', 'Eurosistem – impianti tecnologici', 'COSTO', true, 71, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (78, '50.01.007', 'Mallamace – forniture edili', 'COSTO', true, 71, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (79, '50.01.008', 'Scavi e movimento terra', 'COSTO', true, 71, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (80, '50.01.009', 'Piastrelle cucina – rivestimenti', 'COSTO', true, 71, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (81, '40.07', 'Contabilità, consulenze e paghe', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (82, '40.07.001', 'Coldiretti – assistenza tecnica e fiscale', 'COSTO', false, 81, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (83, '40.07.002', 'Torres – consulente esterno', 'COSTO', false, 81, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (84, '40.08', 'Contributi previdenziali', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (85, '40.08.001', 'Contributi previdenziali Amministratore', 'COSTO', false, 84, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (86, '40.08.002', 'Contributi previdenziali dipendenti', 'COSTO', false, 84, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (87, '40.09', 'Manutenzioni ordinarie', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (88, '40.09.001', 'Manutenzione ordinaria – FOC (contratto)', 'COSTO', false, 87, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (89, '40.09.002', 'Manutenzione ordinaria – interventi previsti', 'COSTO', false, 87, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (90, '40.09.003', 'Manutenzione ordinaria – Comedil Mangino', 'COSTO', false, 87, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (91, '40.09.004', 'Pulizie e sanificazione – Zep Italia / New Cleaning', 'COSTO', false, 87, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (92, '40.10', 'Compenso Amministratore', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (93, '40.10.001', 'Compenso amministratore – quota mensile', 'COSTO', false, 92, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (94, '40.11', 'Altri costi operativi', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (95, '40.11.001', 'Sonvico – forniture varie', 'COSTO', false, 94, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (96, '40.11.002', 'Bio – prodotti biologici / certificazioni', 'COSTO', false, 94, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (97, '40.11.003', 'New Cleaning – prodotti pulizia', 'COSTO', false, 94, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (98, '40.11.004', 'Commissioni mercato / Alveare', 'COSTO', false, 94, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (99, '40.11.005', 'Consorzio – quote associative', 'COSTO', false, 94, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (100, '40.12', 'Materie prime – Vendita Prodotti e Spaccio', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (101, '40.12.001', 'Materie prime spaccio – stime preventive', 'COSTO', false, 100, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (102, '40.12.002', 'Materie prime spaccio – fatture fornitore principale', 'COSTO', false, 100, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (103, '40.12.003', 'Fattoria Ginestra – acquisti prodotti agricoli', 'COSTO', false, 100, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (104, '40.04.010', 'Acquisti olio EVO', 'COSTO', false, 51, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (105, '10.03', 'Giroconti e trasferimenti interni', 'ATTIVITA', false, 1, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (106, '10.03.001', 'Giroconto da Crédit Agricole a Banco BPM', 'ATTIVITA', false, 105, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (107, '10.03.002', 'Giroconto da Banco BPM a Crédit Agricole', 'ATTIVITA', false, 105, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (108, '60', 'ONERI FINANZIARI', 'ONERE_FINANZIARIO', false, NULL, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (109, '60.01', 'Interessi passivi su finanziamenti', 'ONERE_FINANZIARIO', false, 108, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (110, '60.01.001', 'Interessi – mutuo ipotecario', 'ONERE_FINANZIARIO', false, 109, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (111, '60.01.002', 'Interessi – finanziamento Regione Lombardia', 'ONERE_FINANZIARIO', false, 109, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (112, '60.01.003', 'Interessi – ISMEA', 'ONERE_FINANZIARIO', false, 109, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (113, '60.01.004', 'Interessi – Fidicomptur', 'ONERE_FINANZIARIO', false, 109, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (114, '60.01.005', 'Interessi – Merlo (leasing)', 'ONERE_FINANZIARIO', false, 109, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (115, '60.01.006', 'Interessi – Asconfidi (40k)', 'ONERE_FINANZIARIO', false, 109, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (116, '70', 'IMPOSTE E TRIBUTI', 'IMPOSTA', false, NULL, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (117, '70.01', 'IRAP', 'IMPOSTA', false, 116, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (118, '70.02', 'IRPEF / IRES', 'IMPOSTA', false, 116, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (119, '70.01.001', 'IRAP corrente', 'IMPOSTA', false, 117, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (120, '70.02.001', 'IRPEF / IRES corrente', 'IMPOSTA', false, 118, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (121, '40.13', 'Costi diretti eventi', 'COSTO', false, 4, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (122, '40.13.001', 'Affitto sala / location eventi', 'COSTO', false, 121, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (123, '40.13.002', 'DJ e intrattenimento eventi', 'COSTO', false, 121, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (124, '40.13.003', 'Catering esterno eventi', 'COSTO', false, 121, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (125, '40.13.004', 'Torta eventi', 'COSTO', false, 121, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (126, '40.13.005', 'Altri costi diretti eventi', 'COSTO', false, 121, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (127, '40.13.006', 'Personale a ore eventi', 'COSTO', false, 121, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (128, '30.05', 'Contributi pubblici e PAC', 'RICAVO', false, 3, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (129, '30.05.001', 'Contributi pubblici / PAC / AGEA', 'RICAVO', false, 128, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (130, '39', 'Ricavi da classificare (transitori)', 'RICAVO', false, NULL, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (131, '39.99', 'Ricavi da classificare', 'RICAVO', false, 130, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (132, '39.99.999', 'Ricavi da classificare (transitorio)', 'RICAVO', false, 131, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (133, '49', 'Costi da classificare (transitori)', 'COSTO', false, NULL, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (134, '49.99', 'Costi da classificare', 'COSTO', false, 133, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (135, '49.99.999', 'Costi da classificare (transitorio)', 'COSTO', false, 134, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (136, '90', 'Partite patrimoniali e finanziarie', 'PASSIVITA', false, NULL, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (137, '90.01', 'Finanziamenti ricevuti', 'PASSIVITA', false, 136, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (138, '90.02', 'Versamenti soci', 'PASSIVITA', false, 136, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (139, '90.01.001', 'Finanziamenti ricevuti (erogazione)', 'PASSIVITA', false, 137, true);
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) VALUES (140, '90.02.001', 'Versamenti soci', 'PASSIVITA', false, 138, true);

-- ---------- CENTRI DI COSTO COAN ----------
INSERT INTO centri_di_costo_coan (id, codice, descrizione, business_unit_id, is_active) VALUES (1, 'CDC-BU1', 'Centro di costo – Ristorazione e Agriturismo', 1, true);
INSERT INTO centri_di_costo_coan (id, codice, descrizione, business_unit_id, is_active) VALUES (2, 'CDC-BU2', 'Centro di costo – Cerimonie ed Eventi', 2, true);
INSERT INTO centri_di_costo_coan (id, codice, descrizione, business_unit_id, is_active) VALUES (3, 'CDC-BU3', 'Centro di costo – Vendita Prodotti Spaccio', 3, true);
INSERT INTO centri_di_costo_coan (id, codice, descrizione, business_unit_id, is_active) VALUES (4, 'CDC-BU4', 'Centro di costo – Manutenzione Verde', 4, true);
INSERT INTO centri_di_costo_coan (id, codice, descrizione, business_unit_id, is_active) VALUES (5, 'CDC-BU5', 'Centro di costo – Overhead Generale', 5, true);

-- ---------- METODI DI PAGAMENTO ----------
INSERT INTO metodi_pagamento (id, codice, descrizione, is_active) VALUES (1, 'CONTANTI', 'Contanti (cassa fisica)', true);
INSERT INTO metodi_pagamento (id, codice, descrizione, is_active) VALUES (2, 'POS_BPM', 'POS Banco BPM – Numia (fisico)', true);
INSERT INTO metodi_pagamento (id, codice, descrizione, is_active) VALUES (3, 'POS_CA_NEXI', 'POS Crédit Agricole – Nexi NFC (smartphone)', true);
INSERT INTO metodi_pagamento (id, codice, descrizione, is_active) VALUES (4, 'SATISPAY', 'Satispay (accredito netto commissioni)', true);
INSERT INTO metodi_pagamento (id, codice, descrizione, is_active) VALUES (5, 'BONIFICO', 'Bonifico bancario', true);
INSERT INTO metodi_pagamento (id, codice, descrizione, is_active) VALUES (6, 'ALVEARE_STRIPE', 'L''Alveare / Stripe (bonifico mensile netto)', true);
INSERT INTO metodi_pagamento (id, codice, descrizione, is_active) VALUES (7, 'SHOPIFY_STRIPE', 'Shopify / Stripe (accrediti e-commerce)', true);
INSERT INTO metodi_pagamento (id, codice, descrizione, is_active) VALUES (8, 'F24', 'F24 – tributi e contributi', true);
INSERT INTO metodi_pagamento (id, codice, descrizione, is_active) VALUES (9, 'ASSEGNO', 'Assegno bancario', true);
INSERT INTO metodi_pagamento (id, codice, descrizione, is_active) VALUES (10, 'RID_SDDMANDAT', 'Addebito diretto SEPA (RID / SDD)', true);

-- ---------- CONTI BANCARI ----------
INSERT INTO conti_bancari (id, nome, tipo, iban, saldo_iniziale, data_saldo_iniziale, is_active) VALUES (1, 'Banco BPM – c/c operativo', 'BANCARIO', NULL, 0.00, '2024-01-01', true);
INSERT INTO conti_bancari (id, nome, tipo, iban, saldo_iniziale, data_saldo_iniziale, is_active) VALUES (2, 'Crédit Agricole – c/c operativo', 'BANCARIO', NULL, 0.00, '2024-01-01', true);
INSERT INTO conti_bancari (id, nome, tipo, iban, saldo_iniziale, data_saldo_iniziale, is_active) VALUES (3, 'Cassa contanti', 'CASSA', NULL, 0.00, '2024-01-01', true);
INSERT INTO conti_bancari (id, nome, tipo, iban, saldo_iniziale, data_saldo_iniziale, is_active) VALUES (4, 'Satispay – portafoglio digitale', 'DIGITALE', NULL, 0.00, '2024-01-01', true);
INSERT INTO conti_bancari (id, nome, tipo, iban, saldo_iniziale, data_saldo_iniziale, is_active) VALUES (5, 'Stripe / Alveare – portafoglio', 'DIGITALE', NULL, 0.00, '2024-01-01', true);

-- ---------- FORNITORI ----------
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('09c7f488-0c71-4f44-94a2-c49ae2b4d906', 'Pasticceria RM', 'Pasticceria RM', NULL, NULL, 52, 1, 'Dolci e dessert per ristorazione', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('5f2c3936-fe79-4241-84b0-5df7059382ec', 'Pasini Verdure', 'Pasini', NULL, NULL, 53, 1, 'Fornitore verdure e ortaggi freschi', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('e5986817-e651-4cd2-8ad9-299708e2ede2', 'Orma Birra', 'Orma', NULL, NULL, 54, 1, 'Fornitore birra artigianale', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('3af5985f-300c-4b15-80fc-8d9f1a3059ce', 'Gruppo Italiano Vini', 'GIV', NULL, NULL, 55, 1, 'Vini e bevande', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('c76ba5c6-0419-4e77-bbbe-546cdbc934f4', 'Nicellini Vini', 'Nicellini', NULL, NULL, 56, 1, 'Vino locale / regionale', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('ea0b0d10-407b-4444-9a08-625953f494c9', 'Zeus Acque Minerali', 'Zeus', NULL, NULL, 57, 1, 'Acque minerali per ristorazione', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('451c23c8-d206-45a7-a2b3-7cd80d4fe762', 'Ciocca Formaggi', 'Ciocca', NULL, NULL, 58, 1, 'Formaggi e latticini', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('103c3e68-3e3c-4a15-b365-4097ca869218', 'Sogegross', 'Sogegross', NULL, NULL, 59, 1, 'Ingrosso generi alimentari', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('d2163836-f4a6-4dd7-aa28-18ddc13a2635', 'Val Mulini', 'Val Mulini', NULL, NULL, 60, 1, 'Farine, cereali, pasta', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('9931091d-aef2-477c-8d90-751b383ecd26', 'Gerosa Macchine Agricole', 'Gerosa', NULL, NULL, 75, NULL, 'Macchinari e attrezzature agricole', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('4320ecd6-61a2-483e-8485-ebe0cd635b66', 'Aiani Impianti', 'Aiani', NULL, NULL, 76, NULL, 'Lavori idraulici e impianti', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('d852b0a5-9d94-4619-80e0-cd7a38d8910e', 'Eurosistem', 'Eurosistem', NULL, NULL, 77, NULL, 'Impianti tecnologici', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('ec930bcb-a6e1-4985-9c26-657591e83503', 'Mallamace Edilizia', 'Mallamace', NULL, NULL, 78, NULL, 'Forniture edili e materiali', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('82b8a86a-b8f3-4fd6-87c7-13a499f8eb28', 'Erogatore Acqua Locale', 'Acqua', NULL, NULL, 49, 5, 'Utenza idrica', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('577b3b1b-3c86-4e9c-ab0f-be3cfb2cd73b', 'Fornitore GPL', 'GPL', NULL, NULL, 50, 5, 'Gas propano liquido', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('00fa48c3-fb2f-45b9-bd2c-0ad3ad4d5b76', 'Telepass Italia', 'Telepass', NULL, NULL, 68, 5, 'Pedaggi autostradali', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('dfe164d2-4bfb-429f-b961-b43b2abdd6f8', 'Nexi Payments', 'Nexi', NULL, NULL, 45, 5, 'Commissioni POS Nexi / Crédit Agricole', '2026-05-16 14:35:45.995229+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('dfa2c5c7-3c75-4cc4-ac8a-acb5a94527ab', 'Lodetti Ivano', 'Lodetti', NULL, NULL, 43, 5, 'Pagamento manodopera/prestazione occasionale – 07/01/2026', '2026-05-16 14:35:46.339336+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('8b38dc2e-d871-4ce2-9a7a-5ab9337da3dc', 'Comedil Mangino s.r.l.', 'Comedil', NULL, NULL, 90, 5, 'Manutenzione edile – 07/01/2026', '2026-05-16 14:35:46.339336+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('3b5db2e8-d83d-4f2d-bd08-7310c793e11b', 'Zep Italia Srl', 'Zep Italia', NULL, NULL, 91, 5, 'Prodotti per pulizia e manutenzione – 07/01/2026', '2026-05-16 14:35:46.339336+00');
INSERT INTO fornitori (id, ragione_sociale, alias, piva, codice_sdi, coge_default_id, bu_default_id, note, created_at) VALUES ('027b8cce-c0d5-4750-a182-8902a0f5cf12', 'Fattoria Ginestra di Bettoni Adonis', 'Fattoria Ginestra', NULL, NULL, 103, 1, 'Acquisti prodotti agricoli per ristorazione/spaccio – 09/01/2026', '2026-05-16 14:35:46.339336+00');

-- ---------- FORNITORE ALIAS MATCHING ----------
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (1, '09c7f488-0c71-4f44-94a2-c49ae2b4d906', 'PASTICCERIA RM', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (2, '5f2c3936-fe79-4241-84b0-5df7059382ec', 'PASINI', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (3, 'e5986817-e651-4cd2-8ad9-299708e2ede2', 'ORMA BIRRA', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (4, '3af5985f-300c-4b15-80fc-8d9f1a3059ce', 'GRUPPO ITALIANO VI', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (5, 'c76ba5c6-0419-4e77-bbbe-546cdbc934f4', 'NICELLINI', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (6, 'ea0b0d10-407b-4444-9a08-625953f494c9', 'ZEUS', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (7, '451c23c8-d206-45a7-a2b3-7cd80d4fe762', 'CIOCCA', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (8, '103c3e68-3e3c-4a15-b365-4097ca869218', 'SOGEGROSS', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (9, 'd2163836-f4a6-4dd7-aa28-18ddc13a2635', 'VAL MULINI', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (10, '00fa48c3-fb2f-45b9-bd2c-0ad3ad4d5b76', 'TELEPASS', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (11, 'dfe164d2-4bfb-429f-b961-b43b2abdd6f8', 'NEXI', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (12, '577b3b1b-3c86-4e9c-ab0f-be3cfb2cd73b', 'GPL', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (13, '577b3b1b-3c86-4e9c-ab0f-be3cfb2cd73b', 'GAS PROPANO', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (14, 'dfa2c5c7-3c75-4cc4-ac8a-acb5a94527ab', 'LODETTI', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (15, '8b38dc2e-d871-4ce2-9a7a-5ab9337da3dc', 'COMEDIL MANGINO', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (16, '3b5db2e8-d83d-4f2d-bd08-7310c793e11b', 'ZEP ITALIA', 'CONTAINS');
INSERT INTO fornitore_alias_matching (id, fornitore_id, pattern, match_type) VALUES (17, '027b8cce-c0d5-4750-a182-8902a0f5cf12', 'FATTORIA GINESTRA', 'CONTAINS');

-- ---------- CONTROPARTI (rubrica) ----------
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('e9502ae1-7033-49a4-8e68-9241430ad82c', 'FORNITORE', 'PASTICCERIA RM', NULL, '09c7f488-0c71-4f44-94a2-c49ae2b4d906', 52, 1, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('9a343cf2-ab24-4548-8d66-bf6b8f8648ea', 'FORNITORE', 'PASINI VERDURE', NULL, '5f2c3936-fe79-4241-84b0-5df7059382ec', 53, 1, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('86a0bbc5-3889-4b43-b3be-723c95d00388', 'FORNITORE', 'ORMA BIRRA', NULL, 'e5986817-e651-4cd2-8ad9-299708e2ede2', 54, 1, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('8ae53fa1-ec94-4ff0-bb2f-13af0bbeb968', 'FORNITORE', 'GRUPPO ITALIANO VINI', NULL, '3af5985f-300c-4b15-80fc-8d9f1a3059ce', 55, 1, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('b9212d72-7c7e-4e26-ac92-0d6f4c370535', 'FORNITORE', 'NICELLINI VINI', NULL, 'c76ba5c6-0419-4e77-bbbe-546cdbc934f4', 56, 1, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('5e5b2ba9-dfbe-46ca-9022-610077a4f12a', 'FORNITORE', 'ZEUS ACQUE MINERALI', NULL, 'ea0b0d10-407b-4444-9a08-625953f494c9', 57, 1, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('2b08886b-4065-42e6-a67d-0545c08a2bf1', 'FORNITORE', 'CIOCCA FORMAGGI', NULL, '451c23c8-d206-45a7-a2b3-7cd80d4fe762', 58, 1, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('5ddd8a74-780c-4c4d-bb76-99af513990a2', 'FORNITORE', 'SOGEGROSS', NULL, '103c3e68-3e3c-4a15-b365-4097ca869218', 59, 1, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('1b443c68-8497-4787-8289-1a8d9c16a419', 'FORNITORE', 'VAL MULINI', NULL, 'd2163836-f4a6-4dd7-aa28-18ddc13a2635', 60, 1, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('f4d7bbad-b9db-4b90-9220-9793d9780839', 'FORNITORE', 'GEROSA MACCHINE AGRICOLE', NULL, '9931091d-aef2-477c-8d90-751b383ecd26', 75, NULL, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('030e37ed-c296-48f6-97ef-ce9b498e8c29', 'FORNITORE', 'AIANI IMPIANTI', NULL, '4320ecd6-61a2-483e-8485-ebe0cd635b66', 76, NULL, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('98cfddcf-c5ba-491b-83a2-5754a2f7dcce', 'FORNITORE', 'EUROSISTEM', NULL, 'd852b0a5-9d94-4619-80e0-cd7a38d8910e', 77, NULL, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('1eac6679-281b-4ef1-88fd-ca4233ded8d1', 'FORNITORE', 'MALLAMACE EDILIZIA', NULL, 'ec930bcb-a6e1-4985-9c26-657591e83503', 78, NULL, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('59f434a9-2a34-44d0-9033-1670dbde392f', 'FORNITORE', 'EROGATORE ACQUA LOCALE', NULL, '82b8a86a-b8f3-4fd6-87c7-13a499f8eb28', 49, 5, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('f2821059-d1ec-4dc2-a052-f735beeda1a1', 'FORNITORE', 'FORNITORE GPL', NULL, '577b3b1b-3c86-4e9c-ab0f-be3cfb2cd73b', 50, 5, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('d43b9e3c-6f93-4cf3-9c3f-58253dd31534', 'FORNITORE', 'TELEPASS ITALIA', NULL, '00fa48c3-fb2f-45b9-bd2c-0ad3ad4d5b76', 68, 5, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('919bbc3e-4bdc-4959-9cbf-10d2b6cc25d1', 'FORNITORE', 'NEXI PAYMENTS', NULL, 'dfe164d2-4bfb-429f-b961-b43b2abdd6f8', 45, 5, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('af2d015c-a178-4e34-ad4a-f291d9e644d2', 'FORNITORE', 'LODETTI IVANO', NULL, 'dfa2c5c7-3c75-4cc4-ac8a-acb5a94527ab', 43, 5, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('693d0a9f-ef43-4e2b-ab83-a835067b88fd', 'FORNITORE', 'COMEDIL MANGINO S.R.L.', NULL, '8b38dc2e-d871-4ce2-9a7a-5ab9337da3dc', 90, 5, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('82e5c1e7-c252-433d-bf09-9e6a6158f035', 'FORNITORE', 'ZEP ITALIA SRL', NULL, '3b5db2e8-d83d-4f2d-bd08-7310c793e11b', 91, 5, '2026-06-03 10:36:35.146934+00', NULL);
INSERT INTO controparti (id, tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id, created_at, updated_at) VALUES ('dec593bd-8046-4afb-9b80-916a2496c082', 'FORNITORE', 'FATTORIA GINESTRA DI BETTONI ADONIS', NULL, '027b8cce-c0d5-4750-a182-8902a0f5cf12', 103, 1, '2026-06-03 10:36:35.146934+00', NULL);

-- ---------- CATEGORIE ----------
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (1, 'Incassi cassa ristorazione (Billy)', 'ENTRATA', NULL, 1, 10, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (2, 'Pagamenti POS / carte', 'ENTRATA', NULL, 1, 20, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (3, 'Satispay ristorazione', 'ENTRATA', NULL, 1, 30, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (4, 'B&B / Ospitalità', 'ENTRATA', NULL, 1, 40, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (5, 'Degustazioni ed enoturismo', 'ENTRATA', NULL, 1, 50, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (6, 'Materie prime alimentari', 'USCITA', NULL, 1, 10, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (7, 'Bevande – vini, birre, acqua', 'USCITA', NULL, 1, 20, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (8, 'Dolci e dessert', 'USCITA', NULL, 1, 30, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (9, 'Forniture e materiali di consumo', 'USCITA', NULL, 1, 40, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (10, 'Manodopera cucina e sala', 'USCITA', NULL, 1, 50, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (11, 'Manutenzione e riparazioni', 'USCITA', NULL, 1, 60, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (12, 'Abbonamenti e servizi digitali', 'USCITA', NULL, 1, 70, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (13, 'Caparre eventi', 'ENTRATA', NULL, 2, 10, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (14, 'Acconti eventi', 'ENTRATA', NULL, 2, 20, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (15, 'Saldi eventi', 'ENTRATA', NULL, 2, 30, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (16, 'Rimborsi ricevuti', 'ENTRATA', NULL, 2, 40, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (17, 'Food & Beverage per eventi', 'USCITA', NULL, 2, 10, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (18, 'Allestimenti e noleggi', 'USCITA', NULL, 2, 20, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (19, 'Personale eventi (extra)', 'USCITA', NULL, 2, 30, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (20, 'Servizi fornitori (DJ, foto, fiori)', 'USCITA', NULL, 2, 40, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (21, 'Costi organizzativi evento', 'USCITA', NULL, 2, 50, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (22, 'Vendita spaccio – contanti/POS', 'ENTRATA', NULL, 3, 10, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (23, 'Incassi Alveare', 'ENTRATA', NULL, 3, 20, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (24, 'Incassi Shopify / online', 'ENTRATA', NULL, 3, 30, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (25, 'Satispay spaccio', 'ENTRATA', NULL, 3, 40, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (26, 'Acquisti carni e salumi', 'USCITA', NULL, 3, 10, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (27, 'Acquisti ortofrutta e trasformati', 'USCITA', NULL, 3, 20, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (28, 'Imballaggi e packaging', 'USCITA', NULL, 3, 30, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (29, 'Commissioni marketplace', 'USCITA', NULL, 3, 40, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (30, 'Ricavi manutenzione privati', 'ENTRATA', NULL, 4, 10, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (31, 'Ricavi manutenzione aziende', 'ENTRATA', NULL, 4, 20, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (32, 'Ricavi manutenzione condomini', 'ENTRATA', NULL, 4, 30, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (33, 'Materiali (piante, terriccio, concimi)', 'USCITA', NULL, 4, 10, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (34, 'Carburante attrezzature verde', 'USCITA', NULL, 4, 20, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (35, 'Manutenzione macchinari', 'USCITA', NULL, 4, 30, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (36, 'Smaltimento verde e rifiuti', 'USCITA', NULL, 4, 40, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (37, 'Mutui e rate finanziamenti', 'USCITA', NULL, 5, 10, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (38, 'Assicurazioni', 'USCITA', NULL, 5, 20, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (39, 'Utenze (acqua, gas, elettricità)', 'USCITA', NULL, 5, 30, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (40, 'Commercialista e consulenze', 'USCITA', NULL, 5, 40, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (41, 'Marketing e comunicazione', 'USCITA', NULL, 5, 50, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (42, 'Spese bancarie e commissioni POS', 'USCITA', NULL, 5, 60, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (43, 'Bollo auto e pedaggi', 'USCITA', NULL, 5, 70, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (44, 'Cancelleria e ufficio', 'USCITA', NULL, 5, 80, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (45, 'Imposte e tasse (F24)', 'USCITA', NULL, 5, 90, true);
INSERT INTO categorie (id, nome, tipo, parent_id, bu_id, ordinamento, is_active) VALUES (46, 'Spese generali non classificate', 'USCITA', NULL, 5, 100, true);

-- ---------- USERS ----------
INSERT INTO users (id, email, google_sub, full_name, role, is_active, last_login, created_at, updated_at, personale_id) VALUES ('00000000-0000-0000-0000-000000000099', 'test@agostinelli.internal', 'test-sub-integration-99', 'Test Integration User', 'ADMIN', true, NULL, '2026-05-16 14:35:46.701903+00', NULL, NULL);
INSERT INTO users (id, email, google_sub, full_name, role, is_active, last_login, created_at, updated_at, personale_id) VALUES ('14ff5893-fb29-43c0-b329-c33699523b5b', 'agostinelli.pietro1405@gmail.com', 'pending_google_sub_pietro', 'Pietro Agostinelli', 'ADMIN', true, NULL, '2026-05-17 07:57:25.931801+00', NULL, NULL);
INSERT INTO users (id, email, google_sub, full_name, role, is_active, last_login, created_at, updated_at, personale_id) VALUES ('742bb26e-49f6-44ec-84a4-4fa92517bdd8', 'simone.leone300900@gmail.com', '106343406661490436652', 'Simone Leone', 'ADMIN', true, '2026-06-06 12:51:00.336151+00', '2026-05-16 14:35:46.051103+00', '2026-06-06 12:51:00.250737+00', NULL);

-- ---------- REGOLE DI CLASSIFICAZIONE ----------
INSERT INTO regole_classificazione (id, priorita, sorgente, tipo_movimento, campo, match_type, pattern, azione, coge_codice, bu_id, metodo_codice, confidence, attivo, note, created_at) VALUES (1, 30, 'CA', '*', 'DESC_SPACED', 'IN_LIST', 'CANONE,ASSICURAZ,POLIZZA,MUTUO,LEASING,FINANZIAMENTO,BOLLO,ASCONFIDI', 'SKIP_RICORRENTE', NULL, NULL, NULL, 1.00, true, 'Spese ricorrenti/finanziamenti (§4 A3)', '2026-06-03 10:36:35.260527+00');
INSERT INTO regole_classificazione (id, priorita, sorgente, tipo_movimento, campo, match_type, pattern, azione, coge_codice, bu_id, metodo_codice, confidence, attivo, note, created_at) VALUES (2, 30, 'BPM', '*', 'DESC_SPACED', 'IN_LIST', 'CANONE,ASSICURAZ,POLIZZA,MUTUO,LEASING,FINANZIAMENTO,BOLLO,ASCONFIDI', 'SKIP_RICORRENTE', NULL, NULL, NULL, 1.00, true, 'Spese ricorrenti/finanziamenti (§4 A3)', '2026-06-03 10:36:35.260527+00');
INSERT INTO regole_classificazione (id, priorita, sorgente, tipo_movimento, campo, match_type, pattern, azione, coge_codice, bu_id, metodo_codice, confidence, attivo, note, created_at) VALUES (3, 400, 'CA', 'ENTRATA', 'DESC_SPACED', 'CONTAINS', 'ORGANISMO PAGATORE', 'MAP', '30.05.001', 1, NULL, 1.00, true, 'Contributo pubblico / PAC (§10.2 pri.400)', '2026-06-03 10:36:35.260527+00');
INSERT INTO regole_classificazione (id, priorita, sorgente, tipo_movimento, campo, match_type, pattern, azione, coge_codice, bu_id, metodo_codice, confidence, attivo, note, created_at) VALUES (4, 400, 'BPM', 'ENTRATA', 'DESC_SPACED', 'CONTAINS', 'VERSAMENTO SOCIO', 'MAP', '90.02.001', 5, NULL, 1.00, true, 'Apporto soci (§10.3 pri.400)', '2026-06-03 10:36:35.260527+00');

-- ============================================================
-- RIALLINEAMENTO SEQUENZE (tabelle con id generato)
-- ============================================================
SELECT setval(pg_get_serial_sequence('aliquote_iva','id'), COALESCE((SELECT MAX(id) FROM aliquote_iva), 1));
SELECT setval(pg_get_serial_sequence('piano_dei_conti_coge','id'), COALESCE((SELECT MAX(id) FROM piano_dei_conti_coge), 1));
SELECT setval(pg_get_serial_sequence('centri_di_costo_coan','id'), COALESCE((SELECT MAX(id) FROM centri_di_costo_coan), 1));
SELECT setval(pg_get_serial_sequence('metodi_pagamento','id'), COALESCE((SELECT MAX(id) FROM metodi_pagamento), 1));
SELECT setval(pg_get_serial_sequence('categorie','id'), COALESCE((SELECT MAX(id) FROM categorie), 1));
SELECT setval(pg_get_serial_sequence('fornitore_alias_matching','id'), COALESCE((SELECT MAX(id) FROM fornitore_alias_matching), 1));
SELECT setval(pg_get_serial_sequence('regole_classificazione','id'), COALESCE((SELECT MAX(id) FROM regole_classificazione), 1));

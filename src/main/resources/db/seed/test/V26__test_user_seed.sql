-- ============================================================
-- V26 - Utente tecnico con UUID fisso per gli integration test.
-- Rimosso da db/migration in V25; ri-creato qui solo per il
-- profilo test (%test.quarkus.flyway.locations include db/seed/test).
-- Usato come created_by in: MovimentiIntegrationTest, CassaIntegrationTest,
-- EventiIntegrationTest, SpeseRicorrentiIntegrationTest,
-- ForecastingIntegrationTest, ReportingIntegrationTest.
-- ============================================================

INSERT INTO users (id, email, google_sub, full_name, role)
VALUES (
    '00000000-0000-0000-0000-000000000099'::UUID,
    'test@agostinelli.internal',
    'test-sub-integration-99',
    'Test Integration User',
    'ADMIN'
)
ON CONFLICT (email) DO NOTHING;

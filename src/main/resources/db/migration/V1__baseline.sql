-- Baseline migration.
--
-- Establishes the Flyway schema history table on an empty database so that every
-- later migration has a recorded starting point. No application tables are created
-- here: the domain schema arrives with the features that need it.
--
--   V2  users        (Phase 2 - registration and login)
--   V3  accounts     (Phase 3 - wallets, including the @Version lock column)
--   V4  transactions, ledger_entries, system account (Phase 4 - money movement)
--
-- Flyway records this file as applied; it intentionally performs no DDL.

SELECT 1;

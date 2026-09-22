-- =====================================================================
-- V2__seed_skills.sql
--
-- Skills are REFERENCE DATA and therefore belong in a migration.
-- Questions and templates are CONTENT and do NOT belong here: they are
-- loaded through the admin service so they pass the same publish-gate
-- triggers that candidate-facing content does.
--
-- Phase 0 launches with three skills (docs/10-architecture-review.md A1 #3).
-- React, Angular, REST APIs, Microservices and System Design are added by
-- a later migration when their question banks are authored — adding a
-- skill is data, never a deploy of new code.
-- =====================================================================

INSERT INTO app.skills (code, name, description, sort_order) VALUES
  ('JAVA',        'Java',
   'Core language, collections, concurrency, JVM memory model.', 10),
  ('SPRING_BOOT', 'Spring Boot',
   'Dependency injection, configuration, data access, transactions, REST.', 20),
  ('SQL',         'SQL',
   'Schema design, joins, indexing, transactions and isolation levels.', 30)
ON CONFLICT (code) DO NOTHING;

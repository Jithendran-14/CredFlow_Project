-- Preserve historical imports; new importer always fills the hash and provenance fields.
ALTER TABLE financial_import_batch ADD (
 source_provider VARCHAR2(100) DEFAULT 'LEGACY' NOT NULL,
 source_data_version NUMBER(10) DEFAULT 1 NOT NULL CHECK(source_data_version > 0),
 input_hash VARCHAR2(64),
 source_retrieved_at TIMESTAMP WITH TIME ZONE
);
ALTER TABLE financial_import_batch ADD CONSTRAINT uq_fin_import_source
 UNIQUE(application_id,application_revision,as_of_date,source_provider,source_reference,source_data_version);

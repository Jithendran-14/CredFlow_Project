CREATE TABLE eligibility_operation (
 operation_id VARCHAR2(36) PRIMARY KEY,
 evaluation_request_id VARCHAR2(36) NOT NULL UNIQUE,
 application_id VARCHAR2(36) NOT NULL,
 application_revision NUMBER(10) NOT NULL CHECK(application_revision>0),
 verification_id VARCHAR2(36) NOT NULL,
 as_of_date DATE NOT NULL,
 retrieval_reference VARCHAR2(255 CHAR) NOT NULL,
 request_hash VARCHAR2(64) NOT NULL,
 caller_client_id VARCHAR2(100) NOT NULL,
 correlation_id VARCHAR2(100) NOT NULL,
 status VARCHAR2(20) DEFAULT 'PENDING' NOT NULL CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
 created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE,
 finished_at TIMESTAMP WITH TIME ZONE,
 eligibility_result_id VARCHAR2(36) REFERENCES eligibility_evaluation(eligibility_result_id),
 error_code VARCHAR2(100),
 attempts NUMBER(10) DEFAULT 0 NOT NULL CHECK(attempts>=0)
);
CREATE INDEX ix_eligibility_operation_pending ON eligibility_operation(status,created_at);

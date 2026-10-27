CREATE TABLE eligibility_completion (
 eligibility_result_id VARCHAR2(36) PRIMARY KEY
   REFERENCES eligibility_evaluation(eligibility_result_id),
 event_id VARCHAR2(36) NOT NULL UNIQUE REFERENCES event_outbox(event_id)
);

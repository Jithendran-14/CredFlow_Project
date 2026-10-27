Fresh installation only. Connect as SYSTEM to the application PDB and run 00_run_all_as_system.sql.
The four owners, 14 tables and two sequences are unchanged by the service split.
Existing matching schemas need no DROP or recreation. Each lender now validates its own schema from its own process.
The scripts do not execute automatically. Run the verification script and review every result.

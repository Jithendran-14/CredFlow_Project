# CredFlow: fresh Windows laptop setup

This guide creates a **new, independent local installation** from source. It does
not transfer the original laptop's users, seller history, loans, passwords, Kafka
records or signing keys. Source-only cloning does not copy Oracle data.

Use these instructions only when none of the eleven CredFlow schemas already
exist in the target `FREEPDB1`. For an existing installation, keep its private
configuration and follow the individual migrations instead. The helpers have no
overwrite, password-reset, database-drop or Kafka-reformat option.

## 1. Install prerequisites

Use Windows 10/11 x64 and install:

| Tool | Version / requirement |
| --- | --- |
| Git | Git for Windows |
| Java | JDK 25, matching the current configured installation; source modules target Java 21 |
| Apache Maven | Maven 3.9.x, available as `mvn` on PATH |
| Node.js | Node 24 or newer, available as `node` on PATH |
| Oracle Database | Oracle AI Database Free with `FREEPDB1`, listener port 1521 |
| SQL client | Oracle SQL*Plus (`sqlplus`) from the Oracle installation; SQL Developer is optional |
| Apache Kafka | Binary distribution `kafka_2.13-4.3.1`, extracted locally |

Download from official sources:

- Java: https://jdk.java.net/archive/
- Maven: https://maven.apache.org/download.cgi
- Node.js: https://nodejs.org/en/download
- Oracle Free: https://www.oracle.com/database/free/
- Kafka 4.3.1 archive: https://archive.apache.org/dist/kafka/4.3.1/
- Git: https://git-scm.com/downloads/win

The repository includes the Windows Mailpit executable and its license. Do not
install Keycloak or ZooKeeper for this setup. Kafka runs in KRaft mode.

Prefer short installation locations, such as `C:\jdk25`, `C:\kafka` and
`C:\innovation\CredFlow`. Paths containing spaces are supported by the helpers,
but Windows Kafka batch scripts can run into command-line-length limits.

In a new PowerShell terminal, adapt the locations to your machine:

```powershell
$env:JAVA_HOME = 'C:\jdk25'
$env:KAFKA_HOME = 'C:\kafka'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
mvn -version
node --version
git --version
```

Set `JAVA_HOME` and `KAFKA_HOME` in Windows Environment Variables if you want them
to persist. Open a new terminal after changing persistent settings. The startup
script honors both variables; it does not install either tool.

## 2. Obtain the project

```powershell
git clone https://github.com/Jithendran-14/CredFlow_Project.git C:\innovation\CredFlow
Set-Location C:\innovation\CredFlow
```

Use your actual checkout path for subsequent commands. Check that ports 1521,
8081-8087, 8090-8093, 5174-5176, 5178, 9092-9093, 1025 and 8025 are available for
the intended processes. The optional Java gateway on 8080 is not launched.

## 3. Prepare new configuration, passwords and RSA keys

Run once, **before creating any local private directory yourself**:

```powershell
node scripts/setup-local.mjs
```

This prepares the following only in the ignored `backend/runtime/private/`:

- Eleven service `.properties` files, with matching new schema passwords and
  scoped service-client secrets.
- `runtime.properties`, containing local provisioning credentials and account aliases.
- `auth-private.pem`: a newly generated 3072-bit RSA PKCS#8 private key.
- `auth-public.pem`: its matching X.509 SubjectPublicKeyInfo public key.
- `kafka.properties`, a single-node, loopback-only broker/controller configuration.
- `kafka-cluster.id`, a new cluster identity.
- `fresh-install.sql`, an ordered database installer containing the generated
  local schema passwords.
- Empty `kafka-data/` and `logs/kafka/` directories.

The helper validates its inputs before writing and **refuses if the private
directory already exists**, including an empty directory. It does not connect
to Oracle, format Kafka, download dependencies or start services.

Auth signs JWTs with the private key; other services use the matching public key.
Do not independently generate a different public key for each service.

Never commit the private folder, generated SQL, credentials or private PEM. The
public examples deliberately contain `CHANGE_ME`; actual generated configuration
must not contain that placeholder. Use restrictive Windows folder permissions
appropriate to your development account, and do not share the private folder.

## 4. Start Oracle and install fresh schemas

Start your Oracle Free database and listener using the Windows services installed
by Oracle. Connect to `FREEPDB1`, not `CDB$ROOT`, using a normal SYSTEM connection:

```powershell
sqlplus -L SYSTEM@//localhost:1521/FREEPDB1
```

Enter the SYSTEM password when prompted. Do not put it in a command, Git file or
terminal screenshot. At the `SQL>` prompt:

```sql
SHOW CON_NAME
SELECT USER FROM DUAL;
@"C:/innovation/CredFlow/backend/runtime/private/fresh-install.sql"
```

Replace that absolute path with your actual checkout path. Use SQL*Plus for this
assembled installer; its `WHENEVER SQLERROR` and final `EXIT` semantics matter.

The installer checks the session user, PDB and existing schema names before its
first CREATE USER. It refuses if any intended CredFlow schema already exists.
It creates these service owners:

```text
CF_AUTH                 CF_APPLICATION          CF_CONSENT
CF_ELIGIBILITY          CF_AUDIT                CF_ORCHESTRATION
CF_LENDER_INTEGRATION   CF_SIM_FINANCIAL        CF_SIM_BANK_A
CF_SIM_NBFC_B           CF_SIM_BANK_C
```

It then applies the repository's DDL in this order:

1. Auth, Application and Consent base tables from
   `database/member1-member3/member1-schema-reference.sql`, under the corresponding
   current schema.
2. Member 3 base tables and indexes from `02_create_tables_as_system.sql` and
   `03_create_indexes_as_system.sql`. Their CREATE USER script is not repeated.
3. Member 2 Eligibility V1-V8, Audit V1-V2 and mock-financial table DDL, under
   their corresponding owners. The separate guarded `install-fresh.sql` wrappers
   are not repeated.
4. `organization-catalog.sql`.
5. `seller-registration.sql`.
6. `seller-loan-requests.sql`.
7. `marketplace-business-history.sql`.
8. `offer-terms-documents.sql`.
9. `marketplace-management.sql`.
10. `scope-completion.sql`.
11. `phase0-alignment.sql`.
12. `phase0-completion/01_financial_permission_checkpoint.sql`.
13. `bank-c-tables.sql`.
14. `password-recovery.sql`.
15. `fresh-install/seed-organizations.sql` for Marketplace 1 and the three mock lenders.
16. `lender-management.sql` for the registry and routing lock.

Known standalone `EXIT` and container-switch statements are removed only in the
generated assembly so the session can complete all stages. Original SQL files
are preserved. SYSTEM creates the owner-qualified objects; no service gets DBA
or ANY privileges. The original base Consent reference already includes the
financial-provider mapping `UPDATED_AT` column.

**Do not run every SQL file indiscriminately.** The historical inbox recovery,
training repairs and test-fixture scripts are not part of a fresh installation.

Oracle DDL commits implicitly. A failure can leave a partially installed schema
even though SQL*Plus stops on the first error. Do not blindly rerun: inspect the
first error and the resulting schema, or restore a clean development database
before starting again. These helpers never drop schemas automatically.

At completion, the invalid-object query should return no rows. Also check as SYSTEM:

```sql
SELECT owner, object_name, object_type, status
FROM dba_objects
WHERE owner IN ('CF_AUTH','CF_APPLICATION','CF_CONSENT','CF_ELIGIBILITY',
 'CF_AUDIT','CF_ORCHESTRATION','CF_LENDER_INTEGRATION','CF_SIM_FINANCIAL',
 'CF_SIM_BANK_A','CF_SIM_NBFC_B','CF_SIM_BANK_C')
AND status <> 'VALID';
```

Database-only checks do not prove the whole loan workflow works.

## 5. Build Auth and provision local identities

An internet connection is needed for the first Maven dependency download.

```powershell
mvn -f backend/member1/auth-service/pom.xml -DskipTests clean package
node scripts/provision-local.mjs --fresh-install
```

The provisioning helper extracts the built Auth JAR's libraries under the ignored
private folder and runs the supplied Java helpers with their required JDBC and
BCrypt dependencies:

1. `SeedFull.java`: initial demo users and six scoped service clients.
2. `ProvisionPhase0.java`: Bank C officer and callback client.
3. `ProvisionApplicationCorrections.java`: scoped application-correction identity.
4. `ProvisionMarketplaceClients.java`: Marketplace 1's scoped submission identity.

It requires Auth to be stopped. After full success, a private marker prevents
accidental repeat invocation. If provisioning fails partway, review the error
before retrying; identity helpers are not a general password reset mechanism.

Initial human accounts are listed below. Read their newly generated passwords
locally in `backend/runtime/private/runtime.properties`; no fixed passwords are
published in this guide.

| Portal | Username | Password property |
| --- | --- | --- |
| Administrator | `integration.admin` | `account.admin.password` |
| Marketplace 1 | `integration.marketplace` | `account.marketplace.password` |
| Bank A | `integration.bank-a` | `account.bank-a.password` |
| NBFC B | `integration.nbfc-b` | `account.nbfc-b.password` |
| Bank C | `bank.c.officer` | `account.bank-c.password` |

`SeedFull.java` also creates two isolated legacy integration-test seller identities
and their fixture mapping. These are not complete marketplace business profiles.
For a normal demonstration, register a **new** seller through the UI instead of
using those identities or assuming the original laptop's `seller1001` exists.

## 6. Build the remaining services

```powershell
mvn -f backend/member1/application-service/pom.xml -DskipTests clean package
mvn -f backend/member1/consent-service/pom.xml -DskipTests clean package
mvn -f backend/member2/pom.xml -DskipTests clean package
mvn -f backend/member3/pom.xml -DskipTests clean package
```

These commands build the runnable JARs without requiring tests to pass before
the environment has been started. They do not establish test success. `build.cmd`
remains available for the existing build/check workflow after configuration.

## 7. Initialize Kafka once

With Kafka stopped and `JAVA_HOME` / `KAFKA_HOME` set:

```powershell
node scripts/setup-kafka.mjs --check
node scripts/setup-kafka.mjs --format
node scripts/setup-kafka.mjs --check
```

`--format` runs Kafka's storage tool with the generated cluster ID and
`--standalone`. It only accepts this checkout's empty private data directory,
refuses occupied broker/controller ports and refuses already formatted storage.
It does not start Kafka. No ZooKeeper is used.

The single-node settings use replication factor 1 and loopback listeners. They
are for local development, not a production cluster. Topic auto-creation is
enabled, with a default retention of 168 hours. Consumers must not be assumed to
read data forever.

Kafka initialization follows the official KRaft quick start:
https://kafka.apache.org/43/getting-started/quickstart/

## 8. Start and verify

Ensure Oracle remains running, then:

```powershell
.\start.cmd
.\check.cmd
```

The launcher starts Kafka, Mailpit, eleven Java processes and four Node portals.
It does not install Oracle or execute setup/migrations automatically.

| Component | Address |
| --- | --- |
| Marketplace | http://localhost:5174 |
| Seller | http://localhost:5175 |
| Lender (all lenders) | http://localhost:5176 |
| Administrator | http://localhost:5178 |
| Mailpit inbox | http://localhost:8025 |

Check the health output and logs under `backend/runtime/private/logs/`. Kafka's
diagnostic files are under `logs/kafka/`. Do not remove `kafka-data/` to fix a log
problem. `check.cmd` verifies health, not a complete business journey.

For a smoke demonstration:

1. Sign in as administrator and confirm organizations, lenders and service health.
2. Register a new fictional seller with Marketplace 1 from the Seller Portal.
3. Sign in as `integration.marketplace`; save the seller's latest three completed
   months, a simulated credit band, and the explicit business-review checkbox.
4. Sign in as the seller and request a modest loan for a supported purpose.
5. Grant operational consent and separate financial-data permission.
6. Inspect financial/eligibility results, then review the assigned lender submission.
7. Use the appropriate lender officer to ask a question or create an offer; answer
   or respond as the seller. Final qualification depends on the actual saved rules.

The inherited training products support their configured purposes; inspect them
in the corresponding lender portal before a demonstration. Newly onboarded lender
products are initially inactive until their own officer reviews and activates them.

To stop project processes while preserving records:

```powershell
.\stop.cmd
```

Oracle remains running. Node portal sessions end when their server restarts.

## 9. Troubleshooting

| Symptom | Check |
| --- | --- |
| `private already exists` | You are not in a fresh checkout. Preserve the existing folder; do not overwrite it. |
| ORA-12514 / ORA-12541 | Oracle listener and FREEPDB1 service availability. |
| ORA-01017 | Schema password must match the generated installer and service properties. |
| ORA-00942 / missing column | First failed DDL/migration and the intended owner/PDB. |
| Invalid PL/SQL package | Inspect `DBA_ERRORS` for `CF_ELIGIBILITY`; do not proceed with invalid packages. |
| Missing Java class during provisioning | Auth JAR must be built and include JDBC/BCrypt runtime libraries. |
| Key read or JWT signature error | All services must reference the matching generated key pair and expected issuer/audience. |
| Kafka storage not formatted | Run the fresh-format step once, only against the new empty directory. |
| `CHANGE_ME` in a service file | Use generated private files, not public examples as runtime configuration. |
| Seller loan POST gets 403 on 8081 | Seller tokens submit to Auth `/api/v1/seller/loan-requests`; a scoped marketplace worker calls Application. |
| Existing demo username is absent | This is an independent database, not a copy of the original laptop. |

The helper regression tests can be run without changing a database:

```powershell
node --test tests/setup-helpers.test.mjs
```

They validate isolated configuration generation and safety checks. They do not
certify an Oracle installation, compile PL/SQL in a fresh PDB or run the live loan
journey. Follow the existing `tests/README.md` for application verification.

## 10. Before pushing setup changes

Commit this guide, `scripts/setup-*.mjs`, `scripts/provision-local.mjs`, the new
sanitized examples, `database/fresh-install/seed-organizations.sql` and the helper
tests. Do not commit `backend/runtime/private/` or generated credentials/keys.

```powershell
git status --short
git check-ignore backend/runtime/private/auth-private.pem
git check-ignore backend/runtime/private/fresh-install.sql
```

Keep Oracle data and Kafka storage together when restoring an existing environment.
Do not replace a broker's storage independently while retaining its old consumer
inbox records. A fresh independent setup avoids that old-offset mismatch.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {sign,verify,createPrivateKey} from 'node:crypto';
import {prepareLocal,schemas,migrations} from '../scripts/setup-local.mjs';
import {parseProperties} from '../scripts/setup-common.mjs';
import {storageArguments,checkStorage} from '../scripts/setup-kafka.mjs';

const packageRoot=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const source=process.env.CREDFLOW_SETUP_TEST_SOURCE||packageRoot;
function fixture(t){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'credflow-fresh-setup-'));
 t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const files=[...Object.keys(schemas).map(service=>`backend/runtime/examples/${service}.properties.example`),
  'backend/runtime/examples/kafka.properties.example','database/member1-member3/member1-schema-reference.sql',
  'database/member1-member3/02_create_tables_as_system.sql','database/member1-member3/03_create_indexes_as_system.sql',
  ...migrations,
  ...['V1__event_delivery_tables.sql','V2__financial_storage.sql','V3__financial_summary_package.sql','V4__demo_product_rules.sql','V5__eligibility_results.sql','V6__financial_import_provenance.sql','V7__eligibility_completion_outbox.sql','V8__eligibility_operation_intake.sql'].map(f=>`backend/member2/database/manual/eligibility/${f}`),
  'backend/member2/database/manual/audit/V1__event_delivery_tables.sql','backend/member2/database/manual/audit/V2__audit_events.sql','backend/member2/database/manual/mock-financial/01-tables.sql'];
 for(const relative of files){
  const existing=fs.existsSync(path.join(packageRoot,relative))?path.join(packageRoot,relative):path.join(source,relative);
  const dest=path.join(root,relative);fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(existing,dest);
 }
 return root;
}
function settings(root,file){return parseProperties(fs.readFileSync(path.join(root,'backend/runtime/private',file),'utf8'));}
test('fresh setup generates matching RSA keys and independent schema/client credentials',t=>{
 const root=fixture(t),result=prepareLocal(root),runtime=settings(root,'runtime.properties');
 assert.equal(result.files.length,17);
 const privateDir=path.join(root,'backend/runtime/private');
 const privateKey=fs.readFileSync(path.join(privateDir,'auth-private.pem'),'utf8');
 const publicKey=fs.readFileSync(path.join(privateDir,'auth-public.pem'),'utf8');
 assert.match(privateKey,/BEGIN PRIVATE KEY/);assert.match(publicKey,/BEGIN PUBLIC KEY/);
 assert.equal(createPrivateKey(privateKey).asymmetricKeyDetails.modulusLength,3072);
 const bytes=Buffer.from('CredFlow test');assert.ok(verify('RSA-SHA256',bytes,publicKey,sign('RSA-SHA256',bytes,privateKey)));
 assert.equal(new Set(Object.keys(schemas).map(service=>runtime[`db.${service}.password`])).size,11);
 for(const service of Object.keys(schemas)){
  const config=settings(root,`${service}.properties`);
  assert.equal(config['spring.datasource.username'],schemas[service]);
  assert.equal(config['spring.datasource.password'],runtime[`db.${service}.password`]);
  for(const [key,value] of Object.entries(config)){
   assert.doesNotMatch(value,/CHANGE_ME/);
   if(key.endsWith('public-key-path')||key.endsWith('public-key-location'))assert.ok(value.includes('auth-public.pem'));
   if(key.endsWith('client-secret')){
    const paired=config[key.replace(/client-secret$/,'client-id')];
    const expected=paired||({consent:'consent-verification-service',eligibility:'eligibility-service'})[service];
    assert.equal(value,runtime[`client.${expected}.secret`]);
   }
  }
 }
 assert.equal(settings(root,'auth.properties')['credflow.marketplace-submission.enabled'],'true');
});
test('existing private directory is refused without changing a sentinel',t=>{
 const root=fixture(t),dir=path.join(root,'backend/runtime/private');fs.mkdirSync(dir);
 fs.writeFileSync(path.join(dir,'keep.txt'),'preserve');
 assert.throws(()=>prepareLocal(root),/already exists/);
 assert.deepEqual(fs.readdirSync(dir),['keep.txt']);assert.equal(fs.readFileSync(path.join(dir,'keep.txt'),'utf8'),'preserve');
});
test('missing input fails before creating private runtime files',t=>{
 const root=fixture(t);fs.unlinkSync(path.join(root,'database/phase0-alignment.sql'));
 assert.throws(()=>prepareLocal(root));assert.ok(!fs.existsSync(path.join(root,'backend/runtime/private')));
});
test('database assembly has all owners, preflight before DDL, and one final EXIT',t=>{
 const root=fixture(t);prepareLocal(root);
 const sql=fs.readFileSync(path.join(root,'backend/runtime/private/fresh-install.sql'),'utf8');
 assert.equal((sql.match(/^CREATE USER /gm)||[]).length,11);
 assert.ok(sql.indexOf('Fresh installation refused')<sql.indexOf('CREATE USER '));
 for(const owner of ['CF_AUTH','CF_APPLICATION','CF_CONSENT','CF_ELIGIBILITY','CF_AUDIT','CF_SIM_FINANCIAL'])assert.ok(sql.includes(`CURRENT_SCHEMA=${owner};`));
 for(const name of ['CF_AUTH_USERS','CF_APPLICATIONS','CF_CONSENTS'])assert.equal((sql.match(new RegExp(`CREATE TABLE ${name} \\(`,'g'))||[]).length,1);
 assert.equal((sql.match(/^EXIT\b/gm)||[]).length,1);
 assert.doesNotMatch(sql,/^ALTER SESSION SET CONTAINER/gm);
 assert.doesNotMatch(sql,/^\s*(?:DROP USER|CONNECT|HOST|SPOOL)\b/gim);
 assert.ok(sql.indexOf('Creating CF_CONSENT')<sql.indexOf('PROMPT Applying database/organization-catalog.sql'));
 assert.ok(sql.indexOf('seed-organizations.sql')<sql.indexOf('PROMPT Applying database/lender-management.sql'));
 assert.ok(sql.includes('FINANCIAL_PERMISSION_JSON'));assert.ok(sql.includes('APPLICATION_SERVICE'));
});
test('two independent checkouts get distinct key pairs, passwords and cluster IDs',t=>{
 const a=fixture(t),b=fixture(t);prepareLocal(a);prepareLocal(b);
 assert.notEqual(settings(a,'runtime.properties')['db.auth.password'],settings(b,'runtime.properties')['db.auth.password']);
 for(const file of ['auth-public.pem','kafka-cluster.id'])assert.notEqual(fs.readFileSync(path.join(a,'backend/runtime/private',file),'utf8'),fs.readFileSync(path.join(b,'backend/runtime/private',file),'utf8'));
});
test('Kafka storage checks stay inside this checkout and recognize formatted storage',t=>{
 const root=fixture(t);prepareLocal(root);let state=checkStorage(root);assert.equal(state.initialized,false);
 fs.writeFileSync(path.join(state.data,'meta.properties'),'cluster.id=test');assert.equal(checkStorage(root).initialized,true);
 const args=storageArguments('C:/Kafka folder',state.config,'abcdefghijklmnopqrstuv');
 assert.ok(args.includes('--standalone'));assert.ok(args.includes('kafka.tools.StorageTool'));
 assert.equal(args[args.indexOf('-c')+1],state.config);
 assert.throws(()=>storageArguments('C:/kafka',state.config,'not-a-cluster-id'),/Invalid/);
 fs.appendFileSync(state.config,'\nlog.dirs=C:/unrelated/data\n');assert.throws(()=>checkStorage(root),/must be this checkout/);
});
test('a separate Kafka metadata directory is not silently formatted',t=>{
 const root=fixture(t);prepareLocal(root);const state=checkStorage(root);
 fs.appendFileSync(state.config,'\nmetadata.log.dir=C:/other/metadata\n');assert.throws(()=>checkStorage(root),/unsupported/);
});

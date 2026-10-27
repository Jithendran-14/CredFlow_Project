import test from 'node:test';
import assert from 'node:assert/strict';
import { LiveApi, ApiError } from '../js/api.js';
import { allowed, createServer, verifyIdentity, matchesPortal, roleAllowed } from '../../backend/web/server.mjs';

test('routing priorities are admin-only while eligibility rules stay lender-owned',()=>{
 const id='10000000-0000-4000-8000-000000000001';
 assert.equal(roleAllowed('ADMIN','eligibility','GET','/api/v1/admin/routing-priorities'),true);
 assert.equal(roleAllowed('ADMIN','eligibility','PUT',`/api/v1/admin/routing-priorities/${id}`),true);
 for(const role of ['LENDER_OFFICER','SELLER','MARKETPLACE']){
  assert.equal(roleAllowed(role,'eligibility','GET','/api/v1/admin/routing-priorities'),false);
  assert.equal(roleAllowed(role,'eligibility','PUT',`/api/v1/admin/routing-priorities/${id}`),false);
 }
 assert.equal(roleAllowed('ADMIN','eligibility','PUT',`/api/v1/lender-products/${id}/rules`),false);
 assert.equal(allowed('eligibility','PUT',`/api/v1/admin/routing-priorities/${id}`),true);
});

test('offer acceptance uses expected version and unchanged retries reuse their UUID key',async()=>{
 const calls=[];let fail=true;
 const client=new LiveApi({fetcher:async(url,options)=>{calls.push({url,options});if(fail)throw Error('timeout');return new Response(JSON.stringify({status:'ACCEPTED'}),{status:200});}});
 client.setTokens({orchestration:'test-token',integration:''});
 const offer={offerId:'10000000-0000-4000-8000-000000000201',version:4};
 await assert.rejects(client.offerAction(offer,'acceptance'),ApiError);fail=false;await client.offerAction(offer,'acceptance');
 assert.equal(calls[0].options.headers['Idempotency-Key'],calls[1].options.headers['Idempotency-Key']);
 assert.deepEqual(JSON.parse(calls[1].options.body),{expectedOfferVersion:4});
 assert.match(calls[0].url,/\/api\/v1\/offers\/.*\/acceptance$/);
 assert.equal(calls[0].options.headers.Authorization,'Bearer test-token');
});
test('information response carries current version and exact answer field names',async()=>{
 let request;
 const client=new LiveApi({fetcher:async(url,options)=>{request={url,options};return new Response('{"operationId":"operation"}',{status:202});}});
 client.setTokens({orchestration:'test-token',integration:''});
 await client.answer({informationRequestId:'10000000-0000-4000-8000-000000000301',version:3},[{questionId:'q1',answerText:'Inventory'}],['synthetic://seller/demo']);
 assert.deepEqual(JSON.parse(request.options.body),{expectedInformationVersion:3,answers:[{questionId:'q1',answerText:'Inventory'}],syntheticDocumentReferences:['synthetic://seller/demo']});
});
test('lender command uses Integration audience and errors preserve correlation',async()=>{
 let request;
 const client=new LiveApi({fetcher:async(url,options)=>{request={url,options};return new Response('{"code":"CONFLICT","message":"Changed","correlationId":"trace-1"}',{status:409});}});
 client.setTokens({orchestration:'orch',integration:'int'});
 await assert.rejects(client.officer('10000000-0000-4000-8000-000000000101','decisions',{decision:'REJECTED'}),error=>error.status===409&&error.correlationId==='trace-1');
 assert.match(request.url,/proxy\/integration\//);assert.equal(request.options.headers.Authorization,'Bearer int');
});
test('private service, callback, simulator and cross-service routes are excluded',()=>{
 const id='10000000-0000-4000-8000-000000000001';
 assert.equal(allowed('orchestration','GET',`/api/v1/applications/${id}/workflow`),true);
 assert.equal(allowed('integration','POST',`/api/v1/submissions/${id}/offers`),true);
 assert.equal(allowed('orchestration','POST',`/api/v1/internal/submissions/${id}`),false);
 assert.equal(allowed('integration','POST',`/api/v1/lenders/${id}/callbacks`),false);
 assert.equal(allowed('orchestration','POST',`/api/v1/submissions/${id}/offers`),false);
 assert.equal(allowed('integration','GET','/mock/lenders/banka'),false);
});
test('local server serves public assets only and requires tokens for proxied calls',async()=>{
 const server=createServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const url=`http://127.0.0.1:${server.address().port}`;
 try {
  assert.equal((await fetch(url)).status,200);
  assert.equal((await fetch(`${url}/server.mjs`)).status,404);
  assert.equal((await fetch(`${url}/README.md`)).status,404);
  assert.equal((await fetch(`${url}/api/proxy/orchestration/api/v1/submissions`)).status,401);
  assert.equal((await fetch(`${url}/api/proxy/orchestration/api/v1/internal/deliveries`)).status,404);
 }finally{await new Promise(resolve=>server.close(resolve));}
});

import {generateKeyPairSync,sign} from 'node:crypto';
import {portals} from '../../backend/web/portals.mjs';
const pair=generateKeyPairSync('rsa',{modulusLength:2048});
function token(claims={}) {const h=Buffer.from(JSON.stringify({alg:'RS256'})).toString('base64url');const p=Buffer.from(JSON.stringify({iss:'credflow-auth',aud:['credflow-api'],sub:'user',exp:Date.now()/1000+60,roles:['SELLER'],...claims})).toString('base64url');return h+'.'+p+'.'+sign('RSA-SHA256',Buffer.from(h+'.'+p),pair.privateKey).toString('base64url');}
test('signature, issuer, audience and expiry are checked before role trust',()=>{
 assert.equal(verifyIdentity(token(),pair.publicKey).sub,'user');
 for(const claims of [{iss:'untrusted'},{aud:['other']},{exp:1},{nbf:Date.now()/1000+600}])assert.throws(()=>verifyIdentity(token(claims),pair.publicKey));
 const valid=token();assert.throws(()=>verifyIdentity(valid.slice(0,-5)+'xxxxx',pair.publicKey));
});
test('all lenders share one portal and other roles are rejected',()=>{
 const portal=portals.find(p=>p.id==='lender');assert.equal(portal.port,5176);assert.equal(portals.filter(p=>p.role==='LENDER_OFFICER').length,1);
 for(const lenderId of ['bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb3','12345678-1234-4234-8234-123456789012'])assert(matchesPortal({roles:['LENDER_OFFICER'],lenderId},portal));
 assert.equal(matchesPortal({roles:['SELLER']},portal),false);assert.equal(matchesPortal({roles:['LENDER_OFFICER']},portal),false);
});

test('role matrix denies seller creation, marketplace commands and admin mutations',()=>{
 assert.equal(roleAllowed('SELLER','application','POST','/api/v1/applications'),false);
 assert.equal(roleAllowed('MARKETPLACE','integration','POST','/api/v1/submissions/a/offers'),false);
 assert.equal(roleAllowed('ADMIN','orchestration','POST','/api/v1/offers/a/acceptance'),false);
 assert.equal(roleAllowed('LENDER_OFFICER','consent','GET','/api/v1/applications/a/consents'),false);
 assert.equal(roleAllowed('SELLER','auth','POST','/api/v1/auth/register-seller'),true);
 assert.equal(roleAllowed('MARKETPLACE','auth','POST','/api/v1/auth/register-seller'),false);
});
test('seller loan requests are separate from marketplace application creation',()=>{
 assert.equal(allowed('auth','POST','/api/v1/seller/loan-requests'),true);
 assert.equal(roleAllowed('SELLER','auth','POST','/api/v1/seller/loan-requests'),true);
 for(const role of ['MARKETPLACE','ADMIN','LENDER_OFFICER'])assert.equal(roleAllowed(role,'auth','POST','/api/v1/seller/loan-requests'),false);
 assert.equal(roleAllowed('SELLER','application','POST','/api/v1/applications'),false);
 assert.equal(roleAllowed('SELLER','auth','GET','/api/v1/marketplace/loan-requests'),false);
});
test('forged or wrong-portal tokens cannot reach upstream APIs',async()=>{
 const server=createServer({portal:portals[3],publicKey:pair.publicKey});await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const base=`http://127.0.0.1:${server.address().port}`;
 try{const url=base+'/api/proxy/orchestration/api/v1/submissions';
 assert.equal((await fetch(url,{headers:{Authorization:'Bearer forged'}})).status,401);
 assert.equal((await fetch(url,{headers:{Authorization:'Bearer '+token({roles:['LENDER_OFFICER'],lenderId:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1'})}})).status,403);
 assert.equal((await fetch(base+'/api/health',{headers:{Authorization:'Bearer '+token({roles:['LENDER_OFFICER'],lenderId:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2'})}})).status,403);
 }finally{await new Promise(r=>server.close(r));}
});

// Currency and workflow presentation regressions.
import { formatMoney, withApplicationStatuses } from '../js/presentation.js';
test('rupee formatting preserves Indian grouping and exact paisa without encoding artifacts',()=>{
 assert.equal(formatMoney('100000'),'\u20b91,00,000.00');
 assert.equal(formatMoney('100000.88'),'\u20b91,00,000.88');
 assert.equal(formatMoney('9007199254740993.01'),'\u20b99,00,71,99,25,47,40,993.01');
 assert.equal(formatMoney(null),'\u2014');assert.equal(formatMoney('12.345'),'\u2014');
});
test('submitted request displays the current application lifecycle, including cancellation',async()=>{
 const rows=[{requestId:'r1',applicationId:'a1',status:'SUBMITTED',requestedAmount:'100000.00'}];
 let state='UNDER_REVIEW';const read=async id=>({applicationId:id,status:state});
 let result=await withApplicationStatuses(rows,read);assert.equal(result[0].applicationStatus,'UNDER_REVIEW');
 state='CANCELLED';result=await withApplicationStatuses(rows,read);assert.equal(result[0].applicationStatus,'CANCELLED');assert.equal(result[0].status,'SUBMITTED');assert.equal(rows[0].applicationStatus,undefined);
});
test('failed status lookup never presents stale submitted state as the current application status',async()=>{
 const rows=[{applicationId:'a1',status:'SUBMITTED'},{applicationId:'a2',status:'SUBMITTED'},{status:'QUEUED'}];let calls=0;
 const result=await withApplicationStatuses(rows,async id=>{calls++;if(id==='a1')throw Error('unavailable');return {applicationId:id,status:'OFFER_ACCEPTED'};});
 assert.equal(calls,2);assert.equal(result[0].applicationStatus,'UNAVAILABLE');assert.equal(result[1].applicationStatus,'OFFER_ACCEPTED');assert.equal(result[2].status,'QUEUED');assert.equal(result[2].applicationStatus,undefined);
});
test('mismatched application response cannot overwrite a request status',async()=>{
 const result=await withApplicationStatuses([{applicationId:'own',status:'SUBMITTED'}],async()=>({applicationId:'other',status:'CANCELLED'}));assert.equal(result[0].applicationStatus,'UNAVAILABLE');
});

test('marketplace staff can manage scoped history but cannot manually create applications',()=>{
 const path='/api/v1/marketplace/sellers/33333333-3333-4333-8333-333333333333/business-history';
 assert.equal(roleAllowed('MARKETPLACE','application','POST','/api/v1/applications'),false);
 for(const method of ['GET','POST']){assert(allowed('auth',method,path));assert(roleAllowed('MARKETPLACE','auth',method,path));for(const role of ['SELLER','ADMIN','LENDER_OFFICER'])assert.equal(roleAllowed(role,'auth',method,path),false);}
});

test('lender rule routes belong to lender officers, never admin or other roles',()=>{for(const [method,path] of [['GET','/api/v1/lender-products'],['GET','/api/v1/lender-products/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1/rules/history'],['PUT','/api/v1/lender-products/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1/rules']]){assert(allowed('eligibility',method,path));assert(roleAllowed('LENDER_OFFICER','eligibility',method,path));for(const role of ['ADMIN','SELLER','MARKETPLACE'])assert.equal(roleAllowed(role,'eligibility',method,path),false);}});

test('password recovery is public in every portal and uses safe errors',async()=>{for(const role of ['SELLER','MARKETPLACE','LENDER_OFFICER','ADMIN'])for(const route of ['forgot-password','reset-password']){const p='/api/v1/auth/'+route;assert(allowed('auth','POST',p));assert(roleAllowed(role,'auth','POST',p));assert(!allowed('auth','GET',p));}let captured;const api=new LiveApi({fetcher:async(url,init)=>{captured=init;return{ok:false,status:429,headers:new Headers(),json:async()=>({code:'RESET_RATE_LIMITED',message:'Wait 15 minutes.'})};}});await assert.rejects(()=>api.recover('forgot-password',{username:'seller'}),e=>e.status===429&&e.message==='Wait 15 minutes.');assert.equal(captured.headers.Authorization,undefined);});

test('lender creation routes are admin only',()=>{
 for(const method of ['GET','POST']){assert(allowed('auth',method,'/api/v1/admin/lenders'));assert(roleAllowed('ADMIN','auth',method,'/api/v1/admin/lenders'));for(const role of ['SELLER','MARKETPLACE','LENDER_OFFICER'])assert.equal(roleAllowed(role,'auth',method,'/api/v1/admin/lenders'),false);}
});

test('marketplace can answer lender questions but cannot operate seller offers or lender rules',()=>{
 const id='10000000-0000-4000-8000-000000000001';
 assert.equal(roleAllowed('MARKETPLACE','orchestration','GET',`/api/v1/applications/${id}/information-requests`),true);
 assert.equal(roleAllowed('MARKETPLACE','orchestration','POST',`/api/v1/information-requests/${id}/responses`),true);
 assert.equal(roleAllowed('MARKETPLACE','orchestration','POST',`/api/v1/offers/${id}/acceptance`),false);
 assert.equal(roleAllowed('ADMIN','eligibility','PUT',`/api/v1/lender-products/${id}/rules`),false);
});


test('password recovery preserves unknown-account errors',async()=>{const api=new LiveApi({fetcher:async()=>({ok:false,status:404,headers:new Headers(),json:async()=>({code:'RESET_USER_NOT_FOUND',message:'User does not exist. Check your username and try again.'})})});await assert.rejects(()=>api.recover('forgot-password',{username:'missing.user'}),e=>e.status===404&&e.code==='RESET_USER_NOT_FOUND'&&e.message.includes('User does not exist'));});

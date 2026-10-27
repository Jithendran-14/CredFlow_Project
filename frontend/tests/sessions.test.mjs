import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {generateKeyPairSync,sign} from 'node:crypto';
import {createServer} from '../../backend/web/server.mjs';
import {portals} from '../../backend/web/portals.mjs';
import {LiveApi} from '../js/api.js';

const pair=generateKeyPairSync('rsa',{modulusLength:2048});
function signed(role,extra={}){
 const now=Math.floor(Date.now()/1000),header=Buffer.from(JSON.stringify({alg:'RS256'})).toString('base64url');
 const payload=Buffer.from(JSON.stringify({iss:'credflow-auth',aud:['credflow-api'],sub:'user-1',username:'officer',roles:[role],iat:now,exp:now+3600,...extra})).toString('base64url');
 return `${header}.${payload}.${sign('RSA-SHA256',Buffer.from(header+'.'+payload),pair.privateKey).toString('base64url')}`;
}
async function fixture(portal=portals.find(p=>p.id==='lender')){
 const state={token:signed(portal.role,portal.role==='LENDER_OFFICER'?{lenderId:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1'}:{}),calls:[],now:Date.now()};
 const upstream=http.createServer(async(req,res)=>{const chunks=[];for await(const chunk of req)chunks.push(chunk);state.calls.push({url:req.url,authorization:req.headers.authorization,cookie:req.headers.cookie});res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(req.url.endsWith('/login')?{accessToken:state.token}:{ok:true}));});
 await new Promise(r=>upstream.listen(0,'127.0.0.1',r));
 const target=`http://127.0.0.1:${upstream.address().port}`;
 const server=createServer({portal,publicKey:pair.publicKey,serviceTargets:{auth:target,orchestration:target},sessionClock:()=>state.now});await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const base=`http://127.0.0.1:${server.address().port}`;
 return {state,base,portal,async login(cookie){return fetch(base+'/api/proxy/auth/api/v1/auth/login',{method:'POST',headers:{Origin:base,'Content-Type':'application/json','X-CredFlow-Session':'cookie',...(cookie?{Cookie:cookie}:{})},body:JSON.stringify({username:'officer',password:'local-test'})});},async close(){await new Promise(r=>server.close(r));await new Promise(r=>upstream.close(r));}};
}
const cookieOf=response=>response.headers.get('set-cookie').split(';')[0];

test('cookie login keeps tokens server-side and restores each portal after refresh',async()=>{
 for(const portal of portals){const f=await fixture(portal);try{
  const login=await f.login();assert.equal(login.status,200);const session=await login.json(),cookie=cookieOf(login),attributes=login.headers.get('set-cookie');
  assert.equal(session.role,portal.role);assert.equal(session.userId,'user-1');assert(!('accessToken' in session));assert(!cookie.includes(f.state.token));assert.match(attributes,/HttpOnly/);assert.match(attributes,/SameSite=Strict/);assert.match(attributes,/Path=\/api/);assert(!attributes.includes('Domain='));assert(Number(attributes.match(/Max-Age=(\d+)/)[1])<=900);
  const restored=await fetch(f.base+'/api/session',{headers:{Cookie:cookie}});assert.equal(restored.status,200);assert.deepEqual(await restored.json(),session);assert.equal(restored.headers.get('set-cookie'),null);
 }finally{await f.close();}}
});

test('refresh does not extend the fixed fifteen-minute limit',async()=>{
 const f=await fixture();try{
  const login=await f.login(),cookie=cookieOf(login),session=await login.json();
  f.state.now+=5*60*1000;const restored=await fetch(f.base+'/api/session',{headers:{Cookie:cookie}});assert.equal(restored.status,200);assert.equal((await restored.json()).expiresAt,session.expiresAt);assert.equal(restored.headers.get('set-cookie'),null);
  f.state.now=session.expiresAt+1;const expired=await fetch(f.base+'/api/session',{headers:{Cookie:cookie}});assert.equal(expired.status,401);assert.match(expired.headers.get('set-cookie'),/Max-Age=0/);
 }finally{await f.close();}
});

test('the proxy supplies the stored token without forwarding browser cookies',async()=>{
 const f=await fixture();try{const cookie=cookieOf(await f.login());const response=await fetch(f.base+'/api/proxy/auth/api/v1/account/profile',{headers:{Cookie:cookie,'X-CredFlow-User':'user-1'}});assert.equal(response.status,200);assert.equal(f.state.calls.at(-1).authorization,'Bearer '+f.state.token);assert.equal(f.state.calls.at(-1).cookie,undefined);}finally{await f.close();}
});

test('cookie-authenticated writes require the exact portal origin',async()=>{
 const f=await fixture(portals.find(p=>p.id==='seller'));try{const cookie=cookieOf(await f.login()),count=f.state.calls.length,url=f.base+'/api/proxy/auth/api/v1/seller/loan-requests';
  for(const origin of [undefined,'http://localhost:9999','null']){const response=await fetch(url,{method:'POST',headers:{Cookie:cookie,...(origin?{Origin:origin}:{}),'Content-Type':'application/json'},body:'{}'});assert.equal(response.status,403);}
  assert.equal(f.state.calls.length,count);assert.equal((await fetch(url,{method:'POST',headers:{Cookie:cookie,Origin:f.base,'Content-Type':'application/json'},body:'{}'})).status,200);
 }finally{await f.close();}
});

test('logout requires same origin and revokes a copied session cookie immediately',async()=>{
 const f=await fixture();try{const cookie=cookieOf(await f.login()),url=f.base+'/api/session/logout';
  assert.equal((await fetch(url,{method:'POST',headers:{Cookie:cookie,Origin:'http://localhost:9999'}})).status,403);
  const response=await fetch(url,{method:'POST',headers:{Cookie:cookie,Origin:f.base,'X-CredFlow-User':'user-1'}});assert.equal(response.status,200);assert.match(response.headers.get('set-cookie'),/Max-Age=0/);
  assert.equal((await fetch(f.base+'/api/session',{headers:{Cookie:cookie}})).status,401);
 }finally{await f.close();}
});

test('forged cookies and sessions copied between portals are rejected',async()=>{
 const lender=await fixture(),seller=await fixture(portals.find(p=>p.id==='seller'));try{const cookie=cookieOf(await lender.login());
  assert.equal((await fetch(lender.base+'/api/session',{headers:{Cookie:'credflow_session_lender=forged'}})).status,401);
  assert.equal((await fetch(seller.base+'/api/session',{headers:{Cookie:cookie}})).status,401);
  lender.state.token=signed('ADMIN');assert.equal((await lender.login()).status,403);
 }finally{await lender.close();await seller.close();}
});

test('signing in again revokes the previous browser session and rejects stale-account actions',async()=>{
 const f=await fixture();try{const first=cookieOf(await f.login());f.state.token=signed('LENDER_OFFICER',{sub:'user-2',lenderId:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2'});const second=cookieOf(await f.login(first));
  assert.equal((await fetch(f.base+'/api/session',{headers:{Cookie:first}})).status,401);
  const count=f.state.calls.length;const changed=await fetch(f.base+'/api/proxy/auth/api/v1/account/profile',{headers:{Cookie:second,'X-CredFlow-User':'user-1'}});assert.equal(changed.status,409);assert.equal(f.state.calls.length,count);
  const logout=await fetch(f.base+'/api/session/logout',{method:'POST',headers:{Cookie:second,Origin:f.base,'X-CredFlow-User':'user-1'}});assert.equal(logout.status,409);assert.equal((await fetch(f.base+'/api/session',{headers:{Cookie:second}})).status,200);
 }finally{await f.close();}
});

test('browser API restores a session and sends identity guards without bearer tokens',async()=>{
 const calls=[];const session={userId:'user-1',username:'officer',role:'LENDER_OFFICER',lenderId:'bank-id',expiresAt:Date.now()+900000};
 const api=new LiveApi({fetcher:async(url,options)=>{calls.push({url,options});return new Response(JSON.stringify(url==='/api/session'?session:{ok:true}),{status:200});}});
 assert.deepEqual(await api.restoreSession(),session);await api.request('auth','/api/v1/account/profile');const request=calls.at(-1).options;assert.equal(request.credentials,'same-origin');assert.equal(request.headers.Authorization,undefined);assert.equal(request.headers['X-CredFlow-User'],'user-1');
 await api.endSession();assert.equal(calls.at(-1).options.method,'POST');
});

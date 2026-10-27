import {spawn} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {fileURLToPath} from 'node:url';
import {portals} from '../backend/web/portals.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const privateDir=path.join(root,'backend','runtime','private');
const logs=path.join(privateDir,'logs');
const kafkaLogs=path.join(logs,'kafka');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin','java.exe'):'C:/jdk25/bin/java.exe';
const kafka=process.env.KAFKA_HOME||['C:/kafka_2.13-4.3.1','C:/kafka'].find(home=>fs.existsSync(path.join(home,'libs','kafka_2.13-4.3.1.jar')))||'C:/kafka_2.13-4.3.1';
const frontend=path.join(root,'frontend');
const mailpit=path.join(root,'backend','tools','mailpit','mailpit.exe');
const webServer=path.join(root,'backend','web','server.mjs');
const checkOnly=process.argv.includes('--check');

const services=JSON.parse(fs.readFileSync(path.join(root,'backend/services.json'),'utf8'));
// Resolve project-owned storage and keys from this checkout after a folder move.
function relocateConfiguration(){
 const targets={'credflow.marketplace-submission.credentials-file':'marketplace-clients.properties','log.dirs':'kafka-data','credflow.jwt.public-key-path':'auth-public.pem','credflow.jwt.private-key-path':'auth-private.pem','credflow.security.public-key-location':'auth-public.pem'};
 for(const name of fs.readdirSync(privateDir).filter(name=>name.endsWith('.properties'))){
  const file=path.join(privateDir,name),before=fs.readFileSync(file,'utf8');
  const after=before.replace(/^([^#\r\n=]+)=(.*)$/gm,(line,key,value)=>{
   const local=targets[key.trim()];if(!local)return line;
   const destination=path.join(privateDir,local);if(!fs.existsSync(destination))throw Error(`Required local file is missing: ${destination}`);
   return key+'='+(value.startsWith('file:')?'file:':'')+destination.replaceAll('\\','/');
  });
  if(after!==before){if(!fs.existsSync(file+'.before-relocation'))fs.copyFileSync(file,file+'.before-relocation');fs.writeFileSync(file,after);console.log(`Updated local paths: ${name}`);}
 }
}
function listening(port){return new Promise(resolve=>{const socket=net.connect({host:'127.0.0.1',port});socket.setTimeout(1000);socket.once('connect',()=>{socket.destroy();resolve(true)});socket.once('error',()=>resolve(false));socket.once('timeout',()=>{socket.destroy();resolve(false)});});}
async function healthy(port,resource='/actuator/health'){try{const r=await fetch(`http://127.0.0.1:${port}${resource}`,{signal:AbortSignal.timeout(2500)});return r.ok;}catch{return false;}}
async function portalHealthy(portal){try{const r=await fetch(`http://127.0.0.1:${portal.port}/api/portal`,{signal:AbortSignal.timeout(2500)});const data=await r.json();return r.ok&&data.id===portal.id&&data.role===portal.role;}catch{return false;}}
function launch(name,command,args,options={}){
 const out=fs.openSync(path.join(logs,name+'.log'),'a');
 const err=fs.openSync(path.join(logs,name+'.stderr.log'),'a');
 const child=spawn(command,args,{cwd:root,detached:true,windowsHide:true,stdio:['ignore',out,err],...options});
 child.on('error',e=>console.error(`${name}: ${e.message}`));child.unref();fs.closeSync(out);fs.closeSync(err);
 console.log(`Starting ${name} (PID ${child.pid})`);
}
async function wait(name,probe){const until=Date.now()+60000;while(Date.now()<until){if(await probe()){console.log(`${name}: UP`);return;}await new Promise(r=>setTimeout(r,1000));}throw Error(`${name} did not become healthy. See ${logs}`);}
try{
 if(checkOnly){let all=true;const mailOk=await healthy(8025,'/readyz')&&await listening(1025);console.log(`Mailpit 8025/1025: ${mailOk?'UP':'DOWN'}`);all&&=mailOk;const broker=await listening(9092);console.log(`Kafka 9092: ${broker?'LISTENING':'DOWN'}`);all&&=broker;for(const [name,port] of services){const ok=await healthy(port);console.log(`${name} ${port}: ${ok?'UP':'DOWN'}`);all&&=ok;}for(const portal of portals){const ok=await portalHealthy(portal);console.log(`${portal.name} ${portal.port}: ${ok?'UP':'DOWN'}`);all&&=ok;}process.exitCode=all?0:1;}
 else{
  relocateConfiguration();
  for(const file of [mailpit,java,path.join(kafka,'libs'),path.join(privateDir,'kafka.properties'),path.join(privateDir,'auth-private.pem'),webServer,...services.flatMap(([name,,jar])=>[path.join(root,'backend',jar),path.join(privateDir,name+'.properties')])])if(!fs.existsSync(file))throw Error(`Required local file is missing: ${file}. See README.md.`);
  fs.mkdirSync(kafkaLogs,{recursive:true});
  if(!await healthy(8025,'/readyz')||!await listening(1025)){
   if(await listening(8025)||await listening(1025))throw Error('Mailpit requires free ports 8025 and 1025, or a healthy existing instance. Resolve the port conflict before starting.');
   launch('mailpit',mailpit,['--listen','127.0.0.1:8025','--smtp','127.0.0.1:1025','--disable-version-check','--allowed-hosts','localhost,127.0.0.1','--label','CredFlow local test inbox']);
  }
  await wait('Mailpit',async()=>await healthy(8025,'/readyz')&&await listening(1025));
  console.log('Local test inbox: http://localhost:8025');
  if(!await listening(9092))launch('kafka',java,['-Dkafka.logs.dir='+kafkaLogs.replaceAll('\\','/'),'-Dlog4j2.configurationFile='+new URL('file:///'+path.join(kafka,'config/log4j2.yaml').replaceAll('\\','/')).href,'-cp',path.join(kafka,'libs','*'),'kafka.Kafka',path.join(privateDir,'kafka.properties')]);
  await wait('Kafka',()=>listening(9092));
  for(const [name,port,jar] of services){if(!await healthy(port)){if(await listening(port))throw Error(`${name}: port ${port} is occupied by an unhealthy service. Resolve it before starting another copy.`);launch(name,java,['-jar',path.join(root,'backend',jar),'--spring.profiles.active=integrated','--spring.config.additional-location='+new URL('file:///'+path.join(privateDir,name+'.properties').replaceAll('\\','/')).href]);}await wait(name,()=>healthy(port));}
  for(const portal of portals){
   if(!await portalHealthy(portal)){if(await listening(portal.port))throw Error(`Portal port ${portal.port} is occupied.`);launch('frontend-'+portal.id,process.execPath,[webServer],{cwd:frontend,env:{...process.env,CREDFLOW_PORTAL:portal.id}});}
   await wait(portal.name,()=>portalHealthy(portal));
   console.log(`${portal.name}: http://localhost:${portal.port}`);
  }

 }
}catch(e){console.error(e.message);process.exitCode=1;}

import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseProperties,javaExecutable,kafkaHome,run,listening} from './setup-common.mjs';

export function storageArguments(home,config,cluster){
 if(!/^[A-Za-z0-9_-]{22}$/.test(cluster))throw Error('Invalid saved Kafka cluster ID.');
 return ['-Dkafka.logs.dir='+path.join(path.dirname(config),'logs','kafka').replaceAll('\\','/'),'-Dlog4j2.configurationFile='+new URL('file:///'+path.join(home,'config/log4j2.yaml').replaceAll('\\','/')).href,'-cp',path.join(home,'libs','*'),'kafka.tools.StorageTool','format','--standalone','-t',cluster,'-c',config];
}
export function checkStorage(root){
 const privateDir=path.resolve(root,'backend/runtime/private'),config=path.join(privateDir,'kafka.properties');
 const values=parseProperties(fs.readFileSync(config,'utf8'));
 const data=path.resolve(values['log.dirs']||'');
 if(data!==path.join(privateDir,'kafka-data'))throw Error('Refusing Kafka setup: log.dirs must be this checkout’s private/kafka-data folder.');
 if(fs.existsSync(data)&&fs.lstatSync(data).isSymbolicLink())throw Error('Refusing Kafka setup through a linked data directory.');
 if(values['metadata.log.dir'])throw Error('Separate metadata.log.dir is unsupported by this fresh setup helper.');
 const initialized=fs.existsSync(path.join(data,'meta.properties'));
 return {config,data,initialized};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 try{
  const mode=process.argv[2];if(!['--check','--format'].includes(mode)||process.argv.length!==3)throw Error('Usage: node scripts/setup-kafka.mjs --check | --format');
  const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');const state=checkStorage(root),home=kafkaHome();
  if(mode==='--check')console.log(`Kafka installation found. Storage: ${state.initialized?'formatted':'not formatted'}.`);
  else{
   if(await listening(9092)||await listening(9093))throw Error('Kafka port is occupied. Fresh formatting requires Kafka to be stopped.');
   if(state.initialized)throw Error('Storage is already formatted. It was not reformatted.');
   if(!fs.existsSync(state.data)||fs.readdirSync(state.data).length)throw Error('Fresh formatting requires an existing empty private/kafka-data directory.');
   const cluster=fs.readFileSync(path.join(root,'backend/runtime/private/kafka-cluster.id'),'utf8').trim();
   run(javaExecutable(),storageArguments(home,state.config,cluster));
   console.log('Fresh Kafka storage formatted. No broker was started.');
  }
 }catch(error){console.error(error.message);process.exitCode=1;}
}

import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {spawnSync} from 'node:child_process';

export function parseProperties(text){
 const values={};
 for(const raw of text.split(/\r?\n/)){
  const line=raw.trim();if(!line||line.startsWith('#')||line.startsWith('!'))continue;
  const split=line.indexOf('=');if(split<1)throw Error('Expected simple key=value properties.');
  values[line.slice(0,split).trim()]=line.slice(split+1).trim();
 }
 return values;
}
export function properties(values){return Object.entries(values).map(([key,value])=>`${key}=${value}`).join('\n')+'\n';}
export function forward(file){return file.replaceAll('\\','/');}
export function javaExecutable(){return process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin','java.exe'):'C:/jdk25/bin/java.exe';}
export function kafkaHome(){
 const location=process.env.KAFKA_HOME||['C:/kafka_2.13-4.3.1','C:/kafka'].find(p=>fs.existsSync(path.join(p,'libs','kafka_2.13-4.3.1.jar')));
 if(!location||!fs.existsSync(path.join(location,'libs')))throw Error('Install Kafka 4.3.1 and set KAFKA_HOME to its extracted folder.');
 return location;
}
export function run(command,args,options={}){
 const result=spawnSync(command,args,{windowsHide:true,stdio:'inherit',...options});
 if(result.error)throw Error(`Could not start ${path.basename(command)}. Check the installed tool and JAVA_HOME/KAFKA_HOME.`);
 if(result.status!==0)throw Error(`${path.basename(command)} failed; review its preceding output.`);
}
export function listening(port){return new Promise(resolve=>{
 const socket=net.connect({host:'127.0.0.1',port});socket.setTimeout(1500);
 const done=value=>{socket.destroy();resolve(value);};
 socket.once('connect',()=>done(true));socket.once('error',()=>done(false));socket.once('timeout',()=>done(false));
});}

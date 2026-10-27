import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const command="$r=$env:CREDFLOW_STOP_ROOT; Get-CimInstance Win32_Process | Where-Object {$_.CommandLine -and $_.CommandLine.Contains($r) -and ($_.Name -match '^javaw?\\.exe$' -or $_.Name -eq 'node.exe' -or $_.Name -eq 'mailpit.exe')} | Select-Object ProcessId,Name,CommandLine | ConvertTo-Json -Compress";
try{
 const result=execFileSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',command],{encoding:'utf8',windowsHide:true,env:{...process.env,CREDFLOW_STOP_ROOT:root}}).trim();
 const parsed=result?JSON.parse(result):[],processes=Array.isArray(parsed)?parsed:[parsed];
 for(const p of processes){const line=p.CommandLine||'';if(p.ProcessId===process.pid)continue;
  const ownedJava=/^javaw?\.exe$/i.test(p.Name)&&(line.includes('-jar')||line.includes('kafka.Kafka'));
  const ownedFrontend=p.Name==='node.exe'&&line.includes(path.join(root,'backend','web','server.mjs'));
  const ownedMailpit=p.Name==='mailpit.exe'&&line.includes(path.join(root,'backend','tools','mailpit','mailpit.exe'));
  if(ownedJava||ownedFrontend||ownedMailpit){execFileSync('taskkill.exe',['/PID',String(p.ProcessId),'/F'],{windowsHide:true,stdio:'ignore'});console.log(`Stopped ${p.Name} PID ${p.ProcessId}`);}
 }
 console.log('Project processes stopped. Oracle users, tables and persisted records are preserved.');
}catch(e){console.error('Unable to stop project processes: '+e.message);process.exitCode=1;}

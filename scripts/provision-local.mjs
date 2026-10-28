import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {javaExecutable,run,listening} from './setup-common.mjs';

// Explicit manual provisioning after a fresh SQL install. Never called by start.cmd.
try{
 if(process.argv.length!==3||process.argv[2]!=='--fresh-install')throw Error('Usage: node scripts/provision-local.mjs --fresh-install');
 const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
 if(await listening(8085))throw Error('Stop the Auth service before provisioning identities.');
 const privateDir=path.join(root,'backend/runtime/private');
 if(!fs.existsSync(path.join(privateDir,'fresh-install.sql')))throw Error('Generate this checkout’s fresh configuration first.');
 const marker=path.join(privateDir,'identities-provisioned.txt');
 if(fs.existsSync(marker))throw Error('Fresh identities are already marked provisioned. No accounts or passwords were changed.');
 const jar=path.join(root,'backend/member1/auth-service/target/auth-service-1.jar');
 if(!fs.existsSync(jar))throw Error('Build auth-service first; follow SETUP-WINDOWS.md.');
 const java=javaExecutable(),jarTool=path.join(path.dirname(java),'jar.exe');
 const extraction=path.join(privateDir,'setup-tools');fs.mkdirSync(extraction,{recursive:true});
 run(jarTool,['xf',jar,'BOOT-INF/lib'],{cwd:extraction});
 const cp=path.join(extraction,'BOOT-INF/lib','*');
 // Existing helper contracts are reused, including their identity-conflict checks.
 for(const [helper,arg] of [['SeedFull.java',path.join(privateDir,'runtime.properties')],['ProvisionPhase0.java',privateDir],['ProvisionApplicationCorrections.java',privateDir],['ProvisionMarketplaceClients.java',privateDir]]){
  console.log(`Provisioning through ${helper} (credentials are not printed).`);
  run(java,['--class-path',cp,path.join(root,'backend/runtime/tools',helper),arg]);
 }
 fs.writeFileSync(marker,'Fresh identities provisioned.\n',{flag:'wx',mode:0o600});
 console.log('Identity provisioning completed. Continue with the remaining builds and Kafka formatting.');
}catch(error){console.error(error.message);process.exitCode=1;}

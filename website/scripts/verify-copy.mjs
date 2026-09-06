import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
const root=path.resolve(process.argv[2]||'dist');
let count=0;
for(const line of fs.readFileSync(path.join(root,'checksums.sha256'),'utf8').trim().split('\n')){
  const match=/^([a-f0-9]{64})  (.+)$/.exec(line);if(!match)throw Error('Invalid checksum line');
  const filename=path.resolve(root,match[2]);if(!filename.startsWith(root+path.sep))throw Error('Path outside output');
  const hash=await new Promise((resolve,reject)=>{const h=createHash('sha256');fs.createReadStream(filename).on('data',d=>h.update(d)).on('end',()=>resolve(h.digest('hex'))).on('error',reject);});
  if(hash!==match[1])throw Error(`Mismatch: ${match[2]}`);count++;
}
console.log(`Verified ${count} file hashes in ${root}`);

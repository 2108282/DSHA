import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../dist');
const port=Number(process.env.PORT||4180);
const types={'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.json':'application/json; charset=utf-8','.svg':'image/svg+xml','.xml':'application/xml; charset=utf-8','.txt':'text/plain; charset=utf-8','.md':'text/plain; charset=utf-8','.sha256':'text/plain; charset=utf-8','.apk':'application/vnd.android.package-archive','.gz':'application/gzip'};
export const server=http.createServer((req,res)=>{
  if(!['GET','HEAD'].includes(req.method)){res.writeHead(405,{'Allow':'GET, HEAD'});res.end();return;}
  let requested;
  try{requested=decodeURIComponent(new URL(req.url,'http://localhost').pathname);}catch{res.writeHead(400);res.end();return;}
  let filename=path.resolve(root,'.'+requested);
  if(!filename.startsWith(root+path.sep)&&filename!==root){res.writeHead(403);res.end();return;}
  let status=200;
  try{if(fs.statSync(filename).isDirectory())filename=path.join(filename,'index.html');if(!fs.statSync(filename).isFile())throw Error();}catch{filename=path.join(root,'404.html');status=404;}
  const stat=fs.statSync(filename),total=stat.size;
  const headers={'Content-Type':types[path.extname(filename)]||'application/octet-stream','X-Content-Type-Options':'nosniff','Referrer-Policy':'strict-origin-when-cross-origin','Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",'Cache-Control':'no-cache','Accept-Ranges':'bytes'};
  let start=0,end=total-1;
  if(req.headers.range&&status===200){
    const range=/^bytes=(\d*)-(\d*)$/.exec(req.headers.range);
    if(!range||(!range[1]&&!range[2])){res.writeHead(416,{'Content-Range':`bytes */${total}`});res.end();return;}
    if(!range[1]){const suffix=Number(range[2]);start=Math.max(0,total-suffix);}else{start=Number(range[1]);end=range[2]?Math.min(total-1,Number(range[2])):total-1;}
    if(start>end||start>=total||end<0){res.writeHead(416,{'Content-Range':`bytes */${total}`});res.end();return;}
    status=206;headers['Content-Range']=`bytes ${start}-${end}/${total}`;
  }
  headers['Content-Length']=Math.max(0,end-start+1);res.writeHead(status,headers);
  if(req.method==='HEAD'){res.end();return;}
  fs.createReadStream(filename,{start,end}).on('error',()=>res.destroy()).pipe(res);
});
server.listen(port,'127.0.0.1',()=>console.log(`Local: http://127.0.0.1:${server.address().port}`));

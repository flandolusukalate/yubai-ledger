import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../app/src/main/assets/web/',import.meta.url));
const mime={'.html':'text/html','.mjs':'text/javascript','.js':'text/javascript','.css':'text/css','.ttf':'font/ttf','.png':'image/png'};
http.createServer(async(req,res)=>{try{const url=new URL(req.url,'http://127.0.0.1'),relative=decodeURIComponent(url.pathname).replace(/^\/+/,''),file=path.resolve(root,relative||'index.html');if(!file.startsWith(root)){res.writeHead(403);res.end();return;}const content=await fs.readFile(file);res.writeHead(200,{'Content-Type':(mime[path.extname(file)]||'application/octet-stream')+'; charset=utf-8','Cache-Control':'no-store'});res.end(content);}catch{res.writeHead(404);res.end('Not found');}}).listen(4317,'127.0.0.1',()=>console.log('余白预览 http://127.0.0.1:4317'));

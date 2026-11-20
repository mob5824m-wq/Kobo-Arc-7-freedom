/* Execute the real offline SHA-256 and uploader with host browser/HTTP fakes. */
'use strict';
const assert=require('node:assert/strict'),crypto=require('node:crypto'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const asset=path.resolve(__dirname,'../app/src/main/assets/lan-share');
const Sha=require(path.join(asset,'sha256.js'));
const reference=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
for(const length of [0,1,3,55,56,63,64,65,127,128,1024,1048579]){
  const bytes=Buffer.alloc(length);for(let i=0;i<length;i++)bytes[i]=(i*61+13)&255;
  assert.equal(Sha.hex(bytes),reference(bytes));const hash=new Sha();for(let i=0;i<length;i+=73)hash.update(bytes.subarray(i,i+73));assert.equal(hash.hex(),reference(bytes));assert.equal(hash.hex(),reference(bytes));assert.throws(()=>hash.update(Buffer.from([1])));
}
assert.equal(Sha.hex(Buffer.from('abc')),'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
assert.equal(Sha.hex(Buffer.alloc(1000000,97)),'cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0');
// Cross the 32-bit bit-length boundary without keeping a large file in memory.
const block=Buffer.alloc(1048576,0x53),large=new Sha(),expected=crypto.createHash('sha256');
for(let i=0;i<513;i++){large.update(block);expected.update(block);}assert.equal(large.hex(),expected.digest('hex'));
const boundary=new Sha();let padding;boundary.bytes=4294967296;boundary.compress=b=>{padding=Buffer.from(b);};boundary.hex();assert.equal(padding.readUInt32BE(56),8);assert.equal(padding.readUInt32BE(60),0);
console.log('PASS production incremental SHA-256: NIST vectors, split/padding boundaries, 513 MiB streamed >32-bit bit count, 4 GiB length encoding and no whole-file allocation');

async function browser(){
  const elements={files:{files:[]},currentPath:{value:''},status:{textContent:''},progress:{value:0}};
  let maximumRead=0,records=new Map(),calls=[],failChunk=false,loseFinish=false,pausedOnce=false;
  class Reader{async readAsArrayBuffer(blob){try{maximumRead=Math.max(maximumRead,blob.size);assert.ok(blob.size<=1048576);this.result=await blob.arrayBuffer();this.onload();}catch(error){if(this.onerror)this.onerror(error);else throw error;}}}
  const context={Uint8Array,Uint32Array,Number,Math,Object,Array,Promise,Error,FileReader:Reader,AbortController,setTimeout,clearTimeout,shareKey:'test-in-memory-only',document:{getElementById:id=>elements[id]}};
  let next=1;
  context.fetch=async(url,options)=>{
    assert.ok(url.startsWith('/upload/'));assert.equal(options.method,'POST');assert.equal(options.headers['X-Arc7-Share'],context.shareKey);assert.ok(!url.includes(context.shareKey));assert.equal(options.credentials,'same-origin');
    calls.push(url);const target=new URL(url,'http://fixture.invalid'),p=target.searchParams,kind=target.pathname.split('/').pop();let record;
    if(kind==='init'){
      const identity=[p.get('path'),p.get('name'),p.get('size'),p.get('sha256')].join('|');record=[...records.values()].find(r=>r.identity===identity);
      if(!record){record={id:(next++).toString(16).padStart(32,'0'),identity,offset:0,size:Number(p.get('size')),complete:false,name:p.get('name'),sha256:p.get('sha256'),bytes:[]};records.set(record.id,record);}
    }else{
      record=records.get(p.get('id'));assert.ok(record);
      if(kind==='chunk'){
        assert.equal(Number(p.get('offset')),record.offset);const data=Buffer.from(options.body);assert.equal(reference(data),p.get('sha256'));assert.ok(data.length<=1048576);
        if(failChunk&&record.offset>0){failChunk=false;throw new Error('Fixture network disconnect');}
        record.bytes.push(data);record.offset+=data.length;
        if(pausedOnce){pausedOnce=false;context.pauseUpload();throw new Error('Fixture aborted after durable chunk');}
      }else if(kind==='finish'){
        assert.equal(record.offset,record.size);assert.equal(reference(Buffer.concat(record.bytes)),record.sha256);record.complete=true;
        if(loseFinish){loseFinish=false;throw new Error('Fixture response lost after verified save');}
      }else assert.fail('Unexpected endpoint '+kind);
    }
    const json={...record};delete json.bytes;delete json.identity;return {ok:true,json:async()=>json};
  };
  vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(asset,'sha256.js'),'utf8'),context);vm.runInContext(fs.readFileSync(path.join(asset,'upload.js'),'utf8'),context);
  const bytes=Buffer.alloc(2097275);for(let i=0;i<bytes.length;i++)bytes[i]=(i*17)&255;
  function file(name,body){const blob=new Blob([body]);return {name,size:blob.size,slice:(a,b)=>blob.slice(a,b)};}
  elements.files.files=[file('fixture.bin',bytes)];failChunk=true;await context.uploadFiles();assert.match(elements.status.textContent,/Upload stopped/);assert.equal([...records.values()][0].offset,1048576);assert.equal([...records.values()][0].complete,false);
  await context.uploadFiles();assert.match(elements.status.textContent,/All selected files verified/);assert.equal(records.size,1);assert.equal([...records.values()][0].complete,true);assert.equal(maximumRead,1048576);
  elements.files.files=[file('empty.bin',Buffer.alloc(0))];await context.uploadFiles();assert.equal([...records.values()][1].complete,true);
  elements.files.files=[file('lost.bin',Buffer.from('finish exactly once'))];loseFinish=true;await context.uploadFiles();assert.match(elements.status.textContent,/response lost/);await context.uploadFiles();assert.match(elements.status.textContent,/All selected files verified/);assert.equal(records.size,3);
  elements.files.files=[file('paused.bin',bytes)];pausedOnce=true;await context.uploadFiles();assert.match(elements.status.textContent,/Paused/);await context.uploadFiles();assert.equal(records.size,4);assert.match(elements.status.textContent,/All selected files verified/);
  assert.ok(calls.some(url=>url.includes('offset=1048576')));assert.ok(!fs.readFileSync(path.join(asset,'upload.js'),'utf8').includes('crypto.subtle'));
  console.log('PASS real browser uploader with fakes: ≤1 MiB reads/requests, interrupted/paused recovery, exact acknowledged offsets, zero-byte files, lost completion-response retry, in-memory bearer header only and verified receipts');
}
function legacyClient(script){
  const elements={files:{files:[]},currentPath:{value:'nested folder'},status:{textContent:''},progress:{value:0}};
  const sent=[];let pending,complete=true;
  class Request{
    open(method,url,async){assert.equal(method,'POST');assert.equal(async,true);this.url=url;this.headers={};}
    setRequestHeader(name,value){this.headers[name]=value;}
    send(file){assert.ok(file.size<=8388608);assert.equal(this.headers['X-Arc7-Share'],'legacy-private-key');assert.ok(!this.url.includes('legacy-private-key'));sent.push(this);pending=this;if(complete){this.status=201;this.onload();}}
    abort(){this.onabort();}
  }
  const context={document:{getElementById:id=>elements[id]},XMLHttpRequest:Request,shareKey:'legacy-private-key',encodeURIComponent,Math};vm.createContext(context);vm.runInContext(script,context);
  elements.files.files=[{name:'a&b.bin',size:8},{name:'empty.bin',size:0}];context.uploadFiles();assert.equal(sent.length,2);assert.match(sent[0].url,/path=nested%20folder&name=a%26b.bin/);assert.match(elements.status.textContent,/Upload complete/);assert.equal(elements.progress.value,100);
  elements.files.files=[{name:'large.bin',size:8388609}];context.uploadFiles();assert.equal(sent.length,2);assert.match(elements.status.textContent,/8 MiB maximum/);
  elements.files.files=[{name:'pause.bin',size:12}];complete=false;context.uploadFiles();assert.equal(sent.length,3);context.uploadFiles();assert.equal(sent.length,3);context.pauseUpload();assert.match(elements.status.textContent,/Paused.*restart from zero/);
  context.uploadFiles();assert.equal(sent.length,4);pending.onerror();assert.match(elements.status.textContent,/Connection lost.*no resume/);
  console.log('PASS actual served asset-less compatibility client: no broken script assets, 8 MiB cap, zero-byte/multiple files, encoded folder/name, header-only bearer, single-flight, pause and honest lost-response/restart warning');
}
if(process.argv[2])legacyClient(fs.readFileSync(process.argv[2],'utf8'));
browser().catch(error=>{console.error(error);process.exitCode=1;});

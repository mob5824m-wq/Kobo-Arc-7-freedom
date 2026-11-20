'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const p=require('../../tools/qr-transfer/protocol.js'),qr=require('../../third_party/qrcode-generator/qrcode.js'),jsQR=require('../../third_party/jsqr/jsQR.js');
const directory=process.argv[2],mode=process.argv[3],W=640,H=480;
function camera(text){
  const code=qr(0,'L');code.addData(text,'Byte');code.make();
  const y=Buffer.alloc(W*H,242),n=code.getModuleCount(),cell=Math.min(5,Math.floor((Math.min(W,H)-32)/(n+8))),left=Math.floor((W-n*cell)/2),top=Math.floor((H-n*cell)/2);
  assert(cell>=3,'Camera fixture must resolve this density');
  for(let r=0;r<n;r++)for(let c=0;c<n;c++)if(code.isDark(r,c))for(let dy=0;dy<cell;dy++)for(let dx=0;dx<cell;dx++)y[(top+r*cell+dy)*W+left+c*cell+dx]=18;
  return y;
}
function decode(y){
  const rgba=new Uint8ClampedArray(W*H*4);for(let i=0;i<y.length;i++){rgba[i*4]=rgba[i*4+1]=rgba[i*4+2]=y[i];rgba[i*4+3]=255;}
  const result=jsQR(rgba,W,H,{inversionAttempts:'dontInvert'});assert(result,'No synthetic camera QR decoded');return result.data;
}
const changed=(text,index,value)=>p.seal(text.split('|').slice(0,-1).map((v,i)=>i===index?String(value):v).join('|'));
(async()=>{
  const bytes=new Uint8Array(fs.readFileSync(path.join(directory,'payload.bin')));
  if(mode==='prepare'){
    const tx=await p.sender(bytes,'résumé 📷.bin','0123456789abcdef'),frames=[tx.manifest()];
    for(let i=0;i<tx.total;i++)frames.push(tx.data(i));
    fs.writeFileSync(path.join(directory,'browser.frames'),frames.join('\n')+'\n');fs.writeFileSync(path.join(directory,'browser-camera.y'),camera(tx.manifest()));
    const rx=new p.Receiver(p.parse(tx.manifest()));
    for(let i=tx.total-1;i>=0;i--){assert.equal(rx.accept(p.parse(tx.data(i))),true);assert.equal(rx.accept(p.parse(tx.data(i))),false);}
    assert.deepEqual(await rx.verify(),bytes);assert.equal(rx.seen.size,tx.total);
    assert.throws(()=>p.parse(tx.manifest().slice(0,-1)+'z'),/CRC/);
    assert.throws(()=>p.parse(changed(tx.manifest(),3,67108865)),/bounds/);
    assert.throws(()=>p.parse(changed(tx.manifest(),4,256)),/density/);
    assert.throws(()=>p.parse(changed(tx.manifest(),7,'Li4vZXNjYXBl')),/Unsafe/);
    assert.throws(()=>p.parse(changed(tx.data(0),6,'AB==')),/canonical/);
    assert.throws(()=>rx.accept(p.parse(changed(tx.data(0),6,Buffer.alloc(512).toString('base64')))),/Conflicting/);
    assert.throws(()=>rx.accept(p.parse(changed(tx.data(0),6,'AA=='))),/length/);
    assert.throws(()=>rx.accept(p.parse(changed(tx.data(0),5,128))),/large|profile/);
    const bad=new p.Receiver(p.parse(changed(tx.manifest(),6,'0'.repeat(64))));for(let i=0;i<tx.total;i++)bad.accept(p.parse(tx.data(i)));
    await assert.rejects(bad.verify(),/SHA-256/);assert.equal(bad.verified,false);
    const ids=[];
    for(const options of [{chunk:128},{chunk:512},{chunk:1024},{chunk:128,legacy:true}]){
      const profile=await p.sender(bytes,'résumé 📷.bin',undefined,options);ids.push(profile.id);
      assert.equal(decode(camera(profile.data(0))),profile.data(0),'Camera/profile round trip');
      if(options.legacy)assert(profile.data(0).length<=271);
      for(const size of [0,1,options.chunk-1,options.chunk,options.chunk+1]){
        const boundary=await p.sender(new Uint8Array(size),'é'.repeat(100),undefined,options),r=new p.Receiver(p.parse(boundary.manifest()));
        for(let i=boundary.total-1;i>=0;i--)r.accept(p.parse(boundary.data(i)));assert.equal((await r.verify()).length,size);
      }
    }
    fs.writeFileSync(path.join(directory,'browser.ids'),ids.join('\n')+'\n');
    assert.throws(()=>p.parse(p.seal('A7Q1|M|0123456789abcdef|1048577|128|8192|'+'0'.repeat(64)+'|YQ==')),/bounds/);
    const large=Uint8Array.from({length:2*1048576+77},(_,i)=>(i*37+11)&255),big=await p.sender(large,'large.bin'),sink=new p.Receiver(p.parse(big.manifest()));
    // Exercise a complete file above the old 1 MiB limit, out of order, without QR-rendering thousands of frames.
    for(let i=big.total-1;i>=0;i--)sink.accept(p.parse(big.data(i)));assert.deepEqual(await sink.verify(),large);
    await assert.rejects(p.sender(large,'large.bin',undefined,{chunk:128,legacy:true}),/1 MiB/);
    const max=await p.sender(new Uint8Array(p.MAX),'max.bin',undefined,{chunk:1024});assert.equal(p.parse(max.manifest()).size,p.MAX);assert.equal(p.parse(max.data(max.total-1)).bytes.length,1024);
    assert.throws(()=>p.parse(changed(max.manifest(),3,p.MAX+1)),/bounds/);
    console.log('PASS: browser v1/v2/three densities, >2 MiB full transfer, 64 MiB boundary, bitmap/order/CRC/SHA/path/profile limits, synthetic camera and Java fixtures');
  }else{
    const frames=fs.readFileSync(path.join(directory,'java.frames'),'ascii').trimEnd().split('\n'),rx=new p.Receiver(p.parse(frames[0]));
    for(let i=frames.length-1;i>0;i--)rx.accept(p.parse(frames[i]));assert.deepEqual(await rx.verify(),bytes);assert.equal(rx.metadata.name,'résumé 📷.bin');
    assert.equal(decode(fs.readFileSync(path.join(directory,'java-camera.y'))),frames[1]);
    for(let i=0;i<4;i++)assert.equal(decode(fs.readFileSync(path.join(directory,`java-profile-${i}.y`))),fs.readFileSync(path.join(directory,`java-profile-${i}.txt`),'ascii'));
    console.log('PASS: actual Java encoder pixels scanned by jsQR for legacy and all new densities; Android/browser bytes and deterministic IDs agree');
  }
})().catch(error=>{console.error(error);process.exitCode=1;});

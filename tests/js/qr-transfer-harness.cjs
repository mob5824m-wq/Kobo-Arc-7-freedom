'use strict';
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const p = require('../../tools/qr-transfer/protocol.js');
const qr = require('../../third_party/qrcode-generator/qrcode.js');
const jsQR = require('../../third_party/jsqr/jsQR.js');
const directory = process.argv[2], mode = process.argv[3], W=640,H=480;
function camera(text) {
  const code=qr(0,'L');code.addData(text,'Byte');code.make();
  const y=Buffer.alloc(W*H,242),n=code.getModuleCount(),cell=5,left=Math.floor((W-n*cell)/2),top=Math.floor((H-n*cell)/2);
  for(let r=0;r<n;r++)for(let c=0;c<n;c++)if(code.isDark(r,c))for(let dy=0;dy<cell;dy++)for(let dx=0;dx<cell;dx++)y[(top+r*cell+dy)*W+left+c*cell+dx]=18;
  return y;
}
function decode(y) {
  const rgba=new Uint8ClampedArray(W*H*4);
  for(let i=0;i<y.length;i++){rgba[i*4]=rgba[i*4+1]=rgba[i*4+2]=y[i];rgba[i*4+3]=255;}
  const result=jsQR(rgba,W,H,{inversionAttempts:'dontInvert'});assert(result,'No camera QR decoded');return result.data;
}
(async()=>{
  const bytes=new Uint8Array(fs.readFileSync(path.join(directory,'payload.bin')));
  if(mode==='prepare') {
    const tx=await p.sender(bytes,'résumé 📷.bin','0123456789abcdef'),frames=[tx.manifest()];
    for(let i=0;i<tx.total;i++)frames.push(tx.data(i));
    fs.writeFileSync(path.join(directory,'browser.frames'),frames.join('\n')+'\n');
    fs.writeFileSync(path.join(directory,'browser-camera.y'),camera(tx.manifest()));
    assert.equal(decode(camera(tx.data(tx.total-1))),tx.data(tx.total-1));
    const rx=new p.Receiver(p.parse(tx.manifest()));
    for(let i=tx.total-1;i>=0;i--){assert.equal(rx.accept(p.parse(tx.data(i))),true);assert.equal(rx.accept(p.parse(tx.data(i))),false);}
    assert.deepEqual(await rx.verify(),bytes);
    assert.throws(()=>p.parse(tx.manifest().slice(0,-1)+'z'),/CRC/);
    assert.throws(()=>p.parse(p.seal('A7Q1|M|0123456789abcdef|1048577|128|8192|'+'0'.repeat(64)+'|YQ==')),/bounds/);
    assert.throws(()=>p.parse(p.seal('A7Q1|M|0123456789abcdef|1|128|1|'+'0'.repeat(64)+'|Li4vZXNjYXBl')),/Unsafe/);
    assert.throws(()=>p.parse(p.seal('A7Q1|D|0123456789abcdef|0|1|AB==')),/canonical/);
    const forged=p.parse(p.seal(tx.manifest().split('|').slice(0,-1).map((v,i)=>i===6?'0'.repeat(64):v).join('|'))),bad=new p.Receiver(forged);
    for(let i=0;i<tx.total;i++)bad.accept(p.parse(tx.data(i)));
    await assert.rejects(bad.verify(),/SHA-256/);assert.equal(bad.verified,false);
    assert.throws(()=>rx.accept(p.parse(p.seal('A7Q1|D|0123456789abcdef|0|'+tx.total+'|'+Buffer.alloc(128).toString('base64')))),/Conflicting/);
    for(const size of [0,1,127,128,129,1048576]) {
      const boundary=await p.sender(new Uint8Array(size),'é'.repeat(100));
      assert(boundary.manifest().length<=271);assert.equal(p.parse(boundary.manifest()).size,size);assert.equal(p.parse(boundary.data(boundary.total-1)).total,boundary.total);
      if(size<130){const r=new p.Receiver(p.parse(boundary.manifest()));for(let i=0;i<boundary.total;i++)r.accept(p.parse(boundary.data(i)));assert.equal((await r.verify()).length,size);}
    }
    console.log('PASS: browser framing, bounds, CRC/SHA and QR encoder/reader; generated browser-to-Android interoperability fixtures');
  } else {
    const frames=fs.readFileSync(path.join(directory,'java.frames'),'ascii').trimEnd().split('\n'),rx=new p.Receiver(p.parse(frames[0]));
    for(let i=frames.length-1;i>0;i--)rx.accept(p.parse(frames[i]));
    assert.deepEqual(await rx.verify(),bytes);assert.equal(rx.metadata.name,'résumé 📷.bin');
    assert.equal(decode(fs.readFileSync(path.join(directory,'java-camera.y'))),frames[1]);
    console.log('PASS: production Android QR scanned by browser jsQR; production Android frames reassembled byte-for-byte by browser receiver');
  }
})().catch(error=>{console.error(error);process.exitCode=1;});

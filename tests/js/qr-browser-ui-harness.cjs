'use strict';
// Execute the real browser UI with a recording DOM/camera. Pixel readers are tested separately.
const vm=require('node:vm'),fs=require('node:fs'),assert=require('node:assert/strict');
const protocol=require('../../tools/qr-transfer/protocol.js');
const source=fs.readFileSync(require('node:path').join(__dirname,'../../tools/qr-transfer/browser.js'),'utf8');
const flush=()=>new Promise(resolve=>setTimeout(resolve,15));
function fixture(api=protocol){
  const elements={},events={},windowEvents={},timers=new Map(),codes=[],scans=[],urls=[];let id=0,stops=0;
  function element(key){return elements[key]||(elements[key]={value:key==='speed'?'700':key==='density'?'512':'1',hidden:true,files:[],readyState:2,videoWidth:640,videoHeight:480,
    addEventListener(type,callback){this[type]=callback;},play:async()=>{},
    getContext:()=>({fillRect(){},drawImage(){},getImageData:()=>({data:new Uint8ClampedArray(4),width:1,height:1})})});}
  const document={hidden:false,getElementById:element,addEventListener:(type,callback)=>events[type]=callback};
  const window={Arc7Optical:api,addEventListener:(type,callback)=>windowEvents[type]=callback};
  const sandbox={document,window,navigator:{mediaDevices:{getUserMedia:async()=>({getTracks:()=>[{stop(){stops++;}}]})}},
    qrcode:()=>({addData(text){codes.push(text);},make(){},getModuleCount:()=>21,isDark:()=>false}),
    jsQR:()=>scans.length?{data:scans.shift()}:null,URL:{createObjectURL(blob){urls.push(blob);return 'blob:fixture';},revokeObjectURL(){}},
    Blob,Uint8Array,Uint8ClampedArray,setTimeout:(callback)=>{timers.set(++id,callback);return id;},clearTimeout:key=>timers.delete(key),console};
  vm.runInNewContext(source,sandbox);
  return {element,document,events,windowEvents,timers,codes,scans,urls,stops:()=>stops,
    tickScan(){const found=[...timers.entries()].find(([,f])=>String(f).includes('scanLoop'));assert(found,'No scan scheduled');timers.delete(found[0]);found[1]();}};
}
(async()=>{
  const ui=fixture(),$=ui.element;let reads=0;
  const bytes=new Uint8Array(1048577);
  $('file').files=[{name:'larger.bin',size:bytes.length,arrayBuffer:async()=>{reads++;return bytes.buffer;}}];
  await $('file').change();assert.equal(protocol.parse(ui.codes.at(-1)).chunk,512);assert(ui.codes.at(-1).startsWith('A7Q2|M|'));
  assert($('sendInfo').textContent.includes('one clean pass'));
  $('density').value='legacy';await $('density').change();assert.equal(reads,1,'Legacy limit must be checked before reading the file');assert($('status').textContent.includes('1 MiB'));
  const old=ui.codes.length;$('play').onclick();assert.equal(ui.codes.length,old,'An invalid new selection must not resume the old file');
  $('density').value='1024';await $('density').change();assert.equal(protocol.parse(ui.codes.at(-1)).chunk,1024);
  let resolve;const delayed=new Promise(done=>resolve=done);
  $('file').files=[{name:'old.bin',size:1,arrayBuffer:()=>delayed}];const pending=$('file').change();
  $('file').files=[{name:'new.bin',size:1,arrayBuffer:async()=>new Uint8Array([8]).buffer}];await $('file').change();resolve(new Uint8Array([1]).buffer);await pending;
  assert.equal(protocol.parse(ui.codes.at(-1)).name,'new.bin','Stale file preparation replaced a newer selection');
  ui.document.hidden=true;ui.events.visibilitychange();assert.equal($('play').textContent,'Play loop');assert($('status').textContent.includes('Paused'));
  // Actual v2 frames must reach consent, the real byte receiver, SHA verification and an explicit Save link.
  const receive=fixture(),tx=await protocol.sender(new Uint8Array([1,2,3]),'receive.bin');
  receive.scans.push(tx.manifest());await receive.element('camera').onclick();assert.equal(receive.element('consent').hidden,false,'v2 camera frame ignored');
  receive.element('accept').onclick();receive.scans.push(tx.data(0));receive.tickScan();await flush();
  assert.equal(receive.element('download').hidden,false);assert.equal(receive.element('download').download,'receive.bin');assert.equal(receive.urls.length,1);
  assert(receive.stops()>0,'Receiving camera did not stop after completion');
  receive.element('reset').onclick();assert.equal(receive.element('download').hidden,true);receive.windowEvents.pagehide();
  // Reset while verification is pending must never publish the old file later.
  let release;const gate=new Promise(done=>release=done);
  class SlowReceiver extends protocol.Receiver{async verify(){await gate;return super.verify();}}
  const cancelled=fixture({...protocol,Receiver:SlowReceiver});cancelled.scans.push(tx.manifest());await cancelled.element('camera').onclick();cancelled.element('accept').onclick();
  cancelled.scans.push(tx.data(0));cancelled.tickScan();cancelled.element('reset').onclick();release();await flush();assert.equal(cancelled.urls.length,0,'Cancelled receive produced a Save URL');
  console.log('PASS: real browser UI file/profile bounds, stale-selection guard, v2 camera consent/reassembly/explicit Save, reset-during-verify and visibility/camera cleanup (recording DOM/camera)');
})().catch(error=>{console.error(error);process.exitCode=1;});

/* Self-contained offline UI; all bytes stay in this page and its QR codes. */
(function () {
  'use strict';
  const $ = id => document.getElementById(id), protocol = window.Arc7Optical;
  let tx = null, rx = null, header = null, next = -1, displayed = -1, pending = 0;
  let timer = null, playing = false, media = null, scanning = false, verifying = false, facing = 'environment', download = null;
  let cameraGeneration=0, fileGeneration=0, receiveGeneration=0;
  const message = text => { $('status').textContent = text; };
  const failure = error => message(error.message || String(error));
  function stopSend() { clearTimeout(timer); timer = null; playing = false; $('play').textContent = 'Play loop'; }
  function showFrame() {
    if (!tx) return;
    const text = next < 0 ? tx.manifest() : tx.data(next), code = qrcode(0, 'L');
    code.addData(text,'Byte'); code.make();
    const canvas = $('qr'), size = code.getModuleCount(), cell = 6;
    canvas.width = canvas.height = (size+8)*cell;
    const c = canvas.getContext('2d'); c.fillStyle = 'white'; c.fillRect(0,0,canvas.width,canvas.height); c.fillStyle = 'black';
    for (let y=0;y<size;y++) for (let x=0;x<size;x++) if (code.isDark(y,x)) c.fillRect((x+4)*cell,(y+4)*cell,cell,cell);
    displayed = next;
    message(tx.name + ' · ' + (displayed < 0 ? 'file header' : 'frame '+(displayed+1)+' / '+tx.total));
    $('sendInfo').textContent = tx.size+' bytes · SHA-256 '+tx.sha256;
    if (next < 0) next = pending;
    else if (next+1 >= tx.total) { pending=0; next=-1; }
    else if ((next+1)%8===0) { pending=next+1; next=-1; }
    else next++;
    if (playing && !document.hidden) timer = setTimeout(showFrame,Number($('speed').value));
  }
  function closeCamera() { cameraGeneration++; scanning=false; if (media) media.getTracks().forEach(track=>track.stop()); media=null; $('video').srcObject=null; }
  async function scanCamera() {
    closeCamera(); const request=cameraGeneration;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error('Camera access needs a supported browser and a secure context (HTTPS or localhost). A local file works in some browsers. You can always SEND bytes from this page to the Kobo camera.');
    const opened = await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:facing},width:{ideal:640},height:{ideal:480}},audio:false});
    if(request!==cameraGeneration || document.hidden) { opened.getTracks().forEach(track=>track.stop()); return; }
    media=opened; $('video').srcObject=media;
    try { await $('video').play(); }
    catch(error) { if(request===cameraGeneration)closeCamera(); else opened.getTracks().forEach(track=>track.stop()); throw error; }
    if(request!==cameraGeneration) { opened.getTracks().forEach(track=>track.stop()); return; }
    scanning=true; message('Waiting for an Arc7 file header…');
    scanLoop(request);
  }
  function scanLoop(request) {
    if (!scanning || request!==cameraGeneration) return;
    try {
      const video=$('video');
      if (video.readyState >= 2 && video.videoWidth && !header && !verifying) {
        const canvas=$('capture'), scale=Math.min(1,960/video.videoWidth);
        canvas.width=Math.round(video.videoWidth*scale); canvas.height=Math.round(video.videoHeight*scale);
        const c=canvas.getContext('2d',{willReadFrequently:true}); c.drawImage(video,0,0,canvas.width,canvas.height);
        const image=c.getImageData(0,0,canvas.width,canvas.height), result=jsQR(image.data,image.width,image.height,{inversionAttempts:'dontInvert'});
        if (result && result.data.startsWith('A7Q1|')) acceptText(result.data);
      }
    } catch (error) { failure(error); }
    if (scanning && request===cameraGeneration) setTimeout(()=>scanLoop(request),170);
  }
  function acceptText(text) {
    const frame=protocol.parse(text);
    if (!rx) {
      if (!frame.manifest) return;
      header=frame;
      $('consentText').textContent='Receive “'+frame.name+'” ('+frame.size+' bytes, '+frame.total+' frames)? Nothing is saved or opened automatically.';
      $('consent').hidden=false; return;
    }
    rx.accept(frame);
    message(rx.metadata.name+' · '+rx.seen.size+' / '+rx.metadata.total+' frames');
    $('receiveInfo').textContent='Missing frame numbers: '+rx.missing()+'. Keep scanning the loop, or ask the sender to replay those frames.';
    if (rx.complete() && !verifying) {
      verifying=true; closeCamera(); message('Verifying complete file SHA-256…');
      const sink=rx, request=receiveGeneration;
      sink.verify().then(bytes=>{
        if(request!==receiveGeneration || sink!==rx) return;
        message('All bytes received · SHA-256 verified');
        $('receiveInfo').textContent=rx.metadata.name+' · SHA-256 '+rx.metadata.sha256;
        if(download) URL.revokeObjectURL(download);
        download=URL.createObjectURL(new Blob([bytes],{type:'application/octet-stream'}));
        $('download').href=download; $('download').download=rx.metadata.name; $('download').hidden=false;
      }).catch(error=>{if(request===receiveGeneration)failure(error);}).finally(()=>{if(request===receiveGeneration)verifying=false;});
    }
  }
  $('file').addEventListener('change',async event=>{
    try {
      stopSend(); const request=++fileGeneration; const file=event.target.files[0]; if(!file) return;
      if(file.size>protocol.MAX) throw new Error('Choose a file up to 1 MiB; optical QR is slow. Use LAN sharing for larger files.');
      const ready=await protocol.sender(new Uint8Array(await file.arrayBuffer()),file.name);
      if(request!==fileGeneration) return;
      tx=ready; next=-1; pending=0; playing=true; $('play').textContent='Pause'; showFrame();
    } catch(error) { failure(error); }
  });
  $('play').onclick=()=>{ if(!tx) return; if(playing) stopSend(); else { playing=true; $('play').textContent='Pause'; showFrame(); } };
  $('prev').onclick=()=>{if(!tx)return;stopSend();next=Math.max(-1,displayed-1);showFrame();};
  $('next').onclick=()=>{if(!tx)return;stopSend();next=Math.min(tx.total-1,displayed+1);showFrame();};
  $('jump').onclick=()=>{if(!tx)return;const n=Number($('frame').value);if(!Number.isInteger(n)||n<0||n>tx.total)return;stopSend();next=n-1;showFrame();};
  $('camera').onclick=()=>scanCamera().catch(failure);
  $('switch').onclick=()=>{facing=facing==='environment'?'user':'environment';scanCamera().catch(failure);};
  $('stopCamera').onclick=closeCamera;
  $('accept').onclick=()=>{try{rx=new protocol.Receiver(header);header=null;$('consent').hidden=true;message('Receiving '+rx.metadata.name+'…');}catch(error){failure(error);}};
  $('decline').onclick=()=>{header=null;$('consent').hidden=true;closeCamera();message('Transfer declined');};
  $('reset').onclick=()=>{
    closeCamera(); receiveGeneration++; rx=null;header=null;verifying=false;$('consent').hidden=true;$('download').hidden=true;
    if(download)URL.revokeObjectURL(download);download=null;$('receiveInfo').textContent='';message('Ready for another transfer');
  };
  document.addEventListener('visibilitychange',()=>{if(document.hidden){stopSend();closeCamera();message('Paused while this page is hidden. Resume the loop or camera when ready.');}});
  window.addEventListener('pagehide',()=>{fileGeneration++;receiveGeneration++;stopSend();closeCamera();if(download)URL.revokeObjectURL(download);});
})();

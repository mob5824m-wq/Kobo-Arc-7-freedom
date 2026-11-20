/* Offline LAN client. No analytics, remote scripts, localStorage, whole-file buffering or WebCrypto. */
(function (root) {
  'use strict';
  var CHUNK=1048576,MAX=4294967296,busy=false,paused=false,controller=null;
  var el=function(id){return document.getElementById(id);};
  function stopCheck(){if(paused)throw new Error('Paused. Reselect the same file and click Upload to resume.');}
  function read(blob){return new Promise(function(resolve,reject){var r=new FileReader();r.onload=function(){resolve(new Uint8Array(r.result));};r.onerror=function(){reject(new Error('Could not read the selected file.'));};r.readAsArrayBuffer(blob);});}
  function later(){return new Promise(function(resolve){setTimeout(resolve,0);});}
  async function fileSha(file){
    var sha=new root.Arc7Sha256();
    for(var at=0;at<file.size;at+=CHUNK){stopCheck();sha.update(await read(file.slice(at,Math.min(at+CHUNK,file.size))));el('status').textContent='Checking '+file.name+': '+Math.floor(Math.min(file.size,at+CHUNK)*100/file.size)+'% (bounded memory)';await later();}
    stopCheck();return sha.hex();
  }
  async function api(endpoint,params,body){
    stopCheck();
    controller=typeof AbortController==='function'?new AbortController():null;
    var query=Object.keys(params).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(params[k]);}).join('&');
    var options={method:'POST',headers:{'X-Arc7-Share':root.shareKey,'Content-Type':'application/octet-stream'},body:body===undefined?'':body,credentials:'same-origin',cache:'no-store'};
    if(controller)options.signal=controller.signal;
    try{
      var response=await fetch('/upload/'+endpoint+'?'+query,options);
      if(!response.ok)throw new Error(await response.text());
      var result=await response.json();
      if(!/^[0-9a-f]{32}$/.test(result.id)||!Number.isSafeInteger(result.offset)||!Number.isSafeInteger(result.size)||result.offset<0||result.offset>result.size||result.size>MAX||typeof result.complete!=='boolean')throw new Error('Invalid upload receipt.');
      return result;
    }finally{controller=null;}
  }
  root.pauseUpload=function(){paused=true;if(controller)controller.abort();el('status').textContent='Pausing. Acknowledged chunks remain on the Kobo; Upload resumes after rechecking the selected file.';};
  root.uploadFiles=async function(){
    if(busy)return;
    var input=el('files'),files=Array.prototype.slice.call(input.files||[]);if(!files.length)return;
    if(!root.Arc7Sha256||typeof fetch!=='function'||typeof FileReader!=='function'){el('status').textContent='Use a current desktop/phone browser with FileReader and fetch. No upload was started.';return;}
    busy=true;paused=false;var last='';
    try{
      for(var i=0;i<files.length;i++){
        var file=files[i];if(file.size>MAX)throw new Error('The per-file limit is 4 GiB.');
        var sha=await fileSha(file);stopCheck();
        var state=await api('init',{path:el('currentPath').value,name:file.name,size:file.size,sha256:sha});
        if(state.size!==file.size||state.sha256!==sha)throw new Error('File identity differs from the receipt.');
        while(!state.complete&&state.offset<file.size){
          stopCheck();var start=state.offset,bytes=await read(file.slice(start,Math.min(start+CHUNK,file.size)));stopCheck();
          el('status').textContent='Uploading '+file.name+' · '+(i+1)+'/'+files.length+' · '+start+' / '+file.size+' bytes';
          state=await api('chunk',{id:state.id,offset:start,sha256:root.Arc7Sha256.hex(bytes)},bytes);
          if(state.offset!==start+bytes.length)throw new Error('Acknowledged offset differs; reselect the file to recover.');
          el('progress').value=file.size?Math.floor(state.offset*100/file.size):100;
        }
        stopCheck();el('status').textContent='Verifying final SHA-256 on the Kobo for '+file.name+'…';
        state=await api('finish',{id:state.id});
        if(!state.complete||state.offset!==file.size||state.sha256!==sha)throw new Error('Final checksum receipt is invalid.');
        last='Saved '+state.name+' · '+state.size+' bytes · SHA-256 '+sha;
        el('progress').value=100;el('status').textContent=last;
      }
      el('status').textContent='All selected files verified. '+last+' · Refresh to list saved files. Selecting identical bytes/name again reuses the receipt, not a duplicate.';
    }catch(error){
      el('status').textContent=(paused?'Paused. ':'Upload stopped: '+error.message+' ')+(last?last+' · ':'')+'Acknowledged chunks are retained. Click Upload with the same file to resume; after share expiry, scan a new QR for the same folder. Nothing is published until the final checksum passes.';
    }finally{busy=false;controller=null;}
  };
}(typeof globalThis!=='undefined'?globalThis:this));

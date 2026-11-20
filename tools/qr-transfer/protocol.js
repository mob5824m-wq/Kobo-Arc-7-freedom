/* Arc7 optical bytes v2 (64 MiB); receives v1 and can send legacy 1 MiB frames. No network APIs. */
(function (root) {
  'use strict';
  const CHUNK = 512, MAX = 64 * 1048576, LEGACY_MAX = 1048576, MAX_TEXT = 1472;
  const encoder = new TextEncoder(), decoder = new TextDecoder('utf-8', {fatal:true});
  const crypt = root.crypto || (typeof require === 'function' ? require('node:crypto').webcrypto : null);
  const assert = (ok, text) => { if (!ok) throw new Error(text); };
  const hex = bytes => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  const b64 = bytes => btoa(Array.from(bytes, b => String.fromCharCode(b)).join(''));
  const unb64 = text => {
    assert(text.length <= 1368 && text.length % 4 === 0 && /^[A-Za-z0-9+/]*={0,2}$/.test(text), 'Invalid base64');
    const bytes = Uint8Array.from(atob(text), c => c.charCodeAt(0));
    assert(b64(bytes) === text, 'Noncanonical base64'); return bytes;
  };
  const crc = text => {
    let value = -1;
    for (let i = 0; i < text.length; i++) {
      assert(text.charCodeAt(i) < 128, 'QR framing must be ASCII'); value ^= text.charCodeAt(i);
      for (let j = 0; j < 8; j++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
    }
    return ((value ^ -1) >>> 0).toString(16).padStart(8, '0');
  };
  const seal = body => { const text = body + '|' + crc(body); assert(text.length <= (body.startsWith('A7Q1|') ? 271 : MAX_TEXT), 'QR exceeds capacity'); return text; };
  const count = (size, chunk) => Math.max(1, Math.ceil(size / chunk));
  const number = (text, low, high) => { assert(/^(0|[1-9][0-9]{0,8})$/.test(text), 'Invalid frame number'); const n = Number(text); assert(n >= low && n <= high, 'Frame value out of bounds'); return n; };
  const density = (chunk, version) => { assert(version === 1 ? chunk === 128 : [128,512,1024].includes(chunk), 'Unsupported QR density'); return chunk; };
  const safeName = name => {
    let n = String(name).replace(/[\x00-\x1f\x7f/\\:|]/g, '_').replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '').replace(/^\.+/, '') || 'received-file';
    let points = Array.from(n);
    while (encoder.encode(points.join('')).length > 96) points.pop();
    return points.join('');
  };
  const digest = async bytes => {
    assert(crypt && crypt.subtle, 'SHA-256 requires a secure browser context. Open this local HTML file directly, or use HTTPS/localhost.');
    return hex(new Uint8Array(await crypt.subtle.digest('SHA-256', bytes)));
  };
  const headerText = m => seal(['A7Q'+m.version,'M',m.id,m.size,m.chunk,m.total,m.sha256,b64(encoder.encode(m.name))].join('|'));
  function parse(text) {
    assert(typeof text === 'string' && text.length <= MAX_TEXT && /^A7Q[12]\|/.test(text), 'Not an Arc7 byte-transfer QR');
    const version = Number(text.charAt(3)), maximum = version === 1 ? LEGACY_MAX : MAX;
    assert(version !== 1 || text.length <= 271, 'Legacy QR frame is too large');
    const last = text.lastIndexOf('|'), body = text.slice(0,last), parts = body.split('|');
    assert(crc(body) === text.slice(last+1), 'Frame CRC mismatch; rescan');
    const kind = parts[1], id = parts[2];
    assert(/^[0-9a-f]{16}$/.test(id), 'Invalid transfer ID');
    if (kind === 'M' && parts.length === 8) {
      const size = number(parts[3],0,maximum), chunk = density(number(parts[4],128,1024),version), total = number(parts[5],1,maximum/chunk);
      const nameBytes = unb64(parts[7]), name = decoder.decode(nameBytes), sha256 = parts[6];
      assert(total === count(size,chunk) && /^[0-9a-f]{64}$/.test(sha256) && nameBytes.length > 0 && nameBytes.length <= 96
        && safeName(name) === name && b64(encoder.encode(name)) === parts[7], 'Unsafe file metadata');
      return {manifest:true,version,chunk,id,size,total,name,sha256};
    }
    assert(kind === 'D' && parts.length === (version===1 ? 6 : 7), 'Unknown QR protocol frame');
    const chunk = version===1 ? 128 : density(number(parts[5],128,1024),version);
    const total = number(parts[4],1,maximum/chunk), index = number(parts[3],0,total-1), bytes = unb64(parts[version===1?5:6]);
    assert(bytes.length <= chunk, 'Payload too large'); return {manifest:false,version,chunk,id,total,index,bytes};
  }
  async function sender(bytes, name, requestedId, options) {
    options = options || {}; const version=options.legacy ? 1 : 2, chunk=density(options.chunk || (version===1?128:CHUNK),version);
    assert(bytes && Number.isInteger(bytes.length) && bytes.length >= 0, 'Expected file bytes');
    assert(bytes.length <= (version===1?LEGACY_MAX:MAX), version===1 ? 'Compatibility mode supports 1 MiB. Choose Standard or Dense for larger files.' : 'Optical transfer limit is 64 MiB.');
    bytes = Uint8Array.from(bytes);
    const sha256 = await digest(bytes), safe = safeName(name);
    // Content/name/profile identity matches Android, enabling receiver resume after reopening the sender.
    const id = requestedId || (await digest(encoder.encode(sha256+'\n'+safe+'\n'+version+'\n'+chunk))).slice(0,16);
    assert(/^[0-9a-f]{16}$/.test(id), 'Invalid transfer ID');
    const size = bytes.length, total = count(size,chunk);
    const m = {size,total,name:safe,sha256,id,version,chunk};
    return Object.assign(m, {
      manifest:() => headerText(m),
      data:index => {
        assert(Number.isInteger(index) && index >= 0 && index < total, 'Frame out of range');
        const fields=['A7Q'+version,'D',id,index,total]; if(version===2)fields.push(chunk);
        fields.push(b64(bytes.subarray(index*chunk,Math.min(size,(index+1)*chunk)))); return seal(fields.join('|'));
      }
    });
  }
  class Receiver {
    constructor(header) {
      assert(header && header.manifest, 'Scan a header first');
      const meta = parse(headerText(header));
      this.metadata = meta; this.bytes = new Uint8Array(meta.size); this.verified = false;
      // Bounded bitmap, not a Set of hundreds of thousands of JS heap objects.
      const bits=new Uint8Array(Math.ceil(meta.total/8));
      this.seen={size:0,has:index => !!(bits[index>>>3] & (1<<(index&7))),add:index=>{bits[index>>>3]|=1<<(index&7);this.seen.size++;}};
    }
    accept(frame) {
      const m = this.metadata;
      assert(frame.id === m.id && frame.total === m.total && frame.version===m.version && frame.chunk===m.chunk, 'Different transfer/profile; reset before receiving another file');
      if (frame.manifest) { assert(frame.sha256 === m.sha256 && frame.size === m.size && frame.name === m.name, 'Conflicting header'); return false; }
      const expected = Math.max(0,Math.min(m.chunk,m.size-frame.index*m.chunk));
      assert(Number.isInteger(frame.index) && frame.index >= 0 && frame.index < m.total && frame.bytes.length === expected, 'Incorrect frame length');
      if (this.seen.has(frame.index)) { assert(frame.bytes.every((b,i) => this.bytes[frame.index*m.chunk+i] === b), 'Conflicting duplicate frame; reset and resend'); return false; }
      this.bytes.set(frame.bytes,frame.index*m.chunk); this.seen.add(frame.index); this.verified = false; return true;
    }
    complete() { return this.seen.size === this.metadata.total; }
    missing() { const n = []; for (let i=0;i<this.metadata.total && n.length<12;i++) if (!this.seen.has(i)) n.push(i+1); return n.join(', ') + (this.metadata.total-this.seen.size>12?' …':''); }
    async verify() { assert(this.complete(),'Frames are still missing'); assert(await digest(this.bytes) === this.metadata.sha256,'File SHA-256 mismatch. Nothing was saved. Reset and resend.'); this.verified = true; return this.bytes; }
  }
  const api = {CHUNK,MAX,LEGACY_MAX,MAX_TEXT,parse,sender,Receiver,safeName,crc,seal,digest};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Arc7Optical = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);

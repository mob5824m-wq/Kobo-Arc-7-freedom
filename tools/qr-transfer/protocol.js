/* Arc7 optical byte protocol v1. Same framing/bounds as Arc7QrTransfer.java. No network APIs. */
(function (root) {
  'use strict';
  const CHUNK = 128, MAX = 1048576, encoder = new TextEncoder(), decoder = new TextDecoder('utf-8', {fatal:true});
  const crypt = root.crypto || (typeof require === 'function' ? require('node:crypto').webcrypto : null);
  const assert = (ok, text) => { if (!ok) throw new Error(text); };
  const hex = bytes => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  const b64 = bytes => btoa(Array.from(bytes, b => String.fromCharCode(b)).join(''));
  const unb64 = text => {
    assert(text.length <= 172 && text.length % 4 === 0 && /^[A-Za-z0-9+/]*={0,2}$/.test(text), 'Invalid base64');
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
  const seal = body => { const text = body + '|' + crc(body); assert(text.length <= 271, 'QR exceeds capacity'); return text; };
  const count = size => Math.max(1, Math.ceil(size / CHUNK));
  const number = (text, low, high) => { assert(/^(0|[1-9][0-9]{0,6})$/.test(text), 'Invalid frame number'); const n = Number(text); assert(n >= low && n <= high, 'Frame value out of bounds'); return n; };
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
  function parse(text) {
    assert(typeof text === 'string' && text.length <= 271 && text.startsWith('A7Q1|'), 'Not an Arc7 byte-transfer QR');
    const last = text.lastIndexOf('|'), body = text.slice(0,last), parts = body.split('|');
    assert(crc(body) === text.slice(last+1), 'Frame CRC mismatch; rescan');
    const [version, kind, id] = parts;
    assert(/^[0-9a-f]{16}$/.test(id), 'Invalid transfer ID');
    if (kind === 'M' && parts.length === 8) {
      const size = number(parts[3],0,MAX), chunk = number(parts[4],CHUNK,CHUNK), total = number(parts[5],1,MAX/CHUNK);
      const nameBytes = unb64(parts[7]), name = decoder.decode(nameBytes), sha256 = parts[6];
      assert(total === count(size) && /^[0-9a-f]{64}$/.test(sha256) && nameBytes.length > 0 && nameBytes.length <= 96
        && safeName(name) === name && b64(encoder.encode(name)) === parts[7], 'Unsafe file metadata');
      return {manifest:true,id,size,total,name,sha256};
    }
    assert(kind === 'D' && parts.length === 6, 'Unknown QR protocol frame');
    const total = number(parts[4],1,MAX/CHUNK), index = number(parts[3],0,total-1), bytes = unb64(parts[5]);
    assert(bytes.length <= CHUNK, 'Payload too large'); return {manifest:false,id,total,index,bytes};
  }
  async function sender(bytes, name, requestedId) {
    bytes = Uint8Array.from(bytes); assert(bytes.length <= MAX, 'Optical transfer limit is 1 MiB. Use LAN sharing for large files.');
    const sha256 = await digest(bytes), id = requestedId || hex(crypt.getRandomValues(new Uint8Array(8)));
    assert(/^[0-9a-f]{16}$/.test(id), 'Invalid transfer ID');
    const size = bytes.length, total = count(size), safe = safeName(name);
    return {size,total,name:safe,sha256,id,
      manifest:() => seal(['A7Q1','M',id,size,CHUNK,total,sha256,b64(encoder.encode(safe))].join('|')),
      data:index => { assert(Number.isInteger(index) && index >= 0 && index < total, 'Frame out of range'); return seal(['A7Q1','D',id,index,total,b64(bytes.slice(index*CHUNK,(index+1)*CHUNK))].join('|')); }
    };
  }
  class Receiver {
    constructor(header) {
      assert(header && header.manifest, 'Scan a header first');
      // Re-parse through the public wire format before allocating from untrusted metadata.
      const meta = parse(seal(['A7Q1','M',header.id,header.size,CHUNK,header.total,header.sha256,b64(encoder.encode(header.name))].join('|')));
      this.metadata = meta; this.bytes = new Uint8Array(meta.size); this.seen = new Set(); this.verified = false;
    }
    accept(frame) {
      const m = this.metadata;
      assert(frame.id === m.id && frame.total === m.total, 'Different transfer; reset before receiving another file');
      if (frame.manifest) { assert(frame.sha256 === m.sha256 && frame.size === m.size && frame.name === m.name, 'Conflicting header'); return false; }
      const expected = Math.max(0,Math.min(CHUNK,m.size-frame.index*CHUNK));
      assert(frame.index >= 0 && frame.index < m.total && frame.bytes.length === expected, 'Incorrect frame length');
      if (this.seen.has(frame.index)) { assert(frame.bytes.every((b,i) => this.bytes[frame.index*CHUNK+i] === b), 'Conflicting duplicate frame; reset and resend'); return false; }
      this.bytes.set(frame.bytes,frame.index*CHUNK); this.seen.add(frame.index); this.verified = false; return true;
    }
    complete() { return this.seen.size === this.metadata.total; }
    missing() { const n = []; for (let i=0;i<this.metadata.total && n.length<12;i++) if (!this.seen.has(i)) n.push(i+1); return n.join(', ') + (this.metadata.total-this.seen.size>12?' …':''); }
    async verify() { assert(this.complete(),'Frames are still missing'); assert(await digest(this.bytes) === this.metadata.sha256,'File SHA-256 mismatch. Nothing was saved. Reset and resend.'); this.verified = true; return this.bytes; }
  }
  const api = {CHUNK,MAX,parse,sender,Receiver,safeName,crc,seal,digest};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Arc7Optical = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);

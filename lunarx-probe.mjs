import crypto from "node:crypto";

const UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
const KEY0 = "RLxi7IOWuU1siL1B8LVrMtFtf2vo9HeeWORTbUjSF0Gt3y3RpXwY8cDiDtq3qwdclpv1TF4";
const KEY1 = "uTMtgEBM80im6vKqubDWvQmjmfvEjsDMATYjdxP34C01SNNEhBAj4F1RQYZRD47DcL3N3GXnacUtHh0F";

const b64u = (buf) => Buffer.from(buf).toString("base64url");
const randStr = (n) => {
  const c = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  let s = "";
  for (let i = 0; i < n; i++) s += c[Math.floor(Math.random() * c.length)];
  return s;
};

async function mint(slug, chapter) {
  const k0 = Buffer.from(KEY0), k1 = Buffer.from(KEY1);
  const h = crypto.createHash("sha256").update(Buffer.concat([k0, Buffer.from([1]), k1])).digest();
  const len = Math.max(k0.length, k1.length);
  const key = Buffer.alloc(len);
  for (let i = 0; i < len; i++) {
    key[i] = (k0[i % k0.length] ^ k1[i % k1.length] ^ h[i % 32] ^ ((83 * i + 29) & 0xff)) & 0xff;
  }
  const nonce = randStr(12);
  const hex = Math.floor(Date.now() / 1000).toString(16);
  const body = `${hex}|${nonce}|${slug}|${chapter}|${randStr(6)}`;
  const n = Math.floor(Math.random() * 256);
  const bodyBytes = Buffer.from(body, "utf8");
  const out = Buffer.alloc(bodyBytes.length + 1);
  out[0] = n;
  for (let i = 0; i < bodyBytes.length; i++) {
    out[i + 1] = (bodyBytes[i] ^ key[(i + n) % key.length] ^ ((n + 83 * i) & 0xff)) & 0xff;
  }
  return { token: b64u(out), nonce };
}

const kp = crypto.generateKeyPairSync("ec", { namedCurve: "prime256v1" });
const pub = kp.publicKey.export({ format: "jwk" });
const header = b64u(JSON.stringify({ alg: "ES256", typ: "dpop+jwt", jwk: { crv: "P-256", kty: "EC", x: pub.x, y: pub.y } }));
function proof(htu) {
  const iat = Math.floor(Date.now() / 1000);
  const payload = b64u(JSON.stringify({ htu, htm: "GET", iat, jti: randStr(18) + "-" + Date.now() }));
  const sig = crypto.createSign("SHA256").update(`${header}.${payload}`).sign({ key: kp.privateKey, dsaEncoding: "ieee-p1363" });
  return `${header}.${payload}.${b64u(sig)}`;
}

async function req(url, extra) {
  try {
    const r = await fetch(url, {
      headers: {
        Origin: "https://lunarx.to",
        Referer: "https://lunarx.to/",
        "User-Agent": UA,
        Accept: "application/json",
        ...extra,
      },
    });
    const t = await r.text();
    return { status: r.status, ctype: r.headers.get("content-type"), body: t.slice(0, 2500) };
  } catch (e) {
    return { err: String(e) };
  }
}

const out = {};

// 1. plain search with Origin
out.search = await req("https://api.lunarx.to/api/manga/search?page=1&limit=1", {});

// 2. reader, en, X-Native-App
let { token, nonce } = await mint("one-piece", "1");
let htu = `https://api.lunarx.to/api/manga/r/${token}`;
out.readerNative = await req(htu, { "cant-catch-this-monkey": proof(htu), "X-Native-App": "true" });

// 3. reader, en, no X-Native-App
({ token, nonce } = await mint("one-piece", "1"));
htu = `https://api.lunarx.to/api/manga/r/${token}`;
out.readerWeb = await req(htu, { "cant-catch-this-monkey": proof(htu) });

// 4. reader without dpop at all
({ token } = await mint("one-piece", "1"));
htu = `https://api.lunarx.to/api/manga/r/${token}`;
out.readerNoDpop = await req(htu, { "X-Native-App": "true" });

// 5. non-en chapter: htu WITHOUT query
({ token } = await mint("one-piece", "1"));
const htuNoQ = `https://api.lunarx.to/api/manga/r/${token}`;
out.readerNonEnNoQuery = await req(`${htuNoQ}?language=es`, { "cant-catch-this-monkey": proof(htuNoQ) });

// 6. non-en chapter: htu WITH query
({ token } = await mint("one-piece", "1"));
const htuWithQ = `https://api.lunarx.to/api/manga/r/${token}?language=es`;
out.readerNonEnWithQuery = await req(htuWithQ, { "cant-catch-this-monkey": proof(htuWithQ) });

console.log(JSON.stringify(out, null, 2));

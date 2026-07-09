// Optional persistence shim for free "sleeps after inactivity" hosting tiers
// (Render, etc.), which wipe the coordinator's local filesystem on every
// spin-down/spin-up cycle. The coordinator's in-memory group/queue state is
// already designed to tolerate a restart losing it (see server.js) - but the
// save zip itself is the one thing that must never just vanish between play
// sessions. This backs it up to a GitHub Release (as an asset) in the same
// repo right after every successful upload, and restores everything back to
// local disk once at startup, before the server starts accepting requests.
//
// Entirely a no-op unless GITHUB_TOKEN + GITHUB_REPO are set, so local dev
// and test_migration.js are completely unaffected.

const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');

const GITHUB_TOKEN = process.env.GITHUB_TOKEN || '';
const GITHUB_REPO = process.env.GITHUB_REPO || ''; // "owner/repo"
const RELEASE_TAG = process.env.GITHUB_BACKUP_TAG || 'coordinator-saves-backup';

const enabled = Boolean(GITHUB_TOKEN && GITHUB_REPO);

function apiRequest(method, urlPath, { body, headers = {}, host = 'api.github.com' } = {}) {
  return new Promise((resolve, reject) => {
    const req = https.request({
      method,
      host,
      path: urlPath,
      headers: {
        'User-Agent': 'campfire-coordinator',
        Authorization: `Bearer ${GITHUB_TOKEN}`,
        Accept: 'application/vnd.github+json',
        ...headers,
      },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const data = Buffer.concat(chunks);
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve({ status: res.statusCode, data });
        } else {
          reject(new Error(`GitHub API ${method} ${urlPath} -> ${res.statusCode}: ${data.toString().slice(0, 300)}`));
        }
      });
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

// The release-asset download endpoint replies with either the bytes directly
// or a redirect to a signed, time-limited CDN URL - never forward the GitHub
// token to that second host, it doesn't need it and doesn't want it.
function downloadFollowingRedirects(url, extraHeaders = {}) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const lib = parsed.protocol === 'http:' ? http : https;
    lib.get(url, { headers: { 'User-Agent': 'campfire-coordinator', ...extraHeaders } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        resolve(downloadFollowingRedirects(res.headers.location));
        return;
      }
      if (res.statusCode < 200 || res.statusCode >= 300) {
        reject(new Error(`download ${url} -> ${res.statusCode}`));
        return;
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve(Buffer.concat(chunks)));
    }).on('error', reject);
  });
}

let releaseCache = null; // { id, assets: [{id, name}] } - invalidated whenever assets change

async function getOrCreateRelease() {
  if (releaseCache) return releaseCache;
  try {
    const { data } = await apiRequest('GET', `/repos/${GITHUB_REPO}/releases/tags/${RELEASE_TAG}`);
    releaseCache = JSON.parse(data.toString());
  } catch {
    // Doesn't exist yet - create it. Not meant to be seen; just a container
    // to hang per-group save-zip assets off of.
    const { data } = await apiRequest('POST', `/repos/${GITHUB_REPO}/releases`, {
      body: JSON.stringify({ tag_name: RELEASE_TAG, name: 'Coordinator save backups', prerelease: true, draft: false }),
      headers: { 'Content-Type': 'application/json' },
    });
    releaseCache = JSON.parse(data.toString());
  }
  return releaseCache;
}

// Fire-and-forget from the upload handler, right after a save lands on
// local disk. Never throws - a failed backup shouldn't fail the upload
// response the mod is waiting on; it just logs and leaves the next
// successful upload to try again.
async function backupSaveToGithub(groupId, buf) {
  if (!enabled) return;
  try {
    const release = await getOrCreateRelease();
    const assetName = `${groupId}.zip`;
    const existing = (release.assets || []).find((a) => a.name === assetName);
    if (existing) {
      await apiRequest('DELETE', `/repos/${GITHUB_REPO}/releases/assets/${existing.id}`);
    }
    await apiRequest('POST', `/repos/${GITHUB_REPO}/releases/${release.id}/assets?name=${encodeURIComponent(assetName)}`, {
      host: 'uploads.github.com',
      body: buf,
      headers: { 'Content-Type': 'application/zip', 'Content-Length': buf.length },
    });
    releaseCache = null; // asset list just changed - refetch next time
    console.log(`[github-backup] ${groupId} backed up (${(buf.length / 1024 / 1024).toFixed(1)} MB)`);
  } catch (e) {
    console.log(`[github-backup] ${groupId} backup failed: ${e.message}`);
  }
}

// Runs once at startup, before the server accepts any requests, so a
// group's in-memory saveVersion (seeded from whether a local zip exists)
// never gets seeded wrong by a cold start that hasn't restored yet.
async function restoreAllSaves(savesDir) {
  if (!enabled) return;
  try {
    const release = await getOrCreateRelease();
    for (const asset of release.assets || []) {
      if (!asset.name.endsWith('.zip')) continue;
      const dest = path.join(savesDir, asset.name);
      if (fs.existsSync(dest)) continue; // local copy already present - don't clobber something newer
      const assetUrl = `https://api.github.com/repos/${GITHUB_REPO}/releases/assets/${asset.id}`;
      const buf = await downloadFollowingRedirects(assetUrl, {
        Authorization: `Bearer ${GITHUB_TOKEN}`,
        Accept: 'application/octet-stream',
      });
      fs.writeFileSync(dest, buf);
      console.log(`[github-backup] restored ${asset.name} (${(buf.length / 1024 / 1024).toFixed(1)} MB)`);
    }
  } catch (e) {
    console.log(`[github-backup] restore failed: ${e.message}`);
  }
}

module.exports = { enabled, backupSaveToGithub, restoreAllSaves };

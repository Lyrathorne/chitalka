"""Publish immutable Windows assets using existing Git credentials; never prints credentials."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import urllib.request
import urllib.error
import urllib.parse
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'tools'))
from github_release import token

p=argparse.ArgumentParser()
p.add_argument('--tag',required=True)
p.add_argument('--title',required=True)
p.add_argument('--notes',type=Path,required=True)
p.add_argument('--asset',type=Path,action='append',default=[])
p.add_argument('--verify',action='store_true')
a=p.parse_args()
credential=token()
def request(method,path,data=None,raw=None):
    url=path if path.startswith('https://') else 'https://api.github.com'+path
    if urllib.parse.urlparse(url).hostname not in {'api.github.com','uploads.github.com'}:raise RuntimeError('Unexpected API host')
    headers={'Authorization':'Bearer '+credential,'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','User-Agent':'Chitalka-Windows-release'}
    if data is not None:raw=json.dumps(data).encode();headers['Content-Type']='application/json'
    elif raw is not None:headers['Content-Type']='application/octet-stream'
    with urllib.request.urlopen(urllib.request.Request(url,data=raw,headers=headers,method=method),timeout=240) as r:return json.load(r)
if request('GET','/user')['login'].lower()!='lyrathorne':raise RuntimeError('Wrong account')
base='/repos/Lyrathorne/chitalka'
repo=request('GET',base)
if repo['private']:raise RuntimeError('Expected public repository')
try:release=request('GET',base+'/releases/tags/'+urllib.parse.quote(a.tag,safe=''))
except urllib.error.HTTPError as e:
    if e.code!=404 or a.verify:raise
    release=request('POST',base+'/releases',data={'tag_name':a.tag,'target_commitish':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'name':a.title,'body':a.notes.read_text(encoding='utf8'),'prerelease':True,'draft':False})
for path in a.asset:
    digest='sha256:'+hashlib.sha256(path.read_bytes()).hexdigest()
    asset=next((x for x in release.get('assets',[]) if x['name']==path.name),None)
    if asset:
        if asset['size']!=path.stat().st_size or asset.get('digest')!=digest:raise RuntimeError('Existing asset differs: '+path.name)
    elif a.verify:raise RuntimeError('Missing asset: '+path.name)
    else:asset=request('POST',release['upload_url'].split('{')[0]+'?'+urllib.parse.urlencode({'name':path.name}),raw=path.read_bytes())
    if asset.get('digest')!=digest:raise RuntimeError('GitHub SHA256 differs: '+path.name)
    print(json.dumps({'url':asset['browser_download_url'],'bytes':asset['size'],'sha256':digest,'verified':True}))
print(release['html_url'])

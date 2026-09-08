"""Read/negative replay audit of packaged development artifacts; never opens new seeds."""
from pathlib import Path
import json,subprocess,shutil,hashlib,time
s=Path(__file__).resolve().parent
config=json.load(open(s/'benchmark-2-config.json')); base=s/'benchmark-2-worker'; results=[]
def run(label,folder):
 a=list(config['argv']);a[a.index('--ledger')+1]=str(folder/'ledger.json');a[a.index('--out')+1]=str(folder/(label+'-result.json'));start=time.monotonic()
 p=subprocess.run(a,cwd=config['cwd'],capture_output=True,text=True,timeout=60)
 (folder/(label+'.log')).write_text(p.stdout+p.stderr)
 return {'case':label,'exit_code':p.returncode,'wall_seconds':time.monotonic()-start,'output_tail':(p.stdout+p.stderr)[-1200:]}
before=json.load(open(base/'ledger.json')); r=run('resume',base);after=json.load(open(base/'ledger.json'));r.update(attempts_before=len(before['attempts']),attempts_after=len(after['attempts']),attempts_unchanged=before['attempts']==after['attempts']);assert r['exit_code']==0 and r['attempts_unchanged'];results.append(r)
for case in ['corrupt-artifact','missing-artifact']:
 d=s/case;shutil.copytree(base,d,dirs_exist_ok=False);target=next((d/'ledger.json.results').glob('*.json'))
 if case=='missing-artifact':target.unlink()
 else:
  obj=json.load(open(target));obj['row']['event_count']=999;target.write_text(json.dumps(obj)+'\n')
 r=run(case,d);assert r['exit_code']!=0;results.append(r)
(s/'packaged-resume-audit.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps([{k:r[k] for k in ['case','exit_code','wall_seconds']} for r in results],indent=2))

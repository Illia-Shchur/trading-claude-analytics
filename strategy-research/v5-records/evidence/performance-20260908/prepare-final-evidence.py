from pathlib import Path
import json,subprocess,shutil
root=Path.cwd(); scratch=root/'.report-run/performance-20260908'; jar=scratch/'executor-final.jar'
def cli(*args):
 p=subprocess.run(['java','-Xmx2g','-jar',str(jar),*args],cwd='/tmp',check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 return json.loads(p.stdout)
identity=cli('build-identity'); (scratch/'final-build-identity.json').write_text(json.dumps(identity,indent=2)+'\n')
profile=cli('strategy-research-v5','operating-characteristics-parallel-profile','--requested-workers','8')
(scratch/'final-execution-profile.json').write_text(json.dumps(profile,indent=2)+'\n')
plan=json.load(open(scratch/'smoke-plan-v002.json'))
plan.update(execution_profile_sha256=profile['content_sha256'],executor_identity_sha256=identity['executable']['sha256'],executor_build_input_fingerprint=identity['compiled']['input_fingerprint'],content_sha256='')
plan['executor_source_sha256']=identity['compiled']['input_fingerprint']
p=scratch/'benchmark-plan-final.json'; p.write_text(json.dumps(plan,indent=2)+'\n')
l=scratch/'final-hash-paths.txt'; l.write_text(str(p)+'\n')
hashes=cli('strategy-research-v5','canonical-hash-batch','--paths-file',str(l));plan['content_sha256']=hashes['files'][0]['content_sha256'];p.write_text(json.dumps(plan,indent=2)+'\n')
base=json.load(open(scratch/'smoke-v002-config.json'))
for workers in [1,2]:
 out=scratch/f'benchmark-{workers}-worker'; out.mkdir(exist_ok=True)
 c=json.loads(json.dumps(base)); a=c['argv']; a[a.index('-jar')+1]=str(jar);a[a.index('--plan')+1]=str(p);a[a.index('--profile')+1]=str(scratch/'final-execution-profile.json');a[a.index('--workers')+1]=str(workers);a[a.index('--ledger')+1]=str(out/'ledger.json');a[a.index('--out')+1]=str(out/'result.json');c['measurement_output']=str(out/'measurement.json');c['sample_tree']=str(out);c['timeout_seconds']=300
 (scratch/f'benchmark-{workers}-config.json').write_text(json.dumps(c,indent=2)+'\n')
print(json.dumps({'executor':identity['executable']['sha256'],'source':identity['compiled']['input_fingerprint'],'profile':profile['content_sha256'],'plan':plan['content_sha256'],'workers':profile['effective_workers']},indent=2))

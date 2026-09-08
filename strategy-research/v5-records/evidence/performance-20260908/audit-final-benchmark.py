from pathlib import Path
import json,copy,hashlib
s=Path(__file__).resolve().parent
measurements=[]
for n in [1,2]:
 m=json.load(open(s/f'benchmark-{n}-worker/measurement.json'));r=json.load(open(s/f'benchmark-{n}-worker/result.json'))
 assert r['status']=='COMPLETE' and r['completed_slots']==2 and m['exit_code']==0 and not m['timed_out'] and not m['probe_errors']
 measurements.append({'workers':n,'status':r['status'],**{k:m[k] for k in ['wall_seconds','sampled_aggregate_peak_rss_bytes','sampled_managed_peak_disk_bytes','exit_code']}})
def portable_projection(raw):
 v=copy.deepcopy(raw)
 for k in ['content_sha256','semantic_sha256','economic_semantic_sha256','build_identity','exposure_head_sha256','exposure_head_path','executor_identity_sha256','attempt_identity_sha256','legacy_exposure_migration','portfolio_policy_path','lifecycle_timing_policy_path']:v.pop(k,None)
 def strip(x):
  if isinstance(x,dict):
   x.pop('path',None);x.pop('physical_root_reference',None)
   for y in x.values():strip(y)
  elif isinstance(x,list):
   for y in x:strip(y)
 strip(v);return v
left={p.name:json.load(open(p)) for p in (s/'benchmark-1-worker/ledger.json.results').glob('*.json')}
right={p.name:json.load(open(p)) for p in (s/'benchmark-2-worker/ledger.json.results').glob('*.json')};assert left.keys()==right.keys();checks={}
for name in sorted(left):
 a,b=left[name],right[name];x,y=a['row']['raw_evaluator_result'],b['row']['raw_evaluator_result']
 c={'portable_digest_equal':a['portable_economic_sha256']==b['portable_economic_sha256'],'portable_economic_sha256':a['portable_economic_sha256'],'entire_normalized_raw_equal':portable_projection(x)==portable_projection(y),'legacy_economic_hash_equal':x['economic_semantic_sha256']==y['economic_semantic_sha256']}
 for k in ['metrics','control_selections','attempts','portfolio']:c[k+'_equal']=x[k]==y[k]
 assert all(v for k,v in c.items() if k.endswith('_equal') and k!='legacy_economic_hash_equal')
 checks[name]=c
orders=[]
for n in [1,2]:orders.append([x['slot_id'] for x in json.load(open(s/f'benchmark-{n}-worker/result.json'))['result_refs']])
assert orders[0]==orders[1]
a={'status':'PASS','scope':'LOCAL_DEVELOPMENT_ONLY','measurements':measurements,'checks':checks,'deterministic_result_order':orders[0],'measured_wall_ratio':measurements[0]['wall_seconds']/measurements[1]['wall_seconds'],'benchmark_pairs':1,'confirmation_seeds_opened':False,'note':'Legacy economic digest includes absolute policy paths and is intentionally preserved; additive portable digest and exact normalized raw comparison provide location-independent equivalence. This single prefix pair is not 28-core throughput evidence.'}
(s/'serial-parallel-independent-audit.json').write_text(json.dumps(a,indent=2)+'\n');print(json.dumps({'status':a['status'],'measurements':measurements,'ratio':a['measured_wall_ratio']},indent=2))

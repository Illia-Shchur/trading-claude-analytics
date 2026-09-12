import json, math, hashlib, sys
from pathlib import Path
from datetime import datetime

def close(a,b):
    assert math.isclose(a,b,rel_tol=1e-10,abs_tol=1e-7),(a,b)
def time(s): return datetime.fromisoformat(s.replace('Z','+00:00'))
def audit(book):
    cash=book['starting_equity_usdt']; peak=cash; maximum=0; held={}; events=[]; net=0
    for tr in book['trades']:
        ident=tr['episode_id']; life=tr['lifecycle']; out=life['exits'][0]
        assert len(life['exits'])==1
        enter=time(life['entry_time']); leave=time(out.get('availability_time') or out['time'])
        assert enter<leave
        expected=(tr['exit_price']-tr['entry_price'])*tr['quantity']-tr['fees_usdt']-tr['slippage_usdt']-tr['capacity_debit_usdt']
        close(expected,tr['net_pnl_usdt']); net+=expected
        events += [(enter,1,ident,tr,None),(leave,0,ident,tr,None)]
        for mark in tr['portfolio_mark_points']:
            stamp=time(mark['time']); assert enter<=stamp<leave
            events.append((stamp,2,ident,tr,mark['price']))
    events.sort(key=lambda e:e[:3]); curve=book['equity_curve']; assert len(events)==len(curve)
    for (stamp,kind,ident,tr,mark),point in zip(events,curve):
        out=tr['lifecycle']['exits'][0]; q=tr['quantity']; ef=out.get('fees_usd',0); es=out.get('slippage_usd',0)
        if kind==1:
            assert ident not in held
            cash-=q*tr['entry_price']+tr['fees_usdt']-ef+tr['slippage_usdt']-es+tr['capacity_debit_usdt']
            held[ident]=q*tr['entry_price']
        elif kind==0:
            assert ident in held
            cash+=q*tr['exit_price']-ef-es; del held[ident]
        else:
            assert ident in held; held[ident]=q*mark
        equity=cash+math.fsum(held.values()); peak=max(peak,equity); maximum=max(maximum,peak-equity)
        assert time(point['event_time'])==stamp and point['episode_id']==ident
        assert point['event_type']==['EXIT','ENTRY','MARK'][kind]
        assert point['active_trade_ids']==sorted(held) and point['active_position_count']==len(held)
        close(point['cash_usdt'],cash); close(point['equity_usdt'],equity)
        close(point['marked_holdings_usdt'],math.fsum(held.values())); close(point['drawdown_usdt'],peak-equity)
    assert not held
    close(cash,book['ending_equity_usdt']); close(cash,book['starting_equity_usdt']+net)
    close(maximum,book['max_drawdown_usdt'])
    return len(events)


def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()

def combined(portfolio):
    books=[portfolio['event_book'],portfolio['control_book']]
    points=[]
    for index,book in enumerate(books):
        for ordinal,p in enumerate(book['equity_curve']):
            points.append((time(p['event_time']),{'EXIT':0,'ENTRY':1,'MARK':2}[p['event_type']],index,ordinal,p))
    points.sort(key=lambda x:x[:4]); current=[{'equity_usdt':b['starting_equity_usdt'],'cash_usdt':b['starting_equity_usdt']} for b in books]
    peak=sum(b['starting_equity_usdt'] for b in books); maximum=0
    assert len(points)==len(portfolio['combined_equity_curve'])
    for (stamp,kind,index,ordinal,p),q in zip(points,portfolio['combined_equity_curve']):
        current[index]=p
        assert time(q['event_time'])==stamp and q['event_type']==p['event_type'] and q['source_book']==['EVENT','CONTROL'][index]
        for field in ['cash_usdt','marked_holdings_usdt','holdings_at_entry_cost_usdt','equity_usdt','active_position_count']:
            close(q[field],sum(c.get(field,0) for c in current))
        peak=max(peak,q['equity_usdt']); maximum=max(maximum,peak-q['equity_usdt'])
        close(q['peak_equity_usdt'],peak);close(q['drawdown_usdt'],peak-q['equity_usdt'])
    for field in ['starting_equity_usdt','ending_equity_usdt','net_pnl_usdt','gross_pnl_usdt','fees_usdt','slippage_usdt','capacity_debit_usdt','realized_pnl_usdt']:
        close(portfolio[field],sum(b[field] for b in books))
    close(portfolio['max_drawdown_usdt'],maximum)
    close(portfolio['ending_equity_usdt'],portfolio['starting_equity_usdt']+portfolio['net_pnl_usdt'])
    assert portfolio['final_active_position_count']==0
    close(portfolio['final_marked_holdings_usdt'],0)
    close(portfolio['final_curve_equity_usdt'],portfolio['ending_equity_usdt'])
    return len(points)

def geometry(raw):
    for field,expected in {'event_count':450,'matched_control_count':450,'admitted_event_count':450,'trade_count':900,'market_episode_count':450,'independent_market_episode_count':288}.items(): assert raw[field]==expected,(field,raw[field])
    for field,expected in {'setup_events':450,'control_selections':450,'attempts':450,'independent_market_episodes':288}.items(): assert len(raw[field])==expected
    clusters=raw['independent_market_episodes']; sizes=[len(c['source_episode_ids']) for c in clusters]
    assert sizes.count(1)==126 and sizes.count(2)==162
    sources=[s for c in clusters for s in c['source_episode_ids']]
    assert len(sources)==len(set(sources))==450
    for field in ['setup_events','independent_market_episodes']:
        ids=[x['episode_id'] for x in raw[field]];assert len(ids)==len(set(ids))
    setups={x['episode_id']:x for x in raw['setup_events']}
    selections={x['event_id']:x for x in raw['control_selections']}
    assert len(selections)==450 and set(selections)==set(setups)
    control_ids=[x['control']['episode_id'] for x in selections.values()]
    assert len(set(control_ids))==450
    expected_sources={event_id+'::'+selection['control']['episode_id'] for event_id,selection in selections.items()}
    assert set(sources)==expected_sources
    for event_id,selection in selections.items():
        assert selection['outcome_blind'] is True and selection['candidate_count']>=1
        assert time(setups[event_id]['decision_time'])-time(selection['control']['decision_time'])==__import__('datetime').timedelta(days=21)
    for field,expected in {'event_tested_cluster_count':288,'paired_tested_cluster_count':288,'independent_market_episode_count':288,'paired_count':450}.items(): assert raw['metrics'][field]==expected
    eb=raw['portfolio']['event_book']['trades'];cb=raw['portfolio']['control_book']['trades']
    assert len(eb)==len(cb)==450
    event={t['episode_id']:t for t in eb};control={t['episode_id']:t for t in cb}
    assert len(event)==len(control)==450
    usede=set();usedc=set();attempts=set()
    for a in raw['attempts']:
        assert a['status']=='COMPLETE';eid=a['event_trade']['episode_id'];cid=a['control_trade']['episode_id']
        assert eid in event and cid in control and eid not in usede and cid not in usedc and a['event_id'] not in attempts
        assert a['event_id']==eid and selections[eid]['control']['episode_id']==cid
        assert a['event_trade']==event[eid] and a['control_trade']==control[cid]
        usede.add(eid);usedc.add(cid);attempts.add(a['event_id'])
        close(a['paired_net_pnl_usdt'],a['event_trade']['net_pnl_usdt']-a['control_trade']['net_pnl_usdt'])
    attr=raw['matching_attrition'];assert attr['outcome_blind'] is True
    for field,expected in {'feature_rows_to_setup_events':450,'setup_events_to_admitted_events':450,'admitted_events_to_control_selections':450,'control_selections_to_matched_controls':450,'complete_pairs_to_paired_clusters':288,'admitted_events_to_event_clusters':288}.items():assert attr['sequential_attrition'][field]==expected
    assert attr['marginal_attrition']['merged_scheduled_lifecycle_clusters']==288
    assert attempts==set(setups) and usede==set(event) and usedc==set(control)
    for c in clusters:
        assert len(c['window_intervals'])==2*len(c['source_episode_ids'])
        assert {w['source_episode_id'] for w in c['window_intervals']}==set(c['source_episode_ids'])
        assert c['start_ms']==min(w['start_ms'] for w in c['window_intervals'])
        assert c['end_ms']==max(w['end_ms'] for w in c['window_intervals'])
        for source in c['source_episode_ids']:
            event_id,control_id=source.split('::')
            expected_starts={int(time(setups[event_id]['decision_time']).timestamp()*1000),int(time(selections[event_id]['control']['decision_time']).timestamp()*1000)}
            assert {w['start_ms'] for w in c['window_intervals'] if w['source_episode_id']==source}==expected_starts
        # Planned observation windows retain the complete 10-day horizon,
        # including trades that exit sooner.
        for window in c['window_intervals']:assert window['end_ms']-window['start_ms']==14400*60000

def artifact(path, full=True):
    data=path.read_bytes();a=json.loads(data);row=a['row'];raw=row['raw_evaluator_result']
    assert a['status']=='COMPLETE' and row['status']=='COMPLETE'
    assert a['promotion_eligible'] is False and a['activation_authorized'] is False
    assert raw['accounting_version']=='PORTFOLIO_ACCOUNTING_CORRECTION_V1'
    assert raw['evaluator_identity']=='com.tradinganalytics.research.v5.StrategyFixedBaselineCorrectedV1'
    if full:
        assert a['mode']=='FULL'; geometry(raw)
        assert row['event_count']==450 and row['paired_count']==450 and row['independent_units']==288
        assert a['seed'] in {920000000,920000001,920100000,920100001,920200000,920200001,920300000,920300001}
    assert row['metrics']==raw['metrics']; assert row['portfolio_summary']==raw['portfolio']
    identity=a['executor_identity']
    expected_repository=str((path.parent/'repository').resolve())
    for field in ['cwd','user_dir']:
        assert str(Path(identity['runtime'][field]).resolve())==expected_repository,(field,identity['runtime'][field],expected_repository)
    counts=[audit(raw['portfolio'][b]) for b in ['event_book','control_book']]
    combined_points=combined(raw['portfolio'])
    return {'path':str(path),'byte_sha256':hashlib.sha256(data).hexdigest(),'slot_id':a['slot_id'],'plan_sha256':a['plan_sha256'],'run_id':a['run_id'],'seed':a['seed'],'scenario':a['scenario'],'effect_size':a['effect_size'],'replication':a['replication'],'portfolio_points':sum(counts),'combined_points':combined_points,'trades':sum(len(raw['portfolio'][b]['trades']) for b in ['event_book','control_book']),'portable_economic_sha256':a['portable_economic_sha256'],'comparison_sha256':digest([raw['metrics'],raw['portfolio']['event_book']['equity_curve'],raw['portfolio']['control_book']['equity_curve'],raw['portfolio']['combined_equity_curve']]),'executor_identity':a['executor_identity']}

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('paths',nargs='+');parser.add_argument('--out',required=True);parser.add_argument('--prefix-selftest',action='store_true');args=parser.parse_args()
    rows=[]
    for name in args.paths:
        row=artifact(Path(name),not args.prefix_selftest);rows.append(row);print('AUDITED '+name,flush=True)
    out={'status':'PASS','scope':'ENGINEERING_ONLY_NOT_QUALIFICATION','artifacts':rows,'artifacts_audited':len(rows),'independently_reconciled_books':len(rows)*2,'book_curve_points':sum(r['portfolio_points'] for r in rows),'combined_curve_points':sum(r['combined_points'] for r in rows),'trades_reconciled':sum(r['trades'] for r in rows)}
    if not args.prefix_selftest and len(rows)==16:
        seeds={}
        for row in rows:seeds.setdefault(row['seed'],[]).append(row)
        assert len(seeds)==8 and all(len(v)==2 for v in seeds.values())
        for seed,pair in seeds.items():
            assert pair[0]['comparison_sha256']==pair[1]['comparison_sha256'],seed
            assert pair[0]['portable_economic_sha256']==pair[1]['portable_economic_sha256'],seed
            left=json.loads(json.dumps(pair[0]['executor_identity']));right=json.loads(json.dumps(pair[1]['executor_identity']))
            for identity in [left,right]:
                # Each runtime path was independently bound to its own slot repository above.
                del identity['runtime']['cwd'];del identity['runtime']['user_dir']
            assert left==right,seed
            assert pair[0]['plan_sha256']==pair[1]['plan_sha256'],seed
        out['serial_parallel_metrics_and_book_and_combined_curves_equal']=True
    Path(args.out).write_text(json.dumps(out,indent=2)+'\n')

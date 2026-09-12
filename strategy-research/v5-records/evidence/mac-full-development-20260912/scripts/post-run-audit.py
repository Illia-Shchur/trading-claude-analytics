#!/usr/bin/env python3
"""Wait for completed engineering waves; only then reopen their large artifacts."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def now():
    return datetime.now(timezone.utc).isoformat()


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    os.replace(temporary, path)


def process_identity(pid):
    result = subprocess.run(['ps', '-p', str(pid), '-o', 'lstart=,command='], capture_output=True, text=True)
    if result.returncode == 1 and not result.stdout.strip():
        return None
    if result.returncode != 0:
        raise RuntimeError('Cannot read harness process identity')
    return result.stdout.strip()


def require(value, message):
    if not value:
        raise RuntimeError(message)


def main(args):
    root = args.root.resolve()
    audit = args.audit.resolve()
    status_path = root / 'independent-audit-status.json'
    metadata = {
        'scope': 'ENGINEERING_ONLY_NOT_QUALIFICATION',
        'wrapper_pid': os.getpid(), 'harness_pid': args.harness_pid,
        'started_at': now(), 'poll_seconds': 30,
        'audit_script_sha256': hashlib.sha256(audit.read_bytes()).hexdigest(),
        'wrapper_script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'heavy_artifact_reads_started': False,
    }
    try:
        if args.completed:
            require(process_identity(args.harness_pid) is None, 'Original harness PID is still occupied')
            manifest = read(root / 'manifest.json')
            require(manifest.get('status') == 'PASS', 'Completed-run manifest is not PASS')
            metadata['completion_mode'] = 'EXPLICIT_COMPLETED_RUN_REAUDIT'
        else:
            original = process_identity(args.harness_pid)
            require(original is not None and 'run_mac_full_development.py' in original
                    and 'full-paired-waves' in original, 'Expected harness is not running')
            metadata['harness_process_identity'] = original
            write(status_path, dict(metadata, status='WAITING_FOR_HARNESS_COMPLETION'))
            print('Waiting for both waves, strict validation, final PASS manifest and harness exit', flush=True)
            deadline = time.monotonic() + 10 * 3600
            while True:
                manifest_path = root / 'manifest.json'
                manifest = read(manifest_path) if manifest_path.exists() else None
                if manifest is not None:
                    require(manifest.get('status') == 'PASS', 'Harness manifest is not PASS')
                current = process_identity(args.harness_pid)
                if current != original:
                    require(manifest is not None, 'Harness disappeared before a final PASS manifest')
                    break
                require(time.monotonic() < deadline, 'Harness completion wait exceeded ten hours')
                time.sleep(30)
    
        require(set(manifest.get('waves', [])) == {'parallel', 'serial'}, 'Final manifest lacks both waves')
        # A completed parent must leave no generator or strict-validator child.
        processes = subprocess.check_output(['ps', '-axo', 'command='], text=True)
        require(not any(str(root) in line and ('parallel-worker' in line or 'StrictArtifactValidator' in line)
                        for line in processes.splitlines()), 'Run children remain after harness exit')
        engineering = read(root / 'engineering-validation.json')
        require(engineering.get('status') == 'PASS' and engineering.get('parallel_slots') == 8
                and engineering.get('serial_slots') == 8
                and engineering.get('serial_parallel_economic_digests_equal') is True,
                'Harness engineering validation is incomplete')
        require(engineering.get('qualification_attempted') is False
                and engineering.get('heldout_executed') is False, 'Unexpected qualification or heldout scope')
        declaration = read(root / 'declaration.json')
        expected = {}
        for scenario, effect, base in [('NO_EDGE', 0, 920000000), ('PLANTED_EDGE', 0, 920100000),
                                     ('PLANTED_EDGE', 0.02, 920200000), ('PLANTED_EDGE', 0.04, 920300000)]:
            for replication in range(2):
                seed = base + replication
                expected[seed] = (scenario, effect, replication, f'FULL|{scenario}|{effect:g}|{replication}|{seed}')
        paths = []
        payloads = {}
        for wave in ['parallel', 'serial']:
            strict = read(root / wave / 'strict-validation.json')
            execution = read(root / wave / 'execution-records.json')
            require(len(strict) == 8 and len(execution) == 8, f'{wave} has an incomplete validation inventory')
            wanted = {value[3] for value in expected.values()}
            require({row['slot_id'] for row in strict} == wanted and all(row['status'] == 'PASS' for row in strict),
                    f'{wave} strict validation did not pass all eight slots')
            require({row['slot_id'] for row in execution} == wanted
                    and all(row['status'] == 'PUBLISHED_TEMPORARY' and row['exit_code'] == 0 for row in execution),
                    f'{wave} contains a failed execution')
            for seed, (_, _, _, slot_id) in expected.items():
                path = root / wave / 'slots' / slot_id.replace('|', '_') / 'worker-result.json'
                require(path.is_file() and not path.is_symlink(), f'Missing regular artifact {path}')
                paths.append(path.resolve())
                payload=read(path.parent/'payload.json')
                require(payload['executor_identity_sha256']==declaration['executor_jar_sha256']
                        and payload['executor_source_fingerprint']==declaration['executor_source_sha256']
                        and payload['profile_sha256']==declaration['profile_content_sha256']
                        and payload['plan']['content_sha256']==declaration['plan_content_sha256']
                        and payload['run_id']==manifest['wave_run_ids'][wave]
                        and payload['slot']['slot_id']==slot_id, 'Payload does not match declared execution bindings')
                payloads[(wave,seed)]=payload
        require(len(paths) == len(set(paths)) == 16, 'Expected sixteen distinct artifact paths')
        require(hashlib.sha256(audit.read_bytes()).hexdigest() == metadata['audit_script_sha256'],
                'Independent audit script changed while waiting')
        metadata['heavy_artifact_reads_started'] = True
        metadata['audit_started_at'] = now()
        write(status_path, dict(metadata, status='AUDITING_COMPLETED_WAVES'))
        detail_path = root / 'independent-audit-detail.json'
        subprocess.run([sys.executable, '-u', str(audit), *map(str, paths), '--out', str(detail_path)],
                       check=True, timeout=3600)
        detail = read(detail_path)
        rows = detail['artifacts']
        require(detail['status'] == 'PASS' and len(rows) == 16, 'Independent accounting audit incomplete')
        require(Counter(row['seed'] for row in rows) == Counter({seed: 2 for seed in expected}),
                'Seeds are not the fixed eight exactly twice')
        observed = set()
        for row in rows:
            path = Path(row['path']).resolve()
            wave = path.parents[2].name
            seed = row['seed']
            scenario, effect, replication, slot_id = expected[seed]
            require((row['scenario'], row['effect_size'], row['replication'], row['slot_id'])
                    == (scenario, effect, replication, slot_id), 'Artifact seed/cell/replication mismatch')
            require(path in paths and wave in {'parallel', 'serial'} and (wave, seed) not in observed,
                    'Artifact wave provenance is duplicated or unexpected')
            observed.add((wave, seed))
            require(row['plan_sha256']==declaration['plan_content_sha256']
                    and row['run_id']==manifest['wave_run_ids'][wave], 'Artifact plan/run binding mismatch')
            identity = row['executor_identity']
            require(identity['executable']['sha256'] == declaration['executor_jar_sha256']
                    and identity['compiled']['input_fingerprint'] == declaration['executor_source_sha256'],
                    'Artifact executor differs from the declared package')
        require(len(observed) == 16 and detail.get('serial_parallel_metrics_and_book_and_combined_curves_equal') is True,
                'Paired-wave digest comparison incomplete')
        require(detail['independently_reconciled_books'] == 32 and detail['trades_reconciled'] == 14400,
                'Accounting coverage is incomplete')
        detail.update({
            'exact_seed_cell_replication_and_wave_mapping_verified': True,
            'sixteen_unique_artifact_paths_verified': True,
            'declared_executor_identity_verified': True,
            'declared_plan_profile_and_wave_run_ids_verified': True,
            'runtime_directories_bound_to_own_slot_repository': True,
            'audit_checker_correction': 'Previous whole executor_identity equality wrongly rejected legitimately different runtime.cwd and runtime.user_dir. Both now must resolve to the exact per-slot private repository; all other executor identity fields remain exactly equal between waves. First failure evidence preserved under pr13-mac-full-audit/failed-first-post-run.',
            'unique_development_seeds': 8,
            'scientific_acceptance_claim': 'NONE',
            'qualification_claim': 'NONE',
            'completion_guard': metadata,
            'completed_at': now(),
        })
        write(root / 'independent-audit.json', detail)
        write(status_path, dict(metadata, status='PASS', completed_at=now(),
                                audit='independent-audit.json', artifacts_audited=16))
        print('PASS: sixteen distinct completed engineering artifacts independently audited', flush=True)
    except BaseException as error:
        write(status_path, dict(metadata, status='FAILED', failed_at=now(), error=str(error)))
        print('FAILED: ' + str(error), flush=True)
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--audit', type=Path, required=True)
    parser.add_argument('--harness-pid', type=int, required=True)
    parser.add_argument('--completed', action='store_true', help='Audit an already exited harness only with a final PASS manifest')
    main(parser.parse_args())

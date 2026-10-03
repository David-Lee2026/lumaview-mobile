#!/usr/bin/env python3
"""Validate planning documents only. Does NOT compile or test the mobile app.

Usage: python audit/validate_plan.py [--write]
Uses only Python's standard library; --write updates plan-validation.json.
"""
from __future__ import annotations
import argparse
import ast
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent.parent

class HtmlIndex(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.ids: list[str] = []
        self.anchors: list[str] = []
        self.external_assets: list[str] = []
    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        a = dict(attrs)
        if a.get('id'): self.ids.append(a['id'])
        if tag == 'a' and a.get('href', '').startswith('#'):
            self.anchors.append(a['href'][1:])
        if tag in ('script', 'img', 'iframe', 'source') and a.get('src'):
            self.external_assets.append(a['src'])
        if tag == 'link' and a.get('rel') in ('stylesheet', 'preload'):
            self.external_assets.append(a.get('href',''))


def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args=parser.parse_args()
    checks: list[dict] = []
    def record(name: str, ok: bool, detail: object) -> None:
        checks.append({'check': name, 'ok': bool(ok), 'detail': detail})
    try:
        spec_path=ROOT/'docs/superpowers/specs/2026-10-03-lumaview-mobile-design.md'
        plan_path=ROOT/'docs/superpowers/plans/2026-10-03-lumaview-mobile-implementation.md'
        spec=spec_path.read_text(encoding='utf-8')
        plan=plan_path.read_text(encoding='utf-8')
        tasks=json.loads((ROOT/'audit/task-register.json').read_text(encoding='utf-8'))['tasks']
        matrix=json.loads((ROOT/'audit/acceptance-matrix.json').read_text(encoding='utf-8'))
        approval=json.loads((ROOT/'audit/approval-record.json').read_text(encoding='utf-8'))
        criteria=matrix['criteria']; ids=[t['id'] for t in tasks]
        record('twenty_unique_tasks', ids == [f'T{n:02d}' for n in range(1,21)], ids)
        record('five_phases', sorted({t['phase'] for t in tasks})==['M1','M2','M3','M4','M5'],sorted({t['phase'] for t in tasks}))
        deps_bad=[(t['id'],d) for i,t in enumerate(tasks) for d in t['depends_on'] if d not in ids[:i]]
        record('dependency_graph_topological_no_cycles',not deps_bad,deps_bad)
        spec_rows=[]
        for line in spec.splitlines():
            if re.match(r'^\| [CPRETSF]\d{2} \|',line):
                cells=[x.strip() for x in line.strip('|').split('|')]
                spec_rows.append({'id':cells[0],'scene':cells[1],'criterion':cells[2]})
        actual=[{k:c[k] for k in ('id','scene','criterion')} for c in criteria]
        record('38_criteria_preserve_approved_text',len(spec_rows)==38 and spec_rows==actual,len(spec_rows))
        task_by_id={t['id']:t for t in tasks}; bad=[]
        for c in criteria:
            for n in [c['primary_task']]+c['supporting_tasks']:
                if n not in task_by_id or c['id'] not in task_by_id[n]['acceptance']:bad.append((c['id'],n))
        record('every_criterion_has_mapped_owner_and_support',not bad,bad)
        record('all_work_and_test_states_unexecuted',all(t['status']=='NOT_STARTED' for t in tasks) and all(c['status']=='NOT_RUN' and c['measurements'] is None and c['evidence']==[] for c in criteria),{'tasks':len(tasks),'criteria':len(criteria)})
        digest=hashlib.sha256(spec_path.read_bytes()).hexdigest()
        record('approved_spec_bytes_match_hash',digest==approval['spec_sha256']==matrix['spec_sha256'],digest)
        record('approval_not_expanded_to_execution',approval['spec_approval']=='APPROVED_IN_CONVERSATION' and approval['plan_approval']=='PENDING' and approval['execution_mode']=='PENDING' and not approval['apk_produced'] and not approval['mobile_tests_executed'],approval['implementation_status'])
        found=re.findall(r'^### (T\d{2})\u3000(.+)$',plan,re.M)
        record('task_register_matches_markdown_headings',found==[(t['id'],t['title']) for t in tasks],len(found))
        file_drift=[]
        for t in tasks:
            segment=plan.split('### '+t['id']+'\u3000',1)[1].split('\n### T',1)[0].split('\n## 5.',1)[0]
            for _,p in t['files']:
                if '`'+p+'`' not in segment:file_drift.append((t['id'],p))
            if segment.count('- [ ] **步骤') != 6:file_drift.append((t['id'],'steps != 6'))
        record('120_checklist_steps_and_exact_file_paths',not file_drift and plan.count('- [ ] **步骤')==120,file_drift)
        blocks=re.findall(r'```python\n(.*?)```',plan,re.S);errors=[]
        for i,b in enumerate(blocks):
            try:ast.parse(b)
            except SyntaxError as e:errors.append((i,str(e)))
        record('20_test_example_blocks_syntactically_valid',len(blocks)==20 and not errors,errors)
        created={};duplicates=[]
        for t in tasks:
            for op,p in t['files']:
                if op in ('新建','补丁新增'):
                    if p in created:duplicates.append((p,created[p],t['id']))
                    created[p]=t['id']
        record('no_duplicate_new_file_ownership',not duplicates,{'new_file_count':len(created),'conflicts':duplicates})
        needed=['README.md','docs/implementation/interface-contracts.md','docs/implementation/acceptance-matrix.md','docs/implementation/verification-protocol.md','docs/implementation/progress-ledger.md','audit/source-review.json','LumaView_Mobile_Implementation_Plan_v0.1.html','LumaView_Mobile_Implementation_Plan_v0.1.md']
        missing=[p for p in needed if not (ROOT/p).is_file()]
        record('referenced_companion_documents_exist',not missing,missing)
        dom=HtmlIndex();dom.feed((ROOT/'LumaView_Mobile_Implementation_Plan_v0.1.html').read_text(encoding='utf-8'))
        badanchors=sorted(set(dom.anchors)-set(dom.ids))
        record('standalone_html_anchors_resolve',not badanchors and len(dom.ids)==len(set(dom.ids)) and all(n in dom.ids for n in ids),{'broken':badanchors,'anchor_count':len(dom.anchors)})
        record('html_no_remote_assets',not dom.external_assets,dom.external_assets)
        forbidden=[str(p.relative_to(ROOT)) for p in ROOT.rglob('*') if p.is_file() and p.suffix.lower() in ('.apk','.aab','.hap','.so','.exe','.ttf','.otf','.woff','.woff2','.jks','.keystore','.pem')]
        record('no_binaries_fonts_or_keys_in_plan_package',not forbidden,forbidden)
        record('no_vague_unresolved_placeholders',not re.search(r'\b(TODO|TBD|FIXME)\b',plan),[])
    except (OSError, ValueError, KeyError, IndexError) as e:
        record('document_processing',False,f'{type(e).__name__}: {e}')
    out={'scope':'PLAN_DOCUMENTS_ONLY','result':'VALID' if all(c['ok'] for c in checks) else 'INVALID','generated_at_utc':datetime.now(timezone.utc).isoformat(),'checks':checks,'apk_build_executed':False,'mobile_tests_executed':False,'warning':'文档检查结果，不是应用编译、触控、画质或真机性能验收。'}
    if args.write:(ROOT/'audit/plan-validation.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(out,ensure_ascii=False,indent=2))
    return 0 if out['result']=='VALID' else 1
if __name__=='__main__':sys.exit(main())

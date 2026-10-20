"""准备 A2 诊断建议与 A0 的对照底稿；只汇总人工填写的评分。"""
from pathlib import Path
from collections import Counter
from datetime import datetime, timezone
import argparse
import hashlib
import json

FIELDS = {
    'evidenceSupport': {'SUPPORTED', 'PARTIALLY_SUPPORTED', 'UNSUPPORTED', 'CONTRADICTED', 'NO_CITATION'},
    'applicabilityCorrect': bool,
    'addedValueOverRule': {'NONE', 'CLEARER_ACTION', 'NEW_SUPPORTED_STEP', 'UNSUPPORTED_ADDITION', 'UNDECIDABLE'},
    'factHypothesisConfusion': bool,
}

def read_rows(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def write_json(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')

def prepare(run, output):
    run = run.resolve()
    manifest = json.loads((run / 'manifest.json').read_text(encoding='utf-8-sig'))
    if manifest['split'] != 'dev' or not (run / 'completed.json').exists():
        raise ValueError('只允许从完整开发集记录准备复核材料')
    results = read_rows(run / 'results.jsonl')
    grouped = {}
    for arm in ('A0', 'A2'):
        records = [r for r in results if r['arm'] == arm]
        grouped[arm] = {r['caseId']: r for r in records}
        if len(records) != 20 or len(grouped[arm]) != 20:
            raise ValueError('每组必须包含 20 条不重复的开发案例')
    if grouped['A0'].keys() != grouped['A2'].keys():
        raise ValueError('A0/A2 案例集合不一致')
    template, cases = [], []
    for case_id, candidate in grouped['A2'].items():
        baseline = grouped['A0'][case_id]
        if baseline['input'] != candidate['input']:
            raise ValueError('同案例比较输入不一致')
        suggestions = (candidate.get('rawOutput') or {}).get('suggestions', [])
        cases.append({'caseId': case_id, 'wholeOutputStatus': candidate['status'],
                      'acceptedIntakeOutput': candidate.get('acceptedOutput') is not None,
                      'suggestionCount': len(suggestions)})
        for index, suggestion in enumerate(suggestions):
            material = {'input': candidate['input'], 'wholeOutputStatus': candidate['status'],
                        'wholeOutputValidationErrors': candidate['validationErrors'],
                        'acceptedIntakeOutput': candidate.get('acceptedOutput') is not None,
                        'sourceView': 'A2.rawOutput.suggestions',
                        'suggestion': suggestion, 'retrievedEvidence': candidate['retrievedEvidence'],
                        'a0Suggestions': (baseline.get('acceptedOutput') or {}).get('suggestions', [])}
            template.append({'reviewId': f'{case_id}:A2:suggestion:{index}', 'material': material,
                             'scores': {field: None for field in FIELDS}, 'reviewer': None, 'notes': None})
    output.mkdir(parents=True, exist_ok=False)
    with (output / 'template.jsonl').open('x', encoding='utf-8') as stream:
        for row in template:
            stream.write(json.dumps(row, ensure_ascii=False) + '\n')
    write_json(output / 'cases.json', cases)
    write_json(output / 'manifest.json', {
        'version': 'evidence-review-v1', 'preparedAt': datetime.now(timezone.utc).isoformat(),
        'sourceRun': str(run), 'sourceResultsSha256': digest(run / 'results.jsonl'),
        'sourceManifestSha256': digest(run / 'manifest.json'), 'templateSha256': digest(output / 'template.jsonl'),
        'caseCount': len(cases), 'suggestionCount': len(template),
        'casesWithNoSuggestion': sum(c['suggestionCount'] == 0 for c in cases),
        'wholeOutputRejectedCases': sum(not c['acceptedIntakeOutput'] for c in cases),
        'reviewStatus': 'PENDING_HUMAN_REVIEW',
        'scope': '独立评价原始 A2 建议；整份输出已拒绝的案例也保留，但不计作可用受理输出'
    })
    return {'cases': len(cases), 'suggestions': len(template), 'reviewed': 0}

def summarize(packet, reviewed, output):
    manifest = json.loads((packet / 'manifest.json').read_text(encoding='utf-8-sig'))
    source = Path(manifest['sourceRun'])
    for path, expected in [
        (packet / 'template.jsonl', manifest['templateSha256']),
        (source / 'results.jsonl', manifest['sourceResultsSha256']),
        (source / 'manifest.json', manifest['sourceManifestSha256'])
    ]:
        if digest(path) != expected:
            raise ValueError('复核依据已变化，请新建材料版本')
    original = {r['reviewId']: r for r in read_rows(packet / 'template.jsonl')}
    ratings = {}
    for row in read_rows(reviewed):
        review_id = row.get('reviewId')
        if review_id not in original or review_id in ratings:
            raise ValueError('评分标识未知或重复')
        if row.get('material') != original[review_id]['material']:
            raise ValueError('禁止修改原始输入、候选、规则建议或知识材料')
        scores = row.get('scores')
        if not isinstance(scores, dict) or set(scores) != set(FIELDS):
            raise ValueError('评分字段缺失或出现未定义字段')
        for field, allowed in FIELDS.items():
            value = scores[field]
            if value is None:
                continue
            if allowed is bool:
                if type(value) is not bool:
                    raise ValueError('布尔评分仅允许 true/false/null')
            elif not isinstance(value, str) or value not in allowed:
                raise ValueError('评分枚举不合法')
        if any(value is not None for value in scores.values()):
            if not all(isinstance(row.get(key), str) and row[key].strip() for key in ('reviewer', 'notes')):
                raise ValueError('已评分项必须填写审核人及判定理由')
        ratings[review_id] = row
    summary = {'caseCount': manifest['caseCount'], 'suggestionCount': len(original),
               'casesWithNoSuggestion': manifest['casesWithNoSuggestion'],
               'wholeOutputRejectedCases': manifest['wholeOutputRejectedCases'],
               'fullyReviewedSuggestions': sum(all(v is not None for v in r['scores'].values()) for r in ratings.values()),
               'metrics': {}}
    for field in FIELDS:
        counts = Counter()
        for review_id in original:
            value = ratings.get(review_id, {}).get('scores', {}).get(field)
            label = 'UNSCORED' if value is None else str(value).lower() if type(value) is bool else value
            counts[label] += 1
        summary['metrics'][field] = dict(counts)
    summary['limitation'] = '计数单位为建议条目，不是案例通过率；未评分不能视为正确，也不能据此认定 AI 增益'
    write_json(output, summary)
    return summary

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    prepare_parser = sub.add_parser('prepare')
    prepare_parser.add_argument('run', type=Path)
    prepare_parser.add_argument('output', type=Path)
    summary_parser = sub.add_parser('summarize')
    summary_parser.add_argument('packet', type=Path)
    summary_parser.add_argument('reviewed', type=Path)
    summary_parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = prepare(args.run, args.output) if args.command == 'prepare' else summarize(args.packet, args.reviewed, args.output)
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()

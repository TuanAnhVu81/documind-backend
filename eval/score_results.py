"""Score a manually adjudicated run; does not call a model or infer semantic correctness."""
import argparse
import hashlib
import json
from pathlib import Path


def score(questions, run):
    expected = {q['id']: q for q in questions}
    cases = run['cases']
    if run.get('judgementMethod') != 'human' or len(cases) != len(expected):
        raise ValueError('A complete human-adjudicated run is required')
    by_id = {case['id']: case for case in cases}
    if len(by_id) != len(cases) or set(by_id) != set(expected):
        raise ValueError('Cases must match the locked question set exactly')
    total_answerable = sum(q['answerable'] for q in questions)
    total_unanswerable = len(questions) - total_answerable
    results = []
    hits = refusals = invalid_ids = correct = retrieved = 0
    reciprocal = recall_sum = precision_n = precision_d = claim_hits = claim_total = 0
    top_k = run['metadata'].get('topK')
    if type(top_k) is not int or top_k < 1:
        raise ValueError('metadata.topK must be a positive integer')
    for q in questions:
        case = by_id[q['id']]
        if type(case.get('answerCorrect')) is not bool or not case.get('judgementNotes', '').strip():
            raise ValueError(f"{q['id']}: missing human correctness judgement or notes")
        citations = case['citations']
        ranks = case['retrieved']
        for entry in citations:
            if not isinstance(entry.get('id'), str) or type(entry.get('validId')) is not bool or type(entry.get('supportsAnswer')) is not bool:
                raise ValueError(f"{q['id']}: invalid citation judgement")
            if not entry['validId'] and entry['supportsAnswer']:
                raise ValueError(f"{q['id']}: invalid ID cannot support an answer")
            indices = entry['supportedClaimIndices']
            if (not isinstance(indices, list) or len(indices) != len(set(indices))
                    or any(type(i) is not int or i < 0 or i >= len(q['expectedClaims']) for i in indices)
                    or (indices and not entry['supportsAnswer'])):
                raise ValueError(f"{q['id']}: invalid supported claim indices")
        for item in ranks:
            indices = item.get('supportedSourceIndices')
            if (not isinstance(indices, list) or len(indices) != len(set(indices))
                    or any(type(i) is not int or i < 0 or i >= len(q['expectedSources']) for i in indices)):
                raise ValueError(f"{q['id']}: retrieval source mapping needs human judgement")
        complete = case['status'] == 'COMPLETE'
        invalid = sum(not c['validId'] for c in citations) if complete else 0
        invalid_ids += invalid
        valid_citations = [c for c in citations if c['validId']]
        if q['answerable']:
            hit = complete and case['outcome'] == 'ANSWER' and any(
                c['supportsAnswer'] for c in citations[:3] if c['validId'])
            hits += hit
            correct += complete and case['outcome'] == 'ANSWER' and case['answerCorrect']
            top_results = ranks[:top_k]
            first = next((i for i, item in enumerate(top_results, 1) if item['supportedSourceIndices']), None)
            retrieved += first is not None
            reciprocal += 1 / first if first else 0
            found_sources = {i for item in top_results for i in item['supportedSourceIndices']}
            recall_sum += len(found_sources) / len(q['expectedSources'])
            precision_n += sum(c['supportsAnswer'] for c in valid_citations)
            precision_d += len(citations)
            covered = {i for c in valid_citations if c['supportsAnswer'] for i in c['supportedClaimIndices']}
            claim_hits += len(covered)
            claim_total += len(q['expectedClaims'])
            results.append({'id': q['id'], 'sourceHitAt3': bool(hit), 'answerCorrect': bool(complete and case['answerCorrect']),
                            'invalidCitationIds': invalid, 'notes': case['judgementNotes']})
        else:
            refused = complete and case['outcome'] == 'NO_EVIDENCE' and not citations and case['answerCorrect']
            refusals += refused
            results.append({'id': q['id'], 'correctRefusal': bool(refused),
                            'invalidCitationIds': invalid, 'notes': case['judgementNotes']})
    return {'metadata': run['metadata'], 'rubric': 'human judgment against locked question/source/claim annotations',
            'metrics': {'sourceHitAt3': [hits, total_answerable], 'correctRefusal': [refusals, total_unanswerable],
                        'invalidCitationIds': invalid_ids, 'answerCorrect': [correct, total_answerable],
                        'retrievalHitAtK': [retrieved, total_answerable],
                        'retrievalRecallAtK': recall_sum / total_answerable, 'mrrAtK': reciprocal / total_answerable,
                        'citationPrecision': [precision_n, precision_d], 'claimCitationRecall': [claim_hits, claim_total]},
            'cases': results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path, help='JSON results with explicit human judgments')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).parent
    question_bytes = (root / 'questions.json').read_bytes()
    manifest = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
    digest = hashlib.sha256(question_bytes).hexdigest()
    if manifest['reviewStatus'] != 'APPROVED' or digest != manifest['questionsSha256']:
        raise ValueError('The locked question set is not approved or its hash has changed')
    questions = json.loads(question_bytes)
    run = json.loads(args.run.read_text(encoding='utf-8'))
    if (run['metadata'].get('questionsSha256') != digest
            or run['metadata'].get('evaluationVersion') != manifest['evaluationVersion']):
        raise ValueError('Run metadata does not identify the locked question set')
    result = score(questions, run)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()

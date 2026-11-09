"""Capture real SSE responses from an isolated local demo account; default is preflight only."""
import argparse
import hashlib
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


OPENER = urllib.request.build_opener(NoRedirect())


def local_base(value):
    url = urllib.parse.urlsplit(value)
    if (url.scheme != 'http' or url.hostname not in {'localhost', '127.0.0.1', '::1'}
            or url.username or url.password or url.query or url.fragment or url.path not in {'', '/'}):
        raise ValueError('Only a loopback HTTP backend origin is supported')
    return value.rstrip('/')


def request(base, token, method, path, body=None, headers=None):
    combined = {'Authorization': f'Bearer {token}', **(headers or {})}
    payload = None
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode('utf-8')
        combined['Content-Type'] = 'application/json'
    req = urllib.request.Request(base + '/api/v1' + path, data=payload, headers=combined, method=method)
    return OPENER.open(req, timeout=240)


def parse_sse(stream):
    event = 'message'
    data = []
    for raw in stream:
        line = raw.decode('utf-8').rstrip('\r\n')
        if not line:
            if data:
                yield event, json.loads('\n'.join(data))
            event, data = 'message', []
        elif line.startswith('event:'):
            event = line[6:].strip()
        elif line.startswith('data:'):
            data.append(line[5:].lstrip())


def preflight(base, token, manifest, questions, histories):
    with request(base, token, 'GET', '/documents?page=0&size=100') as response:
        page = json.load(response)
    expected = {Path(doc['path']).name: doc['id'] for doc in manifest['documents']}
    if page['totalElements'] != len(expected) or len(page['items']) != len(expected):
        raise ValueError('Demo account must contain exactly the 20 locked fixtures')
    mapping = {}
    for item in page['items']:
        filename = item['filename']
        if filename not in expected or item['status'] != 'READY' or expected[filename] in mapping:
            raise ValueError('Fixtures must be unique, named exactly as in the manifest, and READY')
        mapping[expected[filename]] = item['id']
    multi = [q for q in questions if q['history']]
    if set(histories) != {q['id'] for q in multi} or len(set(histories.values())) != len(multi):
        raise ValueError('Provide a separate prepared history conversation for each multi-turn case')
    for q in multi:
        conversation = str(uuid.UUID(histories[q['id']]))
        with request(base, token, 'GET', f'/conversations/{conversation}/messages?limit=10') as response:
            history = json.load(response)
        if history['hasMore'] or len(history['items']) != len(q['history']):
            raise ValueError(f"{q['id']}: prepared history has unexpected messages")
        for actual, expected_message in zip(history['items'], q['history']):
            content = re.sub(r'\s*\[S\d+\]', '', actual['content']).strip()
            if (actual['status'] != 'COMPLETE' or actual['role'] != expected_message['role']
                    or content != expected_message['content'].strip()):
                raise ValueError(f"{q['id']}: prepared history differs from locked history")
            if actual['role'] == 'ASSISTANT':
                if actual['outcome'] != 'ANSWER':
                    raise ValueError(f"{q['id']}: prepared answer is not an ANSWER")
                refs = expected_message['sourceRefs']
                sources = actual['sources']
                for ref in refs:
                    if not any(source['availability'] == 'AVAILABLE'
                               and source['documentId'] == mapping[ref['fixtureId']]
                               and source['pageNumber'] == ref['pageNumber']
                               and source['paragraphIndex'] == ref['paragraphIndex'] for source in sources):
                        raise ValueError(f"{q['id']}: prepared history is missing a valid source")
    return mapping


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, help='Isolated loopback backend, e.g. http://127.0.0.1:8080')
    parser.add_argument('--histories', required=True, type=Path, help='JSON mapping five question IDs to prepared conversation UUIDs')
    parser.add_argument('--output', required=True, type=Path, help='New output file for raw results')
    parser.add_argument('--configuration', required=True, help='Recorded configuration label; verify runtime flags independently')
    parser.add_argument('--interval-seconds', type=float, default=20)
    parser.add_argument('--execute', action='store_true', help='Explicitly submit 25 real requests after successful preflight')
    args = parser.parse_args()
    if args.interval_seconds < 20:
        raise ValueError('Interval must be at least 20 seconds; check account TPM and RPD separately')
    base = local_base(args.base)
    token = os.environ.get('DOCUMIND_EVAL_ACCESS_TOKEN', '')
    if not token:
        raise ValueError('Provide DOCUMIND_EVAL_ACCESS_TOKEN for the isolated demo account via environment')
    if args.output.exists():
        raise ValueError('Output file already exists; refusing to overwrite')
    manifest = json.loads((ROOT / 'manifest.json').read_text(encoding='utf-8'))
    question_bytes = (ROOT / 'questions.json').read_bytes()
    if manifest['reviewStatus'] != 'APPROVED' or hashlib.sha256(question_bytes).hexdigest() != manifest['questionsSha256']:
        raise ValueError('Evaluation set is not approved or has changed')
    questions = json.loads(question_bytes)
    histories = json.loads(args.histories.read_text(encoding='utf-8'))
    mapping = preflight(base, token, manifest, questions, histories)
    if not args.execute:
        print('Preflight passed. No chat or embedding requests sent.')
        return
    result = {'metadata': {'evaluationVersion': manifest['evaluationVersion'],
                           'questionsSha256': manifest['questionsSha256'], 'configuration': args.configuration,
                           'timestampUtc': datetime.now(timezone.utc).isoformat(),
                           'retrievalDiagnostics': 'NOT_COLLECTED_BY_PUBLIC_API'}, 'cases': []}
    for position, q in enumerate(questions):
        started = time.monotonic()
        try:
            if q['history']:
                conversation = histories[q['id']]
            else:
                with request(base, token, 'POST', '/conversations', {'title': 'Eval ' + q['id']}) as response:
                    conversation = json.load(response)['id']
            body = {'content': q['question']}
            if q['scopeMode'] == 'SELECTED':
                body['documentIds'] = [mapping[fixture] for fixture in q['documentFixtureIds']]
            with request(base, token, 'POST', f'/conversations/{conversation}/messages', body,
                         {'Idempotency-Key': str(uuid.uuid4()), 'Accept': 'text/event-stream'}) as response:
                events = [(name, data) for name, data in parse_sse(response) if name in {'done', 'error'}]
            result['cases'].append({'id': q['id'], 'events': events, 'durationSeconds': time.monotonic() - started})
            if len(events) != 1 or events[0][0] != 'done':
                break
        except urllib.error.HTTPError as failure:
            result['cases'].append({'id': q['id'], 'httpStatus': failure.code, 'durationSeconds': time.monotonic() - started})
            if failure.code in {301, 302, 303, 307, 308, 401, 403, 429}:
                break
        finally:
            args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        remaining = args.interval_seconds - (time.monotonic() - started)
        if remaining > 0 and position + 1 < len(questions):
            time.sleep(remaining)


if __name__ == '__main__':
    main()

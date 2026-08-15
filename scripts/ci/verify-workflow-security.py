#!/usr/bin/env python3
"""Fail when PR workflows cross the untrusted-code trust boundary."""

from pathlib import Path
import re
import sys

workflow_dir = Path('.github/workflows')
errors: list[str] = []

for workflow in sorted(workflow_dir.glob('*.yml')):
    text = workflow.read_text(encoding='utf-8')
    if re.search(r'^\s*pull_request_target\s*:', text, re.MULTILINE):
        errors.append(f'{workflow}: pull_request_target is prohibited')
    if re.search(r'^\s*(contents|packages|id-token|deployments|actions)\s*:\s*write\s*$', text, re.MULTILINE):
        errors.append(f'{workflow}: write permission is prohibited in MVP-0 PR CI')
    if re.search(r'\bsecrets\s*:', text):
        errors.append(f'{workflow}: explicit secrets are prohibited in untrusted PR CI')
    for line_number, line in enumerate(text.splitlines(), start=1):
        match = re.search(r'uses:\s+[^@\s]+@([^\s#]+)', line)
        if match and not re.fullmatch(r'[0-9a-f]{40}', match.group(1)):
            errors.append(f'{workflow}:{line_number}: action must be pinned to a full commit SHA')

if errors:
    print('\n'.join(errors), file=sys.stderr)
    raise SystemExit(1)

print(f'Validated {len(list(workflow_dir.glob("*.yml")))} workflow files: read-only, secretless, SHA-pinned.')

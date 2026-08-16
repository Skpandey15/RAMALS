#!/usr/bin/env python3
"""Enforce the CI/CD trust boundary between untrusted PR code and privileged publish/deploy.

Workflows are classified by what can trigger them:

* UNTRUSTED - reachable from a pull request. These run attacker-influenced code, so they get no
  write permissions, no secrets, and no publish/deploy capability.
* TRUSTED   - reachable only from protected refs (push to main, release tags, schedule, manual
  dispatch). These may publish images, but must never be reachable from a pull request.

Both classes must pin every third-party action to a full commit SHA.
"""

from pathlib import Path
import re
import sys

WORKFLOW_DIR = Path('.github/workflows')

# A reusable workflow (workflow_call) inherits its caller's trust; PR CI calls these, so they are
# treated as untrusted.
UNTRUSTED_TRIGGERS = ('pull_request', 'workflow_call')
WRITE_PERMISSION = re.compile(
    r'^\s*(contents|packages|id-token|deployments|actions|attestations|security-events)\s*:\s*write\s*$',
    re.MULTILINE,
)
PUBLISH_MARKERS = ('docker/login-action', 'docker/build-push-action', 'push: true')


def triggers(text: str) -> set[str]:
    """Collect the top-level `on:` keys of a workflow."""
    found: set[str] = set()
    in_on = False
    for line in text.splitlines():
        if re.match(r'^on\s*:', line):
            in_on = True
            # Inline form, e.g. `on: [push]` or `on: push`.
            inline = line.split(':', 1)[1].strip().strip('[]')
            found.update(part.strip() for part in inline.split(',') if part.strip())
            continue
        if in_on:
            if re.match(r'^\S', line):  # dedent to a new top-level key ends the on: block
                in_on = False
                continue
            match = re.match(r'^\s{2}([a-z_]+)\s*:', line)
            if match:
                found.add(match.group(1))
    return found


def main() -> int:
    errors: list[str] = []
    workflows = sorted(WORKFLOW_DIR.glob('*.yml'))
    untrusted_count = 0
    trusted_count = 0

    for workflow in workflows:
        text = workflow.read_text(encoding='utf-8')
        name = workflow.name
        on_keys = triggers(text)

        # pull_request_target runs untrusted code with a privileged token; never allowed.
        if 'pull_request_target' in on_keys:
            errors.append(f'{name}: pull_request_target is prohibited')

        is_untrusted = any(trigger in on_keys for trigger in UNTRUSTED_TRIGGERS)

        if is_untrusted:
            untrusted_count += 1
            if WRITE_PERMISSION.search(text):
                errors.append(
                    f'{name}: untrusted (PR-reachable) workflow must not request write permissions'
                )
            if re.search(r'^\s*secrets\s*:', text, re.MULTILINE):
                errors.append(f'{name}: untrusted workflow must not receive explicit secrets')
            for marker in PUBLISH_MARKERS:
                if marker in text:
                    errors.append(
                        f'{name}: untrusted workflow must not publish images (found "{marker}")'
                    )
        else:
            trusted_count += 1
            # A privileged workflow must not become PR-reachable by a later edit.
            if any(trigger in on_keys for trigger in UNTRUSTED_TRIGGERS):
                errors.append(f'{name}: trusted workflow must not be triggerable by a pull request')
            # Publishing requires an immutable identity, never a mutable-tag-only deploy.
            if 'docker/build-push-action' in text and 'type=sha' not in text:
                errors.append(f'{name}: published images must carry an immutable sha tag')

        # OCI references must be lowercase, but a GitHub owner may contain capitals. Interpolating
        # the owner straight into an image reference silently produces an unparseable reference.
        for line_number, line in enumerate(text.splitlines(), start=1):
            if re.search(r'(image|image-ref|images|subject-name)\s*:.*github\.repository_owner', line):
                errors.append(
                    f'{name}:{line_number}: build image references from a lowercased owner, '
                    'not github.repository_owner directly'
                )

        # Supply-chain pinning applies to every workflow.
        for line_number, line in enumerate(text.splitlines(), start=1):
            match = re.search(r'uses:\s+([^@\s]+)@([^\s#]+)', line)
            if not match:
                continue
            target, ref = match.groups()
            if target.startswith('./'):  # local reusable workflow, no SHA to pin
                continue
            if not re.fullmatch(r'[0-9a-f]{40}', ref):
                errors.append(f'{name}:{line_number}: action must be pinned to a full commit SHA')

    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1

    print(
        f'Validated {len(workflows)} workflows: '
        f'{untrusted_count} untrusted (read-only, secretless, non-publishing), '
        f'{trusted_count} trusted (protected refs only). All actions SHA-pinned.'
    )
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

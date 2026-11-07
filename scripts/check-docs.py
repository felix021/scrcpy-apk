#!/usr/bin/env python3
"""Check repository Markdown links, topic indexes and documented AVD phases offline."""
import ast
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent
LINK = re.compile(r'!?\[[^\]\n]*\]\((<[^>\n]*>|[^\s)\n]+)(?:\s+"[^"\n]*")?\)')
errors = []
links = set()
files = subprocess.check_output(
    ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', '*.md'],
    cwd=ROOT, text=True, encoding='utf-8').split('\0')
documents = {ROOT / name for name in files if (ROOT / name).is_file()}

for source in sorted(documents):
    fence = None
    for line_number, line in enumerate(source.read_text(encoding='utf-8').splitlines(), 1):
        marker = re.match(r'^\s*(`{3,}|~{3,})', line)
        if marker:
            value = marker.group(1)
            if fence is None:
                fence = value
            elif value[0] == fence[0] and len(value) >= len(fence):
                fence = None
            continue
        if fence is not None:
            continue
        for match in LINK.finditer(line):
            address = match.group(1).strip('<>')
            parsed = urlsplit(address)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            target = (source.parent / unquote(parsed.path)).resolve()
            location = f'{source.relative_to(ROOT)}:{line_number}'
            if target != ROOT and ROOT not in target.parents:
                errors.append(f'{location}: link escapes repository: {address}')
            elif not target.exists():
                errors.append(f'{location}: missing target: {address}')
            else:
                links.add((source, target))
    if fence is not None:
        errors.append(f'{source.relative_to(ROOT)}: unclosed code fence')

# Every topic/report has an entry in its directory index; nested indexes are
# linked from their parent index. This also catches unindexed new documents.
for source in sorted(documents):
    if ROOT / 'docs' not in source.parents or source == ROOT / 'docs/README.md':
        continue
    parent = source.parent.parent if source.name == 'README.md' else source.parent
    index = parent / 'README.md'
    if (index, source) not in links:
        errors.append(f'{source.relative_to(ROOT)}: missing from {index.relative_to(ROOT)}')

for source, target in [('README.md', 'README.en.md'), ('README.en.md', 'README.md'),
                       ('README.md', 'docs/README.md'), ('README.en.md', 'docs/README.md'),
                       ('AGENTS.md', 'docs/README.md')]:
    if (ROOT / source, ROOT / target) not in links:
        errors.append(f'{source}: missing navigation link to {target}')

# Read argparse metadata without importing a script that manipulates devices.
tree = ast.parse((ROOT / 'scripts/verify-avd.py').read_text(encoding='utf-8'))
phases = None
for node in ast.walk(tree):
    if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
            and node.func.attr == 'add_argument' and node.args
            and isinstance(node.args[0], ast.Constant) and node.args[0].value == '--phase'):
        phases = set(ast.literal_eval(next(k.value for k in node.keywords if k.arg == 'choices')))
if phases is None:
    errors.append('scripts/verify-avd.py: --phase choices not found')
else:
    documented = set(re.findall(r'`--phase ([a-z-]+)`', (ROOT / 'docs/testing.md').read_text(encoding='utf-8')))
    for value in sorted(phases - documented):
        errors.append(f'docs/testing.md: undocumented phase {value}')
    for value in sorted(documented - phases):
        errors.append(f'docs/testing.md: unknown phase {value}')

if errors:
    print('\n'.join(errors), file=sys.stderr)
    sys.exit(1)
print(f'OK: {len(documents)} Markdown files; local links, fences, indexes and AVD phases checked.')

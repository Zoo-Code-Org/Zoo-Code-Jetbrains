#!/usr/bin/env python3
"""Check the shared Zoo Code task history after a concurrent two-IDE run.

Issue #6: several IDE processes write one global history store. A safe store
keeps one valid history_item.json per task, with no shared index file and with
parent and child links that stay inside one IDE's task tree.
"""
import argparse
import json
import re
import sys
from pathlib import Path

PLACEHOLDER = re.compile(r'^\s*(Task|Work) #\d+')
BAD_LOG = re.compile(r'invalid status transition|DeltaRejectedError|failed to persist|Cannot re-delegate', re.I)
MARKER = re.compile(r'HISTORY_RACE_(PARENT|CHILD) (\w+)')


def load_items(storage, since):
    """Return ({task id: item}, failures) for tasks written at or after `since`."""
    items, failures = {}, []
    for index in storage.glob('**/tasks/_index.json'):
        if index.stat().st_mtime >= since:
            failures.append(f'shared index file was written: {index}')
    for path in sorted(storage.glob('**/tasks/*/history_item.json')):
        if path.stat().st_mtime < since:
            continue
        try:
            item = json.loads(path.read_text(encoding='utf-8'))
        except (OSError, ValueError) as error:
            failures.append(f'{path.parent.name}: history_item.json is not valid JSON ({error})')
            continue
        if not isinstance(item, dict):
            failures.append(f'{path.parent.name}: history_item.json is not an object')
            continue
        if item.get('id') != path.parent.name:
            failures.append(f'{path.parent.name}: id field is {item.get("id")!r}')
            continue
        items[item['id']] = item
    return items, failures


def check_items(items, instances):
    failures = []
    tasks = {}
    for task_id, item in items.items():
        text = item.get('task') or ''
        if PLACEHOLDER.match(text) or not text.strip():
            failures.append(f'{task_id}: placeholder or empty title {text!r}')
            continue
        match = MARKER.search(text)
        if not match:
            failures.append(f'{task_id}: title does not carry a run marker: {text!r}')
            continue
        if not item.get('size'):
            failures.append(f'{task_id}: size is {item.get("size")!r} after real work')
        tasks[task_id] = (match.group(1), match.group(2), item)
    parents = {i: t for i, t in tasks.items() if t[0] == 'PARENT'}
    children = {i: t for i, t in tasks.items() if t[0] == 'CHILD'}
    if len(parents) != instances or len(children) != instances:
        failures.append(f'expected {instances} parents and {instances} children, '
                        f'found {len(parents)} and {len(children)}')
    for parent_id, (_, tag, parent) in parents.items():
        own = [c for c, t in children.items() if t[1] == tag]
        if len(own) != 1:
            failures.append(f'{parent_id}: expected one child for tag {tag}, found {len(own)}')
            continue
        child_id = own[0]
        child = children[child_id][2]
        if parent.get('childIds') != [child_id]:
            failures.append(f'{parent_id}: childIds {parent.get("childIds")!r}, expected {[child_id]!r}')
        if child.get('parentTaskId') != parent_id:
            failures.append(f'{child_id}: parentTaskId {child.get("parentTaskId")!r}, expected {parent_id!r}')
        if child.get('status') != 'completed':
            failures.append(f'{child_id}: child status {child.get("status")!r}, expected completed')
        if parent.get('status') == 'delegated' or parent.get('awaitingChildId') or parent.get('delegatedToId'):
            failures.append(f'{parent_id}: parent still waits on a child (status {parent.get("status")!r})')
        if parent.get('workspace') != child.get('workspace'):
            failures.append(f'{parent_id}: parent and child workspaces differ')
    workspaces = {t[2].get('workspace') for t in parents.values()}
    if instances > 1 and len(workspaces) != instances:
        failures.append(f'expected {instances} distinct workspaces, found {sorted(map(str, workspaces))}')
    return failures


def check_provider(path, instances):
    events = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]
    names = [e['event'] for e in events]
    failures = []
    if names.count('barrier-released') != instances:
        failures.append(f'barrier released {names.count("barrier-released")} times, expected {instances}')
    for bad in ('barrier-timeout', 'disconnected'):
        if bad in names:
            failures.append(f'provider logged {bad}')
    if names.count('completed') != 3 * instances:
        failures.append(f'{names.count("completed")} completed streams, expected {3 * instances}')
    return failures


def check_logs(paths):
    failures = []
    for path in paths:
        for number, line in enumerate(path.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
            if BAD_LOG.search(line):
                failures.append(f'{path.name}:{number}: {line.strip()[:200]}')
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--storage', type=Path, required=True)
    parser.add_argument('--since', type=float, required=True, help='epoch seconds when the run started')
    parser.add_argument('--provider-log', type=Path, required=True)
    parser.add_argument('--ide-log', type=Path, action='append', default=[])
    parser.add_argument('--instances', type=int, default=2)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    items, failures = load_items(args.storage, args.since)
    failures += check_items(items, args.instances)
    failures += check_provider(args.provider_log, args.instances)
    failures += check_logs([p for p in args.ide_log if p.exists()])
    report = {'tasks': len(items), 'failures': failures}
    text = json.dumps(report, indent=2)
    if args.report:
        args.report.write_text(text, encoding='utf-8')
    print(text)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())

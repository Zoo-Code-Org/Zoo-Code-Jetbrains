import json
import tempfile
import unittest
from pathlib import Path

from check_history_store import check_items, check_logs, check_provider, load_items


def item(task_id, text, **extra):
    return {'id': task_id, 'task': text, 'size': 10, 'workspace': 'w', **extra}


def good_pair(tag, workspace):
    return {
        'p' + tag: item('p' + tag, f'HISTORY_RACE_PARENT {tag} go', childIds=['c' + tag],
                        status='active', workspace=workspace),
        'c' + tag: item('c' + tag, f'HISTORY_RACE_CHILD {tag} go', parentTaskId='p' + tag,
                        status='completed', workspace=workspace),
    }


# Real items from Windows CI run 43562237505 (commit 5a494cf, IDEA 2026.2.1, extension 3.96.0).
# Only the temporary run folder name in `workspace` was shortened.
REAL_ITEMS = json.loads(r'''[
    {
        "id": "01a1d898-c7ab-70da-936e-6467341e0125",
        "parentTaskId": "01a1d898-bf28-71a1-92d9-df474bf72871",
        "number": 1,
        "ts": 1794635304779,
        "task": "HISTORY_RACE_CHILD A reply with attempt_completion.",
        "tokensIn": 0,
        "tokensOut": 0,
        "totalCost": 0,
        "size": 10621,
        "workspace": "c:\\Users\\runneradmin\\AppData\\Local\\Temp\\zoo-focus-RUN\\workspace-A",
        "mode": "code",
        "apiConfigName": "focus-fixture",
        "status": "completed",
        "completionResultSummary": "Fixture turn 4 complete."
    },
    {
        "id": "01a1d898-c663-72e9-b6dd-1caba4bda93a",
        "parentTaskId": "01a1d898-4435-75fd-afc2-6ca3aa4c89a4",
        "number": 1,
        "ts": 1794635304331,
        "task": "HISTORY_RACE_CHILD B reply with attempt_completion.",
        "tokensIn": 0,
        "tokensOut": 0,
        "totalCost": 0,
        "size": 10623,
        "workspace": "c:\\Users\\runneradmin\\AppData\\Local\\Temp\\zoo-focus-RUN\\workspace-B",
        "mode": "code",
        "apiConfigName": "focus-fixture",
        "status": "completed",
        "completionResultSummary": "Fixture turn 3 complete."
    },
    {
        "id": "01a1d898-bf28-71a1-92d9-df474bf72871",
        "number": 1,
        "ts": 1794635306508,
        "task": "HISTORY_RACE_PARENT A delegate one subtask, then finish.",
        "tokensIn": 0,
        "tokensOut": 0,
        "totalCost": 0,
        "size": 7694,
        "workspace": "c:\\Users\\runneradmin\\AppData\\Local\\Temp\\zoo-focus-RUN\\workspace-A",
        "mode": "code",
        "apiConfigName": "focus-fixture",
        "status": "active",
        "childIds": [
            "01a1d898-c7ab-70da-936e-6467341e0125"
        ],
        "completedByChildId": "01a1d898-c7ab-70da-936e-6467341e0125",
        "completionResultSummary": "Fixture turn 4 complete."
    },
    {
        "id": "01a1d898-4435-75fd-afc2-6ca3aa4c89a4",
        "number": 1,
        "ts": 1794635306029,
        "task": "HISTORY_RACE_PARENT B delegate one subtask, then finish.",
        "tokensIn": 0,
        "tokensOut": 0,
        "totalCost": 0,
        "size": 7698,
        "workspace": "c:\\Users\\runneradmin\\AppData\\Local\\Temp\\zoo-focus-RUN\\workspace-B",
        "mode": "code",
        "apiConfigName": "focus-fixture",
        "status": "active",
        "childIds": [
            "01a1d898-c663-72e9-b6dd-1caba4bda93a"
        ],
        "completedByChildId": "01a1d898-c663-72e9-b6dd-1caba4bda93a",
        "completionResultSummary": "Fixture turn 3 complete."
    }
]''')


class CheckItemsTest(unittest.TestCase):
    def test_real_extension_items_pass(self):
        items = {i['id']: i for i in REAL_ITEMS}
        self.assertEqual(4, len(items))
        self.assertEqual([], check_items(items, 2))


    def two(self):
        return {**good_pair('A', 'wa'), **good_pair('B', 'wb')}

    def test_clean_store_passes(self):
        self.assertEqual([], check_items(self.two(), 2))

    def test_placeholder_title_and_zero_size_fail(self):
        items = self.two()
        items['pA']['task'] = 'Task #1'
        items['cB']['size'] = 0
        failures = check_items(items, 2)
        self.assertTrue(any('placeholder' in f for f in failures))
        self.assertTrue(any('size is 0' in f for f in failures))

    def test_cross_linked_children_fail(self):
        items = self.two()
        items['pA']['childIds'] = ['cB']
        items['cA']['parentTaskId'] = 'pB'
        failures = check_items(items, 2)
        self.assertTrue(any('childIds' in f for f in failures))
        self.assertTrue(any('parentTaskId' in f for f in failures))

    def test_foreign_or_missing_child_ids_fail(self):
        for child_ids in (['cA', 'cB'], []):
            items = self.two()
            items['pA']['childIds'] = child_ids
            self.assertTrue(any('childIds' in f for f in check_items(items, 2)), child_ids)

    def test_stuck_delegation_and_open_child_fail(self):
        items = self.two()
        items['pA'].update(status='delegated', awaitingChildId='cA')
        items['cA']['status'] = 'interrupted'
        failures = check_items(items, 2)
        self.assertTrue(any('still waits' in f for f in failures))
        self.assertTrue(any('child status' in f for f in failures))

    def fails(self, items, message):
        self.assertIn(message, check_items(items, 2))

    def test_each_bad_item_branch_reports_an_exact_message(self):
        items = self.two()
        items['pA']['task'] = 'Work #2'
        self.fails(items, "pA: placeholder or empty title 'Work #2'")
        items = self.two()
        items['pA']['task'] = '  '
        self.fails(items, "pA: placeholder or empty title '  '")
        items = self.two()
        items['pA']['task'] = 'no marker'
        self.fails(items, "pA: title does not carry a run marker: 'no marker'")
        items = self.two()
        items['pA']['delegatedToId'] = 'cA'
        self.fails(items, 'pA: parent still waits on a child (status \'active\')')
        items = self.two()
        items['pA']['awaitingChildId'] = 'cA'
        self.fails(items, 'pA: parent still waits on a child (status \'active\')')
        items = self.two()
        items['cA']['workspace'] = 'other'
        self.fails(items, 'pA: parent and child workspaces differ')

    def test_shared_workspace_and_extra_child_fail(self):
        items = {**good_pair('A', 'w'), **good_pair('B', 'w')}
        self.assertTrue(any('distinct workspaces' in f for f in check_items(items, 2)))
        items = self.two()
        items.update(good_pair('A', 'wa'))
        items['cA2'] = item('cA2', 'HISTORY_RACE_CHILD A again', parentTaskId='pA',
                            status='completed', workspace='wa')
        self.fails(items, 'pA: expected one child for tag A, found 2')

    def test_missing_task_fails(self):
        items = self.two()
        del items['cB']
        self.assertTrue(any('expected 2 parents' in f for f in check_items(items, 2)))


class StoreAndLogTest(unittest.TestCase):
    def test_index_file_and_bad_json_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            tasks = Path(directory) / 'ext' / 'tasks'
            (tasks / 'x').mkdir(parents=True)
            (tasks / '_index.json').write_text('{}')
            (tasks / 'x' / 'history_item.json').write_text('{broken')
            items, failures = load_items(Path(directory), 0)
            self.assertEqual({}, items)
            self.assertEqual(2, len(failures))

    def test_id_mismatch_and_non_object_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            tasks = Path(directory) / 'ext' / 'tasks'
            for name, text in (('x', '{"id": "y"}'), ('z', '[1]')):
                (tasks / name).mkdir(parents=True)
                (tasks / name / 'history_item.json').write_text(text)
            items, failures = load_items(Path(directory), 0)
            self.assertEqual({}, items)
            self.assertEqual(["x: id field is 'y'", 'z: history_item.json is not an object'], failures)

    def test_old_tasks_are_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            task = Path(directory) / 'ext' / 'tasks' / 'x'
            task.mkdir(parents=True)
            (task / 'history_item.json').write_text('{broken')
            self.assertEqual(({}, []), load_items(Path(directory), 2 ** 40))

    def test_provider_and_log_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            provider = Path(directory) / 'p.jsonl'
            good = ['barrier-released'] * 2 + ['completed'] * 6
            provider.write_text('\n'.join(json.dumps({'event': e}) for e in good))
            self.assertEqual([], check_provider(provider, 2))
            provider.write_text(json.dumps({'event': 'barrier-timeout'}))
            self.assertTrue(check_provider(provider, 2))
            def provider_failures(events):
                provider.write_text('\n'.join(json.dumps({'event': e}) for e in events))
                return check_provider(provider, 2)
            self.assertIn('provider logged disconnected', provider_failures(good + ['disconnected']))
            self.assertIn('barrier released 1 times, expected 2',
                          provider_failures(['barrier-released'] + ['completed'] * 6))
            self.assertIn('5 completed streams, expected 6',
                          provider_failures(['barrier-released'] * 2 + ['completed'] * 5))
            self.assertIn('7 completed streams, expected 6', provider_failures(good + ['completed']))
            self.assertIn('barrier released 3 times, expected 2', provider_failures(good + ['barrier-released']))
            self.assertEqual(['provider logged barrier-timeout'], provider_failures(good + ['barrier-timeout']))
            provider.write_text('{"event": "completed"}\n{broken')
            self.assertTrue(any('not valid JSON' in f for f in check_provider(provider, 2)))
            provider.write_text('[1]\n' + '\n'.join(json.dumps({'event': e}) for e in good))
            self.assertEqual(["p.jsonl: provider log line is not an object: [1]"], check_provider(provider, 2))
            missing = Path(directory) / 'missing.log'
            self.assertEqual([f'IDE log does not exist: {missing}'], check_logs([missing]))
            log = Path(directory) / 'idea.log'
            log.write_text('ok\nInvalid status transition interrupted -> delegated\n')
            self.assertEqual(1, len(check_logs([log])))
            for phrase in ('invalid status transition', 'DeltaRejectedError', 'failed to persist', 'Cannot re-delegate'):
                log.write_text(f'ok\n{phrase}\n')
                self.assertEqual([f'idea.log:2: {phrase}'], check_logs([log]), phrase)
            stamped = '2026-01-01 12:00:00,000 [  1] WARN - Invalid status transition x'
            log.write_text(f'ok\n{stamped}\n')
            self.assertEqual([f'idea.log:2: {stamped}'], check_logs([log]))


if __name__ == '__main__':
    unittest.main()

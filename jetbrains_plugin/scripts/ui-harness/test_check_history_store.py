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


class CheckItemsTest(unittest.TestCase):
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

    def test_stuck_delegation_and_open_child_fail(self):
        items = self.two()
        items['pA'].update(status='delegated', awaitingChildId='cA')
        items['cA']['status'] = 'interrupted'
        failures = check_items(items, 2)
        self.assertTrue(any('still waits' in f for f in failures))
        self.assertTrue(any('child status' in f for f in failures))

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

    def test_old_tasks_are_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            task = Path(directory) / 'ext' / 'tasks' / 'x'
            task.mkdir(parents=True)
            (task / 'history_item.json').write_text('{broken')
            self.assertEqual(({}, []), load_items(Path(directory), 2 ** 40))

    def test_provider_and_log_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            provider = Path(directory) / 'p.jsonl'
            events = ['barrier-released'] * 2 + ['completed'] * 6
            provider.write_text('\n'.join(json.dumps({'event': e}) for e in events))
            self.assertEqual([], check_provider(provider, 2))
            provider.write_text(json.dumps({'event': 'barrier-timeout'}))
            self.assertTrue(check_provider(provider, 2))
            log = Path(directory) / 'idea.log'
            log.write_text('ok\nInvalid status transition interrupted -> delegated\n')
            self.assertEqual(1, len(check_logs([log])))


if __name__ == '__main__':
    unittest.main()

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class HotspotSyncTest(unittest.TestCase):
    def run_fixture(self, override):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            repo = temp / 'repo'
            repo.mkdir()
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=repo, text=True).strip()
            git('init', '-q')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            (repo / 'gradlew').write_text('#!/bin/bash\nexit 0\n')
            (repo / 'scripts').mkdir()
            (repo / 'scripts/test-hotspot-api27.sh').write_text('#!/bin/bash\nexit 0\n')
            git('add', '.')
            git('commit', '-qm', 'base')
            base = git('rev-parse', 'HEAD')
            git('tag', 'v0.2.0')
            git('switch', '-qc', 'fixture-patch')
            (repo / 'runtime.txt').write_text('runtime\n')
            git('add', '.')
            git('commit', '-qm', 'runtime')
            runtime = git('rev-parse', 'HEAD')
            (repo / 'migration.txt').write_text('branch-specific doc\n')
            git('add', '.')
            git('commit', '-qm', 'documentation')
            doc = git('rev-parse', 'HEAD')
            # Exercise the real sync script against a tiny local history. Only its fixed
            # upstream base and network fetch are substituted; Gradle/API27 are stubs here.
            script = temp / 'sync.sh'
            script.write_text((ROOT / 'scripts/check-hotspot-upstream.sh').read_text().replace(
                'base=72dbc164114c9fa16f2c7fb77f36ecd5d48aa25d', f'base={base}'))
            bin_dir = temp / 'bin'
            bin_dir.mkdir()
            wrapper = bin_dir / 'git'
            wrapper.write_text('#!/bin/bash\nif [[ "$1" == fetch ]]; then exit 0; fi\nexec "$REAL_GIT" "$@"\n')
            wrapper.chmod(0o755)
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'],
                       REAL_GIT=shutil.which('git'), PATCH_REF='fixture-patch')
            env.pop('PATCH_COMMITS', None)
            if override is not None:
                env['PATCH_COMMITS'] = {'runtime': runtime, 'reverse': doc + '\n' + runtime}.get(override, override)
            result = subprocess.run(['bash', str(script), 'v0.2.0'], cwd=repo,
                                    env=env, text=True, capture_output=True)
            commits = git('log', '--reverse', '--format=%B', f'{base}..HEAD')
            count = git('rev-list', '--count', f'{base}..HEAD')
            return result, commits, count, runtime, doc

    def test_default_preserves_full_range_and_provenance(self):
        result, commits, count, runtime, doc = self.run_fixture(None)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('2', count)
        self.assertIn(f'cherry picked from commit {runtime}', commits)
        self.assertIn(f'cherry picked from commit {doc}', commits)

    def test_explicit_selection_skips_docs_and_preserves_caller_order(self):
        result, commits, count, runtime, doc = self.run_fixture('runtime')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('1', count)
        self.assertIn(runtime, commits)
        self.assertNotIn(doc, commits)
        result, commits, count, runtime, doc = self.run_fixture('reverse')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertLess(commits.index(doc), commits.index(runtime))

    def test_invalid_selection_fails_before_creating_disposable_branch(self):
        result, _, count, _, _ = self.run_fixture('not-a-commit')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('2', count)

"""Contract checks for tools/release-comparison.ps1 and ReleaseComparisonHarness. Nothing here runs a benchmark."""
import csv
import hashlib
import re
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "docs/architecture/research/release-comparison-v0.5.7-v0.6.0"
SCRIPT = (ROOT / "tools/release-comparison.ps1").read_text(encoding="utf-8")
HARNESS = (ROOT / "tools/ReleaseComparisonHarness.java").read_text(encoding="utf-8")
CORPUS = RESEARCH / "common-corpus-30-release-vs-v0.6.0.fen"
PUBLISHED_V057_SOURCE = "beaef361d35160d768473af7d2c349719db8508b"
STALE_TAG_COMMIT = "7b5b3a4332193d28a1f668654806fac434f2cb8f"
V060_SOURCE = "bc3dc380e759526e8aa19cd8385af16ab22ac502"


def script_value(name):
    return re.search(r'\$' + name + r'\s*=\s*"([^"]+)"', SCRIPT).group(1)


def git_show(ref, path):
    done = subprocess.run(["git", "show", f"{ref}:{path}"], cwd=ROOT, capture_output=True, text=True)
    return done.stdout if done.returncode == 0 else None


class ReleaseComparisonContract(unittest.TestCase):
    def test_arm_a_is_the_published_source_and_the_stale_tag_is_refused(self):
        self.assertEqual(re.search(r'\$TagA\s*=\s*"([0-9a-f]{40})"', SCRIPT).group(1), PUBLISHED_V057_SOURCE)
        self.assertEqual(re.search(r'\$ExpectedCommitA\s*=\s*"([0-9a-f]{40})"', SCRIPT).group(1), PUBLISHED_V057_SOURCE)
        self.assertEqual(re.search(r'\$ExpectedCommitB\s*=\s*"([0-9a-f]{40})"', SCRIPT).group(1), V060_SOURCE)
        self.assertEqual(script_value("StaleTagCommit"), STALE_TAG_COMMIT)
        self.assertIn("-eq $StaleTagCommit", SCRIPT)
        self.assertIn("-ne $ExpectedCommitA", SCRIPT)

    def test_corpus_hash_count_and_membership(self):
        text = CORPUS.read_text(encoding="utf-8").replace("\r", "")
        self.assertEqual(hashlib.sha256(text.encode("utf-8")).hexdigest(), script_value("CorpusSha256"))
        fens = text.split("\n")[:-1]
        self.assertEqual(len(fens), 30)
        self.assertIn("$CorpusPositions = 30", SCRIPT)
        for ref in (PUBLISHED_V057_SOURCE, "v0.6.0"):
            source = git_show(ref, "engine-uci/src/main/java/coeusyk/game/chess/uci/BenchRunner.java")
            if source is None:
                continue  # shallow clone without that history
            for fen in fens:
                self.assertIn(f'"{fen}"', source, f"{fen} missing from {ref} BenchRunner")

    def test_expected_node_totals_match_the_archived_results(self):
        with open(RESEARCH / "per-position-nodes.tsv", newline="") as f:
            rows = list(csv.DictReader(f, delimiter="\t"))
        a = sum(int(r["nodes_v0.5.7"]) for r in rows)
        b = sum(int(r["nodes_v0.6.0"]) for r in rows)
        self.assertEqual((a, b), (59793662, 20540625))
        self.assertIn("A = 59793662L; B = 20540625L", SCRIPT)

    def test_identity_listings_exist_and_are_wired_in(self):
        for name in ("classes-published-v0.5.7.sha256", "classes-published-v0.6.0.sha256"):
            self.assertTrue((RESEARCH / "identity" / name).is_file())
            self.assertIn(name, SCRIPT)
        self.assertIn("Get-ClassListing", SCRIPT)

    def test_identical_settings_for_both_engines(self):
        self.assertIn("searcher.setContempt(0)", HARNESS)
        self.assertIn("searcher.setTranspositionTableSizeMb(hashMb)", HARNESS)
        self.assertIn('CONFIG depth=', HARNESS)
        self.assertIn("$Depth = 13; $HashMb = 16", SCRIPT)
        self.assertIn("contempt=0 threads=1 evaluator=$($ExpectedEvaluator[$arm])", SCRIPT)
        self.assertEqual(len(re.findall(r"Invoke-Run", SCRIPT)), 4)  # definition, two warm-ups, timed pairs
        # one JVM flag set, shared by both arms
        self.assertEqual(len(re.findall(r'\$jvm = @\(', SCRIPT)), 1)

    def test_failure_handling_and_evidence_preservation(self):
        for needle in ("WaitForExit($TimeoutSec * 1000)", "$p.Kill($true)", "FAILED.txt", "git worktree remove --force",
                       "already exists and is not empty", "git status --porcelain", "-ValidateOnly", "Read-Host"):
            self.assertIn(needle, SCRIPT)
        for var in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "MAVEN_OPTS"):
            self.assertIn(var, SCRIPT)

    def test_order_alternates_from_selected_arm_and_no_speedup_from_nodes(self):
        self.assertIn('[ValidateSet("A", "B")][string]$StartWith = "A"', SCRIPT)
        self.assertIn('$firstArm = $StartWith', SCRIPT)
        self.assertIn('$secondArm = if ($StartWith -eq "A") { "B" } else { "A" }', SCRIPT)
        self.assertIn('foreach ($arm in @($firstArm, $secondArm))', SCRIPT)
        self.assertIn('$firstArm, $secondArm = $secondArm, $firstArm', SCRIPT)
        self.assertIn('measured_start_with = $StartWith', SCRIPT)
        self.assertIn('Log "warm-up A"; Invoke-Run "A" "warmup"', SCRIPT)
        self.assertIn('Log "warm-up B"; Invoke-Run "B" "warmup"', SCRIPT)
        for start, first_pair in (("A", ("A", "B")), ("B", ("B", "A"))):
            first, second = start, first_pair[1]
            pairs = []
            for _ in range(4):
                pairs.append((first, second))
                first, second = second, first
            self.assertEqual(pairs, [first_pair, first_pair[::-1], first_pair, first_pair[::-1]])
        self.assertNotRegex(SCRIPT.lower(), r"speedup\s*=")
        self.assertIn("Not a strength measurement", SCRIPT)

    def test_maven_project_list_is_a_single_argument(self):
        self.assertIn('-pl "engine-core,engine-uci"', SCRIPT)
        self.assertNotRegex(SCRIPT, r"-pl\s+engine-core,\s")

    def test_failed_and_timed_out_runs_keep_their_evidence(self):
        timeout = SCRIPT.index("$timedOut = -not $p.WaitForExit")
        saved = SCRIPT.index("[IO.File]::WriteAllText($tsv", timeout)
        failed = SCRIPT.index('if ($timedOut) { Fail', saved)
        self.assertLess(timeout, saved)
        self.assertLess(saved, failed)
        # environment and build identity are written before the first run, the manifest after the last log line
        self.assertLess(SCRIPT.index('"environment.json"'), SCRIPT.index("Log \"warm-up A\""))
        self.assertLess(SCRIPT.index('Log "done"'), SCRIPT.index("    Write-Manifest   #"))
        start = SCRIPT.index("} catch {")
        catch = SCRIPT[start:SCRIPT.index("} finally {", start)]
        self.assertIn("FAILED.txt", catch)
        self.assertIn("Write-Manifest", catch)

    def test_cleanup_failures_are_reported(self):
        self.assertIn("git worktree remove --force $t 2>&1", SCRIPT)
        self.assertIn("CLEANUP-FAILED.txt", SCRIPT)
        self.assertIn("exit 1", SCRIPT)

    def test_in_process_warmup_is_configured_and_does_not_change_the_search(self):
        self.assertIn("[int]$WarmupDepth = 8", SCRIPT)
        self.assertIn('"$HashMb", "$WarmupDepth"', SCRIPT)
        self.assertIn("warmup_depth=$WarmupDepth", SCRIPT)
        self.assertIn("warmup_depth=", HARNESS)
        # every search, warm-up or timed, builds a fresh Searcher so the transposition table starts empty
        self.assertEqual(HARNESS.count("newSearcher(hashMb)"), 3)

    def test_no_absolute_local_paths(self):
        for text in (SCRIPT, HARNESS, (RESEARCH / "native-run-instructions.md").read_text(encoding="utf-8")):
            self.assertNotRegex(text, r"[A-Za-z]:\\(Users|Tools|Windows)|/home/|/mnt/|/Users/")

    @unittest.skipUnless(shutil.which("pwsh"), "pwsh not installed")
    def test_script_parses(self):
        command = ("$e=$null;$t=$null;[void][System.Management.Automation.Language.Parser]::ParseFile("
                   "'tools/release-comparison.ps1',[ref]$t,[ref]$e);exit $e.Count")
        self.assertEqual(subprocess.run(["pwsh", "-NoProfile", "-Command", command], cwd=ROOT).returncode, 0)


if __name__ == "__main__":
    unittest.main()

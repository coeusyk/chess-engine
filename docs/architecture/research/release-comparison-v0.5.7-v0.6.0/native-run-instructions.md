# Native timing run on RENEGADE

This runs `tools/release-comparison.ps1`: published v0.5.7 (source `beaef361d35160d768473af7d2c349719db8508b`) against v0.6.0 (`bc3dc380e759526e8aa19cd8385af16ab22ac502`), 30 positions at depth 13, Hash 16 MB, one thread, Classical evaluation, Contempt 0. Expect roughly 25 to 35 minutes (an estimate from the WSL2 runs, not measured natively): two builds, two warm-ups, then 14 timed runs.

It measures the elapsed time of fixed-depth search work. It says nothing about playing strength. Do not run it in WSL, and do not start it until the machine is idle.

## 1. Get a clean checkout of the branch

Use a separate worktree so the run starts from a clean tree and does not touch your working checkout (the script refuses to start on a dirty tree).

```powershell
# in your Windows clone of the repository
git fetch origin --tags
git fetch origin research/release-comparison-v0.5.7-v0.6.0
git worktree add ../release-comparison-run FETCH_HEAD
cd ../release-comparison-run
```

Both release commits must exist locally: `git cat-file -t beaef361d35160d768473af7d2c349719db8508b` and `git cat-file -t v0.6.0` should each print a type, not an error.

## 2. Make the machine idle

1. Plug in AC power. Leave the power plan as it is (Stage 5 used Balanced); the script records it.
2. Close browsers, chat, media players (Spotify was running when this was checked), game launchers, IDEs and terminals other than this one.
3. Stop WSL: `wsl --shutdown`. The WSL VM takes CPU even when idle, and it has been running builds for this task.
4. Pause Windows Update and any backup or sync client. Do not use the machine during the run.
5. Wait a couple of minutes after the last of those, then look at Task Manager: total CPU should be at or below 2 to 3 percent.

The script then checks for itself. It refuses to start if a `java`, `javaw`, `mvn` or `javac` process is running, or if mean CPU over 15 seconds exceeds `-MaxIdleCpuPercent` (default 5). It records the mean and maximum, and finally asks you to type `idle`.

## 3. Pre-flight (no build, no timing)

```powershell
& "$env:JAVA_HOME/bin/java" -version     # JAVA_HOME must already point at a JDK 21 (the runs used Azul Zulu 21.0.10)
pwsh -NoProfile -File tools/release-comparison.ps1 -ValidateOnly
```

This should print `ValidateOnly OK` with the two commits, the corpus hash and the Java version. It checks: PowerShell 7 on Windows, run from the repository root, JDK 21, `mvn` on PATH, no `JAVA_TOOL_OPTIONS`/`_JAVA_OPTIONS`/`JDK_JAVA_OPTIONS`/`MAVEN_OPTS`, that arm A resolves to `beaef361...` and not to the stale `v0.5.7` tag commit, that arm B resolves to `bc3dc380...`, the corpus SHA-256 (`3544a8fb...`, with line endings normalized), a clean working tree, and an empty output directory. Fix whatever it reports and run it again.

## 4. Run

The two runs behind the report were made into `tools/results/release-comparison-20261012` and `tools/results/release-comparison-20261012-repeat-bfirst`. Those directories are committed on this branch, so a new run needs new names (each run also needs a clean tree). A-first:

```powershell
pwsh -NoProfile -File tools/release-comparison.ps1 -OutDir tools/results/release-comparison-rerun-a-first
```

Warm-up runs always occur A then B. They do not determine the measured-pair order. Pair 1 starts with `-StartWith` (A by default), and the arms alternate in every pair. The second run started the measured sequence B-first:

```powershell
pwsh -NoProfile -File tools/release-comparison.ps1 -OutDir tools/results/release-comparison-rerun-b-first -StartWith B
```

Each timed run is a fresh JVM that first searches the whole corpus once at depth 8 (`-WarmupDepth`, untimed and discarded) and then times the depth-13 pass, so cold-JIT cost is not in the measured time. This is different from the Stage 5 procedure, which timed a whole cold `--bench` process, so absolute seconds here are not comparable with the Phase 21 Stage 5 numbers. The warm-up does not change any search result: node counts, scores, moves and PVs were identical with and without it on four positions on both JARs. The two discarded full runs still precede the timed pairs, to settle file caches.

Leave the machine alone until it prints the summary. The script stops, with the reason in `FAILED.txt`, if a build fails, a rebuilt JAR differs from the published one class by class, a run exceeds 20 minutes (`-TimeoutSec`), a run reports a different configuration than expected, or a run's node count differs from the recorded total (59,793,662 for v0.5.7 and 20,540,625 for v0.6.0). A failed run is evidence too: its partial output, `environment.json` (written before the first run) and a checksum manifest are kept. Do not delete the directory, and do not reuse it (the script refuses an existing non-empty output directory). If a temporary worktree cannot be removed at the end, the script writes `CLEANUP-FAILED.txt` and exits with code 1; remove the listed worktrees with `git worktree remove --force <path>` before another run.

## 5. Validate the output

In the output directory:

1. `FAILED.txt` must not exist.
2. `run.log` shows both class-listing matches, warm-up A, warm-up B and 14 timed runs (`pair01` to `pair07`, each arm once). `pair01` begins with the arm in `environment.json`'s `measured_start_with` field, and each pair alternates.
3. `runs.csv` has 14 rows. Every A row has `nodes` 59793662 and every B row 20540625.
4. `raw/` holds 16 TSV files (two warm-ups and 14 timed runs) and 16 `.stderr.txt` files. Each stderr file contains a `CONFIG` line: depth 13, hash_mb 16, contempt 0, threads 1, evaluator `Evaluator` (A) or `ClassicalEvaluator` (B), warmup_depth 8.
5. `environment.json` has `class_listing_matches_published: true` for both arms, the right commits, the idle-check numbers, the Java and Maven versions, the power scheme and the hashes of the script, harness and corpus. It contains no local paths.
6. `SHA256SUMS` verifies. In PowerShell:
   `Get-Content SHA256SUMS | ForEach-Object { $h,$f = $_ -split '  ',2; if ((Get-FileHash $f -Algorithm SHA256).Hash.ToLower() -ne $h) { "MISMATCH $f" } }` prints nothing when everything matches.
7. `summary.json` gives median, minimum, maximum and median absolute deviation of elapsed time per arm, median NPS, the median elapsed ratio B over A and the per-pair ratios. Treat the run as noisy if an arm's maximum is more than 5 percent above its minimum, or if the per-pair ratios do not agree to within a few percent. Do not drop runs; repeat the whole procedure in a fresh output directory instead.

Copy the output directory back and the results can be added to the report. Only elapsed times and the ratio are conclusions from this run. The node reduction is a separate, deterministic result, and neither says anything about Elo.

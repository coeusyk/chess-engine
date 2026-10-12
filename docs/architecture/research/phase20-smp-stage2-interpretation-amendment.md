# Phase 20 Stage 2 Interpretation Amendment

## 0. Status and provenance

This is a post-Stage-2 amendment. It was written on 2026-09-24, after the native-Windows Stage 2 run had been recorded in issue #246 and after the Stage 2 stop had fired. It was not preregistered, and nothing in it should be read as if it had been.

The historical record stands as written: **Stage 2 stopped under the original preregistered criterion**, with the classification "environment-limited", and Stage 3 was not started. This document does not change that record. It argues that the stop rule itself was flawed, and it proposes a replacement rule that applies only going forward, and only if this amendment is reviewed and accepted before any Stage 3 execution.

No code, benchmark, game or SPRT was run to write this. The inputs were `phase20-smp-qualification-preregistration.md`, `dev-entries/phase-20.md`, the Stage 2 comment on issue #246, and the harness source in `tools/Phase20Stage2Harness.java` and `tools/phase20-stage2.ps1` (commit `cbdda19`). The raw Stage 2 CSVs live in the native checkout (`tools/results/phase20-stage2/20260924-095910/`) and were not available to this review, so every number below comes from the recorded summary table.

## 1. What Stage 2 established

The run was clean as a measurement. Every one of the 98 worker samples searched exactly 24,780,049 nodes at depth 13, so every arm did identical work. No worker threw. Start skew was at most 4 ms across processes and under 0.05 ms in one JVM, against corpus runtimes of roughly 70 to 110 seconds. Arms were interleaved with a rotating N order, after one discarded warm-up per configuration, as section 6 of the preregistration asked.

Reading the harness confirmed what the numbers mean:

- A worker sample is one full 31-position corpus searched sequentially with a fresh `Searcher` and a private 16 MB TT per position. Per-worker NPS is corpus nodes divided by that worker's own wall time.
- Aggregate NPS is total nodes divided by the makespan, from the earliest worker start to the latest worker finish. It is limited by the slowest worker in the pass.
- The separate-process workers are persistent JVMs reused across passes. The same-JVM workers run on a fixed four-thread pool inside the controller JVM.
- No affinity was set, the power plan was Balanced, and the JVM flags matched the frozen controls.

The recorded values, with two derived columns added. The retention range uses the same definition as the harness's `retentionRange` (lowest N sample over highest 1T sample, up to highest N sample over lowest 1T sample). Aggregate scaling is median aggregate NPS divided by the arm's 1T median.

| Arm | N | Median per-worker NPS | r(N) | Retention range | Aggregate scaling (median, pass range) |
|---|---:|---:|---:|---:|---:|
| Separate processes | 1 | 338,811 | 1.000 | 1T spread 4.9% | 1.00 |
| Separate processes | 2 | 260,831 | 0.770 | 0.642 to 1.103 | 1.53 (1.29 to 2.08) |
| Separate processes | 4 | 286,903 | 0.847 | 0.715 to 1.068 | 3.38 (2.88 to 4.01) |
| Same JVM | 1 | 343,634 | 1.000 | 1T spread 11.3% | 1.00 |
| Same JVM | 2 | 272,137 | 0.792 | 0.652 to 1.097 | 1.58 (1.39 to 2.09) |
| Same JVM | 4 | 274,010 | 0.797 | 0.673 to 1.023 | 3.16 (2.88 to 3.86) |

So Stage 2 established four things.

1. Median per-worker throughput drops to roughly 77 to 85% of 1T when two or four independent searchers run at once, under this machine's default scheduling and power policy.
2. Aggregate throughput still grows a lot. Two workers deliver about 1.5x the work of one, and four deliver about 3.2 to 3.4x.
3. The drop is not a fixed ceiling. Because aggregate NPS is makespan-based, a pass that reaches aggregate A at N workers implies that every worker in that pass ran at least A / N. The best separate-process 2T pass (705,001) therefore had both workers at 352,500 NPS or better, above every separate-process 1T sample. The best 4T pass (1,359,439) had all four workers at 339,860 or better, which is inside the 1T range. The same holds in one JVM (at least 359,109 per worker at 2T and at least 331,809 at 4T). On those passes the machine ran two and four independent searchers with no per-worker loss at all.
4. Separate-process and same-JVM retention ranges overlap at both N, so this run does not show any JVM-specific loss.

## 2. Why the original stop rule was not valid

The stop was applied correctly as the preregistration was executed. The problem is in the rule, and there are three separate faults.

### 2.1 The preregistration contradicts itself on this point

Section 5, Stage 2, "Next" says: "Always go to Stage 3. Stage 2 is the denominator for Stage 3's contention attribution." Its decision text says that if (a) is low, "the ceiling is hardware or OS; record it and don't blame SMP for it." Section 12 lists "JVM or environment limited" as one of several labels that "can combine", and describes its effect as "SMP scaling is then capped by this ceiling, not by the design". That is a label that sits beside later Stage 3 results, not a stop.

Section 13 then says: "Stage 2 shows the platform can't keep throughput at two independent threads: stop with an environment classification."

Section 13 was never operationalized separately, so the execution used the section 12 test (median retention below the 1T spread) as the trigger for the section 13 stop. With two conflicting instructions and only one of them operationalized, that was a defensible reading, and that is why the historical stop stands. It is still a contradiction written into the plan before any data existed, and it is enough on its own to say the rule needs fixing.

### 2.2 The operational test cannot discriminate

"Median per-worker NPS at N is below the 1T observed range" fires on nearly any modern desktop CPU. A single busy core on a Ryzen 7 7700X gets the highest boost clock, the whole 32 MB L3, and uncontended memory bandwidth. Add a second busy core and some of each is lost. With a 1T spread of about 5%, a per-worker loss of more than about 5% fires the rule, and ordinary frequency and shared-cache effects are about that size or larger. A test that almost every machine fails says nothing about whether this machine can support an SMP measurement. It only restates that parallel hardware does not scale perfectly per thread, which is exactly what Stage 2 was supposed to measure, not screen out.

### 2.3 It tested the wrong quantity against the wrong question

Section 13 asks whether the platform can "keep throughput at two independent threads". Throughput at N threads is aggregate work per second. The executed test looked at per-worker retention instead. The two answer different questions:

- Per-worker retention r(N) says how fast each thread runs under load. Stage 3 needs it as a denominator: when a production SMP thread runs at r_shared(N), r(b)(N) says how much of that loss the environment explains before any shared-TT effect.
- Aggregate scaling says whether adding threads adds usable compute at all. That is the only question for which "the platform can't support this" is a sensible answer.

The two have to be read together. A low r(N) with strong aggregate growth is a platform that runs parallel work well but slows each thread. That is expected, it can be measured, and it is what this run shows. A platform that is actually unfit for SMP measurement would show aggregate NPS at N that does not clearly beat 1T, or per-worker behavior so erratic that no ceiling can be stated, or workload failures. None of those happened. Aggregate grew by 1.29x even in the worst 2T pass.

### 2.4 What the rule should have separated

Expected slowdown under concurrent load is a property to record: lower boost at higher active-core counts, shared L3 and memory bandwidth, SMT or scheduler placement, OS noise. It lowers the ceiling that Stage 3 is normalized against and does not stop anything.

An environment failure that invalidates SMP measurement is one where adding workers does not add throughput, where identical workloads stop producing identical node counts, where workers fail or start too far apart to be treated as concurrent, or where the per-worker distribution is too wide to give Stage 3 any usable denominator. The first three are clearly absent. The last is discussed in section 5.

## 3. Evidence that remains trustworthy

- Workload identity. Every sample searched exactly 24,780,049 nodes, so every NPS figure compares identical work.
- The 1T controls. They were measured in the same session and interleaved with the other arms. Their spreads (4.9% for separate processes, 11.3% for same JVM) are the session noise floors.
- The medians and observed ranges in the table above.
- The aggregate-scaling result, and the lower bound on every worker's NPS in the best passes. These follow directly from the makespan definition in the harness.
- The null result on JVM-specific loss. The ranges overlap at both N. That is "not detected at this spread", not "ruled out". At 4T the same-JVM median sits about 5 points below the separate-process median, which is inside the ranges and should not be read as a finding.

Less trustworthy is any claim about why the per-worker loss happens. The run recorded no per-worker core placement, clock frequency, or temperature, so SMT co-scheduling, core parking under the Balanced plan, and boost behavior all remain hypotheses.

## 4. Whether the existing run can serve as the hardware/JVM ceiling

Yes, with two limits that have to be stated wherever it is used.

First, the ceiling is tied to its scheduling policy. It was measured with no affinity, the Balanced plan, and the frozen JVM flags. It is valid as a Stage 3 denominator only if Stage 3 uses the same policy. Production SMP sets no affinity, so matching it is also the representative choice. If Stage 3 were run with affinity or a different power plan, this ceiling would no longer apply and Stage 2 would have to be repeated under that policy, which this amendment does not authorize.

Second, the ceiling carries a known bias. Stage 2 workers each hold a private 16 MB TT, so N workers keep N x 16 MB of TT live: 32 MB at 2T and 64 MB at 4T, against the 7700X's 32 MB shared L3. Production Stage 3 at Hash=16 keeps one shared 16 MB table. Stage 2 therefore puts more cache-capacity pressure on each thread than Stage 3 will, and lacks the coherence traffic Stage 3 will have. The two effects push in opposite directions and neither was measured, but the capacity side means r(b)(N) may be lower than a true no-sharing ceiling at Stage 3's footprint. The practical effect is that r_shared(N) at or above r(b)(N) cannot be read as "no TT contention". Only r_shared(N) below the r(b) range supports a contention claim. The capacity and footprint mismatch cannot be removed with a better Stage 2 design: shrinking each private TT to 16/N MB changes the search tree and breaks the identical-workload property that makes Stage 2 useful. So the bias is recorded rather than fixed.

For Stage 3 the same-JVM arm (b) is the primary ceiling, because production SMP runs its threads in one JVM. The separate-process arm (a) is context for how much of the loss is hardware or OS.

## 5. Treatment of the variance and the 2T/4T non-monotonicity

The per-worker spread at N>1 is wide. At separate-process 2T it runs from 219,206 to 358,533, about 53% of the median, against a 1T spread of 4.9%. The best passes show that low samples are not a steady state of the machine. Something episodic, most likely placement or frequency state under the default scheduler, decides whether a given worker in a given pass runs near 1T speed or at about two thirds of it. Without the raw CSVs, this review cannot tell whether the low samples cluster by pass (machine-wide state) or by worker within a pass (placement).

The non-monotonicity is not a finding. Separate-process r(2) = 0.770 and r(4) = 0.847 come from 14 and 28 samples whose ranges almost fully overlap (0.642 to 1.103 against 0.715 to 1.068). In one JVM the two medians are 0.792 and 0.797. With distributions this wide, the order of the two medians is inside sampling noise. No mechanism should be attached to "4T beats 2T per worker", and no decision below depends on the order.

This variance widens uncertainty. It does not invalidate the run. Nothing about it biases the medians toward one arm: the arms were interleaved, the work was identical, and the same scheduler handled every arm. What it does is reduce power. Under the preregistration's own range-based rule, Stage 3 could only attribute a loss to TT contention if production per-thread retention fell below about 0.65 at 2T or 0.67 at 4T (the lower ends of the same-JVM ranges). Smaller contention effects would go undetected. That limit has to be stated up front, as it is in section 7, so a null H3 result is not over-read later.

## 6. Additional control run

None is required, and none is authorized by this amendment.

The obvious candidate was an affinity-pinned rerun, one worker per physical core, to test whether placement explains the variance. It was rejected for three reasons:

1. No Stage 3 decision depends on why the variance exists. Stage 3 compares production against a ceiling measured under the same policy, and the variance is handled by reading ranges rather than point values.
2. Pinning would measure a policy production does not use. It would then force Stage 3 to be pinned too, which would move Phase 20 away from qualifying the production configuration.
3. Running it now, after seeing an inconvenient Stage 2 result, is the "one more run to see if it firms up" that section 13 of the preregistration forbids.

One non-measurement step is recommended but does not gate anything: tabulate the existing `workers.csv` by pass to see whether low samples cluster by pass or by worker within a pass, and record the result as descriptive context. It uses data that already exists, and no conclusion in this amendment depends on its outcome.

## 7. Revised criterion for whether Stage 3 is interpretable

These criteria replace the section 13 Stage 2 stop, and nothing else in the preregistration.

**Platform fitness.** This decides whether SMP can be measured at all on this platform, and it reads section 13's word "throughput" literally, as aggregate throughput. Stage 2 counts as a platform failure only if any of the following holds:

- the median aggregate NPS at N=2 in either arm is not above that arm's highest 1T sample;
- any worker's node count differs from the frozen corpus total;
- any worker fails or aborts;
- start skew is large enough, relative to corpus runtime, that workers cannot be treated as concurrent.

The existing run fails none of these. Its median 2T aggregates of 517,169 and 543,676 are well above the 1T maxima of 341,570 and 367,148, all node counts matched, no worker failed, and the largest skew was 4 ms. The section 12 label "environment-limited" is kept as a descriptive label: the median r(a)(N) is below the 1T spread at both N. It limits how Stage 3 is read and stops nothing.

**Stage 3 session validity.** Stage 3 must repeat the frozen controls exactly: same machine, Windows build, JDK, JVM flags, Balanced plan, no affinity, Hash=16, PawnHash=1, Classical, and the section 6 protocol of one discarded warm-up and seven interleaved passes. It must run its own interleaved 1T arm. If the median of that same-session 1T arm falls outside the Stage 2 same-JVM 1T observed range (328,472 to 367,148), the session differs from Stage 2 and the Stage 2 ceiling does not transfer. In that case Stage 3 reports its node-based results (section 8) but makes no H3 contention attribution for that session. The range is Stage 2's own observed data, not a new threshold.

**What Stage 3 can claim, stated before it runs:**

- A TT-contention (H3) claim requires the production per-thread retention r_shared(N) to fall below the lower bound of the Stage 2 same-JVM retention range at that N (0.652 at 2T, 0.673 at 4T). r_shared(N) inside or above the range means "H3 not detected at this power", never "H3 ruled out" (see section 4 on bias and section 5 on power).
- A mechanism claim (helpers reduce main-thread work to depth) rests on main-thread nodes to depth, which is described below.
- Wall-clock time-to-depth speedup is reported, and its noise band is the same-session 1T spread. If it falls inside that band while node-based results flag nothing, Stage 3 is inconclusive under the existing section 13 rule.

## 8. How Stage 3 normalizes production SMP against the Stage 2 ceiling

Stage 3's headline metric in the preregistration is main-thread time to depth. That time factors exactly into two parts that Stage 2 separates:

```
TTD_1 / TTD_N  =  (mainNodes_1 / mainNodes_N)  x  (mainNPS_N / mainNPS_1)
                   [search-work factor]           [per-thread speed factor]
```

- The **search-work factor** is the ratio of main-thread nodes to depth at 1T and at N. It measures whether helper TT entries let the main thread reach depth 13 with less work. Node counts remove direct clock/NPS normalization, but they are not scheduler-independent: helper scheduling changes when TT entries become visible and can therefore change the main-thread search tree. Pass-to-pass variation in main-thread nodes-to-depth is part of the Stage 3 mechanism evidence. Report the factor per position and in aggregate with its pass-to-pass spread (SMP node counts are nondeterministic). A factor clearly above 1 means the TT is doing useful work. A factor at or below 1 points toward H4 or H5, which Stages 4 and 5 separate.
- The **per-thread speed factor** is r_shared(N) for the main thread. Its expected value with no SMP-specific cost is r(b)(N) from Stage 2. The comparison against the r(b) range follows section 7. Helpers run at `NORM_PRIORITY - 1` while Stage 2 workers ran at normal priority, so helper-thread NPS in Stage 3 should be reported separately from main-thread NPS rather than pooled.

Stage 3 should also report total work across all threads against Stage 2 aggregate scaling. Total nodes per second across all threads, divided by the same-session 1T NPS, is the production analogue of the Stage 2 aggregate scaling column. If it lands clearly below the Stage 2 same-JVM aggregate range, that establishes additional production-SMP execution overhead; it does not identify TT contention. TT contention remains one possible mechanism and requires the preregistered H3 evidence or later Stage 4 evidence. If aggregate throughput matches while the search-work factor stays near 1, the helpers are running at full speed but their work is wasted, which is the H4/H5 signature the preregistration already describes.

An "environment-adjusted" speedup, TTD speedup divided by the r(b)(N) median, can be shown as a convenience. It is roughly the search-work factor with Stage 2's noise added, so the node ratio itself stays the primary number.

## 9. Anti-post-hoc safeguards

1. **Record kept.** Issue #246, the dev entry, and any Phase 20 report keep "Stage 2 stopped under the original preregistered criterion" as written. Any continuation is described as resuming under this post-Stage-2 amendment.
2. **The argument does not depend on the observed value.** Sections 2.1 to 2.3 rest on the preregistration's text, written before data, and on the fact that a per-worker-below-1T-spread test fires on almost any multi-core machine with boost and a shared cache. They would hold whatever r(N) had been. The observed aggregate numbers are used to show that the replacement criterion is met, not to justify replacing the old one. Readers should still know that this amendment was written after the data was seen.
3. **No thresholds moved.** No numeric threshold is loosened or invented. The platform-fitness test uses Stage 2's own 1T maxima, the session-validity test uses Stage 2's own 1T range, and the H3 bound uses Stage 2's own retention range as the harness already computes it. No percentage is chosen by hand.
4. **No policy changes to improve the result.** No affinity, no power-plan change, no Windows tuning, no JVM flag change, no search change. Stage 3 matches Stage 2's policy exactly.
5. **No Stage 2 reruns.** The existing run is the ceiling. It is not repeated or extended to tighten the ranges.
6. **Frozen before execution.** This amendment must be reviewed, accepted and committed before any Stage 3 timing run, and the Stage 3 record must cite the commit SHA. Every Stage 3 criterion in sections 7 and 8 is fixed now.
7. **No Stage 3 extension.** Section 13 of the preregistration left open whether one extension is allowed for an inconclusive result, to be decided before execution. This amendment decides it: none. An inconclusive Stage 3 stops as inconclusive.
8. **One-sided claims only where the ceiling is biased.** Because of the footprint bias in section 4, a null H3 result is reported as "not detected", never as a clean bill.

## 10. Stop conditions

These add to the section 13 conditions in the preregistration and replace only its Stage 2 condition.

- This amendment is not accepted on review: Phase 20 stays stopped at Stage 2 as recorded, and the phase closes on the environment-limited classification.
- Stage 3 cannot reproduce the Stage 2 policy (machine, OS build, JDK, flags, power plan, no affinity): stop. Do not substitute a different policy and reuse this ceiling.
- Stage 3's same-session 1T median falls outside the Stage 2 same-JVM 1T range: report node-based mechanism results only, make no H3 attribution, and do not rerun the session to get a matching one.
- Any Stage 3 worker node count or depth failure, helper exception, or lifecycle gate regression: stop under the existing section 13 rules.
- Stage 3 inconclusive, meaning the search-work factor does not separate from its pass-to-pass spread, time-to-depth speedup falls inside the same-session 1T band, and no mechanism is flagged: stop as inconclusive, skip Stage 6, no extension.
- Continuing would require affinity, a power-plan change, or any other environment change to become interpretable: stop, record why, and defer to a separately preregistered slice. This amendment does not authorize that slice.
- The existing no-games, no-SPRT, no-production-search-change rules are unchanged.

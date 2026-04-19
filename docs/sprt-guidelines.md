# SPRT Testing Guidelines

## 1. Standard SPRT Usage (single change)

Use the following parameters for testing a single isolated change:

```
H0  = 0 Elo   (null hypothesis: no improvement)
H1  = 50 Elo  (alternative hypothesis: meaningful gain)
α   = 0.05    (5% false-positive rate)
β   = 0.05    (5% false-negative rate)
TC  = 5+0.05  (5-second base + 0.05-second increment)
```

Run via:

```powershell
.\tools\sprt.ps1 -New engine-uci\target\engine-uci.jar -Old tools\engine-uci-<version>.jar
```

**Reading results:**
- `H1 accepted` (LLR ≥ upper bound) — patch improves strength; merge.
- `H0 accepted` (LLR ≤ lower bound) — no improvement detected; investigate.
- Still running — test is inconclusive; wait for cutoff.

---

## 2. Bonferroni Correction for Multiple Simultaneous Hypotheses

When testing multiple independent changes in a single SPRT run, the family-wise error rate (FWER) inflates:

$$\alpha_{\text{FWER}} = 1 - (1 - \alpha)^m$$

For $m = 5$ changes at $\alpha = 0.05$: FWER ≈ 22.6%.

**Fix:** Divide α and β by the number of hypotheses $m$ (Bonferroni correction):

$$\alpha_{\text{adj}} = \frac{\alpha}{m}, \quad \beta_{\text{adj}} = \frac{\beta}{m}$$

**Worked example — m = 5:**

| Parameter | Original | Adjusted (m=5) |
|-----------|----------|----------------|
| α         | 0.05     | 0.01           |
| β         | 0.05     | 0.01           |
| FWER      | 22.6%    | ≤5%            |

Run via:

```powershell
.\tools\sprt.ps1 -New engine-uci\target\engine-uci.jar -Old tools\engine-uci-<version>.jar -BonferroniM 5
```

The script prints a correction notice and passes adjusted α/β to cutechess-cli automatically.

**When to use:** Any time a single SPRT test bundles changes that addressed more than one independent hypothesis (e.g. tuning + search heuristic + eval term in one batch).

---

## 3. H1 Scaling for Batched Tests

If $m$ changes each contribute roughly $\delta$ Elo individually, the combined gain is **sub-additive** due to overlapping heuristics:

$$\text{Combined Elo} \approx \delta \cdot \sqrt{m} \quad \text{(rough lower bound)}$$

**Guideline:** For a batch of $m \approx 5$ changes each expected to contribute ~5 Elo, use:

```
H1 = 15–20 Elo
```

This prevents premature termination at a threshold that only confirms the combined patch is non-trivially positive without diagnosing negative interactions between individual patches.

Do **not** use `H1 = 5` for batched tests — it terminates as soon as the combined effect exceeds 5 Elo but gives no signal about which sub-changes are responsible for any regression.

---

## 4. SPRT is a Sequential Test — Not a Fixed-Sample Test

SPRT terminates as soon as sufficient evidence has accumulated. It is **not** equivalent to a fixed-sample test of $n$ games.

The common formula:

$$n \approx \frac{(z_\alpha + z_\beta)^2 \cdot 2}{\Delta^2}$$

...applies to a fixed-sample design and **does not apply here**. Do not use it to plan game counts.

Vex's 2,000-game SPRT at $H_0 = 0$, $H_1 = 5 \cdot \sqrt{50}$, $\alpha = 0.05$, $\beta = 0.05$ is correctly calibrated for the sequential likelihood-ratio framework. The test may terminate in as few as 400 games for a large effect or take the full 20,000-game cap for a marginal effect. Both outcomes are statistically valid.

**Never truncate an SPRT early** just because a certain game count has been reached. Let the LLR decide.

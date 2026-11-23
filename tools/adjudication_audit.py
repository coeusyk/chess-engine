#!/usr/bin/env python3
"""Independent audit of resign-adjudicated games from a finished Cute Chess SPRT.

Read-only on its inputs. Run it only after the match has finished, on copies of match.log and match.pgn.

  python tools/adjudication_audit.py --pgn match.pgn --log match.log --stockfish <exe> \
      [--per-stratum 25] [--seed 262] [--depth 24] [--out audit-out]

Stratified, reproducible sample: candidate (NEW) wins and losses, split by the candidate's colour and by whether the
winning engine also supported the result, drawn from games whose log reason is "<colour> wins by adjudication".
"both_engines" means the winner's own last 5 reported evaluations were all >= +400 cp (the frozen resign threshold,
applied symmetrically); "loser_only" means at least one was not, or was missing. Cute Chess's one-sided resign rule
only looks at the loser. Every sampled game is replayed with python-chess (this validates the move list and the final
position), the engines' last evaluations are extracted, and the final position is evaluated by an independent engine.
Classes: supported (loser <= -300 cp for the loser per the independent engine), uncertain (-300 < cp <= -150),
potentially_incorrect (> -150). A depth-limited centipawn score is not a proven game-theoretic result. A Syzygy path is
optional (--syzygy); without one the report says so.
"""
import argparse, collections, csv, math, random, re, sys
import chess, chess.engine, chess.pgn


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, c - h), min(1.0, c + h))


def read_log(path):
    fin = {}
    for m in re.finditer(r'^Finished game (\d+) \((\w+) vs (\w+)\): (1-0|0-1|1/2-1/2|\*)\s*(\{[^}]*\})?',
                         open(path, errors='replace').read().replace('\r', ''), re.M):
        fin[int(m.group(1))] = (m.group(2), m.group(3), m.group(4), (m.group(5) or '').strip('{}'))
    return [fin[k] for k in sorted(fin)]  # PGN is written in game-number order


def evaluation(comment):
    """Reported score in centipawns from a Cute Chess move comment, or None. Mate scores map to +-10000."""
    m = re.match(r'\s*([+-]?\d+\.\d+|[+-]?M\d+)/', comment or '')
    if not m:
        return None
    t = m.group(1)
    return (-10000 if t.startswith('-') else 10000) if 'M' in t else int(round(float(t) * 100))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--pgn', required=True); ap.add_argument('--log', required=True)
    ap.add_argument('--stockfish', required=True); ap.add_argument('--syzygy', default='')
    ap.add_argument('--per-stratum', type=int, default=25); ap.add_argument('--seed', type=int, default=262)
    ap.add_argument('--depth', type=int, default=24); ap.add_argument('--threads', type=int, default=1); ap.add_argument('--hash', type=int, default=256)
    ap.add_argument('--out', default='audit-out'); ap.add_argument('--no-engine', action='store_true')
    a = ap.parse_args()

    logged = read_log(a.log)
    games = []
    with open(a.pgn, errors='replace') as fh:
        while (g := chess.pgn.read_game(fh)) is not None:
            games.append(g)
    if len(games) != len(logged):
        sys.exit('PGN games (%d) != log finished games (%d): not a finished, consistent pair' % (len(games), len(logged)))

    strata = collections.defaultdict(list)
    for i, ((w, b, r, reason), g) in enumerate(zip(logged, games)):
        if (g.headers['White'], g.headers['Black'], g.headers['Result']) != (w, b, r):
            sys.exit('log/PGN disagree at game index %d' % i)
        if r == '*' or not reason.endswith('wins by adjudication'):
            continue  # unterminated games and every non-resign ending are out of scope
        cand_white = (w == 'NEW'); white_won = (r == '1-0')
        outcome = 'candidate_win' if white_won == cand_white else 'candidate_loss'
        evs = [evaluation(n.comment) for n in g.mainline()]
        winner_last5 = [evs[j] for j in range(len(evs) - 2, -1, -2)][:5]
        support = 'both_engines' if len(winner_last5) == 5 and all(e is not None and e >= 400 for e in winner_last5) else 'loser_only'
        strata[(outcome, 'NEW_white' if cand_white else 'NEW_black', support)].append(i)

    rng = random.Random(a.seed)
    sample = []
    for key in sorted(strata):
        pool = sorted(strata[key]); rng.shuffle(pool)
        sample += [(key, i) for i in pool[:a.per_stratum]]
    print('resign-adjudicated games per stratum (pool size):', {k: len(v) for k, v in sorted(strata.items())})

    eng = None
    if not a.no_engine:
        eng = chess.engine.SimpleEngine.popen_uci(a.stockfish)
        eng.configure({'Threads': a.threads, 'Hash': a.hash, **({'SyzygyPath': a.syzygy} if a.syzygy else {})})
        print('independent evaluator:', eng.id.get('name'), '| depth', a.depth, '| tablebases:', a.syzygy or 'NONE (limitation)')

    rows = []
    for (outcome, colour, support), i in sample:
        g = games[i]; board = g.board(); evs = []
        for node in g.mainline():
            board.push(node.move)
            evs.append(evaluation(node.comment))
        loser = chess.BLACK if g.headers['Result'] == '1-0' else chess.WHITE
        loser_last5 = [evs[j] for j in range(len(evs) - 1, -1, -2)][:5]
        winner_last5 = [evs[j] for j in range(len(evs) - 2, -1, -2)][:5]
        row = dict(game_index=i + 1, pgn_start=g.headers.get('GameStartTime', ''), stratum_outcome=outcome,
                   stratum_colour=colour, stratum_support=support, plies=len(evs),
                   loser='White' if loser == chess.WHITE else 'Black',
                   loser_last5_cp=' '.join(str(x) for x in loser_last5), winner_last5_cp=' '.join(str(x) for x in winner_last5),
                   final_fen=board.fen(), pieces=chess.popcount(board.occupied), legal_final=board.is_valid())
        if eng:
            info = eng.analyse(board, chess.engine.Limit(depth=a.depth))
            sc = info['score'].pov(loser)
            row['sf_cp_for_loser'] = sc.score(mate_score=100000)
            row['sf_depth'] = info.get('depth')
            row['verdict'] = ('supported' if row['sf_cp_for_loser'] <= -300 else
                              'uncertain' if row['sf_cp_for_loser'] <= -150 else 'potentially_incorrect')
        rows.append(row)
    if eng:
        eng.quit()

    import os
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, 'sample.csv'), 'w', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)
    print('sampled', len(rows), 'games; seed', a.seed)
    if not a.no_engine:
        for key in sorted(strata):
            rs = [r for r in rows if (r['stratum_outcome'], r['stratum_colour'], r['stratum_support']) == key]
            bad = sum(1 for r in rs if r['verdict'] == 'potentially_incorrect'); unc = sum(1 for r in rs if r['verdict'] == 'uncertain')
            lo, hi = wilson(bad, len(rs))
            print('  %-14s %-9s %-12s pool=%4d n=%3d supported=%3d uncertain=%3d potentially_incorrect=%3d  95%% CI [%.1f%%, %.1f%%]' % (
                key[0], key[1], key[2], len(strata[key]), len(rs), len(rs) - bad - unc, unc, bad, 100 * lo, 100 * hi))
        print('Unweighted pooled sample (NOT an estimate for the whole match): n=%d potentially_incorrect=%d uncertain=%d' % (
            len(rows), sum(1 for r in rows if r['verdict'] == 'potentially_incorrect'), sum(1 for r in rows if r['verdict'] == 'uncertain')))


if __name__ == '__main__':
    main()

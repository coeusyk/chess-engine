"""Audit/repair tooling for historical (pre-#263) Stage 1 shards. Runs entirely on
tiny synthetic fixtures -- the real datasets are gitignored research artifacts and are
never touched by tests. Needs no `zstandard` (pure CSV/JSON), so it runs in the
default trainer test invocation.
"""

import hashlib
import json
from pathlib import Path

import pytest

from scripts.repair_stage1_perspective import audit, main, repair

BLACK_A = "4k3/3q4/8/8/8/8/8/4K3 b - -"
BLACK_B = "r5k1/8/8/8/8/8/5PPP/6K1 b - -"
WHITE_A = "4k3/8/8/8/8/8/3Q4/4K3 w - -"
WHITE_B = "6k1/5ppp/8/8/8/8/8/R5K1 w - -"

# Historical shard: White-perspective, so the Black rows carry flipped signs.
HISTORICAL_ROWS = [
    f"{WHITE_A},900,",
    f"{BLACK_A},-900,",      # Black up a queen, recorded White-perspective
    f"{WHITE_B},,1",
    f"{BLACK_B},,-1",        # Black mates in 1, recorded White-perspective
    f"{BLACK_A},350,,17",    # extra `ply` column must survive; mate column empty
    f"{WHITE_A},-20,",
]


def _make_historical(root: Path, name="stage1-lichess", rows=HISTORICAL_ROWS, **manifest_overrides) -> Path:
    d = root / name
    d.mkdir()
    (d / "stage1.csv").write_bytes(("\r\n".join(rows) + "\r\n").encode("ascii"))
    manifest = {
        "dataset_identifier": name,
        "stage": "public",
        "source_url": "https://database.lichess.org/lichess_db_eval.jsonl.zst",
        "output_sha256": hashlib.sha256((d / "stage1.csv").read_bytes()).hexdigest(),
        "trainer_commit": "deadbeef",
        "created_at_epoch_seconds": 1_752_000_000,
        "position_count": len(rows),
    }
    manifest.update(manifest_overrides)
    (d / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return d


def _tree_digest(root: Path) -> dict:
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.rglob("*")) if p.is_file()}


def test_audit_reports_per_side_statistics(tmp_path):
    report = audit(_make_historical(tmp_path))

    assert report["total_rows"] == 6
    assert report["label_perspective_marker"] is None
    white, black = report["white_to_move"], report["black_to_move"]
    assert (white["rows"], black["rows"]) == (3, 3)
    assert white["cp"] == {"count": 2, "mean": 440.0, "positive": 1, "negative": 1, "zero": 0}
    assert white["mate"] == {"count": 1, "positive": 1, "negative": 0, "zero": 0}
    assert black["cp"]["count"] == 2 and black["cp"]["positive"] == 1 and black["cp"]["negative"] == 1
    assert black["mate"] == {"count": 1, "positive": 0, "negative": 1, "zero": 0}
    assert report["shards"]["stage1.csv"]["rows_black_to_move"] == 3


def test_audit_is_read_only_and_deterministic(tmp_path):
    d = _make_historical(tmp_path)
    before = _tree_digest(tmp_path)
    first = json.dumps(audit(d), sort_keys=True)
    second = json.dumps(audit(d), sort_keys=True)
    assert first == second
    assert _tree_digest(tmp_path) == before


def test_repair_flips_only_black_to_move_rows_and_preserves_extra_columns(tmp_path):
    src = _make_historical(tmp_path)
    out = tmp_path / "stage1-lichess-v2"

    report = repair(src, out, "stage1-lichess-v2", confirm_white_perspective=True)

    assert (out / "stage1.csv").read_bytes().decode("ascii").split("\r\n")[:-1] == [
        f"{WHITE_A},900,",
        f"{BLACK_A},900,",
        f"{WHITE_B},,1",
        f"{BLACK_B},,1",
        f"{BLACK_A},-350,,17",
        f"{WHITE_A},-20,",
    ]
    assert report["rows_black_to_move_flipped"] == 3
    assert report["cp_values_negated"] == 2 and report["mate_values_negated"] == 1
    assert report["after"]["black_to_move"]["cp"]["positive"] == 1  # 900 (the -350 row is negative)


def test_repair_never_modifies_the_original_artifacts(tmp_path):
    src = _make_historical(tmp_path)
    before = _tree_digest(src)
    repair(src, tmp_path / "v2", "stage1-lichess-v2", confirm_white_perspective=True)
    assert _tree_digest(src) == before


def test_repair_manifest_is_versioned_and_traceable(tmp_path):
    src = _make_historical(tmp_path)
    original = json.loads((src / "manifest.json").read_text())
    out = tmp_path / "v2"

    repair(src, out, "stage1-lichess-v2", confirm_white_perspective=True)

    manifest = json.loads((out / "manifest.json").read_text())
    assert manifest["dataset_identifier"] == "stage1-lichess-v2"
    assert manifest["label_perspective"] == "mover-relative"
    assert manifest["source_url"] == original["source_url"]
    assert manifest["created_at_epoch_seconds"] == original["created_at_epoch_seconds"]
    assert manifest["output_sha256"] == hashlib.sha256((out / "stage1.csv").read_bytes()).hexdigest()
    provenance = manifest["repaired_from"]
    assert provenance["dataset_identifier"] == "stage1-lichess"
    assert provenance["manifest_sha256"] == hashlib.sha256((src / "manifest.json").read_bytes()).hexdigest()
    assert provenance["shard_sha256"] == {"stage1.csv": original["output_sha256"]}
    assert provenance["rows_black_to_move_flipped"] == 3


def test_repair_is_deterministic(tmp_path):
    src = _make_historical(tmp_path)
    repair(src, tmp_path / "a", "v2", confirm_white_perspective=True)
    repair(src, tmp_path / "b", "v2", confirm_white_perspective=True)
    digest_a = {k: v for k, v in _tree_digest(tmp_path / "a").items()}
    digest_b = {k: v for k, v in _tree_digest(tmp_path / "b").items()}
    assert digest_a == digest_b


def test_repair_requires_explicit_confirmation(tmp_path):
    src = _make_historical(tmp_path)
    with pytest.raises(ValueError, match="confirm"):
        repair(src, tmp_path / "v2", "v2", confirm_white_perspective=False)
    assert not (tmp_path / "v2").exists()


def test_repair_refuses_existing_or_overlapping_output(tmp_path):
    src = _make_historical(tmp_path)
    existing = tmp_path / "exists"
    existing.mkdir()
    (existing / "keep.txt").write_text("precious")
    with pytest.raises(ValueError, match="already exists"):
        repair(src, existing, "v2", confirm_white_perspective=True)
    assert (existing / "keep.txt").read_text() == "precious"

    with pytest.raises(ValueError, match="disjoint"):
        repair(src, src, "v2", confirm_white_perspective=True)
    with pytest.raises(ValueError, match="disjoint"):
        repair(src, src / "nested", "v2", confirm_white_perspective=True)
    with pytest.raises(ValueError, match="disjoint"):
        repair(src, tmp_path, "v2", confirm_white_perspective=True)


def test_repair_refuses_already_normalized_input(tmp_path):
    src = _make_historical(tmp_path, label_perspective="mover-relative")
    with pytest.raises(ValueError, match="already declares"):
        repair(src, tmp_path / "v2", "v2", confirm_white_perspective=True)


def test_repair_of_a_repaired_dataset_is_refused(tmp_path):
    src = _make_historical(tmp_path)
    repair(src, tmp_path / "v2", "v2", confirm_white_perspective=True)
    with pytest.raises(ValueError, match="already declares"):
        repair(tmp_path / "v2", tmp_path / "v3", "v3", confirm_white_perspective=True)


def test_repair_refuses_non_stage1_manifest(tmp_path):
    src = _make_historical(tmp_path, stage="sf-labeled")
    with pytest.raises(ValueError, match="Stage 1"):
        repair(src, tmp_path / "v2", "v2", confirm_white_perspective=True)


def test_repair_requires_a_new_dataset_identifier(tmp_path):
    src = _make_historical(tmp_path)
    with pytest.raises(ValueError, match="differ"):
        repair(src, tmp_path / "v2", "stage1-lichess", confirm_white_perspective=True)


def test_missing_manifest_or_shards_is_rejected(tmp_path):
    empty = tmp_path / "empty"
    empty.mkdir()
    with pytest.raises(ValueError, match="manifest"):
        audit(empty)
    (empty / "manifest.json").write_text(json.dumps({"stage": "public"}))
    with pytest.raises(ValueError, match="no \\*.csv shards"):
        audit(empty)


def test_malformed_row_aborts_without_leaving_partial_output(tmp_path):
    src = _make_historical(tmp_path, rows=[f"{WHITE_A},10,", "4k3/8/8/8/8/8/8/4K3 x - -,5,"])
    out = tmp_path / "v2"
    with pytest.raises(ValueError, match="side to move"):
        repair(src, out, "v2", confirm_white_perspective=True)
    assert not out.exists()


def test_multi_shard_directories_are_repaired_shard_by_shard(tmp_path):
    src = _make_historical(tmp_path, rows=[f"{BLACK_A},-100,"])
    (src / "part2.csv").write_bytes(f"{BLACK_B},,-3\r\n{WHITE_A},5,\r\n".encode("ascii"))
    out = tmp_path / "v2"

    repair(src, out, "v2", confirm_white_perspective=True)

    assert (out / "stage1.csv").read_bytes() == f"{BLACK_A},100,\r\n".encode("ascii")
    assert (out / "part2.csv").read_bytes() == f"{BLACK_B},,3\r\n{WHITE_A},5,\r\n".encode("ascii")
    manifest = json.loads((out / "manifest.json").read_text())
    assert set(manifest["shard_sha256"]) == {"stage1.csv", "part2.csv"}
    assert "output_sha256" not in manifest


def test_cli_audit_and_repair(tmp_path, capsys):
    src = _make_historical(tmp_path)
    report_path = tmp_path / "audit.json"
    assert main(["audit", str(src), "--json", str(report_path)]) == 0
    assert json.loads(report_path.read_text())["total_rows"] == 6
    with pytest.raises(SystemExit):
        main(["audit", str(src), "--json", str(report_path)])  # refuses to overwrite

    with pytest.raises(ValueError, match="confirm"):
        main(["repair", str(src), str(tmp_path / "v2"), "v2"])
    assert main(["repair", str(src), str(tmp_path / "v2"), "v2", "--confirm-white-perspective"]) == 0
    assert "repaired 3" in capsys.readouterr().out


def test_repair_preserves_lf_line_endings_and_two_column_rows(tmp_path):
    src = tmp_path / "lf-shard"
    src.mkdir()
    rows = f"{WHITE_A},100\n{BLACK_A},-250\n"
    (src / "stage1.csv").write_bytes(rows.encode("ascii"))
    (src / "manifest.json").write_text(json.dumps({
        "dataset_identifier": "lf-v1",
        "stage": "public",
        "trainer_commit": "deadbeef",
        "created_at_epoch_seconds": 1_752_000_000,
    }))
    out = tmp_path / "lf-v2"

    repair(src, out, "lf-v2", confirm_white_perspective=True)

    assert (out / "stage1.csv").read_bytes() == f"{WHITE_A},100\n{BLACK_A},250\n".encode("ascii")


def test_repair_rejects_missing_created_at_epoch_seconds_before_creating_output(tmp_path):
    src = tmp_path / "no-ts"
    src.mkdir()
    (src / "stage1.csv").write_bytes(f"{WHITE_A},10,\n".encode("ascii"))
    (src / "manifest.json").write_text(json.dumps({
        "dataset_identifier": "no-ts",
        "stage": "public",
        "trainer_commit": "deadbeef",
    }))
    out = tmp_path / "no-ts-v2"

    with pytest.raises(ValueError, match="created_at_epoch_seconds"):
        repair(src, out, "no-ts-v2", confirm_white_perspective=True)
    assert not out.exists()

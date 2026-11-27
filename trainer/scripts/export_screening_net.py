"""Export one checkpoint as a screening network, with identity and loader checks (#265, plan #329).

    python -m scripts.export_screening_net CKPT.pt --out-dir DIR [--engine-jar JAR] [--java CMD]
        [--allow-duplicate REASON] [--trust-checkpoint]

A thin composition of the existing pieces: `checkpoint_to_canonical`, the production `quantize()` (through
`compute_quantization_parity`, so the exported network is exactly the one measured), the public `export()`,
`read_nnue`, `assert_java_loadable` and the pre-flight in `tools/nnue_preflight.py`. It has no quantization or
byte-format code of its own, and `export()` and the manifest are unchanged. It writes, next to `<uuid>.nnue`
and `<uuid>.json`, a sidecar `<uuid>.screening.json` with the checkpoint hash, the float and network content
identities, the file hash, the parity and scale-utilization measurements and the hard gates.

Hard gates (each pass or fail, none statistical; `not run` is never counted as a pass):
  identity          the network read back from the file equals the quantized network array for array and
                    scalar for scalar, the header uuid equals the manifest uuid, and the file's SHA-256 equals
                    the manifest's `nnue_sha256`;
  loader_safety     `assert_java_loadable` passes (the worst-case accumulator bound the Java loader enforces),
                    and with `--engine-jar` the real strict engine loads the file through the pre-flight with
                    the pinned uuid and hash.
Exact Java-versus-Python parity and orientation are checked by `screen_stage0.py` and are recorded as
`pending-stage0` here. A network is not called Java-verified until they have passed.

Float-versus-quantized parity and scale utilization are measurements, not gates.

Exit status: 0 every gate that ran passed, 2 an identical network was already exported to this directory
(refused unless `--allow-duplicate REASON`, which is recorded), 3 a hard gate failed (the files are kept for
inspection and the sidecar says `failed`; a network that fails `assert_java_loadable` is not exported at all).
Records hold base names only, never a local path.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Sequence

import numpy as np

from scripts.audit_checkpoint_quantization import default_corpus, file_sha256, load_checkpoint
from trainer.encoding.feature_spec import repo_root
from trainer.export.canonical import QuantizedCanonicalNetwork, checkpoint_to_canonical
from trainer.export.exporter import export
from trainer.export.identity import network_content_sha256, tensor_sha256
from trainer.export.nnue_reader import read_nnue
from trainer.quantization.inference import assert_java_loadable
from trainer.reproducibility.experiment_metadata import ExperimentMetadata
from trainer.validation.quantization_parity import compute_quantization_parity
from trainer.validation.scale_utilization import compute_scale_utilization

SIDECAR_SUFFIX = ".screening.json"
_SCALARS = ("hidden_width", "output_bias", "qa", "qb", "output_scale", "architecture_id", "feature_set_id")


def _networks_equal(a: QuantizedCanonicalNetwork, b: QuantizedCanonicalNetwork) -> bool:
    return (all(getattr(a, f) == getattr(b, f) for f in _SCALARS)
            and all(np.array_equal(getattr(a, f), getattr(b, f)) and getattr(a, f).dtype == getattr(b, f).dtype
                    for f in ("ft_weights", "ft_biases", "output_weights")))


def _existing_exports(out_dir: Path) -> Dict[str, str]:
    """network_content_sha256 -> sidecar file name, for every screening export already in `out_dir`."""
    found: Dict[str, str] = {}
    for sidecar in sorted(Path(out_dir).glob(f"*{SIDECAR_SUFFIX}")):
        try:
            found.setdefault(json.loads(sidecar.read_text(encoding="utf-8"))["network_content_sha256"], sidecar.name)
        except (OSError, ValueError, KeyError):
            continue
    return found


def _load_preflight():
    path = repo_root(Path(__file__).resolve()) / "tools" / "nnue_preflight.py"
    spec = importlib.util.spec_from_file_location("nnue_preflight", path)
    if spec is None or spec.loader is None:
        raise SystemExit("tools/nnue_preflight.py is missing: the engine loading check cannot run")
    module = importlib.util.module_from_spec(spec)
    sys.modules.setdefault("nnue_preflight", module)
    spec.loader.exec_module(module)
    return module


def engine_loading_gate(jar: Path, java: str) -> Callable[[Path, str, str], Dict[str, Any]]:
    """The real strict-engine check: the pre-flight with the pinned uuid and file hash."""
    pre = _load_preflight()

    def gate(nnue_path: Path, uuid: str, sha256: str) -> Dict[str, Any]:
        config = pre.EngineConfig("SCREEN", java, ("--add-modules", "jdk.incubator.vector", "-jar", str(Path(jar).resolve())),
                                  (("EvalType", "NNUE"), ("EvalFile", str(Path(nnue_path).resolve())), ("StrictNnue", "true")))
        try:
            return pre.preflight(config, pre.Pins(uuid, sha256))
        except pre.PreflightError as error:
            raise RuntimeError(f"status {error.code}: {error}") from error

    return gate


def _atomic_write(path: Path, text: str) -> None:
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=path.name, suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(text)
    os.replace(tmp, path)


def export_screening_net(checkpoint_path: Path, out_dir: Path, *, trust_checkpoint: bool = False,
                         engine_gate: Optional[Callable[[Path, str, str], Dict[str, Any]]] = None,
                         allow_duplicate_reason: Optional[str] = None,
                         fens: Optional[Sequence[str]] = None, fen_sources: Optional[Sequence[str]] = None) -> Dict[str, Any]:
    """Returns a result dict: `exit_status` plus, when something was exported, the sidecar content."""
    checkpoint_path, out_dir = Path(checkpoint_path), Path(out_dir)
    if fens is None:
        fens, fen_sources = default_corpus()
    checkpoint = load_checkpoint(checkpoint_path, trust_checkpoint)
    canonical = checkpoint_to_canonical(checkpoint)
    recorded = checkpoint.get("experiment_metadata")
    if not recorded:
        return {"exit_status": 3, "error": "the checkpoint records no experiment_metadata, which export() requires"}

    parity, quantized = compute_quantization_parity(canonical, fens)
    content_hash = network_content_sha256(quantized)
    try:
        assert_java_loadable(quantized)
    except ValueError as error:
        return {"exit_status": 3, "exported": False, "hard_gates": {"loader_safety": "failed"}, "error": str(error)}

    out_dir.mkdir(parents=True, exist_ok=True)
    existing = _existing_exports(out_dir).get(content_hash)
    if existing is not None and not allow_duplicate_reason:
        return {"exit_status": 2, "exported": False, "duplicate_of": existing, "network_content_sha256": content_hash}

    utilization = compute_scale_utilization(canonical, list(fens)[:5000])
    result = export(quantized, ExperimentMetadata(**recorded), [], out_dir,
                    quantization_parity=parity.to_manifest_dict(), scale_utilization=utilization.to_manifest_dict())
    manifest = json.loads(result.manifest_path.read_text(encoding="utf-8"))
    nnue_sha = file_sha256(result.nnue_path)

    gates: Dict[str, Any] = {"java_python_parity": "pending-stage0", "orientation": "pending-stage0"}
    read_back, header_uuid = read_nnue(result.nnue_path)
    gates["identity"] = "pass" if (_networks_equal(read_back, quantized) and header_uuid == result.network_uuid
                                   and manifest.get("network_uuid") == result.network_uuid
                                   and manifest.get("nnue_sha256") == nnue_sha) else "failed"
    gates["loader_safety_python"] = "pass"
    engine_record: Optional[Dict[str, Any]] = None
    if engine_gate is None:
        gates["loader_safety_engine"] = "not run"
    else:
        try:
            engine_record = engine_gate(result.nnue_path, result.network_uuid, nnue_sha)
            gates["loader_safety_engine"] = "pass"
        except RuntimeError as error:
            gates["loader_safety_engine"] = f"failed: {error}"

    sidecar = {
        "schema": "screening-sidecar/1",
        "uuid": result.network_uuid,
        "nnue_file": result.nnue_path.name,
        "nnue_sha256": nnue_sha,
        "source_checkpoint": checkpoint_path.name,
        "source_checkpoint_sha256": file_sha256(checkpoint_path),
        "tensor_sha256": tensor_sha256(canonical),
        "network_content_sha256": content_hash,
        "trainer_commit": recorded.get("trainer_commit"),
        "training_seed": recorded.get("seed"),
        "training_data": "not recorded in the checkpoint",
        "quantization_parity": parity.to_manifest_dict(),
        "scale_utilization": utilization.to_manifest_dict(),
        "fen_sources": list(fen_sources or []),
        "hard_gates": gates,
        "engine_preflight": engine_record,
        "duplicate_override_reason": allow_duplicate_reason if existing is not None else None,
        "duplicate_of": existing,
        "result": "failed" if any(str(v).startswith("failed") for v in gates.values()) else "exported",
    }
    _atomic_write(out_dir / f"{result.network_uuid}{SIDECAR_SUFFIX}", json.dumps(sidecar, indent=2, sort_keys=True) + "\n")
    sidecar["exit_status"] = 3 if sidecar["result"] == "failed" else 0
    sidecar["exported"] = True
    return sidecar


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--engine-jar", type=Path, default=None)
    parser.add_argument("--java", default="java")
    parser.add_argument("--allow-duplicate", metavar="REASON", default=None)
    parser.add_argument("--trust-checkpoint", action="store_true")
    args = parser.parse_args(argv)

    gate = engine_loading_gate(args.engine_jar, args.java) if args.engine_jar else None
    result = export_screening_net(args.checkpoint, args.out_dir, trust_checkpoint=args.trust_checkpoint, engine_gate=gate,
                                  allow_duplicate_reason=args.allow_duplicate)
    status = result["exit_status"]
    if status == 2:
        print(f"refused: this network was already exported to the directory ({result['duplicate_of']}); "
              f"pass --allow-duplicate REASON to export it again", file=sys.stderr)
        return 2
    if not result.get("exported"):
        print(f"not exported: {result.get('error')}", file=sys.stderr)
        return 3
    print(json.dumps({k: result[k] for k in ("uuid", "nnue_sha256", "network_content_sha256", "hard_gates", "result")}, indent=2, sort_keys=True))
    if result["hard_gates"]["loader_safety_engine"] == "not run":
        print("note: the engine loading check did not run (no --engine-jar); no Java loadability is claimed", file=sys.stderr)
    return status


if __name__ == "__main__":
    sys.exit(main())
